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
## <a id="0.x.0" href="https://github.com/hydromatic/morel/releases/tag/morel-0.x.0">0.x.0</a> / xxxx-xx-xx

Release 0.x.0 ...

Breaking changes:

Contributors:

### Features

### Bug-fixes and internal improvements

### Build and tests

### Component upgrades

### Site and documentation

* Release 0.x.0 (#xxx)

-->

## <a id="0.9.0" href="https://github.com/hydromatic/morel/releases/tag/morel-0.9.0">0.9.0</a> / 2026-08-13

Release 0.9.0 is a large release that adds a Datalog sub-language, a
constraint solver that can evaluate queries over unbounded variables,
several standard library structures, and a much improved shell.

Morel now speaks
[Datalog](https://github.com/hydromatic/morel/issues/323), with
stratified negation and semi-naive evaluation. `Datalog.execute`
translates a Datalog program into Morel and runs it on the usual engine;
the relations it computes are returned as ordinary Morel values, so a
`from` query can consume them. See the
[Datalog reference](docs/datalog.md).

Queries no longer need to say where their rows come from. Given
constraints on
[unbounded variables](https://github.com/hydromatic/morel/issues/217),
Morel inverts the predicates to deduce a set of rows to scan, tightening
the deduced bounds using
[feasibility-based bound tightening](https://github.com/hydromatic/morel/issues/373)
and, where that is not enough, a
[SAT solver](https://github.com/hydromatic/morel/issues/367).
An unbounded scan may now have
[an arbitrary pattern](https://github.com/hydromatic/morel/issues/440),
including a type annotation, and yields each satisfying assignment
[once, in the order of its type](https://github.com/hydromatic/morel/issues/443).
Related work adds
[outer joins](https://github.com/hydromatic/morel/issues/75) and
[dependent joins](https://github.com/hydromatic/morel/issues/275).

Query syntax gains
[`yieldAll`](https://github.com/hydromatic/morel/issues/257) (a flatMap
step), the
[safe navigation operator `?.`](https://github.com/hydromatic/morel/issues/378),
[postfix method calls](https://github.com/hydromatic/morel/issues/346)
such as `s.size ()`, the
[record modifiers](https://github.com/hydromatic/morel/issues/432)
`extend`, `remove`, `rename` and `replace`, and
[dot syntax for tuple fields](https://github.com/hydromatic/morel/issues/332).
A `yield`, `yieldAll` or `group` step can also
[name its output with a single variable](https://github.com/hydromatic/morel/issues/387),
as in `yield v = e`.

The built-in library adds the
[`Date`](https://github.com/hydromatic/morel/issues/278),
[`Range`](https://github.com/hydromatic/morel/issues/338),
[`StringCvt`](https://github.com/hydromatic/morel/issues/371),
[`Time`](https://github.com/hydromatic/morel/issues/351),
[`Variant`](https://github.com/hydromatic/morel/issues/324) and
[`Word`](https://github.com/hydromatic/morel/issues/396)
structures, and the top-level environment is
[aligned with Standard ML](https://github.com/hydromatic/morel/issues/395).

The shell has
[syntax highlighting](https://github.com/hydromatic/morel/issues/413),
[command history](https://github.com/hydromatic/morel/issues/414), and
an [`-e` flag](https://github.com/hydromatic/morel/issues/333) to
evaluate a single command. Values are printed by a new
[`PP` pretty-printer](https://github.com/hydromatic/morel/issues/398),
and tabular mode renders
[nested collections](https://github.com/hydromatic/morel/issues/376),
[`option` values](https://github.com/hydromatic/morel/issues/382) and
[enum values](https://github.com/hydromatic/morel/issues/441).

Internally, evaluation moved from a chain of environments to
[a stack](https://github.com/hydromatic/morel/issues/349), and
[tail-call optimization](https://github.com/hydromatic/morel/issues/151)
means that recursion depth is no longer bounded by the Java stack; that
in turn allows
[N Queens](https://github.com/hydromatic/morel/issues/148) to be solved
for arbitrarily large N.

Breaking changes:
* The `with` record modifier is now called `replace`
  ([#432](https://github.com/hydromatic/morel/issues/432))
* A `yield`, `yieldAll` or `group` step of the form `v = e` now binds
  `v`, rather than testing whether `v` equals `e`; write `yield (v = e)`
  for the equality test
  ([#387](https://github.com/hydromatic/morel/issues/387))
* An unbounded scan has type `list` rather than `bag`, yields its values
  in the natural order of their type, and yields each value once;
  previously the order depended on which generator the optimizer chose,
  and duplicates were possible. A bounded scan is unaffected.
  ([#443](https://github.com/hydromatic/morel/issues/443))
* `ordinal` in a join's `on` condition is now the ordinal of the
  candidate pair of rows, that is, the number of times the `on`
  condition has been evaluated; previously it was the ordinal of the
  left-hand row
  ([#435](https://github.com/hydromatic/morel/issues/435))

Contributors:
Guy Freeman,
Hellblazer,
Julian Hyde

### Features

* Unbounded scans should be ordered and distinct
  ([#443](https://github.com/hydromatic/morel/issues/443))
* Allow unbounded scan to have arbitrary pattern, including type annotation
  (`from b: bool`) ([#440](https://github.com/hydromatic/morel/issues/440))
* `StringCvt` structure, and `fmt`, `scan`, `toString` and `fromString`
  functions in various structures
  ([#371](https://github.com/hydromatic/morel/issues/371))
* Extend tabular mode to render enum values as scalars
  ([#441](https://github.com/hydromatic/morel/issues/441))
* Add `extend`, `remove` and `rename` record modifiers, and replace `with`
  with `replace` ([#432](https://github.com/hydromatic/morel/issues/432))
* Qualified types for overloaded identifiers
  ([#426](https://github.com/hydromatic/morel/issues/426))
* Let-polymorphism: generalize values bound in a local `let`
  ([#427](https://github.com/hydromatic/morel/issues/427))
* Syntax highlighting in the shell
  ([#413](https://github.com/hydromatic/morel/issues/413))
* Shell command history
  ([#414](https://github.com/hydromatic/morel/issues/414))
* Syntax that allows `yield`, `yieldAll` and `group` to produce a single
  "binder" variable ([#387](https://github.com/hydromatic/morel/issues/387))
* Add `type_string` operator
  ([#406](https://github.com/hydromatic/morel/issues/406))
* Add `PP` structure (pretty-printer), use it to print values, and
  pretty-print lists and types compactly, like SML/NJ
  ([#398](https://github.com/hydromatic/morel/issues/398),
  [#339](https://github.com/hydromatic/morel/issues/339))
* Add the `word` type and `Word` structure
  ([#396](https://github.com/hydromatic/morel/issues/396))
* Align the top-level environment with Standard ML
  ([#395](https://github.com/hydromatic/morel/issues/395))
* Ground an unbounded variable bounded by `elem` over a range
* Disallow unbound type variables in `type` and `datatype` declarations
  ([#356](https://github.com/hydromatic/morel/issues/356))
* Evaluation of dependent joins
  ([#275](https://github.com/hydromatic/morel/issues/275))
* Make the built-in structures consistent in Java and Rust implementations
  ([#385](https://github.com/hydromatic/morel/issues/385))
* Outer joins ([#75](https://github.com/hydromatic/morel/issues/75))
* Add safe navigation operator `?.`
  ([#378](https://github.com/hydromatic/morel/issues/378))
* Extend tabular mode to render `option` values
  ([#382](https://github.com/hydromatic/morel/issues/382))
* Add `yieldAll` step, a flatMap for `from` expressions
  ([#257](https://github.com/hydromatic/morel/issues/257))
* Extend tabular mode to fold strings, and to display nested collections,
  nested records and record options
  ([#376](https://github.com/hydromatic/morel/issues/376))
* Use feasibility-based bound tightening (FBBT) to deduce and strengthen
  variable bounds ([#373](https://github.com/hydromatic/morel/issues/373))
* Extend list constructor to allow ranges, e.g. `where i elem [0..^10, 20,
  100..]` ([#372](https://github.com/hydromatic/morel/issues/372))
* Add attributes and doc comments
  ([#369](https://github.com/hydromatic/morel/issues/369))
* Add `Sys.parseTree` built-in function for AST inspection
* Include source position in interactive compile-error messages
* Add `raise` command ([#364](https://github.com/hydromatic/morel/issues/364))
* Display whole `real` values without trailing `.0`, in both classic and
  tabular output
  ([#358](https://github.com/hydromatic/morel/issues/358))
* `Range` structure ([#338](https://github.com/hydromatic/morel/issues/338))
* Add Darn notebook kernel and MorelHighlighter
  ([#345](https://github.com/hydromatic/morel/issues/345))
* `Date` structure ([#278](https://github.com/hydromatic/morel/issues/278))
* `Time` structure ([#351](https://github.com/hydromatic/morel/issues/351))
* Add `now` and `timeZone` properties for deterministic date/time behavior
  ([#352](https://github.com/hydromatic/morel/issues/352))
* Implement tail-call optimization via trampolining
  ([#151](https://github.com/hydromatic/morel/issues/151))
* Add postfix method-call syntax `x.f arg` and `x.f (a,b).g (c)`
  ([#346](https://github.com/hydromatic/morel/issues/346))
* Invert `case` expressions with multiple arms
  ([#341](https://github.com/hydromatic/morel/issues/341))
* Aggregate functions should adapt to the collection type of the input
  ([#271](https://github.com/hydromatic/morel/issues/271))
* Exclude the `Test` structure from the environment, controlled by a new
  `excludeStructures` property
  ([#342](https://github.com/hydromatic/morel/issues/342))
* Datalog ([#323](https://github.com/hydromatic/morel/issues/323))
* Inline functions (and other expressions) that are not in the same compile
  unit ([#223](https://github.com/hydromatic/morel/issues/223))
* Implement queries with unbounded variables by inverting predicates
  ([#217](https://github.com/hydromatic/morel/issues/217))
* The built-in `abs` function should be overloaded, and can apply to both
  `int` and `real` ([#318](https://github.com/hydromatic/morel/issues/318))
* Add `-e`/`--eval` option to the `morel` script, to execute a single command
  ([#333](https://github.com/hydromatic/morel/issues/333))
* Access tuple fields using dot syntax, e.g. `tuple.1`
  ([#332](https://github.com/hydromatic/morel/issues/332))
* Allow nested block comments
  ([#306](https://github.com/hydromatic/morel/issues/306))
* Add `variant` datatype and `Variant` structure
  ([#324](https://github.com/hydromatic/morel/issues/324))

### Bug-fixes and internal improvements

* Inlining a subquery that ends with `yield` builds an invalid query
  ([#444](https://github.com/hydromatic/morel/issues/444))
* Type annotation containing `typeof` throws `AssertionError`
  ([#445](https://github.com/hydromatic/morel/issues/445))
* Shell loses input when a line holds a comment or more than one statement
  ([#439](https://github.com/hydromatic/morel/issues/439))
* Shell highlighter should color a keyword inside backticks as an identifier
  ([#437](https://github.com/hydromatic/morel/issues/437))
* Source span of a function application omits the parentheses around a
  grouped argument ([#422](https://github.com/hydromatic/morel/issues/422))
* `ordinal` in a join's `on` condition should be the ordinal of the candidate
  pair ([#435](https://github.com/hydromatic/morel/issues/435))
* Change implementation of `ordinal` from a slot to a row field
  ([#434](https://github.com/hydromatic/morel/issues/434))
* Backswing from morel-go
  ([#428](https://github.com/hydromatic/morel/issues/428))
* Redefining a type name with `type` or `datatype` breaks the new type and
  values of the old one ([#429](https://github.com/hydromatic/morel/issues/429))
* Row binder gives wrong result or crashes when the binder name equals the
  record's only field name
  ([#416](https://github.com/hydromatic/morel/issues/416))
* Don't assume that `NaN` is positive
  ([#425](https://github.com/hydromatic/morel/issues/425))
* Unify `list` and `bag` in type resolution via an orderedness atom
  ([#407](https://github.com/hydromatic/morel/issues/407))
* `Real.floor`, `Real.ceil`, and `Real.round` give wrong results
  ([#423](https://github.com/hydromatic/morel/issues/423))
* `max` and `min` give wrong answers or crash for `word`, `real`, and
  composite typed arguments
  ([#421](https://github.com/hydromatic/morel/issues/421))
* `max` and `min` over an empty collection should raise `Empty`
  ([#419](https://github.com/hydromatic/morel/issues/419))
* Character constant that is not exactly one character crashes the shell
  ([#420](https://github.com/hydromatic/morel/issues/420))
* Shell highlighter crashes when typing a string escape
  ([#415](https://github.com/hydromatic/morel/issues/415))
* Simplify `MatchCode` in `Compiler`, and other cleanups
* Collections of collections crash or corrupt values on the Calcite path
  ([#410](https://github.com/hydromatic/morel/issues/410))
* Direct calls to `List.concat`, `except` and `intersect` crash or give wrong
  answers on the Calcite path
  ([#408](https://github.com/hydromatic/morel/issues/408))
* Multi-operand `except`/`intersect` drop operands beyond the second on the
  Calcite interpreter path
  ([#402](https://github.com/hydromatic/morel/issues/402))
* Incorrect results for `except` and `intersect` queries pushed to Calcite
  ([#391](https://github.com/hydromatic/morel/issues/391))
* Set operations (`except`, `intersect`, `union`) crash after `distinct` and
  over records
* `group` and `distinct` should preserve arrival order
* `through` over a rewritten function throws `NullPointerException`
* `elements` used outside `compute` crashes the compiler
* Launcher should not collapse multiple file arguments
  ([#392](https://github.com/hydromatic/morel/issues/392))
* Lexical error should not crash the shell
  ([#383](https://github.com/hydromatic/morel/issues/383))
* Unparser should quote reserved-word identifiers (e.g. `left`, `o`,
  `ordinal`)
* Report a source position for more type errors, not `0.0-0.0`
  ([#380](https://github.com/hydromatic/morel/issues/380))
* Invalid inlining of a correlated subquery
* `Fn.repeat` with a negative count should raise `Domain` immediately
  ([#354](https://github.com/hydromatic/morel/issues/354))
* You're going to need a bigger SAT Solver
  ([#367](https://github.com/hydromatic/morel/issues/367))
* Compute correct results for cousin-style transitive closure queries
* Reject `(t1, ..., tn)` as a stand-alone tuple type
  ([#360](https://github.com/hydromatic/morel/issues/360))
* Composite value declarations should not assign `it`
  ([#355](https://github.com/hydromatic/morel/issues/355))
* Constructor values should pretty-print their payloads according to their
  type
* Merge multiple range constraints into a single call to
  `Range.discreteSetOf`
  ([#338](https://github.com/hydromatic/morel/issues/338))
* Migrate evaluation from `EvalEnv` chain to `Stack`
  ([#349](https://github.com/hydromatic/morel/issues/349))
* Predicate inversion should filter by outer-scope variables
  ([#347](https://github.com/hydromatic/morel/issues/347))
* Share type variable scope within declarations so that 'a in annotations
  refers to the same type
  ([#343](https://github.com/hydromatic/morel/issues/343))
* Refactor predicate inversion logic
* Remove `@Nullable` from the type parameter bounds of `class Pair`
* Precedence of list constructor is wrong
  ([#293](https://github.com/hydromatic/morel/issues/293))
* Add method `Ord.allMatchIndexed`
* Add method `Static.transformToMap`
* Change signature of method `CoreBuilder.recordPat`
* In a zero-field relation, `distinct` should give different result to `group
  {}` ([#328](https://github.com/hydromatic/morel/issues/328))
* Disallow '0' and integer literals starting with '0' as record labels
* Inline `case x` when `x` is constant
  ([#330](https://github.com/hydromatic/morel/issues/330))
* Add method `Type.elementType()`
* Refactor type constructor lookup
* In `PairList`, add methods `asSortedMap` and `withSortedKeys`
  ([#326](https://github.com/hydromatic/morel/issues/326))

### Build and tests

* Lint should ensure that block comment continuation lines have a '*' prefix
  ([#442](https://github.com/hydromatic/morel/issues/442))
* Script test harness discards the output of a statement that has no expected
  output ([#438](https://github.com/hydromatic/morel/issues/438))
* `ShellTest` fails intermittently, and the shell writes its input twice
* Add a test, `dual.smli`, that runs each query locally and in Calcite
  ([#412](https://github.com/hydromatic/morel/issues/412))
* Add `git-commit-id.skip` property to disable `git-commit-id` plugin
* Add `built-in/datalog.smli`, and check that every structure has a test
  script
* Add a `matchStrict` property, to enable strict output matching, and move
  pretty-printing tests into `pretty.smli`
  ([#398](https://github.com/hydromatic/morel/issues/398))
* Long method-call chain causes javac StackOverflowError
* Lint: Add rule to encourage converting consecutive line comments `(*)` into
  block comments `(*` ... `*)`
  ([#399](https://github.com/hydromatic/morel/issues/399))
* Add lint rule that a class has at most one primary constructor
  ([#366](https://github.com/hydromatic/morel/issues/366))
* Split `built-in.smli` into one file per structure
  ([#361](https://github.com/hydromatic/morel/issues/361))
* Add graph algorithm examples based on "EmptyHeaded" paper
  ([#233](https://github.com/hydromatic/morel/issues/233))
* Run longest-running script tests first to minimize total parallel duration
* Solve the "N Queens" problem
  ([#148](https://github.com/hydromatic/morel/issues/148))
* Linter should include target line number in sort-violation messages
  (continues [#316](https://github.com/hydromatic/morel/issues/316))
* Test suite hangs intermittently due to JDBC connection pool exhaustion and
  cyclic wait for connections
  ([#340](https://github.com/hydromatic/morel/issues/340))
* Linter should cover Markdown files
* Make test scripts resilient to changes in the order of `bag` values, and
  fail to match if types are different
  ([#334](https://github.com/hydromatic/morel/issues/334))
* Linter should police Morel block comments
  ([#335](https://github.com/hydromatic/morel/issues/335))
* Linter should prevent fully-qualified class names in Java code
  ([#337](https://github.com/hydromatic/morel/issues/337))
* Add function `Sys.planEx phase`, to print extended plans for testing
  ([#329](https://github.com/hydromatic/morel/issues/329))
* Add `variant.smli`, a test for the `variant` datatype
  ([#324](https://github.com/hydromatic/morel/issues/324))
* Script framework incorrectly strips output lines inside block comments
  ([#306](https://github.com/hydromatic/morel/issues/306))
* Allow running GitHub action on a specified commit

### Component upgrades

* Remove unused dependency `jackson-dataformat-toml` (its last use went away
  when documentation metadata moved to `.sig` files)
* Bump calcite from 1.41.0 to 1.42.0
* Bump central-publishing-maven-plugin from 0.8.0 to 0.11.0
* Bump checkstyle from 12.1.2 to 13.10.0
* Bump guava from 33.5.0-jre to 33.6.0-jre
* Bump javacc-maven-plugin from 3.0.3 to 3.8.0
* Bump jspecify from 1.0.0 to 1.0.1
* Bump maven-compiler-plugin from 3.14.1 to 3.15.0
* Bump maven-enforcer-plugin from 3.6.2 to 3.6.3
* Bump versions-maven-plugin from 2.19.1 to 2.21.0

### Site and documentation

* Move documentation metadata from `functions.toml` to `.sig` files
  ([#368](https://github.com/hydromatic/morel/issues/368))
* Maven Central badge in README is broken
* Split documentation of the built-in functions into a page per structure,
  and document methods (built-in functions that allow postfix calls)
  ([#348](https://github.com/hydromatic/morel/issues/348))
* Generate a table of all built-in properties
* Documentation for `Relational.iterate`
* Release 0.9.0 ([#446](https://github.com/hydromatic/morel/issues/446))

## <a id="0.8.0" href="https://github.com/hydromatic/morel/releases/tag/morel-0.8.0">0.8.0</a> / 2025-11-23

Release 0.8.0 has improvements to aggregate query syntax, the type
system, and the standard library.

The syntax of aggregate queries is
[now more powerful](https://github.com/hydromatic/morel/issues/288).
You can now compute expressions before and after aggregation, for
example `2.0 * avg over (units * unitPrice)`. The `elements`
collection lets you access the
[raw elements of a group](https://github.com/hydromatic/morel/issues/304)
and even write subqueries in the `compute` clause.

The type system includes
[type aliases](https://github.com/hydromatic/morel/issues/285)
via the `type` keyword. The `typeof` operator lets you
[extract an expression's type](https://github.com/hydromatic/morel/issues/291).

The built-in library adds the
[`Either`](https://github.com/hydromatic/morel/issues/302),
[`Fn`](https://github.com/hydromatic/morel/issues/301), and
[`ListPair`](https://github.com/hydromatic/morel/issues/295)
structures.

Breaking changes:
* In an aggregate query, the `of` keyword is replaced by `over`, and
  composite keys and compute expressions must be records, written with
  the usual `{` ... `}` syntax
  ([#288](https://github.com/hydromatic/morel/issues/288))

Contributors:
Julian Hyde

### Features

* Don't allow `on` after singleton scan (denoted by `=`)
  ([#317](https://github.com/hydromatic/morel/issues/317))
* Add properties "productName", "productVersion", "banner"
  ([#319](https://github.com/hydromatic/morel/issues/319))
* Add signatures for standard library, and verify that built-in types match
* Parse `signature`
  ([#315](https://github.com/hydromatic/morel/issues/315))
* Support `op` keyword (operator sections)
  ([#311](https://github.com/hydromatic/morel/issues/311))
* Add `elements` collection, available in `compute` clause, to enable advanced
  aggregation
  ([#304](https://github.com/hydromatic/morel/issues/304))
* `Either` structure
  ([#302](https://github.com/hydromatic/morel/issues/302))
* `Fn` structure
  ([#301](https://github.com/hydromatic/morel/issues/301))
* Improve syntax of `group` and `compute` steps
  ([#288](https://github.com/hydromatic/morel/issues/288))
* Add `typeof` operator, to extract an expression's type
  ([#291](https://github.com/hydromatic/morel/issues/291))
* Type abbreviations, also known as alias types, declared using the `type`
  keyword
  ([#285](https://github.com/hydromatic/morel/issues/285))
* Parse exceptions should indicate the position in the source code where the
  exception occurred
  ([#297](https://github.com/hydromatic/morel/issues/297))
* `ListPair` structure
  ([#295](https://github.com/hydromatic/morel/issues/295))
* Quoted type names
  ([#289](https://github.com/hydromatic/morel/issues/289))

### Bug-fixes and internal improvements

* The `intersect` and `except` steps should count, and preserve order
  ([#321](https://github.com/hydromatic/morel/issues/321))
* Various refactorings and minor bug-fixes to match Rust
  ([#308](https://github.com/hydromatic/morel/issues/308))
* Refactor: Normalize order of several `enum` types
* When encoding record types in unifier, quote field names that contain ':'
* Add directive `lint:sort until` to ensure that code regions are sorted
  ([#316](https://github.com/hydromatic/morel/issues/316))
* Refactor: In `class StepEnv` add field `ordered`
* `Ast.ref` should translate the `List.map` function to `#map List`, not to
  `map List`
* The "cannot derive label for compute expression" error currently causes a
  crash
  ([#305](https://github.com/hydromatic/morel/issues/305))
* In `class Pair`, add `List` variants of methods `allMatch`, `anyMatch`,
  `noneMatch`, `firstMatch`, `forEach`
* In `interface PairList`, add method `fromTransformed`
* Refactor: Move `interface RowSink` out of `class Codes` to top-level
* Lint: Sort constants in `class Codes`
* Refactor: Make `ImmutablePairList.copyOf` more tolerant
* In `class PairList`, add methods `subList`, `first`, `skipFirst`
* Implementations of built-in functions should be in n-ary form, with a helper
  to curry them
  ([#298](https://github.com/hydromatic/morel/issues/298))
* Add method `ImmutablePairList.fromTransformed`
* Refactor: Add `copy` methods for various AST nodes
* Assert that in an assignment `val p = e`, pattern and expression
  have same type
* Environment for an `EnvVisitor` should be the output from the previous step
* Refactor: Change type of `Ast.Record.args` from `SortedMap` to `PairList`
* Add method `PairList.toImmutableSortedMap`
* Refactor: When printing the AST of a record constructor, omit obvious labels
* Refactor: Change key of `class Ast.Record` from `String` to `Ast.Id`

### Build and tests

* Add validation-mode to scripts, with syntax `:t`, to check an expression's
  type without evaluating
  ([#310](https://github.com/hydromatic/morel/issues/310))
* Add an `--echo` flag to script runner
  ([#309](https://github.com/hydromatic/morel/issues/309))
* In CI, bump actions `checkout` from 1 to 4, and `setup-java` from 1 to 4
* Refactor: Add `@Nullable` annotations where necessary
* Lint: Sort enum constants in `class BuiltIn`
* Decompose definition of built-in functions into prototype and description
* Lint: Sort various sections in Maven POM file
* Refactor: Add `NullMarked` annotation to every package, so that fields and
  parameters are not-null by default
* Run `.smli` scripts from the command-line
  ([#300](https://github.com/hydromatic/morel/issues/300))
* Lint: Add another check for broken strings
* Lint: Assert that regions of a file are sorted

### Component upgrades

* In Maven, use central-publishing-maven-plugin
* Bump build-helper-maven-plugin from 3.6.0 to 3.6.1
* Bump calcite from 1.40.0 to 1.41.0
* Bump checkstyle from 10.25.0 to 12.1.2
* Bump guava from 33.4.8-jre to 33.5.0-jre
* Bump jackson from 2.19.0 to 2.20.1
* Bump java from 8..24 to 8..25
* Bump junit-jupiter from 5.13.1 to 5.14.1
* Bump maven from 3.9.9 to 3.9.11
* Bump maven-compiler-plugin from 3.14.0 to 3.14.1
* Bump maven-enforcer-plugin from 3.5.0 to 3.6.2
* Bump maven-javadoc-plugin from 3.11.2 to 3.12.0
* Bump maven-site-plugin from 3.12.1 to 3.21.0
* Bump maven-surefire-plugin from 3.5.3 to 3.5.4
* Bump scott-data-hsqldb from 0.2 to 0.3
* Bump versions-maven-plugin from 2.18.0 to 2.19.1

### Site and documentation

* Release 0.8.0
  ([#320](https://github.com/hydromatic/morel/issues/320))

## <a id="0.7.0" href="https://github.com/hydromatic/morel/releases/tag/morel-0.7.0">0.7.0</a> / 2025-06-07

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

Breaking changes:
* The `scott` sample database maps the `EMP` table to `emps`, and
  pluralizes the other table names likewise
  ([#255](https://github.com/hydromatic/morel/issues/255))
* The syntax of the `order` step is simplified, and the `desc` keyword
  is removed
  ([#244](https://github.com/hydromatic/morel/issues/244))
* Queries distinguish ordered from unordered collections, and several
  operations now return the new `bag` type rather than `list`
  ([#273](https://github.com/hydromatic/morel/issues/273),
  [#235](https://github.com/hydromatic/morel/issues/235))

Contributors:
Julian Hyde

### Features

* Simplify the syntax of the `order` step, and remove the `desc` keyword
  ([#244](https://github.com/hydromatic/morel/issues/244))
* Add built-in datatype `Descending`, and method `Relational.compare`, for
  type-based orderings
  ([#282](https://github.com/hydromatic/morel/issues/282))
* `String` structure
  ([#279](https://github.com/hydromatic/morel/issues/279))
* `unorder` step
  ([#277](https://github.com/hydromatic/morel/issues/277))
* `ordinal` expression
  ([#276](https://github.com/hydromatic/morel/issues/276))
* Add `current` keyword, representing the current row in a query
  ([#265](https://github.com/hydromatic/morel/issues/265))
* Ordered and unordered queries
  ([#273](https://github.com/hydromatic/morel/issues/273))
* Operator overloading (`over` and `inst` keywords)
  ([#237](https://github.com/hydromatic/morel/issues/237))
* Add a `bag` type, and `Bag` structure, to represent unordered collections
  with duplicates allowed
  ([#235](https://github.com/hydromatic/morel/issues/235))
* Allow set operators (`union`, `intersect`, `except`) as steps in a pipeline
  ([#253](https://github.com/hydromatic/morel/issues/253))
* Allow atomic `yield` steps at any point in the pipeline
  ([#262](https://github.com/hydromatic/morel/issues/262))
* In the `scott` sample database, map the `EMP` table to `emps` (and pluralize
  other table names)
  ([#255](https://github.com/hydromatic/morel/issues/255))
* `Char` structure
  ([#264](https://github.com/hydromatic/morel/issues/264))

### Bug-fixes and internal improvements

* Degenerate joins
  ([#287](https://github.com/hydromatic/morel/issues/287))
* Conversion of SQL `DATE`, `TIME`, `TIMESTAMP` values to Morel strings should
  not depend on locale
  ([#286](https://github.com/hydromatic/morel/issues/286))
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
* Test scripts should report errors relative to first token after whitespace
  and comments
  ([#274](https://github.com/hydromatic/morel/issues/274))
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

* Release 0.7.0
  ([#284](https://github.com/hydromatic/morel/issues/284))
* Decompose the documentation for built-in functions and structures
  ([#269](https://github.com/hydromatic/morel/issues/269))
* Typos in query reference

## <a id="0.6.0" href="https://github.com/hydromatic/morel/releases/tag/morel-0.6.0">0.6.0</a> / 2024-05-02

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

* Tabular mode
  ([#259](https://github.com/hydromatic/morel/issues/259))
* Add `with` operator (functional update notation for record values)
  ([#249](https://github.com/hydromatic/morel/issues/249))
* Add function `Sys.showAll ()`
  ([#260](https://github.com/hydromatic/morel/issues/260))
* Add function `Sys.clearEnv ()`
  ([#251](https://github.com/hydromatic/morel/issues/251))
* Tune unifier
  ([#246](https://github.com/hydromatic/morel/issues/246))
* Universal and existential quantification (`forall` and `exists`) and
  implication (`implies`)
  ([#241](https://github.com/hydromatic/morel/issues/241))

### Bug-fixes and internal improvements

* Scalar `yield` after singleton record `yield` throws
  "Conversion to core did not preserve type"
  ([#253](https://github.com/hydromatic/morel/issues/253))
* `skip` and `take` in query with unbounded variable give error
  ([#258](https://github.com/hydromatic/morel/issues/258))
* Add method `PairList.viewOf(Map)`
* Allow `Sys` to be evaluated and printed in the shell
* Refactor `NameGenerator`
* When unparsing Morel, try to generate qualified identifiers less often
  ([#252](https://github.com/hydromatic/morel/issues/252))
* Refactor: Define built-in datatypes in an enum
* Refactor: Omit declarations from type-unification
* In `PairList`, add a static `copyOf` method, and enable `set` and `remove`
  methods
* The `toString` methods of `RelList` and `Binding` should not print the
  contents of tables
* Inliner generates invalid plan if dead code occurs in a nested `let`
  ([#250](https://github.com/hydromatic/morel/issues/250))
* Optimize method `Static.transformEager` for `List` and `Collection` arguments
  ([#248](https://github.com/hydromatic/morel/issues/248))
* Cannot translate an expression that contains type annotations
  ([#247](https://github.com/hydromatic/morel/issues/247))
* In `class Static`, add methods `last`, `skipLast`

### Build and tests

* Reduce CI timeout
* Checkstyle should require that braces around blocks in `case:` are at the
  start of the line
* Enable google-java-format
  ([#245](https://github.com/hydromatic/morel/issues/245))

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

* Release 0.6.0
  ([#261](https://github.com/hydromatic/morel/issues/261))
* [Document query expressions](docs/query.md)
* In release notes, use the '0.x.0' format for releases

## <a id="0.5.0" href="https://github.com/hydromatic/morel/releases/tag/morel-0.5.0">0.5.0</a> / 2025-03-04

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

* Add `distinct` keyword as shorthand for `group` with all fields and no
  aggregate functions
  ([#231](https://github.com/hydromatic/morel/issues/231))
* Allow lambda (`fn`) to have multiple branches, similar to `case`
  ([#230](https://github.com/hydromatic/morel/issues/230))
* `Int` structure
  ([#228](https://github.com/hydromatic/morel/issues/228))
* Add `into` and `through` clauses to `from` expression
  ([#171](https://github.com/hydromatic/morel/issues/171))
* Add function `Interactive.useSilently`
* Allow comma-separated scans in `join`, and `on` in the `from` clause
  ([#216](https://github.com/hydromatic/morel/issues/216))
* Allow unbounded variables (`from` and `join` without `in`), and remove
  `suchthat` keyword
  ([#202](https://github.com/hydromatic/morel/issues/202))
* Inline singleton `case`
* Require that a non-terminal `yield` step is a record expression
  ([#213](https://github.com/hydromatic/morel/issues/213))

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
  [#43](https://github.com/hydromatic/morel/issues/43)

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
* Release 0.5.0
  ([#243](https://github.com/hydromatic/morel/issues/243))

## <a id="0.4.0" href="https://github.com/hydromatic/morel/releases/tag/morel-0.4.0">0.4.0</a> / 2024-01-04

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

* Add `take` and `skip` relational operators
  ([#204](https://github.com/hydromatic/morel/issues/204))
* File reader, and progressive types
  ([#209](https://github.com/hydromatic/morel/issues/209))
* Fold long types when printing
  ([#210](https://github.com/hydromatic/morel/issues/210))
* Rename `Option.flatten` to `Option.join`
* Allow identifiers to be quoted using backticks
  ([#71](https://github.com/hydromatic/morel/issues/71))
* Indent tuples when printing
  ([#206](https://github.com/hydromatic/morel/issues/206))
* Define relations via constrained iterations, and introduce a `suchthat`
  keyword to use them
  ([#129](https://github.com/hydromatic/morel/issues/129))

### Bug-fixes and internal improvements

* Add `directory` property to `Session`
* Add type parameter to `Core.Literal.unwrap()` method
* `FromBuilder` should remove trivial `yield` step between two scan steps
  ([#208](https://github.com/hydromatic/morel/issues/208))
* Pattern that uses nested type-constructors should not be considered
  redundant
  ([#205](https://github.com/hydromatic/morel/issues/205))
* Add utility methods `Static.transform` and `transformEager`
* Cannot deduce type for `from b in [SOME true, NONE]`
  ([#203](https://github.com/hydromatic/morel/issues/203))
* Refactor: In `Unifier.Variable`, store ordinal rather than computing it
* Refactor: Rename `Ord.forEach` to `forEachIndexed`
* In `CoreBuilder`, add `tuple(TypeSystem, Exp...)`, a more convenient
  `apply`
* Simplify `EnvShuttle`, `EnvVisitor` by adding `push` method
* Add `interface PairList` and `interface ImmutablePairList`
* Add method `Static.nextPowerOfTwo`
* `Real.signBit` gives different result on JDK 19/ARM
  ([#201](https://github.com/hydromatic/morel/issues/201))

### Build and tests

* Run `script.sml` in tests
* Detect and fix flaky tests
  ([#207](https://github.com/hydromatic/morel/issues/207))
* Disallow static star import
* In the test suite, run arbitrary "lint" checks on code
  ([#200](https://github.com/hydromatic/morel/issues/200))
* Idempotent mode for test scripts
  ([#198](https://github.com/hydromatic/morel/issues/198))
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
* Support JDK 19 and 20
  ([#199](https://github.com/hydromatic/morel/issues/199))

### Site and documentation

* Release 0.4.0
  ([#211](https://github.com/hydromatic/morel/issues/211))
* Add Maven Central badge to `README.md`

## <a id="0.3.0" href="https://github.com/hydromatic/morel/releases/tag/morel-0.3.0">0.3.0</a> / 2022-10-02

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

* Improve message when type resolution cannot deduce full record type
  ([#165](https://github.com/hydromatic/morel/issues/165))
* `from` should not have a singleton record type unless it ends with a
  singleton record `yield`
  ([#159](https://github.com/hydromatic/morel/issues/159))
* Allow Calcite schemas to contain sub-schemas
  ([#147](https://github.com/hydromatic/morel/issues/147))
* Type annotations in patterns, function declarations and expressions
  ([#138](https://github.com/hydromatic/morel/issues/138))
* Analyze match coverage, detecting redundant and exhaustive matches
  ([#55](https://github.com/hydromatic/morel/issues/55))
* Satisfiability prover
* Report positions in error messages and exceptions
  ([#118](https://github.com/hydromatic/morel/issues/118))
* Allow identifiers to contain prime characters (`'`)
  ([#105](https://github.com/hydromatic/morel/issues/105))
* Polymorphic datatype
  ([#70](https://github.com/hydromatic/morel/issues/70))
* Make functions top-level
  ([#104](https://github.com/hydromatic/morel/issues/104))
* Layered patterns (`as`), and composite `val`
  ([#103](https://github.com/hydromatic/morel/issues/103))
* `Real` structure
  ([#102](https://github.com/hydromatic/morel/issues/102))
* Allow quick eager evaluation for functions/operators with more than one
  argument
* Print control
  ([#96](https://github.com/hydromatic/morel/issues/96))
* Allow double-quote and backslash in string and char literals
  ([#100](https://github.com/hydromatic/morel/issues/100))
* `Math` structure
  ([#88](https://github.com/hydromatic/morel/issues/88))
* Floating point: `posInf`, `negInf`, `NaN`, and negative zero
* Wordle solver
  ([#94](https://github.com/hydromatic/morel/issues/94))
* Mutually recursive functions
  ([#95](https://github.com/hydromatic/morel/issues/95))
* Add `Relational.iterate`, which allows "recursive queries" such as
  transitive closure
* Add `compute` clause, for monoid comprehensions
  ([#69](https://github.com/hydromatic/morel/issues/69))
* Add `use` function, to read and execute source from a file
  ([#86](https://github.com/hydromatic/morel/issues/86))
* Join
  ([#72](https://github.com/hydromatic/morel/issues/72))
* In `from` clause, allow 'variable = value'
  ([#11](https://github.com/hydromatic/morel/issues/11))
* Add function `Relational.only`, which allows scalar sub-queries
  ([#62](https://github.com/hydromatic/morel/issues/62))
* In the shell (REPL), use prompt '`-`' and continuation '`=`'
  ([#65](https://github.com/hydromatic/morel/issues/65))
* Add `Relational.exists` function, and push it down to Calcite
  ([#51](https://github.com/hydromatic/morel/issues/51))
* Add `morel.lang`, Morel language definition for the Linux 'highlight'
  command
* Push `elem`, `notelem` and `not ... elem` down to Calcite (as SQL `IN`
  and `NOT IN`)
  ([#60](https://github.com/hydromatic/morel/issues/60))
* Allow multiple `yield` steps in `from`
  ([#52](https://github.com/hydromatic/morel/issues/52))
* Translate `List.filter` as if user had written `where`;
  and `List.map` to `yield`
  ([#45](https://github.com/hydromatic/morel/issues/45))
* `group` with empty key should emit one row
  ([#57](https://github.com/hydromatic/morel/issues/57))
* Views (parameterized functions that return a relation) and inlining
  ([#54](https://github.com/hydromatic/morel/issues/54))
* Optimize core language by inlining expressions
  ([#53](https://github.com/hydromatic/morel/issues/53))
* Add a `morel` Calcite UDF, to allow hybrid plans with a mixture of relational
  and non-relational
  ([#42](https://github.com/hydromatic/morel/issues/42))
* Translate to full Calcite algebra
  ([#40](https://github.com/hydromatic/morel/issues/40))
* Core language
  ([#46](https://github.com/hydromatic/morel/issues/46))
* Deduce types when a function is applied to a record selector
  ([#48](https://github.com/hydromatic/morel/issues/48))
* Session variables
  ([#44](https://github.com/hydromatic/morel/issues/44))
* Add `Sys.plan ()` function, to display plans from the shell
  ([#41](https://github.com/hydromatic/morel/issues/41))
* Implement built-in `vector` and `order` data types
  ([#39](https://github.com/hydromatic/morel/issues/39))
* Implement built-in `option` datatype and supporting functions
  ([#38](https://github.com/hydromatic/morel/issues/38))
* Raise exceptions in built-in functions
  ([#37](https://github.com/hydromatic/morel/issues/37))
* Add `o` (function composition) and `@` (list concatenation) operators
  ([#25](https://github.com/hydromatic/morel/issues/25))
* In record pattern, make labels optional, and disallow `...` anywhere but end
  ([#36](https://github.com/hydromatic/morel/issues/36))
* Functions in relations
  ([#34](https://github.com/hydromatic/morel/issues/34))
* Create objects for built-in structures `List` and `String`, and allow
  `structure.name` references
  ([#27](https://github.com/hydromatic/morel/issues/27))
* Add `elem`, `notelem` operators
  ([#33](https://github.com/hydromatic/morel/issues/33))
* Add `union`, `intersect`, `except` operators
  ([#30](https://github.com/hydromatic/morel/issues/30))
* In `from` expression, allow `in` to assign to pattern
  ([#31](https://github.com/hydromatic/morel/issues/31))
* Overload `+` operator to allow both `int` and `real` arguments
  ([#29](https://github.com/hydromatic/morel/issues/29))
* Use `=` rather than `as` for assigning column aliases in `group` and `compute`
  ([#24](https://github.com/hydromatic/morel/issues/24))
* Default arguments for aggregate functions
  ([#23](https://github.com/hydromatic/morel/issues/23))
* Allow `from` clause that defines 0 sources
  ([#17](https://github.com/hydromatic/morel/issues/17))
* Add `order` clause
  ([#20](https://github.com/hydromatic/morel/issues/20))
* In `compute` clause of `group`, apply `of` expression to each row
* In `from`, allow multiple `group` and `where` clauses in any order, optionally
  followed by `yield`
  ([#21](https://github.com/hydromatic/morel/issues/21))

### Bug-fixes and internal improvements

* In compilation environment, use name + ordinal as the key, not just name, to
  accommodate variable copies caused by inlining
  ([#179](https://github.com/hydromatic/morel/issues/179))
* Refactor: Move list methods into `Static`
* In `CoreBuilder`, add methods for frequently used operations: `equals`,
  `lessThan`, `only` etc.
* Allocate `bool` literals (`true` and `false`) once in `CoreBuilder`
* Keywords should be lower case
* Join variables are out of order in the rows sent to an aggregate function
  ([#101](https://github.com/hydromatic/morel/issues/101))
* Script parser hangs if it encounters invalid syntax, and ignores comments at
  ends of files
  ([#99](https://github.com/hydromatic/morel/issues/99))
* Refactor: `Array` to `List`, `List` to `Consumer`
* Refactor: move `Shell` state into new `interface Config`
* Tuning
* Rename `Option.join` to `flatten` (to make way for the `join` keyword)
* Keywords must be lower case
  ([#73](https://github.com/hydromatic/morel/issues/73))
* `order` after `yield` gives 'unbound variable' error
  ([#64](https://github.com/hydromatic/morel/issues/64))
* In `compute` clause, the key value should be available but is null
  ([#67](https://github.com/hydromatic/morel/issues/67))
* During inlining, use '_' as delimiter for qualified variable names
* Converting an empty list to relational algebra throws
  ([#59](https://github.com/hydromatic/morel/issues/59))
* Add `Core.Yield` step, and obsolete `Core.yieldExp`
* Rationalize order of Java 'import' statements
* Always inline atomic variables
* Add method `Static.shorterThan(Iterable, int)`
* Function argument `()` should have type `unit`, not 0-tuple
  ([#49](https://github.com/hydromatic/morel/issues/49))
* Type resolver does not resolve all fields of record types
  ([#50](https://github.com/hydromatic/morel/issues/50))
* Improve field names, e.g. rename `Exp e` to `Exp exp`
* Remove overrides of `toString()` method in `Environment` and `EvalEnv`
* Factor `Converter` out of `CalciteForeignValue`
* Refactor: rename `let` fields
* Add `enum Prop`, for strongly-typed properties
* Garbage-collect obscured variables
  ([#13](https://github.com/hydromatic/morel/issues/13))
* AST that contains a call to `op +` is unparsed incorrectly
* Refactor `DataSet`
* `TypeVisitor` should visit parameter types inside `DataType`
* Record pattern in `from` mixes up fields if not in alphabetical order
  ([#35](https://github.com/hydromatic/morel/issues/35))
* Deduce type of polymorphic field in tuple or record
* Add tracing to `Unifier`
* Move `class TypeResolver.TypeMap` to top-level
* Correct order of numeric labels in records, and allow 0 as a label
* A record with a field named "1" is a record, not a tuple
* During validation, replace `From.sources` if rewrites occur
* The `sum` aggregate function only works on `int` values
  ([#28](https://github.com/hydromatic/morel/issues/28))
* Cannot parse `()` as pattern
  ([#26](https://github.com/hydromatic/morel/issues/26))
* Morel shell should work even if Apache Maven is not installed
* `AssertionError: unknown FUN_DECL`
  ([#19](https://github.com/hydromatic/morel/issues/19))
* Character literal should unparse as `#"a"`, not `"a"`
  ([#22](https://github.com/hydromatic/morel/issues/22))

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
* `ShellTest` is non-deterministic
  ([#107](https://github.com/hydromatic/morel/issues/107))
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
* Bump `junit` from 4.11 to 5.7.2,
  `hamcrest` from 1.3 to 2.2
  ([#47](https://github.com/hydromatic/morel/issues/47))
* Add tests for blog post 'Aggregate queries in Morel'
* Add tests for blog post 'Word Count revisited'
* Bump `javacc` from 4.0 to 7.0.5,
  `maven-javacc-plugin` from 2.4 to 3.0.0

### Site and documentation

* Release 0.3.0
  ([#181](https://github.com/hydromatic/morel/issues/181))
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

## <a id="0.2.0" href="https://github.com/hydromatic/morel/releases/tag/morel-0.2.0">0.2.0</a> / 2020-03-10

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

* Improve pretty-printing: wrap long lines, and abbreviate long lists and deep
  structures
  ([#15](https://github.com/hydromatic/morel/issues/15))
* In `from` clause, allow initializers to reference previous variables
  ([#12](https://github.com/hydromatic/morel/issues/12))
* In `group`, `as alias` is optional
* Implicit labels in record expressions
  ([#10](https://github.com/hydromatic/morel/issues/10))
* Allow `<expr>.<field>` as an alternative syntax for `#<field> <expr>`
  ([#9](https://github.com/hydromatic/morel/issues/9))
* Rename project from 'smlj' to 'morel'
  ([#7](https://github.com/hydromatic/morel/issues/7))
* Foreign values, including record values based on the contents of a JDBC schema
  ([#5](https://github.com/hydromatic/morel/issues/5))
* Add `group` clause (and `compute` sub-clause) to `from` expression, to support
  aggregation and aggregate functions
  ([#6](https://github.com/hydromatic/morel/issues/6))
* Polymorphic types
* Add `String` and `List` basis functions

### Bug-fixes and internal improvements

* Ensure that types derived for REPL expressions have no free type variables
  ([#16](https://github.com/hydromatic/morel/issues/16))
* Tuple should equal record, and both equal `unit` when empty
  ([#14](https://github.com/hydromatic/morel/issues/14))
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
* `let fun` inside `from` expression fails
  ([#4](https://github.com/hydromatic/morel/issues/4))
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

## <a id="0.1" href="https://github.com/hydromatic/morel/releases/tag/smlj-0.1.0">0.1</a> / 2019-07-24

Initial release features the core language (primitive types, lists,
tuples, records; `let`, `if`, `fn` and `case` expressions; `val`,
`fun` and `datatype` declarations), an interactive shell `smlj`, and
relational extensions (`from`).
