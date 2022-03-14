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
Sys.set ("lineWidth", 78);

(*) Literals
1;
~2;
~2147483647;
~2147483648;
2147483647;
"a string";
"a \"quoted\" string\\with backslash";
"";
#"a";
#"\"";
#"\\";
true;
[1,2,3];
(1,2);
(1,(2,"three"),true);
[(1,"a"),(2,"b"),(3,"c")];
([1,2],["a","b","c"]);
{a=1, b="two"};
{1="a", 2=true, 3=(3,4,5)};
("a", true, (3,4,5));
{1="a"};
("a");
{1=1,2=2,3=3,4=4,5=5,6=6,7=7,8=8,9=9,10=10,11=11,a="a",a1="a1",a2="a2",a10="a10"};
{1=1,2=2,3=3,4=4,5=5,6=6,7=7,8=8,9=9,10=10,11=11};
{0=0};
{0=0,1=1,2=2,3=3,4=4,5=5,6=6,7=7,8=8,9=9,10=10,11=11};
{1=1,2=2,3=3,5=5,6=6,7=7,8=8,9=9,10=10,11=11};

(*) Identifiers
val x = 1
and x' = 2
and x'' = 3
and x'y = 4
and ABC123 = 5
and Abc_123 = 6
and Abc_ = 7;

fun foo x
  x'
  x''
  x'y
  ABC123
  Abc_123
  Abc_ = 1;

{x = 1,
 x' = 2,
 x'' = 3,
 x'y = 4,
 ABC123 = 5,
 Abc_123 = 6,
 Abc_ = 7};

(*) Simple commands
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

fun strTimes s 0 l = l
  | strTimes s i l = strTimes ("a" ^ s) (i - 1) (s :: l);

strTimes "" 3 [];
strTimes "" 8 [];
strTimes "" 9 [];
strTimes "" 12 [];
strTimes "" 13 [];
strTimes "" 20 [];

[{a="aaaaaaaaaaaaaaaaaaaaaa",b="bbbbbbbbbbbbbb",c="cccccccccccccccccc",d="ddddddddd"}];
[{a="aaaaaaaaaaaaaaaaaaaaaa",b="bbbbbbbbbbbbbb",c="cccccccccccccccccc",d="ddddddddd"},{a="aaaaaaaaaaaaaaaaaaaaaa",b="bbbbbbbbbbbbbb",c="cccccccccccccccccc",d="ddddddddd"}];
{a=1,b=2,c=3,d=4,e=5,f=6,g=7,h=8,i=9,j=10,k=11,l=12,m=13,n=14,o_=15,p=16};
(*
sml prints the following, but we cannot split types yet:

val it = {a=1,b={a=2,b={a=3,b={a=4,b={a=#,b=#}}}}}
  : {a:int,
     b:{a:int, b:{a:int, b:{a:int, b:{a:int, b:{a:int, b:{a:int, b:unit}}}}}}}
*)
{a=1,b={a=2,b={a=3,b={a=4,b={a=5,b={a=6,b={a=7,b={}}}}}}}};

(*) Bug: Fails due to lack of parentheses
(*
let
  fun in_ e [] = false
    | in_ e (h :: t) = e = h orelse in_ e t
in
  (in_ 3 [1,2,3], in_ 4 [1,2,3])
end;
*)

(*) Succeeds when parentheses are added
let
  fun in_ e [] = false
    | in_ e (h :: t) = e = h orelse (in_ e t)
in
  (in_ 3 [1,2,3], in_ 4 [1,2,3])
end;

(*) Lambda on unit
fn () => 1;
it ();

(*) Multiple functions
let
  fun f x = 1
  fun g x = 2
in
  f (g 0)
end;

(*) Closure
let
  fun f x = 1 + x;
  val x = f 2;
  fun f y = x + y;
  val x = 10
in
  f x
end;

(*) As "Closure", but each 'fun' is replaced with a lambda.
let
  val f = fn x => 1 + x;
  val x = f 2;
  val f = fn y => x + y;
  val x = 10
in
  f x
end;

(*) As "Closure", but converted to nested 'let' expressions.
let
  fun f x = 1 + x
in
  let
    val x = f 2
  in
    let
      fun g y = x + y
    in
      let
        val x = 10
      in
        g x
      end
    end
  end
end;

(*) Similar to "Closure", but a simpler expression.
let
  val x = 1;
  fun f y = x + y;
  val x = 10;
in
  f x
end;

(*) Similar to "Closure", but the two occurrences of 'x'
(*) have different data types, so that the bug is more obvious.
let
  val x = "abc";
  fun g y = size x + y;
  val x = 10
in
  g x
end;

(*) As previous, but converting 'fun' to a lambda.
let
  val x = "abc";
  val g = fn y => size x + y;
  val x = 10
in
  g x
end;

(*) A fun in a fun (test case for [MOREL-19])
fun foo arg =
  let
    fun baz x = x
  in
    baz arg
  end;
foo 3;

(*) Define a function using higher-order functions
val longest = foldl (fn (s, t) => if size s > size t then s else t) "";
longest ["ab", "cde", "", "f"];
longest [];

(*) Mutually recursive functions
let
  fun f i = g (i * 2)
  and g i = if i > 10 then i else f (i + 3)
in
  f
end;
it 1;

(*) Same as previous, using 'val rec'
let
  val rec f = fn i => g (i * 2)
  and g = fn i => if i > 10 then i else f (i + 3)
in
  f
end;
it 1;

let
  fun odd 0 = false
    | odd n = even (n - 1)
  and even 0 = true
    | even n = odd (n - 1)
in
  (odd 17, even 17)
end;

let
  fun isZeroMod3 0 = true
    | isZeroMod3 n = isTwoMod3 (n - 1)
  and isOneMod3 0 = false
    | isOneMod3 n = isZeroMod3 (n - 1)
  and isTwoMod3 0 = false
    | isTwoMod3 n = isOneMod3 (n - 1)
in
  (isZeroMod3 17, isOneMod3 17, isTwoMod3 17)
end;

(*) An example where identifiers with primes (') are more readable
fun distance (x, y) (x', y') =
  Math.sqrt ((x - x') * (x - x') + (y - y') * (y - y'));
distance (0.0, 1.0) (4.0, 4.0);

(*) Composite declarations
let
  val (x, y) = (1, 2)
in
  x + y
end;
val (x, y) = (1, 2);
it;
val (x, 2) = (1, 2);
it;
val h :: t = [1, 2, 3];
it;
val _ = 3;

(* TODO fix in [MOREL-43]
val SOME i = SOME 1;
val i = 1 : 'a
*)
val SOME i = NONE;
(* TODO fix in [MOREL-43]
val SOME (p as (1, i), SOME true) = SOME ((1, 2), SOME true);
val p = (1,2) : int * 'a
val i = 2 : 'a
*)
(* TODO fix in [MOREL-43]
val SOME (i, NONE) = SOME (1, SOME false);
*)

val (i, true) = (1, true);
val (i, false) = (1, true);
let
  val (i, false) = (1, true)
in
  i + 1
end;

(*) Patterns

(*) The following example is from "Introduction to Standard ML" by Robert Harper.
val x = (( "foo", true ), 17 );
val ( l as (ll,lr), r ) = x;

fun f (true, []) = ~1
  | f (true, l as (hd :: tl)) = length l
  | f (false, list) = 0;
f (true, ["a","b","c"]);

let
  val w as (x, y) = (1, 2)
in
  #1 w + #2 w + x + y
end;

val x as (y, z) = (1, 2);
val x as h :: t = [1,2,3];
val x as y as (h,i) :: t = [(1,2),(3,4),(5,6)];
(* TODO fix in [MOREL-43]
val a as SOME (b as (c, d)) = SOME (1, 2);
val a = SOME (1,2) : (int * int) option
val b = (1,2) : 'a * 'b
val c = 1 : 'a
val d = 2 : 'b
*)
val x as y as z = 3;

(*) Errors
(* The first is printed as '1.9', the second as '1.9-1.11', and the third as '1.9-1.12'. *)
"a string literal to reset the line counter after the comment";
val a = p + 1;
val a = pq + 1;
val a = pqr + 1;

fun f n = String.substring ("hello", 1, n);
f 2;
f 6;
f ~1;

(*) End simple.sml
