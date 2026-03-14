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

# Morel language reference

This document describes the grammar of Morel
([constants](#constants),
[identifiers](#identifiers),
[expressions](#expressions),
[patterns](#patterns),
[types](#types),
[declarations](#declarations)),
and then lists its built-in
[operators](#built-in-operators),
[types](#built-in-types),
[functions](#built-in-functions).
[Properties](#properties) affect the execution strategy and the
behavior of the shell.

Query expressions (`from`, `exists`, `forall`) are described in more
detail in the [query reference](query.md).

## Grammar

This reference is based on
[Andreas Rossberg's grammar for Standard ML](https://people.mpi-sws.org/~rossberg/sml.html).
While the files have a different [notation](#notation),
they are similar enough to the two languages.

### Differences between Morel and SML

Morel aims to be compatible with Standard ML.
It extends Standard ML in areas related to relational operations.
Some of the features in Standard ML are missing,
just because they take effort to build.
Contributions are welcome!

In Morel but not Standard ML:
* Queries (expressions starting with `exists`, `forall` or
  `from`) with `compute`,
  `distinct`,
  `except`,
  `group`,
  `intersect`,
  `into`,
  `join`,
  `order`,
  `require`,
  `skip`,
  `take`,
  `through`,
  `union`,
  `unorder`,
  `where`,
  `yield` steps and `in` and `of` keywords
* `elem`,
  `implies`,
  `notelem` binary operators
* `current`,
  `elements`,
  `ordinal` nilary operators
* `typeof` type operator
* <code><i>lab</i> =</code> is optional in <code><i>exprow</i></code>
* <code><i>record</i>.<i>lab</i></code> as an alternative to
  <code>#<i>lab</i> <i>record</i></code>;
  for tuples, <code><i>tuple</i>.1</code>, <code><i>tuple</i>.2</code> etc.
  as an alternative to <code>#1 <i>tuple</i></code>,
  <code>#2 <i>tuple</i></code>
* postfix method-call syntax <code><i>exp</i>.<i>f</i> ()</code> and
  <code><i>exp</i>.<i>f</i> <i>arg</i></code>, where `f` is a function
  whose first parameter is named `self`
* identifiers and type names may be quoted
  (for example, <code>\`an identifier\`</code>)
* `with` functional update for record values
* overloaded functions may be declared using `over` and `inst`
* `(*)` line comments (syntax as SML/NJ and MLton)

In Standard ML but not in Morel:
* `word` constant
* `longid` identifier
* references (`ref` and operators `!` and `:=`)
* exceptions (`raise`, `handle`, `exception`)
* `while` loop
* data type replication (`type`)
* `withtype` in `datatype` declaration
* abstract type (`abstype`)
* structures (`structure`)
* signature refinement (`where type`)
* signature sharing constraints
* local declarations (`local`)
* operator declarations (`nonfix`, `infix`, `infixr`)
* `open`
* `before` and `o` operators

### Constants

<pre>
<i>con</i> <b>&rarr;</b> <i>int</i>                       integer
    | <i>float</i>                     floating point
    | <i>char</i>                      character
    | <i>string</i>                    string
<i>int</i> &rarr; [<b>~</b>]<i>num</i>                    decimal
    | [<b>~</b>]<b>0x</b><i>hex</i>                  hexadecimal
<i>float</i> &rarr; [<b>~</b>]<i>num</i><b>.</b><i>num</i>              floating point
    | [<b>~</b>]<i>num</i>[<b>.</b><i>num</i>]<b>e</b>[<b>~</b>]<i>num</i>
                                scientific
<i>char</i> &rarr; <b>#"</b><i>ascii</i><b>"</b>                 character
<i>string</i> &rarr; <b>"</b><i>ascii</i>*<b>"</b>               string
<i>num</i> &rarr; <i>digit</i> <i>digit</i>*              number
<i>hex</i> &rarr; (<i>digit</i> | <i>letter</i>) (<i>digit</i> | <i>letter</i>)*
                                hexadecimal number (letters
                                may only be in the range A-F)
<i>ascii</i> &rarr; ...                     single non-" ASCII character
                                or \-headed escape sequence
</pre>

### Identifiers

<pre>
<i>id</i> &rarr;  <i>letter</i> (<i>letter</i> | <i>digit</i> | ''' | <b>_</b>)*
                                alphanumeric
    | <i>symbol</i> <i>symbol</i>*            symbolic (not allowed for type
                                variables or module language
                                identifiers)
<i>symbol</i> &rarr; <b>!</b>
    | <b>%</b>
    | <b>&amp;</b>
    | <b>$</b>
    | <b>#</b>
    | <b>+</b>
    | <b>-</b>
    | <b>/</b>
    | <b>:</b>
    | <b>&lt;</b>
    | <b>=</b>
    | <b>&gt;</b>
    | <b>?</b>
    | <b>@</b>
    | <b>\</b>
    | <b>~</b>
    | <b>`</b>
    | <b>^</b>
    | '<b>|</b>'
    | '<b>*</b>'
<i>var</i> &rarr; '''(<i>letter</i> | <i>digit</i> | ''' | <b>_</b>)*
                                unconstrained
      ''''(<i>letter</i> | <i>digit</i> | ''' | <b>_</b>⟩*
                                equality
<i>lab</i> &rarr; <i>id</i>                        identifier
      <i>num</i>                       number (may not start with 0)
</pre>

### Expressions

<pre>
<i>exp</i> &rarr; <i>con</i>                       constant
    | [ <b>op</b> ] <i>id</i>                 value or constructor identifier
    | <i>exp<sub>1</sub></i> <i>exp<sub>2</sub></i>                 application
    | <i>exp<sub>1</sub></i> <i>id</i> <i>exp<sub>2</sub></i>              infix application
    | '<b>(</b>' <i>exp</i> '<b>)</b>'               parentheses
    | '<b>(</b>' <i>exp<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>exp<sub>n</sub></i> '<b>)</b>' tuple (n &ne; 1)
    | <b>{</b> [ <i>exprow</i> ] <b>}</b>            record
    | <b>#</b><i>lab</i>                      record selector
    | '<b>[</b>' <i>exp<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>exp<sub>n</sub></i> '<b>]</b>' list (n &ge; 0)
    | '<b>(</b>' <i>exp<sub>1</sub></i> <b>;</b> ... <b>;</b> <i>exp<sub>n</sub></i> '<b>)</b>' sequence (n &ge; 2)
    | <b>let</b> <i>dec</i> <b>in</b> <i>exp<sub>1</sub></i> ; ... ; <i>exp<sub>n</sub></i> <b>end</b>
                                local declaration (n ≥ 1)
    | <i>exp</i> <b>.</b> <i>lab</i> <b>()</b>              postfix call (no argument)
    | <i>exp<sub>1</sub></i> <b>.</b> <i>lab</i> <i>exp<sub>2</sub></i>            postfix call (with argument)
    | <i>exp</i> <b>:</b> <i>type</i>                type annotation
    | <i>exp<sub>1</sub></i> <b>andalso</b> <i>exp<sub>2</sub></i>         conjunction
    | <i>exp<sub>1</sub></i> <b>orelse</b> <i>exp<sub>2</sub></i>          disjunction
    | <b>if</b> <i>exp<sub>1</sub></i> <b>then</b> <i>exp<sub>2</sub></i> <b>else</b> <i>exp<sub>3</sub></i>
                                conditional
    | <b>case</b> <i>exp</i> <b>of</b> <i>match</i>         case analysis
    | <b>fn</b> <i>match</i>                  function
    | <b>current</b>                   current element (only valid in a query step)
    | <b>elements</b>                  elements of current group (only valid in compute)
    | <b>ordinal</b>                   element ordinal (only valid in a query step)
    | <i>exp<sub>1</sub></i> <b>over</b> <i>exp<sub>2</sub></i>            aggregate (only valid in compute)
    | <b>from</b> [ <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i> ] <i>step<sub>1</sub></i> ... <i>step<sub>t</sub></i> [ <i>terminalStep</i> ]
                                relational expression (<i>s</i> &ge; 0, <i>t</i> &ge; 0)
    | <b>exists</b> [ <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i> ] <i>step<sub>1</sub></i> ... <i>step<sub>t</sub></i>
                                existential quantification (<i>s</i> &ge; 0, <i>t</i> &ge; 0)
    | <b>forall</b> [ <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i> ] <i>step<sub>1</sub></i> ... <i>step<sub>t</sub></i> <b>require</b> <i>exp</i>
                                universal quantification (<i>s</i> &ge; 0, <i>t</i> &ge; 0)
<i>exprow</i> &rarr; [ <i>exp</i> <b>with</b> ] <i>exprowItem</i> [<b>,</b> <i>exprowItem</i> ]*
                                expression row
<i>exprowItem</i> &rarr; [ <i>lab</i> <b>=</b> ] <i>exp</i>
<i>match</i> &rarr; <i>matchItem</i> [ '<b>|</b>' <i>matchItem</i> ]*
                                match
<i>matchItem</i> &rarr; <i>pat</i> <b>=&gt;</b> <i>exp</i>
<i>scan</i> &rarr; <i>pat</i> <b>in</b> <i>exp</i> [ <b>on</b> <i>exp</i> ]    iteration
    | <i>pat</i> <b>=</b> <i>exp</i>                 single iteration
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

### Patterns

<pre>
<i>pat</i> &rarr; <i>con</i>                       constant
    | <b>_</b>                         wildcard
    | [ <b>op</b> ] <i>id</i>                 variable
    | [ <b>op</b> ] <i>id</i> [ <i>pat</i> ]         construction
    | <i>pat<sub>1</sub></i> <i>id</i> <i>pat<sub>2</sub></i>              infix construction
    | '<b>(</b>' <i>pat</i> '<b>)</b>'               parentheses
    | '<b>(</b>' <i>pat<sub>1</sub></i> , ... , <i>pat<sub>n</sub></i> '<b>)</b>' tuple (n &ne; 1)
    | <b>{</b> [ <i>patrow</i> ] <b>}</b>            record
    | '<b>[</b>' <i>pat<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>pat<sub>n</sub></i> '<b>]</b>' list (n &ge; 0)
    | <i>pat</i> <b>:</b> <i>type</i>                type annotation
    | <i>id</i> <b>as</b> <i>pat</i>                 layered
<i>patrow</i> &rarr; '<b>...</b>'                  wildcard
    | <i>lab</i> <b>=</b> <i>pat</i> [<b>,</b> <i>patrow</i>]      pattern
    | <i>id</i> [<b>,</b> <i>patrow</i>]             label as variable
</pre>

### Types

<pre>
<i>typ</i> &rarr; <i>var</i>                       variable
    | [ <i>typ</i> ] <i>id</i>                constructor
    | '<b>(</b>' <i>typ</i> [<b>,</b> <i>typ</i> ]* '<b>)</b>' <i>id</i>  constructor
    | '<b>(</b>' <i>typ</i> '<b>)</b>'               parentheses
    | <i>typ<sub>1</sub></i> <b>-&gt;</b> <i>typ<sub>2</sub></i>              function
    | <i>typ<sub>1</sub></i> '<b>*</b>' ... '<b>*</b>' <i>typ<sub>n</sub></i>     tuple (n &ge; 2)
    | <b>{</b> [ <i>typrow</i> ] <b>}</b>            record
    | <b>typeof</b> <i>exp</i>                expression type
<i>typrow</i> &rarr; <i>lab</i> : <i>typ</i> [, <i>typrow</i>]   type row
</pre>

### Declarations

<pre>
<i>dec</i> &rarr; <i>vals</i> <i>valbind</i>              value
    | <b>fun</b> <i>funbind</i>               function
    | <b>type</b> <i>typbind</i>              type
    | <b>datatype</b> <i>datbind</i>          data type
    | <b>signature</b> <i>sigbind</i>         signature
    | <b>over</b> <i>id</i>                   overloaded name
    | <i>empty</i>
    | <i>dec<sub>1</sub></i> [<b>;</b>] <i>dec<sub>2</sub></i>             sequence
<i>valbind</i> &rarr; <i>pat</i> <b>=</b> <i>exp</i> [ <b>and</b> <i>valbind</i> ]*
                                destructuring
    | <b>rec</b> <i>valbind</i>               recursive
    | <b>inst</b> <i>valbind</i>              overload instance
<i>funbind</i> &rarr; <i>funmatch</i> [ <b>and</b> <i>funmatch</i> ]*
                                clausal function
<i>funmatch</i> &rarr; <i>funmatchItem</i> [ '<b>|</b>' funmatchItem ]*
<i>funmatchItem</i> &rarr; [ <b>op</b> ] <i>id</i> <i>pat<sub>1</sub></i> ... <i>pat<sub>n</sub></i> [ <b>:</b> <i>type</i> ] <b>=</b> <i>exp</i>
                                nonfix (n &ge; 1)
    | <i>pat<sub>1</sub></i> <i>id</i> <i>pat<sub>2</sub></i> [ <b>:</b> <i>type</i> ] <b>=</b> <i>exp</i>
                                infix
    | '<b>(</b>' <i>pat<sub>1</sub></i> <i>id</i> <i>pat<sub>2</sub></i> '<b>)</b>' <i>pat'<sub>1</sub></i> ... <i>pat'<sub>n</sub></i> [ <b>:</b> <i>type</i> ] = <i>exp</i>
                                infix (n &ge; 0)
<i>typbind</i> &rarr; [ <i>vars</i> ] <i>id</i> <b>=</b> <i>typ</i> [ <b>and</b> <i>typbind</i> ]*
                                abbreviation
<i>datbind</i> &rarr; <i>datbindItem</i> [ <b>and</b> <i>datbindItem</i> ]*
                                data type
<i>datbindItem</i> &rarr; [ <i>vars</i> ] <i>id</i> <b>=</b> <i>conbind</i>
<i>conbind</i> &rarr; <i>conbindItem</i> [ '<b>|</b>' <i>conbindItem</i> ]*
                                data constructor
<i>conbindItem</i> &rarr; <i>id</i> [ <b>of</b> <i>typ</i> ]
<i>vals</i> &rarr; <i>val</i>
    | '<b>(</b>' <i>val</i> [<b>,</b> <i>val</i>]* '<b>)</b>'
<i>vars</i> &rarr; <i>var</i>
    | '<b>(</b>' <i>var</i> [<b>,</b> <i>var</i>]* '<b>)</b>'
</pre>

### Modules

<pre>
<i>sigbind</i> &rarr; <i>id</i> <b>=</b> <b>sig</b> <i>spec</i> <b>end</b> [ <b>and</b> <i>sigbind</i> ]*
                                signature
<i>spec</i> &rarr; <b>val</b> <i>valdesc</i>              value
    | <b>type</b> <i>typdesc</i>              abstract type
    | <b>type</b> <i>typbind</i>              type abbreviation
    | <b>datatype</b> <i>datdesc</i>          data type
    | <b>exception</b> <i>exndesc</i>         exception
    | <i>empty</i>
    | <i>spec<sub>1</sub></i> [<b>;</b>] <i>spec<sub>2</sub></i>           sequence
<i>valdesc</i> &rarr; <i>id</i> <b>:</b> <i>typ</i> [ <b>and</b> <i>valdesc</i> ]*
                                value specification
<i>typdesc</i> &rarr; [ <i>vars</i> ] <i>id</i> [ <b>and</b> <i>typdesc</i> ]*
                                type specification
<i>datdesc</i> &rarr; <i>datdescItem</i> [ <b>and</b> <i>datdescItem</i> ]*
                                datatype specification
<i>datdescItem</i> &rarr; [ <i>vars</i> ] <i>id</i> <b>=</b> <i>conbind</i>
<i>exndesc</i> &rarr; <i>id</i> [ <b>of</b> <i>typ</i> ] [ <b>and</b> <i>exndesc</i> ]*
                                exception specification
</pre>

A **signature** defines an interface that specifies types,
values, datatypes, and exceptions without providing
implementations. Signatures are used to document module
interfaces and, in future versions of Morel, will be used to
constrain structure implementations.

Signature declarations appear at the top level (see grammar in
[Declarations](#declarations)).

#### Specifications

A signature body contains **specifications** that describe the
interface:

**Value specifications** declare the type of a value without
defining it:
```sml
val empty : 'a stack
val push : 'a * 'a stack -> 'a stack
```

**Type specifications** can be abstract (no definition) or
concrete (type alias):
```sml
type 'a stack              (* abstract type *)
type point = real * real   (* concrete type alias *)
type ('k, 'v) map          (* abstract with multiple params *)
```

**Datatype specifications** describe algebraic datatypes:
```sml
datatype 'a tree = Leaf | Node of 'a * 'a tree * 'a tree
```

**Exception specifications** declare exceptions:
```sml
exception Empty                  (* exception without payload *)
exception QueueError of string   (* exception with payload *)
```

#### Examples

A simple signature with abstract type and value specifications:

```sml
signature STACK =
sig
  type 'a stack
  exception Empty
  val empty : 'a stack
  val isEmpty : 'a stack -> bool
  val push : 'a * 'a stack -> 'a stack
  val pop : 'a stack -> 'a stack
  val top : 'a stack -> 'a
end
```

Multiple signatures declared together using `and`:

```sml
signature EQ =
sig
  type t
  val eq : t * t -> bool
end
and ORD =
sig
  type t
  val lt : t * t -> bool
  val le : t * t -> bool
end
```

#### Current Limitations

The current implementation supports parsing and
pretty-printing signatures but does not yet support:
* Structure declarations that implement signatures
* Signature refinement (`where type`)
* Signature sharing constraints
* Signature inclusion (`include`)

These features may be added in future versions.

### Notation

This grammar uses the following notation:

| Syntax      | Meaning |
| ----------- | ------- |
| *symbol*    | Grammar symbol (e.g. *con*) |
| **keyword** | Morel keyword (e.g. **if**) and symbol (e.g. **~**, "**(**") |
| \[ term \]  | Option: term may occur 0 or 1 times |
| \[ term1 \| term2 \] | Alternative: term1 may occur, or term2 may occur, or neither |
| term*       | Repetition: term may occur 0 or more times |
| 's'         | Quotation: Symbols used in the grammar &mdash; ( ) \[ \] \| * ... &mdash; are quoted when they appear in Morel language |

## Built-in operators

| Operator | Precedence | Meaning |
| :------- | ---------: | :------ |
| *        |    infix 7 | Multiplication |
| /        |    infix 7 | Division |
| div      |    infix 7 | Integer division |
| mod      |    infix 7 | Modulo |
| +        |    infix 6 | Plus |
| -        |    infix 6 | Minus |
| ^        |    infix 6 | String concatenate |
| ~        |   prefix 6 | Negate |
| ::       |   infixr 5 | List cons |
| @        |   infixr 5 | List append |
| &lt;=    |    infix 4 | Less than or equal |
| &lt;     |    infix 4 | Less than |
| &gt;=    |    infix 4 | Greater than or equal |
| &gt;     |    infix 4 | Greater than |
| =        |    infix 4 | Equal |
| &lt;&gt; |    infix 4 | Not equal |
| elem     |    infix 4 | Member of list |
| notelem  |    infix 4 | Not member of list |
| :=       |    infix 3 | Assign |
| o        |    infix 3 | Compose |
| andalso  |    infix 2 | Logical and |
| orelse   |    infix 1 | Logical or |
| implies  |    infix 0 | Logical implication |

`abs` is a built-in function (not an operator, because it uses function
syntax rather than prefix or infix syntax). It is overloaded: its type is
`int -> int` when applied to an `int` argument, and `real -> real` when
applied to a `real` argument. It is equivalent to `Int.abs` and `Real.abs`
respectively.

## Built-in types

Primitive: `bool`, `char`, `int`, `real`, `string`, `unit`

Datatype:
* `datatype 'a descending = DESC of 'a` (in structure `Relational`)
* `datatype ('l, 'r) either = INL of 'l | INR of 'r` (in structure `Either`)
* `datatype 'a list = nil | :: of 'a * 'a list` (in structure `List`)
* `datatype 'a option = NONE | SOME of 'a` (in structure `Option`)
* `datatype 'a order = LESS | EQUAL | GREATER`
  (in structure `General`)

Eqtype:
* `eqtype 'a bag = 'a bag` (in structure `Bag`)
* `eqtype 'a vector = 'a vector` (in structure `Vector`)

Exception:
* `Bind` (in structure `General`)
* `Chr` (in structure `General`)
* `Div` (in structure `General`)
* `Domain` (in structure `General`)
* `Empty` (in structure `List`)
* `Error` (in structure `Interact`)
* `Option` (in structure `Option`)
* `Overflow` (in structure `Option`)
* `Size` (in structure `General`)
* `Subscript` (in structure `General`)
* `Unordered` (in structure `IEEEReal`)

## Structures

[//]: # (start:structures)

| Structure | Description |
| --------- | ----------- |
| [Bag](lib/bag.md) | Unordered collection of elements with duplicates.<br>[`@`](lib/bag.md#@-impl), [`all`](lib/bag.md#all-impl), [`app`](lib/bag.md#app-impl), [`collate`](lib/bag.md#collate-impl), [`concat`](lib/bag.md#concat-impl), [`drop`](lib/bag.md#drop-impl), [`exists`](lib/bag.md#exists-impl), [`filter`](lib/bag.md#filter-impl), [`find`](lib/bag.md#find-impl), [`fold`](lib/bag.md#fold-impl), [`fromList`](lib/bag.md#fromList-impl), [`getItem`](lib/bag.md#getItem-impl), [`hd`](lib/bag.md#hd-impl), [`length`](lib/bag.md#length-impl), [`map`](lib/bag.md#map-impl), [`mapPartial`](lib/bag.md#mapPartial-impl), [`nil`](lib/bag.md#nil-impl), [`nth`](lib/bag.md#nth-impl), [`null`](lib/bag.md#null-impl), [`partition`](lib/bag.md#partition-impl), [`tabulate`](lib/bag.md#tabulate-impl), [`take`](lib/bag.md#take-impl), [`tl`](lib/bag.md#tl-impl), [`toList`](lib/bag.md#toList-impl) |
| [Bool](lib/bool.md) | Boolean values and operations.<br>[`bool`](lib/bool.md#bool-impl), [`fromString`](lib/bool.md#fromString-impl), [`implies`](lib/bool.md#implies-impl), [`not`](lib/bool.md#not-impl), [`toString`](lib/bool.md#toString-impl) |
| [Char](lib/char.md) | Character values and operations.<br>[`char`](lib/char.md#char-impl), [`<`](lib/char.md#<-impl), [`<=`](lib/char.md#<=-impl), [`>`](lib/char.md#>-impl), [`>=`](lib/char.md#>=-impl), [`chr`](lib/char.md#chr-impl), [`compare`](lib/char.md#compare-impl), [`contains`](lib/char.md#contains-impl), [`fromCString`](lib/char.md#fromCString-impl), [`fromInt`](lib/char.md#fromInt-impl), [`fromString`](lib/char.md#fromString-impl), [`isAlpha`](lib/char.md#isAlpha-impl), [`isAlphaNum`](lib/char.md#isAlphaNum-impl), [`isAscii`](lib/char.md#isAscii-impl), [`isCntrl`](lib/char.md#isCntrl-impl), [`isDigit`](lib/char.md#isDigit-impl), [`isGraph`](lib/char.md#isGraph-impl), [`isHexDigit`](lib/char.md#isHexDigit-impl), [`isLower`](lib/char.md#isLower-impl), [`isOctDigit`](lib/char.md#isOctDigit-impl), [`isPrint`](lib/char.md#isPrint-impl), [`isPunct`](lib/char.md#isPunct-impl), [`isSpace`](lib/char.md#isSpace-impl), [`isUpper`](lib/char.md#isUpper-impl), [`maxChar`](lib/char.md#maxChar-impl), [`maxOrd`](lib/char.md#maxOrd-impl), [`minChar`](lib/char.md#minChar-impl), [`notContains`](lib/char.md#notContains-impl), [`ord`](lib/char.md#ord-impl), [`pred`](lib/char.md#pred-impl), [`succ`](lib/char.md#succ-impl), [`toCString`](lib/char.md#toCString-impl), [`toLower`](lib/char.md#toLower-impl), [`toString`](lib/char.md#toString-impl), [`toUpper`](lib/char.md#toUpper-impl) |
| [Datalog](lib/datalog.md) | Datalog query interface.<br>[`execute`](lib/datalog.md#execute-impl), [`translate`](lib/datalog.md#translate-impl), [`validate`](lib/datalog.md#validate-impl) |
| [Either](lib/either.md) | Values that are one of two types.<br>[`either`](lib/either.md#either-impl), [`app`](lib/either.md#app-impl), [`appLeft`](lib/either.md#appLeft-impl), [`appRight`](lib/either.md#appRight-impl), [`asLeft`](lib/either.md#asLeft-impl), [`asRight`](lib/either.md#asRight-impl), [`fold`](lib/either.md#fold-impl), [`isLeft`](lib/either.md#isLeft-impl), [`isRight`](lib/either.md#isRight-impl), [`map`](lib/either.md#map-impl), [`mapLeft`](lib/either.md#mapLeft-impl), [`mapRight`](lib/either.md#mapRight-impl), [`partition`](lib/either.md#partition-impl), [`proj`](lib/either.md#proj-impl) |
| [Fn](lib/fn.md) | Higher-order function combinators.<br>[`apply`](lib/fn.md#apply-impl), [`const`](lib/fn.md#const-impl), [`curry`](lib/fn.md#curry-impl), [`equal`](lib/fn.md#equal-impl), [`flip`](lib/fn.md#flip-impl), [`id`](lib/fn.md#id-impl), [`notEqual`](lib/fn.md#notEqual-impl), [`o`](lib/fn.md#o-impl), [`repeat`](lib/fn.md#repeat-impl), [`uncurry`](lib/fn.md#uncurry-impl) |
| [General](lib/general.md) | Basic types, exceptions, and utility functions.<br>[`exn`](lib/general.md#exn-impl), [`order`](lib/general.md#order-impl), [`unit`](lib/general.md#unit-impl), [`Bind`](lib/general.md#Bind-impl), [`Chr`](lib/general.md#Chr-impl), [`Div`](lib/general.md#Div-impl), [`Domain`](lib/general.md#Domain-impl), [`Fail`](lib/general.md#Fail-impl), [`Match`](lib/general.md#Match-impl), [`Overflow`](lib/general.md#Overflow-impl), [`Size`](lib/general.md#Size-impl), [`Span`](lib/general.md#Span-impl), [`Subscript`](lib/general.md#Subscript-impl), [`before`](lib/general.md#before-impl), [`exnMessage`](lib/general.md#exnMessage-impl), [`exnName`](lib/general.md#exnName-impl), [`ignore`](lib/general.md#ignore-impl), [`o`](lib/general.md#o-impl) |
| [IEEEReal](lib/ieee-real.md) | IEEE 754 floating-point definitions.<br>[`decimal_approx`](lib/ieee-real.md#decimal_approx-impl), [`float_class`](lib/ieee-real.md#float_class-impl), [`real_order`](lib/ieee-real.md#real_order-impl), [`rounding_mode`](lib/ieee-real.md#rounding_mode-impl) |
| [Int](lib/int.md) | Fixed-precision integer operations.<br>[`int`](lib/int.md#int-impl), [`*`](lib/int.md#*-impl), [`+`](lib/int.md#+-impl), [`-`](lib/int.md#--impl), [`<`](lib/int.md#<-impl), [`<=`](lib/int.md#<=-impl), [`>`](lib/int.md#>-impl), [`>=`](lib/int.md#>=-impl), [`abs`](lib/int.md#abs-impl), [`compare`](lib/int.md#compare-impl), [`div`](lib/int.md#div-impl), [`fmt`](lib/int.md#fmt-impl), [`fromInt`](lib/int.md#fromInt-impl), [`fromLarge`](lib/int.md#fromLarge-impl), [`fromString`](lib/int.md#fromString-impl), [`max`](lib/int.md#max-impl), [`maxInt`](lib/int.md#maxInt-impl), [`min`](lib/int.md#min-impl), [`minInt`](lib/int.md#minInt-impl), [`mod`](lib/int.md#mod-impl), [`precision`](lib/int.md#precision-impl), [`quot`](lib/int.md#quot-impl), [`rem`](lib/int.md#rem-impl), [`sameSign`](lib/int.md#sameSign-impl), [`scan`](lib/int.md#scan-impl), [`sign`](lib/int.md#sign-impl), [`toInt`](lib/int.md#toInt-impl), [`toLarge`](lib/int.md#toLarge-impl), [`toString`](lib/int.md#toString-impl), [`~`](lib/int.md#~-impl) |
| [IntInf](lib/int-inf.md) | Arbitrary-precision integer operations.<br>[`int`](lib/int-inf.md#int-impl) |
| [Interact](lib/interact.md) | Interactive session utilities.<br>[`use`](lib/interact.md#use-impl), [`useSilently`](lib/interact.md#useSilently-impl) |
| [List](lib/list.md) | Polymorphic singly-linked lists.<br>[`list`](lib/list.md#list-impl), [`Empty`](lib/list.md#Empty-impl), [`@`](lib/list.md#@-impl), [`all`](lib/list.md#all-impl), [`app`](lib/list.md#app-impl), [`at`](lib/list.md#at-impl), [`collate`](lib/list.md#collate-impl), [`concat`](lib/list.md#concat-impl), [`drop`](lib/list.md#drop-impl), [`except`](lib/list.md#except-impl), [`exists`](lib/list.md#exists-impl), [`filter`](lib/list.md#filter-impl), [`find`](lib/list.md#find-impl), [`foldl`](lib/list.md#foldl-impl), [`foldr`](lib/list.md#foldr-impl), [`getItem`](lib/list.md#getItem-impl), [`hd`](lib/list.md#hd-impl), [`intersect`](lib/list.md#intersect-impl), [`last`](lib/list.md#last-impl), [`length`](lib/list.md#length-impl), [`map`](lib/list.md#map-impl), [`mapPartial`](lib/list.md#mapPartial-impl), [`mapi`](lib/list.md#mapi-impl), [`nil`](lib/list.md#nil-impl), [`nth`](lib/list.md#nth-impl), [`null`](lib/list.md#null-impl), [`partition`](lib/list.md#partition-impl), [`rev`](lib/list.md#rev-impl), [`revAppend`](lib/list.md#revAppend-impl), [`tabulate`](lib/list.md#tabulate-impl), [`take`](lib/list.md#take-impl), [`tl`](lib/list.md#tl-impl) |
| [ListPair](lib/list-pair.md) | Operations on pairs of lists.<br>[`UnequalLengths`](lib/list-pair.md#UnequalLengths-impl), [`all`](lib/list-pair.md#all-impl), [`allEq`](lib/list-pair.md#allEq-impl), [`app`](lib/list-pair.md#app-impl), [`appEq`](lib/list-pair.md#appEq-impl), [`exists`](lib/list-pair.md#exists-impl), [`foldl`](lib/list-pair.md#foldl-impl), [`foldlEq`](lib/list-pair.md#foldlEq-impl), [`foldr`](lib/list-pair.md#foldr-impl), [`foldrEq`](lib/list-pair.md#foldrEq-impl), [`map`](lib/list-pair.md#map-impl), [`mapEq`](lib/list-pair.md#mapEq-impl), [`unzip`](lib/list-pair.md#unzip-impl), [`zip`](lib/list-pair.md#zip-impl), [`zipEq`](lib/list-pair.md#zipEq-impl) |
| [Math](lib/math.md) | Mathematical functions for real numbers.<br>[`acos`](lib/math.md#acos-impl), [`asin`](lib/math.md#asin-impl), [`atan`](lib/math.md#atan-impl), [`atan2`](lib/math.md#atan2-impl), [`cos`](lib/math.md#cos-impl), [`cosh`](lib/math.md#cosh-impl), [`e`](lib/math.md#e-impl), [`exp`](lib/math.md#exp-impl), [`ln`](lib/math.md#ln-impl), [`log10`](lib/math.md#log10-impl), [`pi`](lib/math.md#pi-impl), [`pow`](lib/math.md#pow-impl), [`sin`](lib/math.md#sin-impl), [`sinh`](lib/math.md#sinh-impl), [`sqrt`](lib/math.md#sqrt-impl), [`tan`](lib/math.md#tan-impl), [`tanh`](lib/math.md#tanh-impl) |
| [Option](lib/option.md) | Optional values.<br>[`option`](lib/option.md#option-impl), [`Option`](lib/option.md#Option-impl), [`app`](lib/option.md#app-impl), [`compose`](lib/option.md#compose-impl), [`composePartial`](lib/option.md#composePartial-impl), [`filter`](lib/option.md#filter-impl), [`getOpt`](lib/option.md#getOpt-impl), [`isSome`](lib/option.md#isSome-impl), [`join`](lib/option.md#join-impl), [`map`](lib/option.md#map-impl), [`mapPartial`](lib/option.md#mapPartial-impl), [`valOf`](lib/option.md#valOf-impl) |
| [Real](lib/real.md) | Floating-point number operations.<br>[`real`](lib/real.md#real-impl), [`!=`](lib/real.md#!=-impl), [`*`](lib/real.md#*-impl), [`*+`](lib/real.md#*+-impl), [`*-`](lib/real.md#*--impl), [`+`](lib/real.md#+-impl), [`-`](lib/real.md#--impl), [`/`](lib/real.md#/-impl), [`<`](lib/real.md#<-impl), [`<=`](lib/real.md#<=-impl), [`==`](lib/real.md#==-impl), [`>`](lib/real.md#>-impl), [`>=`](lib/real.md#>=-impl), [`?=`](lib/real.md#?=-impl), [`abs`](lib/real.md#abs-impl), [`ceil`](lib/real.md#ceil-impl), [`checkFloat`](lib/real.md#checkFloat-impl), [`class`](lib/real.md#class-impl), [`compare`](lib/real.md#compare-impl), [`compareReal`](lib/real.md#compareReal-impl), [`copySign`](lib/real.md#copySign-impl), [`floor`](lib/real.md#floor-impl), [`fmt`](lib/real.md#fmt-impl), [`fromDecimal`](lib/real.md#fromDecimal-impl), [`fromInt`](lib/real.md#fromInt-impl), [`fromLarge`](lib/real.md#fromLarge-impl), [`fromLargeInt`](lib/real.md#fromLargeInt-impl), [`fromManExp`](lib/real.md#fromManExp-impl), [`fromString`](lib/real.md#fromString-impl), [`isFinite`](lib/real.md#isFinite-impl), [`isNan`](lib/real.md#isNan-impl), [`isNormal`](lib/real.md#isNormal-impl), [`max`](lib/real.md#max-impl), [`maxFinite`](lib/real.md#maxFinite-impl), [`min`](lib/real.md#min-impl), [`minNormalPos`](lib/real.md#minNormalPos-impl), [`minPos`](lib/real.md#minPos-impl), [`negInf`](lib/real.md#negInf-impl), [`nextAfter`](lib/real.md#nextAfter-impl), [`posInf`](lib/real.md#posInf-impl), [`precision`](lib/real.md#precision-impl), [`radix`](lib/real.md#radix-impl), [`realCeil`](lib/real.md#realCeil-impl), [`realFloor`](lib/real.md#realFloor-impl), [`realMod`](lib/real.md#realMod-impl), [`realRound`](lib/real.md#realRound-impl), [`realTrunc`](lib/real.md#realTrunc-impl), [`rem`](lib/real.md#rem-impl), [`round`](lib/real.md#round-impl), [`sameSign`](lib/real.md#sameSign-impl), [`scan`](lib/real.md#scan-impl), [`sign`](lib/real.md#sign-impl), [`signBit`](lib/real.md#signBit-impl), [`split`](lib/real.md#split-impl), [`toDecimal`](lib/real.md#toDecimal-impl), [`toInt`](lib/real.md#toInt-impl), [`toLarge`](lib/real.md#toLarge-impl), [`toLargeInt`](lib/real.md#toLargeInt-impl), [`toManExp`](lib/real.md#toManExp-impl), [`toString`](lib/real.md#toString-impl), [`trunc`](lib/real.md#trunc-impl), [`unordered`](lib/real.md#unordered-impl), [`~`](lib/real.md#~-impl) |
| [Relational](lib/relational.md) | Relational algebra operations for Morel queries.<br>[`descending`](lib/relational.md#descending-impl), [`compare`](lib/relational.md#compare-impl), [`count`](lib/relational.md#count-impl), [`elem`](lib/relational.md#elem-impl), [`empty`](lib/relational.md#empty-impl), [`iterate`](lib/relational.md#iterate-impl), [`max`](lib/relational.md#max-impl), [`min`](lib/relational.md#min-impl), [`nonEmpty`](lib/relational.md#nonEmpty-impl), [`notelem`](lib/relational.md#notelem-impl), [`only`](lib/relational.md#only-impl), [`sum`](lib/relational.md#sum-impl) |
| [String](lib/string.md) | String operations.<br>[`string`](lib/string.md#string-impl), [`<`](lib/string.md#<-impl), [`<=`](lib/string.md#<=-impl), [`>`](lib/string.md#>-impl), [`>=`](lib/string.md#>=-impl), [`^`](lib/string.md#^-impl), [`collate`](lib/string.md#collate-impl), [`compare`](lib/string.md#compare-impl), [`concat`](lib/string.md#concat-impl), [`concatWith`](lib/string.md#concatWith-impl), [`explode`](lib/string.md#explode-impl), [`extract`](lib/string.md#extract-impl), [`fields`](lib/string.md#fields-impl), [`fromCString`](lib/string.md#fromCString-impl), [`fromString`](lib/string.md#fromString-impl), [`implode`](lib/string.md#implode-impl), [`isPrefix`](lib/string.md#isPrefix-impl), [`isSubstring`](lib/string.md#isSubstring-impl), [`isSuffix`](lib/string.md#isSuffix-impl), [`map`](lib/string.md#map-impl), [`maxSize`](lib/string.md#maxSize-impl), [`scan`](lib/string.md#scan-impl), [`size`](lib/string.md#size-impl), [`str`](lib/string.md#str-impl), [`sub`](lib/string.md#sub-impl), [`substring`](lib/string.md#substring-impl), [`toCString`](lib/string.md#toCString-impl), [`toString`](lib/string.md#toString-impl), [`tokens`](lib/string.md#tokens-impl), [`translate`](lib/string.md#translate-impl) |
| [StringCvt](lib/string-cvt.md) | String conversion utilities and types.<br>[`radix`](lib/string-cvt.md#radix-impl), [`reader`](lib/string-cvt.md#reader-impl), [`realfmt`](lib/string-cvt.md#realfmt-impl) |
| [Sys](lib/sys.md) | System interface utilities.<br>[`clearEnv`](lib/sys.md#clearEnv-impl), [`env`](lib/sys.md#env-impl), [`file`](lib/sys.md#file-impl), [`plan`](lib/sys.md#plan-impl), [`planEx`](lib/sys.md#planEx-impl), [`set`](lib/sys.md#set-impl), [`show`](lib/sys.md#show-impl), [`showAll`](lib/sys.md#showAll-impl), [`unset`](lib/sys.md#unset-impl) |
| [Time](lib/time.md) | Time values and operations.<br>[`time`](lib/time.md#time-impl), [`Time`](lib/time.md#Time-impl), [`+`](lib/time.md#+-impl), [`-`](lib/time.md#--impl), [`<`](lib/time.md#<-impl), [`<=`](lib/time.md#<=-impl), [`>`](lib/time.md#>-impl), [`>=`](lib/time.md#>=-impl), [`compare`](lib/time.md#compare-impl), [`fmt`](lib/time.md#fmt-impl), [`fromMicroseconds`](lib/time.md#fromMicroseconds-impl), [`fromMilliseconds`](lib/time.md#fromMilliseconds-impl), [`fromNanoseconds`](lib/time.md#fromNanoseconds-impl), [`fromReal`](lib/time.md#fromReal-impl), [`fromSeconds`](lib/time.md#fromSeconds-impl), [`fromString`](lib/time.md#fromString-impl), [`now`](lib/time.md#now-impl), [`toMicroseconds`](lib/time.md#toMicroseconds-impl), [`toMilliseconds`](lib/time.md#toMilliseconds-impl), [`toNanoseconds`](lib/time.md#toNanoseconds-impl), [`toReal`](lib/time.md#toReal-impl), [`toSeconds`](lib/time.md#toSeconds-impl), [`toString`](lib/time.md#toString-impl), [`zeroTime`](lib/time.md#zeroTime-impl) |
| [Variant](lib/variant.md) | Dynamically-typed variant values.<br>[`variant`](lib/variant.md#variant-impl), [`parse`](lib/variant.md#parse-impl), [`print`](lib/variant.md#print-impl) |
| [Vector](lib/vector.md) | Immutable fixed-length arrays.<br>[`vector`](lib/vector.md#vector-impl), [`all`](lib/vector.md#all-impl), [`app`](lib/vector.md#app-impl), [`appi`](lib/vector.md#appi-impl), [`collate`](lib/vector.md#collate-impl), [`concat`](lib/vector.md#concat-impl), [`exists`](lib/vector.md#exists-impl), [`find`](lib/vector.md#find-impl), [`findi`](lib/vector.md#findi-impl), [`foldl`](lib/vector.md#foldl-impl), [`foldli`](lib/vector.md#foldli-impl), [`foldr`](lib/vector.md#foldr-impl), [`foldri`](lib/vector.md#foldri-impl), [`fromList`](lib/vector.md#fromList-impl), [`length`](lib/vector.md#length-impl), [`map`](lib/vector.md#map-impl), [`mapi`](lib/vector.md#mapi-impl), [`maxLen`](lib/vector.md#maxLen-impl), [`sub`](lib/vector.md#sub-impl), [`tabulate`](lib/vector.md#tabulate-impl), [`update`](lib/vector.md#update-impl) |

[//]: # (end:structures)

## Properties

Each property is set using the function `Sys.set (name, value)`,
displayed using `Sys.show name`,
and unset using `Sys.unset name`.
`Sys.showAll ()` shows all properties and their values.

[//]: # (start:properties)

| Name                 | Type   | Default | Description |
| -------------------- | ------ | ------- | ----------- |
| banner               | string | Morel version ... | Startup banner message displayed when launching the Morel shell. |
| directory            | file   |         | Path of the directory that the 'file' variable maps to in this connection. |
| excludeStructures    | string | ^Test$  | Regular expression that controls which built-in structures are excluded from the environment. |
| hybrid               | bool   | false   | Whether to try to create a hybrid execution plan that uses Apache Calcite relational algebra. |
| inlinePassCount      | int    | 5       | Maximum number of inlining passes. |
| lineWidth            | int    | 79      | When printing, the length at which lines are wrapped. |
| matchCoverageEnabled | bool   | true    | Whether to check whether patterns are exhaustive and/or redundant. |
| now                  | string | null    | Overrides the current time. Value is an ISO-8601 string (e.g. '2024-01-01T00:00:00Z'). If not set, the system clock is used. |
| optionalInt          | int    | null    | For testing. |
| output               | enum   | classic | How values should be formatted. "classic" (the default) prints values in a compact nested format; "tabular" prints values in a table if their type is a list of records. |
| printDepth           | int    | 5       | When printing, the depth of nesting of recursive data structure at which ellipsis begins. |
| printLength          | int    | 12      | When printing, the length of lists at which ellipsis begins. |
| productName          | string | morel-java | Name of the Morel product. |
| productVersion       | string | 0.8.0   | Current version of Morel. |
| relationalize        | bool   | false   | Whether to convert to relational algebra. |
| scriptDirectory      | file   |         | Path of the directory where the 'use' command looks for scripts. When running a script, it is generally set to the directory that contains the script. |
| stringDepth          | int    | 70      | When printing, the length of strings at which ellipsis begins. |
| timeZone             | string | null    | Overrides the local timezone. Value is a timezone ID (e.g. 'UTC' or 'America/New_York'). If not set, the JVM default timezone is used. |

[//]: # (end:properties)
