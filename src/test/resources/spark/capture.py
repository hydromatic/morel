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
# Writes script/spark-plan-generated.smli: the corpus of
# script/spark.smli built with the PySpark Connect DataFrame API, each
# query as Morel wrote it followed by the unresolved plan Spark builds
# for it, in protobuf text format. Morel renders a plan the same way, so
# the file this prints should equal the committed one; a difference is
# the translator and Spark disagreeing, and this is the only thing that
# says which of them is right.
#
#   python3 -m venv pyenv && pyenv/bin/pip install pyspark-client
#   g=src/test/resources/script/spark-plan-generated.smli
#   pyenv/bin/python src/test/resources/spark/capture.py > /tmp/generated
#   diff $g /tmp/generated
#
# The lightweight client is all it needs: no server is contacted, the
# client's config and column-validation round trips being stubbed, and
# plans built offline. With --execute the queries also run against a
# Spark Connect server at localhost:15002 (started by start-spark.sh,
# which seeds the scott database), and their rows go to stderr so that
# the plans can still be diffed.
import os, re, sys
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

def canon(text, rows):
    # plan ids are per-session counters; renumber in order of appearance
    ids = {}
    def sub(m):
        ids.setdefault(m.group(1), len(ids))
        return "plan_id: %d" % ids[m.group(1)]
    text = re.sub(r"plan_id: (\d+)", sub, text)
    # The rows of a local relation are an Arrow batch, and Morel prints how
    # many there are rather than the bytes; a plan is read, not decoded.
    if 'data: "' in text:
        if rows is None:
            raise Exception("local relation, but no row count given")
        text = re.sub(r'data: "(?:[^"\\]|\\.)*"', "data: [%d rows]" % rows,
                      text)
    return text

# The queries are numbered as they are written, except that two which
# pin one decision -- the two halves of query 15 -- share a number, and
# say so by sharing a title.
titles = []

def show(df, title, morel, rows=None):
    if not titles or titles[-1] != title:
        titles.append(title)
    name = "Query %d: %s" % (len(titles), title)
    # Morel prints a plan as a raw string literal, and its pretty-printer
    # breaks the line before one this long.
    plan = canon(text_format.MessageToString(
        df._plan.to_proto(spark._client)), rows).rstrip("\n")
    lines = plan.split("\n")
    print("(*) " + name)
    print("(spark.connection.prepare (fn () =>")
    for line in morel.split("\n"):
        print("  " + line)
    print(")).toString ();")
    print("> val it =")
    print(">   {_|")
    for line in lines[:-1]:
        print("> " + line)
    print("> " + lines[-1] + "|_} : string")
    print()
    if EXECUTE:
        print("=== %s ===" % name, file=sys.stderr)
        for row in df.collect():
            print("  ", tuple(row), file=sys.stderr)

# Every scan is aliased by its Morel binder, and every column reference
# is qualified by that alias. This is the only form in which Spark 4.0.0
# resolves the outer reference of a correlated subquery (query 5) when
# both sides have a column of the same name; qualifying by plan id
# fails with CANNOT_RESOLVE_DATAFRAME_COLUMN.
e = spark.table("scott.emps").alias("e")
d = spark.table("scott.depts").alias("d")

# Generate the license header and preamble from the existing file.
SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "..", "script", "spark-plan-generated.smli")
with open(SCRIPT) as f:
    HEADER = f.read().split("\n(*) Query 1:")[0]

print(HEADER)

t = "filter and project over an inline relation."
m = """from r in [{i = 1, s = "one", b = true, x = 1.5},
           {i = 2, s = "two", b = false, x = 2.5},
           {i = 3, s = "three", b = true, x = 3.5}]
  where r.b andalso r.i > 1
  yield {r.s, y = r.x * 2.0}"""
local_schema = StructType([
    StructField("b", BooleanType(), False),
    StructField("i", IntegerType(), False),
    StructField("s", StringType(), False),
    StructField("x", DoubleType(), False)])
local_rows = [(True, 1, "one", 1.5), (False, 2, "two", 2.5),
              (True, 3, "three", 3.5)]
r = spark.createDataFrame(local_rows, local_schema).alias("r")
q = (r.filter(F.col("r.b") & (F.col("r.i") > 1))
      .select(F.col("r.s"), (F.col("r.x") * 2.0).alias("y")))
show(q, t, m, len(local_rows))

t = "equijoin."
m = """from e in spark.catalog.scott.emps
  join d in spark.catalog.scott.depts on e.deptno = d.deptno
  yield {e.ename, d.dname}"""
q = (e.join(d, F.col("e.deptno") == F.col("d.deptno"))
      .select(F.col("d.dname"), F.col("e.ename")))
show(q, t, m)

t = "group by with aggregates."
m = """from e in spark.catalog.scott.emps
  group e.deptno
    compute {c = count over (), total = sum over e.sal, top = max over e.sal}"""
q = e.groupBy(F.col("e.deptno")).agg(
    F.count(F.lit(1)).alias("c"),
    F.max(F.col("e.sal")).alias("top"),
    F.sum(F.col("e.sal")).alias("total"))
show(q, t, m)

t = "sort and limit."
m = """from e in spark.catalog.scott.emps
  yield {e.ename, e.sal}
  order (DESC sal, ename)
  take 3"""
# The projection rebinds, so the sort keys are the projected names,
# unqualified.
q = (e.select(F.col("e.ename"), F.col("e.sal"))
      .orderBy(F.col("sal").desc(), F.col("ename").asc()).limit(3))
show(q, t, m)

t = "correlated exists subquery."
m = """from d in spark.catalog.scott.depts
  where (exists e in spark.catalog.scott.emps
         where e.deptno = d.deptno andalso e.job = "CLERK")
  yield d.dname"""
inner = e.where((F.col("e.deptno") == F.col("d.deptno")) & (F.col("e.job") == "CLERK"))
q = d.where(inner.exists()).select(F.col("d.dname"))
show(q, t, m)

# The remaining queries extend the corpus (spark-plan-generated.smli,
# spark-exec.smli).
# A Morel decimal literal is arbitrary-precision, so it is emitted with the
# narrowest decimal type that holds it, as Spark's parser gives `2BD`;
# F.lit would default to decimal(10, 0) whatever the value.
def dec(s):
    d = Decimal(s)
    scale = max(-d.as_tuple().exponent, 0)
    return Column(
        LiteralExpression(d, DecimalType(max(len(d.as_tuple().digits),
                                             scale + 1), scale)))

t = "order, skip, take, then project."
m = """from e in spark.catalog.scott.emps
  order e.empno
  skip 2
  take 3
  yield {e.empno, e.ename}"""
q = (e.orderBy(F.col("e.empno")).offset(2).limit(3)
      .select(F.col("e.empno"), F.col("e.ename")))
show(q, t, m)

t = "distinct, a group with no aggregates."
m = """from e in spark.catalog.scott.emps group e.job"""
q = e.select(F.col("e.job")).dropDuplicates()
show(q, t, m)

t = "left join."
m = """from d in spark.catalog.scott.depts
  left join e in spark.catalog.scott.emps on d.deptno = e.deptno
  where d.deptno >= 30
  yield {d.dname, e}"""
# The absent side is null, and crosses to Morel as an option of the
# record; so the right row is a struct that is null when the join found
# nothing. The witness -- the column whose nullness says the join found
# nothing -- has to be one that is not nullable in itself; comm is, so
# it is deptno.
emp_fields = ["comm", "deptno", "empno", "ename", "hiredate", "job", "mgr", "sal"]
q = (d.join(e, F.col("d.deptno") == F.col("e.deptno"), "left")
      .where(F.col("d.deptno") >= 30)
      .select(
          F.col("d.dname"),
          F.when(F.col("e.deptno").isNull(), F.lit(None))
           .otherwise(F.struct(*[F.col("e." + f).alias(f) for f in emp_fields]))
           .alias("e")))
show(q, t, m)

t = "union, of bags, so every row is kept."
m = """from e in spark.catalog.scott.emps where e.deptno = 10 yield {n = e.empno}
  union (from d in spark.catalog.scott.depts yield {n = d.deptno})"""
q = (e.where(F.col("e.deptno") == 10).select(F.col("e.empno").alias("n"))
      .union(d.select(F.col("d.deptno").alias("n"))))
show(q, t, m)

t = "arithmetic, including div and mod."
m = """from e in spark.catalog.scott.emps
  where e.deptno = 10
  yield {e.empno, w = ~e.empno, x = e.sal * decimal "2", y = e.empno div 7,
         z = e.empno mod 7}"""
# Morel's div and mod floor, and Spark's operators truncate, so the
# function table rewrites them into SQL text, which crosses as an
# expression string.
q = (e.where(F.col("e.deptno") == 10)
       .select(
           F.col("e.empno"),
           (-F.col("e.empno")).alias("w"),
           (F.col("e.sal") * dec("2")).alias("x"),
           F.expr("cast(floor(e.empno / 7) as int)").alias("y"),
           F.expr("((e.empno % 7 + 7) % 7)").alias("z")))
show(q, t, m)

t = "string functions."
m = """from e in spark.catalog.scott.emps
  where String.isPrefix "S" e.ename
  yield {e.ename, n = String.size e.ename,
         s = String.substring (e.ename, 0, 2), t = e.ename ^ "!"}"""
# isPrefix and substring are rewritten functions -- Morel's arguments
# are in the other order, and its substring counts from zero -- so they
# cross as SQL.
q = (e.where(F.expr("startswith(e.ename, 'S')"))
       .select(
           F.col("e.ename"),
           F.length(F.col("e.ename")).alias("n"),
           F.expr("substring(e.ename, 0 + 1, 2)").alias("s"),
           F.concat(F.col("e.ename"), F.lit("!")).alias("t")))
show(q, t, m)

t = "elem, an IN subquery."
m = """from e in spark.catalog.scott.emps
  where e.deptno elem
    (from d in spark.catalog.scott.depts
     where d.loc = "CHICAGO" yield d.deptno)
  yield e.ename"""
# Spark Connect 4.0 has no IN subquery, so it is an EXISTS subquery with
# an equality.
q = (e.where(d.where((F.col("d.loc") == "CHICAGO")
                       & (F.col("d.deptno") == F.col("e.deptno"))).exists())
       .select(F.col("e.ename")))
show(q, t, m)

t = "a correlated scalar subquery."
m = """from d in spark.catalog.scott.depts
  yield {d.dname,
         n = (from e in spark.catalog.scott.emps where e.deptno = d.deptno
              compute count over ())}"""
q = d.select(
    F.col("d.dname"),
    e.where(F.col("e.deptno") == F.col("d.deptno"))
     .agg(F.count(F.lit(1)).alias("count")).scalar().alias("n"))
show(q, t, m)

t = "a result that is not a collection."
m = """from e in spark.catalog.scott.emps compute count over ()"""
# The column takes the name Morel's `compute` gives the aggregate, as in
# the query above; what the value is bound to outside the query is
# Morel's own business, and nothing reads the name of a column it takes
# whole.
q = e.agg(F.count(F.lit(1)).alias("count"))
show(q, t, m)

t = "except and intersect, of bags."
m = """from e in spark.catalog.scott.emps yield {n = e.deptno}
  except (from d in spark.catalog.scott.depts
          where d.deptno > 20 yield {n = d.deptno})"""
right = d.where(F.col("d.deptno") > 20).select(F.col("d.deptno").alias("n"))
q = e.select(F.col("e.deptno").alias("n")).exceptAll(right)
show(q, t, m)

t = "except and intersect, of bags."
m = """from e in spark.catalog.scott.emps yield {n = e.deptno}
  intersect (from d in spark.catalog.scott.depts
             where d.deptno > 20 yield {n = d.deptno})"""
q = e.select(F.col("e.deptno").alias("n")).intersectAll(right)
show(q, t, m)

t = "group by two keys with two aggregates, then sort."
m = """from e in spark.catalog.scott.emps
  group {e.deptno, e.job}
    compute {n = count over (), s = sum over e.sal}
  order (deptno, job)"""
q = (e.groupBy(F.col("e.deptno"), F.col("e.job"))
       .agg(F.count(F.lit(1)).alias("n"), F.sum(F.col("e.sal")).alias("s"))
       .orderBy(F.col("deptno"), F.col("job")))
show(q, t, m)

print("(*) End spark-plan-generated.smli")

# End capture.py
