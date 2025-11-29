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

# Claude Code Notes

This file provides guidance to Claude Code (claude.ai/code) when working
with code in this repository.

## Overview

Morel is a Standard ML interpreter with relational extensions,
implemented in Java. It allows users to write Standard ML code with
SQL-like query expressions to operate on in-memory data structures.
The project uses Apache Calcite for query optimization and planning.

## Build and Test Commands

### Building
```bash
./mvnw install              # Full build with all checks
./mvnw verify               # Compile and run tests
./mvnw compile              # Compile only
```

### Running Tests
```bash
./mvnw test                 # Run all tests
./mvnw test -Dtest=MainTest # Run specific test class
./mvnw test -Dtest=MainTest#testRepl # Run specific test method

# Run individual .smli script test files
./morel src/test/resources/script/wordle.smli

# Run individual script with visible output (for debugging)
# The --echo flag shows test output to stdout in real-time
./morel --echo src/test/resources/script/wordle.smli
```

### Running the Shell
```bash
./morel                     # Start interactive REPL
./morel -e '1 + 2'          # Evaluate expression and exit
```

### Code Quality
```bash
./mvnw checkstyle:check     # Run checkstyle
./mvnw javadoc:javadoc      # Generate javadoc
```

Note: The build uses Google Java Format automatically during the
`process-sources` phase. Checkstyle runs in the same phase.

## Architecture

Morel follows a traditional interpreter pipeline:
Parse → Type Check → Compile → Evaluate.

### Core Components

**Parser (`net.hydromatic.morel.parse`)**
- `MorelParser.jj`: JavaCC grammar for Standard ML plus extensions
- `MorelParserImpl`: Generated parser implementation
- Produces AST (`Ast` nodes)

**AST Layer (`net.hydromatic.morel.ast`)**
- `Ast`: User-facing abstract syntax tree from parser
- `Core`: Internal representation after type resolution
- `AstBuilder`, `CoreBuilder`: Fluent builders for constructing nodes
- `Visitor`, `Shuttle`: Tree traversal patterns

**Type System (`net.hydromatic.morel.type`)**
- `TypeSystem`: Central registry for types
- `Type` hierarchy: `PrimitiveType`, `RecordType`, `TupleType`,
  `ListType`, `FnType`, `DataType`, `TypeVar`, etc.
- `TypeVar`: Polymorphic type variables (parametric polymorphism)
- `TypeUnifier`: Hindley-Milner type inference using unification
- `Binding`: Associates names with types and values

**Compilation (`net.hydromatic.morel.compile`)**
- `TypeResolver`: Type inference and checking; converts `Ast` to `Core`
- `Compiler`: Compiles typed `Core` expressions into executable `Code`
- `Environment`: Symbol table holding bindings
- `BuiltIn`: Defines all built-in functions, operators, and types
- `CalciteCompiler`: Translates relational expressions to Calcite plans
- `Resolver`: Resolves names and converts patterns to code

**Evaluation (`net.hydromatic.morel.eval`)**
- `Code`: Interface for executable code nodes
- `Codes`: Implementations of all code types
- `EvalEnv`: Runtime environment mapping variables to values
- `Closure`: Function values that capture their environment
- `Applicable`: Function objects with apply methods
- `Session`: Maintains REPL state and configuration

**Datalog (`net.hydromatic.morel.datalog`)**
- `DatalogParserImpl`: JavaCC parser for Datalog syntax
- `DatalogAst`: Datalog abstract syntax tree nodes
- `DatalogAnalyzer`: Safety and stratification checking
- `DatalogTranslator`: Translates Datalog to Morel source
- `DatalogEvaluator`: Orchestrates parse → analyze → translate → execute

**Foreign Interface (`net.hydromatic.morel.foreign`)**
- `ForeignValue`: Interface for exposing Java values/functions to Morel
- `Calcite`: Integration with Apache Calcite for relational queries
- `DataSet`: Abstraction for queryable datasets (backed by Calcite)

**Main Entry Points**
- `Main`: REPL implementation with shell and sub-shell support
- `Shell`: Handles command execution and error reporting

### Key Execution Flow

1. **Parsing**: User input → `MorelParser` → `Ast` nodes
2. **Type Resolution**: `Ast` + `Environment` → `TypeResolver` →
   typed `Core` nodes
3. **Compilation**: `Core` → `Compiler` → `Code` nodes
4. **Evaluation**: `Code` + `EvalEnv` → execution → result value

### Important Implementation Details

**Type Inference**
- Uses Hindley-Milner algorithm (Algorithm W) via `TypeResolver`
- Type variables represent unknown types during inference
- Unification (`TypeUnifier`) propagates type constraints
- Generalization introduces polymorphism at `let` bindings

**Relational Extensions**
- `from` expressions are first-class and composable
- TypeResolver converts `from` to `Core.From` nodes
- Compiler can either:
  - Inline as nested loops (simple cases)
  - Send to `CalciteCompiler` for optimization (complex queries)
- Integration with Calcite allows joining external data sources

**Overloading**
- Functions like `+`, `max`, `empty` support multiple type signatures
- Declared using `over` (declares overloaded name) and `inst` (adds
  instance)
- Bindings track `overloadId` to distinguish overload instances
- Type resolution selects appropriate instance based on argument types

**Pattern Matching**
- Patterns appear in `val`, `fun`, `case`, `fn`, and `from`
- `PatternCoverageChecker` ensures exhaustiveness and redundancy
- Compiled to decision trees with guards

## Test Organization

Tests are in `src/test/java` and use JUnit 5. The main test
infrastructure:

- `MainTest`: Primary tests using the `Ml` helper class
- `Ml.ml()`: Helper to run Morel code and check results
- `src/test/resources/script/`: Reference test files (`.smli` suffix)
  - These are Morel source files with expected output
  - Run via `MainTest` methods that check actual vs. expected output

Key test files in `src/test/resources/script/`:
- `builtIn.smli`: Tests for built-in functions and operators
- `relational.smli`: Tests for relational/query features
- `simple.smli`: Basic language features
- `datatype.smli`: Algebraic data types
- `type.smli`: Type system tests
- `foreign.smli`: Foreign value integration
- `datalog.smli`: Datalog interface tests

## Common Development Patterns

### Adding a Built-in Function

1. Add the function definition in `BuiltIn.java`
2. Register it in the appropriate structure (LIST, STRING, etc.)
3. Add tests in the corresponding `src/test/resources/script/` file
4. Update type signatures if polymorphic

### Adding a Language Feature

1. Update `MorelParser.jj` grammar
2. Add AST node types to `Ast.java` if needed
3. Update `TypeResolver.java` for type checking
4. Add compilation logic in `Compiler.java`
5. Add evaluation logic in `Codes.java`
6. Add tests

### Debugging Type Errors

- The `TypeResolver` tracks type constraints during inference
- Look for `unify()` calls to see where types are constrained
- Type variables have unique IDs; follow them through unification
- The `Tracer` interface can log type resolution steps

### Multi-line string formatting

Morel's linter (`LintTest`) requires that string literals that contain
line endings are split across lines, but if the total string can fit
on one line Google Java Format will join the lines. Add `//` after the
first `\n"` to prevent this. For example:

```java
String s = "first line\n" //
    + "second line";
```
