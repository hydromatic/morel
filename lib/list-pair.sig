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
 * The LIST_PAIR signature, per the Standard ML Basis Library.
 *)
(**
 * The `ListPair` structure provides operations for working with two lists
 * simultaneously. Operations whose names do not end in `Eq` silently
 * ignore excess elements of the longer list; those ending in `Eq` raise
 * `UnequalLengths` when the lists differ in length.
 *)
signature LIST_PAIR =
sig

  (**
   * combines the two lists *l1* and *l2* into a list of
   * pairs, with the first element of each list comprising the first
   * element of the result, the second elements comprising the second
   * element of the result, and so on.  If the lists are of unequal
   * lengths, `zip` ignores the excess elements from the tail of the longer
   * one.
   *)
  val zip    : 'a list * 'b list -> ('a * 'b) list [@@prototype "zip (l1, l2)"]
  (**
   * returns a pair of lists formed by splitting the elements of
   * *l*. This is the inverse of `zip` for equal length lists.
   *)
  val unzip  : ('a * 'b) list -> 'a list * 'b list [@@prototype "unzip l"]
  (**
   * maps the function *f* over the list of pairs of
   * elements generated from left to right from the lists *l1* and *l2*,
   * returning the list of results.  If the lists are of unequal lengths,
   * ignores the excess elements from the tail of the longer one.  It is
   * equivalent to:
   *
   * <pre>List.map f (zip (l1, l2))</pre>
   *
   * <p>ignoring possible side effects of the function *f*.
   *)
  val map    : ('a * 'b -> 'c)   -> 'a list * 'b list -> 'c list
      [@@prototype "map f (l1, l2)"]
  (**
   * applies the function *f* to the list of pairs of
   * elements generated from left to right from the lists *l1* and *l2*. If
   * the lists are of unequal lengths, ignores the excess elements from the
   * tail of the longer one. It is equivalent to:
   *
   * <pre>List.app f (zip (l1, l2))</pre>
   *
   * <p>ignoring possible side effects of the function *f*.
   *)
  val app    : ('a * 'b -> unit) -> 'a list * 'b list -> unit
      [@@prototype "app f (l1, l2)"]
  (**
   * provides short-circuit testing of a predicate over a pair of lists.
   *
   * <p>It is equivalent to:
   * <pre>List.all f (zip (l1, l2))</pre>
   *)
  val all    : ('a * 'b -> bool) -> 'a list * 'b list -> bool
      [@@prototype "all f (l1, l2)"]
  (**
   * provides short-circuit testing of a predicate over a pair of lists.
   *
   * <p>It is equivalent to:
   * <pre>List.exists f (zip (l1, l2))</pre>
   *)
  val `exists` : ('a * 'b -> bool) -> 'a list * 'b list -> bool
      [@@prototype "exists f (l1, l2)"]
  (**
   * returns the result of folding the function *f*
   * in the specified direction over the pair of lists *l1* and *l2*
   * starting with the value *init*.  It is equivalent to:
   *
   * <pre>List.foldr f' init (zip (l1, l2))</pre>
   *
   * where *f'* is `fn ((a,b),c) => f(a,b,c)` and ignoring possible
   * side effects of the function *f*.
   *)
  val foldr  : ('a * 'b * 'c -> 'c) -> 'c -> 'a list * 'b list -> 'c
      [@@prototype "foldr f init (l1, l2)"]
  (**
   * returns the result of folding the function *f*
   * in the specified direction over the pair of lists *l1* and *l2*
   * starting with the value *init*.  It is equivalent to:
   *
   * <pre>List.foldl f' init (zip (l1, l2))</pre>
   *
   * where *f'* is `fn ((a,b),c) => f(a,b,c)` and ignoring possible
   * side effects of the function *f*.
   *)
  val foldl  : ('a * 'b * 'c -> 'c) -> 'c -> 'a list * 'b list -> 'c
      [@@prototype "foldl f init (l1, l2)"]
  (**
   * returns `true` if *l1* and *l2* have equal length
   * and all pairs of elements satisfy the predicate *f*. That is, the
   * expression is equivalent to:
   *
   * <pre>
   * (List.length l1 = List.length l2) andalso
   * (List.all f (zip (l1, l2)))
   * </pre>
   *
   * <p>This function does not appear to have any nice algebraic relation
   * with the other functions, but it is included as providing a useful
   * notion of equality, analogous to the notion of equality of lists over
   * equality types.
   *
   * <p>**Implementation note:**
   *
   * <p>The implementation is simple:
   *
   * <pre>
   * fun allEq p ([], []) = true
   *   | allEq p (x::xs, y::ys) = p(x,y) andalso allEq p (xs,ys)
   *   | allEq _ _ = false
   * </pre>
   *)

  val allEq    : ('a * 'b -> bool) -> 'a list * 'b list -> bool
      [@@prototype "allEq f (l1, l2)"]
  (**
   * is raised by those functions that require arguments
   * of identical length.
   *)

  exception UnequalLengths
  (**
   * combines the two lists *l1* and *l2* into a list of
   * pairs, with the first element of each list comprising the first
   * element of the result, the second elements comprising the second
   * element of the result, and so on.  If the lists are of unequal
   * lengths, `zipEq` raises the exception `UnequalLengths`.
   *)

  val zipEq    : ('a list * 'b list) -> ('a * 'b) list
      [@@prototype "zipEq (l1, l2)"]
  (**
   * maps the function *f* over the list of pairs of
   * elements generated from left to right from the lists *l1* and *l2*,
   * returning the list of results.  If the lists are of unequal lengths,
   * raises `UnequalLengths`.  It is equivalent to:
   *
   * <pre>List.map f (zipEq (l1, l2))</pre>
   *
   * <p>ignoring possible side effects of the function *f*.
   *)
  val mapEq    : ('a * 'b -> 'c) -> 'a list * 'b list -> 'c list
      [@@prototype "mapEq f (l1, l2)"]
  (**
   * applies the function *f* to the list of pairs of
   * elements generated from left to right from the lists *l1* and *l2*. If
   * the lists are of unequal lengths, raises `UnequalLengths`. It is
   * equivalent to:
   *
   * <pre>List.app f (zipEq (l1, l2))</pre>
   *
   * <p>ignoring possible side effects of the function *f*.
   *)
  val appEq    : ('a * 'b -> unit) -> 'a list * 'b list -> unit
      [@@prototype "appEq f (l1, l2)"]
  (**
   * returns the result of folding the function
   * *f* in the specified direction over the pair of lists *l1* and *l2*
   * starting with the value *init*.  It is equivalent to:
   *
   * <pre>List.foldr f' init (zipEq (l1, l2))</pre>
   *
   * where *f'* is `fn ((a,b),c) => f(a,b,c)` and ignoring possible
   * side effects of the function *f*.
   *)
  val foldrEq  : ('a * 'b * 'c -> 'c) -> 'c -> 'a list * 'b list -> 'c
      [@@prototype "foldrEq f init (l1, l2)"]
  (**
   * returns the result of folding the function
   * *f* in the specified direction over the pair of lists *l1* and *l2*
   * starting with the value *init*.  It is equivalent to:
   *
   * <pre>List.foldl f' init (zipEq (l1, l2))</pre>
   *
   * where *f'* is `fn ((a,b),c) => f(a,b,c)` and ignoring possible
   * side effects of the function *f*.
   *)
  val foldlEq  : ('a * 'b * 'c -> 'c) -> 'c -> 'a list * 'b list -> 'c
      [@@prototype "foldlEq f init (l1, l2)"]
end
[@@description "Operations on pairs of lists."]

(*) End list-pair.sig
