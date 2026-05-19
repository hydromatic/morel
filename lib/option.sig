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
 * The Standard ML Basis Library has an Option structure.
 * There is no OPTION signature, but we define it here for
 * validation purposes.
 *)
(**
 * The `Option` structure provides the `option` type `'a option` whose
 * values are either `NONE` (absent) or `SOME v` (present), along with
 * operations for creating, examining, and transforming optional values.
 *)
signature OPTION =
sig

  (**
   * The type `option` provides a distinction between some value and no
   * value, and is often used for representing the result of partially
   * defined functions. It can be viewed as a typed version of the C
   * convention of returning a NULL pointer to indicate no value.
   *)
  datatype 'a option = NONE | SOME of 'a
  (** is raised by `valOf` when applied to `NONE`. *)
  exception Option

  (**
   * returns `v` if `opt` is `SOME (v)`; otherwise
   * returns `a`.
   *)
  val getOpt : 'a option * 'a -> 'a [@@method] [@@prototype "getOpt (opt, a)"]

  (**
   * returns `true` if `opt` is `SOME v`; otherwise returns
   * `false`.
   *)
  val isSome : 'a option -> bool [@@method] [@@prototype "isSome opt"]

  (**
   * returns `v` if `opt` is `SOME v`, otherwise raises
   * `Option`.
   *)
  val valOf : 'a option -> 'a [@@method] [@@prototype "valOf opt"]

  (** returns `SOME a` if `f(a)` is `true`, `NONE` otherwise. *)
  val filter : ('a -> bool) -> 'a -> 'a option [@@prototype "filter f a"]

  (** maps `NONE` to `NONE` and `SOME v` to `v`. *)
  val `join` : 'a option option -> 'a option [@@prototype "join opt"]

  (**
   * applies the function `f` to the value `v` if `opt` is
   * `SOME v`, and otherwise does nothing.
   *)
  val app : ('a -> unit) -> 'a option -> unit [@@prototype "app f opt"]

  (** maps `NONE` to `NONE` and `SOME v` to `SOME (f v)`. *)
  val map : ('a -> 'b) -> 'a option -> 'b option [@@prototype "map f opt"]

  (** maps `NONE` to `NONE` and `SOME v` to `f(v)`. *)
  val mapPartial : ('a -> 'b option)
                   -> 'a option -> 'b option [@@prototype "mapPartial f opt"]

  (**
   * returns `NONE` if `g(a)` is `NONE`; otherwise, if
   * `g(a)` is `SOME v`, it returns `SOME (f v)`.
   *)
  val compose : ('a -> 'b) * ('c -> 'a option)
                -> 'c -> 'b option [@@prototype "compose (f, g) a"]

  (**
   * returns `NONE` if `g(a)` is `NONE`;
   * otherwise, if `g(a)` is `SOME v`, returns `f(v)`.
   *)
  val composePartial : ('a -> 'b option) * ('c -> 'a option)
                       -> 'c -> 'b option [@@prototype "composePartial (f, g) a"]
end
[@@description "Optional values."]

(*) End option.sig
