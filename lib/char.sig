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
 * The CHAR signature, per the Standard ML Basis Library.
 *)
(**
 * The `Char` structure provides the character type and associated
 * operations for examining and converting characters. Characters are
 * identified by their Unicode code points.
 *)
signature CHAR =
sig

  (** is the type of characters. *)
  eqtype char
  eqtype string

  (**
   * is the minimal (most negative) character representable by
   * `char`. If a value is `NONE`, `char` can represent all negative
   * integers, within the limits of the heap size. If `precision` is `SOME
   * (n)`, then we have `minChar` = -2<sup>(n-1)</sup>.
   *)
  val minChar : char [@@prototype "minChar"]

  (** is the greatest character in the ordering `<`. *)
  val maxChar : char [@@prototype "maxChar"]

  (** is the greatest character code; it equals `ord maxChar`. *)
  val maxOrd : int [@@prototype "maxOrd"]

  (** returns the code of character `c`. *)
  val ord : char -> int [@@method] [@@prototype "ord c"]

  (**
   * returns the character whose code is `i`. Raises `Chr` if `i` <
   * 0 or `i` > `maxOrd`.
   *)
  val chr : int -> char [@@prototype "chr i"]

  (**
   * returns the character immediately following `c`, or raises
   * `Chr` if `c` = `maxChar`
   *)
  val succ : char -> char [@@method] [@@prototype "succ c"]

  (**
   * returns the predecessor of `c`. Raises `Subscript` if `c` is
   * `minOrd`.
   *)
  val pred : char -> char [@@method] [@@prototype "pred c"]

  (**
   * returns `LESS`, `EQUAL`, or `GREATER` according to
   * whether its first argument is less than, equal to, or greater than the
   * second.
   *)
  val compare : char * char -> `order`
      [@@method] [@@prototype "compare (c1, c2)"]

  (**
   * returns true if `c1` is less than `c2` in the character ordering.
   *)
  val `<`  : char * char -> bool [@@prototype "c1 < c2"] [@@syntax "infix"]
  (**
   * returns true if `c1` is less than or equal to `c2` in the character
   * ordering.
   *)
  val `<=` : char * char -> bool [@@prototype "c1 <= c2"] [@@syntax "infix"]
  (**
   * returns true if `c1` is greater than `c2` in the character ordering.
   *)
  val `>`  : char * char -> bool [@@prototype "c1 > c2"] [@@syntax "infix"]
  (**
   * returns true if `c1` is greater than or equal to `c2` in the character
   * ordering.
   *)
  val `>=` : char * char -> bool [@@prototype "c1 >= c2"] [@@syntax "infix"]
  (** returns true if `c1` and `c2` are the same character. *)
  val `=`  : char * char -> bool [@@prototype "c1 = c2"] [@@syntax "infix"]
  (** returns true if `c1` and `c2` are different characters. *)
  val `<>` : char * char -> bool [@@prototype "c1 <> c2"] [@@syntax "infix"]

  (**
   * returns true if character `c` occurs in the string `s`;
   * false otherwise. The function, when applied to `s`, builds a table and
   * returns a function which uses table lookup to decide whether a given
   * character is in the string or not. Hence it is relatively expensive to
   * compute `val p = contains s` but very fast to compute `p(c)` for any
   * given character.
   *)
  val contains : string -> char -> bool [@@prototype "contains s c"]

  (**
   * returns true if character `c` does not occur in the
   * string `s`; false otherwise. Works by construction of a lookup table
   * in the same way as `Char.contains`.
   *)
  val notContains : string -> char -> bool [@@prototype "notContains s c"]

  (** returns true if 0 ≤ `ord c` ≤ 127 `c`. *)
  val isAscii : char -> bool [@@method] [@@prototype "isAscii c"]

  (**
   * returns the lowercase letter corresponding to `c`, if `c`
   * is a letter (a to z or A to Z); otherwise returns `c`.
   *)
  val toLower : char -> char [@@method] [@@prototype "toLower c"]

  (**
   * returns the uppercase letter corresponding to `c`, if `c`
   * is a letter (a to z or A to Z); otherwise returns `c`.
   *)
  val toUpper : char -> char [@@method] [@@prototype "toUpper c"]

  (** returns true if `c` is a letter (lowercase or uppercase). *)
  val isAlpha : char -> bool [@@method] [@@prototype "isAlpha c"]

  (**
   * returns true if `c` is alphanumeric (a letter or a
   * decimal digit).
   *)
  val isAlphaNum : char -> bool [@@method] [@@prototype "isAlphaNum c"]

  (**
   * returns true if `c` is a control character, that is, if
   * `not (isPrint c)`.
   *)
  val isCntrl : char -> bool [@@method] [@@prototype "isCntrl c"]

  (** returns true if `c` is a decimal digit (0 to 9). *)
  val isDigit : char -> bool [@@method] [@@prototype "isDigit c"]

  (**
   * returns true if `c` is a graphical character, that is, it
   * is printable and not a whitespace character.
   *)
  val isGraph : char -> bool [@@method] [@@prototype "isGraph c"]

  (** returns true if `c` is a hexadecimal digit. *)
  val isHexDigit : char -> bool [@@method] [@@prototype "isHexDigit c"]

  (** returns true if `c` is an octal digit (0 to 7). *)
  val isOctDigit : char -> bool [@@method] [@@prototype "isOctDigit c"]

  (**
   * returns true if `c` is a hexadecimal digit (0 to 9 or a to
   * f or A to F).
   *)
  val isLower : char -> bool [@@method] [@@prototype "isLower c"]

  (**
   * returns true if `c` is a printable character (space or
   * visible).
   *)
  val isPrint : char -> bool [@@method] [@@prototype "isPrint c"]

  (**
   * returns true if `c` is a whitespace character (blank,
   * newline, tab, vertical tab, new page).
   *)
  val isSpace : char -> bool [@@method] [@@prototype "isSpace c"]

  (**
   * returns true if `c` is a punctuation character, that is,
   * graphical but not alphanumeric.
   *)
  val isPunct : char -> bool [@@method] [@@prototype "isPunct c"]

  (** returns true if `c` is an uppercase letter (A to Z). *)
  val isUpper : char -> bool [@@method] [@@prototype "isUpper c"]

  (* Returns a printable string representation of the character. *)
  val toString : char -> (*String.*)string [@@method] [@@prototype "toString c"]
(*
  val scan       : (Char.char, 'a) StringCvt.reader
                   -> (char, 'a) StringCvt.reader
*)
  (* Scans a character from a string, returning SOME c or NONE. *)
  val fromString : (*String.*)string -> char option [@@prototype "fromString s"]

  (** returns `SOME c`, the character with code `i`, or `NONE` if `i` is not in
   * the range `0` to `maxOrd`. *)
  val fromInt : int -> char option [@@prototype "fromInt i"]

  (* Returns a string corresponding to the C-language representation of
   * the character. *)
  val toCString : char -> (*String.*)string
      [@@method] [@@prototype "toCString c"]

  (* Scans a C-language character escape sequence from a string. *)
  val fromCString : (*String.*)string -> char option
      [@@prototype "fromCString s"]
end
[@@description "Character values and operations."]

(*) End char.sig
