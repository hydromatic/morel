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

TODO:
* test that query with negative skip gives error (consistent with sql)
* test skip 0 is a no-op
* test skip >N yields nothing
* test that query with negative take gives error
* val dice = [1, 2, 3, 4, 5, 6]
* from i in dice, j in dice yield i + j distinct -- should  valid but has bug
* from i in dice, j in dice yield i + j; -- works, int list
* from i in dice, j in dice yield {x=i + j}; -- works, rec list
* from i in dice, j in dice yield {x=i + j} order x; -- works, int list
* from i in dice, j in dice yield {x=i + j} order x take 3;
* from i in dice, j in dice yield {x=i + j} order x skip 3;
* from i in dice, j in dice yield {x=i + j} order x skip 3 yield x;
* from i in dice, j in dice yield {x=i + j} order x yield x;
* from i in dice, j in dice yield {x=i + j} yield x; -- has bug
* from i in dice, j in dice yield {x=i + j} skip 3 yield x; -- works
* from i in dice, j in dice yield {x=i + j} distinct; -- works
* test through with partially eval function, 'from i in dice through j in multiplesOf 3'
* Char.toUpper and toLower, and test 'String.map Char.toUpper' etc.

{% endcomment %}
-->

# Query

Queries are a class of Morel expressions that operate on
collections. A typical query takes one or more collections as input
and returns a collection, but there are also variants that return a
scalar value such as a `bool` or a single record.

For example, the following query returns the name and job title of all
employees in department 10:

<pre>
<b>from</b> e <b>in</b> scott.emps
  <b>where</b> e.deptno = 10
  <b>yield</b> {e.ename, e.job};
<i>
ename  job
------ ---------
CLARK  MANAGER
KING   PRESIDENT
MILLER CLERK

val it : {ename:string, job:string} bag</i>
</pre>

(Notice how this result is printed as a table. Morel automatically
uses tabular format if the value is a list of records or atomic
values, provided that you have `set("output", "tabular");` in the
current session; see [properties](reference.md#properties).)

If you know SQL, you might have noticed that this looks similar to a
SQL query:

<pre>
<b>SELECT</b> e.ename, e.job
<b>FROM</b> scott.emps <b>AS</b> e
<b>WHERE</b> e.deptno = 10;
</pre>

There are deep similarities between Morel query expressions and SQL,
which is expected, because both are based on relational algebra. Any
SQL query has an equivalent in Morel, often with
[similar syntax](#correspondence-between-sql-and-morel-query).

## Syntax

The formal syntax of queries is as follows.

<pre>
<i>exp</i> &rarr; (other expressions)
    | <b>from</b> [ <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i> ] <i>step<sub>1</sub></i> ... <i>step<sub>t</sub></i> [ <i>terminalStep</i> ]
                                relational expression (<i>s</i> &ge; 0, <i>t</i> &ge; 0)
    | <b>exists</b> [ <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i> ] <i>step<sub>1</sub></i> ... <i>step<sub>t</sub></i>
                                existential quantification (<i>s</i> &ge; 0, <i>t</i> &ge; 0)
    | <b>forall</b> [ <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i> ] <i>step<sub>1</sub></i> ... <i>step<sub>t</sub></i> <b>require</b> <i>exp</i>
                                universal quantification (<i>s</i> &ge; 0, <i>t</i> &ge; 0)

<i>scan</i> &rarr; <i>pat</i> <b>in</b> <i>exp</i> [ <b>on</b> <i>exp</i> ]    iteration
    | <i>pat</i> <b>=</b> <i>exp</i> [ <b>on</b> <i>exp</i> ]      single iteration
    | <i>val</i>                       unbounded variable

<i>step</i> &rarr; <b>distinct</b>                 distinct step
    | <b>except</b> [ <b>distinct</b> ] <i>exp<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>exp<sub>e</sub></i>
                                except step (<i>e</i> &ge; 1)
    | <b>group</b> <i>exp<sub>1</sub></i> [ <b>compute</b> <i>exp<sub>2</sub></i> ]
                                group step
    | <b>intersect</b> [ <b>distinct</b> ] <i>exp<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>exp<sub>i</sub></i>
                                intersect step (<i>i</i> &ge; 1)
    | <b>join</b> <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i>  join step (<i>s</i> &ge; 1)
    | <b>order</b> <i>exp</i>                 order step
    | <b>skip</b> <i>exp</i>                  skip step
    | <b>take</b> <i>exp</i>                  take step
    | <b>through</b> <i>pat</i> <b>in</b> <i>exp</i>        through step
    | <b>union</b> [ <b>distinct</b> ] <i>exp<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>exp<sub>u</sub></i>
                                union step (<i>u</i> &ge; 1)
    | <b>where</b> <i>exp</i>                 filter step
    | <b>yield</b> <i>exp</i>                 yield step

<i>terminalStep</i> &rarr; <b>into</b> <i>exp</i>         into step
    | <b>compute</b> <i>exp</i>               compute step

<i>groupKey</i> &rarr; [ <i>id</i> <b>=</b> ] <i>exp</i>

<i>agg</i> &rarr; [ <i>id</i> <b>=</b> ] <i>exp</i> [ <b>of</b> <i>exp</i> ]
</pre>

A query is a `from`, `exists` or `forall` keyword followed by one or
more *scans*, then followed by zero or more *steps*. (A `forall` query
must end with a `require` step, and a `from` query may end with an
`into` or `compute` terminal step.)

For example, the query

<pre>
<b>from</b> e <b>in</b> scott.emps,
    d <b>in</b> scott.depts <b>on</b> e.deptno = d.deptno
  <b>where</b> e.deptno = 10
  <b>yield</b> {d.dname, e.ename, e.job};
</pre>

has two scans (<code>e <b>in</b> scott.emps</code> and
<code>d <b>in</b> scott.depts <b>on</b> e.deptno = d.deptno</code>)
and two steps (<code><b>where</b> e.deptno = 10</code> and
<code><b>yield</b> {d.dname, e.ename, e.job}</code>).

In the following sections we will look at [scans](#scan) and
[steps](#step) in more detail. We will focus on `from` for now, and
will cover `exists` and `forall` in
[quantified queries](#quantified-queries).

Finally, remember that a query is an expression.  You can evaluate a
query by typing it into the shell, just like any other expression.
Also, you can use a query anywhere in a Morel program that an
expression is valid, such as in a `case` expression, the body of a
`fn` lambda, or the argument to a function call. Because Morel is
strongly typed, the type of the query expression has to match where it
is being used. Most queries return a collection, but quantified
queries (`exists` and `forall`) and queries with a terminal step
(`compute` or `into`) return a scalar value, and therefore are
particularly easy to use in expressions.

## Scan

A **scan** is a source of rows. The most common form, "*id* `in`
*collection*", assigns each element of *collection* to *id* in turn
and then invokes the later steps in the pipeline.

A scan is like a "for" loop in a language such as Java or Python.

The collection can have elements of any type. In SQL, the elements
must be records, but in Morel they may be atomic values, lists, lists
of lists, records that contain lists of records, or anything else.

<pre>
<i>(* Query over a list of integers. *)</i>
<b>from</b> i <b>in</b> [1, 2, 3, 4, 5]
  <b>where</b> i <b>mod</b> 2 = 0;
<i>
2
4

val it : int list</i>
</pre>

If the collection has a structured type, you can use a pattern to
deconstruct it.

<pre>
<i>(* Query over a list of (string, int) pairs. *)</i>
<b>from</b> (name, age) <b>in</b> [("shaggy", 17), ("scooby", 7)]
  <b>yield</b> {s = name ^ " is " ^ Int.toString(age) ^ "."};
<i>
shaggy is 17.
scooby is 7.

val it : {s:string} list</i>
</pre>

### Multiple scans

If there are multiple scans, the query generates a cartesian product:

<pre>
<b>from</b> i <b>in</b> [2, 3],
  s <b>in</b> ["ab", "cde"];
<i>
i s
- ---
2 ab
2 cde
3 ab
3 cde

val it : {i:int, s:string} list</i>
</pre>

If you want to add a join condition, you can append an `on` clause:

<pre>
<b>from</b> e <b>in</b> scott.emps,
    d <b>in</b> scott.depts <b>on</b> e.deptno = d.deptno
  <b>where</b> e.job = "MANAGER"
  <b>yield</b> {e.ename, d.dname};
<i>
dname      ename
---------- -----
RESEARCH   JONES
SALES      BLAKE
ACCOUNTING CLARK

val it : {dname:string, ename:string} bag</i>
</pre>

(The `on` clause is not allowed on the first scan.)

If you want scans later in a query, use the `join` step.

<pre>
<b>from</b> c <b>in</b> clients
  <b>where</b> c.city = "BOSTON"
  <b>join</b> e <b>in</b> scott.emps <b>on</b> c.contact = e.empno,
      d <b>in</b> scott.depts <b>on</b> e.deptno = d.deptno
  <b>yield</b> {c.cname, e.ename, d.dname};
<i>
cname  dname ename
------ ----- ------
Apple  SALES MARTIN
Disney SALES ALLEN
Ford   SALES WARD
IBM    SALES MARTIN</i>
</pre>

### Lateral scans and nested data

Multiple scans are a convenient way of dealing with nested data.

<pre>
<i>(* Define the shipments data set; each shipment has one or
   more nested items. *)</i>
<b>val</b> shipments =
  [{id=1, shipping=10.0, items=[{product="soda", quantity=12},
                                {product="beer", quantity=3}]},
   {id=2, shipping=7.5, items=[{product="cider",quantity=4}]}];

<i>(* Flatten the data set by joining each shipment to its own
   items. *)</i>
<b>from</b> s <b>in</b> shipments,
    i <b>in</b> s.items
  <b>yield</b> {s.id, i.product, i.quantity};
<i>
id product quantity
-- ------- --------
 1 soda          12
 1 beer           3
 2 cider          4

val it : {id:int, product:string, quantity:int} list</i>
</pre>

Note that the second scan uses current row from the first scan (`s`
appears in the expression `s.items`). SQL calls this a lateral join
(because lateral means "sideways" and one scan is looking "sideways"
at the other scan). Lateral joins are only activated in SQL when you
use the keywords `LATERAL` or `UNNEST`, but Morel's scans and joins
are always lateral. As a result, queries over nested data are easy and
concise in Morel.

### Single-row scan

A scan with `=` syntax iterates over a single value. While
<code>pat = exp</code> is just syntactic sugar for
<code>pat <b>in</b> [exp]</code>, it is nevertheless a useful way to
add a column to the current row.

<pre>
<i>(* Iterate over a list of integers and compute whether
   they are odd. *)</i>
<b>from</b> i <b>in</b> [1, 2, 3, 4, 5],
    odd = (i <b>mod</b> 2 = 1);
<i>
i odd
- -----
1 true
2 false
3 true
4 false
5 true

val it : {i:int, odd:bool} list</i>

<i>(* Equivalent using "in" and a singleton list. *)</i>
<b>from</b> i <b>in</b> [1, 2, 3, 4, 5],
    odd <b>in</b> [(i <b>mod</b> 2 = 1)];
<i>
i odd
- -----
1 true
2 false
3 true
4 false
5 true

val it : {i:int, odd:bool} list</i>
</pre>

### Empty scan

In case you are wondering, yes, a query with no scans is legal. It
produces one row with zero fields.

<pre>
<b>from</b>;

<it>val it = [()] : unit list</it>
</pre>

You can even feed that one row into a pipeline.

<pre>
<b>from</b>
  <b>where</b> true
  <b>yield</b> 1 + 2;
<i>
3

val it : int list</i>
</pre>

## Step

A query is a pipeline of data flowing through relational
operators. The scans introduce rows into the pipeline, and the steps
are the relational operators that these rows flow through.

Each step has a contract with its preceding and following step: what
fields does it consume, and what fields are produced. A query begins
with a set of scans, and each scan defines a number of variables
(usually one, unless the scan has a complex pattern).

The following query defines two fields: `deptno` of type `int` and
`emp` with a record type.

<pre>
<b>from</b> deptno <b>in</b> [10, 20],
    emp <b>in</b> scott.emps <b>on</b> emp.deptno = deptno;
</pre>

(Unlike SQL, the fields of a record are not automatically unnested. If
you wish to access the `job` field of an employee record, then you
must write `emp.job`; the unqualified expression `job` is invalid.)

The `deptno` and `emp` fields can be consumed in a following `yield`
step, which produces fields `deptno`, `job`, `initial`:

<pre>
<b>from</b> deptno <b>in</b> [10, 20],
    emp <b>in</b> scott.emps <b>on</b> emp.deptno = deptno
  <b>yield</b> {deptno, emp.job, initial = String.sub(emp.ename, 1)};
<i>
deptno initial job
------ ------- ---------
10     L       MANAGER
10     I       PRESIDENT
10     I       CLERK
20     M       CLERK
20     O       MANAGER
20     C       ANALYST
20     D       CLERK
20     O       ANALYST

val it : {deptno:int, initial:char, job:string} bag</i>
</pre>

In the following sections, we define each of Morel's step
types and how they map input fields to output fields.

### Step expressions

Expressions within query steps use the same syntax as
expressions elsewhere in Morel, but they execute within an
enhanced environment that provides additional operations.

Query step expressions have access to two special operations
not available in other contexts:
 * `current` - references the current row being processed
 * `ordinal` - provides the position/index of the current row

Most query steps evaluate once per row and run within a specialized
environment. This applies to all steps except:

* `compute`
* `except`
* `intersect`
* `into`
* `skip`
* `take`
* `through`
* `union`

An expression in a per-row step has access to:
 * Field definitions from the preceding step in the query
   pipeline
 * Special operations: `ordinal` and `current` expressions
   for row-specific processing

#### The `current` expression

The `current` expression refers to the current row. If there are
multiple fields, its type is a record with the fields defined by the
preceding step. If there is only one field, its type is the type of
that field.

<pre>
<i>(* Multiple fields. Current is a record. *)</i>
<b>from</b> i <b>in</b> [1, 2],
    j <b>in</b> ["a", "b"]
  <b>yield</b> <b>current</b>;
<i>
i j
- -
1 a
1 b
2 a
2 b

val it : {i:int, j:string} list</i>

<i>(* Single anonymous field. Current is an atom. *)</i>
<b>from</b> i <b>in</b> [1, 2, 3]
  <b>yield</b> i <b>mod</b> 2
  <b>yield</b> <b>current</b>;
<i>val it = [1,0,1] : int list</i>

<i>(* Single field named "dname". Current is an atom, but you
   can also refer to the field by name. *)</i>
<b>from</b> d <b>in</b> scott.depts
  <b>yield</b> d.dname
  <b>yield</b> <b>current</b>;
<i>val it = ["ACCOUNTING","RESEARCH","SALES","OPERATIONS"]
  : string bag</i>

<b>from</b> d <b>in</b> scott.depts
  <b>yield</b> d.dname
  <b>yield</b> dname;
<i>val it = ["ACCOUNTING","RESEARCH","SALES","OPERATIONS"]
  : string bag</i>

<i>(* Yield is a record expression with a single field.
   Current is a record. *)</i>
<b>from</b> i <b>in</b> [1, 2, 3]
  <b>yield</b> {j = i + 1}
  <b>yield</b> <b>current</b>;
<i>val it = [{j=2}, {j=3}, {j=4}] : {j:int} list</i>
</pre>

#### The `ordinal` expression

The `ordinal` expression refers to the ordinal number of the current
row, starting at 0.

<pre>
(* Print the top 5 employees by salary, and their rank. *)
<b>from</b> e <b>in</b> scott.emps
  <b>order</b> <b>DESC</b> e.sal
  <b>take</b> 5
  <b>yield</b> {e.ename, e.sal, rank = <b>ordinal</b> + 1};
<i>
ename rank sal
----- ---- ------
KING  1    5000.0
SCOTT 2    3000.0
FORD  3    3000.0
JONES 4    2975.0
BLAKE 5    2850.0

val it : {ename:string, rank:int, sal:real} list</i>
</pre>

`ordinal` is only available in steps
whose input is ordered.

<pre>
<b>from</b> e <b>in</b> scott.emps
  <b>yield</b> {e.ename, e.sal, rank = <b>ordinal</b> + 1};
<i>> stdIn:2.33-2.40 Error: cannot use 'ordinal' in unordered query
>   raised at: stdIn:2.33-2.40</i>
</pre>

### Step list

| Name                           | Summary                                                                                                               |
|--------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| [`distinct`](#distinct-step)   | Removes duplicate rows from the current collection.                                                                   |
| [`except`](#except-step)       | Returns the set (or multiset) difference between the current collection and one or more argument collections.         |
| [`group`](#group-step)         | Performs aggregation across groups of rows.                                                                           |
| [`intersect`](#intersect-step) | Returns the set (or multiset) intersection between the current collection and one or more argument collections.       |
| [`join`](#join-step)           | Joins one or more scans to the current collection.                                                                    |
| [`order`](#order-step)         | Sorts the current collection by an expression.                                                               |
| [`skip`](#skip-step)           | Skips a given number of rows from the current collection.                                                             |
| [`take`](#take-step)           | Limits the number of rows to return from the current collection.                                                      |
| [`through`](#through-step)     | Calls a table function, with the current collection as an argument, and starts a scan over the collection it returns. |
| [`union`](#union-step)         | Returns the set (or multiset) union between the current collection and one or more argument collections.              |
| [`unorder`](#unorder-step)     | Makes the current collection unordered.                                                                               |
| [`where`](#where-step)         | Emits rows of the current collection for which a given predicate evaluates to `true`.                                 |
| [`yield`](#yield-step)         | For each row in the current collection, evaluates an expression and emits it as a row.                                |

The following steps produce a single scalar or record value. Because
the output is not a collection, no further steps are possible, and
therefore they are called **terminal steps**.

It can be unwieldy to use a query in an expression such as `if` or
`case` if the query returns a collection. Queries with a terminal
step, and `forall` and `exists` queries, are easy to embed in an
expression.

| Name                       | Summary                                                       |
|----------------------------|---------------------------------------------------------------|
| [`compute`](#compute-step) | Applies aggregate functions to the current collection.        |
| [`into`](#into-step)       | Applies a function to the current collection.                 |
| [`require`](#require-step) | Evaluates the predicate of a [`forall`](#forall-query) query. |

### Distinct step

<pre>
<b>distinct</b>
</pre>

#### Description

Removes duplicate rows from the current collection.

The output fields are the same as the input fields.

#### Example

<pre>
<i>(* Compute the set of distinct rolls of two dice. *)</i>
<b>from</b> i <b>in</b> [1, 2, 3, 4, 5, 6],
    j <b>in</b> [1, 2, 3, 4, 5, 6]
  <b>yield</b> i + j
  <b>distinct</b>;

<i>val it = [2,3,4,5,6,7,8,9,10,11,12] : int list</i>
</pre>

### Except step

<pre>
<b>except</b> [ <b>distinct</b> ] <i>exp<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>exp<sub>e</sub></i>   (<i>e</i> &ge; 1)
</pre>

#### Description

Returns the set (or multiset) difference between the current
collection and one or more argument collections.

The `except` step returns the distinct elements from the input
collection that are not present in any of the arguments (the
collections resulting from evaluating the <code>exp<sub>i</sub></code>
expressions).

With the `distinct` keyword, the `except` step returns the elements
from the input collection that are not present in any of the argument
collections.  If an element occurs *x* times in the input collection
and a total of *y* times in the argument collections, the step emits the
element *x - y* times. If *y* is greater than *x*, the element is not
emitted.

`except` is equivalent to SQL `EXCEPT ALL`; `except distinct` is
equivalent to SQL `EXCEPT` or `EXCEPT DISTINCT`. (Some SQL dialects
use `MINUS` rather than `EXCEPT`.)

The output fields are the same as the input fields.

#### Example

<pre>
<i>(* Which job titles exist in department 10 but not in
   department 20 or 30? *)</i>
<b>from</b> e <b>in</b> scott.emps
  <b>where</b> e.deptno = 10
  <b>yield</b> e.job
  <b>except distinct</b>
    (<b>from</b> e <b>in</b> scott.emps
      <b>where</b> e.deptno = 20
      <b>yield</b> e.job),
    (<b>from</b> e <b>in</b> scott.emps
      <b>where</b> e.deptno = 30
      <b>yield</b> e.job);

<i>val it = ["PRESIDENT"] : string bag</i>
</pre>

<pre>
<i>(* I have two sodas and three candies and give my friend
   one soda and two candies. What do I have left? *)</i>
<b>from</b> i <b>in</b> ["candy", "soda", "soda", "candy", "candy"]
  <b>except</b> ["soda", "candy"];

<i>val it = ["candy","candy","soda"] : string list</i>
</pre>

### Group step

<pre>
<b>group</b> <i>exp<sub>1</sub></i> [ <b>compute</b> <i>exp<sub>2</sub></i> ]
</pre>

#### Description

Performs aggregation across groups of rows.

Groups the rows of the input collection by the group key,
<code><i>exp<sub>1</sub></i></code>. If there is a `compute` clause,
for each group, computes the aggregate expressions specified in
<code><i>exp<sub>2</sub></i></code>.

The output is an atom if there is a single field: if `group` is an
atom and `compute` is missing, or `group` is empty and `compute` is an
atom. Otherwise, the output is a record; `group` and `compute` must
both be records, or be atomic expressions from which a name can be
derived, and the output fields are the combined fields of those
records, whose names must be disjoint.

Field names are derived in the usual way for record fields.  An
explicit field name can be specified using an <code><i>id</i> =</code>
prefix. The explicit field name can be omitted if an implicit field
name can be derived: if the expression is <code><i>id</i></code> then
the implicit field name is <code><i>id</i></code>; if the expression
is <code><i>record</i>.<i>field</i></code> then the implicit field
name is <code><i>field</i></code>; if the expression is
<code><i>agg</i> <b>over</b> <i>arg</i></code> then the implicit field
name is <code><i>agg</i></code>.

The `over` operator applies an aggregate function to a collection of
values. The left operand of `over` is an aggregate expression; it is
typically an aggregate function such as `count` or `sum` but may be an
expression such as `min 0` or `fn x => x`. The right operand of `over`
is an expression that is evaluated for each element in the group.

An `over` expression is just an expression (albeit one that can only
be used in a `compute` clause), and that means that it can be part of
a larger expression. That larger expression can include other `over`
expressions, for example `((min over e.sal) + (max over e.sal)) / 2`.

#### Example

<pre>
<i>(* Count employees and compute total salary for each
   department. *)</i>
<b>from</b> e <b>in</b> scott.emps
  <b>group</b> e.deptno
    <b>compute</b> {count <b>over</b> (), sumSal = sum <b>over</b> e.sal};
<i>count deptno sumSal
----- ------ -------
5     20     10875.0
3     10     8750.0
6     30     9400.0

val it : {count:int, deptno:int, sumSal:real} bag</i>

<i>(* One group key and no compute expressions gives an
   atomic result. *)</i>
<b>from</b> e <b>in</b> scott.emps
  <b>group</b> e.deptno;
<i>
val it = [20,10,30] : int bag</i>

<i>(* Empty group key and one compute expression gives an
   atomic result. *)</i>
<b>from</b> e <b>in</b> scott.emps
  <b>group</b> {} <b>compute</b> min <b>over</b> e.sal + e.comm;
<i>
val it = [800.0] : real bag</i>
</pre>

### Intersect step

<pre>
<b>intersect</b> [ <b>distinct</b> ] <i>exp<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>exp<sub>i</sub></i>   (<i>i</i> &ge; 1)
</pre>

#### Description

Returns the set (or multiset) intersection between the current
collection and one or more argument collections.

The `intersect` step returns the distinct elements that are present in
the input collection and all argument collections (the collections
resulting from evaluating the <code>exp<sub>i</sub></code>
expressions).

With the `distinct` keyword, the `intersect` emits an element if it
occurs in the input and all argument collections, and may emit it
multiple times.  If an element occurs *x* times in the input
collection, <code>y<sub>1</sub></code> times in argument collection 1,
<code>y<sub>2</sub></code> times in argument collection 2, and so
forth, the step will emit it *z* times, where *z* is
<code>min(<i>x</i>, <i>y<sub>1</sub></i>, ..., <i>y<sub>i</sub></i>)</code>.

`intersect` is equivalent to SQL `INTERSECT ALL`; `intersect distinct`
is equivalent to SQL `INTERSECT` or `INTERSECT DISTINCT`.

The output fields are the same as the input fields.

#### Example

<pre>
<i>(* Which job titles exist in department 10 and also in
   departments 20 and 30? *)</i>
<b>from</b> e <b>in</b> scott.emps
  <b>where</b> e.deptno = 10
  <b>yield</b> e.job
  <b>intersect distinct</b>
    (<b>from</b> e <b>in</b> scott.emps
      <b>where</b> e.deptno = 20
      <b>yield</b> e.job),
    (<b>from</b> e <b>in</b> scott.emps
      <b>where</b> e.deptno = 30
      <b>yield</b> e.job);

<i>val it = ["CLERK","MANAGER"] : string bag</i>
</pre>

<pre>
<i>(* I have two sodas and three candies, and my friend
   has one soda, one donut, and four candies. What do we
   have in common? *)</i>
<b>from</b> i <b>in</b> ["candy", "soda", "soda", "candy", "candy"]
  <b>intersect</b> ["soda", "donut", "candy",
             "donut", "candy", "candy"];

<i>val it = ["candy","candy","candy","soda"] : string list</i>
</pre>

### Join step

<pre>
<b>join</b> <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i>   (<i>s</i> &ge; 1)

<i>scan</i> &rarr; <i>pat</i> <b>in</b> <i>exp</i> [ <b>on</b> <i>exp</i> ]
    | <i>pat</i> <b>=</b> <i>exp</i> [ <b>on</b> <i>exp</i> ]
    | <i>var</i>
</pre>

#### Description

Joins one or more scans to the current collection.

The output fields are the input fields plus the identifiers in the
<code><i>pat</i></code> and <code><i>var</i></code> of each of the
scans. Field names must be unique.

If any scan has an `on` clause, the expression must be of type
<b>bool</b> and may reference any variable in the environment,
including the output fields of the previous step, and fields defined
by any preceding scans in this `join`.

Morel does not yet implement [outer join](https://github.com/hydromatic/morel/issues/75).

#### Example

<pre>
<i>(* Find the name of each department and the name of all
   employees in those departments. *)</i>
<b>from</b> d <b>in</b> scott.depts
  <b>join</b> e <b>in</b> scott.emps <b>on</b> e.deptno = d.deptno
  <b>yield</b> {d.dname, e.ename};
<i>
dname      ename
---------- ------
ACCOUNTING CLARK
ACCOUNTING KING
ACCOUNTING MILLER
RESEARCH   SMITH
RESEARCH   JONES
RESEARCH   SCOTT
RESEARCH   ADAMS
RESEARCH   FORD
SALES      ALLEN
SALES      WARD
SALES      MARTIN
SALES      BLAKE
SALES      TURNER
SALES      JAMES

val it : {dname:string, ename:string} bag</i>
</pre>

### Order step

<pre>
<b>order</b> <i>exp</i>
</pre>

#### Description

Sorts the current collection by an expression.

Ordering is determined by the data type, and is defined not just for
scalar expressions like `int` and `string`, but structured types like
tuples, records, lists, and sum types. The rules are as follows:

* Primitive types
  * `bool` orders `false` < `true`;
  * `char` orders as defined by `Char.compare`;
  * `int` orders as defined by `Int.compare`;
  * `real` orders as defined by `Real.compare`;
  * `string` orders as defined by `String.compare`;
  * `unit` has only value, `()`, which compares equal to itself.
* Lists are ordered lexicographically,
  e.g. `[]` < `[3]` < `[3, 1]` < `[3, 2]` < `[4]`.
* Tuples are ordered lexicographically on fields left-to-right,
  e.g. `(1, "b")` < `(1, "c")` < `(2, "a")`.
* Records are ordered lexicographically on fields in alphabetical order,
  e.g. `{x=1, y="b"}` < `{x=1, y="c"}` < `{x=2, y="a"}`.
* Sum types are ordered by the label in declaration order, then by
  values. For example, a sum type `foo` declared as
  <pre>datatype foo =
      EMPTY
    | SINGLE of int
    | PAIR of int * string</pre>
  orders `EMPTY` < `SINGLE 1` < `SINGLE 2` < `PAIR (1, "b")` < `PAIR (2, "a")`.
* `Option` is declared as a sum type
  <pre>datatype 'a Option =
      NONE
    | SOME of 'a</pre>
  and follows the usual rules for sum types,
  therefore `NONE` < `SOME 1` < `SOME 2`.
  (Since `NONE` is Morel's equivalent of SQL's null value,
  this ordering corresponds to SQL `NULLS FIRST`.)
* `Descending` is declared as a sum type
  <pre>datatype 'a Descending = DESC of 'a</pre>
  but has special ordering semantics, reversing the value's order.
  Thus `DESC 3` < `DESC 2` < `DESC 1`.

Common sort specifications can be achieved using tuple types and/or
`DESC`. For example, the equivalent of SQL `ORDER BY e.job, e.sal DESC`
is `order (e.job, DESC e.sal)`.

The output fields are the same as the input fields.

The `order` step is not stable. Even in the event of ties (multiple
elements with the same sort key) the order that it outputs elements is
not affected by the order in which elements arrived. (If you wish to
achieve a stable sort, you can use the `ordinal` expression in a
secondary sort key.)

#### Example

<pre>
<i>(* List employees ordered by salary (descending) then
   name. *)</i>
<b>from</b> e <b>in</b> scott.emps
  <b>order</b> (<b>DESC</b> e.sal, e.ename)
  <b>yield</b> {e.ename, e.job, e.sal};
<i>
ename  job       sal
------ --------- ------
KING   PRESIDENT 5000.0
FORD   ANALYST   3000.0
SCOTT  ANALYST   3000.0
JONES  MANAGER   2975.0
BLAKE  MANAGER   2850.0
CLARK  MANAGER   2450.0
ALLEN  SALESMAN  1600.0
TURNER SALESMAN  1500.0
MILLER CLERK     1300.0
MARTIN SALESMAN  1250.0
WARD   SALESMAN  1250.0
ADAMS  CLERK     1100.0
JAMES  CLERK      950.0
SMITH  CLERK      800.0

val it : {ename:string, job:string, sal:real} list</i>
</pre>

If you sort by a record whose fields do not appear in alphabetical
order, Morel gives a warning.

<pre><i>(* Record expressions are sorted lexicographically on field
   name, that is, by job then by salary. Morel gives a
   warning, in case you were expecting to sort on salary
   first. *)</i>
<b>from</b> e <b>in</b> scott.emps
  <b>order</b> {e.sal, e.job}
  <b>yield</b> {e.ename, e.job, e.sal};
<i>
ename  job       sal
------ --------- ------
SCOTT  ANALYST   3000.0
FORD   ANALYST   3000.0
SMITH  CLERK     800.0
JAMES  CLERK     950.0
ADAMS  CLERK     1100.0
MILLER CLERK     1300.0
CLARK  MANAGER   2450.0
BLAKE  MANAGER   2850.0
JONES  MANAGER   2975.0
KING   PRESIDENT 5000.0
WARD   SALESMAN  1250.0
MARTIN SALESMAN  1250.0
TURNER SALESMAN  1500.0
ALLEN  SALESMAN  1600.0

val it : {ename:string, job:string, sal:real} list

Warning: Sorting on a record whose fields are not in
alphabetical order. Sort order may not be what you expect.
  raised at: stdIn:2.9-2.23</i>
</pre>

### Skip step

<pre>
<b>skip</b> <i>exp</i>
</pre>

#### Description

Skips a given number of rows from the current collection.

The expression <code><i>exp</i></code> must evaluate to an integer,
which specifies the number of rows to skip from the beginning of the
current collection. It is an error if the value is negative. If the
value exceeds the number of rows in the collection, no rows are
returned.

The output fields are the same as the input fields.

#### Example

<pre>
<i>(* Skip the first 3 rows of a collection. *)</i>
<b>from</b> i <b>in</b> [1, 2, 3, 4, 5, 6, 7]
  <b>skip</b> 3;
<i>
val it = [4,5,6,7] : int list</i>
</pre>

### Take step

<pre>
<b>take</b> <i>exp</i>
</pre>

#### Description

Limits the number of rows to return from the current collection.

The expression <code><i>exp</i></code> must evaluate to an integer,
which specifies the maximum number of rows to return from the current
collection. If the value is zero, no rows are returned. It is an error
if the value is negative.

The output fields are the same as the input fields.

#### Example

<pre>
<i>(* Return only the first 3 rows of a collection. *)</i>
<b>from</b> i <b>in</b> [1, 2, 3, 4, 5, 6, 7]
  <b>take</b> 3;
<i>
val it = [1,2,3] : int list</i>
</pre>

### Through step

<pre>
<b>through</b> <i>pat</i> <b>in</b> <i>exp</i>
</pre>

#### Description

Calls a table function, with the current collection as an argument,
and starts a scan over the collection it returns.

The expression <code><i>exp</i></code> must evaluate to a function
that takes the current collection as an argument and returns a new
collection. The pattern <code><i>pat</i></code> is bound to each
element of the returned collection.

The output fields are the fields defined by the pattern
<code><i>pat</i></code>.

#### Example

<pre>
<i>(* Define a table function that returns the even numbers
   from a collection. *)</i>
<b>fun</b> evenNumbers (xs: int list) =
  <b>from</b> x <b>in</b> xs
    <b>where</b> x <b>mod</b> 2 = 0;

<i>(* Use the table function in a query. *)</i>
<b>from</b> i <b>in</b> [1, 2, 3, 4, 5, 6, 7]
  <b>through</b> j <b>in</b> evenNumbers;
<i>
val it = [2,4,6] : int list</i>
</pre>

The previous example can be generalized to find multiples of any given
number.  The table function now takes two arguments, and we provide
the first argument in the `through` clause; the input collection
becomes the second argument.

<pre>
<i>(* Define a table function that returns the numbers from
   a collection that are multiples of base. *)</i>
<b>fun</b> multiplesOf base (xs: int list) =
  <b>from</b> x <b>in</b> xs
    <b>where</b> x <b>mod</b> base = 0;

<i>(* Use the table function to find multiples of 3. *)</i>
<b>from</b> i <b>in</b> [1, 2, 3, 4, 5, 6, 7]
  <b>through</b> j <b>in</b> multiplesOf 3;
<i>
val it = [3,6] : int list</i>
</pre>

#### Description

Calls a table function, with the current collection as an argument,
and starts a scan over the collection it returns.

### Union step

<pre>
<b>union</b> [ <b>distinct</b> ] <i>exp<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>exp<sub>i</sub></i>   (<i>i</i> &ge; 1)
</pre>

#### Description

Returns the set (or multiset) union between the current
collection and one or more argument collections.

The `union` step returns the distinct elements that are present in
the input collection or any of the argument collections (the collections
resulting from evaluating the <code>exp<sub>u</sub></code>
expressions).

With the `distinct` keyword, the `union` step may emit an element
multiple times.  If an element occurs *x* times in the input
collection, <code>y<sub>1</sub></code> times in argument collection 1,
<code>y<sub>2</sub></code> times in argument collection 2, and so
forth, the step will emit it *z* times, where *z* is
<code><i>x</i> + <i>y<sub>1</sub></i> + ... + <i>y<sub>u</sub></i>)</code>.

`union` is equivalent to SQL `UNION ALL`; `union distinct`
is equivalent to SQL `UNION` or `UNION DISTINCT`.

The output fields are the same as the input fields.

#### Example

<pre>
<i>(* Which job titles exist in departments 10, 20 and 30? *)</i>
<b>from</b> e <b>in</b> scott.emps
  <b>where</b> e.deptno = 10
  <b>yield</b> e.job
  <b>union distinct</b>
    (<b>from</b> e <b>in</b> scott.emps
      <b>where</b> e.deptno = 20
      <b>yield</b> e.job),
    (<b>from</b> e <b>in</b> scott.emps
      <b>where</b> e.deptno = 30
      <b>yield</b> e.job);

<i>val it = ["MANAGER","PRESIDENT","CLERK","ANALYST","SALESMAN"]
  : string bag</i>
</pre>

<pre>
<i>(* I have two sodas and three candies, and my friend has
   one soda and one candy. What do we have if we combine
   our stashes? *)</i>
<b>from</b> i <b>in</b> ["candy", "soda", "soda", "candy", "candy"]
  <b>union</b> ["soda", "candy"];

<i>val it = ["candy","soda","soda","candy","candy","soda","candy"]
  : string list</i>
</pre>

### Unorder step

<pre>
<b>unorder</b>
</pre>

#### Description

Removes the ordering of the current collection.

The output fields are the same as the input fields.

Why would you want to remove ordering? Some relational operators can
be more expensive when the input collection is ordered, and if you
don't need the ordering, you can remove it to improve performance.

Consider the `union` step. When applied to lists, its output must be
ordered, consisting of the elements of the input in order, followed by
the elements the first argument in order, and so forth. This limits
parallelism.  When the output of a `union` step is unordered, such as
when it is applied to bags or when followed by an `unorder` step, it
can execute its arguments in parallel.  Furthermore, its inputs can
operate in unordered mode, which may lead to further efficiencies.

Unordered collections can be propagated towards inputs except for the
[`skip`](#skip-step) and [`take`](#take-step) steps, and any step that
evaluates the `ordinal` expression.

Surprisingly, the `order` step is somewhat similar to `unorder`.
While its output is ordered, of course, `order` disregards the order
of its input. This allows upstream steps to operate in unordered mode,
if that is more efficient.

#### Example

<pre>
<i>(* Find the top 3 employees by salary, as an unordered
   collection. *)</i>
<b>from</b> e <b>in</b> scott.emps
  <b>order</b> <b>DESC</b> e.sal
  <b>take</b> 3
  <b>unorder</b>
  <b>yield</b> {e.ename, e.job, e.sal};
<i>
ename job       sal
----- --------- ------
KING  PRESIDENT 5000.0
SCOTT ANALYST   3000.0
FORD  ANALYST   3000.0

val it : {ename:string, job:string, sal:real} bag</i>
</pre>

### Where step

<pre>
<b>where</b> <i>exp</i>
</pre>

#### Description

Emits rows of the current collection for which a given predicate
evaluates to `true`.

The expression <code><i>exp</i></code> must evaluate to a boolean
value. Only rows for which the expression evaluates to `true` are
emitted to the output.

The output fields are the same as the input fields.

#### Example

<pre>
<i>(* Find employees who work in department 20. *)</i>
<b>from</b> e <b>in</b> scott.emps
  <b>where</b> e.deptno = 20
  <b>yield</b> {e.ename, e.job};
<i>
ename job
----- -------
SMITH CLERK
JONES MANAGER
SCOTT ANALYST
ADAMS CLERK
FORD  ANALYST

val it : {ename:string, job:string} bag</i>
</pre>

### Yield step

<pre>
<b>yield</b> <i>exp</i>
</pre>

#### Description

For each row in the current collection, evaluates an expression and
emits it as a row.

The expression <code><i>exp</i></code> defines the output fields. If
<code><i>exp</i></code> is a record expression, its field names become
the output field names.

If this is the last step in the query, the expression may be a
non-record type. In this case, there are no output fields, and the
result of the query is a collection of that non-record type.

#### Example

<pre>
<i>(* Create a new record from each employee with modified
   fields. *)</i>
<b>from</b> e <b>in</b> scott.emps
  <b>yield</b> {name = String.map Char.toUpper e.ename,
      position = String.map Char.toLower e.job,
      annualSalary = e.sal * 12.0};
<i>
annualSalary name   position
------------ ------ ---------
9600.0       SMITH  clerk
19200.0      ALLEN  salesman
15000.0      WARD   salesman
35700.0      JONES  manager
15000.0      MARTIN salesman
34200.0      BLAKE  manager
29400.0      CLARK  manager
36000.0      SCOTT  analyst
60000.0      KING   president
18000.0      TURNER salesman
13200.0      ADAMS  clerk
11400.0      JAMES  clerk
36000.0      FORD   analyst
15600.0      MILLER clerk

val it : {annualSalary:real, name:string, position:string} bag</i>
</pre>

<pre>
<i>(* Return a list of strings describing each employee in
   department 20. *)</i>
<b>from</b> e <b>in</b> scott.emps
  <b>where</b> e.deptno = 20
  <b>yield</b> e.ename ^ " is a " ^ e.job;
<i>
val it =
  ["SMITH is a CLERK","JONES is a MANAGER","SCOTT is a ANALYST",
   "ADAMS is a CLERK","FORD is a ANALYST"] : string bag</i>
</pre>

### Compute terminal step

<pre>
<b>compute</b> <i>agg<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>agg<sub>a</sub></i>   (<i>a</i> &ge; 1)

<i>agg</i> &rarr; [ <i>id</i> <b>=</b> ] <i>exp</i> [ <b>of</b> <i>exp</i> ]
</pre>

#### Description

Applies aggregate functions to the current collection.

Unlike the [`group`](#group-step) step, which groups rows and computes
aggregates for each group, the `compute` terminal step computes
aggregates across the entire collection and returns a single record or
scalar value.

The output is a scalar value if there is one aggregate, or a record if
there is more than one. That value becomes the result of the query
expression.

Field names are derived in the same way as the `group` step. An
explicit field name of an <code><i>agg</i></code> can be specified
using an <code><i>id</i> =</code> prefix. The explicit field name can
be omitted if an implicit field name can be derived: if the aggregate
function is <code><i>id</i></code> then the implicit field name is
<code><i>id</i></code>; if the aggregate function is
<code><i>record</i>.<i>field</i></code> then the implicit field name
is <code><i>field</i></code>.

#### Example

<pre>
<i>(* Compute total number of employees and average salary. *)</i>
<b>from</b> e <b>in</b> scott.emps
  <b>compute</b> total = count,
           avgSal = avg <b>of</b> e.sal,
           minSal = min <b>of</b> e.sal,
           maxSal = max <b>of</b> e.sal;
<i>
val it = {maxSal=5000.0,minSal=800.0,sumSal=29025.0,total=14}
  : {maxSal:real, minSal:real, sumSal:real, total:int}</i>
</pre>

<pre>
<i>(* Compute total number of employees. *)</i>
<b>from</b> e <b>in</b> scott.emps
  <b>compute</b> count;
<i>
val it = 14 : int</i>
</pre>

### Into terminal step

<pre>
<b>into</b> <i>exp</i>
</pre>

#### Description

Applies a function to the current collection.

The expression <code><i>exp</i></code> must evaluate to a function
that takes the current collection as an argument. The result of the
query is the result of applying that function to the collection.

#### Example

<pre>
<i>(* Apply a custom function to the query results. *)</i>
<b>fun</b> analyzeResults (emps: {deptno: int, sal: real} bag) =
  <b>let</b>
    <b>val</b> {count, sumSal} =
      <b>from</b> e <b>in</b> emps
        <b>compute</b> {count <b>over</b> (), sumSal = sum <b>over</b> e.sal}
    <b>val</b> avgSal = sumSal / real count
  <b>in</b>
    {employeeCount = count,
      averageSalary = avgSal,
      classification = <b>if</b> avgSal > 2000.0 <b>then</b> "High" <b>else</b> "Low"}
  <b>end</b>;

<b>from</b> e <b>in</b> scott.emps
  <b>where</b> e.deptno = 10
  <b>yield</b> {e.deptno, e.sal}
  <b>into</b> analyzeResults;
<i>
val it = {employeeCount=3, averageSalary=2916.67, classification="High"}
  : {employeeCount:int, averageSalary:real, classification:string}</i>
</pre>

### Require terminal step

<pre>
<b>require</b> <i>exp</i>
</pre>

#### Description

Evaluates the predicate of a `forall` query.

This step is only valid as the last step of a `forall` query. The
expression <code><i>exp</i></code> must evaluate to a boolean
value. The result of query is `true` if the predicate evaluates to
`true` for every row in the collection, or if the collection is empty.

#### Example

<pre>
<i>(* Check whether all employees earn more than $2000. *)</i>
<b>forall</b> e <b>in</b> scott.emps
  <b>require</b> e.sal > 2000.0;
<i>
val it = false : bool</i>
</pre>

<pre>
<i>(* Check whether all managers earn more than $2000. *)</i>
<b>forall</b> e <b>in</b> scott.emps
  <b>where</b> e.job = "MANAGER"
  <b>require</b> e.sal > 2000.0;
<i>
val it = true : bool</i>
</pre>

## Quantified queries

Morel provides query forms for existential and universal
quantification:
* `exists` returns whether at least one row in the query satisfies the
  critera (existential quantification);
* `forall` returns whether all rows in the query satisfy the criteria
  (universal quantification).

### Exists query

<pre>
<b>exists</b> [ <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i> ] <i>step<sub>1</sub></i> ... <i>step<sub>t</sub></i>   (<i>s</i> &ge; 0, <i>t</i> &ge; 0)
</pre>


An `exists` query returns `true` if the query returns at least one
row, and `false` otherwise.

#### Example

<pre>
<i>(* Do any employees earn more than $3,000? *)</i>
<b>exists</b> e <b>in</b> scott.emps
  <b>where</b> e.sal > 3000.0;

<i>val it = true : bool</i>
</pre>

### Forall query

<pre>
<b>forall</b> [ <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i> ] <i>step<sub>1</sub></i> ... <i>step<sub>t</sub></i>   (<i>s</i> &ge; 0, <i>t</i> &ge; 0)
  <b>require</b> <i>exp</i>
</pre>

A `forall` query returns `true` if the predicate specified in the
`require` step evaluates to `true` for every row that reaches that
step, or no rows reach that step. It returns `false` if the predicate
evaluates to `false` for at least one row.

Rows that are eliminated by previous steps (such as `where`) and do
not reach the `require` step do not count as evaluations of the
predicate.

#### Example

<pre>
<i>(* Do all employees have a job title of clerk, manager or
   president? *)</i>
<b>forall</b> e <b>in</b> scott.emps
  <b>require</b> e.job <b>elem</b> ["CLERK", "MANAGER", "PRESIDENT"];
<i>val it = false : bool</i>
</pre>

<pre>
<i>(* Do all employees in department 10 have a job title of
   clerk, manager or president? *)</i>
<b>forall</b> e <b>in</b> scott.emps
  <b>where</b> e.deptno = 10
  <b>require</b> e.job <b>elem</b> ["CLERK", "MANAGER", "PRESIDENT"];
<i>val it = true : bool</i>
</pre>

<pre>
<i>(* Are all employees in department 10 and have a job
   title of clerk, manager or president? *)</i>
<b>forall</b> e <b>in</b> scott.emps
  <b>require</b> e.deptno = 10
    <b>andalso</b> e.job <b>elem</b> ["CLERK", "MANAGER", "PRESIDENT"];
<i>val it = false : bool</i>
</pre>

## Correspondence between SQL and Morel query

Many of the keywords in a SQL query have an equivalent in Morel.

| SQL         | Morel       | Remarks                                                                                                                                                                                                                |
|-------------|-------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SELECT`    | `yield`     | While `SELECT` must be the first keyword of a SQL query, you may use `yield` at any point in a Morel pipeline. It often occurs last, and you can omit it if the output record already has the right shape.             |
| `FROM`      | `from`      | Unlike SQL `FROM`, `from` is the first keyword in a Morel query.                                                                                                                                                       |
| `JOIN`      | `join`      | SQL `JOIN` is part of the `FROM` clause, but Morel `join` is a step.                                                                                                                                                   |
| `WHERE`     | `where`     | Morel `where` is equivalent to SQL `WHERE`.                                                                                                                                                                            |
| `HAVING`    |             | Use a `where` after a `group`.                                                                                                                                                                                         |
| `DISTINCT`  | `distinct`  | SQL `DISTINCT` is part of the `SELECT` clause, but Morel `distinct` is a step, shorthand for `group`                                                                                                                   |
| `ORDER BY`  | `order`     | Morel `order` is equivalent to SQL `ORDER BY`.                                                                                                                                                                         |
| `LIMIT`     | `take`      | Morel `take` is equivalent to SQL `LIMIT`.                                                                                                                                                                             |
| `OFFSET`    | `skip`      | Morel `skip` is equivalent to SQL `OFFSET`.                                                                                                                                                                            |
| `UNION`     | `union`     | Morel `union` is equivalent to SQL `UNION ALL`; `union distinct` is equivalent to `UNION`.                                                                                                                             |
| `INTERSECT` | `intersect` | Morel `intersect` is equivalent to SQL `INTERSECT ALL`; `intersect distinct` is equivalent to `INTERSECT`.                                                                                                             |
| `EXCEPT`    | `except`    | Morel `except` is equivalent to SQL `EXCEPT ALL`; `except distinct` is equivalent to `EXCEPT`. (Some dialects use `MINUS` rather than `EXCEPT`.)                                                                       |
| `EXISTS`    | `exists`    | SQL `EXISTS` is a unary operator whose operand is a query, but Morel `exists` is a query that returns `true` if the query has at least one row.                                                                        |
| -           | `forall`    | Morel `forall` is a query that returns `true` if a predicate is true for all rows.                                                                                                                                     |
| `IN`        | `elem`      | SQL `IN` is a binary operator whose right operand is either a query or a list (but not an array or multiset); Morel `elem` is the equivalent operator, and its right operand can be any collection, including a query. |
| `NOT IN`    | `notelem`   | Morel `notelem` is equivalent to SQL `NOT IN`, but without SQL's confusing [NULL-value semantics](https://community.snowflake.com/s/article/Behaviour-of-NOT-IN-with-NULL-values).                                     |
| -           | `yieldall`  | Morel `yieldall` evaluates a collection expression and outputs one row for each element of that collection.                                                                                                            |
