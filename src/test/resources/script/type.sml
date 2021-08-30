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

(*) Record and tuple are equivalent.
{1 = "a", 2 = true};
("a", true);
("a", true) = {1 = "a", 2 = true};
{1 = "a", 2 = true} = ("a", true);
("a", true) = {2 = true, 1 = "a"};
("a", true) = {2 = true, 1 = "b"};
("a", false) = {1 = "a", 2 = true};

(*) Empty record and empty tuple are equivalent, and of type 'unit'.
{};
();
{} = ();
() = {};

(*) Function with unit arg
fun one () = 1;
one ();
one {};
let
  fun one () = 1
in
  one ()
end;

(*) A function with a type that is tricky to unparse correctly:
(*)   'a list -> ('a * 'a list) option
fun g [] = NONE
  | g (h :: t) = SOME (h, t);

(*) Ditto:
(*)   'a list -> ('a * 'a list option) option
fun g [] = NONE
  | g (h :: t) = SOME (h, SOME t);

(*) Ditto:
(*)   'a list -> ('a option * 'a list option) option
fun g [] = NONE
  | g (h :: t) = SOME (SOME h, SOME t);

(*) Pattern-match on record
fun f {a = c, b} = b + c;
f {a = 5, b = 6};

fun f2 {a = 1, b} = b * 2
  | f2 {a, b} = b * 3;
f2 {a = 1, b = 6};
f2 {a = 2, b = 6};

fun f3 {a = 1, b} = b * 2;
f3 {a = 1, b = 6};

(*) The following correctly throws
(*)   unbound variable or constructor: a
(*) Disabled because error stacks made tests brittle.
(*) fun f4 {a = 1, b} = a + b;

(*) Variable with polymorphic type
val x = map;
x explode ["ab", "c"];

(*) Has polymorphic type
val rec len = fn x =>
    case x of head :: tail => 1 + (len tail)
            | [] => 0;

len [];
len [1];
len [1,2];

map len (map explode ["ab", "", "cde"]);
map (fn s => len (explode s)) ["ab", "", "cde"];

(*) Type resolution involving record selectors
val emps =
  [{id = 100, name = "Fred", deptno = 10},
   {id = 101, name = "Velma", deptno = 20},
   {id = 102, name = "Shaggy", deptno = 30},
   {id = 103, name = "Scooby", deptno = 30}];
map #deptno emps;
map #deptno (List.filter (fn e => #deptno e > 10) emps);
map #2 [(1,2),(3,1)];
List.filter #1 [(true,1),(false,2),(true,3)];
map #2 (List.filter #1 [(true,1),(false,2),(true,3)]);

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

(*) Record containing polymorphic functions:
(*)  val it = {a=fn,b=fn}
(*) : {a:'a list -> ('a * 'a list) option, b:'b list -> ('b * 'b list) option}
{a = fn x => case x of [] => NONE | (h :: t) => SOME (h, t),
 b = fn x => case x of [] => NONE | (h :: t) => SOME (h, t)};

(*) Similar, expressed via 'fun'.
(* sml/nj gives:
stdIn:1.2-1.66 Warning: type vars not generalized because of
   value restriction are instantiated to dummy types (X1,X2,...)
val it = {a=1,b=fn} : {a:int, b:?.X1 list -> (?.X1 * ?.X1 list) option}
*)
let
  fun g [] = NONE
    | g (h :: t) = SOME (h, t)
in
  {a=1, b=g}
end;

(*) as above
{a = 1, b = let fun g [] = NONE | g (h :: t) = SOME (h, t) in g end};

(*) End type.sml
