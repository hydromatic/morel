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
 * The STRING_CVT signature, per the Standard ML Basis Library.
 *)
(**
 * The `StringCvt` structure provides types and utilities to support
 * formatted string scanning and conversion, including numeric radix
 * specifiers and reader types.
 *)
signature STRING_CVT =
sig

  (**
   * specifies the numeric base: binary (2), octal (8), decimal (10), or
   * hexadecimal (16).
   *)
  datatype radix = BIN | OCT | DEC | HEX

(*
  (**
   * is the type of a scanning function that reads one value of type `'a`
   * from a stream of type `'b`, returning the value and the remaining
   * stream, or `NONE` at end of input.
   *)
  type ('a, 'b) reader = 'b -> ('a * 'b) option
*)

  (** specifies the format for converting real numbers to strings. *)
  datatype realfmt = SCI of int option | FIX of int option | GEN of int option | EXACT

  (**
   * `padLeft c i s` returns `s` padded on the left with `c` characters so
   * that the result has length at least `i`. If `s` is already at least
   * `i` characters long, it is returned unchanged.
   *)
  val padLeft  : char -> int -> string -> string
      [@@prototype "padLeft c i s"]

  (**
   * `padRight c i s` returns `s` padded on the right with `c` characters
   * so that the result has length at least `i`. If `s` is already at
   * least `i` characters long, it is returned unchanged.
   *)
  val padRight : char -> int -> string -> string
      [@@prototype "padRight c i s"]
end
[@@description "String conversion utilities and types."]

(*) End string-cvt.sig
