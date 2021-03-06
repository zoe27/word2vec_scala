package haiqing.word2vec

/**
 * Created by hwang on 07.12.15.
 */
object testVectors {
  def main(args: Array[String]): Unit = {

    println(1.0f.toByte)

    return


    val model = Processing.loadModel(args(0))
    //val model = Processing.loadTmpModel("./data_new",2000)

    val synonyms = model.findSynonyms(args(1), 20)
    //val synonyms = model.findSynonyms("day", 10)

    for((synonym, cosineSimilarity) <- synonyms) {
      println(s"$synonym $cosineSimilarity")
    }

    println()
    println()
    val NEWsynonyms = model.findSynonyms(args(2), 20)
    //val synonyms = model.findSynonyms("day", 10)

    for((synonym, cosineSimilarity) <- NEWsynonyms) {
      println(s"$synonym $cosineSimilarity")
    }
    println()




  }

}
