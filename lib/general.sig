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
 * The INTEGER signature, per the Standard ML Basis Library.
 *)
(**
 * The `General` structure provides the fundamental types `unit`, `exn`,
 * and `order`, the standard exceptions raised by the runtime, and a few
 * utility functions. Its contents are available without qualification in
 * every Morel program.
 *)
signature GENERAL =
sig

  (**
   * is the type that contains the single value `()`. It is used as the
   * result type of functions called for side effects.
   *)
  eqtype unit

  (**
   * is the type of exceptions. Every exception constructor creates a value
   * of this type, and the `raise` and `handle` constructs operate on it.
   *)
  type exn = exn

  (** is raised when pattern matching fails in a `val` binding. *)
  exception Bind

  (**
   * is raised when pattern matching fails in a `case` expression or
   * function application.
   *)
  exception Match

  (**
   * is raised by `Char.chr` when given an integer outside the valid range.
   *)
  exception Chr

  (** is raised on integer division by zero. *)
  exception Div

  (** is raised when a function is applied outside its domain. *)
  exception Domain

  (**
   * is a general-purpose exception carrying a descriptive message string.
   *)
  exception Fail of string

  (**
   * is raised when an integer arithmetic result is too large to represent.
   *)
  exception Overflow

  (**
   * is raised when a size argument is negative or exceeds the maximum
   * allowed.
   *)
  exception Size

  (** is raised when an invalid source span is supplied. *)
  exception Span

  (** is raised when a sequence index is out of bounds. *)
  exception Subscript

  (**
   * returns a name for the exception `ex`. The name returned may be that
   * of any exception constructor aliasing with `ex`. For instance,
   *
   * <pre>let exception E1; exception E2 = E1 in exnName E2 end</pre>
   *
   * might evaluate to "E1" or "E2".
   *)
  val exnName : exn -> string [@@prototype "exnName ex"]

  (**
   * returns a message corresponding to exception `ex`. The precise format
   * of the message may vary between implementations and locales, but will
   * at least contain the string `exnName ex`.
   *
   * <p>**Example:**
   *
   * <pre>exnMessage Div = "Div"
   * exnMessage (OS.SysErr ("No such file", NONE)) =
   *   "OS.SysErr "No such file""</pre>
   *)
  val exnMessage : exn -> string [@@prototype "exnMessage ex"]

  (* The type for ordering values. *)
  datatype `order` = LESS | EQUAL | GREATER
(*
  val ! : 'a ref -> 'a
  val := : 'a ref * 'a -> unit
*)
  (**
   * is the function composition of `f` and `g`. Thus, `(f o g) a`
   * is equivalent to `f (g a)`.
   *)
  val o : ('b -> 'c) * ('a -> 'b) -> 'a -> 'c [@@prototype "f o g"]

  (**
   * returns `a`. It provides a notational shorthand for evaluating `a`,
   * then `b`, before returning the value of `a`.
   *)
  val before : 'a * unit -> 'a [@@prototype "a before b"]

  (**
   * always returns `unit`. The function evaluates its argument
   * but throws away the value.
   *)
  val ignore : 'a -> unit [@@prototype "ignore x"]
end
[@@description "Basic types, exceptions, and utility functions."]

(*) End general.sig
