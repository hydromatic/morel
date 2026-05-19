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
 * The INT_INF signature, per the Standard ML Basis Library.
 *)
(**
 * The `IntInf` structure provides arithmetic and conversion operations for
 * arbitrary-precision integers. Operations are analogous to those in `Int`
 * but operate on unbounded values.
 *)
signature INT_INF =
sig

(*
  (** is the type of arbitrary-precision integers. *)
  eqtype int
*)
end
[@@description "Arbitrary-precision integer operations."]

(*) End int-inf.sig
