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
 * The FN signature, a proposed addition to the standard basis
 * library.
 *)
signature FN =
sig
  (* Returns the value unchanged; the polymorphic identity function. *)
  val id       : 'a -> 'a

  (* Returns the first argument, ignoring the second;
   * useful for creating constant functions. *)
  val const    : 'a -> 'b -> 'a

  (* Applies a function to an argument;
   * equivalent to direct function application. *)
  val apply    : ('a -> 'b) * 'a -> 'b

  (* Composes two functions; (f o g) x evaluates to f (g x). *)
  val o        : ('b -> 'c) * ('a -> 'b) -> ('a -> 'c)

  (* Transforms a binary function into curried form,
   * taking arguments separately. *)
  val curry    : ('a * 'b -> 'c) -> ('a -> 'b -> 'c)

  (* Transforms a curried function into binary form,
   * taking a tuple argument. *)
  val uncurry  : ('a -> 'b -> 'c) -> ('a * 'b -> 'c)

  (* Reverses argument order for a binary function;
   * flip f (x, y) becomes f (y, x). *)
  val flip     : ('a * 'b -> 'c) -> ('b * 'a -> 'c)

  (* Returns the n-fold composition of a function;
   * raises Domain exception if n is negative. *)
  val repeat   : int -> ('a -> 'a) -> ('a -> 'a)

(* TODO support eqtype in signatures
  val equal    : ''a -> ''a -> bool
  val notEqual : ''a -> ''a -> bool
*)
  (* Curried version of the polymorphic equality operator
   * for functional composition. *)
  val equal    : 'a -> 'a -> bool

  (* Curried version of the polymorphic inequality operator
   * for functional composition. *)
  val notEqual : 'a -> 'a -> bool
end

(*) End fn.sig
