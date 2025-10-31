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
signature INTEGER =
sig
  eqtype int

  (* Converts an integer to LargeInt representation. *)
  val toLarge   : int -> (*LargeInt.*)int

  (* Converts from LargeInt representation; may raise Overflow. *)
  val fromLarge : (*LargeInt.*)int -> int

  (* Converts an integer to default Int representation. *)
  val toInt   : int -> (*Int.*)int

  (* Converts from default Int representation. *)
  val fromInt : (*Int.*)int -> int

  (* The number of significant bits in this integer type; NONE for
   * infinite precision. *)
  val precision : (*Int.*)int option

  (* The smallest representable integer; NONE for infinite precision. *)
  val minInt : int option

  (* The largest representable integer; NONE for infinite precision. *)
  val maxInt : int option

(*
  val + : int * int -> int
  val - : int * int -> int
  val * : int * int -> int
*)
  (* Integer division truncated toward negative infinity; raises Div on
   * division by zero. *)
  val div : int * int -> int

  (* Modulus operation; result has same sign as divisor; raises Div on
   * division by zero. *)
  val mod : int * int -> int

  (* Integer division truncated toward zero; raises Div on division by
   * zero. *)
  val quot : int * int -> int

  (* Remainder operation; result has same sign as dividend; raises Div
   * on division by zero. *)
  val rem : int * int -> int

  (* Returns the ordering of two integers. *)
  val compare : int * int -> `order`
(*
  val <  : int * int -> bool
  val <= : int * int -> bool
  val >  : int * int -> bool
  val >= : int * int -> bool

  val ~ : int -> int
*)
  (* Returns the absolute value; raises Overflow on minInt for bounded types. *)
  val abs : int -> int

  (* Returns the smaller of two integers. *)
  val min : int * int -> int

  (* Returns the larger of two integers. *)
  val max : int * int -> int

  (* Returns -1, 0, or 1 when the argument is negative, zero, or positive. *)
  val sign : int -> (*Int.*)int

  (* Returns true if both arguments have the same sign. *)
  val sameSign : int * int -> bool

(*
  val fmt      : StringCvt.radix -> int -> string
*)
  (* Converts an integer to its decimal string representation. *)
  val toString : int -> string
(*
  val scan       : StringCvt.radix
                     -> (char, 'a) StringCvt.reader
                       -> (int, 'a) StringCvt.reader
*)
  (* Parses an integer from a string; returns SOME i or NONE. *)
  val fromString : string -> int option
end

(*) End integer.sig
