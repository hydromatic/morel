<!--
{% comment %}
Licensed to Julian Hyde under one or more contributor license
agreements.  See the NOTICE file distributed with this work
for additional information regarding copyright ownership.
Julian Hyde licenses this file to you under the Apache
License, Version 2.0 (the "License"); you may not use this
file except in compliance with the License.  You may obtain a
copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied.  See the License for the specific
language governing permissions and limitations under the
License.
{% endcomment %}
-->
# Morel on Spark

Morel can run a query on an [Apache Spark](https://spark.apache.org)
cluster. You write the same `from` expression you would write over a list,
Morel translates it to a Spark plan, Spark runs it, and the rows come back
as Morel values.

**You need JDK 17 or later.** The adapter is built only there -- that is
where the `spark` Maven profile activates, and where the protobuf and
[Apache Arrow](https://arrow.apache.org) libraries it needs are
supported. On an earlier JDK, Morel builds and runs
without the adapter, and `Spark.connect` raises `Spark` with error class
`CONNECTION`, saying so. You also need a Spark Connect server, which the
next section starts.

## Why

* **One language for a laptop and a cluster.** A query over a list, over a
  JDBC database, and over a Spark table is the same query. You develop
  against three rows and run against three billion.
* **It is typed before it runs.** `spark.catalog.scott.emps` has a record
  type, so a misspelled column or a comparison between a string and an int
  is a compile error, not a stage failure twenty minutes in.
* **The catalog is a value.** Databases and tables are fields of a
  progressively typed record, so you explore with the language you query
  with -- `spark.catalog;`, then `spark.catalog.scott;` -- rather than with
  a separate dialect of `SHOW`.
* **No silent half-execution.** A plan is all Spark or it is an error: a
  query Spark cannot do is refused where the plan is made, naming the
  construct, rather than quietly running part of it in one place and part
  in another.
* **Your data can meet the cluster's.** A list the session built crosses as
  rows and joins a server table.
* **You can read the plan without running it**, and against an offline
  connection with no cluster at all.

## A first session

Start a server. This one is a Docker container holding the `scott` tables,
which is what Morel's own tests use; any Spark Connect server will do.

```bash
$ src/test/resources/spark/start-spark.sh
sc://localhost:15002
```

Then start Morel with `--spark`, which connects, binds the server as
`spark`, and sends each query there:

```bash
$ ./morel --spark
```

```sml
(*) What Spark has
spark.catalog.scott;
> val it =
>   {bonuses=<relation>,depts=<relation>,emps=<relation>,salgrades=<relation>}
>   : {bonuses:{...} bag, depts:{...} bag, emps:{...} bag, salgrades:{...} bag,
>       ...}

(*) A query, run on the cluster
from e in spark.catalog.scott.emps
  group e.deptno compute {c = count over ()}
  order deptno;
> val it = [{c=3,deptno=10},{c=5,deptno=20},{c=6,deptno=30}]
>   : {c:int, deptno:int} list

(*) A list of your own, joined to a table of theirs
val rates = [{job = "CLERK", bonus = 100}, {job = "MANAGER", bonus = 500}];
from e in spark.catalog.scott.emps
  join r in rates on e.job = r.job
  yield {e.ename, r.bonus};
> val it =
>   [{bonus=500,ename="BLAKE"},{bonus=500,ename="CLARK"},
>    {bonus=500,ename="JONES"},{bonus=100,ename="ADAMS"},
>    {bonus=100,ename="JAMES"},{bonus=100,ename="SMITH"},
>    {bonus=100,ename="MILLER"}] : {bonus:int, ename:string} bag
```

The `rates` rows are sent with the plan; the employees never leave the
cluster.

## Types

A query's type comes from Spark's schema, mapped column by column:

| Spark | Morel | Notes |
| --- | --- | --- |
| `boolean` | `bool` | |
| `tinyint`, `smallint`, `int` | `int` | widened on read |
| `bigint` | `int` | raises `Overflow` if the value does not fit |
| `float`, `double` | `real` | Morel's `real` is 32-bit, so a `double` is narrowed |
| `decimal(p, s)` | `decimal` | rounded half-even to Morel's 34 digits |
| `string`, `char(n)`, `varchar(n)` | `string` | a `char(n)` keeps its trailing spaces |
| `binary` | `word list` | one `word` per byte |
| `date` | `Date.date` | midnight, no zone |
| `timestamp` | `Time.time` | an instant, microsecond precision |
| `timestamp_ntz` | `Date.date` | wall-clock time, no zone |
| `array<t>` | `t list` | |
| `struct<f: t, ...>` | `{f: t, ...}` | fields matched by name; Morel sorts the labels |
| a table | `{...} bag` | a bag, because rows are unordered |
| `map<k, v>` | *rejected* | |
| `interval ...`, `void` | *rejected* | |

A rejected column makes its table unreadable, and says why -- which is
what the container's `zoo` table, one column per Spark type, is for:

```sml
spark.catalog.default.zoo;
> uncaught exception Spark [Spark: UNSUPPORTED_TYPE: Spark type map<string,integer> has no Morel equivalent]
```

### Nulls, and why a column is an option

A column that may be null maps to `t option` -- `int option`, `decimal
option` -- and its nulls arrive as `NONE`. A column that may not maps to
plain `t`. So the question is which columns may be null, and Spark cannot
answer it.

Watch what happens to a `NOT NULL`. The test container is created by
`src/test/resources/spark/init.py`, which for each table runs a `CREATE
TABLE ... USING parquet`, an `INSERT`, and nothing else exotic:

```sql
CREATE TABLE scott.emps (comm DECIMAL(7, 2), deptno INT, empno INT, ...)
  USING parquet
```

Suppose it said `empno INT NOT NULL`. Spark accepts the statement, and
then:

```sql
SHOW CREATE TABLE scott.emps
-- CREATE TABLE spark_catalog.scott.emps (empno INT, ...) USING parquet
INSERT INTO scott.emps VALUES (NULL, ...)   -- succeeds
```

The constraint is gone -- not hidden, gone: the DDL no longer mentions it
and nothing enforces it. Spark's own documentation says why: "When reading
Parquet files, all columns are automatically converted to be nullable for
compatibility reasons." Asking for the schema confirms it; every column of
every stored table comes back nullable, including a primary key and
including the three columns of `scott.depts`, which hold no nulls at all:

```
comm      decimal(7,2)   nullable=true
deptno    integer        nullable=true
empno     integer        nullable=true
...
```

A flag that is true for every column says nothing, so Morel does not
believe it at the top level of a stored table. (One level down it is a
different flag: the nullability of a field inside a `struct`, or of an
array's elements, is recorded rather than inferred, and Morel does believe
that.) Nor is the constraint kept anywhere else: `ALTER COLUMN ... SET NOT
NULL` is refused for a table stored as files, and Spark's constraints
(CHECK, UNIQUE, PRIMARY KEY, FOREIGN KEY) do not include nullability,
because nullability belongs to a column's type. Delta Lake and Iceberg
record it -- at the price of changing file format.

**So the table is given a property.** `init.py` ends each table with:

```sql
ALTER TABLE scott.emps SET TBLPROPERTIES ('morel.nullable' = 'comm,mgr')
```

Morel reads `morel.nullable` when it asks for a table's type, and that is
where `scott.emps` gets its shape:

```sml
spark.catalog.scott.emps;
> val it : {comm:decimal option, deptno:int, empno:int, ename:string,
>           hiredate:string, job:string, mgr:int option, sal:decimal} bag
```

Two columns are options because the property names them; the rest are
plain because it does not. A property of `*` makes every column of that
table an option, which is what to say about a table whose nulls nobody has
mapped. The property is the whole answer for its table,
so an empty property says "this table holds no nulls", which is a useful
thing to be able to say and different from saying nothing. For the
container's `scott` tables the property is generated from Morel's own
`scott` data set, so the fixture and the types cannot drift apart.

A column that nothing declares keeps its plain type, and a null in it
raises where it is read:

```sml
spark.catalog.default.nulls;
> uncaught exception Spark [Spark: NULL_VALUE: Null in column n of row 1, whose type int has no room for it]
```

That error names the column, and the fix is the property. A connection can
also be told directly, which is the next section.

### Morel to Spark

The reverse mapping is needed when Morel sends data -- a list of its own,
a literal in a plan -- and is the inverse of the table above on its image:
`int` to `int`, `real` to `float`, `string`, `bool`, `Time.time` to
`timestamp`, `Date.date` to `timestamp_ntz`, `word list` to `binary`, `t
list` to `array<t>`, a record to a struct, `t option` to a nullable
column. A `char` is a one-character `string`, `unit` is a struct with no
fields, and a tuple is a struct whose fields are `1`, `2`, ... A datatype
other than `option` crosses as a tagged struct, with one nullable field
per constructor that takes an argument. Function types are rejected.

`decimal` is the one type the table above does not decide, because Morel's
decimal carries no precision or scale and every Spark decimal column has
both. A column of them is `decimal(38, 18)` -- the width Spark itself
gives a column it infers from a `BigDecimal` -- and each value is restated
at that scale. A value that does not fit is refused rather than rounded.

A row must be a record, because Spark has no column for a bare value:
`from i in [1, 2]` cannot be sent, and nothing has a column for `unit`.

### Errors

Every failure raises `Spark {errorClass, message}`. The error class is
Spark's own, taken from the error it returned, or one of the adapter's:
`CONNECTION` (the server could not be reached, or the adapter is not
built), `CLOSED`, `NULL_VALUE`, `UNSUPPORTED_TYPE`, `UNSUPPORTED` (the
translator refused), `MOCK` (the offline connection cannot execute). After
any error the connection is still usable.

Where Spark's error is one Morel has a name for, Morel's exception is
raised instead, so that a query fails the same way wherever it ran:
`DIVIDE_BY_ZERO` becomes `Div`, and `ARITHMETIC_OVERFLOW` and
`CAST_OVERFLOW` become `Overflow`.

```sml
(spark.connection.prepare (fn () =>
  from e in spark.catalog.scott.emps where e.deptno div 0 = 1 yield e.ename
)).execute ();
> uncaught exception Div [divide by zero]
```

## Configuration

### Command line and environment

| What | Means |
| --- | --- |
| `--spark` | Connect to `$SPARK_REMOTE`, or `sc://localhost:15002`; bind it as `spark`; push queries down |
| `--spark=URI` | The same, with that URI, whatever `$SPARK_REMOTE` says |
| `--no-spark` | What omitting `--spark` does: no connection, no push-down |
| `$SPARK_REMOTE` | The default server, which `Spark.connectDefault` also reads |

`--spark` starts no server and stops none. Start one yourself --
`src/test/resources/spark/start-spark.sh` runs a container and prints its
URI; `--stop` removes it -- or point `--spark` at a cluster you have.

### Session properties

| Property | Type | Default | Means |
| --- | --- | --- | --- |
| `sparkPushDown` | bool | false | Translate each query for Spark and run it there. `--spark` sets it |
| `sparkFallback` | bool | true | A query Spark cannot do is evaluated here, with a warning, rather than failing |

```sml
Sys.set ("sparkPushDown", false);   (*) back to local evaluation
Sys.set ("sparkFallback", false);   (*) a refusal is an error, not a warning
```

`sparkFallback` governs refusals only -- a construct or function the
translator cannot express. A query that reached the cluster and failed
there is raised, never recomputed locally: its answer is not ours to
invent.

### Connection URI parameters

A URI may carry parameters after `/;`, separated by `;`:

```
sc://localhost:15002/;user_id=fred;nullable=*
```

| Parameter | Means |
| --- | --- |
| `user_id` | The user the server sees |
| `nullable` | Columns that may be null, as `database.table.column`; `*` means every column of every table |

A [table property](#nulls-and-why-a-column-is-an-option) is the normal way
to say which columns may be null, and is better where you can set one: it
travels with the table, and the next person does not have to know.
`nullable` is for the two things a property cannot do.

* **`nullable=*`** is a whole-connection choice -- unwrap every column
  rather than have one raise -- which no single table can make. It is what
  you want against a warehouse nobody has characterized:

  ```bash
  ./morel --spark='sc://localhost:15002/;nullable=*'
  ```

  ```sml
  spark.catalog.scott.depts;
  > val it : {deptno:int option, dname:string option, loc:string option} bag
  ```

* **a column list** is for a table you cannot `ALTER`, because it is
  someone else's, or whose property you have reason to disbelieve. It
  overrides the property for the tables it names.

A property can say `*` for its own table, so that is not a reason to reach
for the URI; what the URI does that a property cannot is cover tables you
have not seen, or cannot change.

It is part of the URI, rather than a session property like
`sparkPushDown`, because a connection caches the type of each table as it
discovers it. Nullability decided after a table has been read could not
change the type that was already given out, so it is decided when the
connection is opened, and a connection that needs a different answer is a
different connection.

### Building and testing

The adapter is compiled by the `spark` Maven profile, which activates on
JDK 17 and later, so a build on JDK 8 or 11 leaves it out -- and a test
that touches it must say `assumeTrue(SparkBackend.isAvailable(), ...)`, or
it will fail there. `./mvnw verify -P '!spark'` reproduces such a build on
any JDK.

Tests that need a server are skipped unless you ask for them:

```bash
export SPARK_REMOTE=$(src/test/resources/spark/start-spark.sh)
./mvnw test -Dmorel.spark=true
```

## The `Spark` structure

`connect`, `connectDefault`, `catalog`, `close`, `prepare`, `toString`,
`execute`, `remote`, `using`, the type `('a, 'b) plan`, and the exception
`Spark {errorClass, message}`. See the
[library reference](lib/spark.md) for each.

```sml
(*) A plan, made but not run
val p = spark.connection.prepare (fn () =>
  from d in spark.catalog.scott.depts where d.deptno = 10 yield d.dname);
p.toString ();     (*) the plan, as Spark's own text format
p.execute ();      (*) run it
```

`prepare` reads the parse tree of the function, so the function has to be
written at the call; a plan with parameters is translated afresh at each
execution, with the argument in place.

## Architecture

Morel talks to Spark over **Spark Connect**, the gRPC protocol that Spark
4 exposes. A client builds a `Plan` -- a tree of protobuf `Relation`
messages -- and the server analyzes or executes it; results come back as
Arrow record batches. Morel speaks the protocol directly, from protobuf
definitions vendored into the repository, so a Morel session needs no
Spark jars and no JVM on the cluster's version.

Six pieces do the work:

| Piece | Job |
| --- | --- |
| `SparkTranslator` | Morel's core query to a protobuf relation tree |
| `SparkTypes`, `SparkSchema` | Spark's types to Morel's, and back |
| `ArrowEncoder`, `ArrowDecoder` | Morel values to Arrow batches, and back |
| `SparkCatalog` | Databases and tables as a progressively typed record |
| `PreparedPlan` | The value of `('a, 'b) plan`: a plan and its connection |
| `SparkConnection` | The channel, and requests on it |

What a query reads crosses in one of two ways, and **what the collection
is decides which, not what it is called**:

* a table of the connection's catalog becomes a scan -- `read {
  named_table { "scott.emps" } }`;
* every other collection -- a list, a data set, a value a query computed --
  becomes `local_relation`, carrying its rows.

So a data set that happens to be named `scott` is not the server's
`scott`: to read the server's table, reach it through the catalog.

## Reading a plan

`Sys.plan ()` prints the plan of the statement just run. Morel's own plan
is what you see, with a node naming any engine that did part of the work:
`calcite(...)` where Apache Calcite did, and `spark(...)` where Spark did.
A plan is a multi-line string, which prints as a raw literal, `{|...|}`;
`Sys.set ("rawStrings", false)` gives the escaped single-line form instead.

With `Sys.set ("hybrid", true)`, a query over a JDBC data set goes to
Calcite:

```sml
- from d in scott.depts where d.deptno = 20 yield d.dname;
val it = ["RESEARCH"] : string bag
- Sys.plan ();
val it = {|globalMarshal(globals [scott], body calcite(plan LogicalProject(dname=[$1])
  LogicalFilter(condition=[=($0, 20)])
    LogicalProject(deptno=[$0], dname=[$1], loc=[$2])
      JdbcTableScan(table=[[scott, DEPT]])
))|} : string
```

With `sparkPushDown`, a query over a catalog table goes to Spark, and the
plan node holds the plan that was sent -- Spark's own text format, which is
what you would see in `spark.sql(...).explain()` on the other side:

```sml
- from d in spark.catalog.scott.depts take 1;
val it = [{deptno=10,dname="ACCOUNTING",loc="NEW YORK"}]
  : {deptno:int, dname:string, loc:string} bag
- Sys.plan ();
val it = {|apply(fnValue spark(plan root {
  common {
    plan_id: 0
  }
  limit {
    input {
      common {
        plan_id: 1
      }
      read {
        named_table {
          unparsed_identifier: "scott.depts"
        }
      }
    }
    limit: 1
  }
}), argCode constant([]))|} : string
```

A real query's plan is longer, for the same reasons a SQL plan is: one
protobuf field to a line, and a `subquery_alias` around each scan to carry
the range variable's name.

Three things to look for:

* **`spark(plan root {...})`** -- Spark ran this, and that is what it was
  asked. `read { named_table }` means it read its own table;
  `local_relation { data: [N rows] }` means Morel sent the rows.
* **`spark(parameters [d])`** -- a prepared plan with a parameter, which has
  no text until it is executed, because each execution translates it afresh.
* **no `spark(` at all** -- it ran here. If you expected otherwise, look
  for the warning: with `sparkFallback` true, a query Spark could not do
  says so and is evaluated locally.

## Limits

* A plan is all Spark or an error; there is no partial push-down yet.
* A row must be a record: Spark has no column for a bare `int`, so
  `from i in [1, 2]` cannot be sent, and nothing has a column for `unit`.
* A few operators answer differently on Spark -- an overflow raises where
  Morel wraps, because Spark runs in ANSI mode; `Int.toString` writes
  `-7782` where Morel writes `~7782`; a substring that runs off the end
  clamps where Morel raises. Each is recorded, with both answers, in
  `src/test/resources/script/spark-divergence.smli`.
* `real` is a 32-bit float in Morel and a column crosses as `double`, so a
  computation done on the cluster is done in double precision.

## See also

* [`Spark` structure reference](lib/spark.md)
* [spec.md](https://github.com/julianhyde/morel/blob/467-spark/spec.md) --
  the design: the type mapping, the `Spark` structure, the function table
* [plan.md](https://github.com/julianhyde/morel/blob/467-spark/plan.md) --
  the decisions, what each measurement showed, and what is not done

Those two live on the `467-spark` branch and are not merged: they are the
working design and diary, not reference material.
