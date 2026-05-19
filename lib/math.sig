(*
 * Licensed to Julian Hyde under one or more contributor license
 * agreements.  See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Julian Hyde licenses this file to you under the Apache
 * License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License.  You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 *
 * The MATH signature, per the Standard ML Basis Library.
 *)
(**
 * The `Math` structure provides standard mathematical functions operating
 * on `real` values, including trigonometric, logarithmic, exponential,
 * and rounding operations, along with mathematical constants.
 *)
signature MATH =
sig

  type real

  (** is the constant pi (3.141592653...). *)
  val pi : real [@@prototype "pi"]

  (** is base e (2.718281828...) of the natural logarithm. *)
  val e : real [@@prototype "e"]

  (**
   * returns the square root of `x`. sqrt (~0.0) = ~0.0. If `x` <
   * 0, returns NaN.
   *)
  val sqrt : real -> real [@@prototype "sqrt x"]

  (**
   * returns the sine of `x`, measured in radians. If `x` is an
   * infinity, returns NaN.
   *)
  val sin : real -> real [@@prototype "sin x"]

  (**
   * returns the cosine of `x`, measured in radians. If `x` is an
   * infinity, returns NaN.
   *)
  val cos : real -> real [@@prototype "cos x"]

  (**
   * returns the tangent of `x`, measured in radians. If `x` is an
   * infinity, returns NaN. Produces infinities at various finite values,
   * roughly corresponding to the singularities of the tangent function.
   *)
  val tan : real -> real [@@prototype "tan x"]

  (**
   * returns the arc sine of `x`. `asin` is the inverse of
   * `sin`. Its result is guaranteed to be in the closed interval \[-pi / 2,
   * pi / 2\]. If the magnitude of `x` exceeds 1.0, returns NaN.
   *)
  val asin : real -> real [@@prototype "asin x"]

  (**
   * returns the arc cosine of `x`. `acos` is the inverse of
   * `cos`. Its result is guaranteed to be in the closed interval \[0,
   * pi\]. If the magnitude of `x` exceeds 1.0, returns NaN.
   *)
  val acos : real -> real [@@prototype "acos x"]

  (**
   * returns the arc tangent of `x`. `atan` is the inverse of
   * `tan`. For finite arguments, the result is guaranteed to be in the
   * open interval (-pi / 2, pi / 2). If `x` is +infinity, it returns pi /
   * 2; if `x` is -infinity, it returns -pi / 2.
   *)
  val atan : real -> real [@@prototype "atan x"]

  (**
   * returns the arc tangent of `(y / x)` in the closed
   * interval \[-pi, pi\], corresponding to angles within +-180 degrees. The
   * quadrant of the resulting angle is determined using the signs of both
   * `x` and `y`, and is the same as the quadrant of the point `(x,
   * y)`. When `x` = 0, this corresponds to an angle of 90 degrees, and the
   * result is `(real (sign y)) * pi / 2.0`.
   *)
  val atan2 : real * real -> real [@@prototype "atan2 (y, x)"]

  (**
   * returns `e(x)`, i.e., `e` raised to the `x`<sup>th</sup>
   * power. If `x` is +infinity, returns +infinity; if `x` is -infinity,
   * returns 0.
   *)
  val exp : real -> real [@@prototype "exp x"]

  (**
   * returns `x(y)`, i.e., `x` raised to the `y`<sup>th</sup>
   * power. For finite `x` and `y`, this is well-defined when `x` > 0, or
   * when `x` < 0 and `y` is integral.
   *)
  val pow : real * real -> real [@@prototype "pow (x, y)"]

  (**
   * returns the natural logarithm (base e) of `x`. If `x` < 0,
   * returns NaN; if `x` = 0, returns -infinity; if `x` is infinity,
   * returns infinity.
   *)
  val ln    : real -> real [@@prototype "ln x"]

  (**
   * returns the decimal logarithm (base 10) of `x`. If `x` < 0,
   * returns NaN; if `x` = 0, returns -infinity; if `x` is infinity,
   * returns infinity.
   *)
  val log10 : real -> real [@@prototype "log10 x"]

  (**
   * returns the hyperbolic sine of `x`, that is, `(e(x) - e(-x))
   * / 2`. Among its properties, sinh +-0 = +-0, sinh +-infinity =
   * +-infinity.
   *)
  val sinh : real -> real [@@prototype "sinh x"]

  (**
   * returns the hyperbolic cosine of `x`, that is, `(e(x) +
   * e(-x)) / 2`. Among its properties, cosh +-0 = 1, cosh +-infinity =
   * +-infinity.
   *)
  val cosh : real -> real [@@prototype "cosh x"]

  (**
   * returns the hyperbolic tangent of `x`, that is, `(sinh x) /
   * (cosh x)`. Among its properties, tanh +-0 = +-0, tanh +-infinity =
   * +-1.
   *)
  val tanh : real -> real [@@prototype "tanh x"]
end
[@@description "Mathematical functions for real numbers."]

(*) End math.sig
