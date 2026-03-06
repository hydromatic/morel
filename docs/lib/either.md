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

# Either structure

[Up to index](index.md)

[//]: # (start:lib/either)
The `Either` structure provides a polymorphic disjoint-sum type
`('left, 'right) either` whose values are either `INL v` (left) or
`INR v` (right), along with operations for examining and transforming
such values.

## Synopsis

<pre>
datatype ('left, 'right) <a id='either' href="#either-impl">either</a> = INL of 'left | INR of 'right

val <a id='isLeft' href="#isLeft-impl">isLeft</a> : ('left, 'right) either -> bool
val <a id='isRight' href="#isRight-impl">isRight</a> : ('left, 'right) either -> bool
val <a id='asLeft' href="#asLeft-impl">asLeft</a> : ('left, 'right) either -> 'left option
val <a id='asRight' href="#asRight-impl">asRight</a> : ('left, 'right) either -> 'right option
val <a id='map' href="#map-impl">map</a> : ('ldom -> 'lrng) * ('rdom -> 'rrng) -> ('ldom, 'rdom) either -> ('lrng, 'rrng) either
val <a id='mapLeft' href="#mapLeft-impl">mapLeft</a> : ('ldom -> 'lrng) -> ('ldom, 'rdom) either -> ('lrng, 'rdom) either
val <a id='mapRight' href="#mapRight-impl">mapRight</a> : ('rdom -> 'rrng) -> ('ldom, 'rdom) either -> ('ldom, 'rrng) either
val <a id='app' href="#app-impl">app</a> : ('left -> unit) * ('right -> unit) -> ('left, 'right) either -> unit
val <a id='appLeft' href="#appLeft-impl">appLeft</a> : ('left -> unit) -> ('left, 'right) either -> unit
val <a id='appRight' href="#appRight-impl">appRight</a> : ('right -> unit) -> ('left, 'right) either -> unit
val <a id='fold' href="#fold-impl">fold</a> : ('left * 'b -> 'b) * ('right * 'b -> 'b) -> 'b -> ('left, 'right) either -> 'b
val <a id='proj' href="#proj-impl">proj</a> : ('a, 'a) either -> 'a
val <a id='partition' href="#partition-impl">partition</a> : ('left, 'right) either list -> ('left list * 'right list)
</pre>

<a id="either-impl"></a>
<h3><code><strong>datatype</strong> ('left, 'right) either</code></h3>

is the type of disjoint-sum values; `INL v` represents a left value and
`INR v` represents a right value.

<a id="isLeft-impl"></a>
<h3><code>isLeft</code></h3>

`isLeft sm` returns true if `sm` is a left value.

<a id="isRight-impl"></a>
<h3><code>isRight</code></h3>

`isRight sm` returns true if `sm` is a right value.

<a id="asLeft-impl"></a>
<h3><code>asLeft</code></h3>

`asLeft sm` returns `SOME (x)` if `sm` is a left value with contents `x`,
otherwise it returns `NONE`.

<a id="asRight-impl"></a>
<h3><code>asRight</code></h3>

`asRight sm` returns `SOME (x)` if `sm` is a right value with contents `x`,
otherwise it returns `NONE`.

<a id="map-impl"></a>
<h3><code>map</code></h3>

`map (fl, fr) sm` maps `fl` over the contents of left values and `fr` over the contents
of right values.

<a id="mapLeft-impl"></a>
<h3><code>mapLeft</code></h3>

`mapLeft f sm` maps the function `f` over the contents of left values and acts as the
identity on right values.

<a id="mapRight-impl"></a>
<h3><code>mapRight</code></h3>

`mapRight f sm` maps the function `f` over the contents of right values and acts as the
identity on left values.

<a id="app-impl"></a>
<h3><code>app</code></h3>

`app (fl, fr) sm` applies `fl` to the contents of left values and `fr` to the contents
of right values.

<a id="appLeft-impl"></a>
<h3><code>appLeft</code></h3>

`appLeft f sm` applies `f` to the contents of left values and ignores right values.

<a id="appRight-impl"></a>
<h3><code>appRight</code></h3>

`appRight f sm` applies `f` to the contents of right values and ignores left values.

<a id="fold-impl"></a>
<h3><code>fold</code></h3>

`fold (fl, fr) init sm` computes `fx (v, init)`, where `v` is the contents of `sm` and `fx`
is either `fl` (if `sm` is a left value) or `fr` (if `sm` is a right
value).

<a id="proj-impl"></a>
<h3><code>proj</code></h3>

`proj sm` projects out the contents of `sm`.

<a id="partition-impl"></a>
<h3><code>partition</code></h3>

`partition sms` partitions the list of sum values into a list of left values and a
list of right values.

[//]: # (end:lib/either)
