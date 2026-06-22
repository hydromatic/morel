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
  `yield`,
  `yieldAll` steps and `in` and `of` keywords
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
* `with` functional update for record values (from OCaml)
* overloaded functions may be declared using `over` and `inst`
* attributes (`[@attr]` / `[@@attr]` / `[@@@attr]`) based on OCaml
* `(*)` line comments (syntax as SML/NJ and MLton)
* `(**` ... `*)` doc comments (from OCaml)

In Standard ML but not in Morel:
* `word` constant
* `longid` identifier
* references (`ref` and operators `!` and `:=`)
* exceptions: `handle` and user-defined `exception` declarations
  (`raise` and built-in exceptions are supported)
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
    | <b>raise</b> <i>exp</i>                 exception raising
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
    | <i>exp</i> <i>expAttr<sub>1</sub></i> ... <i>expAttr<sub>n</sub></i>
                                attributed expression (n &ge; 1)
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
    | [ <b>left</b> | <b>right</b> | <b>full</b> ] <b>join</b> <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i>
                                join step (<i>s</i> &ge; 1)
    | <b>order</b> <i>exp</i>                 order step
    | <b>skip</b> <i>exp</i>                  skip step
    | <b>take</b> <i>exp</i>                  take step
    | <b>through</b> <i>pat</i> <b>in</b> <i>exp</i>        through step
    | <b>union</b> [ <b>distinct</b> ] <i>exp<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>exp<sub>u</sub></i>
                                union step (<i>u</i> &ge; 1)
    | <b>where</b> <i>exp</i>                 filter step
    | <b>yield</b> <i>exp</i>                 yield step
    | <b>yieldAll</b> <i>exp</i>              yieldAll step
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
    | <i>typ</i> <i>expAttr<sub>1</sub></i> ... <i>expAttr<sub>n</sub></i>
                                attributed type (n &ge; 1)
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
    | <i>dec</i> <i>declAttr<sub>1</sub></i> ... <i>declAttr<sub>n</sub></i>
                                attributed declaration (n &ge; 1)
    | <i>floatingAttr</i>              floating attribute
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

### Attributes

<pre>
<i>expAttr</i> &rarr; '<b>[@</b>' <i>attrName</i> [ <i>payload</i> ] '<b>]</b>'
                                expression / type attribute
<i>declAttr</i> &rarr; '<b>[@@</b>' <i>attrName</i> [ <i>payload</i> ] '<b>]</b>'
                                declaration attribute
<i>floatingAttr</i> &rarr; '<b>[@@@</b>' <i>attrName</i> [ <i>payload</i> ] '<b>]</b>'
                                floating attribute
<i>attrName</i> &rarr; <i>id</i> [ <b>.</b> <i>id</i> ]*
                                dotted attribute name
<i>payload</i> &rarr; <i>exp</i>                   expression payload
    | <b>:</b> <i>type</i>                    type payload
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
| [Bag](lib/bag.md) | Unordered collection of elements with duplicates.<br>[`bag`](lib/bag.md#bag-impl), [`nil`](lib/bag.md#nil-impl), [`null`](lib/bag.md#null-impl), [`fromList`](lib/bag.md#fromList-impl), [`toList`](lib/bag.md#toList-impl), [`length`](lib/bag.md#length-impl), [`@`](lib/bag.md#@-impl), [`hd`](lib/bag.md#hd-impl), [`tl`](lib/bag.md#tl-impl), [`getItem`](lib/bag.md#getItem-impl), [`take`](lib/bag.md#take-impl), [`drop`](lib/bag.md#drop-impl), [`concat`](lib/bag.md#concat-impl), [`app`](lib/bag.md#app-impl), [`map`](lib/bag.md#map-impl), [`mapPartial`](lib/bag.md#mapPartial-impl), [`find`](lib/bag.md#find-impl), [`filter`](lib/bag.md#filter-impl), [`partition`](lib/bag.md#partition-impl), [`fold`](lib/bag.md#fold-impl), [`exists`](lib/bag.md#exists-impl), [`all`](lib/bag.md#all-impl), [`tabulate`](lib/bag.md#tabulate-impl), [`nth`](lib/bag.md#nth-impl), [`only`](lib/bag.md#only-impl) |
| [Bool](lib/bool.md) | Boolean values and operations.<br>[`bool`](lib/bool.md#bool-impl), [`not`](lib/bool.md#not-impl), [`toString`](lib/bool.md#toString-impl), [`fromString`](lib/bool.md#fromString-impl), [`andalso`](lib/bool.md#andalso-impl), [`orelse`](lib/bool.md#orelse-impl), [`implies`](lib/bool.md#implies-impl), [`=`](lib/bool.md#=-impl), [`<>`](lib/bool.md#<>-impl), [`<`](lib/bool.md#<-impl), [`>`](lib/bool.md#>-impl) |
| [Char](lib/char.md) | Character values and operations.<br>[`char`](lib/char.md#char-impl), [`string`](lib/char.md#string-impl), [`minChar`](lib/char.md#minChar-impl), [`maxChar`](lib/char.md#maxChar-impl), [`maxOrd`](lib/char.md#maxOrd-impl), [`ord`](lib/char.md#ord-impl), [`chr`](lib/char.md#chr-impl), [`succ`](lib/char.md#succ-impl), [`pred`](lib/char.md#pred-impl), [`compare`](lib/char.md#compare-impl), [`<`](lib/char.md#<-impl), [`<=`](lib/char.md#<=-impl), [`>`](lib/char.md#>-impl), [`>=`](lib/char.md#>=-impl), [`=`](lib/char.md#=-impl), [`<>`](lib/char.md#<>-impl), [`contains`](lib/char.md#contains-impl), [`notContains`](lib/char.md#notContains-impl), [`isAscii`](lib/char.md#isAscii-impl), [`toLower`](lib/char.md#toLower-impl), [`toUpper`](lib/char.md#toUpper-impl), [`isAlpha`](lib/char.md#isAlpha-impl), [`isAlphaNum`](lib/char.md#isAlphaNum-impl), [`isCntrl`](lib/char.md#isCntrl-impl), [`isDigit`](lib/char.md#isDigit-impl), [`isGraph`](lib/char.md#isGraph-impl), [`isHexDigit`](lib/char.md#isHexDigit-impl), [`isOctDigit`](lib/char.md#isOctDigit-impl), [`isLower`](lib/char.md#isLower-impl), [`isPrint`](lib/char.md#isPrint-impl), [`isSpace`](lib/char.md#isSpace-impl), [`isPunct`](lib/char.md#isPunct-impl), [`isUpper`](lib/char.md#isUpper-impl), [`toString`](lib/char.md#toString-impl), [`fromString`](lib/char.md#fromString-impl), [`fromInt`](lib/char.md#fromInt-impl), [`toCString`](lib/char.md#toCString-impl), [`fromCString`](lib/char.md#fromCString-impl), [`scan`](lib/char.md#scan-impl) |
| [Datalog](lib/datalog.md) | Datalog query interface.<br>[`execute`](lib/datalog.md#execute-impl), [`translate`](lib/datalog.md#translate-impl), [`validate`](lib/datalog.md#validate-impl) |
| [Date](lib/date.md) | Calendar date and time values.<br>[`date`](lib/date.md#date-impl), [`month`](lib/date.md#month-impl), [`weekday`](lib/date.md#weekday-impl), [`Date`](lib/date.md#Date-impl), [`compare`](lib/date.md#compare-impl), [`day`](lib/date.md#day-impl), [`fmt`](lib/date.md#fmt-impl), [`fromString`](lib/date.md#fromString-impl), [`fromTimeLocal`](lib/date.md#fromTimeLocal-impl), [`fromTimeUniv`](lib/date.md#fromTimeUniv-impl), [`hour`](lib/date.md#hour-impl), [`isDst`](lib/date.md#isDst-impl), [`localOffset`](lib/date.md#localOffset-impl), [`minute`](lib/date.md#minute-impl), [`second`](lib/date.md#second-impl), [`toString`](lib/date.md#toString-impl), [`toTime`](lib/date.md#toTime-impl), [`weekDay`](lib/date.md#weekDay-impl), [`year`](lib/date.md#year-impl), [`yearDay`](lib/date.md#yearDay-impl) |
| [Either](lib/either.md) | Values that are one of two types.<br>[`either`](lib/either.md#either-impl), [`isLeft`](lib/either.md#isLeft-impl), [`isRight`](lib/either.md#isRight-impl), [`asLeft`](lib/either.md#asLeft-impl), [`asRight`](lib/either.md#asRight-impl), [`map`](lib/either.md#map-impl), [`mapLeft`](lib/either.md#mapLeft-impl), [`mapRight`](lib/either.md#mapRight-impl), [`app`](lib/either.md#app-impl), [`appLeft`](lib/either.md#appLeft-impl), [`appRight`](lib/either.md#appRight-impl), [`fold`](lib/either.md#fold-impl), [`proj`](lib/either.md#proj-impl), [`partition`](lib/either.md#partition-impl) |
| [Fn](lib/fn.md) | Higher-order function combinators.<br>[`id`](lib/fn.md#id-impl), [`const`](lib/fn.md#const-impl), [`apply`](lib/fn.md#apply-impl), [`o`](lib/fn.md#o-impl), [`curry`](lib/fn.md#curry-impl), [`uncurry`](lib/fn.md#uncurry-impl), [`flip`](lib/fn.md#flip-impl), [`repeat`](lib/fn.md#repeat-impl), [`equal`](lib/fn.md#equal-impl), [`notEqual`](lib/fn.md#notEqual-impl) |
| [General](lib/general.md) | Basic types, exceptions, and utility functions.<br>[`unit`](lib/general.md#unit-impl), [`exn`](lib/general.md#exn-impl), [`order`](lib/general.md#order-impl), [`Bind`](lib/general.md#Bind-impl), [`Match`](lib/general.md#Match-impl), [`Chr`](lib/general.md#Chr-impl), [`Div`](lib/general.md#Div-impl), [`Domain`](lib/general.md#Domain-impl), [`Fail`](lib/general.md#Fail-impl), [`Overflow`](lib/general.md#Overflow-impl), [`Size`](lib/general.md#Size-impl), [`Span`](lib/general.md#Span-impl), [`Subscript`](lib/general.md#Subscript-impl), [`exnName`](lib/general.md#exnName-impl), [`exnMessage`](lib/general.md#exnMessage-impl), [`o`](lib/general.md#o-impl), [`before`](lib/general.md#before-impl), [`ignore`](lib/general.md#ignore-impl), [`!`](lib/general.md#!-impl) |
| [IEEEReal](lib/ieee-real.md) | <br> |
| [Int](lib/int.md) | Fixed-precision integer operations.<br>[`int`](lib/int.md#int-impl), [`toLarge`](lib/int.md#toLarge-impl), [`fromLarge`](lib/int.md#fromLarge-impl), [`toInt`](lib/int.md#toInt-impl), [`fromInt`](lib/int.md#fromInt-impl), [`precision`](lib/int.md#precision-impl), [`minInt`](lib/int.md#minInt-impl), [`maxInt`](lib/int.md#maxInt-impl), [`+`](lib/int.md#+-impl), [`-`](lib/int.md#--impl), [`*`](lib/int.md#*-impl), [`div`](lib/int.md#div-impl), [`mod`](lib/int.md#mod-impl), [`quot`](lib/int.md#quot-impl), [`rem`](lib/int.md#rem-impl), [`compare`](lib/int.md#compare-impl), [`<`](lib/int.md#<-impl), [`<=`](lib/int.md#<=-impl), [`>`](lib/int.md#>-impl), [`>=`](lib/int.md#>=-impl), [`~`](lib/int.md#~-impl), [`abs`](lib/int.md#abs-impl), [`min`](lib/int.md#min-impl), [`max`](lib/int.md#max-impl), [`sign`](lib/int.md#sign-impl), [`sameSign`](lib/int.md#sameSign-impl), [`fmt`](lib/int.md#fmt-impl), [`toString`](lib/int.md#toString-impl), [`fromString`](lib/int.md#fromString-impl) |
| [IntInf](lib/int-inf.md) | Arbitrary-precision integer operations.<br> |
| [Interact](lib/interact.md) | Interactive session utilities.<br>[`use`](lib/interact.md#use-impl), [`useSilently`](lib/interact.md#useSilently-impl) |
| [List](lib/list.md) | Polymorphic singly-linked lists.<br>[`list`](lib/list.md#list-impl), [`Empty`](lib/list.md#Empty-impl), [`null`](lib/list.md#null-impl), [`length`](lib/list.md#length-impl), [`@`](lib/list.md#@-impl), [`hd`](lib/list.md#hd-impl), [`tl`](lib/list.md#tl-impl), [`last`](lib/list.md#last-impl), [`getItem`](lib/list.md#getItem-impl), [`nth`](lib/list.md#nth-impl), [`only`](lib/list.md#only-impl), [`take`](lib/list.md#take-impl), [`drop`](lib/list.md#drop-impl), [`rev`](lib/list.md#rev-impl), [`concat`](lib/list.md#concat-impl), [`revAppend`](lib/list.md#revAppend-impl), [`app`](lib/list.md#app-impl), [`map`](lib/list.md#map-impl), [`mapPartial`](lib/list.md#mapPartial-impl), [`find`](lib/list.md#find-impl), [`filter`](lib/list.md#filter-impl), [`partition`](lib/list.md#partition-impl), [`foldl`](lib/list.md#foldl-impl), [`foldr`](lib/list.md#foldr-impl), [`exists`](lib/list.md#exists-impl), [`all`](lib/list.md#all-impl), [`tabulate`](lib/list.md#tabulate-impl), [`collate`](lib/list.md#collate-impl), [`except`](lib/list.md#except-impl), [`intersect`](lib/list.md#intersect-impl), [`mapi`](lib/list.md#mapi-impl) |
| [ListPair](lib/list-pair.md) | Operations on pairs of lists.<br>[`UnequalLengths`](lib/list-pair.md#UnequalLengths-impl), [`zip`](lib/list-pair.md#zip-impl), [`unzip`](lib/list-pair.md#unzip-impl), [`map`](lib/list-pair.md#map-impl), [`app`](lib/list-pair.md#app-impl), [`all`](lib/list-pair.md#all-impl), [`exists`](lib/list-pair.md#exists-impl), [`foldr`](lib/list-pair.md#foldr-impl), [`foldl`](lib/list-pair.md#foldl-impl), [`allEq`](lib/list-pair.md#allEq-impl), [`zipEq`](lib/list-pair.md#zipEq-impl), [`mapEq`](lib/list-pair.md#mapEq-impl), [`appEq`](lib/list-pair.md#appEq-impl), [`foldrEq`](lib/list-pair.md#foldrEq-impl), [`foldlEq`](lib/list-pair.md#foldlEq-impl) |
| [Math](lib/math.md) | Mathematical functions for real numbers.<br>[`real`](lib/math.md#real-impl), [`pi`](lib/math.md#pi-impl), [`e`](lib/math.md#e-impl), [`sqrt`](lib/math.md#sqrt-impl), [`sin`](lib/math.md#sin-impl), [`cos`](lib/math.md#cos-impl), [`tan`](lib/math.md#tan-impl), [`asin`](lib/math.md#asin-impl), [`acos`](lib/math.md#acos-impl), [`atan`](lib/math.md#atan-impl), [`atan2`](lib/math.md#atan2-impl), [`exp`](lib/math.md#exp-impl), [`pow`](lib/math.md#pow-impl), [`ln`](lib/math.md#ln-impl), [`log10`](lib/math.md#log10-impl), [`sinh`](lib/math.md#sinh-impl), [`cosh`](lib/math.md#cosh-impl), [`tanh`](lib/math.md#tanh-impl) |
| [Option](lib/option.md) | Optional values.<br>[`option`](lib/option.md#option-impl), [`Option`](lib/option.md#Option-impl), [`getOpt`](lib/option.md#getOpt-impl), [`isSome`](lib/option.md#isSome-impl), [`valOf`](lib/option.md#valOf-impl), [`filter`](lib/option.md#filter-impl), [`join`](lib/option.md#join-impl), [`app`](lib/option.md#app-impl), [`map`](lib/option.md#map-impl), [`mapPartial`](lib/option.md#mapPartial-impl), [`compose`](lib/option.md#compose-impl), [`composePartial`](lib/option.md#composePartial-impl) |
| [PP](lib/pp.md) | Wadler-Leijen pretty-printer.<br>[`doc`](lib/pp.md#doc-impl), [`empty`](lib/pp.md#empty-impl), [`line`](lib/pp.md#line-impl), [`lineBreak`](lib/pp.md#lineBreak-impl), [`softLine`](lib/pp.md#softLine-impl), [`softBreak`](lib/pp.md#softBreak-impl), [`hardLine`](lib/pp.md#hardLine-impl), [`text`](lib/pp.md#text-impl), [`beside`](lib/pp.md#beside-impl), [`nest`](lib/pp.md#nest-impl), [`group`](lib/pp.md#group-impl), [`align`](lib/pp.md#align-impl), [`hang`](lib/pp.md#hang-impl), [`indent`](lib/pp.md#indent-impl), [`hsep`](lib/pp.md#hsep-impl), [`vsep`](lib/pp.md#vsep-impl), [`sep`](lib/pp.md#sep-impl), [`hcat`](lib/pp.md#hcat-impl), [`vcat`](lib/pp.md#vcat-impl), [`cat`](lib/pp.md#cat-impl), [`fillSep`](lib/pp.md#fillSep-impl), [`fillCat`](lib/pp.md#fillCat-impl), [`punctuate`](lib/pp.md#punctuate-impl), [`encloseSep`](lib/pp.md#encloseSep-impl), [`parens`](lib/pp.md#parens-impl), [`braces`](lib/pp.md#braces-impl), [`brackets`](lib/pp.md#brackets-impl), [`render`](lib/pp.md#render-impl) |
| [Range](lib/range.md) | Operations on ranges of ordered values.<br>[`continuous_set`](lib/range.md#continuous_set-impl), [`discrete_set`](lib/range.md#discrete_set-impl), [`range`](lib/range.md#range-impl), [`contains`](lib/range.md#contains-impl), [`toBag`](lib/range.md#toBag-impl), [`toList`](lib/range.md#toList-impl), [`continuousSetOf`](lib/range.md#continuousSetOf-impl), [`discreteSetOf`](lib/range.md#discreteSetOf-impl), [`flatten`](lib/range.md#flatten-impl), [`ranges`](lib/range.md#ranges-impl), [`complement`](lib/range.md#complement-impl) |
| [Real](lib/real.md) | Floating-point number operations.<br>[`real`](lib/real.md#real-impl), [`radix`](lib/real.md#radix-impl), [`precision`](lib/real.md#precision-impl), [`maxFinite`](lib/real.md#maxFinite-impl), [`minPos`](lib/real.md#minPos-impl), [`minNormalPos`](lib/real.md#minNormalPos-impl), [`posInf`](lib/real.md#posInf-impl), [`negInf`](lib/real.md#negInf-impl), [`+`](lib/real.md#+-impl), [`-`](lib/real.md#--impl), [`*`](lib/real.md#*-impl), [`/`](lib/real.md#/-impl), [`rem`](lib/real.md#rem-impl), [`~`](lib/real.md#~-impl), [`abs`](lib/real.md#abs-impl), [`min`](lib/real.md#min-impl), [`max`](lib/real.md#max-impl), [`sign`](lib/real.md#sign-impl), [`signBit`](lib/real.md#signBit-impl), [`sameSign`](lib/real.md#sameSign-impl), [`copySign`](lib/real.md#copySign-impl), [`compare`](lib/real.md#compare-impl), [`<`](lib/real.md#<-impl), [`<=`](lib/real.md#<=-impl), [`>`](lib/real.md#>-impl), [`>=`](lib/real.md#>=-impl), [`=`](lib/real.md#=-impl), [`<>`](lib/real.md#<>-impl), [`unordered`](lib/real.md#unordered-impl), [`isFinite`](lib/real.md#isFinite-impl), [`isNan`](lib/real.md#isNan-impl), [`isNormal`](lib/real.md#isNormal-impl), [`toManExp`](lib/real.md#toManExp-impl), [`fromManExp`](lib/real.md#fromManExp-impl), [`split`](lib/real.md#split-impl), [`realMod`](lib/real.md#realMod-impl), [`checkFloat`](lib/real.md#checkFloat-impl), [`realFloor`](lib/real.md#realFloor-impl), [`realCeil`](lib/real.md#realCeil-impl), [`realTrunc`](lib/real.md#realTrunc-impl), [`realRound`](lib/real.md#realRound-impl), [`floor`](lib/real.md#floor-impl), [`ceil`](lib/real.md#ceil-impl), [`trunc`](lib/real.md#trunc-impl), [`round`](lib/real.md#round-impl), [`fromInt`](lib/real.md#fromInt-impl), [`fmt`](lib/real.md#fmt-impl), [`toString`](lib/real.md#toString-impl), [`fromString`](lib/real.md#fromString-impl) |
| [Relational](lib/relational.md) | Relational algebra operations for Morel queries.<br>[`descending`](lib/relational.md#descending-impl), [`compare`](lib/relational.md#compare-impl), [`count`](lib/relational.md#count-impl), [`empty`](lib/relational.md#empty-impl), [`iterate`](lib/relational.md#iterate-impl), [`max`](lib/relational.md#max-impl), [`min`](lib/relational.md#min-impl), [`nonEmpty`](lib/relational.md#nonEmpty-impl), [`only`](lib/relational.md#only-impl), [`sum`](lib/relational.md#sum-impl) |
| [String](lib/string.md) | String operations.<br>[`string`](lib/string.md#string-impl), [`char`](lib/string.md#char-impl), [`maxSize`](lib/string.md#maxSize-impl), [`size`](lib/string.md#size-impl), [`sub`](lib/string.md#sub-impl), [`extract`](lib/string.md#extract-impl), [`substring`](lib/string.md#substring-impl), [`^`](lib/string.md#^-impl), [`concat`](lib/string.md#concat-impl), [`concatWith`](lib/string.md#concatWith-impl), [`str`](lib/string.md#str-impl), [`implode`](lib/string.md#implode-impl), [`explode`](lib/string.md#explode-impl), [`map`](lib/string.md#map-impl), [`translate`](lib/string.md#translate-impl), [`tokens`](lib/string.md#tokens-impl), [`fields`](lib/string.md#fields-impl), [`isPrefix`](lib/string.md#isPrefix-impl), [`isSubstring`](lib/string.md#isSubstring-impl), [`isSuffix`](lib/string.md#isSuffix-impl), [`compare`](lib/string.md#compare-impl), [`collate`](lib/string.md#collate-impl), [`<`](lib/string.md#<-impl), [`<=`](lib/string.md#<=-impl), [`>`](lib/string.md#>-impl), [`>=`](lib/string.md#>=-impl), [`=`](lib/string.md#=-impl), [`<>`](lib/string.md#<>-impl) |
| [StringCvt](lib/string-cvt.md) | String conversion utilities and types.<br>[`radix`](lib/string-cvt.md#radix-impl), [`realfmt`](lib/string-cvt.md#realfmt-impl), [`padLeft`](lib/string-cvt.md#padLeft-impl), [`padRight`](lib/string-cvt.md#padRight-impl) |
| [Sys](lib/sys.md) | System interface utilities.<br>[`clearEnv`](lib/sys.md#clearEnv-impl), [`env`](lib/sys.md#env-impl), [`file`](lib/sys.md#file-impl), [`parseTree`](lib/sys.md#parseTree-impl), [`plan`](lib/sys.md#plan-impl), [`planEx`](lib/sys.md#planEx-impl), [`set`](lib/sys.md#set-impl), [`show`](lib/sys.md#show-impl), [`showAll`](lib/sys.md#showAll-impl), [`unset`](lib/sys.md#unset-impl) |
| [Time](lib/time.md) | Time values and operations.<br>[`time`](lib/time.md#time-impl), [`Time`](lib/time.md#Time-impl), [`zeroTime`](lib/time.md#zeroTime-impl), [`fromReal`](lib/time.md#fromReal-impl), [`toReal`](lib/time.md#toReal-impl), [`toSeconds`](lib/time.md#toSeconds-impl), [`toMilliseconds`](lib/time.md#toMilliseconds-impl), [`toMicroseconds`](lib/time.md#toMicroseconds-impl), [`toNanoseconds`](lib/time.md#toNanoseconds-impl), [`fromSeconds`](lib/time.md#fromSeconds-impl), [`fromMilliseconds`](lib/time.md#fromMilliseconds-impl), [`fromMicroseconds`](lib/time.md#fromMicroseconds-impl), [`fromNanoseconds`](lib/time.md#fromNanoseconds-impl), [`+`](lib/time.md#+-impl), [`-`](lib/time.md#--impl), [`compare`](lib/time.md#compare-impl), [`<`](lib/time.md#<-impl), [`<=`](lib/time.md#<=-impl), [`>`](lib/time.md#>-impl), [`>=`](lib/time.md#>=-impl), [`now`](lib/time.md#now-impl), [`fmt`](lib/time.md#fmt-impl), [`toString`](lib/time.md#toString-impl), [`fromString`](lib/time.md#fromString-impl) |
| [Variant](lib/variant.md) | Dynamically-typed variant values.<br>[`variant`](lib/variant.md#variant-impl), [`parse`](lib/variant.md#parse-impl), [`print`](lib/variant.md#print-impl) |
| [Vector](lib/vector.md) | Immutable fixed-length arrays.<br>[`vector`](lib/vector.md#vector-impl), [`maxLen`](lib/vector.md#maxLen-impl), [`fromList`](lib/vector.md#fromList-impl), [`tabulate`](lib/vector.md#tabulate-impl), [`length`](lib/vector.md#length-impl), [`sub`](lib/vector.md#sub-impl), [`update`](lib/vector.md#update-impl), [`concat`](lib/vector.md#concat-impl), [`appi`](lib/vector.md#appi-impl), [`app`](lib/vector.md#app-impl), [`mapi`](lib/vector.md#mapi-impl), [`map`](lib/vector.md#map-impl), [`foldli`](lib/vector.md#foldli-impl), [`foldri`](lib/vector.md#foldri-impl), [`foldl`](lib/vector.md#foldl-impl), [`foldr`](lib/vector.md#foldr-impl), [`findi`](lib/vector.md#findi-impl), [`find`](lib/vector.md#find-impl), [`exists`](lib/vector.md#exists-impl), [`all`](lib/vector.md#all-impl), [`collate`](lib/vector.md#collate-impl) |
| [Word](lib/word.md) | Unsigned-integer (word) operations.<br>[`word`](lib/word.md#word-impl), [`wordSize`](lib/word.md#wordSize-impl), [`toLarge`](lib/word.md#toLarge-impl), [`toLargeX`](lib/word.md#toLargeX-impl), [`toLargeWord`](lib/word.md#toLargeWord-impl), [`toLargeWordX`](lib/word.md#toLargeWordX-impl), [`fromLarge`](lib/word.md#fromLarge-impl), [`fromLargeWord`](lib/word.md#fromLargeWord-impl), [`toLargeInt`](lib/word.md#toLargeInt-impl), [`toLargeIntX`](lib/word.md#toLargeIntX-impl), [`fromLargeInt`](lib/word.md#fromLargeInt-impl), [`toInt`](lib/word.md#toInt-impl), [`toIntX`](lib/word.md#toIntX-impl), [`fromInt`](lib/word.md#fromInt-impl), [`andb`](lib/word.md#andb-impl), [`orb`](lib/word.md#orb-impl), [`xorb`](lib/word.md#xorb-impl), [`notb`](lib/word.md#notb-impl), [`<<`](lib/word.md#<<-impl), [`>>`](lib/word.md#>>-impl), [`~>>`](lib/word.md#~>>-impl), [`+`](lib/word.md#+-impl), [`-`](lib/word.md#--impl), [`*`](lib/word.md#*-impl), [`div`](lib/word.md#div-impl), [`mod`](lib/word.md#mod-impl), [`compare`](lib/word.md#compare-impl), [`<`](lib/word.md#<-impl), [`<=`](lib/word.md#<=-impl), [`>`](lib/word.md#>-impl), [`>=`](lib/word.md#>=-impl), [`~`](lib/word.md#~-impl), [`min`](lib/word.md#min-impl), [`max`](lib/word.md#max-impl), [`fmt`](lib/word.md#fmt-impl), [`toString`](lib/word.md#toString-impl), [`fromString`](lib/word.md#fromString-impl) |

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
| matchStrict          | bool   | false   | Whether the script-test harness compares output verbatim, rather than modulo whitespace and bag-element order. |
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
| stringFold           | int    | null    | In tabular mode, the column width at which long strings are folded across multiple lines. If not set, folding is disabled. Legal values are 1 or greater. |
| timeZone             | string | null    | Overrides the local timezone. Value is a timezone ID (e.g. 'UTC' or 'America/New_York'). If not set, the JVM default timezone is used. |

[//]: # (end:properties)
