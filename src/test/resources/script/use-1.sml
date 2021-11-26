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
 * Called by use.sml (which we assume has defined 'x');
 * also called standalone. Some commands fail if 'x' is not
 * defined, but the script continues to the end.
 *)

"entering use-1.sml";
val y = x ^ ", ";
val x = y ^ "step 2";
fun plus3 n = n + 3;
plus3 ~1;
"leaving use-1.sml";

(*) End use-1.sml
