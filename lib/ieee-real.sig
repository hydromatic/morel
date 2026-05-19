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
 * The IEEE_REAL signature, per the Standard ML Basis Library.
 *)
(**
 * The `IEEEReal` structure provides types and constants related to
 * IEEE 754 floating-point arithmetic, including rounding modes,
 * floating-point classes, and a decimal approximation record type.
 *)
signature IEEE_REAL =
sig

(*
  (**
   * is a record type representing a decimal approximation of a
   * floating-point number.
   *)
  type decimal_approx
*)

(*
  (** classifies a floating-point value. *)
  datatype float_class = NAN | INF | ZERO | NORMAL | SUBNORMAL
*)

(*
  (**
   * is like `order` but adds `UNORDERED` for comparisons involving NaN.
   *)
  datatype real_order = LESS | EQUAL | GREATER | UNORDERED
*)

(*
  (**
   * specifies the IEEE 754 rounding mode for floating-point operations.
   *)
  datatype rounding_mode = TO_NEAREST | TO_NEGINF | TO_POSINF | TO_ZERO
*)
end
[@@description "IEEE 754 floating-point definitions."]

(*) End ieee-real.sig
