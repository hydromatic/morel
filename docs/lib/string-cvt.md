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

[//]: # (end:lib/string-cvt)
