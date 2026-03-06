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

# IEEEReal structure

[Up to index](index.md)

[//]: # (start:lib/ieee-real)
The `IEEEReal` structure provides types and constants related to
IEEE 754 floating-point arithmetic, including rounding modes,
floating-point classes, and a decimal approximation record type.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/ieee-real.html).*

## Synopsis

<pre>
type <a id='decimal_approx' href="#decimal_approx-impl">decimal_approx</a>
datatype <a id='float_class' href="#float_class-impl">float_class</a> = NAN | INF | ZERO | NORMAL | SUBNORMAL
datatype <a id='real_order' href="#real_order-impl">real_order</a> = LESS | EQUAL | GREATER | UNORDERED
datatype <a id='rounding_mode' href="#rounding_mode-impl">rounding_mode</a> = TO_NEAREST | TO_NEGINF | TO_POSINF | TO_ZERO
</pre>

<a id="decimal_approx-impl"></a>
<h3><code><strong>type</strong> decimal_approx</code></h3>

is a record type representing a decimal approximation of a
floating-point number.

<a id="float_class-impl"></a>
<h3><code><strong>datatype</strong> float_class</code></h3>

classifies a floating-point value.

<a id="real_order-impl"></a>
<h3><code><strong>datatype</strong> real_order</code></h3>

is like `order` but adds `UNORDERED` for comparisons involving NaN.

<a id="rounding_mode-impl"></a>
<h3><code><strong>datatype</strong> rounding_mode</code></h3>

specifies the IEEE 754 rounding mode for floating-point operations.

[//]: # (end:lib/ieee-real)
