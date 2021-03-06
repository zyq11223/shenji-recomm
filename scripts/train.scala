import scala.collection.Map
import scala.io._

import java.io._

import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.classification.LogisticRegressionWithSGD
import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics
import org.apache.spark.mllib.tree.DecisionTree
import org.apache.spark.mllib.classification.SVMWithSGD

import com.bj58.shenji.data.Position
import com.bj58.shenji.util._

  def position(values: Array[String]) = 
  {
    Position(infoid = values(2),
						 scate1 = values(6),
						 scate2 = values(7),
						 scate3 = values(8),
						 title = values(9),
						 userid = values(5),
						 local = values(10),
						 salary = values(11),
						 education = values(12),
						 experience = values(13),
						 trade = values(14),
						 enttype = values(15),
						 fresh = values(17),
						 fuli = values(16),
						 highlights = "",
						 additional = values(18)
						 )
  }

  def labeledPoints(action: String, position: Position, locals: Array[String], jobcates: Array[String], cmcLocal: scala.collection.Map[String, String], actionCount: Map[String, Int]) =
  {
    val features = Vectors.dense(position.lrFeatures(locals, jobcates, cmcLocal))
//    println("features.size: " + features.size)
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
    
    // 查看电话seetel、在线交谈message、立即申请apply 点击记为1，展现记为0
    action match {
      case "seetel" => Range(0,needBase * 10).map(x => LabeledPoint(1, features)).toArray    // 100
      case "message" => Range(0,needBase * 10).map(x => LabeledPoint(1, features)).toArray
      case "apply" => Range(0,needBase * 10).map(x => LabeledPoint(1, features)).toArray
      case "1" => Range(0,needBase).map(x => LabeledPoint(1, features)).toArray        //40
      case "0" => Array(LabeledPoint(0, features))
      case _ => Array(LabeledPoint(0, features))
    }
  }
  
  def labeledPoints2(label: Double, features: Array[Double]) =
  {
    // 查看电话seetel、在线交谈message、立即申请apply 点击记为1，展现记为0
    label match {
      case 2 => Range(0,100).map(x => LabeledPoint(1, Vectors.dense(features))).toArray
      case 1 => Range(0,40).map(x => LabeledPoint(1, Vectors.dense(features))).toArray
      case 0 => Array(LabeledPoint(0, Vectors.dense(features)))
    }
  }

  def labeledPoint(values: Array[String], locals: Array[String], jobcates: Array[String], cmcLocal: scala.collection.Map[String, String]) =
  {
    val p = position(values)
    val features = Vectors.dense(p.lrFeatures(locals, jobcates, cmcLocal))
    
    // 查看电话seetel、在线交谈message、立即申请apply 点击记为1，展现记为0
    val label = values(3) match {
      case "seetel" => 1.0
      case "message" => 1.0
      case "apply" => 1.0
      case "1" => 1.0
      case "0" => 0.0
      case _ => 0.0
    }
    
    LabeledPoint(label, features)
  }
  
val cookies = "m1NfUhbQujboiZKAEM0zNY7OUYVKuk, m1NfUh3QuhR2NWNduDqWi7uWmdFKuk, m1NfUhbQubPhUbG5yWKpPYFn07FKuk, yb0Qwj7_uRRC2YIREycfRM-jm17ZIk, HZGNrH7_u-FHn7I2rytdEhQsnNOaIk, w-RDugRAubGPNLFWmYNoNgPJnAqvNE, uvVYENdyubQVuRw8pHwuEN65PLKOIk, njRWwDuARMmo0A6amNqCuDwiibRKuk, RDqMHZ6Ay-ufNRwoi1wFpZKFU7uhuk, m1NfUMnQu-PrmvqJP-PEiY7LIHPKuk, pvG8ihRAmWFiP17JpRcdwg7Y0LDYNE, m1NfUh3QuhcYwNuzyAt30duwXMPKuk, UvqNu7K_uyIgyWR60gDvw7GjPA6GNE, NDwwyBqyugRvuDOOE1EosdR3ERRdNE, m1NfUh3QuA_oIR73N-E30DPlRh6Kuk, RNu7u-GAm1Nd0vF3rNI7RWK8IZK_EE, m1NfUMK_mv_OEy7VnL0OpYndPd6Kuk, m1NfUh3Qu-PgnMw701FpmREvIZ6Kuk, uA-ZPD-AuHP2rAF_Pv-oIY_1w1FNNE".split(", ").toSet
val userLocals = Source.fromFile(new File("data/user_locals2")).getLines().map(_.split("\001")).filter(values => cookies.contains(values(0))).map(values => (values(0), values(1).split(";"))).toMap
val userJobCates = Source.fromFile(new File("data/user_job_cates")).getLines().map(_.split("\001")).filter(values => cookies.contains(values(0))).map(values => (values(0), values(1).split(";"))).toMap
val cmcLocal = Map[String, String]() // Source.fromFile(new File("data/cmc_local")).getLines().map(_.split("\t")).map(values => (values(0), values(1))).toMap

var max_auc = 0d
var iter = 0


var total_auc = 0d
var count = 0

val scores = cookies.map { cookieid =>
  //val cookieid = "m1NfUhbQujboiZKAEM0zNY7OUYVKuk"
  val trainDatas = sc.textFile("data/userdata/train/" + cookieid)
  val validData = sc.textFile("data/userdata/valid/" + cookieid)
  val testData = sc.textFile("data/userdata/test/" + cookieid)
  
  val rawdatas = trainDatas.union(validData).map(_.split("\t")).map(values => (values(3), position(values)))
  val actionCount = rawdatas.map { case (action, position) => (action,1) }.reduceByKey(_ + _).collectAsMap
  //println(actionCount)
  
  //val firstCate3 = rawdatas.filter(values => values(3) == "1" && values(8) != "-").first()(8)
  //val bcate3 = sc.broadcast(firstCate3)
  val locals = userLocals.getOrElse(cookieid, Array())
  val jobcates = userJobCates.getOrElse(cookieid, Array())
  
  val datas = rawdatas.flatMap { case (action, position) => labeledPoints(action, position, locals, jobcates, cmcLocal, actionCount) }.cache
  
  val lrmodel = LogisticRegressionWithSGD.train(datas, numIterations=250, stepSize=2.0) // 250-2.0:0.6287871779982286
  val dtmodel = DecisionTree.trainRegressor(datas, scala.collection.immutable.Map[Int, Int](), impurity="variance", maxDepth=10, maxBins=36) // 9:0.6448457311761356
  val svmmodel = SVMWithSGD.train(datas, 200) // 250-2.0:0.6600676006045625 0.6652199055721625; 0.7054455107565476

  count = count + 1
  println(cookieid + ": " + count)
  Range(0,31,1).map { base =>
    val result = testData.map(_.split("\t")).map(values => labeledPoint(values,locals,jobcates, cmcLocal)).map(lp => ((vecdot(lrmodel.weights.toArray, lp.features.toArray)) * 10 + dtmodel.predict(lp.features) * 12 + vecdot(svmmodel.weights.toArray, lp.features.toArray) * base, lp.label))
    new BinaryClassificationMetrics(result).areaUnderROC
  }
}

Range(0,31,1).map { base =>
  scores.map(_(base)).sum / count
}.zipWithIndex.foreach(println)




println("max_auc is " + max_auc)
println("the base is " + iter)