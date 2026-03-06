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

# Fn structure

[Up to index](index.md)

[//]: # (start:lib/fn)
The `Fn` structure provides combinators for working with function values,
including application, composition, currying, and fixpoint operators.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/fn.html).*

## Synopsis

<pre>
val <a id='id' href="#id-impl">id</a> : 'a -> 'a
val <a id='const' href="#const-impl">const</a> : 'a -> 'b -> 'a
val <a id='apply' href="#apply-impl">apply</a> : ('a -> 'b) * 'a -> 'b
val <a id='o' href="#o-impl">o</a> : ('b -> 'c) * ('a -> 'b) -> 'a -> 'c
val <a id='curry' href="#curry-impl">curry</a> : ('a * 'b -> 'c) -> 'a -> 'b -> 'c
val <a id='uncurry' href="#uncurry-impl">uncurry</a> : ('a -> 'b -> 'c) -> 'a * 'b -> 'c
val <a id='flip' href="#flip-impl">flip</a> : ('a * 'b -> 'c) -> 'b * 'a -> 'c
val <a id='repeat' href="#repeat-impl">repeat</a> : int -> ('a -> 'a) -> 'a -> 'a
val <a id='equal' href="#equal-impl">equal</a> : 'a -> 'a -> bool
val <a id='notEqual' href="#notEqual-impl">notEqual</a> : 'a -> 'a -> bool
</pre>

<a id="id-impl"></a>
<h3><code>id</code></h3>

`id x` returns the value `x`. (`id` is the polymorphic identity function.)

<a id="const-impl"></a>
<h3><code>const</code></h3>

`const x y` returns the value `x`.

<a id="apply-impl"></a>
<h3><code>apply</code></h3>

`apply (f, x)` applies the function `f` to `x`. Thus, it is equivalent to `f x`.

<a id="o-impl"></a>
<h3><code>o</code></h3>

`f o g` is the function composition of `f` and `g`. Thus, `(f o g) a`
is equivalent to `f (g a)`. This function is the same as the global
`o` operator and is also part of the `General` structure.

<a id="curry-impl"></a>
<h3><code>curry</code></h3>

`curry f x y` is equivalent to `f (x, y)`; i.e., `curry f` transforms
the binary function `f` into curried form.

<a id="uncurry-impl"></a>
<h3><code>uncurry</code></h3>

`ucurry f (x, y)` is equivalent to `f x y`; i.e., `uncurry f` transforms the curried
function `f` into a binary function. This function is the inverse of
`curry`.

<a id="flip-impl"></a>
<h3><code>flip</code></h3>

`flip f (x, y)` is equivalent to `f (y, x)`; i.e., `flip f` flips the argument order
of the binary function `f`.

<a id="repeat-impl"></a>
<h3><code>repeat</code></h3>

`repeat n f` returns the `n`-fold composition of `f`. If `n` is zero, then
`repeat n f` returns the identity function. If `n` is negative, then
it raises the exception `Domain`.

<a id="equal-impl"></a>
<h3><code>equal</code></h3>

`equal a b` returns whether `a` is equal to `b`. It is a curried version of the
polymorphic equality function (`=`).

<a id="notEqual-impl"></a>
<h3><code>notEqual</code></h3>

`notEqual a b` returns whether `a` is not equal to `b`. It is a curried version of
the polymorphic inequality function (`<>`).

[//]: # (end:lib/fn)
