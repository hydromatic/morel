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
* `from` expression with `where`, `group`, `order`, `yield` clauses
  (and `compute`, `desc` sub-clauses)
* `union`, `except`, `intersect`, `elem`, `notElem` operators
* "*lab* `=`" is optional in `exprow`

In Standard ML but not in Morel:
* `word` constant
* `longid` identifier
* type annotations ("`:` *typ*") (appears in expressions, patterns, and *funmatch*)
* references (`ref` and operators `!` and `:=`)
* exceptions (`raise`, `handle`, `exception`)
* `while` loop
* `as` (layered patterns)
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
    | <i>exp<sub>1</sub></i> <b>andalso</b> <i>exp<sub>2</sub></i>         conjunction
    | <i>exp<sub>1</sub></i> <b>orelse</b> <i>exp<sub>2</sub></i>          disjunction
    | <b>if</b> <i>exp<sub>1</sub></i> <b>then</b> <i>exp<sub>2</sub></i> <b>else</b> <i>exp<sub>3</sub></i>
                                conditional
    | <b>case</b> <i>exp</i> <b>of</b> <i>match</i>         case analysis
    | <b>fn</b> <i>match</i>                  function
    | <b>from</b> <i>fromSource<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>fromSource<sub>s</sub></i>
      ( <i>fromWhere</i> | <i>fromGroup</i> | <i>fromOrder</i> | <i>fromYield</i> )*
                                relational expression (<i>s</i> &ge; 0)
<i>exprow</i> &rarr; <i>exprowItem</i> [<b>,</b> <i>exprowItem</i> ]*
                                expression row
<i>exprowItem</i> &rarr; [ <i>lab</i> <b>=</b> ] <i>exp</i>
<i>match</i> &rarr; <i>matchItem</i> [ '<b>|</b>' <i>matchItem</i> ]*
                                match
<i>matchItem</i> &rarr; <i>pat</i> <b>=&gt;</b> <i>exp</i>
<i>fromSource</i> &rarr; <i>pat</i> [ <b>in</b> | <b>=</b> ] <i>exp</i>
<i>fromFilter</i> &rarr; <b>where</b> <i>exp</i>          filter clause
<i>fromGroup</i> &rarr; <b>group</b> <i>groupKey<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>groupKey<sub>g</sub></i>
      [ <b>compute</b> <i>agg<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>agg<sub>a</sub></i> ]
                                group clause (<i>g</i> &ge; 0, <i>a</i> &ge; 1)
<i>groupKey</i> &rarr; [ <i>id</i> <b>=</b> ] <i>exp</i>
<i>agg</i> &rarr; [ <i>id</i> <b>=</b> ] <i>exp</i> [ <b>of</b> <i>exp</i> ]
<i>fromOrder</i> &rarr; <b>order</b> <i>orderItem<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>orderItem<sub>o</sub></i>
                                order clause (<i>o</i> &ge; 1)
<i>orderItem</i> &rarr; <i>exp</i> [ <b>desc</b> ]
<i>fromYield</i> &rarr; <b>yield</b> <i>exp</i>
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
<i>funmatchItem</i> &rarr; [ <b>op</b> ] <i>id</i> <i>pat<sub>1</sub></i> ... <i>pat<sub>n</sub></i> <b>=</b> <i>exp</i>
                                nonfix (n &ge; 1)
    | <i>pat<sub>1</sub></i> <i>id</i> <i>pat<sub>2</sub></i> <b>=</b> <i>exp</i>        infix
    | '<b>(</b>' <i>pat<sub>1</sub></i> <i>id</i> <i>pat<sub>2</sub></i> '<b>)</b>' <i>pat'<sub>1</sub></i> ... <i>pat'<sub>n</sub></i> = <i>exp</i>
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

| Syntax      | Meaning
| ----------- | -------
| *symbol*    | Grammar symbol (e.g. *con*)
| **keyword** | Morel keyword (e.g. **if**) and symbol (e.g. **~**, "**(**")
| \[ term \]  | Option: term may occur 0 or 1 times
| \[ term1 \| term2 \] | Alternative: term1 may occur, or term2 may occur, or neither
| term*       | Repetition: term may occur 0 or more times
| 's'         | Quotation: Symbols used in the grammar &mdash; ( ) \[ \] \| * ... &mdash; are quoted when they appear in Morel language

## Built-in operators

| Operator | Precedence | Meaning
| :------- | ---------: | :------
| *        |    infix 7 | Multiplication
| /        |    infix 7 | Division
| div      |    infix 7 | Integer division
| mod      |    infix 7 | Modulo
| intersect |   infix 7 | List intersect
| +        |    infix 6 | Plus
| -        |    infix 6 | Minus
| ^        |    infix 6 | String concatenate
| union    |    infix 6 | List union
| except   |    infix 6 | List difference
| ~        |   prefix 6 | Negate
| ::       |   infixr 5 | List cons
| @        |   infixr 5 | List append
| &lt;=    |    infix 4 | Less than or equal
| &lt;     |    infix 4 | Less than
| &gt;=    |    infix 4 | Greater than or equal
| &gt;     |    infix 4 | Greater than
| =        |    infix 4 | Equal
| &lt;&gt; |    infix 4 | Not equal
| elem     |    infix 4 | Member of list
| notElem  |    infix 4 | Not member of list
| :=       |    infix 3 | Assign
| o        |    infix 3 | Compose
| andalso  |    infix 2 | Logical and
| orelse   |    infix 1 | Logical or

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

| Name | Type | Description
| ---- | ---- | -----------
| true | bool | Literal true.
| false | bool | Literal false.
| not | bool &rarr; bool | "not b" returns the logical inverse of `b`.
| abs | int &rarr; int | "abs n" returns the absolute value of `n`.
| General.ignore | &alpha; &rarr; unit | "ignore x" always returns `unit`. The function evaluates its argument but throws away the value.
| General.op o | (&beta; &rarr; &gamma;) (&alpha; &rarr; &beta;) &rarr; &alpha; &rarr; &gamma; | "f o g" is the function composition of `f` and `g`. Thus, `(f o g) a` is equivalent to `f (g a)`.
| String.maxSize | int | The longest allowed size of a string.
| String.size | string &rarr; int | "size s" returns \|`s`\|, the number of characters in string `s`.
| String.sub | string * int &rarr; char | "sub (s, i)" returns the `i`(th) character of `s`, counting from zero. This raises `Subscript` if `i` &lt; 0 or \|`s`\| &le; `i`.
| String.extract | string * int * int option &rarr; string | "extract (s, i, NONE)" and "extract (s, i, SOME j)" return substrings of `s`. The first returns the substring of `s` from the `i`(th) character to the end of the string, i.e., the string `s`\[`i`..\|`s`\|-1\]. This raises `Subscript` if `i` &lt; 0 or \|`s`\| &lt; `i`.<br><br>The second form returns the substring of size `j` starting at index `i`, i.e., the string `s`\[`i`..`i`+`j`-1\]. Raises `Subscript` if `i` &lt; 0 or `j` &lt; 0 or \|`s`\| &lt; `i` + `j`. Note that, if defined, `extract` returns the empty string when `i` = \|`s`\|.
| String.substring | string * int * int &rarr; string | "substring (s, i, j)" returns the substring `s`\[`i`..`i`+`j`-1\], i.e., the substring of size `j` starting at index `i`. This is equivalent to `extract(s, i, SOME j)`.
| String.concat | string list &rarr; string | "concat l" is the concatenation of all the strings in `l`. This raises `Size` if the sum of all the sizes is greater than `maxSize`.
| String.concatWith | string &rarr; string list &rarr; string | "concatWith s l" returns the concatenation of the strings in the list `l` using the string `s` as a separator. This raises `Size` if the size of the resulting string would be greater than `maxSize`.
| String.str | char &rarr; string | "str c" is the string of size one containing the character `c`.
| String.implode | char list &rarr; string | "implode l" generates the string containing the characters in the list `l`. This is equivalent to `concat (List.map str l)`. This raises `Size` if the resulting string would have size greater than `maxSize`.
| String.explode | string &rarr; char list | "explode s" is the list of characters in the string `s`.
| String.map | (char &rarr; char) &rarr; string &rarr; string | "map f s" applies `f` to each element of `s` from left to right, returning the resulting string. It is equivalent to `implode(List.map f (explode s))`.
| String.translate | (char &rarr; string) &rarr; string &rarr; string | "translate f s" returns the string generated from `s` by mapping each character in `s` by `f`. It is equivalent to `concat(List.map f (explode s))`.
| String.isPrefix | string &rarr; string &rarr; bool | "isPrefix s1 s2" returns `true` if the string `s1` is a prefix of the string `s2`. Note that the empty string is a prefix of any string, and that a string is a prefix of itself.
| String.isSubstring | string &rarr; string &rarr; bool | "isSubstring s1 s2" returns `true` if the string `s1` is a substring of the string `s2`. Note that the empty string is a substring of any string, and that a string is a substring of itself.
| String.isSuffix | string &rarr; string &rarr; bool | "isSuffix s1 s2" returns `true` if the string `s1` is a suffix of the string `s2`. Note that the empty string is a suffix of any string, and that a string is a suffix of itself.
| List.nil | &alpha; list | "nil" is the empty list.
| List.null | &alpha; list &rarr; bool | "null l" returns `true` if the list `l` is empty.
| List.length | &alpha; list &rarr; int | "length l" returns the number of elements in the list `l`.
| List.op @ | &alpha; list * &alpha; list &rarr; &alpha; list | "l1 @ l2" returns the list that is the concatenation of `l1` and `l2`.
| List.at | &alpha; list * &alpha; list &rarr; &alpha; list | "at (l1, l2)" is equivalent to "l1 @ l2".
| List.hd | &alpha; list &rarr; &alpha; | "hd l" returns the first element of `l`. Raises `Empty` if `l` is `nil`.
| List.tl | &alpha; list &rarr; &alpha; list | "tl l" returns all but the first element of `l`. Raises `Empty` if `l` is `nil`.
| List.last | &alpha; list &rarr; &alpha; | "last l" returns the last element of `l`. Raises `Empty` if `l` is `nil`.
| List.getItem | &alpha; list &rarr; * (&alpha; * &alpha; list) option | "getItem l" returns `NONE` if the `list` is empty, and `SOME(hd l,tl l)` otherwise. This function is particularly useful for creating value readers from lists of characters. For example, `Int.scan StringCvt.DEC getItem` has the type `(int, char list) StringCvt.reader` and can be used to scan decimal integers from lists of characters.
| List.nth | &alpha; list * int &rarr; &alpha; | "nth (l, i)" returns the `i`(th) element of the list `l`, counting from 0. Raises `Subscript` if `i` &lt; 0 or `i` &ge; `length l`. We have `nth(l, 0)` = `hd l`, ignoring exceptions.
| List.take | &alpha; list * int &rarr; &alpha; list | "take (l, i)" returns the first `i` elements of the list `l`. Raises `Subscript` if `i` &lt; 0 or `i` &gt; `length l`. We have `take(l, length l)` = `l`.
| List.drop | &alpha; list * int &rarr; &alpha; list | "drop (l, i)" returns what is left after dropping the first `i` elements of the list `l`. Raises `Subscript` if `i` &lt; 0 or `i` &gt; `length l`.<br><br>It holds that `take(l, i) @ drop(l, i)` = `l` when 0 &le; `i` &le; `length l`. We also have `drop(l, length l)` = `[]`.
| List.rev | &alpha; list &rarr; &alpha; list | "rev l" returns a list consisting of `l`'s elements in reverse order.
| List.concat | &alpha; list list &rarr; &alpha; list | "concat l" returns the list that is the concatenation of all the lists in `l` in order. `concat [l1, l2, ... ln]` = `l1 @ l2 @ ... @ ln`
| List.revAppend | &alpha; list * &alpha; list &rarr; &alpha; list | "revAppend (l1, l2)" returns `(rev l1) @ l2`.
| List.app | (&alpha; &rarr; unit) &rarr; &alpha; list &rarr; unit | "app f l" applies `f` to the elements of `l`, from left to right.
| List.map | (&alpha; &rarr; &beta;) &rarr; &alpha; list &rarr; &beta; list | "map f l" applies `f` to each element of `l` from left to right, returning the list of results.
| List.mapPartial | (&alpha; &rarr; &beta; option) &rarr; &alpha; list &rarr; &beta; list | "mapPartial f l" applies `f` to each element of `l` from left to right, returning a list of results, with `SOME` stripped, where `f` was defined. `f` is not defined for an element of `l` if `f` applied to the element returns `NONE`. The above expression is equivalent to `((map valOf) o (filter isSome) o (map f)) l`.
| List.find | (&alpha; &rarr; bool) &rarr; &alpha; list &rarr; &alpha; option | "find f l" applies `f` to each element `x` of the list `l`, from left to right, until `f x` evaluates to `true`. It returns `SOME(x)` if such an `x` exists; otherwise it returns `NONE`.
| List.filter | (&alpha; &rarr; bool) &rarr; &alpha; list &rarr; &alpha; list | "filter f l" applies `f` to each element `x` of `l`, from left to right, and returns the list of those `x` for which `f x` evaluated to `true`, in the same order as they occurred in the argument list.
| List.partition | (&alpha; &rarr; bool) &rarr; &alpha; list &rarr; &alpha; list * &alpha; list | "partition f l" applies `f` to each element `x` of `l`, from left to right, and returns a pair `(pos, neg)` where `pos` is the list of those `x` for which `f x` evaluated to `true`, and `neg` is the list of those for which `f x` evaluated to `false`. The elements of `pos` and `neg` retain the same relative order they possessed in `l`.
| List.foldl | (&alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; list &rarr; &beta; | "foldl f init \[x1, x2, ..., xn\]" returns `f(xn, ... , f(x2, f(x1, init))...)` or `init` if the list is empty.
| List.foldr | (&alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; list &rarr; &beta; | "foldr f init \[x1, x2, ..., xn\]" returns `f(x1, f(x2, ..., f(xn, init)...))` or `init` if the list is empty.
| List.exists | (&alpha; &rarr; bool) &rarr; &alpha; list &rarr; bool | "exists f l" applies `f` to each element `x` of the list `l`, from left to right, until `f(x)` evaluates to `true`; it returns `true` if such an `x` exists and `false` otherwise.
| List.all | (&alpha; &rarr; bool) &rarr; &alpha; list &rarr; bool | "all f l" applies `f` to each element `x` of the list `l`, from left to right, `f(x)` evaluates to `false`; it returns `false` if such an `x` exists and `true` otherwise. It is equivalent to `not(exists (not o f) l))`.
| List.tabulate | int * (int &rarr; &alpha;) &rarr; &alpha; list | "tabulate (n, f)" returns a list of length `n` equal to `[f(0), f(1), ..., f(n-1)]`, created from left to right. Raises `Size` if `n` &lt; 0.
| List.collate | (&alpha; * &alpha; &rarr; order) &rarr; &alpha; list * &alpha; list &rarr; order | "collate f (l1, l2)" performs lexicographic comparison of the two lists using the given ordering `f` on the list elements.
| Option.app | (&alpha; &rarr; unit) &rarr; &alpha; option &rarr; unit | "app f opt" applies the function `f` to the value `v` if `opt` is `SOME v`, and otherwise does nothing
| Option.compose | (&alpha; &rarr; &beta;) * (&gamma; &rarr; &alpha; option) &rarr; &gamma; &rarr; &beta; option | "compose (f, g) a" returns `NONE` if `g(a)` is `NONE`; otherwise, if `g(a)` is `SOME v`, it returns `SOME (f v)`.
| Option.composePartial | (&alpha; &rarr; &beta; option) * (&gamma; &rarr; &alpha; option) &rarr; &gamma; &rarr; &beta; option | "composePartial (f, g) a" returns `NONE` if `g(a)` is `NONE`; otherwise, if `g(a)` is `SOME v`, returns `f(v)`.
| Option.map | &alpha; &rarr; &beta;) &rarr; &alpha; option &rarr; &beta; option | "map f opt" maps `NONE` to `NONE` and `SOME v` to `SOME (f v)`.
| Option.mapPartial | &alpha; &rarr; &beta; option) &rarr; &alpha; option &rarr; &beta; option | "mapPartial f opt" maps `NONE` to `NONE` and `SOME v` to `f(v)`.
| Option.getOpt | &alpha; option * &alpha; &rarr; &alpha; | "getOpt (opt, a)" returns `v` if `opt` is `SOME(v)`; otherwise returns `a`.
| Option.isSome | &alpha; option &rarr; bool | "isSome opt" returns `true` if `opt` is `SOME v`; otherwise returns `false`.
| Option.filter | (&alpha; &rarr; bool) &rarr; &alpha; &rarr; &alpha; option | "filter f a" returns `SOME a` if `f(a)` is `true`, `NONE` otherwise.
| Option.join| &alpha; option option &rarr; &alpha; option | "join opt" maps `NONE` to `NONE` and `SOME v` to `v`.
| Option.valOf | &alpha; option &rarr; &alpha; | "valOf opt" returns `v` if `opt` is `SOME v`, otherwise raises `Option`.
| Vector.all
| Vector.app | (&alpha; &rarr; unit) &rarr; &alpha; vector &rarr; unit | "app f vec" applies the function `f` to the elements of a vector in left to right order (i.e., in order of increasing indices)
| Vector.appi | (int * &alpha; &rarr; unit) &rarr; &alpha; vector &rarr; unit | "appi f vec" applies the function `f` to the elements of a vector in left to right order (i.e., in order of increasing indices)
| Vector.collate
| Vector.concat | &alpha; vector list &rarr; &alpha; vector | "concat l" returns the vector that is the concatenation of the vectors in the list `l`. Raises `Size` if the total length of these vectors exceeds `maxLen`
| Vector.exists
| Vector.find | (&alpha; &rarr; bool) &rarr; &alpha; vector &rarr; &alpha; option | "find f vec" applies `f` to each element `x` of the vector `vec`, from left to right, until `f(x)` evaluates to `true`. It returns `SOME(x)` if such an `x` exists; otherwise it returns `NONE`.
| Vector.findi | (int * &alpha; &rarr; bool) &rarr; &alpha; vector &rarr; (int * &alpha;) option | "findi f vec" applies `f` to each element `x` and element index `i` of the vector `vec`, from left to right, until `f(i, x)` evaluates to `true`. It returns `SOME(i, x)` if such an `x` exists; otherwise it returns `NONE`.
| Vector.foldl | (&alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; vector &rarr; &beta; | "foldl f init vec" folds the function `f` over all the elements of vector `vec`, left to right, using the initial value `init`
| Vector.foldli | (int * &alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; vector &rarr; &beta; | "foldli f init vec" folds the function `f` over all the (index, element) pairs of vector `vec`, left to right, using the initial value `init`
| Vector.foldr | (&alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; vector &rarr; &beta; | "foldr f init vec" folds the function `f` over all the elements of vector `vec`, right to left, using the initial value `init`
| Vector.foldri | (int * &alpha; * &beta; &rarr; &beta;) &rarr; &beta; &rarr; &alpha; vector &rarr; &beta; | "foldri f init vec" folds the function `f` over all the (index, element) pairs of vector `vec`, right to left, using the initial value `init`
| Vector.fromList | &alpha; list &rarr; &alpha; vector | "fromList l" creates a new vector from `l`, whose length is `length l` and with the i<sup>th</sup> element of `l` used as the `i`<sup>th</sup> element of the vector. Raises `Size` if `maxLen` &lt; `n`.
| Vector.length | &alpha; vector &rarr; int | "length v" returns the number of elements in the vector `v`.
| Vector.map | (&alpha; &rarr; &beta;) &rarr; &alpha; vector &rarr; &beta; vector | "map f vec" applies the function `f` to the elements of the argument vector `vec`
| Vector.mapi | (int * &alpha; &rarr; &beta;) &rarr; &alpha; vector &rarr; &beta; vector | "mapi f vec" applies the function `f` to the elements of the argument vector `vec`, supplying the vector index and element as arguments to each call.
| Vector.maxLen | int | "maxLen" returns the maximum length of vectors supported in this implementation.
| Vector.sub | &alpha; vector * int &rarr; &alpha; | "sub (vec, i)" returns the `i`<sup>th</sup> element of vector `vec`. Raises `Subscript` if `i` &lt; 0 or `size vec` &le; `i`.
| Vector.tabulate | int * (int &rarr; &alpha;) &rarr; &alpha; vector | "tabulate (n, f)" returns a vector of length `n` equal to `[f(0), f(1), ..., f(n-1)]`, created from left to right. Raises `Size` if `n` &lt; 0 or `maxLen` &lt; `n`.
| Vector.update | &alpha; vector * int * &alpha; &rarr; &alpha; vector | "update (vec, i, x)" returns  a new vector, identical to `vec`, except the `i`<sup>th</sup> element of `vec` is set to `x`. Raises `Subscript` if `i` &lt; 0 or `size vec` &le; `i`.
| Relational.count, count | int list &rarr; int | "count list" returns the number of elements in `list`. Often used with `group`, for example `from e in emps group e.deptno compute countId = count`.
| Relational.exists | &alpha; list &rarr; bool | "exists list" returns whether the list has at least one element, for example `from d in depts where exists (from e where e.deptno = d.deptno)`.
| Relational.notExists | &alpha; list &rarr; bool | "notExists list" returns whether the list is empty, for example `from d in depts where notExists (from e where e.deptno = d.deptno)`.
| Relational.only | &alpha; list &rarr; &alpha; | "only list" returns the sole element of list, for example `from e in emps yield only (from d where d.deptno = e.deptno)`.
| Relational.max, max | &alpha; list &rarr; &alpha; | "max list" returns the greatest element of `list`. Often used with `group`, for example `from e in emps group e.deptno compute maxId = max of e.id`.
| Relational.min, min | &alpha; list &rarr; &alpha; | "min list" returns the least element of `list`. Often used with `group`, for example `from e in emps group e.deptno compute minId = min of e.id`.
| Relational.sum, sum | int list &rarr; int | "sum list" returns the sum of the elements of `list`. Often used with `group`, for example `from e in emps group e.deptno compute sumId = sum of e.id`.
| Sys.env, env | unit &rarr; string list | "env ()" prints the environment.
| Sys.plan | unit &rarr; string | "plan ()" print the plan of the most recently executed expression.
| Sys.set | string * &alpha; &rarr; unit | "set (property, value)" sets the value of `property` to `value`.
| Sys.show | string &rarr; string option | "show property" returns the current the value of `property`, as a string, or `NONE` if unset.
| Sys.unset | string &rarr; unit | "unset property" clears the current the value of `property`.
