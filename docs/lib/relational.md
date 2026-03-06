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

# Relational structure

[Up to index](index.md)

[//]: # (start:lib/relational)
The `Relational` structure provides aggregation, comparison, and set
operations that are used in Morel `from` expressions. These functions
extend Standard ML with relational-algebra capabilities.

## Synopsis

<pre>
datatype 'a <a id='descending' href="#descending-impl">descending</a> = DESC of 'a

val <a id='count' href="#count-impl">count</a> : int list -> int
val <a id='empty' href="#empty-impl">empty</a> : 'a list -> bool
val <a id='iterate' href="#iterate-impl">iterate</a> : 'a bag -> ('a bag * 'a bag -> 'a bag) -> 'a bag
val <a id='max' href="#max-impl">max</a> : 'a list -> 'a
val <a id='min' href="#min-impl">min</a> : 'a list -> 'a
val <a id='nonEmpty' href="#nonEmpty-impl">nonEmpty</a> : 'a list -> bool
val <a id='only' href="#only-impl">only</a> : 'a list -> 'a
val <a id='elem' href="#elem-impl">elem</a> : 'a * 'a bag -> bool, 'a * 'a list -> bool
val <a id='notelem' href="#notelem-impl">notelem</a> : 'a * 'a bag -> bool, 'a * 'a list -> bool
val <a id='sum' href="#sum-impl">sum</a> : int list -> int
val <a id='compare' href="#compare-impl">compare</a> : 'a * 'a -> order
</pre>

<a id="descending-impl"></a>
<h3><code><strong>datatype</strong> 'a descending</code></h3>

wraps a value so that it sorts in descending order when used with
`Relational.compare`.

<a id="count-impl"></a>
<h3><code>count</code></h3>

`count list` returns the number of elements in `list`. Often used with
`group`, for example `from e in emps group e.deptno compute countId =
count`.

<a id="empty-impl"></a>
<h3><code>empty</code></h3>

`empty list` returns whether the list is empty, for example `from d in
depts where empty (from e where e.deptno = d.deptno)`.

<a id="iterate-impl"></a>
<h3><code>iterate</code></h3>

`iterate initialList listUpdate` computes a fixed point, starting with `initialList` and calling
`listUpdate prevList newList` each iteration, terminating the
iteration when it returns `newList`.

<a id="max-impl"></a>
<h3><code>max</code></h3>

`max list` returns the greatest element of `list`. Often used with
`group`, for example `from e in emps group e.deptno compute maxId =
max of e.id`.

<a id="min-impl"></a>
<h3><code>min</code></h3>

`min list` returns the least element of `list`. Often used with
`group`, for example `from e in emps group e.deptno compute minId =
min of e.id`.

<a id="nonEmpty-impl"></a>
<h3><code>nonEmpty</code></h3>

`nonEmpty list` returns whether the list has at least one element, for
example `from d in depts where nonEmpty (from e where e.deptno =
d.deptno)`.

<a id="only-impl"></a>
<h3><code>only</code></h3>

`only list` returns the sole element of list, for example `from e in
emps yield only (from d where d.deptno = e.deptno)`.

<a id="elem-impl"></a>
<h3><code>elem</code></h3>

`e elem collection` returns whether `e` is a member of `collection`.

<a id="notelem-impl"></a>
<h3><code>notelem</code></h3>

`e notelem collection` returns whether `e` is not a member of
`collection`.

<a id="sum-impl"></a>
<h3><code>sum</code></h3>

`sum list` returns the sum of the elements of `list`. Often used with
`group`, for example `from e in emps group e.deptno compute sumId =
sum of e.id`.

<a id="compare-impl"></a>
<h3><code>compare</code></h3>

`compare (x, y)` returns `LESS`, `EQUAL`, or `GREATER` according to
whether its first argument is less than, equal to, or greater than the
second.

Comparisons are based on the structure of the type `α`.
Primitive types are compared using their natural order;
Option types compare with NONE last;
Tuple types compare lexicographically;
Record types compare lexicographically, with the fields
compared in alphabetical order;
List values compare lexicographically;
Bag values compare lexicographically, the elements appearing
in an order that is arbitrary but is consistent for each
particular value.

[//]: # (end:lib/relational)
