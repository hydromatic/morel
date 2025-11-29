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

# Datalog

Morel includes support for Datalog, a declarative logic programming
language commonly used for program analysis, knowledge representation,
and deductive databases.

## Overview

Datalog extends Morel with:
- **Declarative relations** defined by facts and rules
- **Recursive queries** using stratified negation
- **Semi-naive evaluation** for efficient fixpoint computation
- **Type-safe integration** with Morel's type system

## Getting Started

### Basic Example

<pre>
<b>val</b> program = ".decl edge(x:int, y:int)
edge(1, 2).
edge(2, 3).
edge(3, 4).
.output edge";

Datalog.execute program;
<i>
val it = {edge=[{x=1,y=2},{x=2,y=3},{x=3,y=4}]}
  : {edge:{x:int, y:int} list} variant</i>
</pre>

### Transitive Closure

<pre>
<b>val</b> tc = ".decl edge(x:int, y:int)
.decl path(x:int, y:int)
edge(1, 2).
edge(2, 3).
edge(3, 4).

(* Base case: direct edges are paths *)
path(X, Y) :- edge(X, Y).

(* Recursive case: paths are transitive *)
path(X, Z) :- path(X, Y), edge(Y, Z).

.output path";

Datalog.execute tc;
<i>
val it = {path=[{x=1,y=2},{x=1,y=3},{x=1,y=4},{x=2,y=3},{x=2,y=4},{x=3,y=4}]}
  : {path:{x:int, y:int} list} variant</i>
</pre>

## Syntax

The syntax is based on [Souffle](https://souffle-lang.github.io/) with the
following differences:
- Souffle's `number` type is `int`
- Souffle's `symbol` type is `string`
- Souffle's `.input` directive has one argument; Morel's has an optional
  second argument for the file name

### Declarations

Relations must be declared before use:

```datalog
.decl relation_name(param1:type1, param2:type2, ...)
```

Supported types:
- `int` - integers (mapped to Morel `int`)
- `string` - strings (mapped to Morel `string`)

Examples:
```datalog
.decl edge(x:int, y:int)
.decl person(name:string, age:int)
```

### Facts

Facts define base data:

```datalog
edge(1, 2).
person("Alice", 30).
color(red).
```

### Rules

Rules derive new facts from existing ones:

```datalog
head(Args) :- body1(Args1), body2(Args2), ...
```

Components:
- **Head**: Single atom defining what is derived
- **Body**: Comma-separated atoms (conjunction)
- **Variables**: Must start with uppercase letter
- **Constants**: Numbers or quoted strings

Example:
```datalog
.decl parent(p:string, c:string)
.decl ancestor(a:string, d:string)

parent("Alice", "Bob").
parent("Bob", "Carol").

(* Base case *)
ancestor(P, C) :- parent(P, C).

(* Recursive case *)
ancestor(A, D) :- ancestor(A, X), parent(X, D).
```

### Negation

Use `!` to negate atoms (requires stratification):

```datalog
.decl student(name:string)
.decl graduate(name:string)
.decl undergraduate(name:string)

student("Alice").
student("Bob").
graduate("Alice").

(* Bob is a student but not a graduate *)
undergraduate(X) :- student(X), !graduate(X).
```

**Stratification**: Negation cycles are prohibited:
```datalog
(* INVALID - negation cycle *)
p(X) :- edge(X, Y), !q(X).
q(X) :- edge(X, Y), !p(X).
```

### Comparison Operators

Rules can include comparisons between variables and constants:

```datalog
.decl num(n:int)
.decl small(n:int)
num(1). num(5). num(10).
small(X) :- num(X), X < 7.       (* less than *)
```

Supported operators:
- `=` - equality
- `!=` - not equal
- `<` - less than
- `<=` - less than or equal
- `>` - greater than
- `>=` - greater than or equal

Examples:
```datalog
(* Find self-loops in a graph *)
self_loop(X) :- edge(X, Y), X = Y.

(* Distinct pairs only *)
sibling(X, Y) :- parent(P, X), parent(P, Y), X != Y.

(* Ordered pairs to avoid duplicates *)
sibling(X, Y) :- parent(P, X), parent(P, Y), X < Y.
```

### Arithmetic Expressions

Head atoms can contain arithmetic expressions:

```datalog
.decl fact(n:int, value:int)
fact(0, 1).
fact(N + 1, value * (N + 1)) :- fact(N, value), N < 10.
```

This computes factorial values: `{(0,1), (1,1), (2,2), (3,6), ...}`.

Supported operators:
- `+` - addition
- `-` - subtraction
- `*` - multiplication

Variables in arithmetic expressions must be grounded by positive
body atoms.

### Input Directive

Load facts from CSV files:

```datalog
.decl dept(deptno:int, dname:string, loc:string)
.input dept "data/scott/depts.csv"
.output dept
```

The CSV file should have a header row matching the relation's
parameter names.

### Output Directive

Specify which relations to return:

```datalog
.output relation_name
```

Multiple outputs are supported:
```datalog
.output edge
.output path
```

### Comments

```datalog
(* This is a block comment *)

// This is a line comment

.decl edge(x:int, y:int)  (* Inline comment *)
```

## API

### Datalog.execute

Executes a Datalog program and returns a variant:

```sml
Datalog.execute : string -> 'a variant
```

**Return type**:
- Single output: `{relation: element_type list} variant`
- Multiple outputs: `{rel1: type1 list, rel2: type2 list, ...} variant`
- No outputs: `unit variant`

**Example**:

<pre>
<b>val</b> result = Datalog.execute ".decl num(n:int)
num(1). num(2). num(3).
.output num";
<i>
val result = {num=[1,2,3]} : {num:int list} variant</i>
</pre>

### Datalog.validate

Validates a Datalog program and returns its type:

```sml
Datalog.validate : string -> string
```

**Example**:

<pre>
Datalog.validate ".decl edge(x:int, y:int)
edge(1, 2).
.output edge";
<i>
val it = "{edge:{x:int, y:int} list}" : string</i>
</pre>

**Return values**:
- Success: Type representation like `"{edge:{x:int, y:int} list}"`
- Parse error: `"Parse error: ..."`
- Semantic error: `"Compilation error: ..."` (safety, stratification)

### Datalog.translate

Translates a Datalog program to Morel source code:

```sml
Datalog.translate : string -> string option
```

**Example**:

<pre>
Datalog.translate ".decl edge(x:int, y:int)
.decl path(x:int, y:int)
edge(1,2). edge(2,3).
path(X,Y) :- edge(X,Y).
path(X,Z) :- path(X,Y), edge(Y,Z).
.output path";
<i>
val it = SOME "let
  val edge = [(1, 2), (2, 3)]
  val path =
    Relational.iterate edge
      (fn (allPath, newPath) =>
        from (x, y) in newPath, (v0, z) in edge
          where y = v0 yield (x, z))
in
  {path = from (x, y) in path}
end" : string option</i>
</pre>

## Datalog in Native Morel

Morel's core language can express Datalog-style deductive queries
directly, without using the `Datalog` structure. This is possible
because of two key features:

- **Unbounded variables**: Variables in `from` expressions that are
  not bound to a collection iterate over all values of their type
- **Predicate inversion**: Morel can invert predicates to generate
  values efficiently, rather than testing all possible values

This means Datalog-style logic programming can be intermixed with
functional programming and SQL-style queries in the same program.

### Correspondence

A Datalog program translates naturally to Morel:

| Datalog | Morel |
|---------|-------|
| Relation declaration | Function returning `bool` |
| Fact `r(1, 2).` | `(1, 2) elem facts` in function body |
| Rule body `,` (and) | `andalso` |
| Multiple rules (or) | `orelse` |
| Variable | Unbounded variable in `from` |
| Existential (body variable not in head) | `exists` expression |
| Query | `from` with `where` calling predicate |

### Example: Transitive Closure

The Datalog transitive closure program:

```datalog
.decl edge(x:int, y:int)
.decl path(x:int, y:int)
edge(1, 2).
edge(2, 3).
path(X, Y) :- edge(X, Y).
path(X, Z) :- path(X, Y), edge(Y, Z).
.output path
```

Corresponds to this native Morel:

<pre>
<b>let</b>
  <b>val</b> edges = [(1, 2), (2, 3)]
  <b>fun</b> edge (x, y) = (x, y) elem edges
  <b>fun</b> path (x, y) =
    edge (x, y) <b>orelse</b>
    (<b>exists</b> z <b>where</b> path (x, z) <b>andalso</b> edge (z, y))
<b>in</b>
  <b>from</b> x, y <b>where</b> path (x, y)
<b>end</b>;
<i>
val it = [(1,2),(2,3),(1,3)] : (int * int) list</i>
</pre>

The key points:
- `edge` is a predicate function: given `(x, y)`, returns whether
  that edge exists
- `path` is a recursive predicate combining the base case (`edge`)
  and recursive case using `orelse`
- The body variable `z` (which appears in the rule body but not
  the head) becomes an `exists` expression
- The query `from x, y where path (x, y)` uses unbounded variables
  `x` and `y`, which Morel resolves via predicate inversion

### Example: Self-Loops

Find vertices with self-loops:

```datalog
.decl edge(x:int, y:int)
.decl self_loop(x:int)
edge(1, 1). edge(2, 3). edge(4, 4).
self_loop(X) :- edge(X, Y), X = Y.
.output self_loop
```

In native Morel:

<pre>
<b>let</b>
  <b>val</b> edges = [(1, 1), (2, 3), (4, 4)]
  <b>fun</b> edge (x, y) = (x, y) elem edges
  <b>fun</b> self_loop x =
    <b>exists</b> y <b>where</b> edge (x, y) <b>andalso</b> x = y
<b>in</b>
  <b>from</b> x <b>where</b> self_loop x
<b>end</b>;
<i>
val it = [1, 4] : int list</i>
</pre>

### Mixing Paradigms

Because Datalog-style predicates are just Morel functions, you can
freely mix deductive, functional, and relational code:

<pre>
<b>let</b>
  (* Datalog-style: define reachability as a predicate *)
  <b>val</b> edges = [(1, 2), (2, 3), (3, 4)]
  <b>fun</b> edge (x, y) = (x, y) elem edges
  <b>fun</b> reachable (x, y) =
    edge (x, y) <b>orelse</b>
    (<b>exists</b> z <b>where</b> reachable (x, z) <b>andalso</b> edge (z, y))

  (* Functional: transform the result *)
  <b>fun</b> formatPair (x, y) =
    Int.toString x ^ " -> " ^ Int.toString y

  (* SQL-style: query with aggregation *)
  <b>val</b> summary =
    <b>from</b> x, y <b>where</b> reachable (x, y)
      <b>group</b> x <b>compute</b> c = count
<b>in</b>
  (* Combine all three styles *)
  <b>from</b> x, y <b>where</b> reachable (x, y)
    <b>yield</b> formatPair (x, y)
<b>end</b>;
<i>
val it = ["1 -> 2","1 -> 3","1 -> 4","2 -> 3","2 -> 4","3 -> 4"]
  : string list</i>
</pre>

### When to Use Each Style

Use the **Datalog structure** (`Datalog.execute`) when:
- You have a standalone Datalog program as a string
- You want automatic semi-naive evaluation optimization
- You're working with Datalog syntax from external sources

Use **native Morel predicates** when:
- You want to mix deductive logic with other Morel code
- You need fine-grained control over evaluation
- You're building predicates programmatically

Both approaches use the same underlying mechanism: predicate
inversion converts unbounded variable queries into efficient
iteration.

## Evaluation

Morel uses **semi-naive evaluation** for efficient fixpoint
computation:

1. **Initialize**: Start with facts
2. **Iterate**: Apply rules to derive new tuples using only newly
   derived tuples from the previous iteration
3. **Fixpoint**: Stop when no new tuples are derived
4. **Output**: Return results for output relations

## Safety Rules

All Datalog programs must be **safe** and **stratified**.

### Safety

**Rule**: Every variable in the head must appear in a positive
body atom (not inside an arithmetic expression or negated atom).

**Valid**:
```datalog
path(X, Y) :- edge(X, Y).
path(X, Z) :- path(X, Y), edge(Y, Z).
```

**Invalid**:
```datalog
(* Y appears in head but not in any positive atom *)
bad(X, Y) :- edge(X, Z).
```

**Rationale**: Unsafe rules can produce infinite results.

### Stratification

**Rule**: No relation can depend on its own negation (directly or
indirectly).

**Invalid**:
```datalog
(* p depends on !q, q depends on !p - negation cycle *)
p(X) :- edge(X, Y), !q(X).
q(X) :- edge(X, Y), !p(X).
```

**Rationale**: Negation cycles have no well-defined semantics.

## Type Checking

Datalog performs type checking on facts and rules:

### Type Mismatches in Facts

<pre>
Datalog.validate ".decl edge(x:int, y:int)
edge(\"hello\", 2).";
<i>
val it = "Compilation error: Type mismatch in fact edge(...):
  expected int, got string for parameter x" : string</i>
</pre>

### Arity Mismatches

<pre>
Datalog.validate ".decl edge(x:int, y:int)
edge(1, 2, 3).";
<i>
val it = "Compilation error: Atom edge/3 does not match
  declaration edge/2" : string</i>
</pre>

### Undeclared Relations

<pre>
Datalog.validate ".decl edge(x:int, y:int)
path(1, 2).";
<i>
val it = "Compilation error: Relation 'path' used in fact
  but not declared" : string</i>
</pre>

## Examples

### Factorial

<pre>
Datalog.execute ".decl fact(n:int, value:int)
fact(0, 1).
fact(N + 1, value * (N + 1)) :- fact(N, value), N < 10.
.output fact";
<i>
val it = {fact=[{n=0,value=1},{n=1,value=1},{n=2,value=2},
  {n=3,value=6},{n=4,value=24},...]}
  : {fact:{n:int, value:int} list} variant</i>
</pre>

### Ancestors

<pre>
<b>val</b> family = ".decl parent(p:string, c:string)
.decl ancestor(a:string, d:string)
.decl descendant(p:string, d:string)

parent(\"Alice\", \"Bob\").
parent(\"Bob\", \"Carol\").
parent(\"Carol\", \"Dan\").

ancestor(P, C) :- parent(P, C).
ancestor(A, D) :- ancestor(A, X), parent(X, D).
descendant(P, D) :- ancestor(D, P).

.output ancestor
.output descendant";

Datalog.execute family;
<i>
val it = {ancestor=[...], descendant=[...]}
  : {ancestor:{a:string, d:string} list,
     descendant:{d:string, p:string} list} variant</i>
</pre>

### Siblings

<pre>
<b>val</b> siblings = ".decl parent(p:string, c:string)
.decl sibling(x:string, y:string)

parent(\"Alice\", \"Bob\").
parent(\"Alice\", \"Carol\").

(* Distinct pairs only *)
sibling(X, Y) :- parent(P, X), parent(P, Y), X != Y.

.output sibling";

Datalog.execute siblings;
<i>
val it = {sibling=[{x="Bob",y="Carol"},{x="Carol",y="Bob"}]}
  : {sibling:{x:string, y:string} list} variant</i>
</pre>

### Set Difference with Negation

<pre>
<b>val</b> diff = ".decl all(x:int)
.decl excluded(x:int)
.decl result(x:int)

all(1). all(2). all(3). all(4).
excluded(2). excluded(4).
result(X) :- all(X), !excluded(X).

.output result";

Datalog.execute diff;
<i>
val it = {result=[1,3]} : {result:int list} variant</i>
</pre>

### Loading External Data

<pre>
Datalog.execute ".decl adj(state:string, adjacent:string)
.decl result(state:string)
.input adj \"data/map/adjacent-states.csv\"
result(state) :- adj(state, \"FL\"), adj(state, \"TN\").
.output result";
<i>
val it = {result=["GA"]} : {result:string list} variant</i>
</pre>

### Odd Cycle Detection

<pre>
Datalog.execute ".decl edge(x:string, y:string)
.decl odd_path(x:string, y:string)
.decl exists_odd_cycle()

edge(\"a\", \"b\").
edge(\"b\", \"c\").
edge(\"c\", \"a\").

odd_path(X, Y) :- edge(X, Y).
odd_path(X, Y) :- odd_path(X, Z), edge(Z, U), edge(U, Y).
exists_odd_cycle() :- odd_path(X, X).

.output exists_odd_cycle";
<i>
val it = {exists_odd_cycle=[()]} : {exists_odd_cycle:unit list} variant</i>
</pre>

## Best Practices

### Naming Conventions

- **Relations**: lowercase with underscores (`edge`, `parent_of`)
- **Variables**: uppercase letters (`X`, `Y`, `Person`)
- **Constants**: numbers or quoted strings

### Writing Efficient Rules

1. **Put selective atoms first**: More restrictive conditions early
2. **Avoid Cartesian products**: Ensure variables connect atoms
3. **Use appropriate base cases**: Initialize recursive rules properly

### Debugging

Use `Datalog.validate` to check for errors before execution:

<pre>
<b>val</b> prog = "...";
<b>val</b> typeResult = Datalog.validate prog;
<i>
val typeResult = "{...}" : string</i>
</pre>

Check if `typeResult` starts with `"Error:"` or `"Parse error:"`.

Use `Datalog.translate` to see the generated Morel code.

## References

- [Datalog (Wikipedia)](https://en.wikipedia.org/wiki/Datalog)
- [What You Always Wanted to Know About Datalog (And Never Dared
  to Ask)](https://personal.utdallas.edu/~gupta/courses/acl/papers/datalog-paper.pdf)
- [Foundations of Databases (Abiteboul, Hull, Vianu)](http://webdam.inria.fr/Alice/)

## See Also

- [Morel Language Reference](REFERENCE.md)
- [Query expressions in Morel](query.md)
- [Unbounded variables via predicate inversion](https://github.com/hydromatic/morel/commit/eff94a5d66b28cb654851face07ee2c525e35369)
  (GitHub commit with detailed explanation)
