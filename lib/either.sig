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
 * The EITHER signature, a proposed addition to the standard basis
 * library.
 *)
(**
 * The `Either` structure provides a polymorphic disjoint-sum type
 * `('left, 'right) either` whose values are either `INL v` (left) or
 * `INR v` (right), along with operations for examining and transforming
 * such values.
 *)
signature EITHER =
sig

  (**
   * is the type of disjoint-sum values; `INL v` represents a left value and
   * `INR v` represents a right value.
   *)
  datatype ('left, 'right) either = INL of 'left | INR of 'right

  (** returns true if `sm` is a left value. *)
  val isLeft  : ('left, 'right) either -> bool
      [@@method] [@@prototype "isLeft sm"]

  (** returns true if `sm` is a right value. *)
  val isRight : ('left, 'right) either -> bool
      [@@method] [@@prototype "isRight sm"]

  (**
   * returns `SOME (x)` if `sm` is a left value with contents `x`,
   * otherwise it returns `NONE`.
   *)
  val asLeft  : ('left, 'right) either -> 'left option
      [@@method] [@@prototype "asLeft sm"]

  (**
   * returns `SOME (x)` if `sm` is a right value with contents `x`,
   * otherwise it returns `NONE`.
   *)
  val asRight : ('left, 'right) either -> 'right option
      [@@method] [@@prototype "asRight sm"]

  (**
   * maps `fl` over the contents of left values and `fr` over the contents
   * of right values.
   *)
  val map : ('ldom -> 'lrng) * ('rdom -> 'rrng)
          -> ('ldom, 'rdom) either
            -> ('lrng, 'rrng) either [@@prototype "map (fl, fr) sm"]

  (**
   * maps the function `f` over the contents of left values and acts as the
   * identity on right values.
   *)
  val mapLeft  : ('ldom -> 'lrng)
               -> ('ldom, 'rdom) either -> ('lrng, 'rdom) either [@@prototype "mapLeft f sm"]

  (**
   * maps the function `f` over the contents of right values and acts as the
   * identity on left values.
   *)
  val mapRight : ('rdom -> 'rrng)
               -> ('ldom, 'rdom) either -> ('ldom, 'rrng) either [@@prototype "mapRight f sm"]

  (**
   * applies `fl` to the contents of left values and `fr` to the contents
   * of right values.
   *)
  val app : ('left -> unit) * ('right -> unit)
          -> ('left, 'right) either
            -> unit [@@prototype "app (fl, fr) sm"]

  (**
   * applies `f` to the contents of left values and ignores right values.
   *)
  val appLeft  : ('left -> unit) -> ('left, 'right) either -> unit
      [@@prototype "appLeft f sm"]

  (**
   * applies `f` to the contents of right values and ignores left values.
   *)
  val appRight : ('right -> unit) -> ('left, 'right) either -> unit
      [@@prototype "appRight f sm"]

  (**
   * computes `fx (v, init)`, where `v` is the contents of `sm` and `fx`
   * is either `fl` (if `sm` is a left value) or `fr` (if `sm` is a right
   * value).
   *)
  val fold : ('left * 'b -> 'b) * ('right * 'b -> 'b)
           -> 'b -> ('left, 'right) either -> 'b [@@prototype "fold (fl, fr) init sm"]

  (** projects out the contents of `sm`. *)
  val proj : ('a, 'a) either -> 'a [@@method] [@@prototype "proj sm"]

  (**
   * partitions the list of sum values into a list of left values and a
   * list of right values.
   *)
  val partition : (('left, 'right) either) list -> ('left list * 'right list)
      [@@prototype "partition sms"]
end
[@@description "Values that are one of two types."]
[@@specified "basis+"]

(*) End either.sig
