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

# ListPair structure

[Up to index](index.md)

[//]: # (start:lib/list-pair)
The `ListPair` structure provides operations for working with two lists
simultaneously. Operations whose names do not end in `Eq` silently
ignore excess elements of the longer list; those ending in `Eq` raise
`UnequalLengths` when the lists differ in length.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/list-pair.html).*

## Synopsis

<pre>
exception <a id='UnequalLengths' href="#UnequalLengths-impl">UnequalLengths</a>

val <a id='zip' href="#zip-impl">zip</a> : 'a list * 'b list -> ('a * 'b) list
val <a id='zipEq' href="#zipEq-impl">zipEq</a> : 'a list * 'b list -> ('a * 'b) list
val <a id='unzip' href="#unzip-impl">unzip</a> : ('a * 'b) list -> 'a list * 'b list
val <a id='app' href="#app-impl">app</a> : ('a * 'b -> unit) -> 'a list * 'b list -> unit
val <a id='appEq' href="#appEq-impl">appEq</a> : ('a * 'b -> unit) -> 'a list * 'b list -> unit
val <a id='map' href="#map-impl">map</a> : ('a * 'b -> 'c) -> 'a list * 'b list -> 'c list
val <a id='mapEq' href="#mapEq-impl">mapEq</a> : ('a * 'b -> 'c) -> 'a list * 'b list -> 'c list
val <a id='foldl' href="#foldl-impl">foldl</a> : ('a * 'b * 'c -> 'c) -> 'c -> 'a list * 'b list -> 'c
val <a id='foldr' href="#foldr-impl">foldr</a> : ('a * 'b * 'c -> 'c) -> 'c -> 'a list * 'b list -> 'c
val <a id='foldlEq' href="#foldlEq-impl">foldlEq</a> : ('a * 'b * 'c -> 'c) -> 'c -> 'a list * 'b list -> 'c
val <a id='foldrEq' href="#foldrEq-impl">foldrEq</a> : ('a * 'b * 'c -> 'c) -> 'c -> 'a list * 'b list -> 'c
val <a id='all' href="#all-impl">all</a> : ('a * 'b -> bool) -> 'a list * 'b list -> bool
val <a id='exists' href="#exists-impl">exists</a> : ('a * 'b -> bool) -> 'a list * 'b list -> bool
val <a id='allEq' href="#allEq-impl">allEq</a> : ('a * 'b -> bool) -> 'a list * 'b list -> bool
</pre>

<a id="UnequalLengths-impl"></a>
<h3><code><strong>exception</strong> UnequalLengths</code></h3>

is raised by those functions that require arguments
of identical length.

<a id="zip-impl"></a>
<h3><code>zip</code></h3>

`zip (l1, l2)` combines the two lists *l1* and *l2* into a list of
pairs, with the first element of each list comprising the first
element of the result, the second elements comprising the second
element of the result, and so on.  If the lists are of unequal
lengths, `zip` ignores the excess elements from the tail of the longer
one.

<a id="zipEq-impl"></a>
<h3><code>zipEq</code></h3>

`zipEq (l1, l2)` combines the two lists *l1* and *l2* into a list of
pairs, with the first element of each list comprising the first
element of the result, the second elements comprising the second
element of the result, and so on.  If the lists are of unequal
lengths, `zipEq` raises the exception `UnequalLengths`.

<a id="unzip-impl"></a>
<h3><code>unzip</code></h3>

`unzip l` returns a pair of lists formed by splitting the elements of
*l*. This is the inverse of `zip` for equal length lists.

<a id="app-impl"></a>
<h3><code>app</code></h3>

`app f (l1, l2)` applies the function *f* to the list of pairs of
elements generated from left to right from the lists *l1* and *l2*. If
the lists are of unequal lengths, ignores the excess elements from the
tail of the longer one. It is equivalent to:

<pre>List.app f (zip (l1, l2))</pre>

ignoring possible side effects of the function *f*.

<a id="appEq-impl"></a>
<h3><code>appEq</code></h3>

`appEq f (l1, l2)` applies the function *f* to the list of pairs of
elements generated from left to right from the lists *l1* and *l2*. If
the lists are of unequal lengths, raises `UnequalLengths`. It is
equivalent to:

<pre>List.app f (zipEq (l1, l2))</pre>

ignoring possible side effects of the function *f*.

<a id="map-impl"></a>
<h3><code>map</code></h3>

`map f (l1, l2)` maps the function *f* over the list of pairs of
elements generated from left to right from the lists *l1* and *l2*,
returning the list of results.  If the lists are of unequal lengths,
ignores the excess elements from the tail of the longer one.  It is
equivalent to:

<pre>List.map f (zip (l1, l2))</pre>

ignoring possible side effects of the function *f*.

<a id="mapEq-impl"></a>
<h3><code>mapEq</code></h3>

`mapEq f (l1, l2)` maps the function *f* over the list of pairs of
elements generated from left to right from the lists *l1* and *l2*,
returning the list of results.  If the lists are of unequal lengths,
raises `UnequalLengths`.  It is equivalent to:

<pre>List.map f (zipEq (l1, l2))</pre>

ignoring possible side effects of the function *f*.

<a id="foldl-impl"></a>
<h3><code>foldl</code></h3>

`foldl f init (l1, l2)` returns the result of folding the function *f*
in the specified direction over the pair of lists *l1* and *l2*
starting with the value *init*.  It is equivalent to:

<pre>List.foldl f' init (zip (l1, l2))</pre>

where *f'* is `fn ((a,b),c) => f(a,b,c)` and ignoring possible
side effects of the function *f*.

<a id="foldr-impl"></a>
<h3><code>foldr</code></h3>

`foldr f init (l1, l2)` returns the result of folding the function *f*
in the specified direction over the pair of lists *l1* and *l2*
starting with the value *init*.  It is equivalent to:

<pre>List.foldr f' init (zip (l1, l2))</pre>

where *f'* is `fn ((a,b),c) => f(a,b,c)` and ignoring possible
side effects of the function *f*.

<a id="foldlEq-impl"></a>
<h3><code>foldlEq</code></h3>

`foldlEq f init (l1, l2)` returns the result of folding the function
*f* in the specified direction over the pair of lists *l1* and *l2*
starting with the value *init*.  It is equivalent to:

<pre>List.foldl f' init (zipEq (l1, l2))</pre>

where *f'* is `fn ((a,b),c) => f(a,b,c)` and ignoring possible
side effects of the function *f*.

<a id="foldrEq-impl"></a>
<h3><code>foldrEq</code></h3>

`foldrEq f init (l1, l2)` returns the result of folding the function
*f* in the specified direction over the pair of lists *l1* and *l2*
starting with the value *init*.  It is equivalent to:

<pre>List.foldr f' init (zipEq (l1, l2))</pre>

where *f'* is `fn ((a,b),c) => f(a,b,c)` and ignoring possible
side effects of the function *f*.

<a id="all-impl"></a>
<h3><code>all</code></h3>

`all f (l1, l2)` provides short-circuit testing of a predicate over a pair of lists.

It is equivalent to:
<pre>List.all f (zip (l1, l2))</pre>

<a id="exists-impl"></a>
<h3><code>exists</code></h3>

`exists f (l1, l2)` provides short-circuit testing of a predicate over a pair of lists.

It is equivalent to:
<pre>List.exists f (zip (l1, l2))</pre>

<a id="allEq-impl"></a>
<h3><code>allEq</code></h3>

`allEq f (l1, l2)` returns `true` if *l1* and *l2* have equal length
and all pairs of elements satisfy the predicate *f*. That is, the
expression is equivalent to:

<pre>
(List.length l1 = List.length l2) andalso
(List.all f (zip (l1, l2)))
</pre>

This function does not appear to have any nice algebraic relation
with the other functions, but it is included as providing a useful
notion of equality, analogous to the notion of equality of lists over
equality types.

**Implementation note:**

The implementation is simple:

<pre>
fun allEq p ([], []) = true
  | allEq p (x::xs, y::ys) = p(x,y) andalso allEq p (xs,ys)
  | allEq _ _ = false
</pre>

[//]: # (end:lib/list-pair)
