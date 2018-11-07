package com.johnsnowlabs.nlp.annotators.spell.ocr

import java.io.{BufferedWriter, File, FileWriter}
import com.github.liblevenshtein.transducer.{Algorithm, Candidate, ITransducer}
import com.github.liblevenshtein.transducer.factory.TransducerBuilder
import com.johnsnowlabs.ml.tensorflow.TensorflowWrapper
import com.johnsnowlabs.nlp.annotators.spell.ocr.parser._
import com.johnsnowlabs.nlp.{AnnotatorApproach, AnnotatorType}
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.param.Param
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.Dataset
import org.tensorflow.{Graph, Session}

import scala.collection.mutable

case class OpenClose(open:String, close:String)

class OcrSpellCheckApproach(override val uid: String) extends AnnotatorApproach[OcrSpellCheckModel]{
  override val description: String = "Ocr specific Spell Checking"

  val trainCorpusPath = new Param[String](this, "trainCorpusPath", "Path to the training corpus text file.")
  def setTrainCorpusPath(path: String): this.type = set(trainCorpusPath, path)

  val vocabPath = new Param[String](this, "vocabPath", "Path to the training corpus text file.")
  def setVocabPath(path: String): this.type = set(vocabPath, path)

  val minCount = new Param[Double](this, "minCount", "Min number of times a token should appear to be included in vocab.")
  def setMinCount(threshold: Double): this.type = set(minCount, threshold)

  val specialClasses = new Param[List[TokenParser]](this, "specialClasses", "Min number of times a token should appear to be included in vocab.")
  def setSpecialClasses(parsers: List[TokenParser]):this.type = set(specialClasses, parsers)

  val languageModelClasses = new Param[Int](this, "languageModelClasses", "Number of classes to use during factorization of the softmax output in the LM.")
  def setLMClasses(k: Int):this.type = set(languageModelClasses, k)

  val prefixes = new Param[Array[String]](this, "prefixes", "Prefix tokens to split during corpus tokenization.")
  def setPrefixes(p: Array[String]):this.type = set(prefixes, p)

  val suffixes = new Param[Array[String]](this, "suffixes", "Suffix tokens to split during corpus tokenization.")
  def setPrefixes(s: Array[String]):this.type = set(suffixes, s)


  setDefault(minCount -> 3.0,
    specialClasses -> List(DateToken, NumberToken),
    prefixes -> Array("'"),
    suffixes -> Array(".", ":", "%", ",", ";", "?", "'")
  )


  val openClose = List(OpenClose("(", ")"), OpenClose("[", "]"), OpenClose("\"", "\""))

  private val firstPass = Seq(SuffixedToken(getOrDefault(suffixes) ++ openClose.map(_.close)),
    PrefixedToken(getOrDefault(prefixes) ++ openClose.map(_.open)))

  override def train(dataset: Dataset[_], recursivePipeline: Option[PipelineModel]): OcrSpellCheckModel = {

    val graph = new Graph()
    val config = Array[Byte](56, 1)
    val session = new Session(graph, config)
    val tf = new TensorflowWrapper(session, graph)

    // extract vocabulary
    require(isDefined(trainCorpusPath), "Train corpus must be set before training")
    val rawTextPath = getOrDefault(trainCorpusPath)
    val vPath = getOrDefault(vocabPath)

    val (vocabFreq, vocabIds) =
      if (new File(vPath).exists())
        loadVocab(vPath)
      else {
        val v = persistVocab(genVocab(rawTextPath), vPath)
        val ids = encodeCorpus(rawTextPath, v.map(_._1))
        (v.toMap, ids)
      }

    // create transducers for special classes
    val specialClassesTransducers = getOrDefault(specialClasses).
      par.map(_.generateTransducer).seq

    new OcrSpellCheckModel().
      setVocabFreq(vocabFreq.toMap).
      setVocabIds(vocabIds.toMap).
      setVocabTransducer(createTransducer(vocabFreq.keys.toList)).
      setSpecialClassesTransducers(specialClassesTransducers).
      setTensorflow(tf).
      readModel("../auxdata/good_model", dataset.sparkSession, "").
      setInputCols(getOrDefault(inputCols))
  }

  private def loadVocab(path: String) = {
    // store individual frequencies of words
    val vocabFreq = mutable.HashMap[String, Double]()

    // store word ids
    val vocabIdxs = mutable.HashMap[String, Int]()

    scala.io.Source.fromFile(path + ".freq").getLines.zipWithIndex.foreach { case (line, idx) =>
       val lineFields = line.split("\\|")
       vocabFreq += (lineFields(0)-> lineFields.last.toDouble)
    }
    // TODO: remove this, retrieve everything from the .freq file
    scala.io.Source.fromFile(path).getLines.zipWithIndex.foreach { case (line, idx) =>
      vocabIdxs += (line-> idx)
    }

    (vocabFreq, vocabIdxs)
  }

  def computeAndPersistClasses(vocab: mutable.HashMap[String, Double], total:Double, k:Int) = {

    val sorted = vocab.toList.sortBy(_._2).reverse
    val word2id = vocab.toList.sortBy(_._1).map(_._1).zipWithIndex.toMap
    val binMass = total / k

    var acc = 0.0
    var currBinLimit = binMass
    var currClass = 0
    var currWordId = 0

    var classes = Map[String, (Int, Int)]()
    var maxWid = 0
    for(word <-sorted) {
      if(acc < currBinLimit){
        acc += word._2
        classes = classes.updated(word._1, (currClass, currWordId))
        currWordId += 1
      }
      else{
        acc += word._2
        currClass += 1
        currBinLimit = (currClass + 1) * binMass
        classes = classes.updated(word._1, (currClass, 0))
        currWordId = 1
      }
      if (currWordId > maxWid)
        maxWid = currWordId
    }
    // TODO hardcoded stuff!!
    val classesFile = new File("classes.psv")
    val bwClassesFile = new BufferedWriter(new FileWriter(classesFile))

    classes.foreach{case (word, (cid, wid)) =>
      bwClassesFile.write(s"""${word2id.get(word).get}|$cid|$wid""")
      bwClassesFile.newLine
    }
    bwClassesFile.close
    classes
  }

  def genVocab(rawDataPath: String):List[(String, Double)] = {
    var vocab = mutable.HashMap[String, Double]()

    // for every sentence we have one end and one begining
    val eosBosCount = scala.io.Source.fromFile(rawDataPath).getLines.size

    // TODO: Spark implementation?
    scala.io.Source.fromFile(rawDataPath).getLines.foreach { line =>
      // second pass identify tokens that belong to special classes, and replace with a label
      // TODO removing crazy encodings of space and replacing with standard one
      line.split(" ").flatMap(_.split(" ")).flatMap(_.split(" ")).filter(_!=" ").foreach { token =>
        var tmp = Seq(token)

        firstPass.foreach{ parser =>
          tmp = tmp.flatMap(_.split(" ").map(_.trim)).map(parser.separate).flatMap(_.split(" "))
        }

        getOrDefault(specialClasses).foreach { specialClass =>
          tmp = tmp.map(specialClass.replaceWithLabel)
        }

        tmp.foreach {cleanToken =>
          val currCount = vocab.getOrElse(cleanToken, 0.0)
          vocab.update(cleanToken, currCount + 1.0)
        }
      }
    }

    // words appearing less that minCount times will be unknown
    val unknownCount = vocab.filter(_._2 < getOrDefault(minCount)).map(_._2).sum

    // remove 'rare' tokens, those appearing less than minCount times
    vocab = vocab.filter(_._2 >= getOrDefault(minCount))

    // Blacklists
    // words that appear with first letter capitalized, at the beginning of sentence
    val fwis = vocab.filter(_._1.length > 1).filter(_._1.head.isUpper).
      filter(w => vocab.contains(w._1.head.toLower + w._1.tail)).map(_._1)

    val hyphen = vocab.filter {
      case (word, weight) =>
        val splits = word.split("-")
        splits.length == 2 && vocab.contains(splits(0)) && vocab.contains(splits(1))
    }.map(_._1)

    val slash = vocab.filter {
      case (word, weight) =>
        val splits = word.split("/")
        splits.length == 2 && vocab.contains(splits(0)) && vocab.contains(splits(1))
    }.map(_._1)

    val blacklist = fwis ++ hyphen ++ slash
    blacklist.foreach{vocab.remove}

    vocab.update("_BOS_", eosBosCount)
    vocab.update("_EOS_", eosBosCount)
    vocab.update("_UNK_", unknownCount)

    // compute frequencies - logarithmic
    var totalCount = vocab.values.reduce(_ + _) + eosBosCount * 2 + unknownCount


    // TODO hard-coded number of classes
    computeAndPersistClasses(vocab, totalCount, 4000)

    totalCount = math.log(totalCount)
    for (key <- vocab.keys){
      vocab.update(key, math.log(vocab(key)) - totalCount)
    }

    // ("_PAD_", 1.0),
    vocab.toList.sortBy(_._1)

  }


  private def persistVocab(v: List[(String, Double)], fileName:String) = {
    // both the vocabulary, and vocabulary + frequencies
    val freqFile = new File(fileName + ".freq")
    val vocabFile = new File(fileName)

    val bwVocabFreq = new BufferedWriter(new FileWriter(freqFile))
    val bwVocab = new BufferedWriter(new FileWriter(vocabFile))

    v.foreach{case (word, freq) =>
      bwVocabFreq.write(s"""$word|$freq""")
      bwVocabFreq.newLine

      bwVocab.write(word)
      bwVocab.newLine
    }
    bwVocab.close
    bwVocabFreq.close
    v
  }


  private def createTransducer(vocab:List[String]) = {
    import scala.collection.JavaConversions._

    // Create Levenshtein Automata
    new TransducerBuilder().
      dictionary(vocab.sorted, true).
      algorithm(Algorithm.STANDARD).
      defaultMaxDistance(2).
      includeDistance(true).
      build[Candidate]
  }

  /* TODO keeping this for reference now, we could relocate it somewhere else */
  private def persistTransducer(transducer:ITransducer[Candidate]) = {
    import com.github.liblevenshtein.serialization.ProtobufSerializer
    import java.nio.file.Files
    import java.nio.file.Paths
    val serializedDictionaryPath = Paths.get("transducer.protobuf.bytes")
    try {
      val stream = Files.newOutputStream(serializedDictionaryPath)
      try {
        val serializer = new ProtobufSerializer
        serializer.serialize(transducer, stream)
      } finally if (stream != null) stream.close()
    }
  }

  private def encodeCorpus(rawTextPath: String, sorted: List[String]) = {

    val vMap: Map[String, Int] = sorted.zipWithIndex.toMap
    val bw = new BufferedWriter(new FileWriter(new File(rawTextPath + ".ids")))

    scala.io.Source.fromFile(rawTextPath).getLines.foreach { line =>
      // TODO removing crazy encodings of space and replacing with standard one - should be done outside Scala
      val text  = line.split(" ").flatMap(_.split(" ")).flatMap(_.split(" ")).filter(_!=" ").flatMap { token =>
        var tmp = token
        firstPass.foreach{ parser =>
          tmp = parser.separate(tmp)
        }
        // second pass identify tokens that belong to special classes, and replace with a label
        specialClasses.foreach { specialClass =>
          tmp = specialClass.replaceWithLabel(tmp)
        }

        tmp.split(" ").filter(_ != " ").map {cleanToken =>
          s"""${vMap.getOrElse(cleanToken, vMap("_UNK_")).toString}"""
        }
      }.mkString(" ")
      bw.write(s"""${vMap("_BOS_")} $text ${vMap("_EOS_")}\n""")
    }
    bw.close
    vMap
  }

  def this() = this(Identifiable.randomUID("SPELL"))

  /** Annotator reference id. Used to identify elements in metadata or to refer to this annotator type */
  override val requiredAnnotatorTypes: Array[String] = Array(AnnotatorType.TOKEN)
  override val annotatorType: AnnotatorType = AnnotatorType.TOKEN

  /* this is a list of functions that return the distance of a string to a particular regex */
  private var tokenClasses = List[(String) => Tuple2[String, Float]]()

}
