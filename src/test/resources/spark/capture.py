# Licensed to Julian Hyde under one or more contributor license
# agreements.  See the NOTICE file distributed with this work
# for additional information regarding copyright ownership.
# Julian Hyde licenses this file to you under the Apache
# License, Version 2.0 (the "License"); you may not use this
# file except in compliance with the License.  You may obtain a
# copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied.  See the License for the specific
# language governing permissions and limitations under the
# License.
#
# Builds the five seed queries of script/spark.smli with the PySpark
# Connect DataFrame API and prints each unresolved plan in protobuf
# text format; seed-plans.txt is this output with a LocalRelation's
# Arrow payload replaced by its row count.
#
# Needs only the lightweight client:
#   python3 -m venv pyenv && pyenv/bin/pip install pyspark-client
#   pyenv/bin/python capture.py
# Without arguments no server is contacted: the client's config and
# column-validation round trips are stubbed, and plans are built
# offline. With --execute, the queries also run against a Spark
# Connect server at localhost:15002 (started by start-spark.sh, which
# seeds the scott database) and print their rows.
import re, sys
from decimal import Decimal
from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from pyspark.sql.types import *
from pyspark.sql.connect.column import Column
from pyspark.sql.connect.expressions import LiteralExpression
from google.protobuf import text_format

import pyspark.errors.utils as _eu
_eu.is_debugging_enabled = lambda: False  # avoid a config RPC; plans build offline
spark = SparkSession.builder.remote("sc://localhost:15002").create()
EXECUTE = "--execute" in sys.argv
if not EXECUTE:
    spark.is_mock_session = True  # skip server-side column validation
    _defaults = {
        "spark.sql.timestampType": "TIMESTAMP_LTZ",
        "spark.sql.session.timeZone": "UTC",
        "spark.sql.session.localRelationCacheThreshold": "67108864",
        "spark.sql.execution.pandas.convertToArrowArraySafely": "false",
        "spark.sql.execution.pandas.inferPandasDictAsMap": "false",
        "spark.sql.pyspark.inferNestedDictAsStruct.enabled": "false",
        "spark.sql.pyspark.legacy.inferArrayTypeFromFirstElement.enabled": "false",
        "spark.sql.pyspark.legacy.inferMapTypeFromFirstPair.enabled": "false",
        "spark.sql.execution.arrow.useLargeVarTypes": "false"}
    spark._client.get_config_dict = lambda *keys: {k: _defaults.get(k) for k in keys}

def canon(text):
    # plan ids are per-session counters; renumber in order of appearance
    ids = {}
    def sub(m):
        ids.setdefault(m.group(1), len(ids))
        return "plan_id: %d" % ids[m.group(1)]
    return re.sub(r"plan_id: (\d+)", sub, text)

def show(name, df, execute=None):
    execute = EXECUTE if execute is None else execute
    print("=== %s ===" % name)
    print(canon(text_format.MessageToString(df._plan.to_proto(spark._client))))
    if execute:
        for row in df.collect():
            print("  ", tuple(row))
    print()

# Every scan is aliased by its Morel binder, and every column reference
# is qualified by that alias. This is the only form in which Spark 4.0.0
# resolves the outer reference of a correlated subquery (query 5) when
# both sides have a column of the same name; qualifying by plan id
# fails with CANNOT_RESOLVE_DATAFRAME_COLUMN.
e = spark.table("scott.emps").alias("e")
d = spark.table("scott.depts").alias("d")

# Query 1: filter and project over an inline relation.
local_schema = StructType([
    StructField("b", BooleanType(), False),
    StructField("i", IntegerType(), False),
    StructField("s", StringType(), False),
    StructField("x", DoubleType(), False)])
r = spark.createDataFrame(
    [(True, 1, "one", 1.5), (False, 2, "two", 2.5), (True, 3, "three", 3.5)],
    local_schema).alias("r")
q1 = (r.filter(F.col("r.b") & (F.col("r.i") > 1))
      .select(F.col("r.s"), (F.col("r.x") * 2.0).alias("y")))
show("q1", q1)

# Query 2: equijoin.
q2 = (e.join(d, F.col("e.deptno") == F.col("d.deptno"))
      .select(F.col("d.dname"), F.col("e.ename")))
show("q2", q2)

# Query 3: group by with aggregates.
q3 = e.groupBy(F.col("e.deptno")).agg(
    F.count(F.lit(1)).alias("c"),
    F.max(F.col("e.sal")).alias("top"),
    F.sum(F.col("e.sal")).alias("total"))
show("q3", q3)

# Query 4: sort and limit. The projection rebinds, so the sort keys are
# the projected names, unqualified.
q4 = (e.select(F.col("e.ename"), F.col("e.sal"))
      .orderBy(F.col("sal").desc(), F.col("ename").asc()).limit(3))
show("q4", q4)

# Query 5: correlated exists subquery.
inner = e.where((F.col("e.deptno") == F.col("d.deptno")) & (F.col("e.job") == "CLERK"))
q5 = d.where(inner.exists()).select(F.col("d.dname"))
show("q5", q5)

# The remaining queries extend the corpus (spark-plan.smli, spark-exec.smli).
# A Morel decimal literal is arbitrary-precision, so it is emitted with the
# narrowest decimal type that holds it, as Spark's parser gives `2BD`;
# F.lit would default to decimal(10, 0) whatever the value.
def dec(s):
    d = Decimal(s)
    scale = max(-d.as_tuple().exponent, 0)
    return Column(
        LiteralExpression(d, DecimalType(max(len(d.as_tuple().digits),
                                             scale + 1), scale)))

# Query 6: order, skip, take, then project.
q6 = (e.orderBy(F.col("e.empno")).offset(2).limit(3)
      .select(F.col("e.empno"), F.col("e.ename")))
show("q6", q6)

# Query 7: distinct (a group with no aggregates).
q7 = e.select(F.col("e.job")).dropDuplicates()
show("q7", q7)

# Query 8: left join. The absent side is null, and crosses to Morel as an
# option of the record; so the right row is a struct that is null when the
# join found nothing.
# The witness -- the column whose nullness says the join found nothing -- has
# to be one that is not nullable in itself; comm is, so it is deptno.
emp_fields = ["comm", "deptno", "empno", "ename", "hiredate", "job", "mgr", "sal"]
q8 = (d.join(e, F.col("d.deptno") == F.col("e.deptno"), "left")
      .where(F.col("d.deptno") >= 30)
      .select(
          F.col("d.dname"),
          F.when(F.col("e.deptno").isNull(), F.lit(None))
           .otherwise(F.struct(*[F.col("e." + f).alias(f) for f in emp_fields]))
           .alias("e")))
show("q8", q8)

# Query 9: union (of bags, so all rows are kept).
q9 = (e.where(F.col("e.deptno") == 10).select(F.col("e.empno").alias("n"))
      .union(d.select(F.col("d.deptno").alias("n"))))
show("q9", q9)

# Query 10: arithmetic, including the templates for div and mod.
# Morel's div and mod floor, and Spark's operators truncate, so the function
# table rewrites them into SQL text, which crosses as an expression string.
q10 = (e.where(F.col("e.deptno") == 10)
       .select(
           F.col("e.empno"),
           (-F.col("e.empno")).alias("w"),
           (F.col("e.sal") * dec("2")).alias("x"),
           F.expr("cast(floor(e.empno / 7) as int)").alias("y"),
           F.expr("((e.empno % 7 + 7) % 7)").alias("z")))
show("q10", q10)

# Query 11: string functions.
# isPrefix and substring are rewritten functions -- Morel's arguments are in
# the other order, and its substring counts from zero -- so they cross as SQL.
q11 = (e.where(F.expr("startswith(e.ename, 'S')"))
       .select(
           F.col("e.ename"),
           F.length(F.col("e.ename")).alias("n"),
           F.expr("substring(e.ename, 0 + 1, 2)").alias("s"),
           F.concat(F.col("e.ename"), F.lit("!")).alias("t")))
show("q11", q11)

# Query 12: "elem". Spark Connect 4.0 has no IN subquery, so it is an
# EXISTS subquery with an equality.
q12 = (e.where(d.where((F.col("d.loc") == "CHICAGO")
                       & (F.col("d.deptno") == F.col("e.deptno"))).exists())
       .select(F.col("e.ename")))
show("q12", q12)

# Query 13: a correlated scalar subquery.
q13 = d.select(
    F.col("d.dname"),
    e.where(F.col("e.deptno") == F.col("d.deptno"))
     .agg(F.count(F.lit(1)).alias("count")).scalar().alias("n"))
show("q13", q13)

# Query 14: a non-collection result is a single-row, single-column relation.
# The column takes the name Morel's `compute` gives the aggregate, as in the
# query above; what the value is bound to outside the query is Morel's own
# business, and nothing reads the name of a column it takes whole.
q14 = e.agg(F.count(F.lit(1)).alias("count"))
show("q14", q14)

# Query 15: except and intersect, of bags.
right = d.where(F.col("d.deptno") > 20).select(F.col("d.deptno").alias("n"))
q15a = e.select(F.col("e.deptno").alias("n")).exceptAll(right)
show("q15a", q15a)
q15b = e.select(F.col("e.deptno").alias("n")).intersectAll(right)
show("q15b", q15b)

# Query 16: group by two keys with two aggregates, then sort.
q16 = (e.groupBy(F.col("e.deptno"), F.col("e.job"))
       .agg(F.count(F.lit(1)).alias("n"), F.sum(F.col("e.sal")).alias("s"))
       .orderBy(F.col("deptno"), F.col("job")))
show("q16", q16)

# End capture.py
