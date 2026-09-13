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
 * The INTERACT signature, a Morel extension.
 *)
(**
 * The `Interact` structure provides functions for interacting with the
 * Morel REPL, such as loading source files.
 *)
signature INTERACT =
sig

  (**
   * is raised by `use` and `useSilently` when the file named cannot be
   * opened.
   *)
  exception Error

  (**
   * is raised by `use` and `useSilently` when the environment has no
   * shell to read a file into, as when Morel is evaluating a single
   * expression given on the command line. It is distinct from `Error`,
   * which says that a particular file could not be opened.
   *)
  exception EvalOnly

  (** loads source text from the file named `f`. *)
  val use : string -> unit [@@prototype "use f"]

  (**
   * loads source text from the file named `f`, without
   * printing to stdout.
   *)
  val useSilently : string -> unit [@@prototype "useSilently f"]
end
[@@description "Interactive session utilities."]
[@@specified "morel"]

(*) End interact.sig
