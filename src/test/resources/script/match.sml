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
 * Pattern matching
 *)

(*) Warning: match nonexhaustive
fun f 1 = 0;
f 1;
f 2;

(*) Warning: match nonexhaustive, twice
fun f x =
  let
    fun g 1 = 1
    and h 2 = 2
  in
    (g x) + (h 2)
  end;
f 1;
f 2;

(*) Error: match redundant and nonexhaustive
fun f 1 = 0
  | f 1 = 0;

(*) OK
fun f 1 = 0
  | f _ = 1;
f 1;
f 2;

(*) Error: match redundant
fun f (1, _) = 1
  | f (_, 2) = 2
  | f (1, 2) = 3
  | f (_, _) = 4;

(*) The Ackermann-Péter function
(*) See "Recursion Equations as a Programming Language", D A Turner 1982
fun ack 0 n = n + 1
  | ack m 0 = ack (m - 1) 1
  | ack m n = ack (m - 1) (ack m (n - 1));
ack 0 0;
ack 0 1;
ack 1 0;
ack 1 2;
ack 2 3;
ack 3 3;

(*) End match.sml
