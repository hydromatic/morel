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
(**
 * The `Fn` structure provides combinators for working with function values,
 * including application, composition, currying, and fixpoint operators.
 *)
signature FN =
sig

  (**
   * returns the value `x`. (`id` is the polymorphic identity function.)
   *)
  val id       : 'a -> 'a [@@prototype "id x"]

  (** returns the value `x`. *)
  val const    : 'a -> 'b -> 'a [@@prototype "const x y"]

  (**
   * applies the function `f` to `x`. Thus, it is equivalent to `f x`.
   *)
  val apply    : ('a -> 'b) * 'a -> 'b [@@prototype "apply (f, x)"]

  (**
   * is the function composition of `f` and `g`. Thus, `(f o g) a`
   * is equivalent to `f (g a)`. This function is the same as the global
   * `o` operator and is also part of the `General` structure.
   *)
  val o        : ('b -> 'c) * ('a -> 'b) -> ('a -> 'c) [@@prototype "f o g"]

  (**
   * is equivalent to `f (x, y)`; i.e., `curry f` transforms
   * the binary function `f` into curried form.
   *)
  val curry    : ('a * 'b -> 'c) -> ('a -> 'b -> 'c) [@@prototype "curry f x y"]

  (**
   * is equivalent to `f x y`; i.e., `uncurry f` transforms the curried
   * function `f` into a binary function. This function is the inverse of
   * `curry`.
   *)
  val uncurry  : ('a -> 'b -> 'c) -> ('a * 'b -> 'c)
      [@@prototype "ucurry f (x, y)"]

  (**
   * is equivalent to `f (y, x)`; i.e., `flip f` flips the argument order
   * of the binary function `f`.
   *)
  val flip     : ('a * 'b -> 'c) -> ('b * 'a -> 'c)
      [@@prototype "flip f (x, y)"]

  (**
   * returns the `n`-fold composition of `f`. If `n` is zero, then
   * `repeat n f` returns the identity function. If `n` is negative, then
   * it raises the exception `Domain`.
   *)
  val repeat   : int -> ('a -> 'a) -> ('a -> 'a) [@@prototype "repeat n f"]

(* TODO support eqtype in signatures
  (**
   * returns whether `a` is equal to `b`. It is a curried version of the
   * polymorphic equality function (`=`).
   *)
  val equal    : ''a -> ''a -> bool [@@prototype "equal a b"]
  (**
   * returns whether `a` is not equal to `b`. It is a curried version of
   * the polymorphic inequality function (`<>`).
   *)
  val notEqual : ''a -> ''a -> bool
*) [@@prototype "notEqual a b"]
  (**
   * returns whether `a` is equal to `b`. It is a curried version of the
   * polymorphic equality function (`=`).
   *)
  val equal    : 'a -> 'a -> bool [@@prototype "equal a b"]

  (**
   * returns whether `a` is not equal to `b`. It is a curried version of
   * the polymorphic inequality function (`<>`).
   *)
  val notEqual : 'a -> 'a -> bool [@@prototype "notEqual a b"]
end
[@@description "Higher-order function combinators."]
[@@specified "basis+"]

(*) End fn.sig
