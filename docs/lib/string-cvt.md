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

# StringCvt structure

[Up to index](index.md)

[//]: # (start:lib/string-cvt)
The `StringCvt` structure provides types and utilities to support
formatted string scanning and conversion, including numeric radix
specifiers and reader types.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/string-cvt.html).*

## Synopsis

<pre>
datatype <a id='radix' href="#radix-impl">radix</a> = BIN | OCT | DEC | HEX
type ('a, 'b) <a id='reader' href="#reader-impl">reader</a> = 'b -> ('a * 'b) option
datatype <a id='realfmt' href="#realfmt-impl">realfmt</a>
  = SCI of int option
  | FIX of int option
  | GEN of int option
  | EXACT

val <a id='padLeft' href="#padLeft-impl">padLeft</a> : char -> int -> string -> string
val <a id='padRight' href="#padRight-impl">padRight</a> : char -> int -> string -> string
val <a id='splitl' href="#splitl-impl">splitl</a> : (char -> bool) -> (char, 'a) reader -> 'a -> string * 'a
val <a id='takel' href="#takel-impl">takel</a> : (char -> bool) -> (char, 'a) reader -> 'a -> string
val <a id='dropl' href="#dropl-impl">dropl</a> : (char -> bool) -> (char, 'a) reader -> 'a -> 'a
val <a id='skipWS' href="#skipWS-impl">skipWS</a> : (char, 'a) reader -> 'a -> 'a
val <a id='scanString' href="#scanString-impl">scanString</a> : ((char, 'b) reader -> ('a, 'b) reader) -> string -> 'a option
</pre>

<a id="radix-impl"></a>
<h3><code><strong>datatype</strong> radix</code></h3>

specifies the numeric base: binary (2), octal (8), decimal (10), or
hexadecimal (16).

<a id="reader-impl"></a>
<h3><code><strong>type</strong> ('a, 'b) reader</code></h3>

is the type of a scanning function that reads one value of type `'a`
from a stream of type `'b`, returning the value and the remaining
stream, or `NONE` at end of input.

<a id="realfmt-impl"></a>
<h3><code><strong>datatype</strong> realfmt</code></h3>

specifies the format for converting real numbers to strings.

<a id="padLeft-impl"></a>
<h3><code>padLeft</code></h3>

`padLeft c i s` `padLeft c i s` returns `s` padded on the left with `c` characters so
that the result has length at least `i`. If `s` is already at least
`i` characters long, it is returned unchanged.

<a id="padRight-impl"></a>
<h3><code>padRight</code></h3>

`padRight c i s` `padRight c i s` returns `s` padded on the right with `c` characters
so that the result has length at least `i`. If `s` is already at
least `i` characters long, it is returned unchanged.

<a id="splitl-impl"></a>
<h3><code>splitl</code></h3>

`splitl f rdr src` `splitl f rdr src` reads from `src` the longest prefix of characters
satisfying `f`, and returns that prefix together with the rest of
`src`.

<a id="takel-impl"></a>
<h3><code>takel</code></h3>

`takel f rdr src` `takel f rdr src` returns the longest prefix of `src` whose characters
satisfy `f`. It is the first component of `splitl f rdr src`.

<a id="dropl-impl"></a>
<h3><code>dropl</code></h3>

`dropl f rdr src` `dropl f rdr src` drops the longest prefix of `src` whose characters
satisfy `f`. It is the second component of `splitl f rdr src`.

<a id="skipWS-impl"></a>
<h3><code>skipWS</code></h3>

`skipWS rdr src` `skipWS rdr src` drops any leading whitespace from `src`.

<a id="scanString-impl"></a>
<h3><code>scanString</code></h3>

`scanString f s` `scanString f s` scans the string `s` using the scanner `f`, and returns
`SOME a` if `f` reads a value `a` from a prefix of `s`, `NONE` otherwise.
`f` is given a reader over the characters of `s`; the type of the stream
that it reads from is not specified.

[//]: # (end:lib/string-cvt)
