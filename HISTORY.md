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
<a href="https://github.com/hydromatic/morel/releases">GitHub</a>.

<!--
## <a href="https://github.com/hydromatic/morel/releases/tag/morel-0.x.0">0.x.0</a> / xxxx-xx-xx

Release 0.x.0 ...

Contributors:

### Features

### Bug-fixes and internal improvements

### Build and tests

### Component upgrades

### Site and documentation

* [[MOREL-xxx](https://github.com/hydromatic/morel/issues/xxx)]
  Release 0.x.0
-->

## <a href="https://github.com/hydromatic/morel/releases/tag/morel-0.7.0">0.7.0</a> / 2025-06-07

Release 0.7.0 is a huge release with major changes to query syntax and
semantics.

The largest change is queries over
[ordered and unordered collections](https://github.com/hydromatic/morel/issues/273).
For this, we introduced a
[`bag` type for unordered collections](https://github.com/hydromatic/morel/issues/235)
(complementing the existing `list` type), a new
[`unorder` step](https://github.com/hydromatic/morel/issues/277), and an
[`ordinal` expression](https://github.com/hydromatic/morel/issues/276).

These query changes required a type inference algorithm that can solve type
constraints, which in turn allows
[operator overloading](https://github.com/hydromatic/morel/issues/237) (with
new `over` and `inst` keywords).

Other changes to query syntax were
[set operators (`union`, `intersect`, `except`) as steps](https://github.com/hydromatic/morel/issues/253),
[atomic `yield` steps](https://github.com/hydromatic/morel/issues/262), the
[`current` keyword](https://github.com/hydromatic/morel/issues/265) for
referencing the current row, and
[simplified syntax for the `order` step](https://github.com/hydromatic/morel/issues/244).

We have implemented the
[`Char`](https://github.com/hydromatic/morel/issues/264) and
[`String`](https://github.com/hydromatic/morel/issues/279) structures as
defined by the
[Standard ML Basis Library](https://smlfamily.github.io/Basis/manpages.html).

The `scott` sample database now uses
[pluralized table names](https://github.com/hydromatic/morel/issues/255)
like `emps` instead of `EMP`.

Contributors:
Julian Hyde

### Features

* [[MOREL-244](https://github.com/hydromatic/morel/issues/244)]
  Simplify the syntax of the `order` step, and remove the `desc` keyword
* [[MOREL-282](https://github.com/hydromatic/morel/issues/282)]
  Add built-in datatype `Descending`, and method `Relational.compare`, for
  type-based orderings
* [[MOREL-279](https://github.com/hydromatic/morel/issues/279)]
  `String` structure
* [[MOREL-277](https://github.com/hydromatic/morel/issues/277)]
  `unorder` step
* [[MOREL-276](https://github.com/hydromatic/morel/issues/276)]
  `ordinal` expression
* [[MOREL-265](https://github.com/hydromatic/morel/issues/265)]
  Add `current` keyword, representing the current row in a query
* [[MOREL-273](https://github.com/hydromatic/morel/issues/273)]
  Ordered and unordered queries
* [[MOREL-237](https://github.com/hydromatic/morel/issues/237)]
  Operator overloading (`over` and `inst` keywords)
* [[MOREL-235](https://github.com/hydromatic/morel/issues/235)]
  Add a `bag` type, and `Bag` structure, to represent unordered collections
  with duplicates allowed
* [[MOREL-253](https://github.com/hydromatic/morel/issues/253)]
  Allow set operators (`union`, `intersect`, `except`) as steps in a pipeline
* [[MOREL-262](https://github.com/hydromatic/morel/issues/262)]
  Allow atomic `yield` steps at any point in the pipeline
* [[MOREL-255](https://github.com/hydromatic/morel/issues/255)]
  In the `scott` sample database, map the `EMP` table to `emps` (and pluralize
  other table names)
* [[MOREL-264](https://github.com/hydromatic/morel/issues/264)]
  `Char` structure

### Bug-fixes and internal improvements

* [[MOREL-287](https://github.com/hydromatic/morel/issues/287)]
  Degenerate joins
* [[MOREL-286](https://github.com/hydromatic/morel/issues/286)]
  Conversion of SQL `DATE`, `TIME`, `TIMESTAMP` values to Morel strings should
  not depend on locale
* Refactor: Add `enum BuiltIn.Constructor`
* Derived type is incorrect if `join` follows record `yield`
* In unifier, use working set rather than depth to prevent infinite recursion
* Tune method `TypeSystem.unqualified(Type)`
* In `Static`, add methods `filterEager`, `allMatch`, `anyMatch`, `noneMatch`
* Unifier should not overwrite previously resolved variables
* Refactor `TypeResolver`

### Build and tests

* Lint: Check version in `README` and `README.md`
* Lint: Ban smart quotes
* Refactor: Make tests for exceptions more concise
* [[MOREL-274](https://github.com/hydromatic/morel/issues/274)]
  Test scripts should report errors relative to first token after whitespace
  and comments
* Lint: Break up large method
* Lint: Check for `<code> ... </code>` spread over multiple lines
* When printing plans, handle list values better
* In `interface RecordLikeType`, add method `List<String> argNames()`
* Lint: Disallow string literals that are broken or contain newline

### Component upgrades

* Bump calcite from 1.39 to 1.40
* Bump checkstyle from 10.23.1 to 10.25.0
* Bump junit-jupiter from 5.12.2 to 5.13.1

### Site and documentation

* [[MOREL-284](https://github.com/hydromatic/morel/issues/284)]
  Release 0.7.0
* [[MOREL-269](https://github.com/hydromatic/morel/issues/269)]
  Decompose the documentation for built-in functions and structures
* Typos in query reference

## <a href="https://github.com/hydromatic/morel/releases/tag/morel-0.6.0">0.6.0</a> / 2024-05-02

Release 0.6.0 generalizes queries with
[universal and existential quantification](https://github.com/hydromatic/morel/issues/241)
(`forall` and `exists`) and adds a keyword for logical implication (`implies`).
The new `with` keyword allows
[functional update of record values](https://github.com/hydromatic/morel/issues/249).

Usability of the shell is improved by
[tabular mode](https://github.com/hydromatic/morel/issues/259),
and
[`showAll`](https://github.com/hydromatic/morel/issues/260)
and
[`clearEnv`](https://github.com/hydromatic/morel/issues/251)
functions.

In preparation for
[operator overloading](https://github.com/hydromatic/morel/issues/237)
we have
[tuned the performance of the unifier](https://github.com/hydromatic/morel/issues/246)
that powers Morel's type-inference.

Contributors:
Julian Hyde

### Features

* [[MOREL-259](https://github.com/hydromatic/morel/issues/259)]
  Tabular mode
* [[MOREL-249](https://github.com/hydromatic/morel/issues/249)]
  Add `with` operator (functional update notation for record values)
* [[MOREL-260](https://github.com/hydromatic/morel/issues/260)]
  Add function `Sys.showAll ()`
* [[MOREL-251](https://github.com/hydromatic/morel/issues/251)]
  Add function `Sys.clearEnv ()`
* [[MOREL-246](https://github.com/hydromatic/morel/issues/246)]
  Tune unifier
* [[MOREL-241](https://github.com/hydromatic/morel/issues/241)]
  Universal and existential quantification (`forall` and `exists`) and
  implication (`implies`)

### Bug-fixes and internal improvements

* [[MOREL-253](https://github.com/hydromatic/morel/issues/253)]
  Scalar `yield` after singleton record `yield` throws
  "Conversion to core did not preserve type"
* [[MOREL-258](https://github.com/hydromatic/morel/issues/258)]
  `skip` and `take` in query with unbounded variable give error
* Add method `PairList.viewOf(Map)`
* Allow `Sys` to be evaluated and printed in the shell
* Refactor `NameGenerator`
* [[MOREL-252](https://github.com/hydromatic/morel/issues/252)]
  When unparsing Morel, try to generate qualified identifiers less often
* Refactor: Define built-in datatypes in an enum
* Refactor: Omit declarations from type-unification
* In `PairList`, add a static `copyOf` method, and enable `set` and `remove`
  methods
* The `toString` methods of `RelList` and `Binding` should not print the
  contents of tables
* [[MOREL-250](https://github.com/hydromatic/morel/issues/250)]
  Inliner generates invalid plan if dead code occurs in a nested `let`
* [[MOREL-248](https://github.com/hydromatic/morel/issues/248)]
  Optimize method `Static.transformEager` for `List` and `Collection` arguments
* [[MOREL-247](https://github.com/hydromatic/morel/issues/247)]
  Cannot translate an expression that contains type annotations
* In `class Static`, add methods `last`, `skipLast`

### Build and tests

* Reduce CI timeout
* Checkstyle should require that braces around blocks in `case:` are at the
  start of the line
* [[MOREL-245](https://github.com/hydromatic/morel/issues/245)]
  Enable google-java-format

### Component upgrades

* Bump calcite from 1.38 to 1.39
* Bump checkstyle from 10.21.4 to 10.23.1
* Bump guava from 33.4.0-jre to 33.4.5-jre,
  and raise minimum from 21.0 to 23.1-jre
* Bump guava from 33.4.5-jre to 33.4.8-jre
* Bump jdk from 21 to 24;
  we still support all Java versions JDK 8 and higher
* Bump junit-jupiter from 5.12.0 to 5.12.2
* Bump maven from 3.8.4 to 3.9.9
* Bump maven-surefire-plugin from 3.5.2 to 3.5.3

### Site and documentation

* [[MOREL-261](https://github.com/hydromatic/morel/issues/261)]
  Release 0.6.0
* [Document query expressions](docs/query.md)
* In release notes, use the '0.x.0' format for releases

## <a href="https://github.com/hydromatic/morel/releases/tag/morel-0.5.0">0.5.0</a> / 2025-03-04

Release 0.5.0 extends the syntax of the `from` expression
([`distinct`](https://github.com/hydromatic/morel/issues/231),
[`into` and `through`](https://github.com/hydromatic/morel/issues/231) keywords,
[comma-separated scans](https://github.com/hydromatic/morel/issues/216),
and [unbounded variables](https://github.com/hydromatic/morel/issues/202)).
Morel now allows
[`fn` to have multiple branches, like `case`](https://github.com/hydromatic/morel/issues/230).
We have improved code generation for a singleton `case` expression,
and implemented the
[`Int` structure](https://github.com/hydromatic/morel/issues/228) as defined by the
[Standard ML Basis Library](https://smlfamily.github.io/Basis/integer.html#Int:STR:SPEC).

Contributors:
Julian Hyde

### Features

* [[MOREL-231](https://github.com/hydromatic/morel/issues/231)]
  Add `distinct` keyword as shorthand for `group` with all fields and no
  aggregate functions
* [[MOREL-230](https://github.com/hydromatic/morel/issues/230)]
  Allow lambda (`fn`) to have multiple branches, similar to `case`
* [[MOREL-228](https://github.com/hydromatic/morel/issues/228)]
  `Int` structure
* [[MOREL-171](https://github.com/hydromatic/morel/issues/171)]
  Add `into` and `through` clauses to `from` expression
* Add function `Interactive.useSilently`
* [[MOREL-216](https://github.com/hydromatic/morel/issues/216)]
  Allow comma-separated scans in `join`, and `on` in the `from` clause
* [[MOREL-202](https://github.com/hydromatic/morel/issues/202)]
  Allow unbounded variables (`from` and `join` without `in`), and remove
  `suchthat` keyword
* Inline singleton `case`
* [[MOREL-213](https://github.com/hydromatic/morel/issues/213)]
  Require that a non-terminal `yield` step is a record expression

### Bug-fixes and internal improvements

* Number type variables left-to-right
* Improve formatting of function types
* Validate field references
* Add `Core.Tuple.forEach`
* Add `Static.find` and `PairList.firstMatch`
* Add `RecordType.mutableMap`
* Make `file.smli` test less flaky

### Build and tests

* Add Australia, cakes tests from
  [MiniZinc tutorial](https://docs.minizinc.dev/en/stable/modelling.html)
* Enable tests for
  [[MOREL-43](https://github.com/hydromatic/morel/issues/43)]

### Component upgrades

* Bump calcite from 1.36 to 1.38
* Bump build-helper-maven-plugin from 3.5.0 to 3.6.0
* Bump checkstyle from 10.12.7 to 10.21.4
* Bump guava from 33.0.0-jre to 33.4.0-jre
* Bump hamcrest from 2.2 to 3.0
* Bump hsqldb from 2.7.2 to 2.7.4
* Bump junit.jupiter from 5.10.1 to 5.12.0
* Bump maven-checkstyle-plugin from 3.3.1 to 3.6.0
* Bump maven-compiler-plugin from 3.12.1 to 3.14.0
* Bump maven-enforcer-plugin from 3.4.1 to 3.5.0
* Bump maven-javadoc-plugin from 3.6.3 to 3.11.2
* Bump maven-project-info-reports-plugin from 3.5.0 to 3.9.0
* Bump maven-surefire-plugin from 3.2.3 to 3.5.2
* Bump versions-maven-plugin from 2.16.2 to 2.18.0

### Site and documentation

* Copy-edit documentation
* Add javadoc badge to README
* [[MOREL-243](https://github.com/hydromatic/morel/issues/243)]
  Release 0.5.0

## <a href="https://github.com/hydromatic/morel/releases/tag/morel-0.4.0">0.4.0</a> / 2024-01-04

Release 0.4.0 extends `from` syntax, adding
<a href="https://github.com/hydromatic/morel/issues/129">`suchthat`</a>,
<a href="https://github.com/hydromatic/morel/issues/204">`take` and `skip`</a>
clauses; allows identifiers to be
<a href="https://github.com/hydromatic/morel/issues/71">quoted using backticks</a>;
improves pretty-printing of
<a href="https://github.com/hydromatic/morel/issues/206">tuples</a> and
<a href="https://github.com/hydromatic/morel/issues/210">long lines</a>.

Contributors:
Julian Hyde,
Rette66

### Features

* [[MOREL-204](https://github.com/hydromatic/morel/issues/204)]
  Add `take` and `skip` relational operators
* [[MOREL-209](https://github.com/hydromatic/morel/issues/209)]
  File reader, and progressive types
* [[MOREL-210](https://github.com/hydromatic/morel/issues/210)]
  Fold long types when printing
* Rename `Option.flatten` to `Option.join`
* [[MOREL-71](https://github.com/hydromatic/morel/issues/71)]
  Allow identifiers to be quoted using backticks
* [[MOREL-206](https://github.com/hydromatic/morel/issues/206)]
  Indent tuples when printing
* [[MOREL-129](https://github.com/hydromatic/morel/issues/129)]
  Define relations via constrained iterations, and introduce a `suchthat`
  keyword to use them

### Bug-fixes and internal improvements

* Add `directory` property to `Session`
* Add type parameter to `Core.Literal.unwrap()` method
* [[MOREL-208](https://github.com/hydromatic/morel/issues/208)]
  `FromBuilder` should remove trivial `yield` step between two scan steps
* [[MOREL-205](https://github.com/hydromatic/morel/issues/205)]
  Pattern that uses nested type-constructors should not be considered
  redundant
* Add utility methods `Static.transform` and `transformEager`
* [[MOREL-203](https://github.com/hydromatic/morel/issues/203)]
  Cannot deduce type for `from b in [SOME true, NONE]`
* Refactor: In `Unifier.Variable`, store ordinal rather than computing it
* Refactor: Rename `Ord.forEach` to `forEachIndexed`
* In `CoreBuilder`, add `tuple(TypeSystem, Exp...)`, a more convenient
  `apply`
* Simplify `EnvShuttle`, `EnvVisitor` by adding `push` method
* Add `interface PairList` and `interface ImmutablePairList`
* Add method `Static.nextPowerOfTwo`
* [[MOREL-201](https://github.com/hydromatic/morel/issues/201)]
  `Real.signBit` gives different result on JDK 19/ARM

### Build and tests

* Run `script.sml` in tests
* [[MOREL-207](https://github.com/hydromatic/morel/issues/207)]
  Detect and fix flaky tests
* Disallow static star import
* [[MOREL-200](https://github.com/hydromatic/morel/issues/200)]
  In the test suite, run arbitrary "lint" checks on code
* [[MOREL-198](https://github.com/hydromatic/morel/issues/198)]
  Idempotent mode for test scripts
* Add a test for various closure and recursion scenarios
* Allow CI runs to be triggered manually, and monthly

### Component upgrades

* Bump build-helper-maven-plugin from 3.3.0 to 3.5.0
* Bump calcite from 1.32.0 to 1.36.0
* Bump checkstyle from 10.3.4 to 10.12.7
* Bump guava from 31.1-jre to 33.0.0-jre;
  increase minimum guava version from 19.0 to 21.0
* Bump hsqldb from 2.7.0 to 2.7.2
* Bump javacc from 7.0.12 to 7.0.13
* Bump jdk to 21; minimum jdk is still 8
* Bump jline from 3.21.0 to 3.25.0
* Bump junit-jupiter from 5.9.1 to 5.10.1
* Bump maven-checkstyle-plugin from 3.2.0 to 3.3.1
* Bump maven-compiler-plugin from 3.10.1 to 3.12.1
* Bump maven-enforcer-plugin from 3.1.0 to 3.4.1
* Bump maven-javadoc-plugin from 3.4.1 to 3.6.3
* Bump maven-project-info-reports-plugin from 3.4.1 to 3.5.0
* Bump maven-release-plugin from 2.4.2 to 3.0.1
* Bump maven-scm-provider-gitexe from 1.9.1 to 2.0.1
* Bump maven-site-plugin from 3.12.1 to 4.0.0-M13
* Bump maven-source-plugin from 3.2.1 to 3.3.0
* Bump maven-surefire-plugin from 2.22.2 to 3.2.3
* Bump slfj from 2.0.3 to 2.1.0-alpha1
* Add versions-maven-plugin version 2.16.2
* [[MOREL-199](https://github.com/hydromatic/morel/issues/199)]
  Support JDK 19 and 20

### Site and documentation

* [[MOREL-211](https://github.com/hydromatic/morel/issues/211)]
  Release 0.4.0
* Add Maven Central badge to `README.md`

## <a href="https://github.com/hydromatic/morel/releases/tag/morel-0.3.0">0.3.0</a> / 2022-10-02

Release 0.3.0 extends `from` syntax, adding an
<a href="https://github.com/hydromatic/morel/issues/20">`order` clause</a>,
allowing multiple
<a href="https://github.com/hydromatic/morel/issues/52">`yield`</a>,
<a href="https://github.com/hydromatic/morel/issues/21">`group` and `where`</a>
steps in any order,
<a href="https://github.com/hydromatic/morel/issues/11">variable `=` value</a>,
<a href="https://github.com/hydromatic/morel/issues/31">patterns in `in`</a>.
The compiler now detects
<a href="https://github.com/hydromatic/morel/issues/55">redundant and exhaustive matches</a>,
and supports
<a href="https://github.com/hydromatic/morel/issues/54">views and inlining</a>.
We add the
<a href="https://github.com/hydromatic/morel/issues/86">use</a> function
and standard structures
<a href="https://github.com/hydromatic/morel/issues/88">Math</a> and
<a href="https://github.com/hydromatic/morel/issues/102">Real</a>
to the standard library.
Integration with
<a href="https://github.com/hydromatic/morel/issues/40">Apache Calcite</a>
allows us to translate whole programs to relational algebra.

Contributors:
Gabriel Tejeda,
Gavin Ray,
Julian Hyde,
Sergey Nuyanzin

### Features

* [[MOREL-165](https://github.com/hydromatic/morel/issues/165)]
  Improve message when type resolution cannot deduce full record type
* [[MOREL-159](https://github.com/hydromatic/morel/issues/159)]
  `from` should not have a singleton record type unless it ends with a
  singleton record `yield`
* [[MOREL-147](https://github.com/hydromatic/morel/issues/147)]
  Allow Calcite schemas to contain sub-schemas
* [[MOREL-138](https://github.com/hydromatic/morel/issues/138)]
  Type annotations in patterns, function declarations and expressions
* [[MOREL-55](https://github.com/hydromatic/morel/issues/55)]
  Analyze match coverage, detecting redundant and exhaustive matches
* Satisfiability prover
* [[MOREL-118](https://github.com/hydromatic/morel/issues/118)]
  Report positions in error messages and exceptions
* [[MOREL-105](https://github.com/hydromatic/morel/issues/105)]
  Allow identifiers to contain prime characters (`'`)
* [[MOREL-70](https://github.com/hydromatic/morel/issues/70)]
  Polymorphic datatype
* [[MOREL-104](https://github.com/hydromatic/morel/issues/104)]
  Make functions top-level
* [[MOREL-103](https://github.com/hydromatic/morel/issues/103)]
  Layered patterns (`as`), and composite `val`
* [[MOREL-102](https://github.com/hydromatic/morel/issues/102)]
  `Real` structure
* Allow quick eager evaluation for functions/operators with more than one
  argument
* [[MOREL-96](https://github.com/hydromatic/morel/issues/96)]
  Print control
* [[MOREL-100](https://github.com/hydromatic/morel/issues/100)]
  Allow double-quote and backslash in string and char literals
* [[MOREL-88](https://github.com/hydromatic/morel/issues/88)]
  `Math` structure
* Floating point: `posInf`, `negInf`, `NaN`, and negative zero
* [[MOREL-94](https://github.com/hydromatic/morel/issues/94)]
  Wordle solver
* [[MOREL-95](https://github.com/hydromatic/morel/issues/95)]
  Mutually recursive functions
* Add `Relational.iterate`, which allows "recursive queries" such as
  transitive closure
* [[MOREL-69](https://github.com/hydromatic/morel/issues/69)]
  Add `compute` clause, for monoid comprehensions
* [[MOREL-86](https://github.com/hydromatic/morel/issues/86)]
  Add `use` function, to read and execute source from a file
* [[MOREL-72](https://github.com/hydromatic/morel/issues/72)]
  Join
* [[MOREL-11](https://github.com/hydromatic/morel/issues/11)]
  In `from` clause, allow 'variable = value'
* [[MOREL-62](https://github.com/hydromatic/morel/issues/62)]
  Add function `Relational.only`, which allows scalar sub-queries
* [[MOREL-65](https://github.com/hydromatic/morel/issues/65)]
  In the shell (REPL), use prompt '`-`' and continuation '`=`'
* [[MOREL-51](https://github.com/hydromatic/morel/issues/51)]
  Add `Relational.exists` function, and push it down to Calcite
* Add `morel.lang`, Morel language definition for the Linux 'highlight'
  command
* [[MOREL-60](https://github.com/hydromatic/morel/issues/60)]
  Push `elem`, `notelem` and `not ... elem` down to Calcite (as SQL `IN`
  and `NOT IN`)
* [[MOREL-52](https://github.com/hydromatic/morel/issues/52)]
  Allow multiple `yield` steps in `from`
* [[MOREL-45](https://github.com/hydromatic/morel/issues/45)]
  Translate `List.filter` as if user had written `where`;
  and `List.map` to `yield`
* [[MOREL-57](https://github.com/hydromatic/morel/issues/57)]
  `group` with empty key should emit one row
* [[MOREL-54](https://github.com/hydromatic/morel/issues/54)]
  Views (parameterized functions that return a relation) and inlining
* [[MOREL-53](https://github.com/hydromatic/morel/issues/53)]
  Optimize core language by inlining expressions
* [[MOREL-42](https://github.com/hydromatic/morel/issues/42)]
  Add a `morel` Calcite UDF, to allow hybrid plans with a mixture of relational
  and non-relational
* [[MOREL-40](https://github.com/hydromatic/morel/issues/40)]
  Translate to full Calcite algebra
* [[MOREL-46](https://github.com/hydromatic/morel/issues/46)]
  Core language
* [[MOREL-48](https://github.com/hydromatic/morel/issues/48)]
  Deduce types when a function is applied to a record selector
* [[MOREL-44](https://github.com/hydromatic/morel/issues/44)]
  Session variables
* [[MOREL-41](https://github.com/hydromatic/morel/issues/41)]
  Add `Sys.plan ()` function, to display plans from the shell
* [[MOREL-39](https://github.com/hydromatic/morel/issues/39)]
  Implement built-in `vector` and `order` data types
* [[MOREL-38](https://github.com/hydromatic/morel/issues/38)]
  Implement built-in `option` datatype and supporting functions
* [[MOREL-37](https://github.com/hydromatic/morel/issues/37)]
  Raise exceptions in built-in functions
* [[MOREL-25](https://github.com/hydromatic/morel/issues/25)]
  Add `o` (function composition) and `@` (list concatenation) operators
* [[MOREL-36](https://github.com/hydromatic/morel/issues/36)]
  In record pattern, make labels optional, and disallow `...` anywhere but end
* [[MOREL-34](https://github.com/hydromatic/morel/issues/34)]
  Functions in relations
* [[MOREL-27](https://github.com/hydromatic/morel/issues/27)]
  Create objects for built-in structures `List` and `String`, and allow
  `structure.name` references
* [[MOREL-33](https://github.com/hydromatic/morel/issues/33)]
  Add `elem`, `notelem` operators
* [[MOREL-30](https://github.com/hydromatic/morel/issues/30)]
  Add `union`, `intersect`, `except` operators
* [[MOREL-31](https://github.com/hydromatic/morel/issues/31)]
  In `from` expression, allow `in` to assign to pattern
* [[MOREL-29](https://github.com/hydromatic/morel/issues/29)]
  Overload `+` operator to allow both `int` and `real` arguments
* [[MOREL-24](https://github.com/hydromatic/morel/issues/24)]
  Use `=` rather than `as` for assigning column aliases in `group` and `compute`
* [[MOREL-23](https://github.com/hydromatic/morel/issues/23)]
  Default arguments for aggregate functions
* [[MOREL-17](https://github.com/hydromatic/morel/issues/17)]
  Allow `from` clause that defines 0 sources
* [[MOREL-20](https://github.com/hydromatic/morel/issues/20)]
  Add `order` clause
* In `compute` clause of `group`, apply `of` expression to each row
* [[MOREL-21](https://github.com/hydromatic/morel/issues/21)]
  In `from`, allow multiple `group` and `where` clauses in any order, optionally
  followed by `yield`

### Bug-fixes and internal improvements

* [[MOREL-179](https://github.com/hydromatic/morel/issues/179)]
  In compilation environment, use name + ordinal as the key, not just name, to
  accommodate variable copies caused by inlining
* Refactor: Move list methods into `Static`
* In `CoreBuilder`, add methods for frequently used operations: `equals`,
  `lessThan`, `only` etc.
* Allocate `bool` literals (`true` and `false`) once in `CoreBuilder`
* Keywords should be lower case
* [[MOREL-101](https://github.com/hydromatic/morel/issues/101)]
  Join variables are out of order in the rows sent to an aggregate function
* [[MOREL-99](https://github.com/hydromatic/morel/issues/99)]
  Script parser hangs if it encounters invalid syntax, and ignores comments at
  ends of files
* Refactor: `Array` to `List`, `List` to `Consumer`
* Refactor: move `Shell` state into new `interface Config`
* Tuning
* Rename `Option.join` to `flatten` (to make way for the `join` keyword)
* [[MOREL-73](https://github.com/hydromatic/morel/issues/73)]
  Keywords must be lower case
* [[MOREL-64](https://github.com/hydromatic/morel/issues/64)]
  `order` after `yield` gives 'unbound variable' error
* [[MOREL-67](https://github.com/hydromatic/morel/issues/67)]
  In `compute` clause, the key value should be available but is null
* During inlining, use '_' as delimiter for qualified variable names
* [[MOREL-59](https://github.com/hydromatic/morel/issues/59)]
  Converting an empty list to relational algebra throws
* Add `Core.Yield` step, and obsolete `Core.yieldExp`
* Rationalize order of Java 'import' statements
* Always inline atomic variables
* Add method `Static.shorterThan(Iterable, int)`
* [[MOREL-49](https://github.com/hydromatic/morel/issues/49)]
  Function argument `()` should have type `unit`, not 0-tuple
* [[MOREL-50](https://github.com/hydromatic/morel/issues/50)]
  Type resolver does not resolve all fields of record types
* Improve field names, e.g. rename `Exp e` to `Exp exp`
* Remove overrides of `toString()` method in `Environment` and `EvalEnv`
* Factor `Converter` out of `CalciteForeignValue`
* Refactor: rename `let` fields
* Add `enum Prop`, for strongly-typed properties
* [[MOREL-13](https://github.com/hydromatic/morel/issues/13)]
  Garbage-collect obscured variables
* AST that contains a call to `op +` is unparsed incorrectly
* Refactor `DataSet`
* `TypeVisitor` should visit parameter types inside `DataType`
* [[MOREL-35](https://github.com/hydromatic/morel/issues/35)]
  Record pattern in `from` mixes up fields if not in alphabetical order
* Deduce type of polymorphic field in tuple or record
* Add tracing to `Unifier`
* Move `class TypeResolver.TypeMap` to top-level
* Correct order of numeric labels in records, and allow 0 as a label
* A record with a field named "1" is a record, not a tuple
* During validation, replace `From.sources` if rewrites occur
* [[MOREL-28](https://github.com/hydromatic/morel/issues/28)]
  The `sum` aggregate function only works on `int` values
* [[MOREL-26](https://github.com/hydromatic/morel/issues/26)]
  Cannot parse `()` as pattern
* Morel shell should work even if Apache Maven is not installed
* [[MOREL-19](https://github.com/hydromatic/morel/issues/19)]
  `AssertionError: unknown FUN_DECL`
* [[MOREL-22](https://github.com/hydromatic/morel/issues/22)]
  Character literal should unparse as `#"a"`, not `"a"`

### Build and tests

* Bump `slf4j-api` from 2.0.2 to 2.0.3
* Bump `hsqldb` from 2.5.1 to 2.7.0
* Add `hsqldb-version` parameter to CI
* Add `interface Tracer`, so that tests can check for several events during the
  lifecycle
* Bump `git-commit-id-plugin` from 2.1.9 to 4.9.10
* Bump `checkstyle` from 7.8.2 to 10.3.4,
  `maven-checkstyle-plugin` from 3.0.0 to 3.1.2,
  `maven-source-plugin` from 2.2.1 to 3.2.1,
  `maven-compiler-plugin` from 2.3.2 to 3.10.1,
  `build-helper-maven-plugin` from 1.9 to 3.3.0
* Bump `calcite-core` from 1.29.0 to 1.32.0
* Make `ShellTest` more robust
* Turn off Travis CI
* Bump `hsqldb` from 2.3.1 to 2.5.1,
  `foodmart-data-hsqldb` from 0.4 to 0.5,
  `scott-data-hsqldb` from 0.1 to 0.2
* Bump `maven-project-info-reports-plugin` from 2.9 to 3.4.1
* Bump `maven-enforcer-plugin` from 3.0.0-M1 to 3.1.0
* Bump `maven-javadoc-plugin` from 3.0.1 to 3.4.1
* Bump `javacc-maven-plugin` from 3.0.0 to 3.0.3
* Test Guava versions 19.0 to 31.1-jre in CI
* Bump `guava` from 21.0 to 23.0
* Bump `jline` from 3.16.0 to 3.21.0
* Bump `maven-surefire-plugin` from 3.0.0-M3 to 3.0.0-M7
* Bump `jsr305` from 1.3.9 to 3.0.2
* Bump `slf4j-api` from 1.7.25 to 2.0.2
* Bump `junit-jupiter.version` from 5.7.2 to 5.9.1
* Bump `javacc` from 7.0.5 to 7.0.12
* Enable Dependabot
* Run CI on multiple Java versions, with Javadoc
* [[MOREL-107](https://github.com/hydromatic/morel/issues/107)]
  `ShellTest` is non-deterministic
* Add GitHub workflow to build and test
* Move project to 'hydromatic' GitHub organization
* Bump `maven` from 3.6.3 to 3.8.4
* Upgrade `calcite-core` to 1.29
* In parser tests, make sure there are no characters after the expression being
  parsed
* Refactor: Test fixture for `ShellTest`
* Refactor: Move `ScriptTest.Utils` to top-level `class TestUtils`
* Bump `jline` from 3.12.1 to 3.16.0, to give support for `xrvt` terminals
* Add tests for 'StrangeLoop 2021' talk
* Travis: quote variables, skip install
* Split `hybrid.sml` out of `foreign.sml`
* Docker login
* `LearningMatcher` makes it easier to ensure that two queries have the same
  plan or same results
* In tests, move implementations of `interface Matcher` into new utility
  `class Matchers`
* Bump `calcite-core` to 1.27
* Run tests in parallel
* [[MOREL-47](https://github.com/hydromatic/morel/issues/47)]
  Bump `junit` from 4.11 to 5.7.2,
  `hamcrest` from 1.3 to 2.2
* Add tests for blog post 'Aggregate queries in Morel'
* Add tests for blog post 'Word Count revisited'
* Bump `javacc` from 4.0 to 7.0.5,
  `maven-javacc-plugin` from 2.4 to 3.0.0

### Site and documentation

* [[MOREL-181](https://github.com/hydromatic/morel/issues/181)]
  Release 0.3.0
* Update operator list in `README`
* In reference, re-order the `String` and `Vector` built-in functions
* Add example of matching regular expressions using combinators
* Add missed brackets and semicolons in `README`
* Improve Morel picture on home page
* Add functions and types to Morel language reference
* Add build status to home page
* Another example of a recursive query: Floyd-Warshall algorithm for shortest
  path in a weighted graph
* Add examples of recursive queries and fixed-point algorithms
* Add Morel logo and square image

## <a href="https://github.com/hydromatic/morel/releases/tag/morel-0.2.0">0.2.0</a> / 2020-03-10

The first release since smlj was renamed to Morel includes major
improvements to the type system and relational extensions. Some highlights:
* Functions and values can have polymorphic types, inferred as part of a
  [Hindley-Milner type system](https://en.wikipedia.org/wiki/Hindley%E2%80%93Milner_type_system);
* Relational expressions may now include a `group` clause, so you can
  evaluate aggregate queries (similar to SQL `GROUP BY`);
* [Foreign values](https://github.com/hydromatic/morel/issues/5) allow external
  data, such as the contents of a JDBC database, to be handled as if it is in
  memory;
* Add built-in functions based on the
  [`String`](http://sml-family.org/Basis/string.html) and
  [`List`](http://sml-family.org/Basis/list.html) structures
  in the Standard ML basis library;
* [Postfix field reference syntax](https://github.com/hydromatic/morel/issues/9)
  makes Morel more familiar to SQL users;
* Add [Morel language reference](docs/reference.md).

### Features

* [[MOREL-15](https://github.com/hydromatic/morel/issues/15)]
  Improve pretty-printing: wrap long lines, and abbreviate long lists and deep
  structures
* [[MOREL-12](https://github.com/hydromatic/morel/issues/12)]
  In `from` clause, allow initializers to reference previous variables
* In `group`, `as alias` is optional
* [[MOREL-10](https://github.com/hydromatic/morel/issues/10)]
  Implicit labels in record expressions
* [[MOREL-9](https://github.com/hydromatic/morel/issues/9)]
  Allow `<expr>.<field>` as an alternative syntax for `#<field> <expr>`
* [[MOREL-7](https://github.com/hydromatic/morel/issues/7)]
  Rename project from 'smlj' to 'morel'
* [[MOREL-5](https://github.com/hydromatic/morel/issues/5)]
  Foreign values, including record values based on the contents of a JDBC schema
* [[MOREL-6](https://github.com/hydromatic/morel/issues/6)]
  Add `group` clause (and `compute` sub-clause) to `from` expression, to support
  aggregation and aggregate functions
* Polymorphic types
* Add `String` and `List` basis functions

### Bug-fixes and internal improvements

* [[MOREL-16](https://github.com/hydromatic/morel/issues/16)]
  Ensure that types derived for REPL expressions have no free type variables
* [[MOREL-14](https://github.com/hydromatic/morel/issues/14)]
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
* [[MOREL-4](https://github.com/hydromatic/morel/issues/4)]
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

## <a href="https://github.com/hydromatic/morel/releases/tag/smlj-0.1.0">0.1</a> / 2019-07-24

Initial release features the core language (primitive types, lists,
tuples, records; `let`, `if`, `fn` and `case` expressions; `val`,
`fun` and `datatype` declarations), an interactive shell `smlj`, and
relational extensions (`from`).
