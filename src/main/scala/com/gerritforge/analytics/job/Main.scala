// Copyright (C) 2017 GerritForge Ltd
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.gerritforge.analytics.job

import com.gerritforge.analytics.engine.GerritAnalyticsTransformations._
import com.gerritforge.analytics.model.{GerritEndpointConfig, GerritProjects}
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.io.{Codec, Source}

object Main extends App with Job {

  new scopt.OptionParser[GerritEndpointConfig]("scopt") {
    head("scopt", "3.x")
    opt[String]('u', "url") optional() action { (x, c) =>
      c.copy(baseUrl = x)
    } text "gerrit url"
    opt[String]('o', "out") optional() action { (x, c) =>
      c.copy(outputDir = x)
    } text "output directory"
    opt[String]('e', "elasticIndex") optional() action { (x, c) =>
      c.copy(elasticIndex = Some(x))
    } text "output directory"
    opt[String]('s', "since") optional() action { (x, c) =>
      c.copy(since = Some(x))
    } text "begin date "
    opt[String]('u', "until") optional() action { (x, c) =>
      c.copy(until = Some(x))
    } text "since date"
    opt[String]('g', "aggregate") optional() action { (x, c) =>
      c.copy(aggregate = Some(x))
    } text "aggregate email/email_hour/email_day/email_month/email_year"
    opt[String]('a', "email-aliases") optional() action { (path, c) =>
      if (!new java.io.File(path).exists) {
        println(s"ERROR: Path '${path}' doesn't exists!")
        System.exit(1)
      }
      c.copy(emailAlias = Some(path))
    } text "\"emails to author alias\" input data path"
  }.parse(args, GerritEndpointConfig()) match {
    case Some(config) =>
      implicit val spark = SparkSession.builder()
        .appName("Gerrit Analytics ETL")
        .getOrCreate()
      implicit val implicitConfig = config;
      val dataFrame = run()
      dataFrame.write.json(config.outputDir)
      saveES(dataFrame)
    case None => // invalid configuration usage has been displayed
  }
}

trait Job {
  implicit val codec = Codec.ISO8859

  def run()(implicit config: GerritEndpointConfig, spark: SparkSession): DataFrame = {
    import spark.sqlContext.implicits._ // toDF
    val sc = spark.sparkContext
    val projects = sc.parallelize(GerritProjects(Source.fromURL(s"${config.baseUrl}/projects/")))
    val emailAliasesDF = getEmailAliasDF(config.emailAlias)

    projects
      .enrichWithSource
      .fetchRawContributors
      .toDF("project", "json")
      .transformCommitterInfo
      .handleAuthorEMailAliases(emailAliasesDF)
      .convertDates("last_commit_date")
      .addOrganization()
  }
  def saveES(df: DataFrame)(implicit config: GerritEndpointConfig) {
    import org.elasticsearch.spark.sql._
    config.elasticIndex.map(df.saveToEs(_))
  }
}

