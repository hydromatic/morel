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

(* Types ---------------------------------------------------------- *)

val n : int = 66;
val x : real = ~23.0;

(* Inference *)
val s : string = "example";
val b : bool = true;
val s = "example";
val b = true;

(* Tuples *)
val t0 = ();
val t3 = (3, "yes", "yes");
val t3b = (3, "yes", true);
val t2 = ((1, 2), 3);

val pair = ("a", "b");
val (a, b) = pair;
val also_a = #1 pair;

(* Records *)
val r = { a = 5, b = "five" };
val r2 = { b = "five", a = 5 };
r = r2;

val pair2 = { 1 = "a", 2 = "b" };
pair = pair2;

(* Functions ------------------------------------------------------ *)

fun factorial n =
  if n < 1
  then 1
  else n * factorial (n - 1);
factorial 3;

(* Explicitly typed *)
(* TODO
fun factorial (n : int) : int = if n < 1  then 1  else n * factorial (n - 1);
factorial 3;
*)

(* Tuples as arguments *)
fun plus (a, b) = a + b;
fun average pair = plus pair div 2;
val four = average (3, 5);

(* TODO
infix averaged_with;
fun a averaged_with b = average (a, b);
val five = 3 averaged_with 7;
*)

(* Function returns tuple *)
fun pair (n : int) = (n, n);

(* Polymorphic *)
fun pair x = (x, x);

fun swap (x, y) = (y, x);
swap (1, "foo");

(* Higher-order functions *)
fun pair_map (f, (x, y)) = (f x, f y);

fun plus i j = i + j;
val add_three = plus 3;
val five = add_three 2;
val ten = plus 5 5;

(* A more complex function *)
fun gray_code n =
  if n < 1
  then "" :: nil
  else let val prev = gray_code (n-1)
        in (map (fn s => "0" ^ s) prev) @ (rev (map (fn s => "1" ^ s) prev))
       end;
val gray_code_3 = gray_code 3;

(* Type declarations ---------------------------------------------- *)

(* TODO
type int_pair = int * int;
fun swap_int_pair ((i,j) : int_pair) = (j,i);
fun swap_int_pair (i : int, j : int) = (j,i);
*)

(* Datatype declarations *)
datatype int_or_string = INT of int | STRING of string | NEITHER;
val i = INT 3;
val s = STRING "qq";
val n = NEITHER;
val INT j = i;

datatype int_list = EMPTY | INT_LIST of int * int_list;

(* Polymorphic datatype *)
datatype 'a pair = PAIR of 'a * 'a;

(* Lists *)
(* TODO
datatype 'a list = nil | :: of 'a * 'a list;
fun length nil = 0
|   length (_::t) = 1 + length t;
length (1 :: 2 :: nil);
length nil;
*)

(* TODO
local
  fun rev_helper (nil, ret) = ret
  |   rev_helper (h::t, ret) = rev_helper (t, h::ret)
in
  fun rev L = rev_helper (L, nil)
end;
rev (false :: false :: true :: nil);
*)

(* Exceptions *)
(* TODO
exception StringException of string;
val e = StringException "example";
val StringException s = e;
*)

(* References *)
(*
val reference : int ref = ref 12;
val ref (twelve : int) = reference;
twelve;
!reference;
reference := !reference + 1;
twelve;
!reference;
*)

(* Equality types *)
datatype suit = HEARTS | CLUBS | DIAMONDS | SPADES;
datatype int_pair = INT_PAIR of int * int;
datatype real_pair = REAL_PAIR of real * real;
datatype 'a option = NONE | SOME of 'a;

val suit0 = HEARTS;
val ip = INT_PAIR (1, 2);
val rp = REAL_PAIR (1.0, 2.0);
val op0 = NONE;
val op1 = SOME true;
val op2 = SOME 3.0;
val r = 5.0;

suit0 = suit0;
ip = ip;
op0 = op0;
op1 = op1;
(* The following give error:
stdIn:1.2-1.11 Error: operator and operand don't agree [equality type required]
op2 = op2;
rp = rp;
r = r;

stdIn:35.1-35.10 Error: operator and operand don't agree [tycon mismatch]
op1 = op2;
*)

(* Miscellaneous *)

(* Formatting of long values *)
val ilist = [999,999,999,9999,999999,999999,999999,999,999,999,999,999,999,999];
val ilist = [1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26];

(* Errors *)

(*) Should give 'Error: unbound variable or constructor: foo'
foo;

(*) Should give 'Error: unbound variable or constructor: foo'
5 + foo;

(*) Should give 'Error: unbound variable or constructor: foo'
(*) or 'Error: unbound variable or constructor: bar'
5 + bar + 6 + foo;

(* end script.sml *)
