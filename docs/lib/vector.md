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

# Vector structure

[Up to index](index.md)

[//]: # (start:lib/vector)
The `Vector` structure provides the `vector` type and operations for
creating, examining, and transforming immutable fixed-length sequences
of elements.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/vector.html).*

## Synopsis

<pre>
eqtype 'a <a id='vector' href="#vector-impl">vector</a>

val <a id='all' href="#all-impl">all</a> : ('a -> bool) -> 'a vector -> bool
val <a id='app' href="#app-impl">app</a> : ('a -> unit) -> 'a vector -> unit
val <a id='appi' href="#appi-impl">appi</a> : (int * 'a -> unit) -> 'a vector -> unit
val <a id='collate' href="#collate-impl">collate</a> : ('a * 'a -> order) -> 'a vector * 'a vector -> order
val <a id='concat' href="#concat-impl">concat</a> : 'a vector list -> 'a vector
val <a id='exists' href="#exists-impl">exists</a> : ('a -> bool) -> 'a vector -> bool
val <a id='find' href="#find-impl">find</a> : ('a -> bool) -> 'a vector -> 'a option
val <a id='findi' href="#findi-impl">findi</a> : (int * 'a -> bool) -> 'a vector -> (int * 'a) option
val <a id='foldl' href="#foldl-impl">foldl</a> : ('a * 'b -> 'b) -> 'b -> 'a vector -> 'b
val <a id='foldli' href="#foldli-impl">foldli</a> : (int * 'a * 'b -> 'b) -> 'b -> 'a vector -> 'b
val <a id='foldr' href="#foldr-impl">foldr</a> : ('a * 'b -> 'b) -> 'b -> 'a vector -> 'b
val <a id='foldri' href="#foldri-impl">foldri</a> : (int * 'a * 'b -> 'b) -> 'b -> 'a vector -> 'b
val <a id='fromList' href="#fromList-impl">fromList</a> : 'a list -> 'a vector
val <a id='length' href="#length-impl">length</a> : 'a vector -> int
val <a id='map' href="#map-impl">map</a> : ('a -> 'b) -> 'a vector -> 'b vector
val <a id='mapi' href="#mapi-impl">mapi</a> : (int * 'a -> 'b) -> 'a vector -> 'b vector
val <a id='maxLen' href="#maxLen-impl">maxLen</a> : int
val <a id='sub' href="#sub-impl">sub</a> : 'a vector * int -> 'a
val <a id='tabulate' href="#tabulate-impl">tabulate</a> : int * (int -> 'a) -> 'a vector
val <a id='update' href="#update-impl">update</a> : 'a vector * int * 'a -> 'a vector
</pre>

<a id="vector-impl"></a>
<h3><code><strong>eqtype</strong> 'a vector</code></h3>

is the type of immutable fixed-length arrays with elements of type
`'a`.

<a id="all-impl"></a>
<h3><code>all</code></h3>

`all f vec` applies `f` to each element `x` of the vector `vec`, from
left to right, until `f(x)` evaluates to `false`. It returns `false`
if such an `x` exists; otherwise it returns `true`. It is equivalent
to `not(exists (not o f) vec)`.

<a id="app-impl"></a>
<h3><code>app</code></h3>

`app f vec` applies the function `f` to the elements of a vector in
left to right order (i.e., in order of increasing indices).

It is equivalent to
<pre>List.app f (foldr (fn (a,l) => a::l) [] vec)</pre>

<a id="appi-impl"></a>
<h3><code>appi</code></h3>

`appi f vec` applies the function `f` to the elements of a vector in
left to right order (i.e., in order of increasing indices).

It is equivalent to
<pre>List.app f (foldri (fn (i,a,l) => (i,a)::l) [] vec)</pre>

<a id="collate-impl"></a>
<h3><code>collate</code></h3>

`collate f (v1, v2)` performs lexicographic comparison of the two
vectors using the given ordering `f` on elements.

<a id="concat-impl"></a>
<h3><code>concat</code></h3>

`concat l` returns the vector that is the concatenation of the vectors
in the list `l`.  Raises `Size` if the total length of these vectors
exceeds `maxLen`

<a id="exists-impl"></a>
<h3><code>exists</code></h3>

`exists f vec` applies `f` to each element `x` of the vector `vec`,
from left to right (i.e., increasing indices), until `f(x)` evaluates
to `true`; it returns `true` if such an `x` exists and `false`
otherwise.

<a id="find-impl"></a>
<h3><code>find</code></h3>

`find f vec` applies `f` to each element `x` of the vector `vec`, from
left to right, until `f(x)` evaluates to `true`. It returns `SOME (x)`
if such an `x` exists; otherwise it returns `NONE`.

<a id="findi-impl"></a>
<h3><code>findi</code></h3>

`findi f vec` applies `f` to each element `x` and element index `i` of
the vector `vec`, from left to right, until `f(i, x)` evaluates to
`true`. It returns `SOME (i, x)` if such an `x` exists; otherwise it
returns `NONE`.

<a id="foldl-impl"></a>
<h3><code>foldl</code></h3>

`foldl f init vec` folds the function `f` over all the elements of
vector `vec`, left to right, using the initial value `init`.

<a id="foldli-impl"></a>
<h3><code>foldli</code></h3>

`foldli f init vec` folds the function `f` over all the (index,
element) pairs of vector `vec`, left to right, using the initial value
`init`.

<a id="foldr-impl"></a>
<h3><code>foldr</code></h3>

`foldr f init vec` folds the function `f` over all the elements of
vector `vec`, right to left, using the initial value `init`.

<a id="foldri-impl"></a>
<h3><code>foldri</code></h3>

`foldri f init vec` folds the function `f` over all the (index,
element) pairs of vector `vec`, right to left, using the initial value
`init`.

<a id="fromList-impl"></a>
<h3><code>fromList</code></h3>

`fromList l` creates a new vector from `l`, whose length is `length l`
and with the `i`<sup>th</sup> element of `l` used as the
`i`<sup>th</sup> element of the vector. Raises `Size` if `maxLen` <
`n`.

<a id="length-impl"></a>
<h3><code>length</code></h3>

`length v` (or `v.length ()`) returns the number of elements in the vector `v`.

<a id="map-impl"></a>
<h3><code>map</code></h3>

`map f vec` applies the function `f` to the elements of the argument
vector `vec`.

It is equivalent to
<pre>fromList (List.map f (foldr (fn (a,l) => a::l) [] vec))</pre>

<a id="mapi-impl"></a>
<h3><code>mapi</code></h3>

`mapi f vec` applies the function `f` to the elements of the argument
vector `vec`, supplying the vector index and element as arguments to
each call.

It is equivalent to
<pre>fromList (List.map f (foldri (fn (i,a,l) => (i,a)::l) [] vec))</pre>

<a id="maxLen-impl"></a>
<h3><code>maxLen</code></h3>

`maxLen` returns the maximum length of vectors supported in this
implementation.

<a id="sub-impl"></a>
<h3><code>sub</code></h3>

`sub (vec, i)` (or `vec.sub i`) returns the `i`<sup>th</sup> element of vector `vec`.
Raises `Subscript` if `i` < 0 or `size vec` &le; `i`.

<a id="tabulate-impl"></a>
<h3><code>tabulate</code></h3>

`tabulate (n, f)` returns a vector of length `n` equal to `[f(0),
f(1), ..., f(n-1)]`, created from left to right. This is equivalent
to the expression

<pre>fromList (List.tabulate (n, f))</pre>

Raises `Size` if `n` < 0 or `maxLen` < `n`.

<a id="update-impl"></a>
<h3><code>update</code></h3>

`update (vec, i, x)` returns a new vector, identical to `vec`, except
the `i`<sup>th</sup> element of `vec` is set to `x`. Raises
`Subscript` if `i` < 0 or `size vec` &le; `i`.

[//]: # (end:lib/vector)
