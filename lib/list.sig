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
 * The LIST signature, per the Standard ML Basis Library.
 *)
(**
 * The `List` structure provides the `list` type and a comprehensive
 * set of operations for constructing, examining, and transforming
 * singly-linked lists. Many operations are provided in both left-to-right
 * and right-to-left variants.
 *)
signature LIST =
sig

  (** is the type of polymorphic singly-linked lists. *)
  datatype 'a list = nil | `::` of 'a * 'a list
  (**
   * is raised by operations that require a non-empty list when given an
   * empty list.
   *)
  exception Empty

  (** returns `true` if the list `l` is empty. *)
  val null : 'a list -> bool [@@method] [@@prototype "null l"]

  (** returns the number of elements in the list `l`. *)
  val length : 'a list -> int [@@method] [@@prototype "length l"]

  (** returns the list that is the concatenation of `l1` and `l2`. *)
  val @ : 'a list * 'a list -> 'a list [@@prototype "l1 @ l2"]

  (**
   * returns the first element of `l`. Raises `Empty` if `l` is
   * `nil`.
   *)
  val hd : 'a list -> 'a [@@method] [@@prototype "hd l"]

  (**
   * returns all but the first element of `l`. Raises `Empty` if `l`
   * is `nil`.
   *)
  val tl : 'a list -> 'a list [@@method] [@@prototype "tl l"]

  (**
   * returns the last element of `l`. Raises `Empty` if `l` is
   * `nil`.
   *)
  val last : 'a list -> 'a [@@method] [@@prototype "last l"]

  (**
   * returns `NONE` if the `list` is empty, and `SOME (hd l, tl
   * l)` otherwise. This function is particularly useful for creating value
   * readers from lists of characters. For example, `Int.scan StringCvt.DEC
   * getItem` has the type `(int, char list) StringCvt.reader` and can be
   * used to scan decimal integers from lists of characters.
   *)
  val getItem : 'a list -> ('a * 'a list) option
      [@@method] [@@prototype "getItem l"]

  (**
   * returns the `i`(th) element of the list `l`, counting
   * from 0. Raises `Subscript` if `i` < 0 or `i` &ge; `length l`. We have
   * `nth(l, 0)` = `hd l`, ignoring exceptions.
   *)
  val nth : 'a list * int -> 'a [@@method] [@@prototype "nth (l, i)"]

  (**
   * returns the only element of list `l`. Raises `Empty` if `l` is empty,
   * `Size` if `l` has more than one element.
   *)
  val only : 'a list -> 'a [@@method] [@@prototype "only l"]

  (**
   * returns the first `i` elements of the list `l`. Raises
   * `Subscript` if `i` < 0 or `i` > `length l`. We have `take(l, length
   * l)` = `l`.
   *)
  val `take` : 'a list * int -> 'a list [@@method] [@@prototype "take (l, i)"]

  (**
   * returns what is left after dropping the first `i`
   * elements of the list `l`. Raises `Subscript` if `i` < 0 or `i` >
   * `length l`.
   *
   * <p>It holds that `take(l, i) @ drop(l, i)` = `l` when 0 &le;
   * `i` &le; `length l`. We also have `drop(l, length l)` = `[]`.
   *)
  val drop : 'a list * int -> 'a list [@@method] [@@prototype "drop (l, i)"]

  (** returns a list consisting of `l`'s elements in reverse order. *)
  val rev : 'a list -> 'a list [@@method] [@@prototype "rev l"]

  (**
   * returns the list that is the concatenation of all the lists
   * in `l` in order. `concat [l1, l2, ... ln]` = `l1 @ l2 @ ... @ ln`
   *)
  val concat : 'a list list -> 'a list [@@prototype "concat l"]

  (** returns `(rev l1) @ l2`. *)
  val revAppend : 'a list * 'a list -> 'a list
      [@@method] [@@prototype "revAppend (l1, l2)"]

  (** applies `f` to the elements of `l`, from left to right. *)
  val app : ('a -> unit) -> 'a list -> unit [@@prototype "app f l"]

  (**
   * applies `f` to each element of `l` from left to right,
   * returning the list of results.
   *)
  val map : ('a -> 'b) -> 'a list -> 'b list [@@prototype "map f l"]

  (**
   * applies `f` to each element of `l` from left to
   * right, returning a list of results, with `SOME` stripped, where `f`
   * was defined. `f` is not defined for an element of `l` if `f` applied
   * to the element returns `NONE`. The above expression is equivalent to:
   *
   * <pre>((map valOf) o (filter isSome) o (map f)) b</pre>
   *)
  val mapPartial : ('a -> 'b option) -> 'a list -> 'b list
      [@@prototype "mapPartial f l"]

  (**
   * applies `f` to each element `x` of the list `l`, from left
   * to right, until `f x` evaluates to `true`. It returns `SOME (x)` if
   * such an `x` exists; otherwise it returns `NONE`.
   *)
  val find : ('a -> bool) -> 'a list -> 'a option [@@prototype "find f l"]

  (**
   * applies `f` to each element `x` of `l`, from left to
   * right, and returns the list of those `x` for which `f x` evaluated to
   * `true`, in the same order as they occurred in the argument list.
   *)
  val filter : ('a -> bool) -> 'a list -> 'a list [@@prototype "filter f l"]

  (**
   * applies `f` to each element `x` of `l`, from left to
   * right, and returns a pair `(pos, neg)` where `pos` is the list of
   * those `x` for which `f x` evaluated to `true`, and `neg` is the list
   * of those for which `f x` evaluated to `false`. The elements of `pos`
   * and `neg` retain the same relative order they possessed in `l`.
   *)
  val partition : ('a -> bool)
                    -> 'a list -> 'a list * 'a list [@@prototype "partition f l"]

  (**
   * returns `f(xn, ... , f(x2, f(x1,
   * init))...)` or `init` if the list is empty.
   *)
  val foldl : ('a * 'b -> 'b)
              -> 'b
              -> 'a list
              -> 'b [@@prototype "foldl f init [x1, x2, ..., xn]"]

  (**
   * returns `f(x1, f(x2, ..., f(xn,
   * init)...))` or `init` if the list is empty.
   *)
  val foldr : ('a * 'b -> 'b)
              -> 'b
              -> 'a list
              -> 'b [@@prototype "foldr f init [x1, x2, ..., xn]"]

  (**
   * applies `f` to each element `x` of the list `l`, from
   * left to right, until `f(x)` evaluates to `true`; it returns `true` if
   * such an `x` exists and `false` otherwise.
   *)
  val `exists` : ('a -> bool) -> 'a list -> bool [@@prototype "exists f l"]

  (**
   * applies `f` to each element `x` of the list `l`, from left
   * to right, until `f(x)` evaluates to `false`; it returns `false` if
   * such an `x` exists and `true` otherwise. It is equivalent to
   * `not(exists (not o f) l))`.
   *)
  val all : ('a -> bool) -> 'a list -> bool [@@prototype "all f l"]

  (**
   * returns a list of length `n` equal to `[f(0), f(1),
   * ..., f(n-1)]`, created from left to right. Raises `Size` if `n` < 0.
   *)
  val tabulate : int * (int -> 'a) -> 'a list [@@prototype "tabulate (n, f)"]

  (**
   * performs lexicographic comparison of the two
   * lists using the given ordering `f` on the list elements.
   *)
  val collate : ('a * 'a -> `order`)
                  -> 'a list * 'a list -> `order` [@@prototype "collate f (l1, l2)"]

  (* Morel extensions *)
  (**
   * returns the list that is the concatenation of all the lists
   * in `l` in order. `concat [l1, l2, ... ln]` = `l1 @ l2 @ ... @ ln`
   *)
  val `except` : 'a list list -> 'a list
      [@@specified "morel"] [@@prototype "except l"]

  (**
   * returns the list that is the concatenation of all the
   * lists in `l` in order. `concat [l1, l2, ... ln]` = `l1 @ l2 @ ... @
   * ln`
   *)
  val `intersect` : 'a list list -> 'a list
      [@@specified "morel"] [@@prototype "intersect l"]

  (**
   * applies the function `f` to the elements of the argument
   * list `l`, supplying the list index and element as arguments to each
   * call.
   *)
  val mapi : (int * 'a -> 'b) -> 'a list -> 'b list [@@prototype "mapi f l"]
end
[@@description "Polymorphic singly-linked lists."]

(*) End list.sig
