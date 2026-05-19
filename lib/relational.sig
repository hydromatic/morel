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
 * The RELATIONAL signature, a Morel extension.
 *)
(**
 * The `Relational` structure provides aggregation, comparison, and set
 * operations that are used in Morel `from` expressions. These functions
 * extend Standard ML with relational-algebra capabilities.
 *)
signature RELATIONAL =
sig

  (**
   * wraps a value so that it sorts in descending order when used with
   * `Relational.compare`.
   *)
  datatype 'a descending = DESC of 'a

  (**
   * returns `LESS`, `EQUAL`, or `GREATER` according to
   * whether its first argument is less than, equal to, or greater than the
   * second.
   *
   * Comparisons are based on the structure of the type `α`.
   * Primitive types are compared using their natural order;
   * Option types compare with NONE last;
   * Tuple types compare lexicographically;
   * Record types compare lexicographically, with the fields
   * compared in alphabetical order;
   * List values compare lexicographically;
   * Bag values compare lexicographically, the elements appearing
   * in an order that is arbitrary but is consistent for each
   * particular value.
   *)
  val compare : 'a * 'a -> `order` [@@prototype "compare (x, y)"]

  (**
   * returns the number of elements in `list`. Often used with
   * `group`, for example `from e in emps group e.deptno compute countId =
   * count`.
   *)
  val count : 'a bag -> int [@@method] [@@prototype "count list"]

  (**
   * returns whether the list is empty, for example `from d in
   * depts where empty (from e where e.deptno = d.deptno)`.
   *)
  val empty : 'a bag -> bool [@@method] [@@prototype "empty list"]

  (**
   * computes a fixed point, starting with `initialList` and calling
   * `listUpdate (prevList, newList)` each iteration, terminating the
   * iteration when it returns `newList`.
   *)
  val iterate : 'a bag -> ('a bag * 'a bag -> 'a bag) -> 'a bag
      [@@method] [@@prototype "iterate initialList listUpdate"]

  (**
   * returns the greatest element of `list`. Often used with
   * `group`, for example `from e in emps group e.deptno compute maxId =
   * max of e.id`.
   *)
  val max : 'a bag -> 'a [@@method] [@@prototype "max list"]

  (**
   * returns the least element of `list`. Often used with
   * `group`, for example `from e in emps group e.deptno compute minId =
   * min of e.id`.
   *)
  val min : 'a bag -> 'a [@@method] [@@prototype "min list"]

  (**
   * returns whether the list has at least one element, for
   * example `from d in depts where nonEmpty (from e where e.deptno =
   * d.deptno)`.
   *)
  val nonEmpty : 'a bag -> bool [@@method] [@@prototype "nonEmpty list"]

  (**
   * returns the sole element of list, for example `from e in
   * emps yield only (from d where d.deptno = e.deptno)`.
   *)
  val only : 'a bag -> 'a [@@method] [@@prototype "only list"]

  (**
   * returns the sum of the elements of `list`. Often used with
   * `group`, for example `from e in emps group e.deptno compute sumId =
   * sum of e.id`.
   *)
  val sum : 'a bag -> 'a [@@method] [@@prototype "sum list"]
end
[@@description "Relational algebra operations for Morel queries."]
[@@specified "morel"]

(*) End relational.sig
