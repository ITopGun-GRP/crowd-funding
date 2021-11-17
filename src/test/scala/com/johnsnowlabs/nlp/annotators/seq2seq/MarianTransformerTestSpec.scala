/*
 * Copyright 2017-2021 John Snow Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.johnsnowlabs.nlp.annotators.seq2seq

import com.johnsnowlabs.nlp.annotator._
import com.johnsnowlabs.nlp.base._
import com.johnsnowlabs.nlp.util.io.ResourceHelper
import com.johnsnowlabs.tags.SlowTest
import com.johnsnowlabs.util.Benchmark

import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.scalatest.flatspec.AnyFlatSpec


class MarianTransformerTestSpec extends AnyFlatSpec {

  "MarianTransformer" should "ignore bad token ids" taggedAs SlowTest in {
    import ResourceHelper.spark.implicits._

    val smallCorpus = Seq(
      "Това е български език.",
      "Y esto al español.",
      "Isto deve ir para o português."
    ).toDF("text")

    val documentAssembler = new DocumentAssembler()
      .setInputCol("text")
      .setOutputCol("document")

    val sentence = SentenceDetectorDLModel.pretrained("sentence_detector_dl", "xx")
      .setInputCols("document")
      .setOutputCol("sentence")

    val marian = MarianTransformer.pretrained("opus_mt_mul_en", "xx")
      .setInputCols("sentence")
      .setOutputCol("translation")
      .setMaxInputLength(50)
      .setIgnoreTokenIds(Array(64171))

    val pipeline = new Pipeline()
      .setStages(Array(
        documentAssembler,
        sentence,
        marian
      ))

    val pipelineModel = pipeline.fit(smallCorpus)

    Benchmark.time("Time to show") {
      val results = pipelineModel
        .transform(smallCorpus)
        .selectExpr("explode(translation) as translation")
        .where("length(translation.result) > 0")
        .selectExpr("translation.result as translation")
      assert(results.count() > 0, "Should return non-empty translations")
      results.show(truncate=false)
    }

  }

  "MarianTransformer" should "correctly load pretrained model" taggedAs SlowTest in {
    import ResourceHelper.spark.implicits._

    val smallCorpus = Seq(
      "What is the capital of France?",
      "This should go to French",
      "This is a sentence in English that we want to translate to French",
      "Despite a Democratic majority in the General Assembly, Nunn was able to enact most of his priorities, including tax increases that funded improvements to the state park system and the construction of a statewide network of mental health centers.",
      "",
      " "
    ).toDF("text")

    val documentAssembler = new DocumentAssembler()
      .setInputCol("text")
      .setOutputCol("document")

    val sentence = SentenceDetectorDLModel.pretrained("sentence_detector_dl", "xx")
      .setInputCols("document")
      .setOutputCol("sentence")

    val marian = MarianTransformer.pretrained()
      .setInputCols("document")
      .setOutputCol("translation")
      .setMaxInputLength(512)
      .setMaxOutputLength(50)

    val pipeline = new Pipeline()
      .setStages(Array(
        documentAssembler,
        sentence,
        marian
      ))

    val pipelineModel = pipeline.fit(smallCorpus)

    Benchmark.time("Time to save pipeline the first time") {
      pipelineModel.transform(smallCorpus).select("translation.result").write.mode("overwrite").save("./tmp_marianmt_pipeline")
    }

    Benchmark.time("Time to save pipeline the second time") {
      pipelineModel.transform(smallCorpus).select("translation.result").write.mode("overwrite").save("./tmp_marianmt_pipeline")
    }

    Benchmark.time("Time to first show") {
      pipelineModel.transform(smallCorpus).select("translation").show(false)
    }

    Benchmark.time("Time to second show") {
      pipelineModel.transform(smallCorpus).select("translation").show(false)
    }

    Benchmark.time("Time to save pipelineMolde") {
      pipelineModel.write.overwrite.save("./tmp_marianmt")
    }

    val savedPipelineModel = Benchmark.time("Time to load pipelineMolde") {
      PipelineModel.load("./tmp_marianmt")
    }
    val pipelineDF = Benchmark.time("Time to transform") {
      savedPipelineModel.transform(smallCorpus)
    }

    Benchmark.time("Time to show") {
      pipelineDF.select("translation").show(false)
    }
    Benchmark.time("Time to second show") {
      pipelineDF.select("translation").show(false)
    }

  }

}
