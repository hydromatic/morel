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
<a href="https://github.com/hydromatic/morel/releases">github</a>.

## <a href="https://github.com/hydromatic/morel/releases/tag/morel-0.4">0.4</a> / 2024-01-04

Release 0.4 extends `from` syntax, adding
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

* [<a href="https://github.com/hydromatic/morel/issues/204">MOREL-204</a>]
  Add `take` and `skip` relational operators
* [<a href="https://github.com/hydromatic/morel/issues/209">MOREL-209</a>]
  File reader, and progressive types
* [<a href="https://github.com/hydromatic/morel/issues/210">MOREL-210</a>]
  Fold long types when printing
* Rename `Option.flatten` to `Option.join`
* [<a href="https://github.com/hydromatic/morel/issues/71">MOREL-71</a>]
  Allow identifiers to be quoted using backticks
* [<a href="https://github.com/hydromatic/morel/issues/206">MOREL-206</a>]
  Indent tuples when printing
* [<a href="https://github.com/hydromatic/morel/issues/129">MOREL-129</a>]
  Define relations via constrained iterations, and introduce a `suchthat`
  keyword to use them

### Bug-fixes and internal improvements

* Add `directory` property to `Session`
* Add type parameter to `Core.Literal.unwrap()` method
* [<a href="https://github.com/hydromatic/morel/issues/208">MOREL-208</a>]
  `FromBuilder` should remove trivial `yield` step between two scan steps
* [<a href="https://github.com/hydromatic/morel/issues/205">MOREL-205</a>]
  Pattern that uses nested type-constructors should not be considered
  redundant
* Add utility methods `Static.transform` and `transformEager`
* [<a href="https://github.com/hydromatic/morel/issues/203">MOREL-203</a>]
  Cannot deduce type for `from b in [SOME true, NONE]`
* Refactor: In `Unifier.Variable`, store ordinal rather than computing it
* Refactor: Rename `Ord.forEach` to `forEachIndexed`
* In `CoreBuilder`, add `tuple(TypeSystem, Exp...)`, a more convenient
  `apply`
* Simplify `EnvShuttle`, `EnvVisitor` by adding `push` method
* Add `interface PairList` and `interface ImmutablePairList`
* Add method `Static.nextPowerOfTwo`
* [<a href="https://github.com/hydromatic/morel/issues/201">MOREL-201</a>]
  `Real.signBit` gives different result on JDK 19/ARM

### Build and tests

* Run `script.sml` in tests
* [<a href="https://github.com/hydromatic/morel/issues/207">MOREL-207</a>]
  Detect and fix flaky tests
* Disallow static star import
* [<a href="https://github.com/hydromatic/morel/issues/200">MOREL-200</a>]
  In the test suite, run arbitrary "lint" checks on code
* [<a href="https://github.com/hydromatic/morel/issues/198">MOREL-198</a>]
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
* [<a href="https://github.com/hydromatic/morel/issues/199">MOREL-199</a>]
  Support JDK 19 and 20

### Site and documentation

* [<a href="https://github.com/hydromatic/morel/issues/211">MOREL-211</a>]
  Release 0.4
* Add Maven Central badge to `README.md`

## <a href="https://github.com/hydromatic/morel/releases/tag/morel-0.3">0.3</a> / 2022-10-02

Release 0.3 extends `from` syntax, adding an
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

* [<a href="https://github.com/hydromatic/morel/issues/165">MOREL-165</a>]
  Improve message when type resolution cannot deduce full record type
* [<a href="https://github.com/hydromatic/morel/issues/159">MOREL-159</a>]
  `from` should not have a singleton record type unless it ends with a
  singleton record `yield`
* [<a href="https://github.com/hydromatic/morel/issues/147">MOREL-147</a>]
  Allow Calcite schemas to contain sub-schemas
* [<a href="https://github.com/hydromatic/morel/issues/138">MOREL-138</a>]
  Type annotations in patterns, function declarations and expressions
* [<a href="https://github.com/hydromatic/morel/issues/55">MOREL-55</a>]
  Analyze match coverage, detecting redundant and exhaustive matches
* Satisfiability prover
* [<a href="https://github.com/hydromatic/morel/issues/118">MOREL-118</a>]
  Report positions in error messages and exceptions
* [<a href="https://github.com/hydromatic/morel/issues/105">MOREL-105</a>]
  Allow identifiers to contain prime characters (`'`)
* [<a href="https://github.com/hydromatic/morel/issues/70">MOREL-70</a>]
  Polymorphic datatype
* [<a href="https://github.com/hydromatic/morel/issues/104">MOREL-104</a>]
  Make functions top-level
* [<a href="https://github.com/hydromatic/morel/issues/103">MOREL-103</a>]
  Layered patterns (`as`), and composite `val`
* [<a href="https://github.com/hydromatic/morel/issues/102">MOREL-102</a>]
  `Real` structure
* Allow quick eager evaluation for functions/operators with more than one
  argument
* [<a href="https://github.com/hydromatic/morel/issues/96">MOREL-96</a>]
  Print control
* [<a href="https://github.com/hydromatic/morel/issues/100">MOREL-100</a>]
  Allow double-quote and backslash in string and char literals
* [<a href="https://github.com/hydromatic/morel/issues/88">MOREL-88</a>]
  `Math` structure
* Floating point: `posInf`, `negInf`, `NaN`, and negative zero
* [<a href="https://github.com/hydromatic/morel/issues/94">MOREL-94</a>]
  Wordle solver
* [<a href="https://github.com/hydromatic/morel/issues/95">MOREL-95</a>]
  Mutually recursive functions
* Add `Relational.iterate`, which allows "recursive queries" such as
  transitive closure
* [<a href="https://github.com/hydromatic/morel/issues/69">MOREL-69</a>]
  Add `compute` clause, for monoid comprehensions
* [<a href="https://github.com/hydromatic/morel/issues/86">MOREL-86</a>]
  Add `use` function, to read and execute source from a file
* [<a href="https://github.com/hydromatic/morel/issues/72">MOREL-72</a>]
  Join
* [<a href="https://github.com/hydromatic/morel/issues/11">MOREL-11</a>]
  In `from` clause, allow 'variable = value'
* [<a href="https://github.com/hydromatic/morel/issues/62">MOREL-62</a>]
  Add function `Relational.only`, which allows scalar sub-queries
* [<a href="https://github.com/hydromatic/morel/issues/65">MOREL-65</a>]
  In the shell (REPL), use prompt '`-`' and continuation '`=`'
* [<a href="https://github.com/hydromatic/morel/issues/51">MOREL-51</a>]
  Add `Relational.exists` function, and push it down to Calcite
* Add `morel.lang`, Morel language definition for the Linux 'highlight'
  command
* [<a href="https://github.com/hydromatic/morel/issues/60">MOREL-60</a>]
  Push `elem`, `notelem` and `not ... elem` down to Calcite (as SQL `IN`
  and `NOT IN`)
* [<a href="https://github.com/hydromatic/morel/issues/52">MOREL-52</a>]
  Allow multiple `yield` steps in `from`
* [<a href="https://github.com/hydromatic/morel/issues/45">MOREL-45</a>]
  Translate `List.filter` as if user had written `where`;
  and `List.map` to `yield`
* [<a href="https://github.com/hydromatic/morel/issues/57">MOREL-57</a>]
  `group` with empty key should emit one row
* [<a href="https://github.com/hydromatic/morel/issues/54">MOREL-54</a>]
  Views (parameterized functions that return a relation) and inlining
* [<a href="https://github.com/hydromatic/morel/issues/53">MOREL-53</a>]
  Optimize core language by inlining expressions
* [<a href="https://github.com/hydromatic/morel/issues/42">MOREL-42</a>]
  Add a `morel` Calcite UDF, to allow hybrid plans with a mixture of relational
  and non-relational
* [<a href="https://github.com/hydromatic/morel/issues/40">MOREL-40</a>]
  Translate to full Calcite algebra
* [<a href="https://github.com/hydromatic/morel/issues/46">MOREL-46</a>]
  Core language
* [<a href="https://github.com/hydromatic/morel/issues/48">MOREL-48</a>]
  Deduce types when a function is applied to a record selector
* [<a href="https://github.com/hydromatic/morel/issues/44">MOREL-44</a>]
  Session variables
* [<a href="https://github.com/hydromatic/morel/issues/41">MOREL-41</a>]
  Add `Sys.plan ()` function, to display plans from the shell
* [<a href="https://github.com/hydromatic/morel/issues/39">MOREL-39</a>]
  Implement built-in `vector` and `order` data types
* [<a href="https://github.com/hydromatic/morel/issues/38">MOREL-38</a>]
  Implement built-in `option` datatype and supporting functions
* [<a href="https://github.com/hydromatic/morel/issues/37">MOREL-37</a>]
  Raise exceptions in built-in functions
* [<a href="https://github.com/hydromatic/morel/issues/25">MOREL-25</a>]
  Add `o` (function composition) and `@` (list concatenation) operators
* [<a href="https://github.com/hydromatic/morel/issues/36">MOREL-36</a>]
  In record pattern, make labels optional, and disallow `...` anywhere but end
* [<a href="https://github.com/hydromatic/morel/issues/34">MOREL-34</a>]
  Functions in relations
* [<a href="https://github.com/hydromatic/morel/issues/27">MOREL-27</a>]
  Create objects for built-in structures `List` and `String`, and allow
  `structure.name` references
* [<a href="https://github.com/hydromatic/morel/issues/33">MOREL-33</a>]
  Add `elem`, `notelem` operators
* [<a href="https://github.com/hydromatic/morel/issues/30">MOREL-30</a>]
  Add `union`, `intersect`, `except` operators
* [<a href="https://github.com/hydromatic/morel/issues/31">MOREL-31</a>]
  In `from` expression, allow `in` to assign to pattern
* [<a href="https://github.com/hydromatic/morel/issues/29">MOREL-29</a>]
  Overload `+` operator to allow both `int` and `real` arguments
* [<a href="https://github.com/hydromatic/morel/issues/24">MOREL-24</a>]
  Use `=` rather than `as` for assigning column aliases in `group` and `compute`
* [<a href="https://github.com/hydromatic/morel/issues/23">MOREL-23</a>]
  Default arguments for aggregate functions
* [<a href="https://github.com/hydromatic/morel/issues/17">MOREL-17</a>]
  Allow `from` clause that defines 0 sources
* [<a href="https://github.com/hydromatic/morel/issues/20">MOREL-20</a>]
  Add `order` clause
* In `compute` clause of `group`, apply `of` expression to each row
* [<a href="https://github.com/hydromatic/morel/issues/21">MOREL-21</a>]
  In `from`, allow multiple `group` and `where` clauses in any order, optionally
  followed by `yield`

### Bug-fixes and internal improvements

* [<a href="https://github.com/hydromatic/morel/issues/179">MOREL-179</a>]
  In compilation environment, use name + ordinal as the key, not just name, to
  accommodate variable copies caused by inlining
* Refactor: Move list methods into `Static`
* In `CoreBuilder`, add methods for frequently used operations: `equals`,
  `lessThan`, `only` etc.
* Allocate `bool` literals (`true` and `false`) once in `CoreBuilder`
* Keywords should be lower case
* [<a href="https://github.com/hydromatic/morel/issues/101">MOREL-101</a>]
  Join variables are out of order in the rows sent to an aggregate function
* [<a href="https://github.com/hydromatic/morel/issues/99">MOREL-99</a>]
  Script parser hangs if it encounters invalid syntax, and ignores comments at
  ends of files
* Refactor: `Array` to `List`, `List` to `Consumer`
* Refactor: move `Shell` state into new `interface Config`
* Tuning
* Rename `Option.join` to `flatten` (to make way for the `join` keyword)
* [<a href="https://github.com/hydromatic/morel/issues/73">MOREL-73</a>]
  Keywords must be lower case
* [<a href="https://github.com/hydromatic/morel/issues/64">MOREL-64</a>]
  `order` after `yield` gives 'unbound variable' error
* [<a href="https://github.com/hydromatic/morel/issues/67">MOREL-67</a>]
  In `compute` clause, the key value should be available but is null
* During inlining, use '_' as delimiter for qualified variable names
* [<a href="https://github.com/hydromatic/morel/issues/59">MOREL-59</a>]
  Converting an empty list to relational algebra throws
* Add `Core.Yield` step, and obsolete `Core.yieldExp`
* Rationalize order of Java 'import' statements
* Always inline atomic variables
* Add method `Static.shorterThan(Iterable, int)`
* [<a href="https://github.com/hydromatic/morel/issues/49">MOREL-49</a>]
  Function argument `()` should have type `unit`, not 0-tuple
* [<a href="https://github.com/hydromatic/morel/issues/50">MOREL-50</a>]
  Type resolver does not resolve all fields of record types
* Improve field names, e.g. rename `Exp e` to `Exp exp`
* Remove overrides of `toString()` method in `Environment` and `EvalEnv`
* Factor `Converter` out of `CalciteForeignValue`
* Refactor: rename `let` fields
* Add `enum Prop`, for strongly-typed properties
* [<a href="https://github.com/hydromatic/morel/issues/13">MOREL-13</a>]
  Garbage-collect obscured variables
* AST that contains a call to `op +` is unparsed incorrectly
* Refactor `DataSet`
* `TypeVisitor` should visit parameter types inside `DataType`
* [<a href="https://github.com/hydromatic/morel/issues/35">MOREL-35</a>]
  Record pattern in `from` mixes up fields if not in alphabetical order
* Deduce type of polymorphic field in tuple or record
* Add tracing to `Unifier`
* Move `class TypeResolver.TypeMap` to top-level
* Correct order of numeric labels in records, and allow 0 as a label
* A record with a field named "1" is a record, not a tuple
* During validation, replace `From.sources` if rewrites occur
* [<a href="https://github.com/hydromatic/morel/issues/28">MOREL-28</a>]
  The `sum` aggregate function only works on `int` values
* [<a href="https://github.com/hydromatic/morel/issues/26">MOREL-26</a>]
  Cannot parse `()` as pattern
* Morel shell should work even if Apache Maven is not installed
* [<a href="https://github.com/hydromatic/morel/issues/19">MOREL-19</a>]
  `AssertionError: unknown FUN_DECL`
* [<a href="https://github.com/hydromatic/morel/issues/22">MOREL-22</a>]
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
* [<a href="https://github.com/hydromatic/morel/issues/107">MOREL-107</a>]
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
* [<a href="https://github.com/hydromatic/morel/issues/47">MOREL-47</a>]
  Bump `junit` from 4.11 to 5.7.2,
  `hamcrest` from 1.3 to 2.2
* Add tests for blog post 'Aggregate queries in Morel'
* Add tests for blog post 'Word Count revisited'
* Bump `javacc` from 4.0 to 7.0.5,
  `maven-javacc-plugin` from 2.4 to 3.0.0

### Site and documentation

* [<a href="https://github.com/hydromatic/morel/issues/181">MOREL-181</a>]
  Release 0.3
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

## <a href="https://github.com/hydromatic/morel/releases/tag/morel-0.2">0.2</a> / 2020-03-10

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

* [<a href="https://github.com/hydromatic/morel/issues/15">MOREL-15</a>]
  Improve pretty-printing: wrap long lines, and abbreviate long lists and deep
  structures
* [<a href="https://github.com/hydromatic/morel/issues/12">MOREL-12</a>]
  In `from` clause, allow initializers to reference previous variables
* In `group`, `as alias` is optional
* [<a href="https://github.com/hydromatic/morel/issues/10">MOREL-10</a>]
  Implicit labels in record expressions
* [<a href="https://github.com/hydromatic/morel/issues/9">MOREL-9</a>]
  Allow `<expr>.<field>` as an alternative syntax for `#<field> <expr>`
* [<a href="https://github.com/hydromatic/morel/issues/7">MOREL-7</a>]
  Rename project from 'smlj' to 'morel'
* [<a href="https://github.com/hydromatic/morel/issues/5">SMLJ-5</a>]
  Foreign values, including record values based on the contents of a JDBC schema
* [<a href="https://github.com/hydromatic/morel/issues/6">SMLJ-6</a>]
  Add `group` clause (and `compute` sub-clause) to `from` expression, to support
  aggregation and aggregate functions
* Polymorphic types
* Add `String` and `List` basis functions

### Bug-fixes and internal improvements

* [<a href="https://github.com/hydromatic/morel/issues/16">MOREL-16</a>]
  Ensure that types derived for REPL expressions have no free type variables
* [<a href="https://github.com/hydromatic/morel/issues/14">MOREL-14</a>]
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
* [<a href="https://github.com/hydromatic/morel/issues/4">SMLJ-4</a>]
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

## <a href="https://github.com/hydromatic/morel/releases/tag/smlj-0.1">0.1</a> / 2019-07-24

Initial release features the core language (primitive types, lists,
tuples, records; `let`, `if`, `fn` and `case` expressions; `val`,
`fun` and `datatype` declarations), an interactive shell `smlj`, and
relational extensions (`from`).
