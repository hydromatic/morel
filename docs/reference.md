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
* Queries (expressions starting with `exists`, `forall` or `from`) with
  `compute`,
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
  `ordinal` nilary operators
* `typeof` type operator
* <code><i>lab</i> =</code> is optional in <code><i>exprow</i></code>
* <code><i>record</i>.<i>lab</i></code> as an alternative to
  <code>#<i>lab</i> <i>record</i></code>
* identifiers and type names may be quoted
  (for example, <code>\`an identifier\`</code>)
* `with` functional update for record values
* overloaded functions may be declared using `over` and `inst`

In Standard ML but not in Morel:
* `word` constant
* `longid` identifier
* references (`ref` and operators `!` and `:=`)
* exceptions (`raise`, `handle`, `exception`)
* `while` loop
* data type replication (`type`)
* `withtype` in `datatype` declaration
* abstract type (`abstype`)
* modules (`structure` and `signature`)
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
    | <i>exp</i> <b>:</b> <i>type</i>                type annotation
    | <i>exp<sub>1</sub></i> <b>andalso</b> <i>exp<sub>2</sub></i>         conjunction
    | <i>exp<sub>1</sub></i> <b>orelse</b> <i>exp<sub>2</sub></i>          disjunction
    | <b>if</b> <i>exp<sub>1</sub></i> <b>then</b> <i>exp<sub>2</sub></i> <b>else</b> <i>exp<sub>3</sub></i>
                                conditional
    | <b>case</b> <i>exp</i> <b>of</b> <i>match</i>         case analysis
    | <b>fn</b> <i>match</i>                  function
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
    | <i>pat</i> <b>=</b> <i>exp</i> [ <b>on</b> <i>exp</i> ]      single iteration
    | <i>var</i>                       unbounded variable
<i>step</i> &rarr; <b>distinct</b>                 distinct step
    | <b>except</b> [ <b>distinct</b> ] <i>exp<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>exp<sub>e</sub></i>
                                except step (<i>e</i> &ge; 1)
    | <b>group</b> <i>groupKey<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>groupKey<sub>g</sub></i>
      [ <b>compute</b> <i>agg<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>agg<sub>a</sub></i> ]
                                group step (<i>g</i> &ge; 0, <i>a</i> &ge; 1)
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
    | <b>compute</b> <i>agg<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>agg<sub>a</sub></i>  compute step (<i>a</i> &ge; 1)
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
    | <b>over</b> <i>id</i>                   overloaded name
    | <i>empty</i>
    | <i>dec<sub>1</sub></i> [<b>;</b>] <i>dec<sub>2</sub></i>              sequence
<i>valbind</i> &rarr; <i>pat</i> <b>=</b> <i>exp</i> [ <b>and</b> <i>valbind</i> ]*
                                destructuring
    | <b>rec</b> <i>valbind</i>               recursive
    | <b>inst</b> <i>valbind</i>              overload instance
<i>funbind</i> &rarr; <i>funmatch</i> [ <b>and</b> <i>funmatch</i> ]*
                                clausal function
<i>funmatch</i> &rarr; <i>funmatchItem</i> [ '<b>|</b>' funmatchItem ]*
<i>funmatchItem</i> &rarr; [ <b>op</b> ] <i>id</i> <i>pat<sub>1</sub></i> ... <i>pat<sub>n</sub></i> [ <b>:</b> <i>type</i> ] <b>=</b> <i>exp</i>
                                nonfix (n &ge; 1)
    | <i>pat<sub>1</sub></i> <i>id</i> <i>pat<sub>2</sub></i> [ <b>:</b> <i>type</i> ] <b>=</b> <i>exp</i>        infix
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

## Built-in types

Primitive: `bool`, `char`, `int`, `real`, `string`, `unit`

Datatype:
* `datatype 'a descending = DESC of 'a (in structure `Relational`)
* `datatype 'a list = nil | :: of 'a * 'a list` (in structure `List`)
* `datatype 'a option = NONE | SOME of 'a` (in structure `Option`)
* `datatype 'a order = LESS | EQUAL | GREATER` (in structure `General`)

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

## Built-in functions

{% comment %}START TABLE{% endcomment %}

| Name | Type | Description |
| ---- | ---- | ----------- |
| Bag.nil | &alpha; bag | "nil" is the empty bag. |
| Bag.null | &alpha; bag &rarr; bool | "null b" returns `true` if the bag `b` is empty. |
| Bag.fromList | &alpha; list &rarr; &alpha; bag | "fromList l" creates a new bag from `l`, whose length is `length l` and whose elements are the same as those of `l`. Raises `Size` if `maxLen` &lt; `n`. |
| Bag.toList | &alpha; bag &rarr; &alpha; list | "toList b" creates a new bag from `b`, whose length is `length b` and whose elements are the same as those of `b`. Raises `Size` if `maxLen` &lt; `n`. |
| Bag.length | &alpha; bag &rarr; int | "length b" returns the number of elements in the bag `b`. |
| Bag.at | &alpha; bag * &alpha; bag &rarr; &alpha; bag | "at (b1, b2)" returns the bag that is the concatenation of `b1` and `b2`. |
| Bag.hd | &alpha; bag &rarr; &alpha; | "hd b" returns an arbitrary element of bag `b`. Raises `Empty` if `b` is `nil`. |
| Bag.tl | &alpha; bag &rarr; &alpha; bag | "tl b" returns all but one arbitrary element of bag `b`. Raises `Empty` if `b` is `nil`. |
| Bag.getItem | &alpha; bag &rarr; * (&alpha; * &alpha; bag) option | "getItem b" returns `NONE` if the bag `b` is empty, and `SOME (hd b, tl b)` otherwise (applying `hd` and `tl` simultaneously so that they choose/remove the same arbitrary element). |
| Bag.take | &alpha; bag * int &rarr; &alpha; bag | "take (b, i)" returns an arbitrary `i` elements of the bag `b`. Raises `Subscript` if `i` &lt; 0 or `i` &gt; `length l`. We have `take(b, length b)` = `b`. |
| Bag.drop | &alpha; bag * int &rarr; &alpha; bag | "drop (b, i)" returns what is left after dropping an arbitrary `i` elements of the bag `b`. Raises `Subscript` if `i` &lt; 0 or `i` &gt; `length l`.<br><br>We have `drop(b, length b)` = `[]`. |
| Bag.concat | &alpha; bag bag &rarr; &alpha; bag | "concat b" returns the bag that is the concatenation of all the bags in `b`. |
| Bag.app | (&alpha; &rarr; unit) &rarr; &alpha; bag &rarr; unit | "app f b" applies `f` to the elements of `b`. |
| Bag.map | (&alpha; &rarr; &beta;) &rarr; &alpha; bag &rarr; &beta; bag | "map f b" applies `f` to each element of `b`, returning the bag of results. This is equivalent to:  <pre>fromList (List.map f (foldr (fn (a,l) =&gt; a::l) [] b))</pre> |
| Bag.mapPartial | (&alpha; &rarr; &beta; option) &rarr; &alpha; bag &rarr; &beta; bag | "mapPartial f b" applies `f` to each element of `b`, returning a bag of results, with `SOME` stripped, where `f` was defined. `f` is not defined for an element of `b` if `f` applied to the element returns `NONE`. The above expression is equivalent to:  <pre>((map valOf) o (filter isSome) o (map f)) b`</pre> |
| Bag.find | (&alpha; &rarr; bool) &rarr; &alpha; bag &rarr; &alpha; option | "find f b" applies `f` to each element `x` of the bag `b`, in arbitrary order, until `f x` evaluates to `true`. It returns `SOME (x)` if such an `x` exists; otherwise it returns `NONE`. |
| Bag.filter | (&alpha; &rarr; bool) &rarr; &alpha; bag &rarr; &alpha; bag | "filter f b" applies `f` to each element `x` of `b` and returns the bag of those `x` for which `f x` evaluated to `true`. |
| Bag.partition | (&alpha; &rarr; bool) &rarr; &alpha; bag &rarr; &alpha; bag * &alpha; bag | "partition f b" applies `f` to each element `x` of `b`, in arbitrary order, and returns a pair `(pos, neg)` where `pos` is the bag of those `x` for which `f x` evaluated to `true`, and `neg` is the bag of those for which `f x` evaluated to `false`. |
| Bag.fold | (&alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; bag &rarr; &beta; | "fold f init (bag \[x1, x2, ..., xn\])" returns `f(xn, ... , f(x2, f(x1, init))...)` (for some arbitrary reordering of the elements `xi`) or `init` if the bag is empty. |
| Bag.exists | (&alpha; &rarr; bool) &rarr; &alpha; bag &rarr; bool | "exists f b" applies `f` to each element `x` of the bag `b`, in arbitrary order, until `f(x)` evaluates to `true`; it returns `true` if such an `x` exists and `false` otherwise. |
| Bag.all | (&alpha; &rarr; bool) &rarr; &alpha; bag &rarr; bool | "all f b" applies `f` to each element `x` of the bag `b`, in arbitrary order, until `f(x)` evaluates to `false`; it returns `false` if such an `x` exists and `true` otherwise. It is equivalent to `not(exists (not o f) b))`. |
| Bag.tabulate | int * (int &rarr; &alpha;) &rarr; &alpha; bag | "tabulate (n, f)" returns a bag of length `n` equal to `[f(0), f(1), ..., f(n-1)]`. This is equivalent to the expression:  <pre>fromList (List.tabulate (n, f))</pre>  Raises `Size` if `n` &lt; 0. |
| Bag.collate | (&alpha; * &alpha; &rarr; order) &rarr; &alpha; bag * &alpha; bag &rarr; order | "collate f (l1, l2)" performs lexicographic comparison of the two bags using the given ordering `f` on the bag elements. |
| Bool.not | bool &rarr; bool | "not b" returns the logical inverse of `b`. |
| Bool.op implies | bool * bool &rarr; bool | "b1 implies b2" returns `true` if `b1` is `false` or `b2` is `true`. |
| Char.chr | int &rarr; char | "chr i" returns the character whose code is `i`. Raises `Chr` if `i` &lt; 0 or `i` &gt; `maxOrd`. |
| Char.compare | char * char &rarr; order | "compare (c1, c2)" returns `LESS`, `EQUAL`, or `GREATER` according to whether its first argument is less than, equal to, or greater than the second. |
| Char.contains | char &rarr; string &rarr; bool | "contains s c" returns true if character `c` occurs in the string `s`; false otherwise. The function, when applied to `s`, builds a table and returns a function which uses table lookup to decide whether a given character is in the string or not. Hence it is relatively expensive to compute `val p = contains s` but very fast to compute `p(c)` for any given character. |
| Char.fromCString | string &rarr; char option | "fromCString s" scans a `char` value from a string. Returns `SOME (r)` if a `char` value can be scanned from a prefix of `s`, ignoring any initial whitespace; otherwise, it returns `NONE`. Equivalent to `StringCvt.scanString (scan StringCvt.ORD)`. |
| Char.fromInt | int &rarr; char option | "fromInt i" converts an `int` into a `char`. Raises `Chr` if `i` &lt; 0 or `i` &gt; `maxOrd`. |
| Char.fromString | string &rarr; char option | "fromString s" attempts to scan a character or ML escape sequence from the string `s`. Does not skip leading whitespace. For instance, `fromString "\\065"` equals `#"A"`. |
| Char.isAlpha | char &rarr; bool | "isAlpha c" returns true if `c` is a letter (lowercase or uppercase). |
| Char.isAlphaNum | char &rarr; bool | "isAlphaNum c" returns true if `c` is alphanumeric (a letter or a decimal digit). |
| Char.isAscii | char &rarr; bool | "isAscii c" returns true if 0 &le; `ord c` &le; 127 `c`. |
| Char.isCntrl | char &rarr; bool | "isCntrl c" returns true if `c` is a control character, that is, if `not (isPrint c)`. |
| Char.isDigit | char &rarr; bool | "isDigit c" returns true if `c` is a decimal digit (0 to 9). |
| Char.isGraph | char &rarr; bool | "isGraph c" returns true if `c` is a graphical character, that is, it is printable and not a whitespace character. |
| Char.isHexDigit | char &rarr; bool | "isHexDigit c" returns true if `c` is a hexadecimal digit. |
| Char.isLower | char &rarr; bool | "isLower c" returns true if `c` is a hexadecimal digit (0 to 9 or a to f or A to F). |
| Char.isOctDigit | char &rarr; bool | "isOctDigit c" returns true if `c` is an octal digit. |
| Char.isPrint | char &rarr; bool | "isPrint c" returns true if `c` is a printable character (space or visible). |
| Char.isPunct | char &rarr; bool | "isPunct c" returns true if `c` is a punctuation character, that is, graphical but not alphanumeric. |
| Char.isSpace | char &rarr; bool | "isSpace c" returns true if `c` is a whitespace character (blank, newline, tab, vertical tab, new page). |
| Char.isUpper | char &rarr; bool | "isUpper c" returns true if `c` is an uppercase letter (A to Z). |
| Char.maxOrd | int | "maxOrd" is the greatest character code; it equals `ord maxChar`. |
| Char.maxChar | int | "maxChar" is the greatest character in the ordering `&lt;`. |
| Char.minChar | char | "minChar" is the minimal (most negative) character representable by `char`. If a value is `NONE`, `char` can represent all negative integers, within the limits of the heap size. If `precision` is `SOME (n)`, then we have `minChar` = -2<sup>(n-1)</sup>. |
| Char.notContains | char &rarr; string &rarr; bool | "notContains s c" returns true if character `c` does not occur in the string `s`; false otherwise. Works by construction of a lookup table in the same way as `Char.contains`. |
| Char.ord | char &rarr; int | "ord c" returns the code of character `c`. |
| Char.pred | char &rarr; char | "pred c" returns the predecessor of `c`. Raises `Subscript` if `c` is `minOrd`. |
| Char.succ | char &rarr; char | "succ c" returns the character immediately following `c`, or raises `Chr` if `c` = `maxChar` |
| Char.toCString | char &rarr; string | "toCString c" converts a `char` into a `string`; equivalent to `(fmt StringCvt.ORD r)`. |
| Char.toLower | char &rarr; char | "toLower c" returns the lowercase letter corresponding to `c`, if `c` is a letter (a to z or A to Z); otherwise returns `c`. |
| Char.toString | char &rarr; string | "toString c" converts a `char` into a `string`; equivalent to `(fmt StringCvt.ORD r)`. |
| Char.toUpper | char &rarr; char | "toUpper c" returns the uppercase letter corresponding to `c`, if `c` is a letter (a to z or A to Z); otherwise returns `c`. |
| General.ignore | &alpha; &rarr; unit | "ignore x" always returns `unit`. The function evaluates its argument but throws away the value. |
| General.op o | (&beta; &rarr; &gamma;) (&alpha; &rarr; &beta;) &rarr; &alpha; &rarr; &gamma; | "f o g" is the function composition of `f` and `g`. Thus, `(f o g) a` is equivalent to `f (g a)`. |
| Int.op * | int * int &rarr; int | "i * j" is the product of `i` and `j`. It raises `Overflow` when the result is not representable. |
| Int.op + | int * int &rarr; int | "i + j" is the sum of `i` and `j`. It raises `Overflow` when the result is not representable. |
| Int.op - | int * int &rarr; int | "i - j" is the difference of `i` and `j`. It raises `Overflow` when the result is not representable. |
| Int.op div | int * int &rarr; int | "i div j" returns the greatest integer less than or equal to the quotient of i by j, i.e., `floor(i / j)`. It raises `Overflow` when the result is not representable, or Div when `j = 0`. Note that rounding is towards negative infinity, not zero. |
| Int.op mod | int * int &rarr; int | "i mod j" returns the remainder of the division of i by j. It raises `Div` when `j = 0`. When defined, `(i mod j)` has the same sign as `j`, and `(i div j) * j + (i mod j) = i`. |
| Int.op &lt; | int * int &rarr; bool | "i &lt; j" returns true if i is less than j. |
| Int.op &lt;= | int * int &rarr; bool | "i &lt; j" returns true if i is less than or equal to j. |
| Int.op &gt; | int * int &rarr; bool | "i &lt; j" returns true if i is greater than j. |
| Int.op &gt;= | int * int &rarr; bool | "i &lt; j" returns true if i is greater than or equal to j. |
| Int.op ~ | int &rarr; int | "~ i" returns the negation of `i`. |
| Int.abs | int &rarr; int | "abs i" returns the absolute value of `i`. |
| Int.compare | int * int &rarr; order | "compare (i, j)" returns `LESS`, `EQUAL`, or `GREATER` according to whether its first argument is less than, equal to, or greater than the second. |
| Int.fromInt, int | int &rarr; int | "fromInt i" converts a value from type `int` to the default integer type. Raises `Overflow` if the value does not fit. |
| Int.fromString | string &rarr; int option | "fromString s" scans a `int` value from a string. Returns `SOME (r)` if a `int` value can be scanned from a prefix of `s`, ignoring any initial whitespace; otherwise, it returns `NONE`. Equivalent to `StringCvt.scanString (scan StringCvt.DEC)`. |
| Int.max | int * int &rarr; int | "max (i, j)" returns the larger of the arguments. |
| Int.maxInt | int | "maxInt" is the maximal (most positive) integer representable by `int`. If a value is `NONE`, `int` can represent all positive integers, within the limits of the heap size. If `precision` is `SOME (n)`, then we have `maxInt` = 2<sup>(n-1)</sup> - 1. |
| Int.min | int * int &rarr; int | "min (i, j)" returns the smaller of the arguments. |
| Int.minInt | int | "minInt" is the minimal (most negative) integer representable by `int`. If a value is `NONE`, `int` can represent all negative integers, within the limits of the heap size. If `precision` is `SOME (n)`, then we have `minInt` = -2<sup>(n-1)</sup>. |
| Int.mod | int * int &rarr; int | "mod (i, j)" returns the remainder of the division of `i` by `j`. It raises `Div` when `j = 0`. When defined, `(i mod j)` has the same sign as `j`, and `(i div j) * j + (i mod j) = i`. |
| Int.precision | int | "precision" is the precision. If `SOME (n)`, this denotes the number `n` of significant bits in type `int`, including the sign bit. If it is `NONE`, int has arbitrary precision. The precision need not necessarily be a power of two. |
| Int.quot | int * int &rarr; int | "quot (i, j)" returns the truncated quotient of the division of `i` by `j`, i.e., it computes `(i / j)` and then drops any fractional part of the quotient. It raises `Overflow` when the result is not representable, or `Div` when `j = 0`. Note that unlike `div`, `quot` rounds towards zero. In addition, unlike `div` and `mod`, neither `quot` nor `rem` are infix by default; an appropriate infix declaration would be `infix 7 quot rem`. This is the semantics of most hardware divide instructions, so `quot` may be faster than `div`. |
| Int.rem | int * int &rarr; int | "rem (i, j)" returns the remainder of the division of `i` by `j`. It raises `Div` when `j = 0`. `(i rem j)` has the same sign as i, and it holds that `(i quot j) * j + (i rem j) = i`. This is the semantics of most hardware divide instructions, so `rem` may be faster than `mod`. |
| Int.sameSign | int * int &rarr; bool | "sameSign (i, j)" returns true if `i` and `j` have the same sign. It is equivalent to `(sign i = sign j)`. |
| Int.sign | int &rarr; int | "sign i" returns ~1, 0, or 1 when `i` is less than, equal to, or greater than 0, respectively. |
| Int.toInt | int &rarr; int | "toInt i" converts a value from the default integer type to type `int`. Raises `Overflow` if the value does not fit. |
| Int.toString | int &rarr; string | "toString i" converts a `int` into a `string`; equivalent to `(fmt StringCvt.DEC r)`. |
| Interact.use | string &rarr; unit | "use f" loads source text from the file named `f`. |
| Interact.useSilently | string &rarr; unit | "useSilently f" loads source text from the file named `f`, without printing to stdout. |
| List.nil | &alpha; list | "nil" is the empty list. |
| List.null | &alpha; list &rarr; bool | "null l" returns `true` if the list `l` is empty. |
| List.length | &alpha; list &rarr; int | "length l" returns the number of elements in the list `l`. |
| List.op @ | &alpha; list * &alpha; list &rarr; &alpha; list | "l1 @ l2" returns the list that is the concatenation of `l1` and `l2`. |
| List.at | &alpha; list * &alpha; list &rarr; &alpha; list | "at (l1, l2)" is equivalent to "l1 @ l2". |
| List.hd | &alpha; list &rarr; &alpha; | "hd l" returns the first element of `l`. Raises `Empty` if `l` is `nil`. |
| List.tl | &alpha; list &rarr; &alpha; list | "tl l" returns all but the first element of `l`. Raises `Empty` if `l` is `nil`. |
| List.last | &alpha; list &rarr; &alpha; | "last l" returns the last element of `l`. Raises `Empty` if `l` is `nil`. |
| List.getItem | &alpha; list &rarr; * (&alpha; * &alpha; list) option | "getItem l" returns `NONE` if the `list` is empty, and `SOME (hd l, tl l)` otherwise. This function is particularly useful for creating value readers from lists of characters. For example, `Int.scan StringCvt.DEC getItem` has the type `(int, char list) StringCvt.reader` and can be used to scan decimal integers from lists of characters. |
| List.nth | &alpha; list * int &rarr; &alpha; | "nth (l, i)" returns the `i`(th) element of the list `l`, counting from 0. Raises `Subscript` if `i` &lt; 0 or `i` &ge; `length l`. We have `nth(l, 0)` = `hd l`, ignoring exceptions. |
| List.take | &alpha; list * int &rarr; &alpha; list | "take (l, i)" returns the first `i` elements of the list `l`. Raises `Subscript` if `i` &lt; 0 or `i` &gt; `length l`. We have `take(l, length l)` = `l`. |
| List.drop | &alpha; list * int &rarr; &alpha; list | "drop (l, i)" returns what is left after dropping the first `i` elements of the list `l`. Raises `Subscript` if `i` &lt; 0 or `i` &gt; `length l`.<br><br>It holds that `take(l, i) @ drop(l, i)` = `l` when 0 &le; `i` &le; `length l`. We also have `drop(l, length l)` = `[]`. |
| List.rev | &alpha; list &rarr; &alpha; list | "rev l" returns a list consisting of `l`'s elements in reverse order. |
| List.concat | &alpha; list list &rarr; &alpha; list | "concat l" returns the list that is the concatenation of all the lists in `l` in order. `concat [l1, l2, ... ln]` = `l1 @ l2 @ ... @ ln` |
| List.except | &alpha; list list &rarr; &alpha; list | "except l" returns the list that is the concatenation of all the lists in `l` in order. `concat [l1, l2, ... ln]` = `l1 @ l2 @ ... @ ln` |
| List.intersect | &alpha; list list &rarr; &alpha; list | "intersect l" returns the list that is the concatenation of all the lists in `l` in order. `concat [l1, l2, ... ln]` = `l1 @ l2 @ ... @ ln` |
| List.revAppend | &alpha; list * &alpha; list &rarr; &alpha; list | "revAppend (l1, l2)" returns `(rev l1) @ l2`. |
| List.app | (&alpha; &rarr; unit) &rarr; &alpha; list &rarr; unit | "app f l" applies `f` to the elements of `l`, from left to right. |
| List.map | (&alpha; &rarr; &beta;) &rarr; &alpha; list &rarr; &beta; list | "map f l" applies `f` to each element of `l` from left to right, returning the list of results. |
| List.mapi | (int * &alpha; &rarr; &beta;) &rarr; &alpha; list &rarr; &beta; list | "mapi f l" applies the function `f` to the elements of the argument list `l`, supplying the list index and element as arguments to each call. |
| List.mapPartial | (&alpha; &rarr; &beta; option) &rarr; &alpha; list &rarr; &beta; list | "mapPartial f l" applies `f` to each element of `l` from left to right, returning a list of results, with `SOME` stripped, where `f` was defined. `f` is not defined for an element of `l` if `f` applied to the element returns `NONE`. The above expression is equivalent to:  <pre>((map valOf) o (filter isSome) o (map f)) b`</pre> |
| List.find | (&alpha; &rarr; bool) &rarr; &alpha; list &rarr; &alpha; option | "find f l" applies `f` to each element `x` of the list `l`, from left to right, until `f x` evaluates to `true`. It returns `SOME (x)` if such an `x` exists; otherwise it returns `NONE`. |
| List.filter | (&alpha; &rarr; bool) &rarr; &alpha; list &rarr; &alpha; list | "filter f l" applies `f` to each element `x` of `l`, from left to right, and returns the list of those `x` for which `f x` evaluated to `true`, in the same order as they occurred in the argument list. |
| List.partition | (&alpha; &rarr; bool) &rarr; &alpha; list &rarr; &alpha; list * &alpha; list | "partition f l" applies `f` to each element `x` of `l`, from left to right, and returns a pair `(pos, neg)` where `pos` is the list of those `x` for which `f x` evaluated to `true`, and `neg` is the list of those for which `f x` evaluated to `false`. The elements of `pos` and `neg` retain the same relative order they possessed in `l`. |
| List.foldl | (&alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; list &rarr; &beta; | "foldl f init \[x1, x2, ..., xn\]" returns `f(xn, ... , f(x2, f(x1, init))...)` or `init` if the list is empty. |
| List.foldr | (&alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; list &rarr; &beta; | "foldr f init \[x1, x2, ..., xn\]" returns `f(x1, f(x2, ..., f(xn, init)...))` or `init` if the list is empty. |
| List.exists | (&alpha; &rarr; bool) &rarr; &alpha; list &rarr; bool | "exists f l" applies `f` to each element `x` of the list `l`, from left to right, until `f(x)` evaluates to `true`; it returns `true` if such an `x` exists and `false` otherwise. |
| List.all | (&alpha; &rarr; bool) &rarr; &alpha; list &rarr; bool | "all f l" applies `f` to each element `x` of the list `l`, from left to right, until `f(x)` evaluates to `false`; it returns `false` if such an `x` exists and `true` otherwise. It is equivalent to `not(exists (not o f) l))`. |
| List.tabulate | int * (int &rarr; &alpha;) &rarr; &alpha; list | "tabulate (n, f)" returns a list of length `n` equal to `[f(0), f(1), ..., f(n-1)]`, created from left to right. Raises `Size` if `n` &lt; 0. |
| List.collate | (&alpha; * &alpha; &rarr; order) &rarr; &alpha; list * &alpha; list &rarr; order | "collate f (l1, l2)" performs lexicographic comparison of the two lists using the given ordering `f` on the list elements. |
| ListPair.zip | &alpha; list * &beta; list &rarr; (&alpha; * &beta;) list | "zip (l1, l2)" combines the two lists *l1* and *l2* into a list of pairs, with the first element of each list comprising the first element of the result, the second elements comprising the second element of the result, and so on.  If the lists are of unequal lengths, `zip` ignores the excess elements from the tail of the longer one. |
| ListPair.zipEq | &alpha; list * &beta; list &rarr; (&alpha; * &beta;) list | "zipEq (l1, l2)" combines the two lists *l1* and *l2* into a list of pairs, with the first element of each list comprising the first element of the result, the second elements comprising the second element of the result, and so on.  If the lists are of unequal lengths, `zipEq` raises the exception `UnequalLengths`. |
| ListPair.unzip | ('a * 'b) list &rarr; 'a list * 'b list | "unzip l" returns a pair of lists formed by splitting the elements of *l*. This is the inverse of `zip` for equal length lists. |
| ListPair.app | (&alpha; * &beta; &rarr; unit) &rarr; &alpha; list * &beta; list &rarr; unit | "app f (l1, l2)" applies the function *f* to the list of pairs of elements generated from left to right from the lists *l1* and *l2*. If the lists are of unequal lengths, ignores the excess elements from the tail of the longer one. It is equivalent to:  <pre>List.app f (zip (l1, l2))</pre><br><br>ignoring possible side effects of the function *f*. |
| ListPair.appEq | (&alpha; * &beta; &rarr; unit) &rarr; &alpha; list * &beta; list &rarr; unit | "appEq f (l1, l2)" applies the function *f* to the list of pairs of elements generated from left to right from the lists *l1* and *l2*. If the lists are of unequal lengths, raises `UnequalLengths`. It is equivalent to:  <pre>List.app f (zipEq (l1, l2))</pre><br><br>ignoring possible side effects of the function *f*. |
| ListPair.map | (&alpha; * &beta; &rarr; &gamma;) &rarr; &alpha; list * &beta; list &rarr; &gamma; list | "map f (l1, l2)" maps the function *f* over the list of pairs of elements generated from left to right from the lists *l1* and *l2*, returning the list of results.  If the lists are of unequal lengths, ignores the excess elements from the tail of the longer one.  It is equivalent to:  <pre>List.map f (zip (l1, l2))</pre><br><br>ignoring possible side effects of the function *f*. |
| ListPair.mapEq | (&alpha; * &beta; &rarr; &gamma;) &rarr; &alpha; list * &beta; list &rarr; &gamma; list | "mapEq f (l1, l2)" maps the function *f* over the list of pairs of elements generated from left to right from the lists *l1* and *l2*, returning the list of results.  If the lists are of unequal lengths, raises `UnequalLengths`.  It is equivalent to:  <pre>List.map f (zipEq (l1, l2))</pre><br><br>ignoring possible side effects of the function *f*. |
| ListPair.foldl | (&alpha; * &beta; * &gamma; &rarr; &gamma;) &rarr; &gamma; &rarr; &alpha; list * &beta; list &rarr; &gamma; | "foldl f init (l1, l2)" returns the result of folding the function *f* in the specified direction over the pair of lists *l1* and *l2* starting with the value *init*.  It is equivalent to:  <pre>List.foldl f' init (zip (l1, l2))</pre>  where *f'* is `fn ((a,b),c) =&gt; f(a,b,c)` and ignoring possible side effects of the function *f*. |
| ListPair.foldr | (&alpha; * &beta; * &gamma; &rarr; &gamma;) &rarr; &gamma; &rarr; &alpha; list * &beta; list &rarr; &gamma; | "foldr f init (l1, l2)" returns the result of folding the function *f* in the specified direction over the pair of lists *l1* and *l2* starting with the value *init*.  It is equivalent to:  <pre>List.foldr f' init (zip (l1, l2))</pre>  where *f'* is `fn ((a,b),c) =&gt; f(a,b,c)` and ignoring possible side effects of the function *f*. |
| ListPair.foldlEq | (&alpha; * &beta; * &gamma; &rarr; &gamma;) &rarr; &gamma; &rarr; &alpha; list * &beta; list &rarr; &gamma; | "foldlEq f init (l1, l2)" returns the result of folding the function *f* in the specified direction over the pair of lists *l1* and *l2* starting with the value *init*.  It is equivalent to:  <pre>List.foldl f' init (zipEq (l1, l2))</pre>  where *f'* is `fn ((a,b),c) =&gt; f(a,b,c)` and ignoring possible side effects of the function *f*. |
| ListPair.foldrEq | (&alpha; * &beta; * &gamma; &rarr; &gamma;) &rarr; &gamma; &rarr; &alpha; list * &beta; list &rarr; &gamma; | "foldrEq f init (l1, l2)" returns the result of folding the function *f* in the specified direction over the pair of lists *l1* and *l2* starting with the value *init*.  It is equivalent to:  <pre>List.foldr f' init (zipEq (l1, l2))</pre>  where *f'* is `fn ((a,b),c) =&gt; f(a,b,c)` and ignoring possible side effects of the function *f*. |
| ListPair.all | (&alpha; * &beta; &rarr; bool) &rarr; &alpha; list * &beta; list &rarr; bool | "all f (l1, l2)" provides short-circuit testing of a predicate over a pair of lists.<br><br>It is equivalent to: <pre>List.all f (zip (l1, l2))</pre> |
| ListPair.exists | (&alpha; * &beta; &rarr; bool) &rarr; &alpha; list * &beta; list &rarr; bool | "exists f (l1, l2)" provides short-circuit testing of a predicate over a pair of lists.<br><br>It is equivalent to: <pre>List.exists f (zip (l1, l2))</pre> |
| ListPair.allEq | (&alpha; * &beta; &rarr; bool) &rarr; &alpha; list * &beta; list &rarr; bool | "allEq f (l1, l2)" returns `true` if *l1* and *l2* have equal length and all pairs of elements satisfy the predicate *f*. That is, the expression is equivalent to:  <pre> (List.length l1 = List.length l2) andalso (List.all f (zip (l1, l2))) </pre><br><br>This function does not appear to have any nice algebraic relation with the other functions, but it is included as providing a useful notion of equality, analogous to the notion of equality of lists over equality types.<br><br>&lt;b&gt;Implementation note:&lt;/b&gt;<br><br>The implementation is simple:  <pre> fun allEq p ([], []) = true   \| allEq p (x::xs, y::ys) = p(x,y) andalso allEq p (xs,ys)   \| allEq _ _ = false </pre> |
| Math.acos | real &rarr; real | "acos x" returns the arc cosine of `x`. `acos` is the inverse of `cos`. Its result is guaranteed to be in the closed interval \[0, pi\]. If the magnitude of `x` exceeds 1.0, returns NaN. |
| Math.asin | real &rarr; real | "asin x" returns the arc sine of `x`. `asin` is the inverse of `sin`. Its result is guaranteed to be in the closed interval \[-pi / 2, pi / 2\]. If the magnitude of `x` exceeds 1.0, returns NaN. |
| Math.atan | real &rarr; real | "atan x" returns the arc tangent of `x`. `atan` is the inverse of `tan`. For finite arguments, the result is guaranteed to be in the open interval (-pi / 2, pi / 2). If `x` is +infinity, it returns pi / 2; if `x` is -infinity, it returns -pi / 2. |
| Math.atan2 | real * real &rarr; real | "atan2 (y, x)" returns the arc tangent of `(y / x)` in the closed interval \[-pi, pi\], corresponding to angles within +-180 degrees. The quadrant of the resulting angle is determined using the signs of both `x` and `y`, and is the same as the quadrant of the point `(x, y)`. When `x` = 0, this corresponds to an angle of 90 degrees, and the result is `(real (sign y)) * pi / 2.0`. |
| Math.cos | real &rarr; real | "cos x" returns the cosine of `x`, measured in radians. If `x` is an infinity, returns NaN. |
| Math.cosh | real &rarr; real | "cosh x" returns the hyperbolic cosine of `x`, that is, `(e(x) + e(-x)) / 2`. Among its properties, cosh +-0 = 1, cosh +-infinity = +-infinity. |
| Math.e | real | "e" is base e (2.718281828...) of the natural logarithm. |
| Math.exp | real &rarr; real | "exp x" returns `e(x)`, i.e., `e` raised to the `x`<sup>th</sup> power. If `x` is +infinity, returns +infinity; if `x` is -infinity, returns 0. |
| Math.ln | real &rarr; real | "ln x" returns the natural logarithm (base e) of `x`. If `x` &lt; 0, returns NaN; if `x` = 0, returns -infinity; if `x` is infinity, returns infinity. |
| Math.log10 | real &rarr; real | "log10 x" returns the decimal logarithm (base 10) of `x`. If `x` &lt; 0, returns NaN; if `x` = 0, returns -infinity; if `x` is infinity, returns infinity. |
| Math.pi | real | "pi" is the constant pi (3.141592653...). |
| Math.pow | real * real &rarr; real | "pow (x, y)" returns `x(y)`, i.e., `x` raised to the `y`<sup>th</sup> power. For finite `x` and `y`, this is well-defined when `x` &gt; 0, or when `x` &lt; 0 and `y` is integral. |
| Math.sin | real &rarr; real | "sin x" returns the sine of `x`, measured in radians. If `x` is an infinity, returns NaN. |
| Math.sinh | real &rarr; real | "sinh x" returns the hyperbolic sine of `x`, that is, `(e(x) - e(-x)) / 2`. Among its properties, sinh +-0 = +-0, sinh +-infinity = +-infinity. |
| Math.sqrt | real &rarr; real | "sqrt x" returns the square root of `x`. sqrt (~0.0) = ~0.0. If `x` &lt; 0, returns NaN. |
| Math.tan | real &rarr; real | "tan x" returns the tangent of `x`, measured in radians. If `x` is an infinity, returns NaN. Produces infinities at various finite values, roughly corresponding to the singularities of the tangent function. |
| Math.tanh | real &rarr; real | "tanh x" returns the hyperbolic tangent of `x`, that is, `(sinh x) / (cosh x)`. Among its properties, tanh +-0 = +-0, tanh +-infinity = +-1. |
| Option.app | (&alpha; &rarr; unit) &rarr; &alpha; option &rarr; unit | "app f opt" applies the function `f` to the value `v` if `opt` is `SOME v`, and otherwise does nothing. |
| Option.compose | (&alpha; &rarr; &beta;) * (&gamma; &rarr; &alpha; option) &rarr; &gamma; &rarr; &beta; option | "compose (f, g) a" returns `NONE` if `g(a)` is `NONE`; otherwise, if `g(a)` is `SOME v`, it returns `SOME (f v)`. |
| Option.composePartial | (&alpha; &rarr; &beta; option) * (&gamma; &rarr; &alpha; option) &rarr; &gamma; &rarr; &beta; option | "composePartial (f, g) a" returns `NONE` if `g(a)` is `NONE`; otherwise, if `g(a)` is `SOME v`, returns `f(v)`. |
| Option.map | &alpha; &rarr; &beta;) &rarr; &alpha; option &rarr; &beta; option | "map f opt" maps `NONE` to `NONE` and `SOME v` to `SOME (f v)`. |
| Option.mapPartial | &alpha; &rarr; &beta; option) &rarr; &alpha; option &rarr; &beta; option | "mapPartial f opt" maps `NONE` to `NONE` and `SOME v` to `f(v)`. |
| Option.getOpt | &alpha; option * &alpha; &rarr; &alpha; | "getOpt (opt, a)" returns `v` if `opt` is `SOME (v)`; otherwise returns `a`. |
| Option.isSome | &alpha; option &rarr; bool | "isSome opt" returns `true` if `opt` is `SOME v`; otherwise returns `false`. |
| Option.filter | (&alpha; &rarr; bool) &rarr; &alpha; &rarr; &alpha; option | "filter f a" returns `SOME a` if `f(a)` is `true`, `NONE` otherwise. |
| Option.join | &alpha; option option &rarr; &alpha; option | "join opt" maps `NONE` to `NONE` and `SOME v` to `v`. |
| Option.valOf | &alpha; option &rarr; &alpha; | "valOf opt" returns `v` if `opt` is `SOME v`, otherwise raises `Option`. |
| Real.op * | real * real &rarr; real | "r1 * r2" is the product of `r1` and `r2`. The product of zero and an infinity produces NaN. Otherwise, if one argument is infinite, the result is infinite with the correct sign, e.g., -5 * (-infinity) = infinity, infinity * (-infinity) = -infinity. |
| Real.op + | real * real &rarr; real | "r1 + r2" is the sum of `r1` and `r2`. If one argument is finite and the other infinite, the result is infinite with the correct sign, e.g., 5 - (-infinity) = infinity. We also have infinity + infinity = infinity and (-infinity) + (-infinity) = (-infinity). Any other combination of two infinities produces NaN. |
| Real.op - | real * real &rarr; real | "r1 - r2" is the difference of `r1` and `r2`. If one argument is finite and the other infinite, the result is infinite with the correct sign, e.g., 5 - (-infinity) = infinity. We also have infinity + infinity = infinity and (-infinity) + (-infinity) = (-infinity). Any other combination of two infinities produces NaN. |
| Real.op / | real * real &rarr; real | "r1 / r2" is the quotient of `r1` and `r2`. We have 0 / 0 = NaN and +-infinity / +-infinity = NaN. Dividing a finite, non-zero number by a zero, or an infinity by a finite number produces an infinity with the correct sign. (Note that zeros are signed.) A finite number divided by an infinity is 0 with the correct sign. |
| Real.op &lt; | real * real &rarr; bool | "x &lt; y" returns true if x is less than y. Return false on unordered arguments, i.e., if either argument is NaN, so that the usual reversal of comparison under negation does not hold, e.g., `a &lt; b` is not the same as `not (a &gt;= b)`. |
| Real.op &lt;= | real * real &rarr; bool | As "&lt;" |
| Real.op &gt; | real * real &rarr; bool | As "&lt;" |
| Real.op &gt;= | real * real &rarr; bool | As "&lt;" |
| Real.op ~ | real &rarr; real | "~ r" returns the negation of `r`. |
| Real.abs | real &rarr; real | "abs r" returns the absolute value of `r`. |
| Real.ceil | real &rarr; int | "floor r" produces `ceil(r)`, the smallest int not less than `r`. |
| Real.checkFloat | real &rarr; real | "checkFloat x" raises `Overflow` if x is an infinity, and raises `Div` if x is NaN. Otherwise, it returns its argument. |
| Real.compare | real * real &rarr; order | "compare (x, y)" returns `LESS`, `EQUAL`, or `GREATER` according to whether its first argument is less than, equal to, or greater than the second. It raises `IEEEReal.Unordered` on unordered arguments. |
| Real.copySign | real * real &rarr; real | "copySign (x, y)" returns `x` with the sign of `y`, even if `y` is NaN. |
| Real.floor | real &rarr; int | "floor r" produces `floor(r)`, the largest int not larger than `r`. |
| Real.fromInt, real | int &rarr; real | "fromInt i" converts the integer `i` to a `real` value. If the absolute value of `i` is larger than `maxFinite`, then the appropriate infinity is returned. If `i` cannot be exactly represented as a `real` value, uses current rounding mode to determine the resulting value. |
| Real.fromManExp | {exp:int, man:real} &rarr; real | "fromManExp r" returns `{man, exp}`, where `man` and `exp` are the mantissa and exponent of r, respectively. |
| Real.fromString | string &rarr; real option | "fromString s" scans a `real` value from a string. Returns `SOME (r)` if a `real` value can be scanned from a prefix of `s`, ignoring any initial whitespace; otherwise, it returns `NONE`. This function is equivalent to `StringCvt.scanString scan`. |
| Real.isFinite | real &rarr; bool | "isFinite x" returns true if x is neither NaN nor an infinity. |
| Real.isNan | real &rarr; bool | "isNan x" returns true if x NaN. |
| Real.isNormal | real &rarr; bool | "isNormal x" returns true if x is normal, i.e., neither zero, subnormal, infinite nor NaN. |
| Real.max | real * real &rarr; real | "max (x, y)" returns the larger of the arguments. If exactly one argument is NaN, returns the other argument. If both arguments are NaN, returns NaN. |
| Real.maxFinite | real | "maxFinite" is the maximum finite number. |
| Real.min | real * real &rarr; real | "min (x, y)" returns the smaller of the arguments. If exactly one argument is NaN, returns the other argument. If both arguments are NaN, returns NaN. |
| Real.minNormalPos | real | "minNormalPos" is the minimum non-zero normalized number. |
| Real.minPos | real | "minPos" is the minimum non-zero positive number. |
| Real.negInf | real | "negInf" is the negative infinity value. |
| Real.posInf | real | "posInf" is the positive infinity value. |
| Real.precision | int | "precision" is the number of digits, each between 0 and `radix` - 1, in the mantissa. Note that the precision includes the implicit (or hidden) bit used in the IEEE representation (e.g., the value of Real64.precision is 53). |
| Real.radix | int | "radix" is the base of the representation, e.g., 2 or 10 for IEEE floating point. |
| Real.realCeil | real &rarr; real | "realCeil r" produces `ceil(r)`, the smallest integer not less than `r`. |
| Real.realFloor | real &rarr; real | "realFloor r" produces `floor(r)`, the largest integer not larger than `r`. |
| Real.realMod | real &rarr; real | "realMod r" returns the fractional parts of `r`; `realMod` is equivalent to `#frac o split`. |
| Real.realRound | real &rarr; real | "realRound r" rounds to the integer-valued real value that is nearest to `r`. In the case of a tie, it rounds to the nearest even integer. |
| Real.realTrunc | real &rarr; real | "realTrunc r" rounds `r` towards zero. |
| Real.rem | real * real &rarr; real | "rem (x, y)" returns the remainder `x - n * y`, where `n` = `trunc (x / y)`. The result has the same sign as `x` and has absolute value less than the absolute value of `y`. If `x` is an infinity or `y` is 0, `rem` returns NaN. If `y` is an infinity, rem returns `x`. |
| Real.round | real &rarr; int | "round r" yields the integer nearest to `r`. In the case of a tie, it rounds to the nearest even integer. |
| Real.sameSign | real * real &rarr; bool | "sameSign (r1, r2)" returns true if and only if `signBit r1` equals `signBit r2`. |
| Real.sign | real &rarr; int | "sign r" returns ~1 if r is negative, 0 if r is zero, or 1 if r is positive. An infinity returns its sign; a zero returns 0 regardless of its sign. It raises `Domain` on NaN. |
| Real.signBit | real &rarr; bool | "signBit r" returns true if and only if the sign of `r` (infinities, zeros, and NaN, included) is negative. |
| Real.split | real &rarr; {frac:real, whole:real} | "split r" returns `{frac, whole}`, where `frac` and `whole` are the fractional and integral parts of `r`, respectively. Specifically, `whole` is integral, and `abs frac` &lt; 1.0. |
| Real.trunc | real &rarr; int | "trunc r" rounds r towards zero. |
| Real.toManExp | real &rarr; {man:real, exp:int} | "toManExp r" returns `{man, exp}`, where `man` and `exp` are the mantissa and exponent of r, respectively. |
| Real.toString | real &rarr; string | "toString r" converts a `real` into a `string`; equivalent to `(fmt (StringCvt.GEN NONE) r)` |
| Real.unordered | real * real &rarr; bool | "unordered (x, y)" returns true if x and y are unordered, i.e., at least one of x and y is NaN. |
| Relational.compare | &alpha; * &alpha; &rarr; order | "compare (x, y)" returns `LESS`, `EQUAL`, or `GREATER` according to whether its first argument is less than, equal to, or greater than the second.  Comparisons are based on the structure of the type `&alpha;`. Primitive types are compared using their natural order; Option types compare with NONE last; Tuple types compare lexicographically; Record types compare lexicographically, with the fields compared in alphabetical order; List values compare lexicographically; Bag values compare lexicographically, the elements appearing in an order that is arbitrary but is consistent for each particular value. |
| Relational.count, count | int list &rarr; int | "count list" returns the number of elements in `list`. Often used with `group`, for example `from e in emps group e.deptno compute countId = count`. |
| Relational.empty, empty | &alpha; list &rarr; bool | "empty list" returns whether the list is empty, for example `from d in depts where empty (from e where e.deptno = d.deptno)`. |
| Relational.max, max | &alpha; list &rarr; &alpha; | "max list" returns the greatest element of `list`. Often used with `group`, for example `from e in emps group e.deptno compute maxId = max of e.id`. |
| Relational.min, min | &alpha; list &rarr; &alpha; | "min list" returns the least element of `list`. Often used with `group`, for example `from e in emps group e.deptno compute minId = min of e.id`. |
| Relational.nonEmpty, nonEmpty | &alpha; list &rarr; bool | "nonEmpty list" returns whether the list has at least one element, for example `from d in depts where nonEmpty (from e where e.deptno = d.deptno)`. |
| Relational.only, only | &alpha; list &rarr; &alpha; | "only list" returns the sole element of list, for example `from e in emps yield only (from d where d.deptno = e.deptno)`. |
| Relational.op elem | &alpha; * &alpha; bag &rarr; bool, &alpha; * &alpha; list &rarr; bool | "e elem collection" returns whether `e` is a member of `collection`. |
| Relational.op notelem | &alpha; * &alpha; bag &rarr; bool, &alpha; * &alpha; list &rarr; bool | "e notelem collection" returns whether `e` is not a member of `collection`. |
| Relational.sum, sum | int list &rarr; int | "sum list" returns the sum of the elements of `list`. Often used with `group`, for example `from e in emps group e.deptno compute sumId = sum of e.id`. |
| String.collate | (char * char -&gt; order) &rarr; string * string &rarr; order | "collate (f, (s, t))" performs lexicographic comparison of the two strings using the given ordering f on characters. |
| String.compare | string * string &rarr; order | "compare (s, t)" does a lexicographic comparison of the two strings using the ordering `Char.compare` on the characters. It returns `LESS`, `EQUAL`, or `GREATER`, if `s` is less than, equal to, or greater than `t`, respectively. |
| String.fields | (char &rarr; bool) &rarr; string &rarr; string list | "fields f s" returns a list of fields derived from `s` from left to right. A field is a (possibly empty) maximal substring of `s` not containing any delimiter. A delimiter is a character satisfying the predicate `f`.  Two tokens may be separated by more than one delimiter, whereas two fields are separated by exactly one delimiter. For example, if the only delimiter is the character `#"\|"`, then the string `"\|abc\|\|def"` contains two tokens `"abc"` and `"def"`, whereas it contains the four fields `""`, `"abc"`, `""` and `"def"`. |
| String.op ^ | string * string &rarr; string | "s ^ t" is the concatenation of the strings `s` and `t`. This raises `Size` if `\|s\| + \|t\| &gt; maxSize`. |
| String.concat | string list &rarr; string | "concat l" is the concatenation of all the strings in `l`. This raises `Size` if the sum of all the sizes is greater than `maxSize`. |
| String.concatWith | string &rarr; string list &rarr; string | "concatWith s l" returns the concatenation of the strings in the list `l` using the string `s` as a separator. This raises `Size` if the size of the resulting string would be greater than `maxSize`. |
| String.explode | string &rarr; char list | "explode s" is the list of characters in the string `s`. |
| String.extract | string * int * int option &rarr; string | "extract (s, i, NONE)" and "extract (s, i, SOME j)" return substrings of `s`. The first returns the substring of `s` from the `i`(th) character to the end of the string, i.e., the string `s`[`i`..\|`s`\|-1]. This raises `Subscript` if `i` &lt; 0 or \|`s`\| &lt; `i`.<br><br>The second form returns the substring of size `j` starting at index `i`, i.e., the string `s`[`i`..`i`+`j`-1]. Raises `Subscript` if `i` &lt; 0 or `j` &lt; 0 or \|`s`\| &lt; `i` + `j`. Note that, if defined, `extract` returns the empty string when `i` = \|`s`\|. |
| String.implode | char list &rarr; string | "implode l" generates the string containing the characters in the list `l`. This is equivalent to `concat (List.map str l)`. This raises `Size` if the resulting string would have size greater than `maxSize`. |
| String.isPrefix | string &rarr; string &rarr; bool | "isPrefix s1 s2" returns `true` if the string `s1` is a prefix of the string `s2`. Note that the empty string is a prefix of any string, and that a string is a prefix of itself. |
| String.isSubstring | string &rarr; string &rarr; bool | "isSubstring s1 s2" returns `true` if the string `s1` is a substring of the string `s2`. Note that the empty string is a substring of any string, and that a string is a substring of itself. |
| String.isSuffix | string &rarr; string &rarr; bool | "isSuffix s1 s2" returns `true` if the string `s1` is a suffix of the string `s2`. Note that the empty string is a suffix of any string, and that a string is a suffix of itself. |
| String.map | (char &rarr; char) &rarr; string &rarr; string | "map f s" applies `f` to each element of `s` from left to right, returning the resulting string. It is equivalent to `implode(List.map f (explode s))`. |
| String.maxSize | int | The longest allowed size of a string. |
| String.size | string &rarr; int | "size s" returns \|`s`\|, the number of characters in string `s`. |
| String.str | char &rarr; string | "str c" is the string of size one containing the character `c`. |
| String.sub | string * int &rarr; char | "sub (s, i)" returns the `i`(th) character of `s`, counting from zero. This raises `Subscript` if `i` &lt; 0 or \|`s`\| &le; `i`. |
| String.substring | string * int * int &rarr; string | "substring (s, i, j)" returns the substring `s`[`i`..`i`+`j`-1], i.e., the substring of size `j` starting at index `i`. This is equivalent to `extract(s, i, SOME j)`. |
| String.translate | (char &rarr; string) &rarr; string &rarr; string | "translate f s" returns the string generated from `s` by mapping each character in `s` by `f`. It is equivalent to `concat(List.map f (explode s))`. |
| String.tokens | (char &rarr; bool) &rarr; string &rarr; string list | "tokens f s" returns a list of tokens derived from `s` from left to right. A token is a non-empty maximal substring of `s` not containing any delimiter. A delimiter is a character satisfying the predicate `f`.  Two tokens may be separated by more than one delimiter, whereas two fields are separated by exactly one delimiter. For example, if the only delimiter is the character `#"\|"`, then the string `"\|abc\|\|def"` contains two tokens `"abc"` and `"def"`, whereas it contains the four fields `""`, `"abc"`, `""` and `"def"`. |
| Sys.clearEnv | unit &rarr; unit | "clearEnv ()" restores the environment to the initial environment. |
| Sys.env, env | unit &rarr; string list | "env ()" prints the environment. |
| Sys.plan | unit &rarr; string | "plan ()" prints the plan of the most recently executed expression. |
| Sys.set | string * &alpha; &rarr; unit | "set (property, value)" sets the value of `property` to `value`. (See [Properties](#properties) below.) |
| Sys.show | string &rarr; string option | "show property" returns the current the value of `property`, as a string, or `NONE` if unset. |
| Sys.showAll | unit &rarr; string * string option list | "showAll ()" returns a list of all properties and their current value as a string, or `NONE` if unset. |
| Sys.unset | string &rarr; unit | "unset property" clears the current the value of `property`. |
| Vector.all | (&alpha; &rarr; bool) &rarr; &alpha; vector &rarr; bool | "all f vec" applies `f` to each element `x` of the vector `vec`, from left to right, until `f(x)` evaluates to `false`. It returns `false` if such an `x` exists; otherwise it returns `true`. It is equivalent to `not(exists (not o f) vec)`. |
| Vector.app | (&alpha; &rarr; unit) &rarr; &alpha; vector &rarr; unit | "app f vec" applies the function `f` to the elements of a vector in left to right order (i.e., in order of increasing indices).<br><br>It is equivalent to <pre>List.app f (foldr (fn (a,l) =&gt; a::l) [] vec)</pre> |
| Vector.appi | (int * &alpha; &rarr; unit) &rarr; &alpha; vector &rarr; unit | "appi f vec" applies the function `f` to the elements of a vector in left to right order (i.e., in order of increasing indices).<br><br>It is equivalent to <pre>List.app f (foldri (fn (i,a,l) =&gt; (i,a)::l) [] vec)</pre> |
| Vector.collate |  | "collate f (v1, v2)" performs lexicographic comparison of the two vectors using the given ordering `f` on elements. |
| Vector.concat | &alpha; vector list &rarr; &alpha; vector | "concat l" returns the vector that is the concatenation of the vectors in the list `l`.  Raises `Size` if the total length of these vectors exceeds `maxLen` |
| Vector.exists |  | "exists f vec" applies `f` to each element `x` of the vector `vec`, from left to right (i.e., increasing indices), until `f(x)` evaluates to `true`; it returns `true` if such an `x` exists and `false` otherwise. |
| Vector.find | (&alpha; &rarr; bool) &rarr; &alpha; vector &rarr; &alpha; option | "find f vec" applies `f` to each element `x` of the vector `vec`, from left to right, until `f(x)` evaluates to `true`. It returns `SOME (x)` if such an `x` exists; otherwise it returns `NONE`. |
| Vector.findi | (int * &alpha; &rarr; bool) &rarr; &alpha; vector &rarr; (int * &alpha;) option | "findi f vec" applies `f` to each element `x` and element index `i` of the vector `vec`, from left to right, until `f(i, x)` evaluates to `true`. It returns `SOME (i, x)` if such an `x` exists; otherwise it returns `NONE`. |
| Vector.foldl | (&alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; vector &rarr; &beta; | "foldl f init vec" folds the function `f` over all the elements of vector `vec`, left to right, using the initial value `init`. |
| Vector.foldli | (int * &alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; vector &rarr; &beta; | "foldli f init vec" folds the function `f` over all the (index, element) pairs of vector `vec`, left to right, using the initial value `init`. |
| Vector.foldr | (&alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; vector &rarr; &beta; | "foldr f init vec" folds the function `f` over all the elements of vector `vec`, right to left, using the initial value `init`. |
| Vector.foldri | (int * &alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; vector &rarr; &beta; | "foldri f init vec" folds the function `f` over all the (index, element) pairs of vector `vec`, right to left, using the initial value `init`. |
| Vector.fromList | &alpha; list &rarr; &alpha; vector | "fromList l" creates a new vector from `l`, whose length is `length l` and with the `i`<sup>th</sup> element of `l` used as the `i`<sup>th</sup> element of the vector. Raises `Size` if `maxLen` &lt; `n`. |
| Vector.length | &alpha; vector &rarr; int | "length v" returns the number of elements in the vector `v`. |
| Vector.map | (&alpha; &rarr; &beta;) &rarr; &alpha; vector &rarr; &beta; vector | "map f vec" applies the function `f` to the elements of the argument vector `vec`.<br><br>It is equivalent to <pre>fromList (List.map f (foldr (fn (a,l) =&gt; a::l) [] vec))</pre> |
| Vector.mapi | (int * &alpha; &rarr; &beta;) &rarr; &alpha; vector &rarr; &beta; vector | "mapi f vec" applies the function `f` to the elements of the argument vector `vec`, supplying the vector index and element as arguments to each call.<br><br>It is equivalent to <pre>fromList (List.map f (foldri (fn (i,a,l) =&gt; (i,a)::l) [] vec))</pre> |
| Vector.maxLen | int | "maxLen" returns the maximum length of vectors supported in this implementation. |
| Vector.sub | &alpha; vector * int &rarr; &alpha; | "sub (vec, i)" returns the `i`<sup>th</sup> element of vector `vec`. Raises `Subscript` if `i` &lt; 0 or `size vec` &le; `i`. |
| Vector.tabulate | int * (int &rarr; &alpha;) &rarr; &alpha; vector | "tabulate (n, f)" returns a vector of length `n` equal to `[f(0), f(1), ..., f(n-1)]`, created from left to right. This is equivalent to the expression  <pre>fromList (List.tabulate (n, f))</pre>  Raises `Size` if `n` &lt; 0 or `maxLen` &lt; `n`. |
| Vector.update | &alpha; vector * int * &alpha; &rarr; &alpha; vector | "update (vec, i, x)" returns a new vector, identical to `vec`, except the `i`<sup>th</sup> element of `vec` is set to `x`. Raises `Subscript` if `i` &lt; 0 or `size vec` &le; `i`. |

Not yet implemented

| Name | Type | Description |
| ---- | ---- | ----------- |
| Int.fmt | StringCvt.radix &rarr; int &rarr; string | "fmt radix i" returns a string containing a representation of i with #"~" used as the sign for negative numbers. Formats the string according to `radix`; the hexadecimal digits 10 through 15 are represented as #"A" through #"F", respectively. No prefix "0x" is generated for the hexadecimal representation. |
| Int.scan | scan radix getc strm | Returns `SOME (i,rest)` if an integer in the format denoted by `radix` can be parsed from a prefix of the character stream `strm` after skipping initial whitespace, where `i` is the value of the integer parsed and `rest` is the rest of the character stream. `NONE` is returned otherwise. This function raises `Overflow` when an integer can be parsed, but is too large to be represented by type `int`. |
| Real.op != | real * real &rarr; bool | "x != y" is equivalent to `not o op ==` and the IEEE `?&lt;&gt;` operator. |
| Real.op *+ | real * real * real &rarr; real | "*+ (a, b, c)" returns `a * b + c`. Its behavior on infinities follows from the behaviors derived from addition and multiplication. |
| Real.op *- | real * real * real &rarr; real | "*- (a, b, c)" returns `a * b - c`. Its behavior on infinities follows from the behaviors derived from subtraction and multiplication. |
| Real.op == | real * real &rarr; bool | "x == y" returns true if and only if neither y nor x is NaN, and y and x are equal, ignoring signs on zeros. This is equivalent to the IEEE `=` operator. |
| Real.op ?= | real * real &rarr; bool | "?= (x, y)" returns true if either argument is NaN or if the arguments are bitwise equal, ignoring signs on zeros. It is equivalent to the IEEE `?=` operator. |
| Real.class | real &rarr; IEEEReal.float_class | "class x" returns the `IEEEReal.float_class` to which x belongs. |
| Real.compareReal | real * real &rarr; IEEEReal.real_order | "compareReal (x, y)" behaves similarly to `Real.compare` except that the values it returns have the extended type `IEEEReal.real_order` and it returns `IEEEReal.UNORDERED` on unordered arguments. |
| Real.fmt | StringCvt.realfmt &rarr; real &rarr; string | "fmt spec r" converts a `real` into a `string` according to by `spec`; raises `Size` when `fmt spec` is evaluated if `spec` is an invalid precision |
| Real.fromDecimal | IEEEReal.decimal_approx &rarr; real | "fromDecimal d" converts decimal approximation to a `real` |
| Real.fromLarge | IEEEReal.rounding_mode &rarr; real &rarr; real | "toLarge r" converts a value of type `real` to type `LargeReal.real`. If `r` is too small or too large to be represented as a real, converts it to a zero or an infinity. |
| Real.fromLargeInt | IntInf.int &rarr; real | See "fromInt" |
| Real.nextAfter | real * real &rarr; real | "nextAfter (r, t)" returns the next representable real after `r` in the direction of `t`. Thus, if `t` is less than `r`, `nextAfter` returns the largest representable floating-point number less than `r`. |
| Real.scan | (char,'a) StringCvt.reader &rarr; (real,'a) StringCvt.reader | "scan getc strm" scans a `real` value from character source. Reads from ARG/strm/ using reader `getc`, ignoring initial whitespace. It returns `SOME (r, rest)` if successful, where `r` is the scanned `real` value and `rest` is the unused portion of the character stream `strm`. Values of too large a magnitude are represented as infinities; values of too small a magnitude are represented as zeros. |
| Real.toDecimal | real &rarr; IEEEReal.decimal_approx | "toDecimal r" converts a `real` to a decimal approximation |
| Real.toInt | real &rarr; IEEEReal.rounding_mode &rarr; int | "toInt mode x" converts the argument `x` to an integral type using the specified rounding mode. It raises `Overflow` if the result is not representable, in particular, if `x` is an infinity. It raises `Domain` if the input real is NaN. |
| Real.toLarge | real &rarr; real | "toLarge r" convert a value of type `real` to type `LargeReal.real`. |
| Real.toLargeInt | real &rarr; IEEEReal.rounding_mode &rarr; IntInf.int | See "toInt" |
| String.fromCString | string &rarr; string option | "fromCString s" scans the string `s` as a string in the C language, converting C escape sequences into the appropriate characters. The semantics are identical to `fromString` above, except that C escape sequences are used (see ISO C standard ISO/IEC 9899:1990).  For more information on the allowed escape sequences, see the entry for `CHAR.fromCString`. Note that `fromCString` accepts an unescaped single quote character, but does not accept an unescaped double quote character. |
| String.toString | string &rarr; string | "toString s" returns a string corresponding to `s`, with non-printable characters replaced by SML escape sequences. This is equivalent to  <pre>translate Char.toString s</pre> |
| String.scan | (char,&alpha;) StringCvt.reader &rarr; (string,&alpha;) StringCvt.reader | "scan getc strm" scans its character source as a sequence of printable characters, converting SML escape sequences into the appropriate characters. It does not skip leading whitespace. It returns as many characters as can successfully be scanned, stopping when it reaches the end of the string or a non-printing character (i.e., one not satisfying `isPrint`), or if it encounters an improper escape sequence. It returns the remaining characters as the rest of the stream. |
| String.fromString | string &rarr; string option | "fromString s" scans the string `s` as a sequence of printable characters, converting SML escape sequences into the appropriate characters. It does not skip leading whitespace. It returns as many characters as can successfully be scanned, stopping when it reaches the end of the string or a non-printing character (i.e., one not satisfying `isPrint`), or if it encounters an improper escape sequence. It ignores the remaining characters.  If no conversion is possible, e.g., if the first character is non-printable or begins an illegal escape sequence, `NONE` is returned. Note, however, that `fromString ""` returns `SOME("")`.  For more information on the allowed escape sequences, see the entry for `CHAR.fromString`. SML source also allows escaped formatting sequences, which are ignored during conversion. The rule is that if any prefix of the input is successfully scanned, including an escaped formatting sequence, the function returns some string. It only returns `NONE` in the case where the prefix of the input cannot be scanned at all. Here are some sample conversions:  <pre> Input string s fromString s ============== ============ "\\q"          NONE "a\^D"         SOME "a" "a\\ \\\\q" SOME "a" "\\ \\"      SOME "" ""             SOME "" "\\ \\\^D"     SOME "" "\\ a"         NONE </pre>  *Implementation note*: Because of the special cases, such as `fromString ""` = `SOME ""`, `fromString "\\ \\\^D"` = `SOME ""`, and `fromString "\^D" = NONE`, the function cannot be implemented as a simple iterative application of `CHAR.scan`. |
| String.toCString | string &rarr; string | "toCString s" returns a string corresponding to `s`, with non-printable characters replaced by C escape sequences. This is equivalent to  <pre>translate Char.toCString s</pre> |

{% comment %}END TABLE{% endcomment %}

## Properties

Each property is set using the function `Sys.set (name, value)`,
displayed using `Sys.show name`,
and unset using `Sys.unset name`.
`Sys.showAll ()` shows all properties and their values.

| Name                 | Type | Default | Description |
| -------------------- | ---- | ------- | ----------- |
| hybrid               | bool | false   | Whether to try to create a hybrid execution plan that uses Apache Calcite relational algebra. |
| inlinePassCount      | int  | 5       | Maximum number of inlining passes. |
| lineWidth            | int  | 79      | When printing, the length at which lines are wrapped. |
| matchCoverageEnabled | bool | true    | Whether to check whether patterns are exhaustive and/or redundant. |
| output               | enum | classic | How values should be formatted. "classic" (the default) prints values in a compact nested format; "tabular" prints values in a table if their type is a list of records. |
| printDepth           | int  | 5       | When printing, the depth of nesting of recursive data structure at which ellipsis begins. |
| printLength          | int  | 12      | When printing, the length of lists at which ellipsis begins. |
| stringDepth          | int  | 70      | When printing, the length of strings at which ellipsis begins. |
