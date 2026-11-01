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

# Spark structure

[Up to index](index.md)

[//]: # (start:lib/spark)


## Synopsis

<pre>
type <a id='connection' href="#connection-impl">connection</a>
type ('a, 'b) <a id='plan' href="#plan-impl">plan</a>

exception <a id='Spark' href="#Spark-impl">Spark</a> of {errorClass:string, message:string}

val <a id='connect' href="#connect-impl">connect</a> : string -> {catalog:{}, connection:connection}
val <a id='connectDefault' href="#connectDefault-impl">connectDefault</a> : unit -> {catalog:{}, connection:connection}
val <a id='catalog' href="#catalog-impl">catalog</a> : connection -> {}
val <a id='close' href="#close-impl">close</a> : connection -> unit
val <a id='prepare' href="#prepare-impl">prepare</a> : connection * ('a -> 'b) -> ('a, 'b) plan
val <a id='toString' href="#toString-impl">toString</a> : ('a, 'b) plan -> string
val <a id='execute' href="#execute-impl">execute</a> : ('a, 'b) plan * 'a -> 'b
val <a id='remote' href="#remote-impl">remote</a> : connection * ('a -> 'b) -> 'a -> 'b
val <a id='using' href="#using-impl">using</a> : (connection * 'a -> 'b) -> 'a -> 'b
</pre>

<a id="connection-impl"></a>
<h3><code><strong>type</strong> connection</code></h3>

is a connection to a Spark Connect server, or the offline
connection whose catalog is the session's foreign data sets.

<a id="plan-impl"></a>
<h3><code><strong>type</strong> ('a, 'b) plan</code></h3>

is a query translated for a connection: `prepare` makes one, `toString`
prints it, and `execute` runs it. The parameters are phantom: they
record what the plan takes and what it computes, and a plan holds
neither. A plan of a query that takes no argument has `'a = unit`.

<a id="Spark-impl"></a>
<h3><code><strong>exception</strong> Spark</code></h3>

is raised by an operation that Spark, or the Spark adapter, rejects.
`errorClass` is Spark's error condition, such as `DIVIDE_BY_ZERO`
or `TABLE_OR_VIEW_NOT_FOUND`, or one of the adapter's own:
`CONNECTION` (the server could not be reached), `CLOSED` (the
connection was closed), `NULL_VALUE` (a null in a column whose Morel
type has no room for it), `UNSUPPORTED_TYPE`, or `MOCK` (the offline
connection cannot execute).

<a id="connect-impl"></a>
<h3><code>connect</code></h3>

`connect uri` opens a connection to the server at `uri`, such as
`"sc://localhost:15002"`, or the offline connection if `uri` is
`"mock:"`. Returns a record whose `connection` field is the
connection and whose `catalog` field is the root of its catalog: a
progressively typed record whose fields are databases, whose fields
in turn are tables, each a bag of records. So `spark.catalog.scott.emps`
reads a table.

After the host, a URI may carry parameters, introduced by `/;` and
separated by `;`. Morel reads one of them, `nullable`, a
comma-separated list of `database.table.column` naming the columns
that may be null:

```
sc://localhost:15002/;nullable=scott.emps.comm,scott.emps.mgr
```

A declared column has type `t option`, and a null in it arrives as
`NONE`; an undeclared one has the plain type `t`, and a null in it
raises `Spark` with error class `NULL_VALUE`. A declaration of `*`
covers every column of every table, for a connection that would
rather unwrap a column than have one raise.

A table can say it instead, and better, in its own `morel.nullable`
property -- a comma-separated list of its column names, which a
connection reads when it asks for the table's type:

```
ALTER TABLE scott.emps SET TBLPROPERTIES ('morel.nullable' = 'comm,mgr')
```

The property is the whole answer for its table: a column it names may
be null, one it does not name may not, and an empty property says the
table holds no nulls. Where a URI also speaks, the URI wins.

Either is needed because Spark keeps nothing itself: it reports every
column of a stored table as nullable whatever the DDL said, discards a
`NOT NULL` where the table is made, and has no constraint for it.

The other parameter Morel reads is `user_id`, which names the user
the server sees; it ignores any other, and other clients ignore
`nullable` in turn.

<a id="connectDefault-impl"></a>
<h3><code>connectDefault</code></h3>

`connectDefault ()` opens a connection to the server named by the `SPARK_REMOTE`
environment variable, as `connect` does.

<a id="catalog-impl"></a>
<h3><code>catalog</code></h3>

`catalog c` (or `c.catalog ()`) returns the root of the catalog of connection `c`, as the `catalog`
field of `connect` returns it. Bind it to a name with `val` to browse
it, since a name's type grows as fields are discovered.

<a id="close-impl"></a>
<h3><code>close</code></h3>

`close c` (or `c.close ()`) closes connection `c`. Any later use of it raises `Spark`.

<a id="prepare-impl"></a>
<h3><code>prepare</code></h3>

`prepare (c, f)` (or `c.prepare f`) translates `f` into a plan that computes the same function on
connection `c`.

It reads the parse tree of `f` rather than evaluating it, so `f` must be
written out at the call: a function reached through a name is a closure
by then, and its tree is gone. Everything the body reads other than its
parameter is fixed when the plan is prepared; the argument is the only
thing that varies from one execution to the next.

What the query reads crosses when the plan is made: a table of `c`'s
catalog becomes a scan of that table, and every other collection -- a
list of the session's own, a data set, a value a query computed --
becomes rows the plan carries. What a collection is decides which, not
what it is named: a data set named `scott` is not the server's `scott`,
so read the server's table through `c.catalog`.

<a id="toString-impl"></a>
<h3><code>toString</code></h3>

`toString p` (or `p.toString ()`) returns plan `p` as text, in Spark's protobuf text format, except that a
local relation shows the number of rows it carries rather than the bytes
that carry them.

<a id="execute-impl"></a>
<h3><code>execute</code></h3>

`execute (p, a)` (or `p.execute a`) runs plan `p` on the connection it was prepared for, with argument `a`,
and returns what it computes: the rows of a query, or the one value of a
query that computes one. A plan of a function that takes no argument is
run with `()`.

<a id="remote-impl"></a>
<h3><code>remote</code></h3>

`remote (c, f)` (or `c.remote f`) returns `f` prepared on connection `c` and executed there: a function of
the same type as `f` that computes what `f` computes, with the work done
by Spark. It is `prepare` and `execute` in one step, and reads the parse
tree of `f` as `prepare` does.

<a id="using-impl"></a>
<h3><code>using</code></h3>

`using f` returns a function that opens the default connection, applies `f` to
the connection and its argument, and closes the connection afterwards,
whether or not `f` raised. So `using (fn (c, x) => x + 1)` is a
function that, applied to 4, returns 5.

[//]: # (end:lib/spark)
