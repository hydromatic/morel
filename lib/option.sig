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
signature OPTION =
sig
  datatype 'a option = NONE | SOME of 'a
  exception Option

  (* Returns the value v if opt is SOME(v); otherwise returns the default
   * value a. Provides a default value when an option is empty. *)
  val getOpt : 'a option * 'a -> 'a

  (* Returns true if opt is SOME(v); otherwise returns false.
   * Checks whether an option contains a value. *)
  val isSome : 'a option -> bool

  (* Returns v if opt is SOME(v); otherwise raises the Option exception.
   * Extracts the value from an option or fails. *)
  val valOf : 'a option -> 'a

  (* Returns SOME(a) if f(a) is true and NONE otherwise.
   * Wraps a value in SOME only if it satisfies a predicate. *)
  val filter : ('a -> bool) -> 'a -> 'a option

  (* Maps NONE to NONE and SOME(v) to v.
   * Flattens a nested option type by one level. *)
  val `join` : 'a option option -> 'a option

  (* Applies the function f to the value v if opt is SOME(v), and otherwise
   * does nothing. Executes a side effect if an option contains a value. *)
  val app : ('a -> unit) -> 'a option -> unit

  (* Maps NONE to NONE and SOME(v) to SOME(f v).
   * Transforms the contained value without changing the option structure. *)
  val map : ('a -> 'b) -> 'a option -> 'b option

  (* Maps NONE to NONE and SOME(v) to f(v).
   * Applies a partial function to a contained value, flattening the result. *)
  val mapPartial : ('a -> 'b option)
                   -> 'a option -> 'b option

  (* Composes a total function with a partial function.
   * Returns NONE if the partial function produces NONE. *)
  val compose : ('a -> 'b) * ('c -> 'a option)
                -> 'c -> 'b option

  (* Composes two partial functions together.
   * Returns NONE if either function produces NONE. *)
  val composePartial : ('a -> 'b option) * ('c -> 'a option)
                       -> 'c -> 'b option
end

(*) End option.sig
