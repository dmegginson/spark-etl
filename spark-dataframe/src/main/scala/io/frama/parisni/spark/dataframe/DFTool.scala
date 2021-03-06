package io.frama.parisni.spark.dataframe

import java.text.Normalizer
import java.util.regex.Pattern

import com.typesafe.scalalogging.LazyLogging
import io.delta.tables.DeltaTable
import io.frama.parisni.spark.quality.Constraints
import io.frama.parisni.spark.quality.Constraints._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql._
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._

import scala.util.Try

/** Factory for [[io.frama.parisni.spark.dataframe.DFTool]] instances. */
object DFTool extends LazyLogging {

  def trimAll(df: DataFrame): _root_.org.apache.spark.sql.DataFrame = {
    df.schema.fields
      .filter(f => "string".equals(f.dataType.typeName.toLowerCase))
      .foldLeft(df) { (memoDf, colName) =>
        memoDf.withColumn(
          colName.name,
          colName.dataType.typeName.toLowerCase() match {
            case "string" =>
              expr(
                s"nullif(regexp_replace(${colName.name}, '^\\\\s+|\\\\s+$$', ''), '')"
              )
            case _ => col(colName.name)
          }
        )
      }
  }

  /**
    * Apply a schema on the given DataFrame. It reorders the
    * columns, cast them, validates the non-nullable columns.
    *
    * @param df     their name
    * @param schema the schema as a StructType
    * @return a validated DataFrame
    */
  def applySchema(df: DataFrame, schema: StructType): DataFrame = {
    val dfReorder = applySchemaSoft(df, schema)
    val result = castColumns(dfReorder, schema)

    result
  }

  /**
    * Apply a schema on the given DataFrame. It reorders the
    * columns, removes the bad columns, add the defaults values
    *
    * @param df     their name
    * @param schema the schema as a StructType
    * @return a validated DataFrame
    */
  def applySchemaSoft(df: DataFrame, schema: StructType): DataFrame = {
    val mandatoryColumns = DFTool.getMandatoryColumns(schema)
    val optionalColumns = DFTool.getOptionalColumns(schema)

    existColumns(df, mandatoryColumns)
    val dfWithoutCol = removeBadColumns(df, schema)
    val dfWithCol = addMissingColumns(dfWithoutCol, optionalColumns)
    val dfReorder = reorderColumns(dfWithCol, schema)

    dfReorder
  }

  /**
    * Validate schema on the given DataFrame. It verifies if
    * the columns exists independently on the schema.
    *
    * @param df            their name
    * @param columnsNeeded the schema as a StructType
    * @return a validated DataFrame
    */
  def existColumns(df: DataFrame, columnsNeeded: StructType) = {
    var tmp = ""
    val columns = df.columns
    for (column <- columnsNeeded.fieldNames) {
      if (!columns.contains(column))
        tmp += column + ", "
    }
    if (tmp != "") {
      throw new Exception(f"Missing columns in the data: [$tmp]")
    }
  }

  /**
    * Look for mandatory columns within the schema.
    *
    * @param schema : a StructType
    * @return a StructType
    */
  def getMandatoryColumns(schema: StructType): StructType = {
    StructType(schema.filter(f => !f.metadata.contains("default")))
  }

  /**
    * Look for optionnal columns within the schema.
    *
    * @param schema : a StructType
    * @return a StructType
    */
  def getOptionalColumns(schema: StructType): StructType = {
    StructType(schema.filter(f => f.metadata.contains("default")))
  }

  /**
    * Remove unspecified columns
    *
    * @param df     : a DataFrame
    * @param schema : StructType
    * @return a DataFrame
    */
  def removeBadColumns(df: DataFrame, schema: StructType): DataFrame = {
    var result = df
    val dfSchema = df.schema
    dfSchema.fields.foreach(f => {
      logger.debug(f"Added ${f.name} column")
      if (!schema.fieldNames.contains(f.name))
        result = result.drop("`" + f.name + "`")
    })
    result
  }

  /**
    * Apply a schema on the given DataFrame. It casts the columns.
    *
    * @param df     their name
    * @param schema the schema as a StructType
    * @return a validated DataFrame
    */
  def castColumns(df: DataFrame, schema: StructType): DataFrame = {
    val newDf = validateNull(df, schema)
    val trDf = newDf.schema.fields.foldLeft(df) { (df, s) =>
      df.withColumn(s.name, df(s.name).cast(s.dataType))
    }
    validateNull(trDf, schema)
  }

  /**
    * Apply a schema on the given DataFrame. It validates
    * the non-null columns.
    *
    * @param df     their name
    * @param schema the schema as a StructType
    * @return a validated DataFrame
    */
  def validateNull(df: DataFrame, schema: StructType): DataFrame = {
    df.sparkSession.createDataFrame(df.rdd, schema)

  }

  def unionDataFrame(sourceDf: DataFrame, targetDf: DataFrame): DataFrame = {
    val missingLeft = getMissingColumns(sourceDf, targetDf)
    val missingRight = getMissingColumns(targetDf, sourceDf)

    val sourceDfPlus = addMissingColumns(sourceDf, missingLeft)
    val targetDfPlus = addMissingColumns(targetDf, missingRight)

    val right = reorderColumns(targetDfPlus, sourceDfPlus.schema)

    sourceDfPlus.union(right)
  }

  /**
    * Apply a schema on the given DataFrame. It reorders the
    * columns.
    *
    * @param df     their name
    * @param schema the schema as a StructType
    * @return a validated DataFrame
    */
  def reorderColumns(df: DataFrame, schema: StructType): DataFrame = {
    val reorderedColumnNames = schema.fieldNames.map(x => "`" + x + "`")
    df.select(reorderedColumnNames.head, reorderedColumnNames.tail: _*)
  }

  /**
    * Add missing columns and apply the default value
    * specified as a Metadata passed with the StrucType
    *
    * @param df            : a DataFrame
    * @param missingSchema : StructType
    * @return a DataFrame
    */
  def addMissingColumns(df: DataFrame, missingSchema: StructType): DataFrame = {
    var result = df
    missingSchema.fields.foreach(f => {
      logger.debug(f"Added ${f.name} column")
      if (!df.columns.contains(f.name))
        result = result.withColumn(
          f.name,
          if (f.metadata.contains("default")) {
            lit(f.metadata.getString("default")).cast(f.dataType)
          } else {
            lit(null)
          }
        )

    })
    result
  }

  def getMissingColumns(
      sourceDf: DataFrame,
      targetDf: DataFrame
  ): StructType = {
    StructType(
      for {
        targetFields <- targetDf.schema.fields
        if !sourceDf.schema.fields.map(_.name).contains(targetFields.name)
      } yield {
        targetFields
      }
    )
  }

  /**
    * Create an empty DataFrame accordingly to a schema.
    *
    * @param spark  : a SparkSession
    * @param schema : a schema as a StructType
    * @return a DataFrame
    */
  def createEmptyDataFrame(
      spark: SparkSession,
      schema: StructType
  ): DataFrame = {
    spark.createDataFrame(spark.sparkContext.emptyRDD[Row], schema)
  }

  /**
    * Remove rows from a DataFrame, given a specified
    * colum
    *
    * @param df     : a DataFrame
    * @param column : a String
    * @return a DataFrameType
    */
  def removeNullRows(df: DataFrame, column: String): DataFrame = {
    df.createOrReplaceTempView("nullTmp")
    val spark = df.sparkSession
    val nulltmp = spark.sql(f"select * from nullTmp where $column IS NULL")
    logger.warn(nulltmp.count + " missing rows")
    spark.sql(
      f"select * from nullTmp where $column IS NOT NULL and trim($column) !=''"
    )
  }

  /**
    * Remove duplicates and show a report
    *
    * @param df     : a DataFrame
    * @param column : the columns not to be duplicated
    * @return a DataFrameType
    */
  def removeDuplicate(df: DataFrame, column: String*): DataFrame = {
    val tmp = df.dropDuplicates(column)

    val diff = df.count - tmp.count
    if (diff > 0) {
      println(f"removed $diff rows")
      df.except(tmp).show
    }
    tmp
  }

  /**
    * Adds a hash column based on several other columns
    *
    * @param df         DataFrame
    * @param columnName List[String] the columns not to be hashed
    * @return DataFrame
    */
  def dfAddSequence(
      df: DataFrame,
      columnName: String,
      indexBegin: Long = 0
  ): DataFrame = {
    val firstCol = df.columns(0)

    val w = Window.partitionBy("fake").orderBy(col(firstCol))
    df.withColumn("fake", lit(1))
      .withColumn(columnName, row_number().over(w).plus(indexBegin))
      .drop("fake")
  }

  /**
    * Rename multiple columns
    *
    * @param df      DataFrame
    * @param columns Map[String -> String]
    * @return DataFrame
    */
  def dfRenameColumn(df: DataFrame, columns: Map[String, String]): DataFrame = {
    var retDf = df
    columns.foreach({ f =>
      {
        retDf = retDf.withColumnRenamed(f._1, f._2)
      }
    })
    retDf
  }

  /*
   * from pyspark.sql.functions import col,collect_list,regexp_replace,map_from_entries,struct,count

def pivot(df, group_by, key, aggFunction, levels=[]):
    if not levels:
        levels = [row[key] for row in df.filter(col(key).isNotNull()).groupBy(col(key)).agg(count(key)).select(key).collect()]
    return df.filter(col(key).isin(*levels) == True).groupBy(group_by)
    .agg(map_from_entries(collect_list(struct(key, aggFunction))).alias("group_map")).select([group_by] + ["group_map." + l for l in levels])
   */
  def simplePivot(
      df: DataFrame,
      groupBy: Column,
      key: Column,
      aggCol: String,
      _levels: List[String] = Nil
  ): DataFrame = {
    val levels =
      if (_levels.isEmpty)
        df.filter(key.isNotNull)
          .select(key)
          .distinct()
          .collect()
          .map(row => row.getString(0))
          .toList
      else _levels

    df.filter(key.isInCollection(levels))
      .groupBy(groupBy)
      .agg(
        map_from_entries(collect_list(struct(key, expr(aggCol))))
          .alias("group_map")
      )
      .select(groupBy.toString, levels.map(f => "group_map." + f): _*)
  }

  def fileExists(spark: SparkSession, filePath: String): Boolean = {
    val defaultFSConf = spark.sessionState.newHadoopConf().get("fs.defaultFS")
    val fsConf = if (filePath.startsWith("file:")) {
      "file:///"
    } else {
      defaultFSConf
    }
    val conf = new Configuration()
    conf.set("fs.defaultFS", fsConf)
    val fs = FileSystem.get(conf)

    fs.exists(new Path(filePath))
  }

  def normalizeColumnNames(df: DataFrame): DataFrame = {
    def removeAccent(str: String) = {
      val strTemp = Normalizer.normalize(str, Normalizer.Form.NFD);
      val pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
      pattern.matcher(strTemp).replaceAll("");
    }

    val cols = df.columns
    cols.foldLeft(df)((df, c) => {
      df.withColumnRenamed(
        c,
        removeAccent(c.toLowerCase()).replaceAll("[^\\w]+", "_")
      )
    })
  }

  def toDate(c: Column, format: String): Column = {
    expr(
      "TO_timestamp(CAST(UNIX_TIMESTAMP(`" + c
        .toString() + "`, '" + format + "') AS TIMESTAMP))"
    )
  }

  def deltaScd1(
      df: DataFrame,
      table: String,
      primaryKeys: List[String],
      database: String
  ) = {

    val spark = df.sparkSession
    val candidate = if (!df.columns.contains("hash")) dfAddHash(df) else df

    val (isHive, isTableExists, deltaPath) =
      if (spark.catalog.databaseExists(database)) {
        val hiveLoc = getHiveLocation(spark, getDbTable(table, database))
          .getOrElse("nothingNeeded")
        (true, spark.catalog.tableExists(database, table), hiveLoc)
      } else
        (false, tableExists(spark, database, table), database + "/" + table)

    val query = primaryKeys.map(x => (f"t.${x} = s.${x}")).mkString(" AND ")
    if (!isTableExists) {
      logger.warn(
        "Table %s does not yet exists".format(getDbTable(table, database))
      )
      if (isHive) saveHive(candidate, getDbTable(table, database), "delta")
      else
        candidate.write.mode(SaveMode.Overwrite).format("delta").save(deltaPath)

    } else {
      logger.info(
        "Merging table %s with table of %d rows".format(
          deltaPath,
          candidate.count
        )
      )
      DeltaTable
        .forPath(spark, deltaPath)
        .as("t")
        .merge(candidate.as("s"), query)
        .whenMatched("s.hash <> t.hash")
        .updateAll()
        .whenNotMatched()
        .insertAll()
        .execute()
    }

  }

  /**
    * Adds a hash column based on several other columns
    *
    * @param df               DataFrame
    * @param columnsToExclude List[String] the columns not to be hashed
    * @return DataFrame
    */
  def dfAddHash(
      df: DataFrame,
      columnsToExclude: List[String] = Nil
  ): DataFrame = {

    df.withColumn(
      "hash",
      hash(
        df.columns
          .filter(x => !columnsToExclude.contains(x))
          .map(x => col("`" + x + "`")): _*
      )
    )

  }

  def saveHiveFull(
      df: DataFrame,
      database: String,
      tableName: String,
      format: String = "parquet"
  ): Unit = {

    def write() = {

      logger.info(s"persisting $tableName")
      val spark = df.sparkSession
      //val candidate = if (!df.columns.contains("hash")) dfAddHash(df) else df

      val (isHive, isTableExists, deltaPath) =
        if (spark.catalog.databaseExists(database)) {
          val hiveLoc = getHiveLocation(spark, getDbTable(tableName, database))
            .getOrElse("nothingNeeded")
          (true, spark.catalog.tableExists(database, tableName), hiveLoc)
        } else
          (
            false,
            DFTool.tableExists(spark, database, tableName),
            database + "/" + tableName
          )

      //val query = primaryKeys.map(x => (f"t.${x} = s.${x}")).mkString(" AND ")
      if (!isTableExists) {
        logger.info(
          "Table %s does not yet exist".format(getDbTable(tableName, database))
        )
        /*if (isHive) saveHive(df, getDbTable(tableName, database), format)
        else df.write.mode(SaveMode.Overwrite).format(format).save(deltaPath)*/
        df.write.format(format).save(deltaPath)
      } else {
        df.write
          .format(format)
          .mode(SaveMode.Overwrite)
          .saveAsTable(tableName)
      }
    }

    val res = Try {
      write()
    }
    /*res.failed.getOrElse(None) match {
      case e: AnalysisException => {
        val location = DFTool.getHiveLocation(e.getMessage())
        logger.warn(s"${location} already exists")
        DFTool.removeHiveLocation(df.sparkSession, location)
        write()
      }
      case _ =>
    }*/
  }

  def getHiveLocation(spark: SparkSession, tableName: String) = {
    Try {
      val path = spark
        .sql(s"describe extended ${tableName}")
        .filter("col_name = 'Location'")
        .select("data_type")
        .collect()
        .map(row => row.getString(0))
        .mkString
      //require(path.startsWith("hdfs://"))
      path
    }
  }

  def tableExists(
      spark: SparkSession,
      deltaPath: String,
      tablePath: String
  ): Boolean = {
    if (spark.catalog.databaseExists(deltaPath))
      spark.catalog.tableExists(getDbTable(tablePath, deltaPath))
    else {
      val defaultFSConf = spark.sessionState.newHadoopConf().get("fs.defaultFS")
      val fsConf = if (deltaPath.startsWith("file:")) {
        "file:///"
      } else {
        defaultFSConf
      }
      val conf = new Configuration()
      conf.set("fs.defaultFS", fsConf)
      val fs = FileSystem.get(conf)

      fs.exists(new Path(deltaPath + tablePath))
    }
  }

  def getDbTable(table: String, db: String = "default") = s"`${db}`.`${table}`"

  def getArchived(
      colJoin: Seq[String],
      newDf: DataFrame,
      dfs: DataFrame*
  ): DataFrame = {
    var tmp: DataFrame = newDf
    dfs.foreach { oldDf =>
      tmp = getArch(tmp, oldDf, colJoin)
    }
    tmp
  }

  def getArch(
      newDf: DataFrame,
      oldDf: DataFrame,
      colJoin: Seq[String]
  ): DataFrame = {
    val archRows =
      oldDf // prendre les lignes qui ne sont pas dans la nouvelle table recuperee
        .join(newDf, colJoin, "left_anti")
    unionPro(newDf :: archRows :: Nil)
  }

  def unionPro(DFList: List[DataFrame]): DataFrame = {

    /**
      * This Function Accepts DataFrame with same or Different Schema/Column Order.With some or none common columns
      * Creates a Unioned DataFrame
      */
    val spark = DFList.head.sparkSession

    val MasterColList: Array[String] =
      DFList.map(_.columns).reduce((x, y) => (x.union(y))).distinct

    def unionExpr(
        myCols: Seq[String],
        allCols: Seq[String]
    ): Seq[org.apache.spark.sql.Column] = {
      allCols.toList.map(x =>
        x match {
          case x if myCols.contains(x) => col(x)
          case _                       => lit(null).as(x)
        }
      )
    }

    // Create EmptyDF , ignoring different Datatype in StructField and treating them same based on Name ignoring cases
    val masterSchema = StructType(
      DFList
        .map(_.schema.fields)
        .reduce((x, y) => (x.union(y)))
        .groupBy(_.name.toUpperCase)
        .map(_._2.head)
        .toArray
    )
    val masterEmptyDF = spark
      .createDataFrame(spark.sparkContext.emptyRDD[Row], masterSchema)
      .select(MasterColList.head, MasterColList.tail: _*)
    DFList
      .map(df => df.select(unionExpr(df.columns, MasterColList): _*))
      .foldLeft(masterEmptyDF)((x, y) => x.union(y))
  }

  def saveAndValidate(hiveTable: String, df: DataFrame, schema: Schema) = {
    val spark = df.sparkSession
    saveHive(df, hiveTable)
    validate(spark, hiveTable, schema)
  }

  def validate(spark: SparkSession, hiveTable: String, schema: Schema): Unit = {
    logger.info(s"validating $hiveTable")
    assert(Constraints.fromSchema(schema)(spark.table(hiveTable)).isSuccess)
  }

  /**
    * Writes a parquet table even if the table already exists
    * The strategy might not work as expected because the hive table is not created.
    * A better strategy may be to catch the error, parse the location and remove it.
    *
    * @param df
    * @param tableName
    */
  def saveHive(
      df: DataFrame,
      tableName: String,
      format: String = "parquet",
      partitions: Int = 200
  ): Unit = {

    def write() = {
      logger.info(s"persisting $tableName")
      df.repartition(partitions)
        .write
        .format(format)
        .mode(SaveMode.Overwrite)
        .saveAsTable(tableName)
    }

    val res = Try {
      write()
    }
    res.failed.getOrElse(None) match {
      case e: AnalysisException => {
        val location = getHiveLocation(e.getMessage())
        logger.warn(s"${location} already exists")
        removeHiveLocation(df.sparkSession, location)
        write()
      }
      case _ =>
    }
  }

  def getHiveLocation(errorMessage: String) = {
    val reg = """.*The associated location\('([^']+)'\) already exists.*""".r
    errorMessage match {
      case reg(url) => url
      case _        => throw new RuntimeException(errorMessage)
    }
  }

  def removeHiveLocation(spark: SparkSession, location: String) = {
    val defaultFSConf = spark.sessionState.newHadoopConf().get("fs.defaultFS")
    val fsConf = if (location.startsWith("file:")) {
      "file:///"
    } else {
      defaultFSConf
    }
    val conf = new Configuration()
    conf.set("fs.defaultFS", fsConf)
    val fs = FileSystem.get(conf)

    logger.warn(s"removing ${location}")
    fs.delete(new Path(location), true)
  }

  def save(
      hiveTable: String,
      df: DataFrame,
      format: String = "parquet",
      partitions: Int = 200
  ): Unit = {
    if (hiveTable.contains("/")) saveFile(hiveTable, df, format, partitions)
    else saveHive(df, hiveTable, format, partitions)
  }

  def saveFile(
      file: String,
      df: DataFrame,
      format: String = "parquet",
      partitions: Int = 200
  ) = {
    df.repartition(partitions)
      .write
      .format(format)
      .mode(SaveMode.Overwrite)
      .save(file)
  }

  def read(
      spark: SparkSession,
      databaseOrPath: String,
      table: String,
      format: String = "parquet"
  ) = {
    if (spark.catalog.databaseExists(databaseOrPath))
      spark.table(getDbTable(table, databaseOrPath))
    else spark.read.format(format).load(databaseOrPath + "/" + table)
  }
}
