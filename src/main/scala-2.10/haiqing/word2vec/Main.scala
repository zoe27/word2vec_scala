package haiqing.word2vec

import javax.rmi.CORBA.Util

import org.apache.spark.{SparkContext, SparkConf}
import scala.collection.mutable
import scala.compat.Platform.currentTime
import scala.io.Source

/**
 * Created by hwang on 17.11.15.
 */
object Main {
  def main(args: Array[String]): Unit = {
    val startTime = currentTime
    val conf = new SparkConf().setAppName("Word2Vec")
    if (!conf.contains("spark.master"))
    {
      conf.setMaster("local[*]")
    }
    val sc = new SparkContext(conf)
    println(sc.defaultParallelism + "   " + sc.master)
    val input = sc.textFile(args(0)).map(line => line.split(" ").toSeq)
    val words = input.flatMap(x => x).filter(s=>s(0).isLetter).map(s=>s.toLowerCase)

    val skipgram = new SkipGram().setNumPartitions(args(1).toInt).setNumIterations(args(2).toInt).setNegative(args(3).toInt).setMinCount(args(4).toInt).setWindow(args(5).toInt).setVectorSize(args(6).toInt).setSample(args(7).toDouble)

    val model = skipgram.fit(words)
    val synonyms = model.findSynonyms(args(8), 10)

    for((synonym, cosineSimilarity) <- synonyms) {
      println(s"$synonym $cosineSimilarity")
    }

    println()

    //skipgram.cleanSyn()

    val msskipgram = new MSSkipGram(skipgram).setNumPartitions(args(9).toInt).setNumIterations(args(10).toInt).setNegative(args(11).toInt).setNumSenses(args(12).toInt).setMinCount(args(13).toInt).setWindow(args(14).toInt).setVectorSize(args(15).toInt).setSample(args(16).toDouble).setSentenceIter(args(17).toInt).setAdjustingRatio(args(18).toDouble)

    val newModel = msskipgram.fit(words)

    for (i <- 0 to args(12).toInt-1) {
      val newSynonyms = newModel.findSynonyms(args(19)+i, 10)

      println()
      for ((synonym, cosineSimilarity) <- newSynonyms) {
        println(s"$synonym $cosineSimilarity")
      }
    }

    println("total time:"+(currentTime-startTime)/1000.0)
  }
}

object Preprocessing {
  def map1(s: String): String={
    var i = 0
    while (i < s.length && s(i) != ''') {i+=1}
    if (i == s.length)
      s.toLowerCase.filter((c: Char) => c >= 'a' && c <= 'z')
    else
      s.substring(0,i).toLowerCase.filter((c: Char) => c >= 'a' && c <= 'z')
  }

  def map2(s: String): String={
    if (s == "apple" && util.Random.nextDouble() <= 0.5)
      "APPLE"
    else
      s
  }

  def filter1(s: String): Boolean={
    if (s.length == 0)
      false
    else
      true
  }

  def filter2(s: String): Boolean={
    if (s(0) >= '0' && s(0) <= '9')
      false
    else
      true
  }

  def filter3(s: String): Boolean={
    s(0).isLetter
  }
}

object Processing {
  def loadModel(path: String): Word2VecModel = {
    val wordIndex = collection.mutable.Map[String, Int]()
    for (line <- Source.fromFile(path+"/wordIndex.txt").getLines()) {
      val pair = line.split(" ")
      wordIndex(pair(0))=pair(1).toInt
    }
    val wordVectors = Source.fromFile(path+"/wordVectors.txt").getLines().next().split(" ").map(s=>s.toFloat)
    new Word2VecModel(wordIndex.toMap, wordVectors)
  }
}