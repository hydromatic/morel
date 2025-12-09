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
 * Variant structure for embedded language interoperability.
 *
 * The Variant structure provides a universal value representation
 * mechanism to facilitate returning results from embedded languages
 * (such as Soufflé Datalog) back to the Morel system.
 *)

(* Variant structure signature *)
signature VARIANT =
sig
  (* The `variant` datatype that can express all Morel values
   * (primitives, lists, bags, vectors, and instances of datatypes).
   *
   * One application is to return values from a dynamically typed
   * API. See, for example, Datalog integration.
   *
   * `UNIT`, `BOOL`, `INT`, `REAL`, `CHAR`, `STRING` cover Morel's
   * primitive data types.
   *
   * `LIST`, `BAG`, `VECTOR` represent the three main collection
   * types.
   *
   * `RECORD` handles both records and tuples. (Tuples are records
   * with numeric labels like "1", "2", etc.)
   *
   * `VARIANT_NONE` and `VARIANT_SOME` create option values. They are
   * so-named because NONE and SOME are constructors of the real
   * `option` datatype.
   *
   * `CONSTRUCT` and `CONSTANT` provide support for constructors of
   * any datatype (with and without payloads, respectively).
   *)
  datatype variant =
      UNIT
    | BOOL of bool
    | INT of int
    | REAL of real
    | CHAR of char
    | STRING of string
    | LIST of variant list
    | BAG of variant list
    | VECTOR of variant list
    | VARIANT_NONE
    | VARIANT_SOME of variant
    | RECORD of (string * variant) list
    | CONSTANT of string
    | CONSTRUCT of string * variant

  (*
   * parse : string -> variant
   *
   * Parses a string representation into a variant.
   *
   * The string should be in the format produced by the print function.
   * This enables round-tripping: `parse (print v) = v` for any variant
   * value `v`.
   *)
  val parse : string -> variant

  (*
   * print : variant -> string
   *
   * Converts a variant to its compact string representation.
   *
   * The output is a valid Standard ML expression that can be parsed
   * back into the same variant using the parse function.
   *)
  val print : variant -> string
end

(*) End variant.sig
