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
 * The DECIMAL signature, a Morel extension.
 *)
(**
 * The `Decimal` structure provides exact decimal arithmetic, based on the
 * IEEE 754-2008 decimal128 format: 34 significant digits, radix 10.
 * Unlike `real`, `decimal "0.1"` is exactly one tenth, so the type is
 * suitable for prices, rates, and SQL `DECIMAL` values.
 *
 * Each value has a single representation. Trailing zeros are discarded
 * (`decimal "12.30"` and `decimal "12.3"` are the same value), and there
 * are no infinities, NaN, or negative zero, so values are totally ordered
 * and `decimal` is an equality type. Operations that cannot produce a
 * finite result raise an exception: `Overflow` if the magnitude is too
 * large, `Div` on division by zero. Inexact results are rounded half-even
 * to 34 significant digits; results too small to represent are truncated
 * toward zero.
 *)
signature DECIMAL =
sig

  (**
   * is the type of decimal numbers with up to 34 significant digits.
   *)
  eqtype decimal

  (**
   * converts the string `s` to a decimal. The string may have a sign
   * (`~`, `-` or `+`), digits with an optional decimal point, and an
   * optional exponent (`E` or `e`, then an optionally signed integer). Raises
   * `Domain` if `s` is not of that form, or if its value cannot be
   * represented exactly. If `s` is a string literal, the conversion occurs
   * at compile time, and an invalid literal is a compile error.
   *)
  val decimal : string -> decimal [@@prototype "decimal s"]

  (** is the base of the representation, 10. *)
  val radix : int [@@prototype "radix"]

  (** is the number of digits in the significand, 34. *)
  val precision : int [@@prototype "precision"]

  (**
   * is the largest decimal value,
   * 9.999999999999999999999999999999999E6144.
   *)
  val maxFinite : decimal [@@prototype "maxFinite"]

  (** is the smallest positive decimal value, 1E~6176. *)
  val minPos : decimal [@@prototype "minPos"]

  (**
   * returns the sum of `d1` and `d2`. Raises `Overflow` if the result is too
   * large.
   *)
  val `+` : decimal * decimal -> decimal
      [@@prototype "d1 + d2"] [@@syntax "infix"]

  (**
   * returns the difference of `d1` and `d2`. Raises `Overflow` if the result
   * is too large.
   *)
  val `-` : decimal * decimal -> decimal
      [@@prototype "d1 - d2"] [@@syntax "infix"]

  (**
   * returns the product of `d1` and `d2`. Raises `Overflow` if the result is
   * too large.
   *)
  val `*` : decimal * decimal -> decimal
      [@@prototype "d1 * d2"] [@@syntax "infix"]

  (**
   * returns the quotient of `d1` and `d2`, rounded half-even to 34
   * significant digits. Raises `Div` if `d2` is zero, `Overflow` if the result
   * is too large.
   *)
  val `/` : decimal * decimal -> decimal
      [@@prototype "d1 / d2"] [@@syntax "infix"]

  (**
   * returns the remainder `x - n * y`, where `n` is the quotient `x / y`
   * truncated toward zero. The result has the same sign as `x`. Raises `Div`
   * if `y` is zero.
   *)
  val rem : decimal * decimal -> decimal
      [@@method] [@@prototype "rem (x, y)"]

  (** returns the negation of `d`. *)
  val `~` : decimal -> decimal [@@prototype "~ d"] [@@syntax "prefix"]

  (** returns the absolute value of `d`. *)
  val abs : decimal -> decimal [@@method] [@@prototype "abs d"]

  (** returns the smaller of `x` and `y`. *)
  val min : decimal * decimal -> decimal
      [@@method] [@@prototype "min (x, y)"]

  (** returns the larger of `x` and `y`. *)
  val max : decimal * decimal -> decimal
      [@@method] [@@prototype "max (x, y)"]

  (**
   * returns ~1, 0, or 1, according to whether `d` is negative, zero, or
   * positive.
   *)
  val sign : decimal -> int [@@method] [@@prototype "sign d"]

  (**
   * returns `LESS`, `EQUAL`, or `GREATER` according to whether `x` is less
   * than, equal to, or greater than `y`.
   *)
  val compare : decimal * decimal -> `order`
      [@@method] [@@prototype "compare (x, y)"]

  (** returns `true` if `x` is less than `y`. *)
  val `<` : decimal * decimal -> bool [@@prototype "x < y"] [@@syntax "infix"]

  (** returns `true` if `x` is less than or equal to `y`. *)
  val `<=` : decimal * decimal -> bool
      [@@prototype "x <= y"] [@@syntax "infix"]

  (** returns `true` if `x` is greater than `y`. *)
  val `>` : decimal * decimal -> bool [@@prototype "x > y"] [@@syntax "infix"]

  (** returns `true` if `x` is greater than or equal to `y`. *)
  val `>=` : decimal * decimal -> bool
      [@@prototype "x >= y"] [@@syntax "infix"]

  (** returns the largest integer-valued decimal not larger than `d`. *)
  val realFloor : decimal -> decimal [@@method] [@@prototype "realFloor d"]

  (** returns the smallest integer-valued decimal not less than `d`. *)
  val realCeil : decimal -> decimal [@@method] [@@prototype "realCeil d"]

  (** returns `d` rounded toward zero to an integer-valued decimal. *)
  val realTrunc : decimal -> decimal [@@method] [@@prototype "realTrunc d"]

  (**
   * returns the integer-valued decimal nearest to `d`. In the case of a tie,
   * it returns the even one.
   *)
  val realRound : decimal -> decimal [@@method] [@@prototype "realRound d"]

  (**
   * returns the largest `int` not larger than `d`. Raises `Overflow` if the
   * result is not representable as an `int`.
   *)
  val floor : decimal -> int [@@method] [@@prototype "floor d"]

  (**
   * returns the smallest `int` not less than `d`. Raises `Overflow` if the
   * result is not representable as an `int`.
   *)
  val ceil : decimal -> int [@@method] [@@prototype "ceil d"]

  (**
   * returns `d` rounded toward zero to an `int`. Raises `Overflow` if the
   * result is not representable as an `int`.
   *)
  val trunc : decimal -> int [@@method] [@@prototype "trunc d"]

  (**
   * returns the `int` nearest to `d`. In the case of a tie, it returns the
   * even one. Raises `Overflow` if the result is not representable as an
   * `int`.
   *)
  val round : decimal -> int [@@method] [@@prototype "round d"]

  (** converts the integer `i` to a decimal. *)
  val fromInt : int -> decimal [@@prototype "fromInt i"]

  (**
   * converts the real `r` to the decimal with the fewest digits that
   * converts back to `r`. Raises `Domain` if `r` is NaN, `Overflow` if `r` is
   * infinite.
   *)
  val fromReal : real -> decimal [@@prototype "fromReal r"]

  (** converts `d` to the nearest real. *)
  val toReal : decimal -> real [@@method] [@@prototype "toReal d"]

  (**
   * converts `d` to a string. Uses plain notation if the adjusted exponent is
   * at least ~7 and less than 34, otherwise scientific notation, and `~` for
   * a negative value. An integer has no decimal point. The result converts
   * back to the same value: `decimal (toString d) = d`.
   *)
  val toString : decimal -> string [@@method] [@@prototype "toString d"]

  (**
   * parses a decimal from a prefix of the string `s`, after skipping initial
   * whitespace, accepting the same syntax as `decimal`. Returns `SOME d` if
   * successful, `NONE` otherwise; characters after the number are ignored.
   * Unlike `decimal`, it rounds a value with more than 34 significant digits.
   * Raises `Overflow` if the value is too large.
   *)
  val fromString : string -> decimal option [@@prototype "fromString s"]

  (**
   * converts `d` to a string according to `spec`, as `Real.fmt` does, but
   * rounding half-even. Raises `Size` if `spec` is an invalid precision.
   *)
  val fmt : realfmt -> decimal -> string [@@prototype "fmt spec d"]
end
[@@description "Exact decimal numbers with 34 significant digits."]
[@@specified "morel"]

(*) End decimal.sig
