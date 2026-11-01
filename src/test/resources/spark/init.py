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
# Driver program for the Morel test container. start-spark.sh runs it
# with spark-submit and the Spark Connect plugin, so the Connect server
# runs in this program's SparkContext and clients see the tables it
# creates. It creates the tables, prints a marker line, and then
# waits forever, keeping the server up.
#
# Tables:
#   scott.emps, scott.depts, scott.bonuses, scott.salgrades
#              Morel's built-in "scott" data set.
#   zoo        In the default database. One column per Spark type. Row 1
#              has typical values, row 2 edge values (extremes, NaN, empty,
#              non-ASCII), and row 3 nulls in every column but id.
#   nulls      In the default database. Two int columns, a null in one,
#              and a property that says the table holds none.
#
# Nullability: Spark's built-in catalog does not keep NOT NULL for
# tables stored as files, so every column of these tables reads back as
# nullable, whatever the schema said when it was created. Each table
# therefore carries a "morel.nullable" property naming the columns that
# hold nulls, which is what Morel reads; the scott tables' properties
# are generated from Morel's own data set, so the two cannot disagree.

import shutil
import time

from pyspark.sql import SparkSession

spark = SparkSession.builder.appName("Morel Spark Connect server").getOrCreate()


def sql(text):
    return spark.sql(text)


def table(name, create, content, nullable=""):
    """Creates a table, fills it, and says which columns hold nulls.

    "create" is the body of the CREATE: the column list, without the
    parentheses. "content" is the body of the INSERT: the rows, each a
    parenthesized tuple, separated by commas; a table with no rows has "".
    Any previous table of the same name is dropped first, so that the
    script can be run again.

    "nullable" is the columns that hold nulls, separated by commas, and
    is written to the table's "morel.nullable" property. A NOT NULL in
    the CREATE would be no use: Spark discards it where the table is
    made, does not enforce it, and reports every column of a stored
    table as nullable whatever the schema said. The property is a place
    that survives, and that every client of the table can read. An
    empty string says what it means -- this table holds no nulls -- and
    is set rather than left off, so that a reader can tell a table that
    has been characterized from one that has not.
    """
    sql("DROP TABLE IF EXISTS " + name)
    sql("CREATE TABLE %s (%s) USING parquet" % (name, create))
    if content:
        sql("INSERT INTO %s VALUES %s" % (name, content))
    sql(
        "ALTER TABLE %s SET TBLPROPERTIES ('morel.nullable' = '%s')"
        % (name, nullable))


# The catalog is in memory, but the warehouse is on disk and survives a
# container restart; a managed table cannot be created over its old
# files, so clear them.
warehouse = spark.conf.get("spark.sql.warehouse.dir")
if warehouse.startswith("file:"):
    shutil.rmtree(warehouse[len("file:"):], ignore_errors=True)

# The scott database holds the same tables and rows as Morel's built-in
# "scott" data set, so that "spark.catalog.scott.emps" on a real
# connection and on the offline connection (backed by the data set)
# give the same values. Saying that twice is how it stopped being true
# three times, so the rest of this section is generated from the data
# set itself, by ScottInit; LintTest.testGeneratedSections fails if
# this file and the data set disagree. Edit the data set, not this.
#
# A null costs something. Spark's catalog keeps no NOT NULL for a table
# stored as files -- it accepts the declaration and reports the column
# nullable anyway -- so a catalog column maps to the plain type unless
# something says otherwise, and a null in one raises when it is read
# What says otherwise is each
# table's "morel.nullable" property, written above: the commission and
# the manager are options, and the rest are not, so a whole employee
# row reads.
sql("CREATE DATABASE IF NOT EXISTS scott")

# (start: scott)
table(
    "scott.bonuses",
    """
    comm DECIMAL(7, 2), ename STRING, job STRING, sal DECIMAL(7, 2)""",
    "")
table(
    "scott.depts",
    """
    deptno INT, dname STRING, loc STRING""",
    """
    (10, 'ACCOUNTING', 'NEW YORK'), (20, 'RESEARCH', 'DALLAS'),
    (30, 'SALES', 'CHICAGO'), (40, 'OPERATIONS', 'BOSTON')""")
table(
    "scott.emps",
    """
    comm DECIMAL(7, 2), deptno INT, empno INT, ename STRING,
    hiredate STRING, job STRING, mgr INT, sal DECIMAL(7, 2)""",
    """
    (NULL, 20, 7369, 'SMITH', '1980-12-17', 'CLERK', 7902, 800),
    (300, 30, 7499, 'ALLEN', '1981-02-20', 'SALESMAN', 7698, 1600),
    (500, 30, 7521, 'WARD', '1981-02-22', 'SALESMAN', 7698, 1250),
    (NULL, 20, 7566, 'JONES', '1981-02-04', 'MANAGER', 7839, 2975),
    (1400, 30, 7654, 'MARTIN', '1981-09-28', 'SALESMAN', 7698, 1250),
    (NULL, 30, 7698, 'BLAKE', '1981-01-05', 'MANAGER', 7839, 2850),
    (NULL, 10, 7782, 'CLARK', '1981-06-09', 'MANAGER', 7839, 2450),
    (NULL, 20, 7788, 'SCOTT', '1987-04-19', 'ANALYST', 7566, 3000),
    (NULL, 10, 7839, 'KING', '1981-11-17', 'PRESIDENT', NULL, 5000),
    (0, 30, 7844, 'TURNER', '1981-09-08', 'SALESMAN', 7698, 1500),
    (NULL, 20, 7876, 'ADAMS', '1987-05-23', 'CLERK', 7788, 1100),
    (NULL, 30, 7900, 'JAMES', '1981-12-03', 'CLERK', 7698, 950),
    (NULL, 20, 7902, 'FORD', '1981-12-03', 'ANALYST', 7566, 3000),
    (NULL, 10, 7934, 'MILLER', '1982-01-23', 'CLERK', 7782, 1300)""",
    "comm,mgr")
table(
    "scott.salgrades",
    """
    grade INT, hisal DECIMAL(7, 2), losal DECIMAL(7, 2)""",
    """
    (1, 1200, 700), (2, 1400, 1201), (3, 2000, 1401), (4, 3000, 2001),
    (5, 9999, 3001)""")
# (end: scott)

# A table that says it holds no nulls and holds one: its "morel.nullable"
# property is empty, so both its columns keep their plain types, and
# reading the null raises NULL_VALUE naming the column. That is what a
# table whose characterization is wrong -- or missing, which reads the
# same way -- looks like. The scott tables cannot show it, because each
# of them declares what it holds, and the zoo cannot, because it has a
# map column that has no Morel type at all.
table(
    "nulls",
    """
    id INT, n INT""",
    """
    (1, 1), (2, NULL)""")

# The zoo is hand-written: it has one column per Spark type, and no
# counterpart in Morel's data set to generate it from. Row 1 holds
# typical values, row 2 edge values (extremes, NaN, empty, non-ASCII),
# and row 3 nulls in every column but the first.
table(
    "zoo",
    """
    id INT, b BOOLEAN, i8 TINYINT, i16 SMALLINT, i32 INT, i64 BIGINT,
    f32 FLOAT, f64 DOUBLE, dec DECIMAL(10, 2), s STRING, bin BINARY,
    d DATE, ts TIMESTAMP, ts_ntz TIMESTAMP_NTZ, arr ARRAY<INT>,
    st STRUCT<a: INT, b: STRING>, m MAP<STRING, INT>""",
    """
    (1, true, 1Y, 2S, 3, 4L, 1.5F, 2.5D, 12.34, 'hello', X'0102',
     DATE '2024-01-15', TIMESTAMP '2024-01-15 10:30:00',
     TIMESTAMP_NTZ '2024-01-15 10:30:00',
     array(1, 2, 3), named_struct('a', 1, 'b', 'x'), map('k', 1)),
    (2, false, -128Y, 32767S, 2147483647, -9223372036854775807L - 1L,
     float('nan'), double('-inf'), 99999999.99, '', X'',
     DATE '1970-01-01', TIMESTAMP '1969-12-31 23:59:59',
     TIMESTAMP_NTZ '9999-12-31 23:59:59',
     CAST(array() AS ARRAY<INT>),
     named_struct('a', CAST(NULL AS INT), 'b', 'ü'),
     CAST(map() AS MAP<STRING, INT>)),
    (3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
     NULL, NULL, NULL, NULL, NULL, NULL)""",
    "b,i8,i16,i32,i64,f32,f64,dec,s,bin,d,ts,ts_ntz,arr,st,m")

print("Morel init tables created: nulls, scott.bonuses, scott.depts,"
      " scott.emps, scott.salgrades, zoo", flush=True)

while True:
    time.sleep(3600)

# End init.py
