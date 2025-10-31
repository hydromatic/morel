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
signature VECTOR =
sig
  eqtype 'a vector

  (* The maximum length of vectors supported by this implementation. *)
  val maxLen : int

  (* Creates a vector from a list, where each element at index i in the
   * list becomes the element at index i in the resulting vector. *)
  val fromList : 'a list -> 'a vector

  (* Creates a vector of n elements, where the elements are defined in
   * order of increasing index by applying f to the element's index. *)
  val tabulate : int * (int -> 'a) -> 'a vector

  (* Returns the number of elements in a vector. *)
  val length : 'a vector -> int

  (* Returns the i(th) element of the vector vec.
   * Raises Subscript if the index is out of bounds. *)
  val sub : 'a vector * int -> 'a

  (* Returns a new vector, identical to vec, except the i(th) element
   * of vec is set to x. *)
  val update : 'a vector * int * 'a -> 'a vector

  (* Combines multiple vectors from a list into a single vector. *)
  val concat : 'a vector list -> 'a vector

  (* Applies a function to each element and its index in left-to-right
   * order for side effects only. *)
  val appi : (int * 'a -> unit) -> 'a vector -> unit

  (* Applies a function to each element in left-to-right order for
   * side effects only. *)
  val app : ('a -> unit) -> 'a vector -> unit

  (* Produces a new vector by mapping the function f from left to right
   * over the argument vector while supplying indices. *)
  val mapi : (int * 'a -> 'b) -> 'a vector -> 'b vector

  (* Produces a new vector by applying a function to each element from
   * left to right. *)
  val map : ('a -> 'b) -> 'a vector -> 'b vector

  (* Accumulates a result by applying a function to each indexed element
   * from left to right. *)
  val foldli : (int * 'a * 'b -> 'b) -> 'b -> 'a vector -> 'b

  (* Accumulates a result by applying a function to each indexed element
   * from right to left. *)
  val foldri : (int * 'a * 'b -> 'b) -> 'b -> 'a vector -> 'b

  (* Accumulates a result by processing elements left to right without
   * index information. *)
  val foldl : ('a * 'b -> 'b) -> 'b -> 'a vector -> 'b

  (* Accumulates a result by processing elements right to left without
   * index information. *)
  val foldr : ('a * 'b -> 'b) -> 'b -> 'a vector -> 'b

  (* Searches for the first element satisfying a predicate, returning
   * both its index and value. *)
  val findi : (int * 'a -> bool) -> 'a vector -> (int * 'a) option

  (* Searches for the first element satisfying a predicate, returning
   * the element itself. *)
  val find : ('a -> bool) -> 'a vector -> 'a option

  (* Returns true if any element satisfies the predicate. *)
  val `exists` : ('a -> bool) -> 'a vector -> bool

  (* Returns true if all elements satisfy the predicate. *)
  val all : ('a -> bool) -> 'a vector -> bool

  (* Performs lexicographic comparison of two vectors using a provided
   * element ordering function. *)
  val collate : ('a * 'a -> `order`) -> 'a vector * 'a vector -> `order`
end

(*) End vector.sig
