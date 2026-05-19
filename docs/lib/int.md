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

# Int structure

[Up to index](index.md)

[//]: # (start:lib/int)
The `Int` structure provides arithmetic, comparison, and conversion
operations for the default fixed-precision integer type.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/int.html).*

## Synopsis

<pre>
type <a id='int' href="#int-impl">int</a>

val <a id='toLarge' href="#toLarge-impl">toLarge</a> : int -> int
val <a id='fromLarge' href="#fromLarge-impl">fromLarge</a> : int -> int
val <a id='toInt' href="#toInt-impl">toInt</a> : int -> int
val <a id='fromInt' href="#fromInt-impl">fromInt</a> : int -> int
val <a id='precision' href="#precision-impl">precision</a> : int option
val <a id='minInt' href="#minInt-impl">minInt</a> : int option
val <a id='maxInt' href="#maxInt-impl">maxInt</a> : int option
val <a id='div' href="#div-impl">div</a> : int * int -> int
val <a id='mod' href="#mod-impl">mod</a> : int * int -> int
val <a id='quot' href="#quot-impl">quot</a> : int * int -> int
val <a id='rem' href="#rem-impl">rem</a> : int * int -> int
val <a id='compare' href="#compare-impl">compare</a> : int * int -> order
val <a id='abs' href="#abs-impl">abs</a> : int -> int
val <a id='min' href="#min-impl">min</a> : int * int -> int
val <a id='max' href="#max-impl">max</a> : int * int -> int
val <a id='sign' href="#sign-impl">sign</a> : int -> int
val <a id='sameSign' href="#sameSign-impl">sameSign</a> : int * int -> bool
val <a id='toString' href="#toString-impl">toString</a> : int -> string
val <a id='fromString' href="#fromString-impl">fromString</a> : string -> int option
</pre>

<a id="int-impl"></a>
<h3><code><strong>type</strong> int</code></h3>

is the type of fixed-precision integers.

<a id="toLarge-impl"></a>
<h3><code>toLarge</code></h3>

`toLarge i`

<a id="fromLarge-impl"></a>
<h3><code>fromLarge</code></h3>

`fromLarge i`

<a id="toInt-impl"></a>
<h3><code>toInt</code></h3>

`toInt i`

<a id="fromInt-impl"></a>
<h3><code>fromInt</code></h3>

`fromInt i`

<a id="precision-impl"></a>
<h3><code>precision</code></h3>

`precision`

<a id="minInt-impl"></a>
<h3><code>minInt</code></h3>

`minInt` is the minimal (most negative) integer representable by
`int`. If a value is `NONE`, `int` can represent all negative
integers, within the limits of the heap size. If `precision` is `SOME
(n)`, then we have `minInt` = -2<sup>(n-1)</sup>.

<a id="maxInt-impl"></a>
<h3><code>maxInt</code></h3>

`maxInt` is the maximal (most positive) integer representable by
`int`. If a value is `NONE`, `int` can represent all positive
integers, within the limits of the heap size. If `precision` is `SOME
(n)`, then we have `maxInt` = 2<sup>(n-1)</sup> - 1.

<a id="div-impl"></a>
<h3><code>div</code></h3>

`i div j` returns the greatest integer less than or equal to the
quotient of `i` by j, i.e., `floor(i / j)`. It raises `Overflow` when
the result is not representable, or Div when `j = 0`. Note that
rounding is towards negative infinity, not zero.

<a id="mod-impl"></a>
<h3><code>mod</code></h3>

`i mod j` returns the remainder of the division of `i` by `j`. It raises
`Div` when `j = 0`. When defined, `(i mod j)` has the same sign as
`j`, and `(i div j) * j + (i mod j) = i`.

<a id="quot-impl"></a>
<h3><code>quot</code></h3>

`quot (i, j)` (or `i.quot j`) returns the truncated quotient of the division of `i` by
`j`, i.e., it computes `(i / j)` and then drops any fractional part of
the quotient. It raises `Overflow` when the result is not
representable, or `Div` when `j = 0`. Note that unlike `div`, `quot`
rounds towards zero. In addition, unlike `div` and `mod`, neither
`quot` nor `rem` are infix by default; an appropriate infix
declaration would be `infix 7 quot rem`. This is the semantics of most
hardware divide instructions, so `quot` may be faster than `div`.

<a id="rem-impl"></a>
<h3><code>rem</code></h3>

`rem (i, j)` (or `i.rem j`) returns the remainder of the division of `i` by `j`. It
raises `Div` when `j = 0`. `(i rem j)` has the same sign as i, and it
holds that `(i quot j) * j + (i rem j) = i`. This is the semantics of
most hardware divide instructions, so `rem` may be faster than `mod`.

<a id="compare-impl"></a>
<h3><code>compare</code></h3>

`compare (i, j)` (or `i.compare j`) returns `LESS`, `EQUAL`, or `GREATER` according to
whether its first argument is less than, equal to, or greater than the
second.

<a id="abs-impl"></a>
<h3><code>abs</code></h3>

`abs i` (or `i.abs ()`) returns the absolute value of `i`.

<a id="min-impl"></a>
<h3><code>min</code></h3>

`min (i, j)` (or `i.min j`) returns the smaller of the arguments.

<a id="max-impl"></a>
<h3><code>max</code></h3>

`max (i, j)` (or `i.max j`) returns the larger of the arguments.

<a id="sign-impl"></a>
<h3><code>sign</code></h3>

`sign i` (or `i.sign ()`)

<a id="sameSign-impl"></a>
<h3><code>sameSign</code></h3>

`sameSign (i, j)` (or `i.sameSign j`) returns true if `i` and `j` have the same sign. It
is equivalent to `(sign i = sign j)`.

<a id="toString-impl"></a>
<h3><code>toString</code></h3>

`toString i` (or `i.toString ()`) converts a `int` into a `string`; equivalent to `(fmt
StringCvt.DEC r)`.

<a id="fromString-impl"></a>
<h3><code>fromString</code></h3>

`fromString s` scans a `int` value from a string. Returns `SOME (r)`
if a `int` value can be scanned from a prefix of `s`, ignoring any
initial whitespace; otherwise, it returns `NONE`. Equivalent to
`StringCvt.scanString (scan StringCvt.DEC)`.

[//]: # (end:lib/int)
