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
 * The WORD signature, per the Standard ML Basis Library.
 *)
(**
 * The `Word` structure provides a type of unsigned integer with modular
 * arithmetic, logical (bit-wise) operations, and conversions. Words are
 * meant to give efficient access to the primitive machine word type of the
 * underlying hardware, and to support bit-level operations on integers.
 *
 * In Morel, `word` is a 64-bit unsigned integer (`wordSize` is 64),
 * `LargeWord.word` = `word`, and `LargeInt.int` = `int`.
 *)
signature WORD =
sig

  (** is the type of unsigned, fixed-precision integers (words). *)
  eqtype word

  (**
   * is the number of bits in type `word`. In Morel, `wordSize` is 64.
   * `wordSize` need not be a power of two; note that `word` has a fixed,
   * finite precision.
   *)
  val wordSize : int [@@prototype "wordSize"]

  (**
   * converts `w` to an equivalent value in `LargeWord.word`, in the range
   * [0, 2<sup>wordSize</sup> - 1]. In Morel, `LargeWord.word` = `word`, so
   * this is the identity.
   *)
  val toLarge   : word -> (*LargeWord.*)word [@@prototype "toLarge w"]

  (**
   * is the "sign-extended" conversion of `w` to `LargeWord.word`: the
   * `wordSize` low-order bits of `w` and `toLargeX w` are the same, and the
   * remaining bits of `toLargeX w` are all equal to the most significant bit
   * of `w`. In Morel, `LargeWord.word` = `word`, so this is the identity.
   *)
  val toLargeX  : word -> (*LargeWord.*)word [@@prototype "toLargeX w"]

  (** is a deprecated synonym of `toLarge`. *)
  val toLargeWord  : word -> (*LargeWord.*)word [@@prototype "toLargeWord w"]

  (** is a deprecated synonym of `toLargeX`. *)
  val toLargeWordX : word -> (*LargeWord.*)word [@@prototype "toLargeWordX w"]

  (**
   * converts `w` to the value `w` (mod 2<sup>wordSize</sup>) of type `word`,
   * taking the low-order `wordSize` bits of the 2's complement representation
   * of `w`. In Morel, `LargeWord.word` = `word`, so this is the identity.
   *)
  val fromLarge     : (*LargeWord.*)word -> word [@@prototype "fromLarge w"]

  (** is a deprecated synonym of `fromLarge`. *)
  val fromLargeWord : (*LargeWord.*)word -> word [@@prototype "fromLargeWord w"]

  (**
   * converts `w`, treated as an integer value in the range
   * [0, 2<sup>wordSize</sup> - 1], to `LargeInt.int`. It raises `Overflow` if
   * the value cannot be represented as a `LargeInt.int`. In Morel,
   * `LargeInt.int` = `int`, so it raises `Overflow` when `w` exceeds
   * `Int.maxInt`.
   *)
  val toLargeInt  : word -> (*LargeInt.*)int [@@prototype "toLargeInt w"]

  (**
   * converts `w`, treated as a 2's complement signed integer with `wordSize`
   * precision, to `LargeInt.int`. In Morel, `LargeInt.int` = `int`
   * (fixed-precision), so it raises `Overflow` when the value cannot be
   * represented as an `int`.
   *)
  val toLargeIntX : word -> (*LargeInt.*)int [@@prototype "toLargeIntX w"]

  (**
   * converts `i` of type `LargeInt.int` to a value of type `word`, taking the
   * low-order `wordSize` bits of the 2's complement representation of `i`.
   *)
  val fromLargeInt : (*LargeInt.*)int -> word [@@prototype "fromLargeInt i"]

  (**
   * converts `w`, treated as an integer value in the range
   * [0, 2<sup>wordSize</sup> - 1], to the default integer type. It raises
   * `Overflow` if the value cannot be represented as an `Int.int`.
   *)
  val toInt  : word -> (*Int.*)int [@@prototype "toInt w"]

  (**
   * converts `w`, treated as a 2's complement signed integer with `wordSize`
   * precision, to the default integer type. It raises `Overflow` if the value
   * cannot be represented as an `Int.int`.
   *)
  val toIntX : word -> (*Int.*)int [@@prototype "toIntX w"]

  (**
   * converts `i` of the default integer type to a value of type `word`,
   * taking the low-order `wordSize` bits of the 2's complement representation
   * of `i`.
   *)
  val fromInt : (*Int.*)int -> word [@@prototype "fromInt i"]

  (** returns the bit-wise AND of `i` and `j`. *)
  val andb : word * word -> word [@@method] [@@prototype "andb (i, j)"]

  (** returns the bit-wise OR of `i` and `j`. *)
  val orb  : word * word -> word [@@method] [@@prototype "orb (i, j)"]

  (** returns the bit-wise exclusive OR of `i` and `j`. *)
  val xorb : word * word -> word [@@method] [@@prototype "xorb (i, j)"]

  (** returns the bit-wise complement (NOT) of `i`. *)
  val notb : word -> word [@@method] [@@prototype "notb i"]

  (**
   * shifts `i` to the left by `n` bit positions, filling in zeros from the
   * right. It returns `(i * 2`<sup>n</sup>`) (mod 2`<sup>wordSize</sup>`)`.
   * When `n >= wordSize` the result is `0w0`.
   *)
  val `<<`  : word * word -> word [@@prototype "i << n"] [@@syntax "infix"]

  (**
   * shifts `i` to the right by `n` bit positions, filling in zeros from the
   * left. It returns `floor(i / 2`<sup>n</sup>`)`. When `n >= wordSize` the
   * result is `0w0`.
   *)
  val `>>`  : word * word -> word [@@prototype "i >> n"] [@@syntax "infix"]

  (**
   * shifts `i` to the right by `n` bit positions. The value of the leftmost
   * bit of `i` remains the same; in a 2's complement interpretation this
   * corresponds to sign extension. It returns `floor(i / 2`<sup>n</sup>`)`.
   *)
  val `~>>` : word * word -> word [@@prototype "i ~>> n"] [@@syntax "infix"]

  (**
   * returns the sum `(i + j) (mod 2`<sup>wordSize</sup>`)`. It does not raise
   * `Overflow`.
   *)
  val `+` : word * word -> word [@@prototype "i + j"] [@@syntax "infix"]

  (**
   * returns the difference of `i` and `j` modulo 2<sup>wordSize</sup>:
   * `(2`<sup>wordSize</sup>` + i - j) (mod 2`<sup>wordSize</sup>`)`. It does
   * not raise `Overflow`.
   *)
  val `-` : word * word -> word [@@prototype "i - j"] [@@syntax "infix"]

  (**
   * returns the product `(i * j) (mod 2`<sup>wordSize</sup>`)`. It does not
   * raise `Overflow`.
   *)
  val `*` : word * word -> word [@@prototype "i * j"] [@@syntax "infix"]

  (**
   * returns the truncated quotient of `i` and `j`, `floor(i / j)`, treating
   * the arguments as unsigned. It raises `Div` when `j = 0w0`.
   *)
  val div : word * word -> word [@@prototype "i div j"]

  (**
   * returns the remainder of the division of `i` by `j`,
   * `i - j * floor(i / j)`, treating the arguments as unsigned. It raises
   * `Div` when `j = 0w0`.
   *)
  val mod : word * word -> word [@@prototype "i mod j"]

  (**
   * returns `LESS`, `EQUAL`, or `GREATER` if and only if `i` is less than,
   * equal to, or greater than `j`, respectively, considered as unsigned
   * binary numbers.
   *)
  val compare : word * word -> `order` [@@method] [@@prototype "compare (i, j)"]

  (** returns true if `i` is less than `j`, considered as unsigned. *)
  val `<`  : word * word -> bool [@@prototype "i < j"] [@@syntax "infix"]
  (**
   * returns true if `i` is less than or equal to `j`, considered as unsigned.
   *)
  val `<=` : word * word -> bool [@@prototype "i <= j"] [@@syntax "infix"]
  (** returns true if `i` is greater than `j`, considered as unsigned. *)
  val `>`  : word * word -> bool [@@prototype "i > j"] [@@syntax "infix"]
  (**
   * returns true if `i` is greater than or equal to `j`, considered as
   * unsigned.
   *)
  val `>=` : word * word -> bool [@@prototype "i >= j"] [@@syntax "infix"]

  (** returns the 2's complement of `i`. *)
  val `~` : word -> word [@@prototype "~ i"] [@@syntax "prefix"]

  (** returns the smaller of the arguments, considered as unsigned. *)
  val min : word * word -> word [@@method] [@@prototype "min (i, j)"]

  (** returns the larger of the arguments, considered as unsigned. *)
  val max : word * word -> word [@@method] [@@prototype "max (i, j)"]

  (**
   * returns a string containing a representation of `i` in the given `radix`.
   * The hexadecimal digits 10 through 15 are represented as #"A" through
   * #"F", respectively. No prefix "0w" or "0wx" is generated.
   *)
  val fmt      : radix -> word -> string [@@prototype "fmt radix i"]

  (**
   * converts a `word` into a `string`; equivalent to
   * `(fmt StringCvt.HEX i)`.
   *)
  val toString : word -> string [@@method] [@@prototype "toString i"]
(*
  (**
   * returns `SOME (w, rest)` if an unsigned number in the format denoted by
   * `radix` can be parsed from a prefix of the character stream `strm` using
   * the character input function `getc`, where `w` is the value parsed and
   * `rest` is the remainder of the character stream. `NONE` is returned
   * otherwise. This function raises `Overflow` when a number can be parsed,
   * but is too large to fit in type `word`.
   *)
  val scan       : StringCvt.radix
                     -> (char, 'a) StringCvt.reader
                       -> (word, 'a) StringCvt.reader
*) [@@prototype "scan radix getc strm"]
  (**
   * returns `SOME (w)` if an unsigned hexadecimal number can be parsed from a
   * prefix of string `s`, ignoring initial whitespace; otherwise it returns
   * `NONE`. Equivalent to `StringCvt.scanString (scan StringCvt.HEX)`. It
   * raises `Overflow` when a hexadecimal numeral can be parsed, but is too
   * large to be represented by type `word`.
   *)
  val fromString : string -> word option [@@prototype "fromString s"]
end
[@@description "Unsigned-integer (word) operations."]

(*) End word.sig
