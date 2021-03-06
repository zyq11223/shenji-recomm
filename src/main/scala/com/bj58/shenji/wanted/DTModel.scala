package com.bj58.shenji.wanted

import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD

import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.classification.LogisticRegressionWithSGD
import org.apache.spark.mllib.tree.DecisionTree

import org.apache.spark.SparkConf
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import org.dmg.pmml.True

import com.bj58.shenji.data.Position
import com.bj58.shenji.util
import com.bj58.shenji.data.Enterprise
import org.apache.spark.mllib.classification.LogisticRegressionModel
import org.jboss.netty.util.internal.ConcurrentHashMap
import java.util.Map.Entry
import org.apache.spark.mllib.classification.SVMWithSGD
import java.util.Date

/**
 * 决策树回归模型
 * @author jiangzhenxing
 */
object DTModel extends Serializable 
{
  val logger = LoggerFactory.getLogger("DTModel")
  val minPartitions = 24  // 最小分区数，对于计算密集型任务，可设为集群CPU核数
  
  def train(sc: SparkContext, cookiePath: String, dtoutput: String) =
  {
      val sep = "\t"
      val testCookies = sc.textFile(cookiePath).collect
      val userJobCates = util.userJobcates(sc)
      val userLocals = util.userLocals(sc)
      // (cookieid,0),(userid,1),(infoid,2),(clicktag,3),(clicktime,4),(userid,5),(scate1,6),(scate2,7),(scate3,8),(title,9),(local,10),
      // (salary,11),(education,12),(experience,13),(trade,14),(enttype,15),(fuli,16),(fresh,17),(additional,18)
      val accum = sc.accumulator(0, "SUCESS_COUNT")

      testCookies.foreach { cookieid =>
        println("train " + cookieid + " begin")
        val jobCates = userJobCates.getOrElse(cookieid, Array())
        val locals = userLocals.getOrElse(cookieid, Array())
        trainUser(sc, cookieid, jobCates, locals, dtoutput)
        accum.add(1)
        println(cookieid + " completed @" + new Date)
      }
  }
  
  /**
   * 对单个用户进行训练
   */
  def trainUser(sc: SparkContext, 
                cookieid: String, 
                jobcates: Array[String], 
                locals: Array[String], 
                dtoutput: String) =
  {
    try {
          val train_data = sc.textFile("/home/team016/middata/stage2/traindatabyuser_split/train80/" + cookieid, minPartitions)
          // 5 + 23 + 21 detail:0-5; position:5-28; enterprise:28-49
          val rawdatas = train_data.map(_.split("\001"))
                                   .map { values => 
                                           val action = values(3)
                                           val position = Position(values.slice(5,28))
                                           val enterprice = Enterprise(values.slice(28, 49))
                                           position.enterprise = enterprice
                                           (action, position) }

          val actionCount = rawdatas.map { case (action, position) => (action, 1) }.reduceByKey(_ + _).collectAsMap
          val bjobcates = sc.broadcast(jobcates)
          val blocals = sc.broadcast(locals)
          
          try {
            val (base1, base2) = needBaseCountDT(actionCount)
            val dtdatas = rawdatas.flatMap { case (action, position) => 
                            labeledPointsDT(action, position, blocals.value, bjobcates.value, base1, base2) }.cache // .sortBy(_(4).toLong)
            DecisionTree.trainRegressor(input=dtdatas, categoricalFeaturesInfo=Map[Int, Int](), impurity="variance", maxDepth=10, maxBins=36)
                        .save(sc, dtoutput + "/" + cookieid) // 9:0.6448457311761356
            
            dtdatas.unpersist(false)
          } finally {
            bjobcates.destroy()
            blocals.destroy()
          }
        } catch {
          case t: Throwable => logger.error(cookieid, t)
        }
  }
  
  def labeledPoints(action: String, position: Position, 
                    locals: Array[String], jobcates: Array[String], 
                    cmcLocal: scala.collection.Map[String, String], needBase: Int) =
  {
    val features = Vectors.dense(position.lrFeatures(locals, jobcates))
    // 查看电话seetel、在线交谈message、立即申请apply 点击记为1，展现记为0
    action match {
      case "seetel" => Range(0,needBase * 2).map(x => LabeledPoint(1, features)).toArray    // 100
      case "message" => Range(0,needBase * 2).map(x => LabeledPoint(1, features)).toArray
      case "apply" => Range(0,needBase * 2).map(x => LabeledPoint(1, features)).toArray
      case "1" => Range(0,needBase).map(x => LabeledPoint(1, features)).toArray        //40
      case "0" => Array(LabeledPoint(0, features))
      case _ => Array(LabeledPoint(0, features))
    }
  }
  
  def labeledPointsDT(action: String, position: Position, 
                      locals: Array[String], jobcates: Array[String], 
                      base1: Int, base2: Int) =
  {
    val features = Vectors.dense(position.lrFeatures(locals, jobcates))
    // 查看电话seetel、在线交谈message、立即申请apply记为2 点击记为1，展现记为0
    action match {
      case "seetel" => Range(0, base2).map(x => LabeledPoint(2, features)).toArray    // 100
      case "message" => Range(0, base2).map(x => LabeledPoint(2, features)).toArray
      case "apply" => Range(0, base2).map(x => LabeledPoint(2, features)).toArray
      case "1" => Range(0, base1).map(x => LabeledPoint(1, features)).toArray        //40
      case "0" => Array(LabeledPoint(0, features))
      case _ => Array(LabeledPoint(0, features))
    }
  }
  
  
  def needBaseCount(actionCount: scala.collection.Map[String, Int]) =
  {
    val seetelCount = actionCount.getOrElse("seetel", 0).doubleValue
    val messageCount = actionCount.getOrElse("message", 0).doubleValue
    val applyCount = actionCount.getOrElse("apply", 0).doubleValue
    
    val deliveryCount = (seetelCount + messageCount + applyCount).doubleValue // 这部分相当于投递数量
    val clickCount = actionCount.getOrElse("1", 0).doubleValue  // 点击数量
    val noCount = actionCount.getOrElse("0", 0).doubleValue     // 末点击数量
    
    val actionTotal = (deliveryCount + clickCount)  // 正样本数量 = 投递数量 + 点击数量
    val needNum = noCount - actionTotal             // 需要过分抽样的数量
    
    // 过分抽样，让正负样本的数量相当
    val needBase = (if (deliveryCount == 0) math.round(needNum / actionTotal).intValue else math.floor(needNum / actionTotal).intValue) + 1
    needBase
  }
  
  def needBaseCountDT(actionCount: scala.collection.Map[String, Int]) =
  {
    val seetelCount = actionCount.getOrElse("seetel", 0).doubleValue
    val messageCount = actionCount.getOrElse("message", 0).doubleValue
    val applyCount = actionCount.getOrElse("apply", 0).doubleValue
    
    val deliveryCount = (seetelCount + messageCount + applyCount).doubleValue // 这部分相当于投递数量
    val clickCount = actionCount.getOrElse("1", 0).doubleValue  // 点击数量
    val notClickCount = actionCount.getOrElse("0", 0).doubleValue     // 末点击数量
    
    val base1 = if (notClickCount > 0) math.round((notClickCount - clickCount) / clickCount).intValue() else 1
    val base2 = if (deliveryCount > 0) math.round((notClickCount - deliveryCount) / deliveryCount).intValue() else 1
    
    (base1, base2)
  }
}