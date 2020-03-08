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
# Morel release history and change log

For a full list of releases, see
<a href="https://github.com/julianhyde/morel/releases">github</a>.

## <a href="https://github.com/julianhyde/morel/releases/tag/morel-0.2">0.2</a> / 2020-03-10

The first release since smlj was renamed to Morel includes major
improvements to the type system and relational extensions. Some highlights:
* Functions and values can have polymorphic types, inferred as part of a
  [Hindley-Milner type system](https://en.wikipedia.org/wiki/Hindley%E2%80%93Milner_type_system);
* Relational expressions may now include a `group` clause, so you can
  evaluate aggregate queries (similar to SQL `GROUP BY`);
* [Foreign values](https://github.com/julianhyde/morel/issues/5) allow external
  data, such as the contents of a JDBC database, to be handled as if it is in
  memory;
* Add built-in functions based on the
  [`String`](http://sml-family.org/Basis/string.html) and
  [`List`](http://sml-family.org/Basis/list.html) structures
  in the Standard ML basis library;
* [Postfix field reference syntax](https://github.com/julianhyde/morel/issues/9)
  makes Morel more familiar to SQL users;
* Add [Morel language reference](docs/reference.md).

### Features

* [<a href="https://github.com/julianhyde/morel/issues/15">MOREL-15</a>]
  Improve pretty-printing: wrap long lines, and abbreviate long lists and deep
  structures
* [<a href="https://github.com/julianhyde/morel/issues/12">MOREL-12</a>]
  In `from` clause, allow initializers to reference previous variables
* In `group`, `as alias` is optional
* [<a href="https://github.com/julianhyde/morel/issues/10">MOREL-10</a>]
  Implicit labels in record expressions
* [<a href="https://github.com/julianhyde/morel/issues/9">MOREL-9</a>]
  Allow `<expr>.<field>` as an alternative syntax for `#<field> <expr>`
* [<a href="https://github.com/julianhyde/morel/issues/7">MOREL-7</a>]
  Rename project from 'smlj' to 'morel'
* [<a href="https://github.com/julianhyde/morel/issues/5">SMLJ-5</a>]
  Foreign values, including record values based on the contents of a JDBC schema
* [<a href="https://github.com/julianhyde/morel/issues/6">SMLJ-6</a>]
  Add `group` clause (and `compute` sub-clause) to `from` expression, to support
  aggregation and aggregate functions
* Polymorphic types
* Add `String` and `List` basis functions

### Bug-fixes and internal improvements

* [<a href="https://github.com/julianhyde/morel/issues/16">MOREL-16</a>]
  Ensure that types derived for REPL expressions have no free type variables
* [<a href="https://github.com/julianhyde/morel/issues/14">MOREL-14</a>]
  Tuple should equal record, and both equal `unit` when empty
* Add macros (special built-in functions that are 'called' at compile time to
  generate a new AST)
* Add `interface MutableEvalEnv`, for code that wants to mutate the last
  binding in an environment
* Make `EvalEnv` immutable
* Recursive functions in closures use the wrong environment
* Unit literal's `toString()` should be `()`, not `[]`
* For built-ins, add their alias to the compile-time environment
* In parallel declarations (`let` ... `and`) add variables to compilation
  environment
* Refactor special type constructors (list, tuple, record)
* [<a href="https://github.com/julianhyde/morel/issues/4">SMLJ-4</a>]
  `let fun` inside `from` expression fails
* Move built-in constants and functions into new `enum BuiltIn`
* In `Shell`, fix parsing single-quote in line comments, and line endings in
  multi-line statements

### Build and tests

* Test expressions used in documentation and blog posts
* Example of a user-defined aggregate function in a query
* Add a test with a temporary function in a query that takes a record-valued
  argument
* In `ScriptTest`, only load `Dictionary` if script is `foreign.sml`
* Enable some `group` tests
* Add `Sys_env ()` function, that returns the current environment
* Upgrade maven: 3.5.4 &rarr; 3.6.3
* Add maven wrapper jar
* Use correct `maven-javadoc-plugin` version
* Before launching repl, build test as well as main
* Convert `MainTest` to use fluent style
* In `Shell`, add optional pause, which seems to make `ShellTest` deterministic

### Site and documentation

* Add [Morel language reference](docs/reference.md)
* Add image to [README](README.md)
* Add [javadoc to site](http://hydromatic.net/morel/apidocs/)
* Generate an asciinema demo

## <a href="https://github.com/julianhyde/morel/releases/tag/smlj-0.1">0.1</a> / 2019-07-24

Initial release features the core language (primitive types, lists,
tuples, records; `let`, `if`, `fn` and `case` expressions; `val`,
`fun` and `datatype` declarations), an interactive shell `smlj`, and
relational extensions (`from`).

