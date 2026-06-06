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
 * The BOOL signature, per the Standard ML Basis Library.
 *)
(** The `Bool` structure provides the boolean type and associated operations. *)
signature BOOL =
sig

  (** is the type of boolean values `true` and `false`. *)
  datatype bool = `false` | `true`
  (** returns the logical inverse of `b`. *)
  val not : bool -> bool [@@method] [@@prototype "not b"] [@@syntax "prefix"]
  (**
   * returns the string representation of `b`, either "true" or "false".
   *)
  val toString : bool -> string [@@method] [@@prototype "toString b"]
  (**
   * scans a `bool` value from the string `s`. Returns `SOME (true)` if
   * `s` is "true", `SOME (false)` if `s` is "false", and `NONE` otherwise.
   *)
  val fromString : string -> bool option [@@prototype "fromString s"]
  (** returns the logical conjunction of `b1` and `b2`. Unlike the `andalso`
   * keyword, always evaluates both arguments. *)
  val `andalso` : bool * bool -> bool
      [@@prototype "andalso (b1, b2)"] [@@syntax "infix"]
  (** returns the logical disjunction of `b1` and `b2`. Unlike the `orelse`
   * keyword, always evaluates both arguments. *)
  val `orelse` : bool * bool -> bool
      [@@prototype "orelse (b1, b2)"] [@@syntax "infix"]
  (** returns the logical implication of `b1` and `b2`; that is, false only if
   * `b1` is true and `b2` is false. Unlike the `implies` keyword, always
   * evaluates both arguments. *)
  val `implies` : bool * bool -> bool
      [@@prototype "implies (b1, b2)"] [@@syntax "infix"]
  (** returns true if `b1` and `b2` are equal. *)
  val `=`  : bool * bool -> bool [@@prototype "b1 = b2"] [@@syntax "infix"]
  (** returns true if `b1` and `b2` are not equal. *)
  val `<>` : bool * bool -> bool [@@prototype "b1 <> b2"] [@@syntax "infix"]
  (** returns true if `b1` is false and `b2` is true. *)
  val `<`  : bool * bool -> bool [@@prototype "b1 < b2"] [@@syntax "infix"]
  (** returns true if `b1` is true and `b2` is false. *)
  val `>`  : bool * bool -> bool [@@prototype "b1 > b2"] [@@syntax "infix"]
end
[@@description "Boolean values and operations."]

(*) End bool.sig
