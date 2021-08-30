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
Sys.set ("printDepth", ~1);

(*) Basic operations on 'int'
2 + 3;
2 - 3;
2 * 3;
2 / 3;
3 / 2;
3 mod 2;
~2 * 3;
~(2 + 3);
2 < 3;
2 <= 3;
2 > 3;
2 >= 3;
2 = 3;
2 <> 3;

(*) Basic operations on 'real'
2.0 + 3.0;
2.0 - 3.0;
2.0 * 3.0;
2.0 / 3.0;
3.0 / 2.0;
~2.0 * 3.0;
~(2.0 + 3.0);
2.0 < 3.0;
2.0 <= 3.0;
2.0 > 3.0;
2.0 >= 3.0;
2.0 = 3.0;
2.0 <> 3.0;

(*) Three different kinds of 'max'
fun max_alpha (x, y) = if x < y then y else x;
max_alpha (2, 3);
max_alpha (2.0, 3.0);
fun max_int (x, y) = if x < y then y + 0 else x;
max_int (2, 3);
fun max_real (x, y) = if x < y then y + 0.0 else x;
max_real (2.0, 3.0);

(*) Tuple type with a polymorphic member
let
  val r = (fn x => x, 2)
in
  (#1 r) 1
end;

(*) Record type with a polymorphic member
let
  val r = {a = fn x => x, b = 2}
in
  r.a 1
end;

let
  val r = {a = fn x => x, b = 2}
in
  (r.a "x", r.b)
end;

(*) A datatype whose constructor overwrites another datatype's constructor
datatype foo = W | X | Y of int;
X;
Y;
Y 0;
datatype bar = X of int | Y of int * bool | Z;
X;
Y;
Y (1, true);
Z;

(*) The following is syntax error:
(*
datatype bar = X of int | Y of (int, bool) | Z;
*)

(*) A recursive type, without generics
datatype inttree = Empty | Node of inttree * int * inttree;
fun max (x, y) = if x < y then y + 0 else x;
fun height Empty = 0
  | height (Node (lft, _, rht)) = 1 + max (height lft, height rht);
Empty;
height it;
height Empty;
Node(Empty, 1, Empty);
height it;
height (Node(Empty, 1, Empty));
Node(Empty, 2, Node(Node(Empty, 3, Empty), 4, Empty));
height it;

(*) Recursive
datatype 'x tree = Empty | Node of 'x tree * 'x * 'x tree;
fun max (x, y) = if x < y then y else x;
fun height Empty = 0
  | height (Node (lft, _, rht)) = 1 + max (height lft, height rht);
Empty;
height it;
Node(Empty, 1, Empty);
height it;
Node(Empty, 2, Node(Node(Empty, 3, Empty), 4, Empty));
height it;

(*) Two type parameters
datatype ('y, 'x) tree =
   Empty
 | Node of ('y, 'x) tree * 'x * 'y * ('y, 'x) tree;
Empty;
Node (Empty, true, "yes", Empty);

datatype ('a, 'b) Union = A of 'a | B of 'b;
A 1;
B true;

(*) Mutually recursive
datatype 'a tree = Empty | Node of 'a * 'a forest
and      'a forest = Nil | Cons of 'a tree * 'a forest;
Empty;
Nil;
Node (1, Nil);
Node (1, Cons (Empty, Nil));
Cons (Empty, Nil);
Cons (Empty, Cons (Node (true, Nil), Nil));

(*) Parentheses are required for 2 or more type parameters,
(*) optional for 1 type parameter,
(*) not allowed for 0 type parameters.
datatype ('a, 'b) pair = Pair of 'a * 'b;
(* disabled; should throw
datatype 'a, 'b pair = Pair of 'a * 'b; (*) not valid
*)
datatype 'a single = Single of 'a;
datatype ('a) single = Single of 'a;
(* disabled; should throw
datatype () void = Void of unit; (*) not valid
datatype () void = Void; (*) not valid
*)
datatype void = Void;
datatype unitVoid = Void of unit;

(*) Recursive datatype with 2 type parameters
datatype ('a, 'b) tree = EMPTY | NODE of 'a * 'b * ('a, 'b) tree;
EMPTY;
NODE (1, true, EMPTY);
NODE (1, true, NODE (2, false, EMPTY));

(*
- fun f x none = x | x some y = y;
stdIn:2.5-2.32 Error: clauses don't all have same function name
- fun f x none = x | f x some y = y;
stdIn:1.6-2.1 Error: clauses don't all have same number of patterns
stdIn:1.6-2.1 Error: types of rules don't agree [tycon mismatch]
  earlier rule(s): 'Z * 'Y -> 'Z
  this rule: 'X * 'W * 'V -> 'V
  in rule:
    (x,some,y) => y
- datatype o = NONE | SOME of int;
datatype o = NONE | SOME of int
- fun f NONE = 0 | f SOME x = x;
stdIn:5.5-5.30 Error: clauses don't all have same number of patterns
stdIn:5.20-5.24 Error: data constructor SOME used without argument in pattern
stdIn:5.5-5.30 Error: parameter or result constraints of clauses don't agree [tycon mismatch]
  this clause:      'Z * 'Y -> 'X
  previous clauses:      o -> 'X
  in declaration:
    f = (fn NONE => 0
          | (_,x) => x)
- fun f NONE = 0 | f (SOME x) = x;
val f = fn : o -> int
- f SOME 5 ;
stdIn:6.1-6.9 Error: operator and operand don't agree [tycon mismatch]
  operator domain: o
  operand:         int -> o
  in expression:
    f SOME
- f (SOME 5);
val it = 5 : int
- fun f x NONE = x | f x SOME y = y;
stdIn:1.6-2.1 Error: clauses don't all have same number of patterns
stdIn:1.25-1.29 Error: data constructor SOME used without argument in pattern
stdIn:1.6-2.1 Error: types of rules don't agree [tycon mismatch]
  earlier rule(s): 'Z * o -> 'Z
  this rule: 'Y * 'X * 'W -> 'W
  in rule:
    (x,_,y) => y
- fun f x NONE = x | f x (SOME y) = y;
val f = fn : int -> o -> int
- f 1 NONE;
val it = 1 : int
- f 2 SOME 3;
stdIn:4.1-4.11 Error: operator and operand don't agree [tycon mismatch]
  operator domain: o
  operand:         int -> o
  in expression:
    (f 2) SOME
- f 2 (SOME 3);
val it = 3 : int
*)

(*) End datatype.sml
