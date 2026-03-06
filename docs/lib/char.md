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

# Char structure

[Up to index](index.md)

[//]: # (start:lib/char)
The `Char` structure provides the character type and associated
operations for examining and converting characters. Characters are
identified by their Unicode code points.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/char.html).*

## Synopsis

<pre>
eqtype <a id='char' href="#char-impl">char</a>

val <a id='chr' href="#chr-impl">chr</a> : int -> char
val <a id='compare' href="#compare-impl">compare</a> : char * char -> order
val <a id='contains' href="#contains-impl">contains</a> : char -> string -> bool
val <a id='fromCString' href="#fromCString-impl">fromCString</a> : string -> char option
val <a id='fromInt' href="#fromInt-impl">fromInt</a> : int -> char option
val <a id='fromString' href="#fromString-impl">fromString</a> : string -> char option
val <a id='isAlpha' href="#isAlpha-impl">isAlpha</a> : char -> bool
val <a id='isAlphaNum' href="#isAlphaNum-impl">isAlphaNum</a> : char -> bool
val <a id='isAscii' href="#isAscii-impl">isAscii</a> : char -> bool
val <a id='isCntrl' href="#isCntrl-impl">isCntrl</a> : char -> bool
val <a id='isDigit' href="#isDigit-impl">isDigit</a> : char -> bool
val <a id='isGraph' href="#isGraph-impl">isGraph</a> : char -> bool
val <a id='isHexDigit' href="#isHexDigit-impl">isHexDigit</a> : char -> bool
val <a id='isLower' href="#isLower-impl">isLower</a> : char -> bool
val <a id='isOctDigit' href="#isOctDigit-impl">isOctDigit</a> : char -> bool
val <a id='isPrint' href="#isPrint-impl">isPrint</a> : char -> bool
val <a id='isPunct' href="#isPunct-impl">isPunct</a> : char -> bool
val <a id='isSpace' href="#isSpace-impl">isSpace</a> : char -> bool
val <a id='isUpper' href="#isUpper-impl">isUpper</a> : char -> bool
val <a id='maxOrd' href="#maxOrd-impl">maxOrd</a> : int
val <a id='maxChar' href="#maxChar-impl">maxChar</a> : int
val <a id='minChar' href="#minChar-impl">minChar</a> : char
val <a id='notContains' href="#notContains-impl">notContains</a> : char -> string -> bool
val <a id='ord' href="#ord-impl">ord</a> : char -> int
val <a id='pred' href="#pred-impl">pred</a> : char -> char
val <a id='succ' href="#succ-impl">succ</a> : char -> char
val <a id='toCString' href="#toCString-impl">toCString</a> : char -> string
val <a id='toLower' href="#toLower-impl">toLower</a> : char -> char
val <a id='toString' href="#toString-impl">toString</a> : char -> string
val <a id='toUpper' href="#toUpper-impl">toUpper</a> : char -> char
val <a id='<' href="#<-impl"><</a> : char * char -> bool
val <a id='<=' href="#<=-impl"><=</a> : char * char -> bool
val <a id='>' href="#>-impl">></a> : char * char -> bool
val <a id='>=' href="#>=-impl">>=</a> : char * char -> bool
</pre>

<a id="char-impl"></a>
<h3><code><strong>eqtype</strong> char</code></h3>

is the type of characters.

<a id="chr-impl"></a>
<h3><code>chr</code></h3>

`chr i` returns the character whose code is `i`. Raises `Chr` if `i` <
0 or `i` > `maxOrd`.

<a id="compare-impl"></a>
<h3><code>compare</code></h3>

`compare (c1, c2)` returns `LESS`, `EQUAL`, or `GREATER` according to
whether its first argument is less than, equal to, or greater than the
second.

<a id="contains-impl"></a>
<h3><code>contains</code></h3>

`contains s c` returns true if character `c` occurs in the string `s`;
false otherwise. The function, when applied to `s`, builds a table and
returns a function which uses table lookup to decide whether a given
character is in the string or not. Hence it is relatively expensive to
compute `val p = contains s` but very fast to compute `p(c)` for any
given character.

<a id="fromCString-impl"></a>
<h3><code>fromCString</code></h3>

`fromCString s` scans a `char` value from a string. Returns `SOME (r)`
if a `char` value can be scanned from a prefix of `s`, ignoring any
initial whitespace; otherwise, it returns `NONE`. Equivalent to
`StringCvt.scanString (scan StringCvt.ORD)`.

<a id="fromInt-impl"></a>
<h3><code>fromInt</code></h3>

`fromInt i` converts an `int` into a `char`. Raises `Chr` if `i` < 0
or `i` > `maxOrd`.

<a id="fromString-impl"></a>
<h3><code>fromString</code></h3>

`fromString s` attempts to scan a character or ML escape sequence from
the string `s`. Does not skip leading whitespace. For instance,
`fromString "\\065"` equals `#"A"`.

<a id="isAlpha-impl"></a>
<h3><code>isAlpha</code></h3>

`isAlpha c` returns true if `c` is a letter (lowercase or uppercase).

<a id="isAlphaNum-impl"></a>
<h3><code>isAlphaNum</code></h3>

`isAlphaNum c` returns true if `c` is alphanumeric (a letter or a
decimal digit).

<a id="isAscii-impl"></a>
<h3><code>isAscii</code></h3>

`isAscii c` returns true if 0 ≤ `ord c` ≤ 127 `c`.

<a id="isCntrl-impl"></a>
<h3><code>isCntrl</code></h3>

`isCntrl c` returns true if `c` is a control character, that is, if
`not (isPrint c)`.

<a id="isDigit-impl"></a>
<h3><code>isDigit</code></h3>

`isDigit c` returns true if `c` is a decimal digit (0 to 9).

<a id="isGraph-impl"></a>
<h3><code>isGraph</code></h3>

`isGraph c` returns true if `c` is a graphical character, that is, it
is printable and not a whitespace character.

<a id="isHexDigit-impl"></a>
<h3><code>isHexDigit</code></h3>

`isHexDigit c` returns true if `c` is a hexadecimal digit.

<a id="isLower-impl"></a>
<h3><code>isLower</code></h3>

`isLower c` returns true if `c` is a hexadecimal digit (0 to 9 or a to
f or A to F).

<a id="isOctDigit-impl"></a>
<h3><code>isOctDigit</code></h3>

`isOctDigit c` returns true if `c` is an octal digit.

<a id="isPrint-impl"></a>
<h3><code>isPrint</code></h3>

`isPrint c` returns true if `c` is a printable character (space or
visible).

<a id="isPunct-impl"></a>
<h3><code>isPunct</code></h3>

`isPunct c` returns true if `c` is a punctuation character, that is,
graphical but not alphanumeric.

<a id="isSpace-impl"></a>
<h3><code>isSpace</code></h3>

`isSpace c` returns true if `c` is a whitespace character (blank,
newline, tab, vertical tab, new page).

<a id="isUpper-impl"></a>
<h3><code>isUpper</code></h3>

`isUpper c` returns true if `c` is an uppercase letter (A to Z).

<a id="maxOrd-impl"></a>
<h3><code>maxOrd</code></h3>

`maxOrd` is the greatest character code; it equals `ord maxChar`.

<a id="maxChar-impl"></a>
<h3><code>maxChar</code></h3>

`maxChar` is the greatest character in the ordering `<`.

<a id="minChar-impl"></a>
<h3><code>minChar</code></h3>

`minChar` is the minimal (most negative) character representable by
`char`. If a value is `NONE`, `char` can represent all negative
integers, within the limits of the heap size. If `precision` is `SOME
(n)`, then we have `minChar` = -2<sup>(n-1)</sup>.

<a id="notContains-impl"></a>
<h3><code>notContains</code></h3>

`notContains s c` returns true if character `c` does not occur in the
string `s`; false otherwise. Works by construction of a lookup table
in the same way as `Char.contains`.

<a id="ord-impl"></a>
<h3><code>ord</code></h3>

`ord c` returns the code of character `c`.

<a id="pred-impl"></a>
<h3><code>pred</code></h3>

`pred c` returns the predecessor of `c`. Raises `Subscript` if `c` is
`minOrd`.

<a id="succ-impl"></a>
<h3><code>succ</code></h3>

`succ c` returns the character immediately following `c`, or raises
`Chr` if `c` = `maxChar`

<a id="toCString-impl"></a>
<h3><code>toCString</code></h3>

`toCString c` converts a `char` into a `string`; equivalent to `(fmt
StringCvt.ORD r)`.

<a id="toLower-impl"></a>
<h3><code>toLower</code></h3>

`toLower c` returns the lowercase letter corresponding to `c`, if `c`
is a letter (a to z or A to Z); otherwise returns `c`.

<a id="toString-impl"></a>
<h3><code>toString</code></h3>

`toString c` converts a `char` into a `string`; equivalent to `(fmt
StringCvt.ORD r)`.

<a id="toUpper-impl"></a>
<h3><code>toUpper</code></h3>

`toUpper c` returns the uppercase letter corresponding to `c`, if `c`
is a letter (a to z or A to Z); otherwise returns `c`.

<a id="<-impl"></a>
<h3><code><</code></h3>

`c1 < c2` returns true if `c1` is less than `c2` in the character ordering.

<a id="<=-impl"></a>
<h3><code><=</code></h3>

`c1 <= c2` returns true if `c1` is less than or equal to `c2` in the character
ordering.

<a id=">-impl"></a>
<h3><code>></code></h3>

`c1 > c2` returns true if `c1` is greater than `c2` in the character ordering.

<a id=">=-impl"></a>
<h3><code>>=</code></h3>

`c1 >= c2` returns true if `c1` is greater than or equal to `c2` in the character
ordering.

[//]: # (end:lib/char)
