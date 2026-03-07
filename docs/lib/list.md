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

# List structure

[Up to index](index.md)

[//]: # (start:lib/list)
The `List` structure provides the `list` type and a comprehensive
set of operations for constructing, examining, and transforming
singly-linked lists. Many operations are provided in both left-to-right
and right-to-left variants.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/list.html).*

## Synopsis

<pre>
datatype 'a <a id='list' href="#list-impl">list</a> = nil | :: of 'a * 'a list

exception <a id='Empty' href="#Empty-impl">Empty</a>

val <a id='nil' href="#nil-impl">nil</a> : 'a list
val <a id='null' href="#null-impl">null</a> : 'a list -> bool
val <a id='length' href="#length-impl">length</a> : 'a list -> int
val <a id='at' href="#at-impl">@</a> : 'a list * 'a list -> 'a list
val <a id='at-fn' href="#at-fn-impl">at</a> : 'a list * 'a list -> 'a list
val <a id='hd' href="#hd-impl">hd</a> : 'a list -> 'a
val <a id='tl' href="#tl-impl">tl</a> : 'a list -> 'a list
val <a id='last' href="#last-impl">last</a> : 'a list -> 'a
val <a id='getItem' href="#getItem-impl">getItem</a> : 'a list -> * ('a * 'a list) option
val <a id='nth' href="#nth-impl">nth</a> : 'a list * int -> 'a
val <a id='take' href="#take-impl">take</a> : 'a list * int -> 'a list
val <a id='drop' href="#drop-impl">drop</a> : 'a list * int -> 'a list
val <a id='rev' href="#rev-impl">rev</a> : 'a list -> 'a list
val <a id='concat' href="#concat-impl">concat</a> : 'a list list -> 'a list
val <a id='revAppend' href="#revAppend-impl">revAppend</a> : 'a list * 'a list -> 'a list
val <a id='app' href="#app-impl">app</a> : ('a -> unit) -> 'a list -> unit
val <a id='map' href="#map-impl">map</a> : ('a -> 'b) -> 'a list -> 'b list
val <a id='mapi' href="#mapi-impl">mapi</a> : (int * 'a -> 'b) -> 'a list -> 'b list
val <a id='mapPartial' href="#mapPartial-impl">mapPartial</a> : ('a -> 'b option) -> 'a list -> 'b list
val <a id='find' href="#find-impl">find</a> : ('a -> bool) -> 'a list -> 'a option
val <a id='filter' href="#filter-impl">filter</a> : ('a -> bool) -> 'a list -> 'a list
val <a id='partition' href="#partition-impl">partition</a> : ('a -> bool) -> 'a list -> 'a list * 'a list
val <a id='foldl' href="#foldl-impl">foldl</a> : ('a * 'b -> 'b) -> 'b -> 'a list -> 'b
val <a id='foldr' href="#foldr-impl">foldr</a> : ('a * 'b -> 'b) -> 'b -> 'a list -> 'b
val <a id='exists' href="#exists-impl">exists</a> : ('a -> bool) -> 'a list -> bool
val <a id='all' href="#all-impl">all</a> : ('a -> bool) -> 'a list -> bool
val <a id='tabulate' href="#tabulate-impl">tabulate</a> : int * (int -> 'a) -> 'a list
val <a id='collate' href="#collate-impl">collate</a> : ('a * 'a -> order) -> 'a list * 'a list -> order
(* Morel extensions *)
val <a id='except' href="#except-impl">except</a> : 'a list list -> 'a list
val <a id='intersect' href="#intersect-impl">intersect</a> : 'a list list -> 'a list
</pre>

<a id="list-impl"></a>
<h3><code><strong>datatype</strong> 'a list</code></h3>

is the type of polymorphic singly-linked lists.

<a id="Empty-impl"></a>
<h3><code><strong>exception</strong> Empty</code></h3>

is raised by operations that require a non-empty list when given an
empty list.

<a id="nil-impl"></a>
<h3><code>nil</code></h3>

`nil` is the empty list.

<a id="null-impl"></a>
<h3><code>null</code></h3>

`null l` (or `l.null ()`) returns `true` if the list `l` is empty.

<a id="length-impl"></a>
<h3><code>length</code></h3>

`length l` (or `l.length ()`) returns the number of elements in the list `l`.

<a id="at-impl"></a>
<h3><code>@</code></h3>

`l1 @ l2` returns the list that is the concatenation of `l1` and `l2`.

<a id="at-fn-impl"></a>
<h3><code>at</code></h3>

`at (l1, l2)` is equivalent to "l1 @ l2".

<a id="hd-impl"></a>
<h3><code>hd</code></h3>

`hd l` (or `l.hd ()`) returns the first element of `l`. Raises `Empty` if `l` is
`nil`.

<a id="tl-impl"></a>
<h3><code>tl</code></h3>

`tl l` (or `l.tl ()`) returns all but the first element of `l`. Raises `Empty` if `l`
is `nil`.

<a id="last-impl"></a>
<h3><code>last</code></h3>

`last l` (or `l.last ()`) returns the last element of `l`. Raises `Empty` if `l` is
`nil`.

<a id="getItem-impl"></a>
<h3><code>getItem</code></h3>

`getItem l` (or `l.getItem ()`) returns `NONE` if the `list` is empty, and `SOME (hd l, tl
l)` otherwise. This function is particularly useful for creating value
readers from lists of characters. For example, `Int.scan StringCvt.DEC
getItem` has the type `(int, char list) StringCvt.reader` and can be
used to scan decimal integers from lists of characters.

<a id="nth-impl"></a>
<h3><code>nth</code></h3>

`nth (l, i)` (or `l.nth i`) returns the `i`(th) element of the list `l`, counting
from 0. Raises `Subscript` if `i` < 0 or `i` &ge; `length l`. We have
`nth(l, 0)` = `hd l`, ignoring exceptions.

<a id="take-impl"></a>
<h3><code>take</code></h3>

`take (l, i)` (or `l.take i`) returns the first `i` elements of the list `l`. Raises
`Subscript` if `i` < 0 or `i` > `length l`. We have `take(l, length
l)` = `l`.

<a id="drop-impl"></a>
<h3><code>drop</code></h3>

`drop (l, i)` (or `l.drop i`) returns what is left after dropping the first `i`
elements of the list `l`. Raises `Subscript` if `i` < 0 or `i` >
`length l`.

It holds that `take(l, i) @ drop(l, i)` = `l` when 0 &le;
`i` &le; `length l`. We also have `drop(l, length l)` = `[]`.

<a id="rev-impl"></a>
<h3><code>rev</code></h3>

`rev l` (or `l.rev ()`) returns a list consisting of `l`'s elements in reverse order.

<a id="concat-impl"></a>
<h3><code>concat</code></h3>

`concat l` returns the list that is the concatenation of all the lists
in `l` in order. `concat [l1, l2, ... ln]` = `l1 @ l2 @ ... @ ln`

<a id="revAppend-impl"></a>
<h3><code>revAppend</code></h3>

`revAppend (l1, l2)` (or `l1.revAppend l2`) returns `(rev l1) @ l2`.

<a id="app-impl"></a>
<h3><code>app</code></h3>

`app f l` applies `f` to the elements of `l`, from left to right.

<a id="map-impl"></a>
<h3><code>map</code></h3>

`map f l` applies `f` to each element of `l` from left to right,
returning the list of results.

<a id="mapi-impl"></a>
<h3><code>mapi</code></h3>

`mapi f l` applies the function `f` to the elements of the argument
list `l`, supplying the list index and element as arguments to each
call.

<a id="mapPartial-impl"></a>
<h3><code>mapPartial</code></h3>

`mapPartial f l` applies `f` to each element of `l` from left to
right, returning a list of results, with `SOME` stripped, where `f`
was defined. `f` is not defined for an element of `l` if `f` applied
to the element returns `NONE`. The above expression is equivalent to:

<pre>((map valOf) o (filter isSome) o (map f)) b</pre>

<a id="find-impl"></a>
<h3><code>find</code></h3>

`find f l` applies `f` to each element `x` of the list `l`, from left
to right, until `f x` evaluates to `true`. It returns `SOME (x)` if
such an `x` exists; otherwise it returns `NONE`.

<a id="filter-impl"></a>
<h3><code>filter</code></h3>

`filter f l` applies `f` to each element `x` of `l`, from left to
right, and returns the list of those `x` for which `f x` evaluated to
`true`, in the same order as they occurred in the argument list.

<a id="partition-impl"></a>
<h3><code>partition</code></h3>

`partition f l` applies `f` to each element `x` of `l`, from left to
right, and returns a pair `(pos, neg)` where `pos` is the list of
those `x` for which `f x` evaluated to `true`, and `neg` is the list
of those for which `f x` evaluated to `false`. The elements of `pos`
and `neg` retain the same relative order they possessed in `l`.

<a id="foldl-impl"></a>
<h3><code>foldl</code></h3>

`foldl f init \[x1, x2, ..., xn\]` returns `f(xn, ... , f(x2, f(x1,
init))...)` or `init` if the list is empty.

<a id="foldr-impl"></a>
<h3><code>foldr</code></h3>

`foldr f init \[x1, x2, ..., xn\]` returns `f(x1, f(x2, ..., f(xn,
init)...))` or `init` if the list is empty.

<a id="exists-impl"></a>
<h3><code>exists</code></h3>

`exists f l` applies `f` to each element `x` of the list `l`, from
left to right, until `f(x)` evaluates to `true`; it returns `true` if
such an `x` exists and `false` otherwise.

<a id="all-impl"></a>
<h3><code>all</code></h3>

`all f l` applies `f` to each element `x` of the list `l`, from left
to right, until `f(x)` evaluates to `false`; it returns `false` if
such an `x` exists and `true` otherwise. It is equivalent to
`not(exists (not o f) l))`.

<a id="tabulate-impl"></a>
<h3><code>tabulate</code></h3>

`tabulate (n, f)` returns a list of length `n` equal to `[f(0), f(1),
..., f(n-1)]`, created from left to right. Raises `Size` if `n` < 0.

<a id="collate-impl"></a>
<h3><code>collate</code></h3>

`collate f (l1, l2)` performs lexicographic comparison of the two
lists using the given ordering `f` on the list elements.

<a id="except-impl"></a>
<h3><code>except</code></h3>

`except l` returns the list that is the concatenation of all the lists
in `l` in order. `concat [l1, l2, ... ln]` = `l1 @ l2 @ ... @ ln`

<a id="intersect-impl"></a>
<h3><code>intersect</code></h3>

`intersect l` returns the list that is the concatenation of all the
lists in `l` in order. `concat [l1, l2, ... ln]` = `l1 @ l2 @ ... @
ln`

[//]: # (end:lib/list)
