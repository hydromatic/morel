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
 *)

(*) Has polymorphic type
(*
val rec len = fn x =>
    case x of head :: tail => 1 + (len tail)
            | [] => 0;

len [];
len [1];
len [1,2];
*)

(*) Should give
(*)  Error: duplicate variable in pattern(s): e
(*
fun in_ e [] = false
  | in_ e e :: tl = true
  | in_ e hd :: tl = in_ e tl
*)

(*) Should give
(*) Error: operator and operand don't agree [tycon mismatch]
(*)     operator domain: 'Z list list
(*)     operand:         (({id:'X; 'Y} -> 'X) * ([+ ty] * [+ ty] -> [+ ty])) list
(*)     in expression:
(*)       aggregate (((fn <pat> => <exp>),sum) :: nil)
(*
let
  fun foldl f start [] = start
    | foldl f start (hd :: tl) = foldl f (f (start, hd)) tl;
  fun map f [] = []
    | map f (hd :: tl) = (f hd) :: (map f tl);
  fun computeAgg (extractor, folder) list =
      foldl folder (map extractor list);
  fun aggregate aggFns list =
      map (computeAgg list) aggFns;
  fun sum (x, y) = x + y;
in
  aggregate [(#id, sum)] emps
end;
*)

(*) End type.sml
