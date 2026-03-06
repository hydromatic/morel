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

# Math structure

[Up to index](index.md)

[//]: # (start:lib/math)
The `Math` structure provides standard mathematical functions operating
on `real` values, including trigonometric, logarithmic, exponential,
and rounding operations, along with mathematical constants.

*Specified by the [Standard ML Basis Library](https://smlfamily.github.io/Basis/math.html).*

## Synopsis

<pre>
val <a id='acos' href="#acos-impl">acos</a> : real -> real
val <a id='asin' href="#asin-impl">asin</a> : real -> real
val <a id='atan' href="#atan-impl">atan</a> : real -> real
val <a id='atan2' href="#atan2-impl">atan2</a> : real * real -> real
val <a id='cos' href="#cos-impl">cos</a> : real -> real
val <a id='cosh' href="#cosh-impl">cosh</a> : real -> real
val <a id='e' href="#e-impl">e</a> : real
val <a id='exp' href="#exp-impl">exp</a> : real -> real
val <a id='ln' href="#ln-impl">ln</a> : real -> real
val <a id='log10' href="#log10-impl">log10</a> : real -> real
val <a id='pi' href="#pi-impl">pi</a> : real
val <a id='pow' href="#pow-impl">pow</a> : real * real -> real
val <a id='sin' href="#sin-impl">sin</a> : real -> real
val <a id='sinh' href="#sinh-impl">sinh</a> : real -> real
val <a id='sqrt' href="#sqrt-impl">sqrt</a> : real -> real
val <a id='tan' href="#tan-impl">tan</a> : real -> real
val <a id='tanh' href="#tanh-impl">tanh</a> : real -> real
</pre>

<a id="acos-impl"></a>
<h3><code>acos</code></h3>

`acos x` returns the arc cosine of `x`. `acos` is the inverse of
`cos`. Its result is guaranteed to be in the closed interval \[0,
pi\]. If the magnitude of `x` exceeds 1.0, returns NaN.

<a id="asin-impl"></a>
<h3><code>asin</code></h3>

`asin x` returns the arc sine of `x`. `asin` is the inverse of
`sin`. Its result is guaranteed to be in the closed interval \[-pi / 2,
pi / 2\]. If the magnitude of `x` exceeds 1.0, returns NaN.

<a id="atan-impl"></a>
<h3><code>atan</code></h3>

`atan x` returns the arc tangent of `x`. `atan` is the inverse of
`tan`. For finite arguments, the result is guaranteed to be in the
open interval (-pi / 2, pi / 2). If `x` is +infinity, it returns pi /
2; if `x` is -infinity, it returns -pi / 2.

<a id="atan2-impl"></a>
<h3><code>atan2</code></h3>

`atan2 (y, x)` returns the arc tangent of `(y / x)` in the closed
interval \[-pi, pi\], corresponding to angles within +-180 degrees. The
quadrant of the resulting angle is determined using the signs of both
`x` and `y`, and is the same as the quadrant of the point `(x,
y)`. When `x` = 0, this corresponds to an angle of 90 degrees, and the
result is `(real (sign y)) * pi / 2.0`.

<a id="cos-impl"></a>
<h3><code>cos</code></h3>

`cos x` returns the cosine of `x`, measured in radians. If `x` is an
infinity, returns NaN.

<a id="cosh-impl"></a>
<h3><code>cosh</code></h3>

`cosh x` returns the hyperbolic cosine of `x`, that is, `(e(x) +
e(-x)) / 2`. Among its properties, cosh +-0 = 1, cosh +-infinity =
+-infinity.

<a id="e-impl"></a>
<h3><code>e</code></h3>

`e` is base e (2.718281828...) of the natural logarithm.

<a id="exp-impl"></a>
<h3><code>exp</code></h3>

`exp x` returns `e(x)`, i.e., `e` raised to the `x`<sup>th</sup>
power. If `x` is +infinity, returns +infinity; if `x` is -infinity,
returns 0.

<a id="ln-impl"></a>
<h3><code>ln</code></h3>

`ln x` returns the natural logarithm (base e) of `x`. If `x` < 0,
returns NaN; if `x` = 0, returns -infinity; if `x` is infinity,
returns infinity.

<a id="log10-impl"></a>
<h3><code>log10</code></h3>

`log10 x` returns the decimal logarithm (base 10) of `x`. If `x` < 0,
returns NaN; if `x` = 0, returns -infinity; if `x` is infinity,
returns infinity.

<a id="pi-impl"></a>
<h3><code>pi</code></h3>

`pi` is the constant pi (3.141592653...).

<a id="pow-impl"></a>
<h3><code>pow</code></h3>

`pow (x, y)` returns `x(y)`, i.e., `x` raised to the `y`<sup>th</sup>
power. For finite `x` and `y`, this is well-defined when `x` > 0, or
when `x` < 0 and `y` is integral.

<a id="sin-impl"></a>
<h3><code>sin</code></h3>

`sin x` returns the sine of `x`, measured in radians. If `x` is an
infinity, returns NaN.

<a id="sinh-impl"></a>
<h3><code>sinh</code></h3>

`sinh x` returns the hyperbolic sine of `x`, that is, `(e(x) - e(-x))
/ 2`. Among its properties, sinh +-0 = +-0, sinh +-infinity =
+-infinity.

<a id="sqrt-impl"></a>
<h3><code>sqrt</code></h3>

`sqrt x` returns the square root of `x`. sqrt (~0.0) = ~0.0. If `x` <
0, returns NaN.

<a id="tan-impl"></a>
<h3><code>tan</code></h3>

`tan x` returns the tangent of `x`, measured in radians. If `x` is an
infinity, returns NaN. Produces infinities at various finite values,
roughly corresponding to the singularities of the tangent function.

<a id="tanh-impl"></a>
<h3><code>tanh</code></h3>

`tanh x` returns the hyperbolic tangent of `x`, that is, `(sinh x) /
(cosh x)`. Among its properties, tanh +-0 = +-0, tanh +-infinity =
+-1.

[//]: # (end:lib/math)
