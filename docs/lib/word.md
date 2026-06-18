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

# Word structure

[Up to index](index.md)

[//]: # (start:lib/word)
The `Word` structure provides a type of unsigned integer with modular
arithmetic, logical (bit-wise) operations, and conversions. Words are
meant to give efficient access to the primitive machine word type of the
underlying hardware, and to support bit-level operations on integers.

In Morel, `word` is a 64-bit unsigned integer (`wordSize` is 64),
`LargeWord.word` = `word`, and `LargeInt.int` = `int`.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/word.html).*

## Synopsis

<pre>
type <a id='word' href="#word-impl">word</a>

val <a id='wordSize' href="#wordSize-impl">wordSize</a> : int
val <a id='toLarge' href="#toLarge-impl">toLarge</a> : word -> word
val <a id='toLargeX' href="#toLargeX-impl">toLargeX</a> : word -> word
val <a id='toLargeWord' href="#toLargeWord-impl">toLargeWord</a> : word -> word
val <a id='toLargeWordX' href="#toLargeWordX-impl">toLargeWordX</a> : word -> word
val <a id='fromLarge' href="#fromLarge-impl">fromLarge</a> : word -> word
val <a id='fromLargeWord' href="#fromLargeWord-impl">fromLargeWord</a> : word -> word
val <a id='toLargeInt' href="#toLargeInt-impl">toLargeInt</a> : word -> int
val <a id='toLargeIntX' href="#toLargeIntX-impl">toLargeIntX</a> : word -> int
val <a id='fromLargeInt' href="#fromLargeInt-impl">fromLargeInt</a> : int -> word
val <a id='toInt' href="#toInt-impl">toInt</a> : word -> int
val <a id='toIntX' href="#toIntX-impl">toIntX</a> : word -> int
val <a id='fromInt' href="#fromInt-impl">fromInt</a> : int -> word
val <a id='andb' href="#andb-impl">andb</a> : word * word -> word
val <a id='orb' href="#orb-impl">orb</a> : word * word -> word
val <a id='xorb' href="#xorb-impl">xorb</a> : word * word -> word
val <a id='notb' href="#notb-impl">notb</a> : word -> word
val <a id='<<' href="#<<-impl"><<</a> : word * word -> word
val <a id='>>' href="#>>-impl">>></a> : word * word -> word
val <a id='~>>' href="#~>>-impl">~>></a> : word * word -> word
val <a id='+' href="#+-impl">+</a> : word * word -> word
val <a id='-' href="#--impl">-</a> : word * word -> word
val <a id='*' href="#*-impl">*</a> : word * word -> word
val <a id='div' href="#div-impl">div</a> : word * word -> word
val <a id='mod' href="#mod-impl">mod</a> : word * word -> word
val <a id='compare' href="#compare-impl">compare</a> : word * word -> order
val <a id='<' href="#<-impl"><</a> : word * word -> bool
val <a id='<=' href="#<=-impl"><=</a> : word * word -> bool
val <a id='>' href="#>-impl">></a> : word * word -> bool
val <a id='>=' href="#>=-impl">>=</a> : word * word -> bool
val <a id='~' href="#~-impl">~</a> : word -> word
val <a id='min' href="#min-impl">min</a> : word * word -> word
val <a id='max' href="#max-impl">max</a> : word * word -> word
val <a id='fmt' href="#fmt-impl">fmt</a> : radix -> word -> string
val <a id='toString' href="#toString-impl">toString</a> : word -> string
val <a id='fromString' href="#fromString-impl">fromString</a> : string -> word option
</pre>

<a id="word-impl"></a>
<h3><code><strong>type</strong> word</code></h3>

is the type of unsigned, fixed-precision integers (words).

<a id="wordSize-impl"></a>
<h3><code>wordSize</code></h3>

`wordSize` is the number of bits in type `word`. In Morel, `wordSize` is 64.
`wordSize` need not be a power of two; note that `word` has a fixed,
finite precision.

<a id="toLarge-impl"></a>
<h3><code>toLarge</code></h3>

`toLarge w` converts `w` to an equivalent value in `LargeWord.word`, in the range
[0, 2<sup>wordSize</sup> - 1]. In Morel, `LargeWord.word` = `word`, so
this is the identity.

<a id="toLargeX-impl"></a>
<h3><code>toLargeX</code></h3>

`toLargeX w` is the "sign-extended" conversion of `w` to `LargeWord.word`: the
`wordSize` low-order bits of `w` and `toLargeX w` are the same, and the
remaining bits of `toLargeX w` are all equal to the most significant bit
of `w`. In Morel, `LargeWord.word` = `word`, so this is the identity.

<a id="toLargeWord-impl"></a>
<h3><code>toLargeWord</code></h3>

`toLargeWord w` is a deprecated synonym of `toLarge`.

<a id="toLargeWordX-impl"></a>
<h3><code>toLargeWordX</code></h3>

`toLargeWordX w` is a deprecated synonym of `toLargeX`.

<a id="fromLarge-impl"></a>
<h3><code>fromLarge</code></h3>

`fromLarge w` converts `w` to the value `w` (mod 2<sup>wordSize</sup>) of type `word`,
taking the low-order `wordSize` bits of the 2's complement representation
of `w`. In Morel, `LargeWord.word` = `word`, so this is the identity.

<a id="fromLargeWord-impl"></a>
<h3><code>fromLargeWord</code></h3>

`fromLargeWord w` is a deprecated synonym of `fromLarge`.

<a id="toLargeInt-impl"></a>
<h3><code>toLargeInt</code></h3>

`toLargeInt w` converts `w`, treated as an integer value in the range
[0, 2<sup>wordSize</sup> - 1], to `LargeInt.int`. It raises `Overflow` if
the value cannot be represented as a `LargeInt.int`. In Morel,
`LargeInt.int` = `int`, so it raises `Overflow` when `w` exceeds
`Int.maxInt`.

<a id="toLargeIntX-impl"></a>
<h3><code>toLargeIntX</code></h3>

`toLargeIntX w` converts `w`, treated as a 2's complement signed integer with `wordSize`
precision, to `LargeInt.int`. In Morel, `LargeInt.int` = `int`
(fixed-precision), so it raises `Overflow` when the value cannot be
represented as an `int`.

<a id="fromLargeInt-impl"></a>
<h3><code>fromLargeInt</code></h3>

`fromLargeInt i` converts `i` of type `LargeInt.int` to a value of type `word`, taking the
low-order `wordSize` bits of the 2's complement representation of `i`.

<a id="toInt-impl"></a>
<h3><code>toInt</code></h3>

`toInt w` converts `w`, treated as an integer value in the range
[0, 2<sup>wordSize</sup> - 1], to the default integer type. It raises
`Overflow` if the value cannot be represented as an `Int.int`.

<a id="toIntX-impl"></a>
<h3><code>toIntX</code></h3>

`toIntX w` converts `w`, treated as a 2's complement signed integer with `wordSize`
precision, to the default integer type. It raises `Overflow` if the value
cannot be represented as an `Int.int`.

<a id="fromInt-impl"></a>
<h3><code>fromInt</code></h3>

`fromInt i` converts `i` of the default integer type to a value of type `word`,
taking the low-order `wordSize` bits of the 2's complement representation
of `i`.

<a id="andb-impl"></a>
<h3><code>andb</code></h3>

`andb (i, j)` (or `i.andb j`) returns the bit-wise AND of `i` and `j`.

<a id="orb-impl"></a>
<h3><code>orb</code></h3>

`orb (i, j)` (or `i.orb j`) returns the bit-wise OR of `i` and `j`.

<a id="xorb-impl"></a>
<h3><code>xorb</code></h3>

`xorb (i, j)` (or `i.xorb j`) returns the bit-wise exclusive OR of `i` and `j`.

<a id="notb-impl"></a>
<h3><code>notb</code></h3>

`notb i` (or `i.notb ()`) returns the bit-wise complement (NOT) of `i`.

<a id="<<-impl"></a>
<h3><code><<</code></h3>

`i << n` shifts `i` to the left by `n` bit positions, filling in zeros from the
right. It returns `(i * 2`<sup>n</sup>`) (mod 2`<sup>wordSize</sup>`)`.
When `n >= wordSize` the result is `0w0`.

<a id=">>-impl"></a>
<h3><code>>></code></h3>

`i >> n` shifts `i` to the right by `n` bit positions, filling in zeros from the
left. It returns `floor(i / 2`<sup>n</sup>`)`. When `n >= wordSize` the
result is `0w0`.

<a id="~>>-impl"></a>
<h3><code>~>></code></h3>

`i ~>> n` shifts `i` to the right by `n` bit positions. The value of the leftmost
bit of `i` remains the same; in a 2's complement interpretation this
corresponds to sign extension. It returns `floor(i / 2`<sup>n</sup>`)`.

<a id="+-impl"></a>
<h3><code>+</code></h3>

`i + j` returns the sum `(i + j) (mod 2`<sup>wordSize</sup>`)`. It does not raise
`Overflow`.

<a id="--impl"></a>
<h3><code>-</code></h3>

`i - j` returns the difference of `i` and `j` modulo 2<sup>wordSize</sup>:
`(2`<sup>wordSize</sup>` + i - j) (mod 2`<sup>wordSize</sup>`)`. It does
not raise `Overflow`.

<a id="*-impl"></a>
<h3><code>*</code></h3>

`i * j` returns the product `(i * j) (mod 2`<sup>wordSize</sup>`)`. It does not
raise `Overflow`.

<a id="div-impl"></a>
<h3><code>div</code></h3>

`i div j` returns the truncated quotient of `i` and `j`, `floor(i / j)`, treating
the arguments as unsigned. It raises `Div` when `j = 0w0`.

<a id="mod-impl"></a>
<h3><code>mod</code></h3>

`i mod j` returns the remainder of the division of `i` by `j`,
`i - j * floor(i / j)`, treating the arguments as unsigned. It raises
`Div` when `j = 0w0`.

<a id="compare-impl"></a>
<h3><code>compare</code></h3>

`compare (i, j)` (or `i.compare j`) returns `LESS`, `EQUAL`, or `GREATER` if and only if `i` is less than,
equal to, or greater than `j`, respectively, considered as unsigned
binary numbers.

<a id="<-impl"></a>
<h3><code><</code></h3>

`i < j` returns true if `i` is less than `j`, considered as unsigned.

<a id="<=-impl"></a>
<h3><code><=</code></h3>

`i <= j` returns true if `i` is less than or equal to `j`, considered as unsigned.

<a id=">-impl"></a>
<h3><code>></code></h3>

`i > j` returns true if `i` is greater than `j`, considered as unsigned.

<a id=">=-impl"></a>
<h3><code>>=</code></h3>

`i >= j` returns true if `i` is greater than or equal to `j`, considered as
unsigned.

<a id="~-impl"></a>
<h3><code>~</code></h3>

`~ i` returns the 2's complement of `i`.

<a id="min-impl"></a>
<h3><code>min</code></h3>

`min (i, j)` (or `i.min j`) returns the smaller of the arguments, considered as unsigned.

<a id="max-impl"></a>
<h3><code>max</code></h3>

`max (i, j)` (or `i.max j`) returns the larger of the arguments, considered as unsigned.

<a id="fmt-impl"></a>
<h3><code>fmt</code></h3>

`fmt radix i` returns a string containing a representation of `i` in the given `radix`.
The hexadecimal digits 10 through 15 are represented as #"A" through
#"F", respectively. No prefix "0w" or "0wx" is generated.

<a id="toString-impl"></a>
<h3><code>toString</code></h3>

`toString i` (or `i.toString ()`) converts a `word` into a `string`; equivalent to
`(fmt StringCvt.HEX i)`.

<a id="fromString-impl"></a>
<h3><code>fromString</code></h3>

`fromString s` returns `SOME (w)` if an unsigned hexadecimal number can be parsed from a
prefix of string `s`, ignoring initial whitespace; otherwise it returns
`NONE`. Equivalent to `StringCvt.scanString (scan StringCvt.HEX)`. It
raises `Overflow` when a hexadecimal numeral can be parsed, but is too
large to be represented by type `word`.

[//]: # (end:lib/word)
