package haiqing.word2vec

import com.github.fommil.netlib.BLAS._
import org.apache.spark.rdd.RDD

import scala.collection.mutable
import scala.collection.mutable.ArrayBuilder
import com.github.fommil.netlib.BLAS.{getInstance => blas}
import org.apache.spark.mllib.linalg.{Vector, Vectors, DenseMatrix, BLAS, DenseVector}
/**
 * Created by hwang on 17.11.15.
 */

case class VocabWord(
  var word: String,
  var cn: Int
)

class MSSkipGram(
    private val skipgram: SkipGram) extends Serializable{

  private var vectorSize = 100
  private var learningRate = 0.025
  private var numPartitions = 1
  private var numIterations = 3
  private var seed = util.Random.nextLong()
  private var minCount = 5
  private var negative = 5
  private var numSenses = 3

  def setNumSenses(numSenses: Int): this.type = {
    this.numSenses = numSenses
    this
  }
  def setVectorSize(vectorSize: Int): this.type = {
    this.vectorSize = vectorSize
    this
  }
  def setLearningRate(learningRate: Double): this.type = {
    this.learningRate = learningRate
    this
  }
  def setNumPartitions(numPartitions: Int): this.type = {
    require(numPartitions > 0, s"numPartitions must be greater than 0 but got $numPartitions")
    this.numPartitions = numPartitions
    this
  }
  def setNumIterations(numIterations: Int): this.type = {
    this.numIterations = numIterations
    this
  }
  def setSeed(seed: Long): this.type = {
    this.seed = seed
    this
  }
  def setNegative(negative: Int): this.type = {
    this.negative = negative
    this
  }
  private val EXP_TABLE_SIZE = 1000
  private val MAX_EXP = 6
  private val MAX_SENTENCE_LENGTH = 1000
  private val POWER = 0.75
  private val VARIANCE = 0.01f
  private val TABEL_SIZE = 10000
  private val window = 5
  private var trainWordsCount = skipgram.getTrainWordsCount()
  private var vocabSize = skipgram.getVocabSize()
  private var vocab = skipgram.getVocab()
  private var vocabHash = skipgram.getVocabHash()
  private var syn0Global:Array[Float] = null
  private var syn1Global:Array[Float] = null

  private def createExpTable(): Array[Float] = {
    val expTable = new Array[Float](EXP_TABLE_SIZE)
    var i = 0
    while (i < EXP_TABLE_SIZE) {
      val tmp = math.exp((2.0 * i / EXP_TABLE_SIZE - 1.0) * MAX_EXP)
      expTable(i) = (tmp / (tmp + 1.0)).toFloat
      i += 1
    }
    expTable
  }
  private def makeTable(): Array[Int] = {
    val table = new Array[Int](TABEL_SIZE)
    var trainWordsPow = 0.0;
    for (a <- 0 to vocabSize-1)
      trainWordsPow += Math.pow(vocab(a).cn, POWER)
    var i = 0
    var d1 = Math.pow(vocab(i).cn,POWER) / trainWordsPow
    for (a <- 0 to TABEL_SIZE-1) {
      table(a) = i
      if (a*1.0/TABEL_SIZE > d1) {
        i += 1
        d1 += Math.pow(vocab(i).cn, POWER) / trainWordsPow
      }
      if (i >= vocabSize)
        i = vocabSize-1
    }
    table
  }

  def fit[S <: Iterable[String]](dataset: RDD[S]): Word2VecModel = {
    val words = dataset.flatMap(x => x)
    val sc = dataset.context
    val expTable = sc.broadcast(createExpTable())
    val bcVocabHash = sc.broadcast(vocabHash)
    val table = sc.broadcast(makeTable())

    syn0Global = new Array[Float](vocabSize * vectorSize * numSenses)
    syn1Global = new Array[Float](vocabSize * vectorSize * numSenses)
    val syn0 = skipgram.getSyn0()
    val syn1 = skipgram.getSyn1()
    for (a <- 0 to syn0.size-1)
      for (i <- 0 to numSenses-1) { //it is a problem
        syn0Global(i*syn0.size+a) = syn0(a)+(util.Random.nextFloat()-0.5f)*VARIANCE
        syn1Global(i*syn0.size+a) = syn1(a)+(util.Random.nextFloat()-0.5f)*VARIANCE
      }

    //sentence need to be changed
    val sentences: RDD[Array[Int]] = words.mapPartitions { iter =>
      new Iterator[Array[Int]] {
        def hasNext: Boolean = iter.hasNext
        def next(): Array[Int] = {
          val sentence = ArrayBuilder.make[Int]
          var sentenceLength = 0
          while (iter.hasNext && sentenceLength < MAX_SENTENCE_LENGTH) {
            val word = bcVocabHash.value.get(iter.next())
            word match {
              case Some(w) =>
                sentence += w+vocabSize*util.Random.nextInt(numSenses)
                sentenceLength += 1
              case None =>
            }
          }
          sentence.result()
        }
      }
    }

    val newSentences = sentences.repartition(numPartitions).cache()
    util.Random.setSeed(seed)
    if (vocabSize.toLong * vectorSize * 8 >= Int.MaxValue) {
      throw new RuntimeException("Please increase minCount or decrease vectorSize in Word2Vec" +
        " to avoid an OOM. You are highly recommended to make your vocabSize*vectorSize, " +
        "which is " + vocabSize + "*" + vectorSize + " for now, less than `Int.MaxValue/8`.")
    }

    var alpha = learningRate
    for (k <- 1 to numIterations) {
      println("Iteration "+k)
      val partial = newSentences.mapPartitionsWithIndex { case (idx, iter) =>
        util.Random.setSeed(seed ^ ((idx + 1) << 16) ^ ((-k - 1) << 8))
        val model = iter.foldLeft((syn0Global, syn1Global, 0, 0)) {
          case ((syn0, syn1, lastWordCount, wordCount), sentence) =>
            var lwc = lastWordCount
            var wc = wordCount
            if (wordCount - lastWordCount > 10000) {
              lwc = wordCount
              // TODO: discount by iteration?
              alpha =
                learningRate * (1 - (trainWordsCount*(k-1) + numPartitions * wordCount.toDouble) / (trainWordsCount + 1) / numIterations)
              //println("!!"+"numIterations"+numIterations+"numPartitions"+numPartitions+(trainWordsCount*k + numPartitions * wordCount.toDouble) / (trainWordsCount + 1) / numIterations)
              if (alpha < learningRate * 0.0001) alpha = learningRate * 0.0001
              println("wordCount = " + wordCount + ", alpha = " + alpha)
            }
            wc += sentence.size
            var pos = 0
            while (pos < sentence.size) {
              var word = sentence(pos)
              var bestSense = -1
              var bestScore = 0.0

              val b = util.Random.nextInt(window)

              //negative sampling
              val negSample = new Array[Int](negative)
              for (i <- 0 to negative-1) {
                negSample(i) = table.value(Math.abs(util.Random.nextLong()%TABEL_SIZE).toInt)
                if (negSample(i) <= 0)
                  negSample(i) = (Math.abs(util.Random.nextLong())%(vocabSize-1)+1).toInt
                negSample(i) = negSample(i)+util.Random.nextInt(numSenses)*vocabSize
              }

              //adjust the senses
              for (sense <- 0 to numSenses-1) {
                var a = b
                var score = 1.0
                word = word%vocabSize+vocabSize*sense
                while (a < window * 2 + 1 - b) {
                  if (a != window) {
                    val c = pos - window + a
                    if (c >= 0 && c < sentence.size) {
                      val lastWord = sentence(c)
                      val l1 = lastWord * vectorSize
                      var target = word
                      var label = 0
                      for (d <- 0 to negative) {
                        if (d > 0) {
                          target = negSample(d-1)
                          label = 1
                        }
                        if (target%vocabSize != lastWord%vocabSize || d == 0) {
                          val l2 = target * vectorSize
                          // Propagate hidden -> output
                          var f = blas.sdot(vectorSize, syn0, l1, 1, syn1, l2, 1)
                          if (f > MAX_EXP)
                            f = expTable.value(expTable.value.length-1)
                          else if (f < -MAX_EXP)
                            f = expTable.value(0)
                          else {
                            val ind = ((f + MAX_EXP) * (EXP_TABLE_SIZE / MAX_EXP / 2.0)).toInt
                            f = expTable.value(ind)
                          }
                          score *= Math.abs(label-f)
                        }
                      }
                    }
                  }
                  a += 1
                }
                if (bestSense == -1 || score > bestScore) {
                  bestScore = score
                  bestSense = sense
                }
              }

              //train Skip-Gram
              var a = b
              word = word%vocabSize+vocabSize*bestSense
              sentence(pos) = word
              while (a < window * 2 + 1 - b) {
                if (a != window) {
                  val c = pos - window + a
                  if (c >= 0 && c < sentence.size) {
                    val lastWord = sentence(c)
                    val l1 = lastWord * vectorSize
                    val neu1e = new Array[Float](vectorSize)
                    var target = word
                    var label = 1
                    for (d <- 0 to negative) {
                      if (d > 0) {
                        target = negSample(d-1)
                        label = 1
                      }
                      if (target%vocabSize != lastWord%vocabSize || d == 0) {
                        val l2 = target * vectorSize
                        // Propagate hidden -> output
                        var f = blas.sdot(vectorSize, syn0, l1, 1, syn1, l2, 1)
                        var g = 0.0f
                        if (f > MAX_EXP)
                          g = (label - 1) * alpha.toFloat
                        else if (f < -MAX_EXP)
                          g = (label - 0) * alpha.toFloat
                        else {
                          val ind = ((f + MAX_EXP) * (EXP_TABLE_SIZE / MAX_EXP / 2.0)).toInt
                          f = expTable.value(ind)
                          g = (label - f) * alpha.toFloat
                        }
                        blas.saxpy(vectorSize, g, syn1, l2, 1, neu1e, 0, 1)
                        blas.saxpy(vectorSize, g, syn0, l1, 1, syn1, l2, 1)
                      }
                    }
                    blas.saxpy(vectorSize, 1.0f, neu1e, 0, 1, syn0, l1, 1)
                  }
                }
                a += 1
              }
              pos += 1
            }
            (syn0, syn1, lwc, wc)
        }
        val syn0Local = model._1
        val syn1Local = model._2
        // Only output modified vectors.
        Iterator.tabulate(vocabSize*numSenses) { index =>
            Some((index, syn0Local.slice(index * vectorSize, (index + 1) * vectorSize)))
        }.flatten ++ Iterator.tabulate(vocabSize*numSenses) { index =>
            Some((index + vocabSize*numSenses, syn1Local.slice(index * vectorSize, (index + 1) * vectorSize)))
        }.flatten
      }
      val synAgg = partial.reduceByKey { case (v1, v2) =>
        blas.saxpy(vectorSize, 1.0f, v2, 1, v1, 1)
        v1
      }.collect()
      var i = 0
      while (i < synAgg.length) {
        val index = synAgg(i)._1
        if (index < vocabSize*numSenses) {
          Array.copy(synAgg(i)._2, 0, syn0Global, index * vectorSize, vectorSize)
        } else {
          Array.copy(synAgg(i)._2, 0, syn1Global, (index - vocabSize*numSenses) * vectorSize, vectorSize)
        }
        i += 1
      }
      for (a <- 0 to syn0Global.size-1) {
        syn0Global(a) /= numPartitions
        syn1Global(a) /= numPartitions
      }
    }
    newSentences.unpersist()

    val wordArray = vocab.map(_.word)
    val msWordArray = new Array[String](wordArray.size*numSenses)
    for (a <- 0 to wordArray.size-1)
      for (i <- 0 to numSenses-1)
        msWordArray(i*wordArray.length+a) = wordArray(a)+i
    new Word2VecModel(msWordArray.zipWithIndex.toMap, syn0Global)

  }
}

class SkipGram extends Serializable {
  private var vectorSize = 100
  private var learningRate = 0.025
  private var numPartitions = 1
  private var numIterations = 1
  private var seed = util.Random.nextLong()
  private var minCount = 5
  private var negative = 5

  def setVectorSize(vectorSize: Int): this.type = {
    this.vectorSize = vectorSize
    this
  }
  def setLearningRate(learningRate: Double): this.type = {
    this.learningRate = learningRate
    this
  }
  def setNumPartitions(numPartitions: Int): this.type = {
    require(numPartitions > 0, s"numPartitions must be greater than 0 but got $numPartitions")
    this.numPartitions = numPartitions
    this
  }
  def setNumIterations(numIterations: Int): this.type = {
    this.numIterations = numIterations
    this
  }
  def setSeed(seed: Long): this.type = {
    this.seed = seed
    this
  }
  def setMinCount(minCount: Int): this.type = {
    this.minCount = minCount
    this
  }
  def setNegative(negative: Int): this.type = {
    this.negative = negative
    this
  }

  private val EXP_TABLE_SIZE = 1000
  private val MAX_EXP = 6
  private val MAX_SENTENCE_LENGTH = 1000
  private val POWER = 0.75
  private val TABEL_SIZE = 10000
  private val window = 5
  private var trainWordsCount = 0
  private var vocabSize = 0
  private var vocab: Array[VocabWord] = null
  private var vocabHash = mutable.HashMap.empty[String, Int]
  private var syn0Global:Array[Float] = null
  private var syn1Global:Array[Float] = null

  def getVocabSize() = vocabSize
  def getVocab() = vocab
  def getVocabHash() = vocabHash
  def getSyn0() = syn0Global
  def getSyn1() = syn1Global
  def getTrainWordsCount() = trainWordsCount

  private def learnVocab(words: RDD[String]): Unit = {
    vocab = words.map(w => (w, 1))
      .reduceByKey(_ + _)
      .map(x => VocabWord(
        x._1,
        x._2))
      .filter(_.cn >= minCount)
      .collect()
      .sortWith((a, b) => a.cn > b.cn)

    vocabSize = vocab.length
    require(vocabSize > 0, "The vocabulary size should be > 0. You may need to check " +
      "the setting of minCount, which could be large enough to remove all your words in sentences.")

    var a = 0
    while (a < vocabSize) {
      vocabHash += vocab(a).word -> a
      trainWordsCount += vocab(a).cn
      a += 1
    }
    println("trainWordsCount = " + trainWordsCount)
  }

  private def createExpTable(): Array[Float] = {
    val expTable = new Array[Float](EXP_TABLE_SIZE)
    var i = 0
    while (i < EXP_TABLE_SIZE) {
      val tmp = math.exp((2.0 * i / EXP_TABLE_SIZE - 1.0) * MAX_EXP)
      expTable(i) = (tmp / (tmp + 1.0)).toFloat
      i += 1
    }
    expTable
  }

  private def makeTable(): Array[Int] = {
    val table = new Array[Int](TABEL_SIZE)
    var trainWordsPow = 0.0;
    for (a <- 0 to vocabSize-1)
      trainWordsPow += Math.pow(vocab(a).cn, POWER)
    var i = 0
    var d1 = Math.pow(vocab(i).cn,POWER) / trainWordsPow
    for (a <- 0 to TABEL_SIZE-1) {
      table(a) = i
      if (a*1.0/TABEL_SIZE > d1) {
        i += 1
        d1 += Math.pow(vocab(i).cn, POWER) / trainWordsPow
      }
      if (i >= vocabSize)
        i = vocabSize-1
    }
    table
  }

  def fit[S <: Iterable[String]](dataset: RDD[S]): Word2VecModel = {

    val words = dataset.flatMap(x => x)

    learnVocab(words)

    val sc = dataset.context

    val expTable = sc.broadcast(createExpTable())
    val bcVocabHash = sc.broadcast(vocabHash)
    val table = sc.broadcast(makeTable())

    val sentences: RDD[Array[Int]] = words.mapPartitions { iter =>
      new Iterator[Array[Int]] {
        def hasNext: Boolean = iter.hasNext

        def next(): Array[Int] = {
          val sentence = ArrayBuilder.make[Int]
          var sentenceLength = 0
          while (iter.hasNext && sentenceLength < MAX_SENTENCE_LENGTH) {
            val word = bcVocabHash.value.get(iter.next())
            word match {
              case Some(w) =>
                sentence += w
                sentenceLength += 1
              case None =>
            }
          }
          sentence.result()
        }
      }
    }

    val newSentences = sentences.repartition(numPartitions).cache()
    util.Random.setSeed(seed)

    if (vocabSize.toLong * vectorSize * 8 >= Int.MaxValue) {
      throw new RuntimeException("Please increase minCount or decrease vectorSize in Word2Vec" +
        " to avoid an OOM. You are highly recommended to make your vocabSize*vectorSize, " +
        "which is " + vocabSize + "*" + vectorSize + " for now, less than `Int.MaxValue/8`.")
    }

    var alpha = learningRate
    syn0Global = Array.fill[Float](vocabSize * vectorSize)((util.Random.nextFloat() - 0.5f) / vectorSize)
    syn1Global = new Array[Float](vocabSize * vectorSize)

    for (k <- 1 to numIterations) {
      println("Iteration "+k)
      val partial = newSentences.mapPartitionsWithIndex { case (idx, iter) =>
        util.Random.setSeed(seed ^ ((idx + 1) << 16) ^ ((-k - 1) << 8))
        val model = iter.foldLeft((syn0Global, syn1Global, 0, 0)) {
          case ((syn0, syn1, lastWordCount, wordCount), sentence) =>
            var lwc = lastWordCount
            var wc = wordCount
            if (wordCount - lastWordCount > 10000) {
              lwc = wordCount
              // TODO: discount by iteration?
              alpha =
                learningRate * (1 - (trainWordsCount*(k-1) + numPartitions * wordCount.toDouble) / (trainWordsCount + 1) / numIterations)
              //println("!!"+"numIterations"+numIterations+"numPartitions"+numPartitions+(trainWordsCount*k + numPartitions * wordCount.toDouble) / (trainWordsCount + 1) / numIterations)
              if (alpha < learningRate * 0.0001) alpha = learningRate * 0.0001
              println("wordCount = " + wordCount + ", alpha = " + alpha)
            }
            wc += sentence.size
            var pos = 0
            while (pos < sentence.size) {
              val word = sentence(pos)
              val b = util.Random.nextInt(window)
              // Train Skip-gram
              var a = b
              while (a < window * 2 + 1 - b) {
                if (a != window) {
                  val c = pos - window + a
                  if (c >= 0 && c < sentence.size) {
                    val lastWord = sentence(c)
                    val l1 = lastWord * vectorSize
                    val neu1e = new Array[Float](vectorSize)
                    var target = word
                    var label = 1
                    for (d <- 0 to negative+1) {
                      if (d > 0) {
                        val idx = Math.abs(util.Random.nextLong()%TABEL_SIZE).toInt
                        target = table.value(idx)
                        if (target <= 0)
                          target = (Math.abs(util.Random.nextLong())%(vocabSize-1)+1).toInt
                        label = 0
                      }
                      if (target != lastWord || d == 0) {
                        val l2 = target * vectorSize
                        // Propagate hidden -> output
                        var f = blas.sdot(vectorSize, syn0, l1, 1, syn1, l2, 1)
                        var g = 0.0f
                        if (f > MAX_EXP)
                          g = (label - 1) * alpha.toFloat
                        else if (f < -MAX_EXP)
                          g = (label - 0) * alpha.toFloat
                        else {
                          val ind = ((f + MAX_EXP) * (EXP_TABLE_SIZE / MAX_EXP / 2.0)).toInt
                          f = expTable.value(ind)
                          g = (label - f) * alpha.toFloat
                        }
                        blas.saxpy(vectorSize, g, syn1, l2, 1, neu1e, 0, 1)
                        blas.saxpy(vectorSize, g, syn0, l1, 1, syn1, l2, 1)
                      }
                    }
                    blas.saxpy(vectorSize, 1.0f, neu1e, 0, 1, syn0, l1, 1)
                  }
                }
                a += 1
              }
              pos += 1
            }
            (syn0, syn1, lwc, wc)
        }
        val syn0Local = model._1
        val syn1Local = model._2
        // Only output modified vectors.
        Iterator.tabulate(vocabSize) { index =>
            Some((index, syn0Local.slice(index * vectorSize, (index + 1) * vectorSize)))
        }.flatten ++ Iterator.tabulate(vocabSize) { index =>
            Some((index + vocabSize, syn1Local.slice(index * vectorSize, (index + 1) * vectorSize)))
        }.flatten
      }
      val synAgg = partial.reduceByKey { case (v1, v2) =>
        blas.saxpy(vectorSize, 1.0f, v2, 1, v1, 1)
        v1
      }.collect()
      var i = 0
      while (i < synAgg.length) {
        val index = synAgg(i)._1
        if (index < vocabSize) {
          Array.copy(synAgg(i)._2, 0, syn0Global, index * vectorSize, vectorSize)
        } else {
          Array.copy(synAgg(i)._2, 0, syn1Global, (index - vocabSize) * vectorSize, vectorSize)
        }
        i += 1
      }
      for (a <- 0 to syn0Global.size-1) {
        syn0Global(a) /= numPartitions
        syn1Global(a) /= numPartitions
      }
    }
    newSentences.unpersist()

    val wordArray = vocab.map(_.word)
    new Word2VecModel(wordArray.zipWithIndex.toMap, syn0Global)
  }
}


class Word2VecModel (
    private val wordIndex: Map[String, Int],
    private val wordVectors: Array[Float]){

  private val numWords = wordIndex.size
  private val vectorSize = wordVectors.length / numWords

  private val wordList: Array[String] = {
    val (wl, _) = wordIndex.toSeq.sortBy(_._2).unzip
    wl.toArray
  }

  private val wordVecNorms: Array[Double] = {
    val wordVecNorms = new Array[Double](numWords)
    var i = 0
    while (i < numWords) {
      val vec = wordVectors.slice(i * vectorSize, i * vectorSize + vectorSize)
      wordVecNorms(i) = blas.snrm2(vectorSize, vec, 1)
      i += 1
    }
    wordVecNorms
  }

  private def cosineSimilarity(v1: Array[Float], v2: Array[Float]): Double = {
    require(v1.length == v2.length, "Vectors should have the same length")
    val n = v1.length
    val norm1 = blas.snrm2(n, v1, 1)
    val norm2 = blas.snrm2(n, v2, 1)
    if (norm1 == 0 || norm2 == 0) return 0.0
    blas.sdot(n, v1, 1, v2, 1) / norm1 / norm2
  }

  def transform(word: String): Vector = {
    wordIndex.get(word) match {
      case Some(ind) =>
        val vec = wordVectors.slice(ind * vectorSize, ind * vectorSize + vectorSize)
        Vectors.dense(vec.map(_.toDouble))
      case None =>
        throw new IllegalStateException(s"$word not in vocabulary")
    }
  }

  def findSynonyms(word: String, num: Int): Array[(String, Double)] = {
    val vector = transform(word)
    findSynonyms(vector, num)
  }

  def findSynonyms(vector: Vector, num: Int): Array[(String, Double)] = {
    require(num > 0, "Number of similar words should > 0")
    // TODO: optimize top-k
    val fVector = vector.toArray.map(_.toFloat)
    val cosineVec = Array.fill[Float](numWords)(0)
    val alpha: Float = 1
    val beta: Float = 0

    blas.sgemv(
      "T", vectorSize, numWords, alpha, wordVectors, vectorSize, fVector, 1, beta, cosineVec, 1)

    // Need not divide with the norm of the given vector since it is constant.
    val cosVec = cosineVec.map(_.toDouble)
    var ind = 0
    while (ind < numWords) {
      cosVec(ind) /= wordVecNorms(ind)
      ind += 1
    }
    wordList.zip(cosVec)
      .toSeq
      .sortBy(- _._2)
      .take(num + 1)
      .tail
      .toArray
  }

  def getVectors: Map[String, Array[Float]] = {
    wordIndex.map { case (word, ind) =>
      (word, wordVectors.slice(vectorSize * ind, vectorSize * ind + vectorSize))
    }
  }
}