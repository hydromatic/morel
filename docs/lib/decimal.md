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

# Decimal structure

[Up to index](index.md)

[//]: # (start:lib/decimal)
The `Decimal` structure provides exact decimal arithmetic, based on the
IEEE 754-2008 decimal128 format: 34 significant digits, radix 10.
Unlike `real`, `decimal "0.1"` is exactly one tenth, so the type is
suitable for prices, rates, and SQL `DECIMAL` values.

Each value has a single representation. Trailing zeros are discarded
(`decimal "12.30"` and `decimal "12.3"` are the same value), and there
are no infinities, NaN, or negative zero, so values are totally ordered
and `decimal` is an equality type. Operations that cannot produce a
finite result raise an exception: `Overflow` if the magnitude is too
large, `Div` on division by zero. Inexact results are rounded half-even
to 34 significant digits; results too small to represent are truncated
toward zero.

## Synopsis

<pre>
type <a id='decimal' href="#decimal-impl">decimal</a>

val <a id='decimal' href="#decimal-impl">decimal</a> : string -> decimal
val <a id='radix' href="#radix-impl">radix</a> : int
val <a id='precision' href="#precision-impl">precision</a> : int
val <a id='maxFinite' href="#maxFinite-impl">maxFinite</a> : decimal
val <a id='minPos' href="#minPos-impl">minPos</a> : decimal
val <a id='+' href="#+-impl">+</a> : decimal * decimal -> decimal
val <a id='-' href="#--impl">-</a> : decimal * decimal -> decimal
val <a id='*' href="#*-impl">*</a> : decimal * decimal -> decimal
val <a id='/' href="#/-impl">/</a> : decimal * decimal -> decimal
val <a id='rem' href="#rem-impl">rem</a> : decimal * decimal -> decimal
val <a id='~' href="#~-impl">~</a> : decimal -> decimal
val <a id='abs' href="#abs-impl">abs</a> : decimal -> decimal
val <a id='min' href="#min-impl">min</a> : decimal * decimal -> decimal
val <a id='max' href="#max-impl">max</a> : decimal * decimal -> decimal
val <a id='sign' href="#sign-impl">sign</a> : decimal -> int
val <a id='compare' href="#compare-impl">compare</a> : decimal * decimal -> order
val <a id='<' href="#<-impl"><</a> : decimal * decimal -> bool
val <a id='<=' href="#<=-impl"><=</a> : decimal * decimal -> bool
val <a id='>' href="#>-impl">></a> : decimal * decimal -> bool
val <a id='>=' href="#>=-impl">>=</a> : decimal * decimal -> bool
val <a id='realFloor' href="#realFloor-impl">realFloor</a> : decimal -> decimal
val <a id='realCeil' href="#realCeil-impl">realCeil</a> : decimal -> decimal
val <a id='realTrunc' href="#realTrunc-impl">realTrunc</a> : decimal -> decimal
val <a id='realRound' href="#realRound-impl">realRound</a> : decimal -> decimal
val <a id='floor' href="#floor-impl">floor</a> : decimal -> int
val <a id='ceil' href="#ceil-impl">ceil</a> : decimal -> int
val <a id='trunc' href="#trunc-impl">trunc</a> : decimal -> int
val <a id='round' href="#round-impl">round</a> : decimal -> int
val <a id='fromInt' href="#fromInt-impl">fromInt</a> : int -> decimal
val <a id='fromReal' href="#fromReal-impl">fromReal</a> : real -> decimal
val <a id='toReal' href="#toReal-impl">toReal</a> : decimal -> real
val <a id='toString' href="#toString-impl">toString</a> : decimal -> string
val <a id='fromString' href="#fromString-impl">fromString</a> : string -> decimal option
val <a id='fmt' href="#fmt-impl">fmt</a> : realfmt -> decimal -> string
</pre>

<a id="decimal-impl"></a>
<h3><code><strong>type</strong> decimal</code></h3>

is the type of decimal numbers with up to 34 significant digits.

<a id="decimal-impl"></a>
<h3><code>decimal</code></h3>

`decimal s` converts the string `s` to a decimal. The string may have a sign
(`~`, `-` or `+`), digits with an optional decimal point, and an
optional exponent (`E` or `e`, then an optionally signed integer). Raises
`Domain` if `s` is not of that form, or if its value cannot be
represented exactly. If `s` is a string literal, the conversion occurs
at compile time, and an invalid literal is a compile error.

<a id="radix-impl"></a>
<h3><code>radix</code></h3>

`radix` is the base of the representation, 10.

<a id="precision-impl"></a>
<h3><code>precision</code></h3>

`precision` is the number of digits in the significand, 34.

<a id="maxFinite-impl"></a>
<h3><code>maxFinite</code></h3>

`maxFinite` is the largest decimal value,
9.999999999999999999999999999999999E6144.

<a id="minPos-impl"></a>
<h3><code>minPos</code></h3>

`minPos` is the smallest positive decimal value, 1E~6176.

<a id="+-impl"></a>
<h3><code>+</code></h3>

`d1 + d2` returns the sum of `d1` and `d2`. Raises `Overflow` if the result is too
large.

<a id="--impl"></a>
<h3><code>-</code></h3>

`d1 - d2` returns the difference of `d1` and `d2`. Raises `Overflow` if the result
is too large.

<a id="*-impl"></a>
<h3><code>*</code></h3>

`d1 * d2` returns the product of `d1` and `d2`. Raises `Overflow` if the result is
too large.

<a id="/-impl"></a>
<h3><code>/</code></h3>

`d1 / d2` returns the quotient of `d1` and `d2`, rounded half-even to 34
significant digits. Raises `Div` if `d2` is zero, `Overflow` if the result
is too large.

<a id="rem-impl"></a>
<h3><code>rem</code></h3>

`rem (x, y)` (or `x.rem y`) returns the remainder `x - n * y`, where `n` is the quotient `x / y`
truncated toward zero. The result has the same sign as `x`. Raises `Div`
if `y` is zero.

<a id="~-impl"></a>
<h3><code>~</code></h3>

`~ d` returns the negation of `d`.

<a id="abs-impl"></a>
<h3><code>abs</code></h3>

`abs d` (or `d.abs ()`) returns the absolute value of `d`.

<a id="min-impl"></a>
<h3><code>min</code></h3>

`min (x, y)` (or `x.min y`) returns the smaller of `x` and `y`.

<a id="max-impl"></a>
<h3><code>max</code></h3>

`max (x, y)` (or `x.max y`) returns the larger of `x` and `y`.

<a id="sign-impl"></a>
<h3><code>sign</code></h3>

`sign d` (or `d.sign ()`) returns ~1, 0, or 1, according to whether `d` is negative, zero, or
positive.

<a id="compare-impl"></a>
<h3><code>compare</code></h3>

`compare (x, y)` (or `x.compare y`) returns `LESS`, `EQUAL`, or `GREATER` according to whether `x` is less
than, equal to, or greater than `y`.

<a id="<-impl"></a>
<h3><code><</code></h3>

`x < y` returns `true` if `x` is less than `y`.

<a id="<=-impl"></a>
<h3><code><=</code></h3>

`x <= y` returns `true` if `x` is less than or equal to `y`.

<a id=">-impl"></a>
<h3><code>></code></h3>

`x > y` returns `true` if `x` is greater than `y`.

<a id=">=-impl"></a>
<h3><code>>=</code></h3>

`x >= y` returns `true` if `x` is greater than or equal to `y`.

<a id="realFloor-impl"></a>
<h3><code>realFloor</code></h3>

`realFloor d` (or `d.realFloor ()`) returns the largest integer-valued decimal not larger than `d`.

<a id="realCeil-impl"></a>
<h3><code>realCeil</code></h3>

`realCeil d` (or `d.realCeil ()`) returns the smallest integer-valued decimal not less than `d`.

<a id="realTrunc-impl"></a>
<h3><code>realTrunc</code></h3>

`realTrunc d` (or `d.realTrunc ()`) returns `d` rounded toward zero to an integer-valued decimal.

<a id="realRound-impl"></a>
<h3><code>realRound</code></h3>

`realRound d` (or `d.realRound ()`) returns the integer-valued decimal nearest to `d`. In the case of a tie,
it returns the even one.

<a id="floor-impl"></a>
<h3><code>floor</code></h3>

`floor d` (or `d.floor ()`) returns the largest `int` not larger than `d`. Raises `Overflow` if the
result is not representable as an `int`.

<a id="ceil-impl"></a>
<h3><code>ceil</code></h3>

`ceil d` (or `d.ceil ()`) returns the smallest `int` not less than `d`. Raises `Overflow` if the
result is not representable as an `int`.

<a id="trunc-impl"></a>
<h3><code>trunc</code></h3>

`trunc d` (or `d.trunc ()`) returns `d` rounded toward zero to an `int`. Raises `Overflow` if the
result is not representable as an `int`.

<a id="round-impl"></a>
<h3><code>round</code></h3>

`round d` (or `d.round ()`) returns the `int` nearest to `d`. In the case of a tie, it returns the
even one. Raises `Overflow` if the result is not representable as an
`int`.

<a id="fromInt-impl"></a>
<h3><code>fromInt</code></h3>

`fromInt i` converts the integer `i` to a decimal.

<a id="fromReal-impl"></a>
<h3><code>fromReal</code></h3>

`fromReal r` converts the real `r` to the decimal with the fewest digits that
converts back to `r`. Raises `Domain` if `r` is NaN, `Overflow` if `r` is
infinite.

<a id="toReal-impl"></a>
<h3><code>toReal</code></h3>

`toReal d` (or `d.toReal ()`) converts `d` to the nearest real.

<a id="toString-impl"></a>
<h3><code>toString</code></h3>

`toString d` (or `d.toString ()`) converts `d` to a string. Uses plain notation if the adjusted exponent is
at least ~7 and less than 34, otherwise scientific notation, and `~` for
a negative value. An integer has no decimal point. The result converts
back to the same value: `decimal (toString d) = d`.

<a id="fromString-impl"></a>
<h3><code>fromString</code></h3>

`fromString s` parses a decimal from a prefix of the string `s`, after skipping initial
whitespace, accepting the same syntax as `decimal`. Returns `SOME d` if
successful, `NONE` otherwise; characters after the number are ignored.
Unlike `decimal`, it rounds a value with more than 34 significant digits.
Raises `Overflow` if the value is too large.

<a id="fmt-impl"></a>
<h3><code>fmt</code></h3>

`fmt spec d` converts `d` to a string according to `spec`, as `Real.fmt` does, but
rounding half-even. Raises `Size` if `spec` is an invalid precision.

[//]: # (end:lib/decimal)
