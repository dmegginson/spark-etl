
package io.frama.parisni.spark.dataframe

import com.holdenkarau.spark.testing._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.scalatest.FunSuite

class AppTest extends FunSuite with SharedSparkContext with DataFrameSuiteBase {

  override implicit def reuseContextIfPossible: Boolean = true

  val dfTool = DFTool
  test("test reorder") {

    val inputDF = sqlContext.sql("select 1 as c2, 2 as c1")
    val schema = StructType(StructField("c1", IntegerType) :: StructField("c2", IntegerType) :: Nil)
    val resultDF = sqlContext.sql("select 2 as c1, 1 as c2")

    assertDataFrameEquals(resultDF, DFTool.reorderColumns(inputDF, schema))

  }

  test("test cast") {

    val inputDF = sqlContext.sql("select '2' as c1, '1' as c2")
    val schema = StructType(StructField("c1", IntegerType, false) :: StructField("c2", IntegerType, false) :: Nil)
    val resultDF = sqlContext.sql("select 2 as c1, 1 as c2")
    val testDF = DFTool.castColumns(inputDF, schema)
    assertDataFrameEquals(resultDF, testDF)

  }

  test("test columns missing") {

    val inputDF = sqlContext.sql("select 1 as c1, 2 as c2")
    val schema = StructType(StructField("c1", IntegerType) :: StructField("c2", IntegerType) :: Nil)

    DFTool.existColumns(inputDF, schema)

  }

  test("test mandatory columns") {
    val mb = new MetadataBuilder()
    val m = mb.putNull("default").build
    val schema = StructType(StructField("c1", IntegerType, false) :: StructField("c2", IntegerType, true, m) :: Nil)
    val mandatorySchema = StructType(StructField("c1", IntegerType) :: Nil)

    assert(DFTool.getMandatoryColumns(schema).toDDL == mandatorySchema.toDDL)

  }

  test("test optional columns") {
    val mb = new MetadataBuilder()
    val m = mb.putNull("default").build
    val schema = StructType(StructField("c1", IntegerType, false) :: StructField("c2", IntegerType, true, m) :: Nil)
    val optionalSchema = StructType(StructField("c2", IntegerType, true, m) :: Nil)

    assert(DFTool.getOptionalColumns(schema).toDDL == optionalSchema.toDDL)

  }

  test("test add columns") {
    val mb = new MetadataBuilder()
    val m = mb.putNull("default").build
    val optionalSchema = StructType(StructField("c3", IntegerType, true, m) :: Nil)
    val inputDF = sqlContext.sql("select '2' as c1, '1' as c2")
    val resultDF = sqlContext.sql("select '2' as c1, '1' as c2, cast(null as int) as c3")

    assertDataFrameEquals(DFTool.addMissingColumns(inputDF, optionalSchema), resultDF)

  }

  test("test rename columns") {
    val mb = new MetadataBuilder()
    val m = mb.putNull("default").build
    val optionalSchema = StructType(StructField("c3", IntegerType, true, m) :: Nil)
    val inputDF = sqlContext.sql("select '2' as c1, '1' as c2")
    val resultDF = sqlContext.sql("select '2' as bob, '1' as jim")

    val columns = Map("c1" -> "bob", "c2" -> "jim")

    assertDataFrameEquals(DFTool.dfRenameColumn(inputDF, columns), resultDF)

  }

  test("generate case classes") {
    val struct = StructType(StructField("bob", IntegerType) :: Nil)
    val test = spark.sql("select 1 as bob, 2 as jim")
    val result = spark.sql("select 1 as bob")
    val testDF = DFTool.applySchema(test, struct)
    val resultDF = DFTool.applySchema(result, struct)
    assertDataFrameEquals(testDF, resultDF)
  }

  test("test pivot") {
    val df = spark.sql(
      """
       select 1 group, 'bob' key, 'so' value 
       union all
    		 select 2 group, 'jim' key, 'tore' value 
       """)
    DFTool.simplePivot(df, col("group"), col("key"), "array(value)", "bob" :: "jim" :: Nil).show
  }

  test("test special character columns") {
    val struct = StructType(StructField("bob.jim", IntegerType) :: Nil)
    val test = spark.sql("select 1 as bob, 2 as jim")
      .withColumn("bob.jim", col("bob"))
    val result = spark.sql("select 1 as `bob.jim`")
    val testDF = DFTool.applySchemaSoft(test, struct)
    val resultDF = DFTool.applySchemaSoft(result, struct)
    assertDataFrameEquals(testDF, resultDF)
  }
}