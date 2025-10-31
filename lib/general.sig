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
signature GENERAL =
sig
  (* The unit type; contains a single value (). *)
  eqtype unit

  (* The type of exceptions. *)
  type exn = exn

  (* Raised when pattern matching fails in val bindings. *)
  exception Bind

  (* Raised when pattern matching fails in case expressions and function
   * applications. *)
  exception Match

  (* Raised when character operations are given invalid arguments. *)
  exception Chr

  (* Raised on integer division by zero. *)
  exception Div

  (* Raised when a function is applied outside its domain. *)
  exception Domain

  (* General-purpose exception with a message string. *)
  exception Fail of string

  (* Raised on integer overflow. *)
  exception Overflow

  (* Raised when a size is too large or negative. *)
  exception Size

  (* Raised when a span is invalid. *)
  exception Span

  (* Raised when an index is out of bounds. *)
  exception Subscript

  (* Returns the name of an exception. *)
  val exnName : exn -> string

  (* Returns the message associated with an exception. *)
  val exnMessage : exn -> string

  (* The type for ordering values. *)
  datatype `order` = LESS | EQUAL | GREATER
(*
  val ! : 'a ref -> 'a
  val := : 'a ref * 'a -> unit
*)
  (* Function composition; (f o g) x equals f(g(x)). *)
  val o : ('b -> 'c) * ('a -> 'b) -> 'a -> 'c

  (* Evaluates both arguments and returns the first, ignoring the second. *)
  val before : 'a * unit -> 'a

  (* Evaluates its argument and returns (). *)
  val ignore : 'a -> unit
end

(*) End general.sig
