package edu.berkeley.veloxms.resources.internal

import javax.servlet.http.{HttpServletResponse, HttpServletRequest, HttpServlet}

import com.codahale.metrics.Timer
import edu.berkeley.veloxms.models.Model
import edu.berkeley.veloxms._
import edu.berkeley.veloxms.util.Logging
import org.apache.hadoop.conf._
import org.apache.hadoop.fs._
import org.apache.commons.io.IOUtils
import java.io.StringWriter

import org.apache.spark.{SparkContext, SparkConf}

case class HDFSLocation(loc: String)
case class LoadModelParameters(userWeightsLoc: String, version: Version)

class WriteToHDFSServlet(
    model: Model[_, _],
    timer: Timer,
    sparkContext: SparkContext,
    sparkDataLocation: String,
    partition: Int) extends HttpServlet with Logging {

  override def doPost(req: HttpServletRequest, resp: HttpServletResponse) {
    val timeContext = timer.time()
    try {
      val obsLocation = jsonMapper.readValue(req.getInputStream, classOf[HDFSLocation])
      val uri = s"$sparkDataLocation/${obsLocation.loc}/part_$partition"

      val observations = model.getObservationsAsRDD(sparkContext)
      observations.saveAsObjectFile(uri)

      resp.setContentType("application/json")
      jsonMapper.writeValue(resp.getOutputStream, "success")
    } finally {
      timeContext.stop()
    }
  }

}


class LoadNewModelServlet(model: Model[_, _], timer: Timer, sparkContext: SparkContext, sparkDataLocation: String)
    extends HttpServlet with Logging {

  override def doPost(req: HttpServletRequest, resp: HttpServletResponse) {
    val timeContext = timer.time()
    val modelLocation = jsonMapper.readValue(req.getInputStream, classOf[LoadModelParameters])
    try {
      val uri = s"$sparkDataLocation/${modelLocation.userWeightsLoc}"

      // TODO only add users in this partition: if (userId % partNum == 0)
      val users = sparkContext.textFile(s"$uri/users/*").map(line => {
        val userSplits = line.split(", ")
        val userId = userSplits(0).toLong
        val userFeatures: Array[Double] = userSplits.drop(1).map(_.toDouble)
        // TODO this should be inserted into storage backend
        (userId, userFeatures)
      }).collect().toMap

      val firstUser = users.head
      logInfo(s"Loaded new models for ${users.size} users. " +
        s"First one is:\n${firstUser._1}, ${firstUser._2.mkString(", ")}")


      // TODO: Should make sure it's sufficiently atomic
      model.writeUserWeights(users, modelLocation.version)
      model.useVersion(modelLocation.version)

      resp.setContentType("application/json");
      jsonMapper.writeValue(resp.getOutputStream, "success")

    } finally {

      timeContext.stop()
    }
  }
}

