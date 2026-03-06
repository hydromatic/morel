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

# General structure

[Up to index](index.md)

[//]: # (start:lib/general)
The `General` structure provides the fundamental types `unit`, `exn`,
and `order`, the standard exceptions raised by the runtime, and a few
utility functions. Its contents are available without qualification in
every Morel program.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/general.html).*

## Synopsis

<pre>
type <a id='exn' href="#exn-impl">exn</a> = exn
datatype <a id='order' href="#order-impl">order</a> = LESS | EQUAL | GREATER
eqtype <a id='unit' href="#unit-impl">unit</a>

exception <a id='Bind' href="#Bind-impl">Bind</a>
exception <a id='Chr' href="#Chr-impl">Chr</a>
exception <a id='Div' href="#Div-impl">Div</a>
exception <a id='Domain' href="#Domain-impl">Domain</a>
exception <a id='Fail' href="#Fail-impl">Fail</a> of string
exception <a id='Match' href="#Match-impl">Match</a>
exception <a id='Overflow' href="#Overflow-impl">Overflow</a>
exception <a id='Size' href="#Size-impl">Size</a>
exception <a id='Span' href="#Span-impl">Span</a>
exception <a id='Subscript' href="#Subscript-impl">Subscript</a>

val <a id='exnName' href="#exnName-impl">exnName</a> : exn -> string
val <a id='exnMessage' href="#exnMessage-impl">exnMessage</a> : exn -> string
val <a id='o' href="#o-impl">o</a> : ('b -> 'c) ('a -> 'b) -> 'a -> 'c
val <a id='before' href="#before-impl">before</a> : 'a * unit -> 'a
val <a id='ignore' href="#ignore-impl">ignore</a> : 'a -> unit
</pre>

<a id="exn-impl"></a>
<h3><code><strong>type</strong> exn</code></h3>

is the type of exceptions. Every exception constructor creates a value
of this type, and the `raise` and `handle` constructs operate on it.

<a id="order-impl"></a>
<h3><code><strong>datatype</strong> order</code></h3>

is the type for comparing two values. `LESS`, `EQUAL`, or `GREATER`
indicates the result of the comparison.

<a id="unit-impl"></a>
<h3><code><strong>eqtype</strong> unit</code></h3>

is the type that contains the single value `()`. It is used as the
result type of functions called for side effects.

<a id="Bind-impl"></a>
<h3><code><strong>exception</strong> Bind</code></h3>

is raised when pattern matching fails in a `val` binding.

<a id="Chr-impl"></a>
<h3><code><strong>exception</strong> Chr</code></h3>

is raised by `Char.chr` when given an integer outside the valid range.

<a id="Div-impl"></a>
<h3><code><strong>exception</strong> Div</code></h3>

is raised on integer division by zero.

<a id="Domain-impl"></a>
<h3><code><strong>exception</strong> Domain</code></h3>

is raised when a function is applied outside its domain.

<a id="Fail-impl"></a>
<h3><code><strong>exception</strong> Fail</code></h3>

is a general-purpose exception carrying a descriptive message string.

<a id="Match-impl"></a>
<h3><code><strong>exception</strong> Match</code></h3>

is raised when pattern matching fails in a `case` expression or
function application.

<a id="Overflow-impl"></a>
<h3><code><strong>exception</strong> Overflow</code></h3>

is raised when an integer arithmetic result is too large to represent.

<a id="Size-impl"></a>
<h3><code><strong>exception</strong> Size</code></h3>

is raised when a size argument is negative or exceeds the maximum
allowed.

<a id="Span-impl"></a>
<h3><code><strong>exception</strong> Span</code></h3>

is raised when an invalid source span is supplied.

<a id="Subscript-impl"></a>
<h3><code><strong>exception</strong> Subscript</code></h3>

is raised when a sequence index is out of bounds.

<a id="exnName-impl"></a>
<h3><code>exnName</code></h3>

`exnName ex` returns a name for the exception `ex`. The name returned may be that
of any exception constructor aliasing with `ex`. For instance,

<pre>let exception E1; exception E2 = E1 in exnName E2 end</pre>

might evaluate to "E1" or "E2".

<a id="exnMessage-impl"></a>
<h3><code>exnMessage</code></h3>

`exnMessage ex` returns a message corresponding to exception `ex`. The precise format
of the message may vary between implementations and locales, but will
at least contain the string `exnName ex`.

**Example:**

<pre>exnMessage Div = "Div"
exnMessage (OS.SysErr ("No such file", NONE)) =
  "OS.SysErr "No such file""</pre>

<a id="o-impl"></a>
<h3><code>o</code></h3>

`f o g` is the function composition of `f` and `g`. Thus, `(f o g) a`
is equivalent to `f (g a)`.

<a id="before-impl"></a>
<h3><code>before</code></h3>

`a before b` returns `a`. It provides a notational shorthand for evaluating `a`,
then `b`, before returning the value of `a`.

<a id="ignore-impl"></a>
<h3><code>ignore</code></h3>

`ignore x` always returns `unit`. The function evaluates its argument
but throws away the value.

[//]: # (end:lib/general)
