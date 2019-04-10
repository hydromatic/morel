/*
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
 */

// Simple commands
1;
val x = 1;
x + 2;
let
  fun fact n =
    if n = 0
    then 1
    else n * (fact (n - 1))
in
  fact 5
end;
let
  fun sum x y = x + y
in
  sum 2
end;
it 3;

/* Disabled due to bug linking recursive functions at top-level
fun str s 0 l = l
  | str s i l = str ("a" ^ s) (i - 1) (s :: l);
val str = fn : string -> int -> string list -> string list

str "" 10 [];
val it =
  ["aaaaaaaaa","aaaaaaaa","aaaaaaa","aaaaaa","aaaaa","aaaa","aaa","aa","a",""]
  : string list

// Note how long lists are wrapped and abbreviated
str "" 20 [];
val it =
  ["aaaaaaaaaaaaaaaaaaa","aaaaaaaaaaaaaaaaaa","aaaaaaaaaaaaaaaaa",
   "aaaaaaaaaaaaaaaa","aaaaaaaaaaaaaaa","aaaaaaaaaaaaaa","aaaaaaaaaaaaa",
   "aaaaaaaaaaaa","aaaaaaaaaaa","aaaaaaaaaa","aaaaaaaaa","aaaaaaaa",...]
  : string list
*/

// Bug: Fails due to lack of parentheses
/*
let
  fun in_ e [] = false
    | in_ e (h :: t) = e = h orelse in_ e t
in
  (in_ 3 [1,2,3], in_ 4 [1,2,3])
end;
*/

// Succeeds when parentheses are added
let
  fun in_ e [] = false
    | in_ e (h :: t) = e = h orelse (in_ e t)
in
  (in_ 3 [1,2,3], in_ 4 [1,2,3])
end;

// End simple.sml
