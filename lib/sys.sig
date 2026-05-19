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
 * The SYS signature, a Morel extension.
 *)
(**
 * The `Sys` structure provides functions for interacting with the Morel
 * execution environment, such as reading properties and managing the
 * environment.
 *)
signature SYS =
sig

  (** restores the environment to the initial environment. *)
  val clearEnv : unit -> unit [@@prototype "clearEnv ()"]

  (** prints the environment. *)
  val env : unit -> (string * string) list [@@prototype "env ()"]

  (**
   * is a view of the file system as a record. The fields of the record
   * depend on the files and directories under the configured directory.
   *)
  val file : {} [@@prototype "file"]

  (**
   * parses `s` as a top-level Morel statement and returns a parenthesized
   * S-expression-style dump of the resulting abstract syntax tree. Useful for
   * testing parser behavior (e.g. operator precedence and attribute attachment)
   * from `.smli` scripts. Raises `Error` if the string does not parse.
   *)
  val parseTree : string -> string [@@prototype "parseTree s"]

  (** prints the plan of the most recently executed expression. *)
  val plan : unit -> string [@@prototype "plan ()"]

  (**
   * re-plans the most recently executed expression and returns the Core
   * representation at the specified phase. The phase argument can be "0" (initial),
   * "-1" (final), or a specific pass number.
   *)
  val planEx : string -> string [@@prototype "planEx phase"]

  (** sets the value of `property` to `value`. *)
  val set : string * 'a -> unit
      [@@prototype "set (property, value)"]
      [@@extra "(See [Properties](#properties) below.)"]

  (**
   * returns the current the value of `property`, as a
   * string, or `NONE` if unset.
   *)
  val show : string -> string option [@@prototype "show property"]

  (**
   * returns a list of all properties and their current value
   * as a string, or `NONE` if unset.
   *)
  val showAll : unit -> (string * string option) list [@@prototype "showAll ()"]

  (** clears the current the value of `property`. *)
  val unset : string -> unit [@@prototype "unset property"]
end
[@@description "System interface utilities."]
[@@specified "morel"]

(*) End sys.sig
