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
signature MATH =
sig
  type real

  (* The constant pi (3.141592653...). *)
  val pi : real

  (* The base e (2.718281828...) of the natural logarithm. *)
  val e : real

  (* Returns the square root; returns NaN for negative values. *)
  val sqrt : real -> real

  (* Returns the sine of the argument (in radians). *)
  val sin : real -> real

  (* Returns the cosine of the argument (in radians). *)
  val cos : real -> real

  (* Returns the tangent of the argument (in radians). *)
  val tan : real -> real

  (* Returns the arc sine in the range [-pi/2, pi/2]; returns NaN if |x| > 1. *)
  val asin : real -> real

  (* Returns the arc cosine in the range [0, pi]; returns NaN if |x| > 1. *)
  val acos : real -> real

  (* Returns the arc tangent in the range (-pi/2, pi/2). *)
  val atan : real -> real

  (* Returns the arc tangent of y/x in the range [-pi, pi]. *)
  val atan2 : real * real -> real

  (* Returns e raised to the power of the argument. *)
  val exp : real -> real

  (* Returns x raised to the power y. *)
  val pow : real * real -> real

  (* Returns the natural logarithm; returns -infinity for 0, NaN for
   * negative values. *)
  val ln    : real -> real

  (* Returns the base-10 logarithm; returns -infinity for 0, NaN for
   * negative values. *)
  val log10 : real -> real

  (* Returns the hyperbolic sine. *)
  val sinh : real -> real

  (* Returns the hyperbolic cosine. *)
  val cosh : real -> real

  (* Returns the hyperbolic tangent. *)
  val tanh : real -> real
end

(*) End math.sig
