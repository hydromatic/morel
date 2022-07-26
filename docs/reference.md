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
* `from` expression with `in`, `join`, `where`, `group`,
  `compute`, `order`, `yield` clauses
* `union`, `except`, `intersect`, `elem`, `notelem` operators
* "*lab* `=`" is optional in `exprow`

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

## Identifiers

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
    | <b>from</b> [ <i>scan<sub>1</sub></i>  <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i> ] <i>step</i>*
                                relational expression (<i>s</i> &ge; 0)
<i>exprow</i> &rarr; <i>exprowItem</i> [<b>,</b> <i>exprowItem</i> ]*
                                expression row
<i>exprowItem</i> &rarr; [ <i>lab</i> <b>=</b> ] <i>exp</i>
<i>match</i> &rarr; <i>matchItem</i> [ '<b>|</b>' <i>matchItem</i> ]*
                                match
<i>matchItem</i> &rarr; <i>pat</i> <b>=&gt;</b> <i>exp</i>
<i>scan</i> &rarr; <i>pat</i> [ <b>in</b> | <b>=</b> ] <i>exp</i>
<i>step</i> &rarr; <b>where</b> <i>exp</i>                filter clause
    | <b>join</b> <i>scan</i> [ <b>on</b> <i>exp</i> ]      join clause
    | <b>group</b> <i>groupKey<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>groupKey<sub>g</sub></i>
      [ <b>compute</b> <i>agg<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>agg<sub>a</sub></i> ]
                                group clause (<i>g</i> &ge; 0, <i>a</i> &ge; 1)
    | <b>compute</b> <i>agg<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>agg<sub>a</sub></i>
                                compute clause (<i>a</i> &gt; 1)
    | <b>order</b> <i>orderItem<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>orderItem<sub>o</sub></i>
                                order clause (<i>o</i> &ge; 1)
    | <b>yield</b> <i>exp</i>
<i>groupKey</i> &rarr; [ <i>id</i> <b>=</b> ] <i>exp</i>
<i>agg</i> &rarr; [ <i>id</i> <b>=</b> ] <i>exp</i> [ <b>of</b> <i>exp</i> ]
<i>orderItem</i> &rarr; <i>exp</i> [ <b>desc</b> ]
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
<i>typrow</i> &rarr; <i>lab</i> : <i>typ</i> [, <i>typrow</i>]   type row
</pre>

### Declarations

<pre>
<i>dec</i> &rarr; <i>vals</i> <i>valbind</i>              value
    | <b>fun</b> <i>vars</i> <i>funbind</i>          function
    | <b>datatype</b> <i>datbind</i>          data type
    | <i>empty</i>
    | <i>dec<sub>1</sub></i> [<b>;</b>] <i>dec<sub>2</sub></i>              sequence
<i>valbind</i> &rarr; <i>pat</i> <b>=</b> <i>exp</i> [ <b>and</b> <i>valbind</i> ]*
                                destructuring
    | <b>rec</b> <i>valbind</i>               recursive
<i>funbind</i> &rarr; <i>funmatch</i> [ <b>and</b> <i>funmatch</i> ]*
                                clausal function
<i>funmatch</i> &rarr; <i>funmatchItem</i> [ '<b>|</b>' funmatchItem ]*
<i>funmatchItem</i> &rarr; [ <b>op</b> ] <i>id</i> <i>pat<sub>1</sub></i> ... <i>pat<sub>n</sub></i> [ <b>:</b> <i>type</i> ] <b>=</b> <i>exp</i>
                                nonfix (n &ge; 1)
    | <i>pat<sub>1</sub></i> <i>id</i> <i>pat<sub>2</sub></i> [ <b>:</b> <i>type</i> ] <b>=</b> <i>exp</i>        infix
    | '<b>(</b>' <i>pat<sub>1</sub></i> <i>id</i> <i>pat<sub>2</sub></i> '<b>)</b>' <i>pat'<sub>1</sub></i> ... <i>pat'<sub>n</sub></i> [ <b>:</b> <i>type</i> ] = <i>exp</i>
                                infix (n &ge; 0)
<i>datbind</i> &rarr; <i>datbindItem</i> [ <b>and</b> <i>datbindItem</i> ]*
                                data type
<i>datbindItem</i> &rarr; <i>vars</i> <i>id</i> <b>=</b> <i>conbind</i>
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
| intersect |   infix 7 | List intersect |
| +        |    infix 6 | Plus |
| -        |    infix 6 | Minus |
| ^        |    infix 6 | String concatenate |
| union    |    infix 6 | List union |
| except   |    infix 6 | List difference |
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

## Built-in types

Primitive: `bool`, `char`, `int`, `real`, `string`, `unit`

Datatype:
* `datatype 'a list = nil | :: of 'a * 'a list` (in structure `List`)
* `datatype 'a option = NONE | SOME of 'a` (in structure `Option`)
* `datatype 'a order = LESS | EQUAL | GREATER` (in structure `General`)

Eqtype:
* `eqtype 'a vector = 'a vector` (in structure `Vector`)

Exception:
* `Empty` (in structure `List`)
* `Option` (in structure `Option`)
* `Size` (in structure `General`)
* `Subscript` (in structure `General`)

## Built-in functions

| Name | Type | Description |
| ---- | ---- | ----------- |
| true | bool | Literal true. |
| false | bool | Literal false. |
| not | bool &rarr; bool | "not b" returns the logical inverse of `b`. |
| abs | int &rarr; int | "abs n" returns the absolute value of `n`. |
| General.ignore | &alpha; &rarr; unit | "ignore x" always returns `unit`. The function evaluates its argument but throws away the value. |
| General.op o | (&beta; &rarr; &gamma;) (&alpha; &rarr; &beta;) &rarr; &alpha; &rarr; &gamma; | "f o g" is the function composition of `f` and `g`. Thus, `(f o g) a` is equivalent to `f (g a)`. |
| Interact.use | string &rarr; unit | "use f" loads source text from the file named `f`. |
| List.nil | &alpha; list | "nil" is the empty list. |
| List.null | &alpha; list &rarr; bool | "null l" returns `true` if the list `l` is empty. |
| List.length | &alpha; list &rarr; int | "length l" returns the number of elements in the list `l`. |
| List.op @ | &alpha; list * &alpha; list &rarr; &alpha; list | "l1 @ l2" returns the list that is the concatenation of `l1` and `l2`. |
| List.at | &alpha; list * &alpha; list &rarr; &alpha; list | "at (l1, l2)" is equivalent to "l1 @ l2". |
| List.hd | &alpha; list &rarr; &alpha; | "hd l" returns the first element of `l`. Raises `Empty` if `l` is `nil`. |
| List.tl | &alpha; list &rarr; &alpha; list | "tl l" returns all but the first element of `l`. Raises `Empty` if `l` is `nil`. |
| List.last | &alpha; list &rarr; &alpha; | "last l" returns the last element of `l`. Raises `Empty` if `l` is `nil`. |
| List.getItem | &alpha; list &rarr; * (&alpha; * &alpha; list) option | "getItem l" returns `NONE` if the `list` is empty, and `SOME(hd l,tl l)` otherwise. This function is particularly useful for creating value readers from lists of characters. For example, `Int.scan StringCvt.DEC getItem` has the type `(int, char list) StringCvt.reader` and can be used to scan decimal integers from lists of characters. |
| List.nth | &alpha; list * int &rarr; &alpha; | "nth (l, i)" returns the `i`(th) element of the list `l`, counting from 0. Raises `Subscript` if `i` &lt; 0 or `i` &ge; `length l`. We have `nth(l, 0)` = `hd l`, ignoring exceptions. |
| List.take | &alpha; list * int &rarr; &alpha; list | "take (l, i)" returns the first `i` elements of the list `l`. Raises `Subscript` if `i` &lt; 0 or `i` &gt; `length l`. We have `take(l, length l)` = `l`. |
| List.drop | &alpha; list * int &rarr; &alpha; list | "drop (l, i)" returns what is left after dropping the first `i` elements of the list `l`. Raises `Subscript` if `i` &lt; 0 or `i` &gt; `length l`.<br><br>It holds that `take(l, i) @ drop(l, i)` = `l` when 0 &le; `i` &le; `length l`. We also have `drop(l, length l)` = `[]`. |
| List.rev | &alpha; list &rarr; &alpha; list | "rev l" returns a list consisting of `l`'s elements in reverse order. |
| List.concat | &alpha; list list &rarr; &alpha; list | "concat l" returns the list that is the concatenation of all the lists in `l` in order. `concat [l1, l2, ... ln]` = `l1 @ l2 @ ... @ ln` |
| List.revAppend | &alpha; list * &alpha; list &rarr; &alpha; list | "revAppend (l1, l2)" returns `(rev l1) @ l2`. |
| List.app | (&alpha; &rarr; unit) &rarr; &alpha; list &rarr; unit | "app f l" applies `f` to the elements of `l`, from left to right. |
| List.map | (&alpha; &rarr; &beta;) &rarr; &alpha; list &rarr; &beta; list | "map f l" applies `f` to each element of `l` from left to right, returning the list of results. |
| List.mapPartial | (&alpha; &rarr; &beta; option) &rarr; &alpha; list &rarr; &beta; list | "mapPartial f l" applies `f` to each element of `l` from left to right, returning a list of results, with `SOME` stripped, where `f` was defined. `f` is not defined for an element of `l` if `f` applied to the element returns `NONE`. The above expression is equivalent to `((map valOf) o (filter isSome) o (map f)) l`. |
| List.find | (&alpha; &rarr; bool) &rarr; &alpha; list &rarr; &alpha; option | "find f l" applies `f` to each element `x` of the list `l`, from left to right, until `f x` evaluates to `true`. It returns `SOME(x)` if such an `x` exists; otherwise it returns `NONE`. |
| List.filter | (&alpha; &rarr; bool) &rarr; &alpha; list &rarr; &alpha; list | "filter f l" applies `f` to each element `x` of `l`, from left to right, and returns the list of those `x` for which `f x` evaluated to `true`, in the same order as they occurred in the argument list. |
| List.partition | (&alpha; &rarr; bool) &rarr; &alpha; list &rarr; &alpha; list * &alpha; list | "partition f l" applies `f` to each element `x` of `l`, from left to right, and returns a pair `(pos, neg)` where `pos` is the list of those `x` for which `f x` evaluated to `true`, and `neg` is the list of those for which `f x` evaluated to `false`. The elements of `pos` and `neg` retain the same relative order they possessed in `l`. |
| List.foldl | (&alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; list &rarr; &beta; | "foldl f init \[x1, x2, ..., xn\]" returns `f(xn, ... , f(x2, f(x1, init))...)` or `init` if the list is empty. |
| List.foldr | (&alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; list &rarr; &beta; | "foldr f init \[x1, x2, ..., xn\]" returns `f(x1, f(x2, ..., f(xn, init)...))` or `init` if the list is empty. |
| List.exists | (&alpha; &rarr; bool) &rarr; &alpha; list &rarr; bool | "exists f l" applies `f` to each element `x` of the list `l`, from left to right, until `f(x)` evaluates to `true`; it returns `true` if such an `x` exists and `false` otherwise. |
| List.all | (&alpha; &rarr; bool) &rarr; &alpha; list &rarr; bool | "all f l" applies `f` to each element `x` of the list `l`, from left to right, `f(x)` evaluates to `false`; it returns `false` if such an `x` exists and `true` otherwise. It is equivalent to `not(exists (not o f) l))`. |
| List.tabulate | int * (int &rarr; &alpha;) &rarr; &alpha; list | "tabulate (n, f)" returns a list of length `n` equal to `[f(0), f(1), ..., f(n-1)]`, created from left to right. Raises `Size` if `n` &lt; 0. |
| List.collate | (&alpha; * &alpha; &rarr; order) &rarr; &alpha; list * &alpha; list &rarr; order | "collate f (l1, l2)" performs lexicographic comparison of the two lists using the given ordering `f` on the list elements. |
| Math.acos | real &rarr; real | "acos x" returns the arc cosine of `x`. `acos` is the inverse of `cos`. Its result is guaranteed to be in the closed interval [0, pi]. If the magnitude of `x` exceeds 1.0, returns NaN. |
| Math.asin | real &rarr; real | "asin x" returns the arc sine of `x`. `asin` is the inverse of `sin`. Its result is guaranteed to be in the closed interval [-pi / 2, pi / 2]. If the magnitude of `x` exceeds 1.0, returns NaN. |
| Math.atan | real &rarr; real | "atan x" returns the arc tangent of `x`. `atan` is the inverse of `tan`. For finite arguments, the result is guaranteed to be in the open interval (-pi / 2, pi / 2). If `x` is +infinity, it returns pi / 2; if `x` is -infinity, it returns -pi / 2. |
| Math.atan2 | real * real &rarr; real | "atan2 (y, x)" returns the arc tangent of `(y / x)` in the closed interval [-pi, pi], corresponding to angles within +-180 degrees. The quadrant of the resulting angle is determined using the signs of both `x` and `y`, and is the same as the quadrant of the point `(x, y)`. When `x` = 0, this corresponds to an angle of 90 degrees, and the result is `(real (sign y)) * pi / 2.0`. |
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
| Option.app | (&alpha; &rarr; unit) &rarr; &alpha; option &rarr; unit | "app f opt" applies the function `f` to the value `v` if `opt` is `SOME v`, and otherwise does nothing |
| Option.compose | (&alpha; &rarr; &beta;) * (&gamma; &rarr; &alpha; option) &rarr; &gamma; &rarr; &beta; option | "compose (f, g) a" returns `NONE` if `g(a)` is `NONE`; otherwise, if `g(a)` is `SOME v`, it returns `SOME (f v)`. |
| Option.composePartial | (&alpha; &rarr; &beta; option) * (&gamma; &rarr; &alpha; option) &rarr; &gamma; &rarr; &beta; option | "composePartial (f, g) a" returns `NONE` if `g(a)` is `NONE`; otherwise, if `g(a)` is `SOME v`, returns `f(v)`. |
| Option.map | &alpha; &rarr; &beta;) &rarr; &alpha; option &rarr; &beta; option | "map f opt" maps `NONE` to `NONE` and `SOME v` to `SOME (f v)`. |
| Option.mapPartial | &alpha; &rarr; &beta; option) &rarr; &alpha; option &rarr; &beta; option | "mapPartial f opt" maps `NONE` to `NONE` and `SOME v` to `f(v)`. |
| Option.getOpt | &alpha; option * &alpha; &rarr; &alpha; | "getOpt (opt, a)" returns `v` if `opt` is `SOME(v)`; otherwise returns `a`. |
| Option.isSome | &alpha; option &rarr; bool | "isSome opt" returns `true` if `opt` is `SOME v`; otherwise returns `false`. |
| Option.filter | (&alpha; &rarr; bool) &rarr; &alpha; &rarr; &alpha; option | "filter f a" returns `SOME a` if `f(a)` is `true`, `NONE` otherwise. |
| Option.flatten | &alpha; option option &rarr; &alpha; option | "flatten opt" maps `NONE` to `NONE` and `SOME v` to `v`. |
| Option.valOf | &alpha; option &rarr; &alpha; | "valOf opt" returns `v` if `opt` is `SOME v`, otherwise raises `Option`. |
| Real op * | real * real &rarr; real | "r1 * r2" is the product of `r1` and `r2`. The product of zero and an infinity produces NaN. Otherwise, if one argument is infinite, the result is infinite with the correct sign, e.g., -5 * (-infinity) = infinity, infinity * (-infinity) = -infinity. |
| Real op + | real * real &rarr; real | "r1 + r2" is the sum of `r1` and `r2`. If one argument is finite and the other infinite, the result is infinite with the correct sign, e.g., 5 - (-infinity) = infinity. We also have infinity + infinity = infinity and (-infinity) + (-infinity) = (-infinity). Any other combination of two infinities produces NaN. |
| Real op - | real * real &rarr; real | "r1 - r2" is the difference of `r1` and `r2`. If one argument is finite and the other infinite, the result is infinite with the correct sign, e.g., 5 - (-infinity) = infinity. We also have infinity + infinity = infinity and (-infinity) + (-infinity) = (-infinity). Any other combination of two infinities produces NaN. |
| Real op / | real * real &rarr; real | "r1 / r2" is the quotient of `r1` and `r2`. We have 0 / 0 = NaN and +-infinity / +-infinity = NaN. Dividing a finite, non-zero number by a zero, or an infinity by a finite number produces an infinity with the correct sign. (Note that zeros are signed.) A finite number divided by an infinity is 0 with the correct sign. |
| Real op &lt; | real * real &rarr; bool | "x &lt; y" returns true if x is less than y. Return false on unordered arguments, i.e., if either argument is NaN, so that the usual reversal of comparison under negation does not hold, e.g., `a &lt; b` is not the same as `not (a &gt;= b)`. |
| Real op &lt;= | real * real &rarr; bool | As "&lt;" |
| Real op &gt; | real * real &rarr; bool | As "&lt;" |
| Real op &gt;= | real * real &rarr; bool | As "&lt;" |
| Real op ~ | real &rarr; real | "~ r" returns the negation of `r`. |
| Real.abs | real &rarr; real | "abs r" returns the absolute value of `r`. |
| Real.ceil | real &rarr; int | "floor r" produces `ceil(r)`, the smallest int not less than `r`. |
| Real.checkFloat | real &rarr; real | "checkFloat x" raises `Overflow` if x is an infinity, and raises `Div` if x is NaN. Otherwise, it returns its argument. |
| Real.compare | real * real &rarr; order | "compare (x, y)" returns `LESS`, `EQUAL`, or `GREATER` according to whether its first argument is less than, equal to, or greater than the second. It raises `IEEEReal.Unordered` on unordered arguments. |
| Real.copySign | real * real &rarr; real | "copySign (x, y)" returns `x` with the sign of `y`, even if `y` is NaN. |
| Real.floor | real &rarr; int | "floor r" produces `floor(r)`, the largest int not larger than `r`. |
| Real.fromInt, real | int &rarr; real | "fromInt i" converts the integer `i` to a `real` value. If the absolute value of `i` is larger than `maxFinite`, then the appropriate infinity is returned. If `i` cannot be exactly represented as a `real` value, uses current rounding mode to determine the resulting value. |
| Real.fromManExp | {exp:int, man:real} &rarr; real | "fromManExp r" returns `{man, exp}`, where `man` and `exp` are the mantissa and exponent of r, respectively. |
| Real.fromString | string &rarr; real option | "fromString s" scans a `real` value from a string. Returns `SOME(r)` if a `real` value can be scanned from a prefix of `s`, ignoring any initial whitespace; otherwise, it returns `NONE`. This function is equivalent to `StringCvt.scanString scan`. |
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
| Real.sign | real &rarr; int | "sign r" returns ~1 if r is negative, 0 if r is zero, or 1 if r is positive. An infinity returns its sign; a zero returns 0 regardless of its sign. It raises Domain on NaN. |
| Real.signBit | real &rarr; bool | "signBit r" returns true if and only if the sign of `r` (infinities, zeros, and NaN, included) is negative. |
| Real.split | real &rarr; {frac:real, whole:real} | "split r" returns `{frac, whole}`, where `frac` and `whole` are the fractional and integral parts of `r`, respectively. Specifically, `whole` is integral, and `abs frac` &lt; 1.0. |
| Real.trunc | real &rarr; int | "trunc r" rounds r towards zero. |
| Real.toManExp | real &rarr; {man:real, exp:int} | "toManExp r" returns `{man, exp}`, where `man` and `exp` are the mantissa and exponent of r, respectively. |
| Real.toString | real &rarr; string | "toString r" converts a `real` into a `string`; equivalent to `(fmt (StringCvt.GEN NONE) r)` |
| Real.unordered | real * real &rarr; bool | "unordered (x, y)" returns true if x and y are unordered, i.e., at least one of x and y is NaN. |
| Relational.count, count | int list &rarr; int | "count list" returns the number of elements in `list`. Often used with `group`, for example `from e in emps group e.deptno compute countId = count`. |
| Relational.exists | &alpha; list &rarr; bool | "exists list" returns whether the list has at least one element, for example `from d in depts where exists (from e where e.deptno = d.deptno)`. |
| Relational.notExists | &alpha; list &rarr; bool | "notExists list" returns whether the list is empty, for example `from d in depts where notExists (from e where e.deptno = d.deptno)`. |
| Relational.only | &alpha; list &rarr; &alpha; | "only list" returns the sole element of list, for example `from e in emps yield only (from d where d.deptno = e.deptno)`. |
| Relational.max, max | &alpha; list &rarr; &alpha; | "max list" returns the greatest element of `list`. Often used with `group`, for example `from e in emps group e.deptno compute maxId = max of e.id`. |
| Relational.min, min | &alpha; list &rarr; &alpha; | "min list" returns the least element of `list`. Often used with `group`, for example `from e in emps group e.deptno compute minId = min of e.id`. |
| Relational.sum, sum | int list &rarr; int | "sum list" returns the sum of the elements of `list`. Often used with `group`, for example `from e in emps group e.deptno compute sumId = sum of e.id`. |
| String.concat | string list &rarr; string | "concat l" is the concatenation of all the strings in `l`. This raises `Size` if the sum of all the sizes is greater than `maxSize`. |
| String.concatWith | string &rarr; string list &rarr; string | "concatWith s l" returns the concatenation of the strings in the list `l` using the string `s` as a separator. This raises `Size` if the size of the resulting string would be greater than `maxSize`. |
| String.explode | string &rarr; char list | "explode s" is the list of characters in the string `s`. |
| String.extract | string * int * int option &rarr; string | "extract (s, i, NONE)" and "extract (s, i, SOME j)" return substrings of `s`. The first returns the substring of `s` from the `i`(th) character to the end of the string, i.e., the string `s`\[`i`..\|`s`\|-1\]. This raises `Subscript` if `i` &lt; 0 or \|`s`\| &lt; `i`.<br><br>The second form returns the substring of size `j` starting at index `i`, i.e., the string `s`\[`i`..`i`+`j`-1\]. Raises `Subscript` if `i` &lt; 0 or `j` &lt; 0 or \|`s`\| &lt; `i` + `j`. Note that, if defined, `extract` returns the empty string when `i` = \|`s`\|. |
| String.implode | char list &rarr; string | "implode l" generates the string containing the characters in the list `l`. This is equivalent to `concat (List.map str l)`. This raises `Size` if the resulting string would have size greater than `maxSize`. |
| String.isPrefix | string &rarr; string &rarr; bool | "isPrefix s1 s2" returns `true` if the string `s1` is a prefix of the string `s2`. Note that the empty string is a prefix of any string, and that a string is a prefix of itself. |
| String.isSubstring | string &rarr; string &rarr; bool | "isSubstring s1 s2" returns `true` if the string `s1` is a substring of the string `s2`. Note that the empty string is a substring of any string, and that a string is a substring of itself. |
| String.isSuffix | string &rarr; string &rarr; bool | "isSuffix s1 s2" returns `true` if the string `s1` is a suffix of the string `s2`. Note that the empty string is a suffix of any string, and that a string is a suffix of itself. |
| String.map | (char &rarr; char) &rarr; string &rarr; string | "map f s" applies `f` to each element of `s` from left to right, returning the resulting string. It is equivalent to `implode(List.map f (explode s))`. |
| String.maxSize | int | The longest allowed size of a string. |
| String.size | string &rarr; int | "size s" returns \|`s`\|, the number of characters in string `s`. |
| String.str | char &rarr; string | "str c" is the string of size one containing the character `c`. |
| String.sub | string * int &rarr; char | "sub (s, i)" returns the `i`(th) character of `s`, counting from zero. This raises `Subscript` if `i` &lt; 0 or \|`s`\| &le; `i`. |
| String.substring | string * int * int &rarr; string | "substring (s, i, j)" returns the substring `s`\[`i`..`i`+`j`-1\], i.e., the substring of size `j` starting at index `i`. This is equivalent to `extract(s, i, SOME j)`. |
| String.translate | (char &rarr; string) &rarr; string &rarr; string | "translate f s" returns the string generated from `s` by mapping each character in `s` by `f`. It is equivalent to `concat(List.map f (explode s))`. |
| Sys.env, env | unit &rarr; string list | "env ()" prints the environment. |
| Sys.plan | unit &rarr; string | "plan ()" print the plan of the most recently executed expression. |
| Sys.set | string * &alpha; &rarr; unit | "set (property, value)" sets the value of `property` to `value`. (See [Properties](#properties) below.) |
| Sys.show | string &rarr; string option | "show property" returns the current the value of `property`, as a string, or `NONE` if unset. |
| Sys.unset | string &rarr; unit | "unset property" clears the current the value of `property`. |
| Vector.all |
| Vector.app | (&alpha; &rarr; unit) &rarr; &alpha; vector &rarr; unit | "app f vec" applies the function `f` to the elements of a vector in left to right order (i.e., in order of increasing indices) |
| Vector.appi | (int * &alpha; &rarr; unit) &rarr; &alpha; vector &rarr; unit | "appi f vec" applies the function `f` to the elements of a vector in left to right order (i.e., in order of increasing indices) |
| Vector.collate |
| Vector.concat | &alpha; vector list &rarr; &alpha; vector | "concat l" returns the vector that is the concatenation of the vectors in the list `l`. Raises `Size` if the total length of these vectors exceeds `maxLen` |
| Vector.exists |
| Vector.find | (&alpha; &rarr; bool) &rarr; &alpha; vector &rarr; &alpha; option | "find f vec" applies `f` to each element `x` of the vector `vec`, from left to right, until `f(x)` evaluates to `true`. It returns `SOME(x)` if such an `x` exists; otherwise it returns `NONE`. |
| Vector.findi | (int * &alpha; &rarr; bool) &rarr; &alpha; vector &rarr; (int * &alpha;) option | "findi f vec" applies `f` to each element `x` and element index `i` of the vector `vec`, from left to right, until `f(i, x)` evaluates to `true`. It returns `SOME(i, x)` if such an `x` exists; otherwise it returns `NONE`. |
| Vector.foldl | (&alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; vector &rarr; &beta; | "foldl f init vec" folds the function `f` over all the elements of vector `vec`, left to right, using the initial value `init` |
| Vector.foldli | (int * &alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; vector &rarr; &beta; | "foldli f init vec" folds the function `f` over all the (index, element) pairs of vector `vec`, left to right, using the initial value `init` |
| Vector.foldr | (&alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; vector &rarr; &beta; | "foldr f init vec" folds the function `f` over all the elements of vector `vec`, right to left, using the initial value `init` |
| Vector.foldri | (int * &alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; vector &rarr; &beta; | "foldri f init vec" folds the function `f` over all the (index, element) pairs of vector `vec`, right to left, using the initial value `init` |
| Vector.fromList | &alpha; list &rarr; &alpha; vector | "fromList l" creates a new vector from `l`, whose length is `length l` and with the i<sup>th</sup> element of `l` used as the `i`<sup>th</sup> element of the vector. Raises `Size` if `maxLen` &lt; `n`. |
| Vector.length | &alpha; vector &rarr; int | "length v" returns the number of elements in the vector `v`. |
| Vector.map | (&alpha; &rarr; &beta;) &rarr; &alpha; vector &rarr; &beta; vector | "map f vec" applies the function `f` to the elements of the argument vector `vec` |
| Vector.mapi | (int * &alpha; &rarr; &beta;) &rarr; &alpha; vector &rarr; &beta; vector | "mapi f vec" applies the function `f` to the elements of the argument vector `vec`, supplying the vector index and element as arguments to each call. |
| Vector.maxLen | int | "maxLen" returns the maximum length of vectors supported in this implementation. |
| Vector.sub | &alpha; vector * int &rarr; &alpha; | "sub (vec, i)" returns the `i`<sup>th</sup> element of vector `vec`. Raises `Subscript` if `i` &lt; 0 or `size vec` &le; `i`. |
| Vector.tabulate | int * (int &rarr; &alpha;) &rarr; &alpha; vector | "tabulate (n, f)" returns a vector of length `n` equal to `[f(0), f(1), ..., f(n-1)]`, created from left to right. Raises `Size` if `n` &lt; 0 or `maxLen` &lt; `n`. |
| Vector.update | &alpha; vector * int * &alpha; &rarr; &alpha; vector | "update (vec, i, x)" returns  a new vector, identical to `vec`, except the `i`<sup>th</sup> element of `vec` is set to `x`. Raises `Subscript` if `i` &lt; 0 or `size vec` &le; `i`. |

Not yet implemented

| Name | Type | Description |
| ---- | ---- | ----------- |
| Real op != | real * real &rarr; bool | "x != y" is equivalent to `not o op ==` and the IEEE `?<>` operator. |
| Real op *+ | real * real * real &rarr; real | "*+ (a, b, c)" returns `a * b + c`. Its behavior on infinities follows from the behaviors derived from addition and multiplication. |
| Real op *- | real * real * real &rarr; real | "*- (a, b, c)" returns `a * b - c`. Its behavior on infinities follows from the behaviors derived from subtraction and multiplication. |
| Real op == | real * real &rarr; bool | "x == y" returns true if and only if neither y nor x is NaN, and y and x are equal, ignoring signs on zeros. This is equivalent to the IEEE `=` operator. |
| Real op ?= | real * real &rarr; bool | "?= (x, y)" returns true if either argument is NaN or if the arguments are bitwise equal, ignoring signs on zeros. It is equivalent to the IEEE `?=` operator. |
| Real.class | real &rarr; IEEEReal.float_class | "class x" returns the `IEEEReal.float_class` to which x belongs. |
| Real.compareReal | real * real &rarr; IEEEReal.real_order | "compareReal (x, y)" behaves similarly to `Real.compare` except that the values it returns have the extended type `IEEEReal.real_order` and it returns `IEEEReal.UNORDERED` on unordered arguments. |
| Real.fmt | StringCvt.realfmt &rarr; real &rarr; string | "fmt spec r" converts a `real` into a `string` according to by `spec`; raises `Size` when `fmt spec` is evaluated if `spec` is an invalid precision |
| Real.fromDecimal | IEEEReal.decimal_approx &rarr; real | "fromDecimal d" converts decimal approximation to a `real` |
| Real.fromLarge | IEEEReal.rounding_mode &rarr; real &rarr; real | "toLarge r" converts a value of type `real` to type `LargeReal.real`. If `r` is too small or too large to be represented as a real, converts it to a zero or an infinity. |
| Real.fromLargeInt | IntInf.int &rarr; real | See "fromInt" |
| Real.nextAfter | real * real &rarr; real | "nextAfter (r, t)" returns the next representable real after `r` in the direction of `t`. Thus, if `t` is less than `r`, `nextAfter` returns the largest representable floating-point number less than `r`. |
| Real.scan | (char,'a) StringCvt.reader &rarr; (real,'a) StringCvt.reader | "scan getc strm" scans a `real` value from character source. Reads from ARG/strm/ using reader `getc`, ignoring initial whitespace. It returns `SOME(r, rest)` if successful, where `r` is the scanned `real` value and `rest` is the unused portion of the character stream `strm`. Values of too large a magnitude are represented as infinities; values of too small a magnitude are represented as zeros. |
| Real.toDecimal | real &rarr; IEEEReal.decimal_approx | "toDecimal r" converts a `real` to a decimal approximation |
| Real.toInt | real &rarr; IEEEReal.rounding_mode &rarr; int | "toInt mode x" converts the argument `x` to an integral type using the specified rounding mode. It raises `Overflow` if the result is not representable, in particular, if `x` is an infinity. It raises `Domain` if the input real is NaN. |
| Real.toLarge | real &rarr; real | "toLarge r" convert a value of type `real` to type `LargeReal.real`. |
| Real.toLargeInt | real &rarr; IEEEReal.rounding_mode &rarr; IntInf.int | See "toInt" |

## Properties

Each property is set using the function `Sys.set (name, value)`,
displayed using `Sys.show name`,
and unset using `Sys.unset name`.

| Name                 | Type | Default | Description |
| -------------------- | ---- | ------- | ----------- |
| hybrid               | bool | false   | Whether to try to create a hybrid execution plan that uses Apache Calcite relational algebra. |
| inlinePassCount      | int  | 5       | Maximum number of inlining passes. |
| lineWidth            | int  | 79      | When printing, the length at which lines are wrapped. |
| matchCoverageEnabled | bool | true    | Whether to check whether patterns are exhaustive and/or redundant. |
| printDepth           | int  | 5       | When printing, the depth of nesting of recursive data structure at which ellipsis begins. |
| printLength          | int  | 12      | When printing, the length of lists at which ellipsis begins. |
| stringDepth          | int  | 70      | When printing, the length of strings at which ellipsis begins. |
