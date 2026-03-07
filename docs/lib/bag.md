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

# Bag structure

[Up to index](index.md)

[//]: # (start:lib/bag)
The `Bag` structure provides operations on bags (also known as
multisets), which are unordered collections that may contain duplicate
elements. Unlike lists, bags do not maintain element order; unlike sets,
bags track multiplicity.

## Synopsis

<pre>
val <a id='nil' href="#nil-impl">nil</a> : 'a bag
val <a id='null' href="#null-impl">null</a> : 'a bag -> bool
val <a id='fromList' href="#fromList-impl">fromList</a> : 'a list -> 'a bag
val <a id='toList' href="#toList-impl">toList</a> : 'a bag -> 'a list
val <a id='length' href="#length-impl">length</a> : 'a bag -> int
val <a id='at' href="#at-impl">@</a> : 'a bag * 'a bag -> 'a bag
val <a id='hd' href="#hd-impl">hd</a> : 'a bag -> 'a
val <a id='tl' href="#tl-impl">tl</a> : 'a bag -> 'a bag
val <a id='getItem' href="#getItem-impl">getItem</a> : 'a bag -> * ('a * 'a bag) option
val <a id='take' href="#take-impl">take</a> : 'a bag * int -> 'a bag
val <a id='drop' href="#drop-impl">drop</a> : 'a bag * int -> 'a bag
val <a id='concat' href="#concat-impl">concat</a> : 'a bag bag -> 'a bag
val <a id='app' href="#app-impl">app</a> : ('a -> unit) -> 'a bag -> unit
val <a id='map' href="#map-impl">map</a> : ('a -> 'b) -> 'a bag -> 'b bag
val <a id='mapPartial' href="#mapPartial-impl">mapPartial</a> : ('a -> 'b option) -> 'a bag -> 'b bag
val <a id='find' href="#find-impl">find</a> : ('a -> bool) -> 'a bag -> 'a option
val <a id='filter' href="#filter-impl">filter</a> : ('a -> bool) -> 'a bag -> 'a bag
val <a id='partition' href="#partition-impl">partition</a> : ('a -> bool) -> 'a bag -> 'a bag * 'a bag
val <a id='fold' href="#fold-impl">fold</a> : ('a * 'b -> 'b) -> 'b -> 'a bag -> 'b
val <a id='exists' href="#exists-impl">exists</a> : ('a -> bool) -> 'a bag -> bool
val <a id='all' href="#all-impl">all</a> : ('a -> bool) -> 'a bag -> bool
val <a id='tabulate' href="#tabulate-impl">tabulate</a> : int * (int -> 'a) -> 'a bag
val <a id='collate' href="#collate-impl">collate</a> : ('a * 'a -> order) -> 'a bag * 'a bag -> order
val <a id='nth' href="#nth-impl">nth</a> : 'a bag * int -> 'a
</pre>

<a id="nil-impl"></a>
<h3><code>nil</code></h3>

`nil` is the empty bag.

<a id="null-impl"></a>
<h3><code>null</code></h3>

`null b` (or `b.null ()`) returns `true` if the bag `b` is empty.

<a id="fromList-impl"></a>
<h3><code>fromList</code></h3>

`fromList l` creates a new bag from `l`, whose length is `length l`
and whose elements are the same as those of `l`. Raises `Size` if
`maxLen` < `n`.

<a id="toList-impl"></a>
<h3><code>toList</code></h3>

`toList b` (or `b.toList ()`) creates a new bag from `b`, whose length is `length b` and
whose elements are the same as those of `b`. Raises `Size` if `maxLen`
< `n`.

<a id="length-impl"></a>
<h3><code>length</code></h3>

`length b` (or `b.length ()`) returns the number of elements in the bag `b`.

<a id="at-impl"></a>
<h3><code>@</code></h3>

`@ (b1, b2)` returns the bag that is the concatenation of `b1` and `b2`.

<a id="hd-impl"></a>
<h3><code>hd</code></h3>

`hd b` (or `b.hd ()`) returns an arbitrary element of bag `b`. Raises `Empty` if `b`
is `nil`.

<a id="tl-impl"></a>
<h3><code>tl</code></h3>

`tl b` (or `b.tl ()`) returns all but one arbitrary element of bag `b`. Raises
`Empty` if `b` is `nil`.

<a id="getItem-impl"></a>
<h3><code>getItem</code></h3>

`getItem b` (or `b.getItem ()`) returns `NONE` if the bag `b` is empty, and `SOME (hd b,
tl b)` otherwise (applying `hd` and `tl` simultaneously so that they
choose/remove the same arbitrary element).

<a id="take-impl"></a>
<h3><code>take</code></h3>

`take (b, i)` (or `b.take i`) returns an arbitrary `i` elements of the bag `b`. Raises
`Subscript` if `i` < 0 or `i` > `length l`. We have `take(b, length
b)` = `b`.

<a id="drop-impl"></a>
<h3><code>drop</code></h3>

`drop (b, i)` (or `b.drop i`) returns what is left after dropping an arbitrary `i`
elements of the bag `b`. Raises `Subscript` if `i` < 0 or `i` >
`length l`.

We have `drop(b, length b)` = `[]`.

<a id="concat-impl"></a>
<h3><code>concat</code></h3>

`concat b` returns the bag that is the concatenation of all the bags in `b`.

<a id="app-impl"></a>
<h3><code>app</code></h3>

`app f b` applies `f` to the elements of `b`.

<a id="map-impl"></a>
<h3><code>map</code></h3>

`map f b` applies `f` to each element of `b`, returning the bag of
results. This is equivalent to:

<pre>fromList (List.map f (foldr (fn (a,l) => a::l) [] b))</pre>

<a id="mapPartial-impl"></a>
<h3><code>mapPartial</code></h3>

`mapPartial f b` applies `f` to each element of `b`, returning a bag
of results, with `SOME` stripped, where `f` was defined. `f` is not
defined for an element of `b` if `f` applied to the element returns
`NONE`. The above expression is equivalent to:

<pre>((map valOf) o (filter isSome) o (map f)) b</pre>

<a id="find-impl"></a>
<h3><code>find</code></h3>

`find f b` applies `f` to each element `x` of the bag `b`, in
arbitrary order, until `f x` evaluates to `true`. It returns `SOME
(x)` if such an `x` exists; otherwise it returns `NONE`.

<a id="filter-impl"></a>
<h3><code>filter</code></h3>

`filter f b` applies `f` to each element `x` of `b` and returns the
bag of those `x` for which `f x` evaluated to `true`.

<a id="partition-impl"></a>
<h3><code>partition</code></h3>

`partition f b` applies `f` to each element `x` of `b`, in arbitrary
order, and returns a pair `(pos, neg)` where `pos` is the bag of those
`x` for which `f x` evaluated to `true`, and `neg` is the bag of those
for which `f x` evaluated to `false`.

<a id="fold-impl"></a>
<h3><code>fold</code></h3>

`fold f init (bag \[x1, x2, ..., xn\])` returns `f(xn, ... , f(x2,
f(x1, init))...)` (for some arbitrary reordering of the elements `xi`)
or `init` if the bag is empty.

<a id="exists-impl"></a>
<h3><code>exists</code></h3>

`exists f b` applies `f` to each element `x` of the bag `b`, in
arbitrary order, until `f(x)` evaluates to `true`; it returns `true`
if such an `x` exists and `false` otherwise.

<a id="all-impl"></a>
<h3><code>all</code></h3>

`all f b` applies `f` to each element `x` of the bag `b`, in arbitrary
order, until `f(x)` evaluates to `false`; it returns `false` if such
an `x` exists and `true` otherwise. It is equivalent to `not(exists
(not o f) b))`.

<a id="tabulate-impl"></a>
<h3><code>tabulate</code></h3>

`tabulate (n, f)` returns a bag of length `n` equal to `[f(0), f(1),
..., f(n-1)]`. This is equivalent to the expression:

<pre>fromList (List.tabulate (n, f))</pre>

Raises `Size` if `n` < 0.

<a id="collate-impl"></a>
<h3><code>collate</code></h3>

`collate f (l1, l2)` performs lexicographic comparison of the two bags
using the given ordering `f` on the bag elements.

<a id="nth-impl"></a>
<h3><code>nth</code></h3>

`nth (b, i)` (or `b.nth i`) returns the `i`th element of the bag `b`, counting from 0. Raises
`Subscript` if `i` < 0 or `i` >= `length b`. We have `nth(b,0) = hd
b`, ignoring exceptions.

[//]: # (end:lib/bag)
