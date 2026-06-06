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
 * The REAL signature, per the Standard ML Basis Library.
 *)
(**
 * The `Real` structure provides arithmetic, comparison, conversion, and
 * classification operations for IEEE 754 double-precision floating-point
 * numbers.
 *)
signature REAL =
sig

  (**
   * is the type of IEEE 754 double-precision floating-point numbers.
   *)
  type real
(*
  structure Math : MATH where type real = real
*)

  (**
   * is the base of the representation, e.g., 2 or 10 for IEEE
   * floating point.
   *)
  val radix : int [@@prototype "radix"]

  (**
   * is the number of digits, each between 0 and `radix` - 1,
   * in the mantissa. Note that the precision includes the implicit (or
   * hidden) bit used in the IEEE representation (e.g., the value of
   * Real64.precision is 53).
   *)
  val precision : int [@@prototype "precision"]

  (** is the maximum finite number. *)
  val maxFinite : real [@@prototype "maxFinite"]
  (** is the minimum non-zero positive number. *)
  val minPos : real [@@prototype "minPos"]
  (** is the minimum non-zero normalized number. *)
  val minNormalPos : real [@@prototype "minNormalPos"]

  (** is the positive infinity value. *)
  val posInf : real [@@prototype "posInf"]
  (** is the negative infinity value. *)
  val negInf : real [@@prototype "negInf"]

  (**
   * is the sum of `r1` and `r2`. If one argument is finite and
   * the other infinite, the result is infinite with the correct sign,
   * e.g., 5 - (-infinity) = infinity. We also have infinity + infinity =
   * infinity and (-infinity) + (-infinity) = (-infinity). Any other
   * combination of two infinities produces NaN.
   *)
  val `+` : real * real -> real [@@prototype "r1 + r2"] [@@syntax "infix"]
  (**
   * is the difference of `r1` and `r2`. If one argument is
   * finite and the other infinite, the result is infinite with the correct
   * sign, e.g., 5 - (-infinity) = infinity. We also have infinity +
   * infinity = infinity and (-infinity) + (-infinity) = (-infinity). Any
   * other combination of two infinities produces NaN.
   *)
  val `-` : real * real -> real [@@prototype "r1 - r2"] [@@syntax "infix"]

  (**
   * is the product of `r1` and `r2`. The product of zero and an
   * infinity produces NaN. Otherwise, if one argument is infinite, the
   * result is infinite with the correct sign, e.g., -5 * (-infinity) =
   * infinity, infinity * (-infinity) = -infinity.
   *)
  val `*` : real * real -> real [@@prototype "r1 * r2"] [@@syntax "infix"]

  (**
   * is the quotient of `r1` and `r2`. We have 0 / 0 = NaN and
   * +-infinity / +-infinity = NaN. Dividing a finite, non-zero number by a
   * zero, or an infinity by a finite number produces an infinity with the
   * correct sign. (Note that zeros are signed.) A finite number divided by
   * an infinity is 0 with the correct sign.
   *)
  val `/` : real * real -> real [@@prototype "r1 / r2"]

  (**
   * returns the remainder `x - n * y`, where `n` = `trunc (x
   * / y)`. The result has the same sign as `x` and has absolute value less
   * than the absolute value of `y`. If `x` is an infinity or `y` is 0,
   * `rem` returns NaN. If `y` is an infinity, rem returns `x`.
   *)
  val rem : real * real -> real [@@method] [@@prototype "rem (x, y)"]

(*
  (**
   * returns `a * b + c`. Its behavior on infinities follows
   * from the behaviors derived from addition and multiplication.
   *)
  val `*+` : real * real * real -> real [@@prototype "*+ (a, b, c)"]
  (**
   * returns `a * b - c`. Its behavior on infinities follows
   * from the behaviors derived from subtraction and multiplication.
   *)
  val `*-` : real * real * real -> real
*) [@@prototype "*- (a, b, c)"]

  (** returns the negation of `r`. *)
  val `~` : real -> real [@@prototype "~ r"] [@@syntax "prefix"]

  (** returns the absolute value of `r`. *)
  val abs : real -> real [@@method] [@@prototype "abs r"]

  (**
   * returns the smaller of the arguments. If exactly one
   * argument is NaN, returns the other argument. If both arguments are
   * NaN, returns NaN.
   *)
  val min : real * real -> real [@@method] [@@prototype "min (x, y)"]
  (**
   * returns the larger of the arguments. If exactly one
   * argument is NaN, returns the other argument. If both arguments are
   * NaN, returns NaN.
   *)
  val max : real * real -> real [@@method] [@@prototype "max (x, y)"]

  (**
   * returns ~1 if r is negative, 0 if r is zero, or 1 if r is
   * positive. An infinity returns its sign; a zero returns 0 regardless of
   * its sign. It raises `Domain` on NaN.
   *)
  val sign : real -> int [@@method] [@@prototype "sign r"]

  (**
   * returns true if and only if the sign of `r` (infinities,
   * zeros, and NaN, included) is negative.
   *)
  val signBit : real -> bool [@@method] [@@prototype "signBit r"]

  (**
   * returns true if and only if `signBit r1` equals
   * `signBit r2`.
   *)
  val sameSign : real * real -> bool
      [@@method] [@@prototype "sameSign (r1, r2)"]

  (**
   * returns `x` with the sign of `y`, even if `y` is
   * NaN.
   *)
  val copySign : real * real -> real [@@method] [@@prototype "copySign (x, y)"]

  (**
   * returns `LESS`, `EQUAL`, or `GREATER` according to
   * whether its first argument is less than, equal to, or greater than the
   * second. It raises `IEEEReal.Unordered` on unordered arguments.
   *)
  val compare : real * real -> `order` [@@method] [@@prototype "compare (x, y)"]

(*
  (**
   * behaves similarly to `Real.compare` except that
   * the values it returns have the extended type `IEEEReal.real_order` and
   * it returns `IEEEReal.UNORDERED` on unordered arguments.
   *)
  val compareReal : real * real -> IEEEReal.real_order
*) [@@prototype "compareReal (x, y)"]

  (**
   * returns true if `x` is less than `y`. Return `false` on unordered
   * arguments, i.e., if either argument is NaN, so that the usual reversal
   * of comparison under negation does not hold, e.g., `a < b` is not the
   * same as `not (a >= b)`.
   *)
  val `<`  : real * real -> bool [@@prototype "x < y"] [@@syntax "infix"]
  (** As "<" *)
  val `<=` : real * real -> bool [@@prototype "x <= y"] [@@syntax "infix"]
  (** As "<" *)
  val `>`  : real * real -> bool [@@prototype "x > y"] [@@syntax "infix"]
  (** As "<" *)
  val `>=` : real * real -> bool [@@prototype "x >= y"] [@@syntax "infix"]
  (** returns true if `x` and `y` are equal. *)
  val `=`  : real * real -> bool [@@prototype "x = y"] [@@syntax "infix"]
  (** returns true if `x` and `y` are not equal. *)
  val `<>` : real * real -> bool [@@prototype "x <> y"] [@@syntax "infix"]

(*
  (**
   * returns `true` if and only if neither `y` nor `x` is NaN, and `y` and
   * `x` are equal, ignoring signs on zeros. This is equivalent to the IEEE
   * `=` operator.
   *)
  val `==` : real * real -> bool
*) [@@prototype "x == y"]

(*
  (** is equivalent to `not o op ==` and the IEEE `?<>` operator. *)
  val `!=` : real * real -> bool
*) [@@prototype "x != y"]

(*
  (**
   * returns `true` if either argument is NaN or if the arguments
   * are bitwise equal, ignoring signs on zeros. It is equivalent to the
   * IEEE `?=` operator.
   *)
  val `?=` : real * real -> bool
*) [@@prototype "?= (x, y)"]

  (**
   * returns true if x and y are unordered, i.e., at
   * least one of x and y is NaN.
   *)
  val unordered : real * real -> bool
      [@@method] [@@prototype "unordered (x, y)"]

  (** returns true if x is neither NaN nor an infinity. *)
  val isFinite : real -> bool [@@method] [@@prototype "isFinite x"]

  (** returns true if x NaN. *)
  val isNan : real -> bool [@@method] [@@prototype "isNan x"]

  (**
   * returns true if x is normal, i.e., neither zero,
   * subnormal, infinite nor NaN.
   *)
  val isNormal : real -> bool [@@method] [@@prototype "isNormal x"]

(*
  (** returns the `IEEEReal.float_class` to which x belongs. *)
  val class : real -> IEEEReal.float_class
*) [@@prototype "class x"]

  (**
   * returns `{man, exp}`, where `man` and `exp` are the
   * mantissa and exponent of r, respectively.
   *)
  val toManExp : real -> {man : real, exp : int}
      [@@method] [@@prototype "toManExp r"]

  (**
   * returns `{man, exp}`, where `man` and `exp` are the
   * mantissa and exponent of r, respectively.
   *)
  val fromManExp : {man : real, exp : int} -> real [@@prototype "fromManExp r"]

  (**
   * returns `{frac, whole}`, where `frac` and `whole` are the
   * fractional and integral parts of `r`, respectively. Specifically,
   * `whole` is integral, and `abs frac` < 1.0.
   *)
  val split : real -> {whole : real, frac : real}
      [@@method] [@@prototype "split r"]

  (**
   * returns the fractional parts of `r`; `realMod` is
   * equivalent to `#frac o split`.
   *)
  val realMod : real -> real [@@method] [@@prototype "realMod r"]

  (* Returns the next representable real after r in the direction of t.
   * If t is less than r, returns the largest representable floating-point
   * number less than r. If r = t then it returns r. *)
(*
  (**
   * returns the next representable real after `r` in
   * the direction of `t`. Thus, if `t` is less than `r`, `nextAfter`
   * returns the largest representable floating-point number less than `r`.
   *)
  val nextAfter : real * real -> real
*) [@@prototype "nextAfter (r, t)"]

  (**
   * raises `Overflow` if x is an infinity, and raises `Div`
   * if x is NaN. Otherwise, it returns its argument.
   *)
  val checkFloat : real -> real [@@method] [@@prototype "checkFloat x"]

  (**
   * produces `floor(r)`, the largest integer not larger than
   * `r`.
   *)
  val realFloor : real -> real [@@method] [@@prototype "realFloor r"]
  (**
   * produces `ceil(r)`, the smallest integer not less than
   * `r`.
   *)
  val realCeil : real -> real [@@method] [@@prototype "realCeil r"]
  (** rounds `r` towards zero. *)
  val realTrunc : real -> real [@@method] [@@prototype "realTrunc r"]
  (**
   * rounds to the integer-valued real value that is nearest
   * to `r`. In the case of a tie, it rounds to the nearest even integer.
   *)
  val realRound : real -> real [@@method] [@@prototype "realRound r"]

  (** produces `floor(r)`, the largest int not larger than `r`. *)
  val floor : real -> int [@@method] [@@prototype "floor r"]
  (** produces `ceil(r)`, the smallest int not less than `r`. *)
  val ceil : real -> int [@@method] [@@prototype "ceil r"]
  (** rounds r towards zero. *)
  val trunc : real -> int [@@method] [@@prototype "trunc r"]
  (**
   * yields the integer nearest to `r`. In the case of a tie, it
   * rounds to the nearest even integer.
   *)
  val round : real -> int [@@method] [@@prototype "round r"]

  (* Convert the argument x to an integral type using the specified
   * rounding mode. They raise Overflow if the result is not representable,
   * in particular, if x is an infinity. They raise Domain if the input
   * real is NaN. *)
(*
  (**
   * converts the argument `x` to an integral type using the
   * specified rounding mode. It raises `Overflow` if the result is not
   * representable, in particular, if `x` is an infinity. It raises
   * `Domain` if the input real is NaN.
   *)
  val toInt : IEEEReal.rounding_mode -> real -> int [@@prototype "toInt mode x"]
  (** As "toInt" *)
  val toLargeInt : IEEEReal.rounding_mode -> real -> LargeInt.int
*) [@@prototype "toLargeInt mode r"]

  (**
   * converts the integer `i` to a `real` value. If the
   * absolute value of `i` is larger than `maxFinite`, then the appropriate
   * infinity is returned. If `i` cannot be exactly represented as a `real`
   * value, uses current rounding mode to determine the resulting value.
   *)
  val fromInt : int -> real [@@prototype "fromInt i"]
(*
  (** As "fromInt" *)
  val fromLargeInt : LargeInt.int -> real
*) [@@prototype "fromLargeInt i"]

(*
  (** convert a value of type `real` to type `LargeReal.real`. *)
  val toLarge : real -> LargeReal.real [@@prototype "toLarge r"]
  (**
   * converts a value of type `real` to type
   * `LargeReal.real`. If `r` is too small or too large to be represented
   * as a real, converts it to a zero or an infinity.
   *)
  val fromLarge : IEEEReal.rounding_mode -> LargeReal.real -> real
*) [@@prototype "toLarge r"]

  (**
   * converts a `real` into a `string` according to `spec`. Raises
   * `Size` when `fmt spec` is evaluated if `spec` is an invalid
   * precision (negative for `SCI` or `FIX`, less than 1 for `GEN`).
   *)
  val fmt : realfmt -> real -> string
      [@@method] [@@prototype "fmt spec r"]

  (**
   * converts a `real` into a `string`; equivalent to `(fmt
   * (StringCvt.GEN NONE) r)`
   *)
  val toString : real -> string [@@method] [@@prototype "toString r"]

  (* Parse real numbers from character sources, accepting formats like
   * [+~-]?(digits.digits | .digits)(e|E)[+~-]?digits and non-finite
   * representations like inf, infinity, nan (case-insensitive). *)
(*
  (**
   * scans a `real` value from character source. Reads
   * from ARG/strm/ using reader `getc`, ignoring initial whitespace. It
   * returns `SOME (r, rest)` if successful, where `r` is the scanned
   * `real` value and `rest` is the unused portion of the character stream
   * `strm`. Values of too large a magnitude are represented as infinities;
   * values of too small a magnitude are represented as zeros.
   *)
  val scan : (char, 'a) StringCvt.reader -> (real, 'a) StringCvt.reader
*) [@@prototype "scan getc strm"]
  (**
   * scans a `real` value from a string. Returns `SOME (r)`
   * if a `real` value can be scanned from a prefix of `s`, ignoring any
   * initial whitespace; otherwise, it returns `NONE`. This function is
   * equivalent to `StringCvt.scanString scan`.
   *)
  val fromString : string -> real option [@@prototype "fromString s"]

  (* Convert between real and IEEEReal.decimal_approx representations.
   * For normal/subnormal values: fromDecimal (toDecimal r) = r bitwise.
   * Returns NONE if decimal format is invalid. *)
(*
  (** converts a `real` to a decimal approximation *)
  val toDecimal : real -> IEEEReal.decimal_approx [@@prototype "toDecimal r"]
  (** converts decimal approximation to a `real` *)
  val fromDecimal : IEEEReal.decimal_approx -> real option
*) [@@prototype "fromDecimal d"]
end
[@@description "Floating-point number operations."]

(*) End real.sig
