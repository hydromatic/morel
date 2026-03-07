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

# Option structure

[Up to index](index.md)

[//]: # (start:lib/option)
The `Option` structure provides the `option` type `'a option` whose
values are either `NONE` (absent) or `SOME v` (present), along with
operations for creating, examining, and transforming optional values.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/option.html).*

## Synopsis

<pre>
datatype 'a <a id='option' href="#option-impl">option</a> = NONE | SOME of 'a

exception <a id='Option' href="#Option-impl">Option</a>

val <a id='app' href="#app-impl">app</a> : ('a -> unit) -> 'a option -> unit
val <a id='compose' href="#compose-impl">compose</a> : ('a -> 'b) * ('c -> 'a option) -> 'c -> 'b option
val <a id='composePartial' href="#composePartial-impl">composePartial</a> : ('a -> 'b option) * ('c -> 'a option) -> 'c -> 'b option
val <a id='map' href="#map-impl">map</a> : 'a -> 'b) -> 'a option -> 'b option
val <a id='mapPartial' href="#mapPartial-impl">mapPartial</a> : 'a -> 'b option) -> 'a option -> 'b option
val <a id='getOpt' href="#getOpt-impl">getOpt</a> : 'a option * 'a -> 'a
val <a id='isSome' href="#isSome-impl">isSome</a> : 'a option -> bool
val <a id='filter' href="#filter-impl">filter</a> : ('a -> bool) -> 'a -> 'a option
val <a id='join' href="#join-impl">join</a> : 'a option option -> 'a option
val <a id='valOf' href="#valOf-impl">valOf</a> : 'a option -> 'a
</pre>

<a id="option-impl"></a>
<h3><code><strong>datatype</strong> 'a option</code></h3>

The type `option` provides a distinction between some value and no
value, and is often used for representing the result of partially
defined functions. It can be viewed as a typed version of the C
convention of returning a NULL pointer to indicate no value.

<a id="Option-impl"></a>
<h3><code><strong>exception</strong> Option</code></h3>

is raised by `valOf` when applied to `NONE`.

<a id="app-impl"></a>
<h3><code>app</code></h3>

`app f opt` applies the function `f` to the value `v` if `opt` is
`SOME v`, and otherwise does nothing.

<a id="compose-impl"></a>
<h3><code>compose</code></h3>

`compose (f, g) a` returns `NONE` if `g(a)` is `NONE`; otherwise, if
`g(a)` is `SOME v`, it returns `SOME (f v)`.

<a id="composePartial-impl"></a>
<h3><code>composePartial</code></h3>

`composePartial (f, g) a` returns `NONE` if `g(a)` is `NONE`;
otherwise, if `g(a)` is `SOME v`, returns `f(v)`.

<a id="map-impl"></a>
<h3><code>map</code></h3>

`map f opt` maps `NONE` to `NONE` and `SOME v` to `SOME (f v)`.

<a id="mapPartial-impl"></a>
<h3><code>mapPartial</code></h3>

`mapPartial f opt` maps `NONE` to `NONE` and `SOME v` to `f(v)`.

<a id="getOpt-impl"></a>
<h3><code>getOpt</code></h3>

`getOpt (opt, a)` (or `opt.getOpt a`) returns `v` if `opt` is `SOME (v)`; otherwise
returns `a`.

<a id="isSome-impl"></a>
<h3><code>isSome</code></h3>

`isSome opt` (or `opt.isSome ()`) returns `true` if `opt` is `SOME v`; otherwise returns
`false`.

<a id="filter-impl"></a>
<h3><code>filter</code></h3>

`filter f a` returns `SOME a` if `f(a)` is `true`, `NONE` otherwise.

<a id="join-impl"></a>
<h3><code>join</code></h3>

`join opt` maps `NONE` to `NONE` and `SOME v` to `v`.

<a id="valOf-impl"></a>
<h3><code>valOf</code></h3>

`valOf opt` (or `opt.valOf ()`) returns `v` if `opt` is `SOME v`, otherwise raises
`Option`.

[//]: # (end:lib/option)
