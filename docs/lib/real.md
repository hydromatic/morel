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

# Real structure

[Up to index](index.md)

[//]: # (start:lib/real)
The `Real` structure provides arithmetic, comparison, conversion, and
classification operations for IEEE 754 double-precision floating-point
numbers.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/real.html).*

## Synopsis

<pre>
type <a id='real' href="#real-impl">real</a>

val <a id='*' href="#*-impl">*</a> : real * real -> real
val <a id='+' href="#+-impl">+</a> : real * real -> real
val <a id='-' href="#--impl">-</a> : real * real -> real
val <a id='/' href="#/-impl">/</a> : real * real -> real
val <a id='<' href="#<-impl"><</a> : real * real -> bool
val <a id='<=' href="#<=-impl"><=</a> : real * real -> bool
val <a id='>' href="#>-impl">></a> : real * real -> bool
val <a id='>=' href="#>=-impl">>=</a> : real * real -> bool
val <a id='~' href="#~-impl">~</a> : real -> real
val <a id='abs' href="#abs-impl">abs</a> : real -> real
val <a id='ceil' href="#ceil-impl">ceil</a> : real -> int
val <a id='checkFloat' href="#checkFloat-impl">checkFloat</a> : real -> real
val <a id='compare' href="#compare-impl">compare</a> : real * real -> order
val <a id='copySign' href="#copySign-impl">copySign</a> : real * real -> real
val <a id='floor' href="#floor-impl">floor</a> : real -> int
val <a id='fromInt' href="#fromInt-impl">fromInt</a> : int -> real
val <a id='fromManExp' href="#fromManExp-impl">fromManExp</a> : {exp:int, man:real} -> real
val <a id='fromString' href="#fromString-impl">fromString</a> : string -> real option
val <a id='isFinite' href="#isFinite-impl">isFinite</a> : real -> bool
val <a id='isNan' href="#isNan-impl">isNan</a> : real -> bool
val <a id='isNormal' href="#isNormal-impl">isNormal</a> : real -> bool
val <a id='max' href="#max-impl">max</a> : real * real -> real
val <a id='maxFinite' href="#maxFinite-impl">maxFinite</a> : real
val <a id='min' href="#min-impl">min</a> : real * real -> real
val <a id='minNormalPos' href="#minNormalPos-impl">minNormalPos</a> : real
val <a id='minPos' href="#minPos-impl">minPos</a> : real
val <a id='negInf' href="#negInf-impl">negInf</a> : real
val <a id='posInf' href="#posInf-impl">posInf</a> : real
val <a id='precision' href="#precision-impl">precision</a> : int
val <a id='radix' href="#radix-impl">radix</a> : int
val <a id='realCeil' href="#realCeil-impl">realCeil</a> : real -> real
val <a id='realFloor' href="#realFloor-impl">realFloor</a> : real -> real
val <a id='realMod' href="#realMod-impl">realMod</a> : real -> real
val <a id='realRound' href="#realRound-impl">realRound</a> : real -> real
val <a id='realTrunc' href="#realTrunc-impl">realTrunc</a> : real -> real
val <a id='rem' href="#rem-impl">rem</a> : real * real -> real
val <a id='round' href="#round-impl">round</a> : real -> int
val <a id='sameSign' href="#sameSign-impl">sameSign</a> : real * real -> bool
val <a id='sign' href="#sign-impl">sign</a> : real -> int
val <a id='signBit' href="#signBit-impl">signBit</a> : real -> bool
val <a id='split' href="#split-impl">split</a> : real -> {frac:real, whole:real}
val <a id='trunc' href="#trunc-impl">trunc</a> : real -> int
val <a id='toManExp' href="#toManExp-impl">toManExp</a> : real -> {man:real, exp:int}
val <a id='toString' href="#toString-impl">toString</a> : real -> string
val <a id='unordered' href="#unordered-impl">unordered</a> : real * real -> bool
val <a id='!=' href="#!=-impl">!=</a> : real * real -> bool
val <a id='*+' href="#*+-impl">*+</a> : real * real * real -> real
val <a id='*-' href="#*--impl">*-</a> : real * real * real -> real
val <a id='==' href="#==-impl">==</a> : real * real -> bool
val <a id='?=' href="#?=-impl">?=</a> : real * real -> bool
val <a id='class' href="#class-impl">class</a> : real -> IEEEReal.float_class
val <a id='compareReal' href="#compareReal-impl">compareReal</a> : real * real -> IEEEReal.real_order
val <a id='fmt' href="#fmt-impl">fmt</a> : StringCvt.realfmt -> real -> string
val <a id='fromDecimal' href="#fromDecimal-impl">fromDecimal</a> : IEEEReal.decimal_approx -> real
val <a id='fromLarge' href="#fromLarge-impl">fromLarge</a> : IEEEReal.rounding_mode -> real -> real
val <a id='fromLargeInt' href="#fromLargeInt-impl">fromLargeInt</a> : IntInf.int -> real
val <a id='nextAfter' href="#nextAfter-impl">nextAfter</a> : real * real -> real
val <a id='scan' href="#scan-impl">scan</a> : (char,'a) StringCvt.reader -> (real,'a) StringCvt.reader
val <a id='toDecimal' href="#toDecimal-impl">toDecimal</a> : real -> IEEEReal.decimal_approx
val <a id='toInt' href="#toInt-impl">toInt</a> : real -> IEEEReal.rounding_mode -> int
val <a id='toLarge' href="#toLarge-impl">toLarge</a> : real -> real
val <a id='toLargeInt' href="#toLargeInt-impl">toLargeInt</a> : real -> IEEEReal.rounding_mode -> IntInf.int
</pre>

<a id="real-impl"></a>
<h3><code><strong>type</strong> real</code></h3>

is the type of IEEE 754 double-precision floating-point numbers.

<a id="*-impl"></a>
<h3><code>*</code></h3>

`r1 * r2` is the product of `r1` and `r2`. The product of zero and an
infinity produces NaN. Otherwise, if one argument is infinite, the
result is infinite with the correct sign, e.g., -5 * (-infinity) =
infinity, infinity * (-infinity) = -infinity.

<a id="+-impl"></a>
<h3><code>+</code></h3>

`r1 + r2` is the sum of `r1` and `r2`. If one argument is finite and
the other infinite, the result is infinite with the correct sign,
e.g., 5 - (-infinity) = infinity. We also have infinity + infinity =
infinity and (-infinity) + (-infinity) = (-infinity). Any other
combination of two infinities produces NaN.

<a id="--impl"></a>
<h3><code>-</code></h3>

`r1 - r2` is the difference of `r1` and `r2`. If one argument is
finite and the other infinite, the result is infinite with the correct
sign, e.g., 5 - (-infinity) = infinity. We also have infinity +
infinity = infinity and (-infinity) + (-infinity) = (-infinity). Any
other combination of two infinities produces NaN.

<a id="/-impl"></a>
<h3><code>/</code></h3>

`r1 / r2` is the quotient of `r1` and `r2`. We have 0 / 0 = NaN and
+-infinity / +-infinity = NaN. Dividing a finite, non-zero number by a
zero, or an infinity by a finite number produces an infinity with the
correct sign. (Note that zeros are signed.) A finite number divided by
an infinity is 0 with the correct sign.

<a id="<-impl"></a>
<h3><code><</code></h3>

`x < y` returns true if `x` is less than `y`. Return `false` on unordered
arguments, i.e., if either argument is NaN, so that the usual reversal
of comparison under negation does not hold, e.g., `a < b` is not the
same as `not (a >= b)`.

<a id="<=-impl"></a>
<h3><code><=</code></h3>

`x <= y` As "<"

<a id=">-impl"></a>
<h3><code>></code></h3>

`x > y` As "<"

<a id=">=-impl"></a>
<h3><code>>=</code></h3>

`x >= y` As "<"

<a id="~-impl"></a>
<h3><code>~</code></h3>

`~ r` returns the negation of `r`.

<a id="abs-impl"></a>
<h3><code>abs</code></h3>

`abs r` (or `r.abs ()`) returns the absolute value of `r`.

<a id="ceil-impl"></a>
<h3><code>ceil</code></h3>

`ceil r` (or `r.ceil ()`) produces `ceil(r)`, the smallest int not less than `r`.

<a id="checkFloat-impl"></a>
<h3><code>checkFloat</code></h3>

`checkFloat x` (or `x.checkFloat ()`) raises `Overflow` if x is an infinity, and raises `Div`
if x is NaN. Otherwise, it returns its argument.

<a id="compare-impl"></a>
<h3><code>compare</code></h3>

`compare (x, y)` (or `x.compare y`) returns `LESS`, `EQUAL`, or `GREATER` according to
whether its first argument is less than, equal to, or greater than the
second. It raises `IEEEReal.Unordered` on unordered arguments.

<a id="copySign-impl"></a>
<h3><code>copySign</code></h3>

`copySign (x, y)` (or `x.copySign y`) returns `x` with the sign of `y`, even if `y` is
NaN.

<a id="floor-impl"></a>
<h3><code>floor</code></h3>

`floor r` (or `r.floor ()`) produces `floor(r)`, the largest int not larger than `r`.

<a id="fromInt-impl"></a>
<h3><code>fromInt</code></h3>

`fromInt i` converts the integer `i` to a `real` value. If the
absolute value of `i` is larger than `maxFinite`, then the appropriate
infinity is returned. If `i` cannot be exactly represented as a `real`
value, uses current rounding mode to determine the resulting value.

<a id="fromManExp-impl"></a>
<h3><code>fromManExp</code></h3>

`fromManExp r` returns `{man, exp}`, where `man` and `exp` are the
mantissa and exponent of r, respectively.

<a id="fromString-impl"></a>
<h3><code>fromString</code></h3>

`fromString s` scans a `real` value from a string. Returns `SOME (r)`
if a `real` value can be scanned from a prefix of `s`, ignoring any
initial whitespace; otherwise, it returns `NONE`. This function is
equivalent to `StringCvt.scanString scan`.

<a id="isFinite-impl"></a>
<h3><code>isFinite</code></h3>

`isFinite x` (or `x.isFinite ()`) returns true if x is neither NaN nor an infinity.

<a id="isNan-impl"></a>
<h3><code>isNan</code></h3>

`isNan x` (or `x.isNan ()`) returns true if x NaN.

<a id="isNormal-impl"></a>
<h3><code>isNormal</code></h3>

`isNormal x` (or `x.isNormal ()`) returns true if x is normal, i.e., neither zero,
subnormal, infinite nor NaN.

<a id="max-impl"></a>
<h3><code>max</code></h3>

`max (x, y)` (or `x.max y`) returns the larger of the arguments. If exactly one
argument is NaN, returns the other argument. If both arguments are
NaN, returns NaN.

<a id="maxFinite-impl"></a>
<h3><code>maxFinite</code></h3>

`maxFinite` is the maximum finite number.

<a id="min-impl"></a>
<h3><code>min</code></h3>

`min (x, y)` (or `x.min y`) returns the smaller of the arguments. If exactly one
argument is NaN, returns the other argument. If both arguments are
NaN, returns NaN.

<a id="minNormalPos-impl"></a>
<h3><code>minNormalPos</code></h3>

`minNormalPos` is the minimum non-zero normalized number.

<a id="minPos-impl"></a>
<h3><code>minPos</code></h3>

`minPos` is the minimum non-zero positive number.

<a id="negInf-impl"></a>
<h3><code>negInf</code></h3>

`negInf` is the negative infinity value.

<a id="posInf-impl"></a>
<h3><code>posInf</code></h3>

`posInf` is the positive infinity value.

<a id="precision-impl"></a>
<h3><code>precision</code></h3>

`precision` is the number of digits, each between 0 and `radix` - 1,
in the mantissa. Note that the precision includes the implicit (or
hidden) bit used in the IEEE representation (e.g., the value of
Real64.precision is 53).

<a id="radix-impl"></a>
<h3><code>radix</code></h3>

`radix` is the base of the representation, e.g., 2 or 10 for IEEE
floating point.

<a id="realCeil-impl"></a>
<h3><code>realCeil</code></h3>

`realCeil r` (or `r.realCeil ()`) produces `ceil(r)`, the smallest integer not less than
`r`.

<a id="realFloor-impl"></a>
<h3><code>realFloor</code></h3>

`realFloor r` (or `r.realFloor ()`) produces `floor(r)`, the largest integer not larger than
`r`.

<a id="realMod-impl"></a>
<h3><code>realMod</code></h3>

`realMod r` (or `r.realMod ()`) returns the fractional parts of `r`; `realMod` is
equivalent to `#frac o split`.

<a id="realRound-impl"></a>
<h3><code>realRound</code></h3>

`realRound r` (or `r.realRound ()`) rounds to the integer-valued real value that is nearest
to `r`. In the case of a tie, it rounds to the nearest even integer.

<a id="realTrunc-impl"></a>
<h3><code>realTrunc</code></h3>

`realTrunc r` (or `r.realTrunc ()`) rounds `r` towards zero.

<a id="rem-impl"></a>
<h3><code>rem</code></h3>

`rem (x, y)` (or `x.rem y`) returns the remainder `x - n * y`, where `n` = `trunc (x
/ y)`. The result has the same sign as `x` and has absolute value less
than the absolute value of `y`. If `x` is an infinity or `y` is 0,
`rem` returns NaN. If `y` is an infinity, rem returns `x`.

<a id="round-impl"></a>
<h3><code>round</code></h3>

`round r` (or `r.round ()`) yields the integer nearest to `r`. In the case of a tie, it
rounds to the nearest even integer.

<a id="sameSign-impl"></a>
<h3><code>sameSign</code></h3>

`sameSign (r1, r2)` (or `r1.sameSign r2`) returns true if and only if `signBit r1` equals
`signBit r2`.

<a id="sign-impl"></a>
<h3><code>sign</code></h3>

`sign r` (or `r.sign ()`) returns ~1 if r is negative, 0 if r is zero, or 1 if r is
positive. An infinity returns its sign; a zero returns 0 regardless of
its sign. It raises `Domain` on NaN.

<a id="signBit-impl"></a>
<h3><code>signBit</code></h3>

`signBit r` (or `r.signBit ()`) returns true if and only if the sign of `r` (infinities,
zeros, and NaN, included) is negative.

<a id="split-impl"></a>
<h3><code>split</code></h3>

`split r` (or `r.split ()`) returns `{frac, whole}`, where `frac` and `whole` are the
fractional and integral parts of `r`, respectively. Specifically,
`whole` is integral, and `abs frac` < 1.0.

<a id="trunc-impl"></a>
<h3><code>trunc</code></h3>

`trunc r` (or `r.trunc ()`) rounds r towards zero.

<a id="toManExp-impl"></a>
<h3><code>toManExp</code></h3>

`toManExp r` (or `r.toManExp ()`) returns `{man, exp}`, where `man` and `exp` are the
mantissa and exponent of r, respectively.

<a id="toString-impl"></a>
<h3><code>toString</code></h3>

`toString r` (or `r.toString ()`) converts a `real` into a `string`; equivalent to `(fmt
(StringCvt.GEN NONE) r)`

<a id="unordered-impl"></a>
<h3><code>unordered</code></h3>

`unordered (x, y)` (or `x.unordered y`) returns true if x and y are unordered, i.e., at
least one of x and y is NaN.

<a id="!=-impl"></a>
<h3><code>!=</code></h3>

`x != y` is equivalent to `not o op ==` and the IEEE `?<>` operator.

*Not yet implemented.*

<a id="*+-impl"></a>
<h3><code>*+</code></h3>

`*+ (a, b, c)` returns `a * b + c`. Its behavior on infinities follows
from the behaviors derived from addition and multiplication.

*Not yet implemented.*

<a id="*--impl"></a>
<h3><code>*-</code></h3>

`*- (a, b, c)` returns `a * b - c`. Its behavior on infinities follows
from the behaviors derived from subtraction and multiplication.

*Not yet implemented.*

<a id="==-impl"></a>
<h3><code>==</code></h3>

`x == y` returns `true` if and only if neither `y` nor `x` is NaN, and `y` and
`x` are equal, ignoring signs on zeros. This is equivalent to the IEEE
`=` operator.

*Not yet implemented.*

<a id="?=-impl"></a>
<h3><code>?=</code></h3>

`?= (x, y)` returns `true` if either argument is NaN or if the arguments
are bitwise equal, ignoring signs on zeros. It is equivalent to the
IEEE `?=` operator.

*Not yet implemented.*

<a id="class-impl"></a>
<h3><code>class</code></h3>

`class x` returns the `IEEEReal.float_class` to which x belongs.

*Not yet implemented.*

<a id="compareReal-impl"></a>
<h3><code>compareReal</code></h3>

`compareReal (x, y)` behaves similarly to `Real.compare` except that
the values it returns have the extended type `IEEEReal.real_order` and
it returns `IEEEReal.UNORDERED` on unordered arguments.

*Not yet implemented.*

<a id="fmt-impl"></a>
<h3><code>fmt</code></h3>

`fmt spec r` converts a `real` into a `string` according to by `spec`;
raises `Size` when `fmt spec` is evaluated if `spec` is an invalid
precision

*Not yet implemented.*

<a id="fromDecimal-impl"></a>
<h3><code>fromDecimal</code></h3>

`fromDecimal d` converts decimal approximation to a `real`

*Not yet implemented.*

<a id="fromLarge-impl"></a>
<h3><code>fromLarge</code></h3>

`toLarge r` converts a value of type `real` to type
`LargeReal.real`. If `r` is too small or too large to be represented
as a real, converts it to a zero or an infinity.

*Not yet implemented.*

<a id="fromLargeInt-impl"></a>
<h3><code>fromLargeInt</code></h3>

`fromLargeInt i` As "fromInt"

*Not yet implemented.*

<a id="nextAfter-impl"></a>
<h3><code>nextAfter</code></h3>

`nextAfter (r, t)` returns the next representable real after `r` in
the direction of `t`. Thus, if `t` is less than `r`, `nextAfter`
returns the largest representable floating-point number less than `r`.

*Not yet implemented.*

<a id="scan-impl"></a>
<h3><code>scan</code></h3>

`scan getc strm` scans a `real` value from character source. Reads
from ARG/strm/ using reader `getc`, ignoring initial whitespace. It
returns `SOME (r, rest)` if successful, where `r` is the scanned
`real` value and `rest` is the unused portion of the character stream
`strm`. Values of too large a magnitude are represented as infinities;
values of too small a magnitude are represented as zeros.

*Not yet implemented.*

<a id="toDecimal-impl"></a>
<h3><code>toDecimal</code></h3>

`toDecimal r` converts a `real` to a decimal approximation

*Not yet implemented.*

<a id="toInt-impl"></a>
<h3><code>toInt</code></h3>

`toInt mode x` converts the argument `x` to an integral type using the
specified rounding mode. It raises `Overflow` if the result is not
representable, in particular, if `x` is an infinity. It raises
`Domain` if the input real is NaN.

*Not yet implemented.*

<a id="toLarge-impl"></a>
<h3><code>toLarge</code></h3>

`toLarge r` convert a value of type `real` to type `LargeReal.real`.

*Not yet implemented.*

<a id="toLargeInt-impl"></a>
<h3><code>toLargeInt</code></h3>

`toLargeInt mode r` As "toInt"

*Not yet implemented.*

[//]: # (end:lib/real)
