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

(*) Literals
1;
~2;
~2147483647;
~2147483648;
2147483647;
"a string";
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

fun str s 0 l = l
  | str s i l = str ("a" ^ s) (i - 1) (s :: l);

str "" 3 [];
str "" 8 [];
str "" 9 [];
str "" 12 [];
str "" 13 [];
str "" 20 [];

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
  fun g y = String.size x + y;
  val x = 10
in
  g x
end;

(*) As previous, but converting 'fun' to a lambda.
let
  val x = "abc";
  val g = fn y => String.size x + y;
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
val longest = List.foldl (fn (s, t) => if String.size s > String.size t then s else t) "";
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

(*) End simple.sml
