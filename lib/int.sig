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
 * The INTEGER signature, per the Standard ML Basis Library.
 *)
(**
 * The `Int` structure provides arithmetic, comparison, and conversion
 * operations for the default fixed-precision integer type.
 *)
signature INTEGER =
sig

  (** is the type of fixed-precision integers. *)
  eqtype int

  (* Converts an integer to LargeInt representation. *)
  val toLarge   : int -> (*LargeInt.*)int [@@prototype "toLarge i"]

  (* Converts from LargeInt representation; may raise Overflow. *)
  val fromLarge : (*LargeInt.*)int -> int [@@prototype "fromLarge i"]

  (* Converts an integer to default Int representation. *)
  val toInt   : int -> (*Int.*)int [@@prototype "toInt i"]

  (* Converts from default Int representation. *)
  val fromInt : (*Int.*)int -> int [@@prototype "fromInt i"]

  (* The number of significant bits in this integer type; NONE for
   * infinite precision. *)
  val precision : (*Int.*)int option [@@prototype "precision"]

  (**
   * is the minimal (most negative) integer representable by
   * `int`. If a value is `NONE`, `int` can represent all negative
   * integers, within the limits of the heap size. If `precision` is `SOME
   * (n)`, then we have `minInt` = -2<sup>(n-1)</sup>.
   *)
  val minInt : int option [@@prototype "minInt"]

  (**
   * is the maximal (most positive) integer representable by
   * `int`. If a value is `NONE`, `int` can represent all positive
   * integers, within the limits of the heap size. If `precision` is `SOME
   * (n)`, then we have `maxInt` = 2<sup>(n-1)</sup> - 1.
   *)
  val maxInt : int option [@@prototype "maxInt"]

  (**
   * is the sum of `i` and `j`. It raises `Overflow` when the
   * result is not representable.
   *)
  val `+` : int * int -> int [@@prototype "i + j"] [@@syntax "infix"]
  (**
   * is the difference of `i` and `j`. It raises `Overflow` when
   * the result is not representable.
   *)
  val `-` : int * int -> int [@@prototype "i - j"] [@@syntax "infix"]
  (**
   * is the product of `i` and `j`. It raises `Overflow` when the
   * result is not representable.
   *)
  val `*` : int * int -> int [@@prototype "i * j"] [@@syntax "infix"]
  (**
   * returns the greatest integer less than or equal to the
   * quotient of `i` by j, i.e., `floor(i / j)`. It raises `Overflow` when
   * the result is not representable, or Div when `j = 0`. Note that
   * rounding is towards negative infinity, not zero.
   *)
  val div : int * int -> int [@@prototype "i div j"]

  (**
   * returns the remainder of the division of `i` by `j`. It raises
   * `Div` when `j = 0`. When defined, `(i mod j)` has the same sign as
   * `j`, and `(i div j) * j + (i mod j) = i`.
   *)
  val mod : int * int -> int [@@prototype "i mod j"]

  (**
   * returns the truncated quotient of the division of `i` by
   * `j`, i.e., it computes `(i / j)` and then drops any fractional part of
   * the quotient. It raises `Overflow` when the result is not
   * representable, or `Div` when `j = 0`. Note that unlike `div`, `quot`
   * rounds towards zero. In addition, unlike `div` and `mod`, neither
   * `quot` nor `rem` are infix by default; an appropriate infix
   * declaration would be `infix 7 quot rem`. This is the semantics of most
   * hardware divide instructions, so `quot` may be faster than `div`.
   *)
  val quot : int * int -> int [@@method] [@@prototype "quot (i, j)"]

  (**
   * returns the remainder of the division of `i` by `j`. It
   * raises `Div` when `j = 0`. `(i rem j)` has the same sign as i, and it
   * holds that `(i quot j) * j + (i rem j) = i`. This is the semantics of
   * most hardware divide instructions, so `rem` may be faster than `mod`.
   *)
  val rem : int * int -> int [@@method] [@@prototype "rem (i, j)"]

  (**
   * returns `LESS`, `EQUAL`, or `GREATER` according to
   * whether its first argument is less than, equal to, or greater than the
   * second.
   *)
  val compare : int * int -> `order` [@@method] [@@prototype "compare (i, j)"]
  (** returns true if `i` is less than `j`. *)
  val `<`  : int * int -> bool [@@prototype "i < j"] [@@syntax "infix"]
  (** returns true if `i` is less than or equal to `j`. *)
  val `<=` : int * int -> bool [@@prototype "i <= j"] [@@syntax "infix"]
  (** returns true if `i` is greater than `j`. *)
  val `>`  : int * int -> bool [@@prototype "i > j"] [@@syntax "infix"]
  (** returns true if `i` is greater than or equal to `j`. *)
  val `>=` : int * int -> bool [@@prototype "i >= j"] [@@syntax "infix"]
  (** returns the negation of `i`. *)
  val `~` : int -> int [@@prototype "~ i"] [@@syntax "prefix"]
  (** returns the absolute value of `i`. *)
  val abs : int -> int [@@method] [@@prototype "abs i"]

  (** returns the smaller of the arguments. *)
  val min : int * int -> int [@@method] [@@prototype "min (i, j)"]

  (** returns the larger of the arguments. *)
  val max : int * int -> int [@@method] [@@prototype "max (i, j)"]

  (* Returns -1, 0, or 1 when the argument is negative, zero, or positive. *)
  val sign : int -> (*Int.*)int [@@method] [@@prototype "sign i"]

  (**
   * returns true if `i` and `j` have the same sign. It
   * is equivalent to `(sign i = sign j)`.
   *)
  val sameSign : int * int -> bool [@@method] [@@prototype "sameSign (i, j)"]

  (**
   * returns a string containing a representation of `i` with
   * #"~" used as the sign for negative numbers. Formats the string
   * according to `radix`; the hexadecimal digits 10 through 15 are
   * represented as #"A" through #"F", respectively. No prefix "0x" is
   * generated for the hexadecimal representation.
   *)
  val fmt      : radix -> int -> string [@@prototype "fmt radix i"]
  (**
   * converts a `int` into a `string`; equivalent to `(fmt
   * StringCvt.DEC r)`.
   *)
  val toString : int -> string [@@method] [@@prototype "toString i"]
(*
  (**
   * returns `SOME (i,rest)` if an integer in the format denoted by `radix`
   * can be parsed from a prefix of the character stream `strm` after
   * skipping initial whitespace, where `i` is the value of the integer
   * parsed and `rest` is the rest of the character stream. `NONE` is
   * returned otherwise. This function raises `Overflow` when an integer
   * can be parsed, but is too large to be represented by type `int`.
   *)
  val scan       : StringCvt.radix
                     -> (char, 'a) StringCvt.reader
                       -> (int, 'a) StringCvt.reader
*) [@@prototype "scan radix getc strm"]
  (**
   * scans a `int` value from a string. Returns `SOME (r)`
   * if a `int` value can be scanned from a prefix of `s`, ignoring any
   * initial whitespace; otherwise, it returns `NONE`. Equivalent to
   * `StringCvt.scanString (scan StringCvt.DEC)`.
   *)
  val fromString : string -> int option [@@prototype "fromString s"]
end
[@@description "Fixed-precision integer operations."]

(*) End int.sig
