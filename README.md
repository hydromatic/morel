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
# smlj
Standard ML interpreter, implemented in Java

## Requirements

Java version 8 or higher.

## Get smlj

### From Maven

Get smlj from
<a href="https://search.maven.org/#search%7Cga%7C1%7Cg%3Anet.hydromatic%20a%3Asmlj">Maven Central</a>:

```xml
<dependency>
  <groupId>net.hydromatic</groupId>
  <artifactId>smlj</artifactId>
  <version>0.1.0</version>
</dependency>
```

### Download and build

```bash
$ git clone git://github.com/julianhyde/smlj.git
$ cd smlj
$ ./mvnw install
```

On Windows, the last line is

```bash
> mvnw install
```

### Run the shell

```bash
$ ./smlj
smlj version 0.1.0 (java version "11.0.4", JLine terminal, xterm-256color)
= "Hello, world!";
val it = "Hello, world!" : string
= exit
$
```

## Status

Implemented:
* Literals
* Variables
* Comments (`(* block *)` and `(*) line`)
* `let` (expression that lets you define local variables and functions)
* `val` (including `val rec`)
* `fun` (declare function)
* Operators: `=` `<>` `<` `>` `<=` `>=`
  `~` `+` `-` `*` `/` `div` `mod` `^`
  `andalso` `orelse`
  `::`
* Built-in constants and functions:
  `it` `true` `false` `nil` `abs` `not` `ignore`
* Type derivation
* `fn`, function values, and function application
* `if`
* `case`
* Primitive, list, tuple and record types
* Type variables (polymorphism) (but see "bugs")
* Enumerated types (`datatype`)
* Tuples and unit, record and list values
* Patterns (destructuring) in `val` and `case`,
  matching constants, wildcards, lists, records and tuples
* Basis library functions:
  * [Top](http://sml-family.org/Basis/top-level-chapter.html): `abs`
  * [List](http://sml-family.org/Basis/list.html): `null`, `length`,
    `@` (as prefix `at` for now), `hd`, `tl`, `last`, `getItem`, `nth`,
    `take`, `drop`, `rev`, `concat`, `revAppend`, `app`, `map`, `mapPartial`,
     `find`, `filter`, `partition`, `foldl`, `foldr`, `exists`, `all`,
     `tabulate`, `collate`
  * [String](http://sml-family.org/Basis/string.html): `maxSize`, `size`, `sub`,
    `extract`, `substring`, `^`, `concat`, `concatWith`, `str`, `implode`,
    `explode`, `map`, `translate`, `isPrefix`, `isSubstring`, `isSuffix`

Not implemented:
* `type`
* `local`
* `raise`, `handle`
* `exception`
* `while`
* References, and operators `!` and `:=`
* Operators: `before` `o`
* User-defined operators (`infix`, `infixr`)
* Type annotations in expressions and patterns

Bugs:
* The `@` infix operator to concatenate lists is currently named `at` and is
  prefix;
* Built-in operators for lists operate on `int` lists not `'a` lists;
* Built-in operators do not use the `option` type;
* Unbound type variables are not yet supported. For example, the
  expression `[]` should have type `'a list` but currently fails
* Prevent user from overriding built-in constants and functions:
  `true`, `false`, `nil`, `ref`, `it`, `::`; they should not be reserved
* Access parameters and variables by offset into a fixed-size array;
  currently we address them by name, in a map that is copied far too often
* Runtime should throw when divide by zero
* Validator should give good user error when it cannot type an expression

## Relational extensions

The `from` expression (and associated `as`, `where` and `yield` keywords)
is a language extension to support relational algebra.
It iterates over a list and generates another list.

In a sense, `from` is syntactic sugar. For example, given `emps` and
`depts`, relations defined as lists of records as follows

```
val emps =
  [{id = 100, name = "Fred", deptno = 10},
   {id = 101, name = "Velma", deptno = 20},
   {id = 102, name = "Shaggy", deptno = 30};
   {id = 103, name = "Scooby", deptno = 30}];
val depts =
  [{deptno = 10, name = "Sales"},
   {deptno = 20, name = "Marketing"},
   {deptno = 30, name = "Engineering"},
   {deptno = 40, name = "Support"}];
```

the expression

```
from e in emps where (#deptno e = 30) yield (#id e)
```

is equivalent to standard ML

```
map (fn e => (#id e)) (filter (fn e => (#deptno e) = 30) emps)
```

with the `where` and `yield` clauses emulating the `filter` and `map`
higher-order functions without the need for lambdas (`fn`).

Relational expressions are an experiment bringing the features of
query languages such as SQL into a functional language.
We believe that a little syntactic sugar, backed by a relational query
planner, makes ML into a powerful and convenient tool for querying
large data sets.
Conversely, we want to see how SQL would look if it supported lambdas,
function-values, polymorphism, pattern-matching, and removed the
syntactic distinction between tables and collection-valued columns.

You can iterate over more than one collection, and therefore generate
a join or a cartesian product:

```
from e in emps, d in depts
  where (#deptno e) = (#deptno d)
  yield {id = (#id e), deptno = (#deptno e), ename = (#name e), dname = (#name d)};
```

As in any ML expression, you can define functions within a `from` expression,
and those functions can operate on lists. Thus we can implement equivalents of
SQL's `IN` and `EXISTS` operators:

```
let
  fun in_ e [] = false
    | in_ e (h :: t) = e = h orelse (in_ e t)
in
  from e in emps
  where in_ (#deptno e) (from d in depts
                where (#name d) = "Engineering"
                yield (#deptno d))
  yield (#name e)
end

let
  fun exists [] = false
    | exists hd :: tl = true
in
  from e in emps
  where exists (from d in depts
                where (#deptno d) = (#deptno e)
                andalso (#name d) = "Engineering")
  yield (#name e)
end
```

In the second query, note that the sub-query inside the `exists` is
correlated (references the `e` variable from the enclosing query)
and skips the `yield` clause (because it doesn't matter which columns
the sub-query returns, just whether it returns any rows).

## More information

* License: <a href="LICENSE">Apache License, Version 2.0</a>
* Author: Julian Hyde (<a href="https://twitter.com/julianhyde">@julianhyde</a>)
* Project page: https://www.hydromatic.net/smlj
* API: https://www.hydromatic.net/smlj/apidocs
* Source code: https://github.com/julianhyde/smlj
* Developers list:
  <a href="mailto:dev@calcite.apache.org">dev at calcite.apache.org</a>
  (<a href="https://mail-archives.apache.org/mod_mbox/calcite-dev/">archive</a>,
  <a href="mailto:dev-subscribe@calcite.apache.org">subscribe</a>)
* Issues: https://github.com/julianhyde/smlj/issues
* <a href="HISTORY.md">Release notes and history</a>
