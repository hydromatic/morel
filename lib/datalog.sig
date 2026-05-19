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
 * The DATALOG signature, a Morel extension.
 *)
(**
 * The `Datalog` structure provides functions to parse, validate, translate,
 * and execute Datalog programs within Morel.
 *)
signature DATALOG =
sig

  (**
   * executes a Datalog program and returns formatted output as a variant.
   *)
  val execute : string -> variant [@@prototype "execute program"]

  (**
   * translates a Datalog program to Morel source code, returning `SOME
   * code` if valid or `NONE` if invalid.
   *)
  val translate : string -> string option [@@prototype "translate program"]

  (**
   * validates a Datalog program and returns type information or error
   * message.
   *)
  val validate : string -> string [@@prototype "validate program"]
end
[@@description "Datalog query interface."]
[@@specified "morel"]

(*) End datalog.sig
