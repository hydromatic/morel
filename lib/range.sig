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
 * The RANGE signature, a Morel extension.
 *)
(**
 * The `Range` structure represents contiguous intervals of values of an
 * ordered type, including open, closed, and half-open intervals, as well as
 * unbounded intervals.
 *
 * A range is a value of the `'a range` datatype, which has ten constructors:
 * `ALL`, `AT_LEAST`, `AT_MOST`, `CLOSED`, `CLOSED_OPEN`, `GREATER_THAN`,
 * `LESS_THAN`, `OPEN`, `OPEN_CLOSED`, and `POINT`.
 *
 * Naming conventions follow Guava and standard mathematical notation:
 * CLOSED endpoints are inclusive `[a, b]`;
 * OPEN endpoints are exclusive `(a, b)`.
 *)
signature RANGE =
sig

  (**
   * represents a set of values as a normalized list of non-overlapping,
   * non-adjacent ranges.
   *)
  eqtype 'a continuous_set

  (**
   * represents a set of discrete values as a normalized list of
   * non-overlapping, non-adjacent ranges.
   *)
  eqtype 'a discrete_set

  (**
   * represents a contiguous interval of values of an ordered type.
   *
   * The constructors and their meanings are:
   * * `ALL`: all values (equivalent to (−∞, +∞))
   * * `AT_LEAST v`: `x >= v`
   * * `AT_MOST v`: `x <= v`
   * * `CLOSED (lo, hi)`: `x >= lo andalso x <= hi`
   * * `CLOSED_OPEN (lo, hi)`: `x >= lo andalso x < hi`
   * * `GREATER_THAN v`: `x > v`
   * * `LESS_THAN v`: `x < v`
   * * `OPEN (lo, hi)`: `x > lo andalso x < hi`
   * * `OPEN_CLOSED (lo, hi)`: `x > lo andalso x <= hi`
   * * `POINT v`: `x = v`
   *)
  datatype 'a range = ALL | AT_LEAST of 'a | AT_MOST of 'a | CLOSED of 'a * 'a | CLOSED_OPEN of 'a * 'a | GREATER_THAN of 'a | LESS_THAN of 'a | OPEN of 'a * 'a | OPEN_CLOSED of 'a * 'a | POINT of 'a

  (**
   * returns `true` if `x` is a member of discrete set `ds`.
   *
   * The ordering is implicit, derived from the type `α`.
   *)
  val contains : 'a discrete_set -> 'a -> bool
      [@@method] [@@prototype "contains ds x"]

  (**
   * enumerates all values in the discrete set `ds` and returns them as a bag.
   * The element type must be discrete (e.g., `int`, `char`, `bool`).
   * Raises an exception if any range is unbounded below and the type has no
   * minimum value (e.g., `LESS_THAN 5 : int range`).
   *)
  val toBag : 'a discrete_set -> 'a bag [@@method] [@@prototype "toBag ds"]

  (**
   * enumerates all values in the discrete set `ds` and returns them as a
   * list, in ascending order. The element type must be discrete (e.g.,
   * `int`, `char`, `bool`).
   * Raises an exception if any range is unbounded below and the type has no
   * minimum value (e.g., `LESS_THAN 5 : int range`).
   *)
  val toList : 'a discrete_set -> 'a list [@@method] [@@prototype "toList ds"]

  (**
   * normalizes `ranges` into a `continuous_set`. Overlapping and adjacent
   * ranges are merged, and the result is sorted by lower bound.
   *
   * The ordering is implicit, derived from the element type.
   *
   * ```
   * - val cs = Range.continuousSetOf [OPEN (1.0, 3.0), AT_LEAST 7.0];
   * val cs = CONTINUOUS_SET [OPEN (1.0,3.0),AT_LEAST 7.0] : real continuous_set
   * - cs.contains 2.0;
   * val it = true : bool
   * - cs.contains 1.0;
   * val it = false : bool
   * - cs.contains 8.5;
   * val it = true : bool
   * - cs.complement ();
   * val it = CONTINUOUS_SET [AT_MOST 1.0,CLOSED_OPEN (3.0,7.0)] : real continuous_set
   * ```
   *)
  val continuousSetOf : 'a range list -> 'a continuous_set
      [@@prototype "continuousSetOf ranges"]

  (**
   * normalizes `ranges` into a `discrete_set`. Overlapping and adjacent
   * ranges are merged (treating adjacent discrete values as mergeable), and
   * the result is sorted by lower bound.
   *
   * The ordering and discreteness are implicit, derived from the element type.
   *
   * ```
   * - val evens = Range.discreteSetOf [CLOSED (0, 2), CLOSED (4, 6), CLOSED (8, 10)];
   * val evens = DISCRETE_SET [CLOSED (0,2),CLOSED (4,6),CLOSED (8,10)] : int discrete_set
   * - evens.toList ();
   * val it = [0,1,2,4,5,6,8,9,10] : int list
   * ```
   *)
  val discreteSetOf : 'a range list -> 'a discrete_set
      [@@prototype "discreteSetOf ranges"]

  (**
   * concatenates the values in `ranges`. Within each range, values
   * appear in ascending order; ranges are concatenated in the order
   * they appear in the input, and duplicate values are preserved. To
   * eliminate duplicates, apply `distinct` to the result.
   *
   * The element type may be any ordered type, but a range over a
   * non-discrete type (e.g. `real`) is infinite unless it is a
   * `POINT`, and unbounded ranges over a discrete type (e.g.
   * `AT_LEAST 5 : int range`) are also infinite. Raises `Size` at
   * runtime if any range is infinite.
   *
   * ```
   * - Range.flatten [CLOSED (0, 3), POINT 10, CLOSED (2, 5)];
   * val it = [0,1,2,3,10,2,3,4,5] : int list
   * - Range.flatten [POINT 1.0, POINT 3.0, POINT 1.0];
   * val it = [1.0,3.0,1.0] : real list
   * ```
   *)
  val flatten : 'a range list -> 'a list [@@prototype "flatten ranges"]

  (** returns the list of ranges in the discrete set `ds`. *)
  val ranges : 'a discrete_set -> 'a range list
      [@@method] [@@prototype "ranges ds"]

  (**
   * returns the complement of discrete set `ds`: a discrete set containing all
   * values of the element type not in `ds`.
   *)
  val complement : 'a discrete_set -> 'a discrete_set
      [@@method] [@@prototype "complement ds"]
end
[@@description "Operations on ranges of ordered values."]
[@@specified "morel"]

(*) End range.sig
