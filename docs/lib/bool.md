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
eqtype <a id='bool' href="#bool-impl">bool</a>

val <a id='fromString' href="#fromString-impl">fromString</a> : string -> bool option
val <a id='not' href="#not-impl">not</a> : bool -> bool
val <a id='toString' href="#toString-impl">toString</a> : bool -> string
(* Morel extensions *)
val <a id='implies' href="#implies-impl">implies</a> : bool * bool -> bool
</pre>

<a id="bool-impl"></a>
<h3><code><strong>eqtype</strong> bool</code></h3>

is the type of boolean values `true` and `false`.

<a id="fromString-impl"></a>
<h3><code>fromString</code></h3>

`fromString s` scans a `bool` value from the string `s`. Returns `SOME (true)` if
`s` is "true", `SOME (false)` if `s` is "false", and `NONE` otherwise.

<a id="not-impl"></a>
<h3><code>not</code></h3>

`not b` returns the logical inverse of `b`.

<a id="toString-impl"></a>
<h3><code>toString</code></h3>

`toString b` returns the string representation of `b`, either "true" or "false".

<a id="implies-impl"></a>
<h3><code>implies</code></h3>

`b1 implies b2` returns `true` if `b1` is `false` or `b2` is `true`.

[//]: # (end:lib/bool)
