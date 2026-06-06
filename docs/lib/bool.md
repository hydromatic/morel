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

# Bool structure

[Up to index](index.md)

[//]: # (start:lib/bool)
The `Bool` structure provides the boolean type and associated operations.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/bool.html).*

## Synopsis

<pre>
datatype <a id='bool' href="#bool-impl">bool</a> = false | true

val <a id='not' href="#not-impl">not</a> : bool -> bool
val <a id='toString' href="#toString-impl">toString</a> : bool -> string
val <a id='fromString' href="#fromString-impl">fromString</a> : string -> bool option
val <a id='andalso' href="#andalso-impl">andalso</a> : bool * bool -> bool
val <a id='orelse' href="#orelse-impl">orelse</a> : bool * bool -> bool
val <a id='implies' href="#implies-impl">implies</a> : bool * bool -> bool
val <a id='=' href="#=-impl">=</a> : bool * bool -> bool
val <a id='<>' href="#<>-impl"><></a> : bool * bool -> bool
val <a id='<' href="#<-impl"><</a> : bool * bool -> bool
val <a id='>' href="#>-impl">></a> : bool * bool -> bool
</pre>

<a id="bool-impl"></a>
<h3><code><strong>datatype</strong> bool</code></h3>

is the type of boolean values `true` and `false`.

<a id="not-impl"></a>
<h3><code>not</code></h3>

`not b` (or `b.not ()`) returns the logical inverse of `b`.

<a id="toString-impl"></a>
<h3><code>toString</code></h3>

`toString b` (or `b.toString ()`) returns the string representation of `b`, either "true" or "false".

<a id="fromString-impl"></a>
<h3><code>fromString</code></h3>

`fromString s` scans a `bool` value from the string `s`. Returns `SOME (true)` if
`s` is "true", `SOME (false)` if `s` is "false", and `NONE` otherwise.

<a id="andalso-impl"></a>
<h3><code>andalso</code></h3>

`andalso (b1, b2)` returns the logical conjunction of `b1` and `b2`. Unlike the `andalso`
keyword, always evaluates both arguments.

<a id="orelse-impl"></a>
<h3><code>orelse</code></h3>

`orelse (b1, b2)` returns the logical disjunction of `b1` and `b2`. Unlike the `orelse`
keyword, always evaluates both arguments.

<a id="implies-impl"></a>
<h3><code>implies</code></h3>

`implies (b1, b2)` returns the logical implication of `b1` and `b2`; that is, false only if
`b1` is true and `b2` is false. Unlike the `implies` keyword, always
evaluates both arguments.

<a id="=-impl"></a>
<h3><code>=</code></h3>

`b1 = b2` returns true if `b1` and `b2` are equal.

<a id="<>-impl"></a>
<h3><code><></code></h3>

`b1 <> b2` returns true if `b1` and `b2` are not equal.

<a id="<-impl"></a>
<h3><code><</code></h3>

`b1 < b2` returns true if `b1` is false and `b2` is true.

<a id=">-impl"></a>
<h3><code>></code></h3>

`b1 > b2` returns true if `b1` is true and `b2` is false.

[//]: # (end:lib/bool)
