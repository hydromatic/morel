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
 * The VECTOR signature, per the Standard ML Basis Library.
 *)
(**
 * The `Vector` structure provides the `vector` type and operations for
 * creating, examining, and transforming immutable fixed-length sequences
 * of elements.
 *)
signature VECTOR =
sig

  (**
   * is the type of immutable fixed-length arrays with elements of type
   * `'a`.
   *)
  eqtype 'a vector

  (**
   * returns the maximum length of vectors supported in this
   * implementation.
   *)
  val maxLen : int [@@prototype "maxLen"]

  (**
   * creates a new vector from `l`, whose length is `length l`
   * and with the `i`<sup>th</sup> element of `l` used as the
   * `i`<sup>th</sup> element of the vector. Raises `Size` if `maxLen` <
   * `n`.
   *)
  val fromList : 'a list -> 'a vector [@@prototype "fromList l"]

  (**
   * returns a vector of length `n` equal to `[f(0),
   * f(1), ..., f(n-1)]`, created from left to right. This is equivalent
   * to the expression
   *
   * <pre>fromList (List.tabulate (n, f))</pre>
   *
   * Raises `Size` if `n` < 0 or `maxLen` < `n`.
   *)
  val tabulate : int * (int -> 'a) -> 'a vector [@@prototype "tabulate (n, f)"]

  (** returns the number of elements in the vector `v`. *)
  val length : 'a vector -> int [@@method] [@@prototype "length v"]

  (**
   * returns the `i`<sup>th</sup> element of vector `vec`.
   * Raises `Subscript` if `i` < 0 or `size vec` &le; `i`.
   *)
  val sub : 'a vector * int -> 'a [@@method] [@@prototype "sub (vec, i)"]

  (**
   * returns a new vector, identical to `vec`, except
   * the `i`<sup>th</sup> element of `vec` is set to `x`. Raises
   * `Subscript` if `i` < 0 or `size vec` &le; `i`.
   *)
  val update : 'a vector * int * 'a -> 'a vector
      [@@prototype "update (vec, i, x)"]

  (**
   * returns the vector that is the concatenation of the vectors
   * in the list `l`.  Raises `Size` if the total length of these vectors
   * exceeds `maxLen`
   *)
  val concat : 'a vector list -> 'a vector [@@prototype "concat l"]

  (**
   * applies the function `f` to the elements of a vector in
   * left to right order (i.e., in order of increasing indices).
   *
   * <p>It is equivalent to
   * <pre>List.app f (foldri (fn (i,a,l) => (i,a)::l) [] vec)</pre>
   *)
  val appi : (int * 'a -> unit) -> 'a vector -> unit [@@prototype "appi f vec"]

  (**
   * applies the function `f` to the elements of a vector in
   * left to right order (i.e., in order of increasing indices).
   *
   * <p>It is equivalent to
   * <pre>List.app f (foldr (fn (a,l) => a::l) [] vec)</pre>
   *)
  val app : ('a -> unit) -> 'a vector -> unit [@@prototype "app f vec"]

  (**
   * applies the function `f` to the elements of the argument
   * vector `vec`, supplying the vector index and element as arguments to
   * each call.
   *
   * <p>It is equivalent to
   * <pre>fromList (List.map f (foldri (fn (i,a,l) => (i,a)::l) [] vec))</pre>
   *)
  val mapi : (int * 'a -> 'b) -> 'a vector -> 'b vector
      [@@prototype "mapi f vec"]

  (**
   * applies the function `f` to the elements of the argument
   * vector `vec`.
   *
   * <p>It is equivalent to
   * <pre>fromList (List.map f (foldr (fn (a,l) => a::l) [] vec))</pre>
   *)
  val map : ('a -> 'b) -> 'a vector -> 'b vector [@@prototype "map f vec"]

  (**
   * folds the function `f` over all the (index,
   * element) pairs of vector `vec`, left to right, using the initial value
   * `init`.
   *)
  val foldli : (int * 'a * 'b -> 'b) -> 'b -> 'a vector -> 'b
      [@@prototype "foldli f init vec"]

  (**
   * folds the function `f` over all the (index,
   * element) pairs of vector `vec`, right to left, using the initial value
   * `init`.
   *)
  val foldri : (int * 'a * 'b -> 'b) -> 'b -> 'a vector -> 'b
      [@@prototype "foldri f init vec"]

  (**
   * folds the function `f` over all the elements of
   * vector `vec`, left to right, using the initial value `init`.
   *)
  val foldl : ('a * 'b -> 'b) -> 'b -> 'a vector -> 'b
      [@@prototype "foldl f init vec"]

  (**
   * folds the function `f` over all the elements of
   * vector `vec`, right to left, using the initial value `init`.
   *)
  val foldr : ('a * 'b -> 'b) -> 'b -> 'a vector -> 'b
      [@@prototype "foldr f init vec"]

  (**
   * applies `f` to each element `x` and element index `i` of
   * the vector `vec`, from left to right, until `f(i, x)` evaluates to
   * `true`. It returns `SOME (i, x)` if such an `x` exists; otherwise it
   * returns `NONE`.
   *)
  val findi : (int * 'a -> bool) -> 'a vector -> (int * 'a) option
      [@@prototype "findi f vec"]

  (**
   * applies `f` to each element `x` of the vector `vec`, from
   * left to right, until `f(x)` evaluates to `true`. It returns `SOME (x)`
   * if such an `x` exists; otherwise it returns `NONE`.
   *)
  val find : ('a -> bool) -> 'a vector -> 'a option [@@prototype "find f vec"]

  (**
   * applies `f` to each element `x` of the vector `vec`,
   * from left to right (i.e., increasing indices), until `f(x)` evaluates
   * to `true`; it returns `true` if such an `x` exists and `false`
   * otherwise.
   *)
  val `exists` : ('a -> bool) -> 'a vector -> bool [@@prototype "exists f vec"]

  (**
   * applies `f` to each element `x` of the vector `vec`, from
   * left to right, until `f(x)` evaluates to `false`. It returns `false`
   * if such an `x` exists; otherwise it returns `true`. It is equivalent
   * to `not(exists (not o f) vec)`.
   *)
  val all : ('a -> bool) -> 'a vector -> bool [@@prototype "all f vec"]

  (**
   * performs lexicographic comparison of the two
   * vectors using the given ordering `f` on elements.
   *)
  val collate : ('a * 'a -> `order`) -> 'a vector * 'a vector -> `order`
      [@@prototype "collate f (v1, v2)"]
end
[@@description "Immutable fixed-length arrays."]

(*) End vector.sig
