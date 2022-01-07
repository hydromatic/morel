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
Sys.set ("printLength", 64);
Sys.set ("stringDepth", ~1);

(* Structures -------------------------------------------------- *)
General;
Interact;
List;
List.rev;
List.rev [1,2,3];
Math;
Option;
Option.compose;
String;
Real;
Relational;

(* Operators --------------------------------------------------- *)
2 + 3;
2 + 3 * 4;
Sys.plan ();

fn x => x + 1;
Sys.plan ();

val nan = Real.posInf / Real.negInf;

(* Datatypes --------------------------------------------------- *)

(*) datatype option
SOME 1;
NONE;
SOME (SOME true);

(* General ----------------------------------------------------- *)

(*) op o - function composition
val plusOne = fn x => x + 1;
val timesTwo = fn x => x * 2;
val plusThree = fn x => x + 3;
plusOne o timesTwo;
(plusOne o timesTwo) 3;
plusOne o timesTwo o plusThree;
((plusOne o timesTwo) o plusThree) 3;
(plusOne o (timesTwo o plusThree)) 3;
Sys.plan ();

ignore;
ignore (1 + 2);
Sys.plan ();

(* Interact ---------------------------------------------------- *)

(*) use - load source from a file
Interact.use;
use;

(* String ------------------------------------------------------ *)

(*) val maxSize : int
String.maxSize;
Sys.plan ();

(*) val size : string -> int
String.size;
String.size "abc";
String.size "";
Sys.plan ();

(*) val sub : string * int -> char
String.sub;
String.sub("abc", 0);
String.sub("abc", 2);
String.sub("abc", 20);
String.sub("abc", 3);
String.sub("abc", ~1);
Sys.plan ();

(*) val extract: string * int * int option -> string
String.extract;
String.extract("abc", 1, NONE);
String.extract("abc", 1, SOME 2);
String.extract("abc", 3, NONE);
String.extract("abc", 3, SOME 0);
String.extract("abc", 4, NONE);
String.extract("abc", ~1, NONE);
String.extract("abc", 4, SOME 2);
String.extract("abc", ~1, SOME 2);
String.extract("abc", 1, SOME ~1);
String.extract("abc", 1, SOME 99);
Sys.plan ();

(*) val substring : string * int * int -> string
String.substring;
String.substring("hello, world", 2, 7);
String.substring("hello, world", 0, 1);
String.substring("hello", 5, 0);
String.substring("hello", 1, 4);
String.substring("", 0, 0);
String.substring("hello", ~1, 0);
String.substring("hello", 1, ~1);
String.substring("hello", 1, 5);
Sys.plan ();

(*) val ^ : string * string -> string
"a" ^ "bc";
"a" ^ "";
"a" ^ "bc" ^ "" ^ "def";
Sys.plan ();

(*) val concat : string list -> string
String.concat;
String.concat ["a", "bc", "def"];
String.concat ["a"];
String.concat [];
Sys.plan ();

(*) val concatWith : string -> string list -> string
String.concatWith;
String.concatWith "," ["a", "bc", "def"];
String.concatWith "," ["a"];
String.concatWith "," ["", ""];
String.concatWith "," [];
Sys.plan ();

(*) val str : char -> string
String.str;
String.str #"a";
Sys.plan ();

(*) val implode : char list -> string
String.implode;
String.implode [#"a", #"b", #"c"];
String.implode [];
Sys.plan ();

(*) val explode : string -> char list
String.explode;
String.explode "abc";
String.explode "";
Sys.plan ();

(*) val map : (char -> char) -> string -> string
String.map;
String.map (fn c => if c = #"a" then #"A" else if c = #"c" then #"C" else c) "abc";
String.map (fn c => if c = #"a" then #"A" else if c = #"c" then #"C" else c) "";
Sys.plan ();

(*) val translate : (char -> string) -> string -> string
String.translate;
String.translate (fn c => if c = #"a" then "AA" else if c = #"c" then "CCC" else "-") "abc";
String.translate (fn c => if c = #"a" then "AA" else if c = #"c" then "CCC" else "-") "";
Sys.plan ();

(*) val tokens : (char -> bool) -> string -> string list
(*) val fields : (char -> bool) -> string -> string list
(*) val isPrefix    : string -> string -> bool
String.isPrefix;
String.isPrefix "he" "hello";
String.isPrefix "el" "hello";
String.isPrefix "lo" "hello";
String.isPrefix "bonjour" "hello";
String.isPrefix "el" "";
String.isPrefix "" "hello";
String.isPrefix "" "";
Sys.plan ();

(*) val isSubstring : string -> string -> bool
String.isSubstring;
String.isSubstring "he" "hello";
String.isSubstring "el" "hello";
String.isSubstring "lo" "hello";
String.isSubstring "bonjour" "hello";
String.isSubstring "el" "";
String.isSubstring "" "hello";
String.isSubstring "" "";
Sys.plan ();

(*) val isSuffix    : string -> string -> bool
String.isSuffix;
String.isSuffix "he" "hello";
String.isSuffix "el" "hello";
String.isSuffix "lo" "hello";
String.isSuffix "bonjour" "hello";
String.isSuffix "el" "";
String.isSuffix "" "hello";
String.isSuffix "" "";
Sys.plan ();

(*) val compare : string * string -> order
(*) val collate : (char * char -> order) -> string * string -> order
(*) val <  : string * string -> bool
(*) val <= : string * string -> bool
(*) val >  : string * string -> bool
(*) val >= : string * string -> bool

(*) val scan       : (char, 'a) StringCvt.reader
(*)                    -> (string, 'a) StringCvt.reader
(*) val toCString : string -> String.string
(*) val fromCString : String.string -> string option

(* List -------------------------------------------------------- *)

(*) val nil : 'a list
List.nil;
Sys.plan ();

(*) val null : 'a list -> bool
List.null;
List.null [];
List.null [1];
Sys.plan ();

(*) val length : 'a list -> int
List.length;
List.length [];
List.length [1,2];
Sys.plan ();

(*) val @ : 'a list * 'a list -> 'a list
List.at;
List.at ([1], [2, 3]);
List.at ([1], []);
List.at ([], [2]);
List.at ([], []);
Sys.plan ();

[1] @ [2, 3];
[] @ [];
Sys.plan ();

(*) val hd : 'a list -> 'a
List.hd;
List.hd [1,2,3];
List.hd [];
Sys.plan ();

(*) val tl : 'a list -> 'a list
List.tl;
List.tl [1,2,3];
List.tl [];
Sys.plan ();

(*) val last : 'a list -> 'a
List.last;
List.last [1,2,3];
List.last [];
Sys.plan ();

(*) val getItem : 'a list -> ('a * 'a list) option
List.getItem;
List.getItem [1,2,3];
List.getItem [1];
Sys.plan ();

(*) val nth : 'a list * int -> 'a
List.nth;
List.nth ([1,2,3], 2);
List.nth ([1], 0);
List.nth ([1,2,3], 3);
List.nth ([1,2,3], ~1);
Sys.plan ();

(*) val take : 'a list * int -> 'a list
List.take;
List.take ([1,2,3], 0);
List.take ([1,2,3], 1);
List.take ([1,2,3], 3);
List.take ([1,2,3], 4);
List.take ([1,2,3], ~1);
Sys.plan ();

(*) val drop : 'a list * int -> 'a list
List.drop;
List.drop ([1,2,3], 0);
List.drop ([1,2,3], 1);
List.drop ([1,2,3], 3);
Sys.plan ();

(*) val rev : 'a list -> 'a list
List.rev;
List.rev [1,2,3];
List.rev [2,1];
List.rev [1];
List.rev [];
Sys.plan ();

(*) val concat : 'a list list -> 'a list
List.concat;
List.concat [[1],[2,3],[4,5,6]];
List.concat [[1],[],[4,5,6]];
List.concat [[],[],[]];
List.concat [];
Sys.plan ();

(*) val revAppend : 'a list * 'a list -> 'a list
List.revAppend;
List.revAppend ([1,2],[3,4,5]);
List.revAppend ([1],[3,4,5]);
List.revAppend ([],[3,4,5]);
List.revAppend ([1,2],[]);
List.revAppend ([],[]);
Sys.plan ();

(*) val app : ('a -> unit) -> 'a list -> unit
List.app;
List.app (fn x => ignore (x + 2)) [2,3,4];
List.app (fn x => ignore (x + 2)) [];
Sys.plan ();

(*) val map : ('a -> 'b) -> 'a list -> 'b list
List.map;
List.map (fn x => x + 1) [1,2,3];
List.map (fn x => x + 1) [];
Sys.plan ();

(*) map is alias for List.map
map;
map (fn x => x) [];
Sys.plan ();

(*) val mapPartial : ('a -> 'b option) -> 'a list -> 'b list
List.mapPartial;
List.mapPartial (fn x => if x mod 2 = 0 then NONE else SOME (x + 1)) [1,2,3,5,8];
List.mapPartial (fn x => if x mod 2 = 0 then NONE else SOME (x + 1)) [];
Sys.plan ();

(*) val find : ('a -> bool) -> 'a list -> 'a option
List.find;
List.find (fn x => x mod 7 = 0) [2,3,5,8,13,21,34];
List.find (fn x => x mod 11 = 0) [2,3,5,8,13,21,34];
Sys.plan ();

(*) val filter : ('a -> bool) -> 'a list -> 'a list
List.filter;
List.filter (fn x => x mod 2 = 0) [0,1,2,3,4,5];
List.filter (fn x => x mod 2 = 0) [1,3];
List.filter (fn x => x mod 2 = 0) [];
Sys.plan ();

(*) val partition : ('a -> bool) -> 'a list -> 'a list * 'a list
List.partition;
List.partition (fn x => x mod 2 = 0) [0,1,2,3,4,5];
List.partition (fn x => x mod 2 = 0) [1];
List.partition (fn x => x mod 2 = 0) [];
Sys.plan ();

(*) val foldl : ('a * 'b -> 'b) -> 'b -> 'a list -> 'b
List.foldl;
List.foldl (fn (a, b) => a + b) 0 [1,2,3];
List.foldl (fn (a, b) => a + b) 0 [];
List.foldl (fn (a, b) => b) 0 [1,2,3];
List.foldl (fn (a, b) => a - b) 0 [1,2,3,4];
Sys.plan ();

(*) val foldr : ('a * 'b -> 'b) -> 'b -> 'a list -> 'b
List.foldr;
List.foldr (fn (a, b) => a + b) 0 [1,2,3];
List.foldr (fn (a, b) => a + b) 0 [];
List.foldr (fn (a, b) => b) 0 [1,2,3];
List.foldr (fn (a, b) => a - b) 0 [1,2,3,4];
Sys.plan ();

(*) val exists : ('a -> bool) -> 'a list -> bool
List.exists;
List.exists (fn x => x mod 2 = 0) [1,3,5];
List.exists (fn x => x mod 2 = 0) [2,4,6];
List.exists (fn x => x mod 2 = 0) [1,2,3];
List.exists (fn x => x mod 2 = 0) [];
Sys.plan ();

(*) val all : ('a -> bool) -> 'a list -> bool
List.all;
List.all (fn x => x mod 2 = 0) [1,3,5];
List.all (fn x => x mod 2 = 0) [2,4,6];
List.all (fn x => x mod 2 = 0) [1,2,3];
List.all (fn x => x mod 2 = 0) [];
Sys.plan ();

(*) val tabulate : int * (int -> 'a) -> 'a list
List.tabulate;
List.tabulate (5, let fun fact n = if n = 0 then 1 else n * fact (n - 1) in fact end);
List.tabulate (1, let fun fact n = if n = 0 then 1 else n * fact (n - 1) in fact end);
List.tabulate (0, let fun fact n = if n = 0 then 1 else n * fact (n - 1) in fact end);
List.tabulate (~1, let fun fact n = if n = 0 then 1 else n * fact (n - 1) in fact end);
Sys.plan ();

(*) val collate : ('a * 'a -> order) -> 'a list * 'a list -> order
List.collate;
List.collate (fn (x, y) => if x < y then LESS else if x = y then EQUAL else GREATER) ([1, 2,3], [1,3,4]);
List.collate (fn (x, y) => if x < y then LESS else if x = y then EQUAL else GREATER) ([1,2,3], [1,2,2]);
List.collate (fn (x, y) => if x < y then LESS else if x = y then EQUAL else GREATER) ([1,2,3], [1,2]);
List.collate (fn (x, y) => if x < y then LESS else if x = y then EQUAL else GREATER) ([1,2,3], [1,2,3,4]);
List.collate (fn (x, y) => if x < y then LESS else if x = y then EQUAL else GREATER) ([1,2,3], []);
List.collate (fn (x, y) => if x < y then LESS else if x = y then EQUAL else GREATER) ([], []);
Sys.plan ();

(* Math -------------------------------------------------------- *)
(* The signature MATH specifies basic mathematical constants, the square root
   function, and trigonometric, hyperbolic, exponential, and logarithmic
   functions based on a real type. The functions defined here have roughly the
   same semantics as their counterparts in ISO C's math.h.

   In the functions below, unless specified otherwise, if any argument is a NaN,
   the return value is a NaN. In a list of rules specifying the behavior of a
   function in special cases, the first matching rule defines the semantics. *)

(* "acos x" returns the arc cosine of x. acos is the inverse of cos.
   Its result is guaranteed to be in the closed interval [0, pi]. If
   the magnitude of x exceeds 1.0, returns NaN. *)
Math.acos;
Math.acos 1.0;
Sys.plan ();
List.map (fn x => (x, Math.acos x))
  [1.0, 0.0, ~0.0, ~1.0, 0.5, Math.sqrt 0.5, 2.0, Real.posInf, Real.negInf, nan];

(* "asin x" returns the arc sine of x. asin is the inverse of sin. Its
   result is guaranteed to be in the closed interval [-pi / 2, pi / 2].
   If the magnitude of x exceeds 1.0, returns NaN. *)
Math.asin;
Math.asin 1.0;
Sys.plan ();
List.map (fn x => (x, Math.asin x))
  [1.0, 0.0, ~0.0, ~1.0, 0.5, Math.sqrt 0.5, 2.0, Real.posInf, Real.negInf, nan];

(* "atan x" returns the arc tangent of x. atan is the inverse of
   tan. For finite arguments, the result is guaranteed to be in the
   open interval (-pi / 2, pi / 2). If x is +infinity, it returns pi / 2;
   if x is -infinity, it returns -pi / 2. *)
Math.atan;
Math.atan 0.0;
Sys.plan ();
List.map (fn x => (x, Math.atan x))
  [1.0, 0.0, ~0.0, ~1.0, 0.5, Math.sqrt 0.5, 2.0, Real.posInf, Real.negInf, nan];

(* "atan2 (y, x)" returns the arc tangent of (y / x) in the closed
   interval [-pi, pi], corresponding to angles within +-180
   degrees. The quadrant of the resulting angle is determined using
   the signs of both x and y, and is the same as the quadrant of the
   point (x, y). When x = 0, this corresponds to an angle of 90
   degrees, and the result is (real (sign y)) * pi / 2.0. It holds
   that
     sign (cos (atan2 (y, x))) = sign (x)
   and
     sign (sin (atan2 (y, x))) = sign (y)
   except for inaccuracies incurred by the finite precision of real
   and the approximation algorithms used to compute the mathematical
   functions.  Rules for exceptional cases are specified in the
   following table.

   y                 x         atan2(y, x)
   ================= ========= ==========
   +-0               0 < x     +-0
   +-0               +0        +-0
   +-0               x < 0     +-pi
   +-0               -0        +-pi
   y, 0 < y          +-0       pi/2
   y, y < 0          +-0       -pi/2
   +-y, finite y > 0 +infinity +-0
   +-y, finite y > 0 -infinity +-pi
   +-infinity        finite x  +-pi/2
   +-infinity        +infinity +-pi/4
   +-infinity        -infinity +-3pi/4
*)
Math.atan2;
Math.atan2 (0.0, 1.0);
Sys.plan ();
List.map (fn x => (x, Math.atan2 (x, 1.0)))
  [1.0, 0.0, ~1.0, 0.5, Math.sqrt 0.5, 2.0, Real.posInf, Real.negInf, nan];
List.map (fn (x, y) => (x, y, Math.atan2 (x, y)))
  [(0.0, 1.0), (~0.0, 1.0),
   (0.0, 0.0), (~0.0, 0.0),
   (0.0, ~1.0), (~0.0, ~1.0),
   (2.5, 0.0), (2.5, ~0.0),
   (~2.5, 0.0), (~2.5, ~0.0),
   (3.0, Real.posInf), (~3.0, Real.posInf),
   (4.0, Real.negInf), (~4.0, Real.negInf),
   (Real.posInf, 5.0), (Real.negInf, 5.0),
   (Real.posInf, Real.posInf), (Real.negInf, Real.posInf),
   (Real.posInf, Real.negInf), (Real.negInf, Real.negInf),
   (0.0, nan), (1.0, nan), (~1.0, nan), (Real.posInf, nan), (Real.negInf, nan),
   (nan, 0.0), (nan, 1.0), (nan, ~1.0), (nan, Real.posInf), (nan, Real.negInf),
   (nan, nan)];

(* "cos x" returns the cosine of x, measured in radians. If x is an infinity,
   returns NaN. *)
Math.cos;
Math.cos 0.0;
Sys.plan ();
List.map (fn x => (x, Math.cos x))
  [0.0, ~0.0, Math.pi, Math.pi * 0.5, ~Math.pi, Math.pi * 5.0, Real.posInf, Real.negInf, nan];

(* "cosh x" returns the hyperbolic cosine of x, that is, (e(x) + e(-x)) / 2.
   It has the properties cosh +-0 = 1, cosh +-infinity = +-infinity. *)
Math.cosh;
Math.cosh 0.0;
Sys.plan ();
List.map (fn x => (x, Math.cosh x))
  [0.0, ~0.0, 1.0, Real.posInf, Real.negInf, nan];

(* "val e : real" The base e (2.718281828...) of the natural logarithm. *)
Math.e;
Sys.plan ();

(* "exp x" returns e(x), i.e., e raised to the x(th) power. If x is
   +infinity, it returns +infinity; if x is -infinity, it returns 0. *)
Math.exp;
Math.exp 0.0;
Sys.plan ();
List.map (fn x => (x, Math.exp x))
  [0.0, ~0.0, 1.0, ~2.0, Real.posInf, Real.negInf, nan];

(* "ln x" returns the natural logarithm (base e) of x. If x < 0,
   returns NaN; if x = 0, returns -infinity; if x is infinity, returns
   infinity. *)
Math.ln;
Math.ln 1.0;
Sys.plan ();
List.map (fn x => (x, Math.ln x))
  [1.0, 2.718, Math.e, 0.0, ~0.0, ~3.0, Real.posInf, Real.negInf, nan];

(* "log10 x" returns the decimal logarithm (base 10) of x. If x < 0,
   returns NaN; if x = 0, returns -infinity; if x is infinity, returns
   infinity. *)
Math.log10;
Math.log10 1.0;
Sys.plan ();
List.map (fn x => (x, Math.log10 x))
  [1.0, 10.0, 1000.0, 0.0, ~0.0, ~3.0, Real.posInf, Real.negInf, nan];

(* "val pi : real" The constant pi (3.141592653...). *)
Math.pi;
Sys.plan ();

(* "pow (x, y)" returns x(y), i.e., x raised to the y(th) power. For
   finite x and y, this is well-defined when x > 0, or when x < 0 and
   y is integral. Rules for exceptional cases are specified below.

   x                 y                             pow(x,y)
   ================= ============================= ==========
   x, including NaN  0                             1
   |x| > 1           +infinity                     +infinity
   |x| < 1           +infinity                     +0
   |x| > 1           -infinity                     +0
   |x| < 1           -infinity                     +infinity
   +infinity         y > 0                         +infinity
   +infinity         y < 0                         +0
   -infinity         y > 0, odd integer            -infinity
   -infinity         y > 0, not odd integer        +infinity
   -infinity         y < 0, odd integer            -0
   -infinity         y < 0, not odd integer        +0
   x                 NaN                           NaN
   NaN               y <> 0                        NaN
   +-1               +-infinity                    NaN
   finite x < 0      finite non-integer y          NaN
   +-0               y < 0, odd integer            +-infinity
   +-0               finite y < 0, not odd integer +infinity
   +-0               y > 0, odd integer            +-0
   +-0               y > 0, not odd integer        +0
*)
Math.pow;
Math.pow (2.0, 3.0);
Math.pow (2.0, ~4.0);
Math.pow (100.0, 0.5);
Sys.plan ();
List.map (fn (x, y) => (x, y, Math.pow (x, y)))
  [(0.0, 0.0), (nan, 0.0),
   (2.0, Real.posInf), (~2.0, Real.posInf),
   (0.5, Real.posInf), (~0.5, Real.posInf),
   (3.0, Real.negInf), (~3.0, Real.negInf),
   (0.25, Real.negInf), (~0.25, Real.negInf),
   (Real.posInf, 0.5),
   (Real.posInf, ~0.5),
   (Real.negInf, 7.0),
   (Real.negInf, 8.0),
   (Real.negInf, ~7.0),
   (Real.negInf, ~8.0),
   (9.5, nan),
   (nan, 9.6),
   (1.0, Real.posInf), (~1.0, Real.posInf), (1.0, Real.negInf), (~1.0, Real.negInf),
   (~9.8, 2.5),
   (0.0, ~9.0), (~0.0, ~9.0),
   (0.0, ~10.0), (~0.0, ~10.0),
   (0.0, 11.0), (~0.0, 11.0),
   (0.0, 12.0), (~0.0, 12.0)];

(* "sin x" returns the sine of x, measured in radians.
   If x is an infinity, returns NaN. *)
Math.sin;
Math.sin 0.0;
Sys.plan ();
List.map (fn x => (x, Math.sin x))
  [0.0, ~0.0, Math.pi, Math.pi * 0.5, ~Math.pi, Math.pi * 5.0, Real.posInf, Real.negInf, nan];

(* "sinh x" returns the hyperbolic sine of x, that is, (e(x) - e(-x)) / 2.
   It has the property sinh +-0 = +-0, sinh +-infinity = +-infinity. *)
Math.sinh;
Math.sinh 0.0;
Sys.plan ();
List.map (fn x => (x, Math.sinh x))
  [0.0, ~0.0, 1.0, Real.posInf, Real.negInf, nan];

(* "sqrt x" returns the square root of x. sqrt (~0.0) = ~0.0.
   If x < 0, returns NaN. *)
Math.sqrt;
Math.sqrt 4.0;
Sys.plan ();
List.map (fn x => (x, Math.sqrt x))
  [4.0, 0.0, ~0.0, ~9.0, Real.posInf, Real.negInf, nan];

(* "tan x" returns the tangent of x, measured in radians. If x is an
   infinity, returns NaN. Produces infinities at various finite values,
   roughly corresponding to the singularities of the tangent function. *)
Math.tan;
Math.tan 0.0;
Sys.plan ();
List.map (fn x => (x, Math.tan x))
  [0.0, ~0.0, Math.pi, Math.pi * 0.5, ~Math.pi, Math.pi * 5.0, Real.posInf, Real.negInf, nan];

(* "tanh x" returns the hyperbolic tangent of x, that is, (sinh x) / (cosh x).
   It has the properties tanh +-0 = +-0, tanh +-infinity = +-1. *)
Math.tanh;
Math.tanh 0.0;
Sys.plan ();
List.map (fn x => (x, Math.tanh x))
  [0.0, ~0.0, 1.0, Real.posInf, Real.negInf, nan];

(* Option ------------------------------------------------------ *)
(*) val getOpt : 'a option * 'a -> 'a
Option.getOpt (SOME 1, 2);
Option.getOpt (NONE, 2);
Sys.plan ();

(*) val isSome : 'a option -> bool
Option.isSome (SOME 1);
Option.isSome NONE;
Sys.plan ();

(*) val valOf : 'a option -> 'a
Option.valOf (SOME 1);
(* sml-nj gives:
    stdIn:6.1-6.18 Warning: type vars not generalized because of
       value restriction are instantiated to dummy types (X1,X2,...)
 *)
Option.valOf NONE;
val noneInt = if true then NONE else SOME 0;
Sys.plan ();
Option.valOf noneInt;
Sys.plan ();

(*) val filter : ('a -> bool) -> 'a -> 'a option
Option.filter (fn x => x mod 2 = 0) 1;
Option.filter (fn x => x mod 2 = 0) 2;
Sys.plan ();

(*) val flatten : 'a option option -> 'a option
(*) (This function is called "Option.join" in the Standard ML basis library.)
Option.flatten (SOME (SOME 1));
Option.flatten (SOME noneInt);
(* sml-nj gives
  stdIn:1.2-1.18 Warning: type vars not generalized because of
     value restriction are instantiated to dummy types (X1,X2,...)
*)
Option.flatten NONE;
Sys.plan ();

(*) val app : ('a -> unit) -> 'a option -> unit
Option.app General.ignore (SOME 1);
Option.app General.ignore NONE;
Sys.plan ();

(*) val map : ('a -> 'b) -> 'a option -> 'b option
Option.map String.size (SOME "xyz");
Option.map String.size NONE;
Sys.plan ();

(*) val mapPartial : ('a -> 'b option) -> 'a option -> 'b option
Option.mapPartial (fn s => if s = "" then NONE else (SOME (String.size s))) (SOME "xyz");
Option.mapPartial (fn s => if s = "" then NONE else (SOME (String.size s))) NONE;
Option.mapPartial (fn s => if s = "" then NONE else (SOME (String.size s))) (SOME "");
Sys.plan ();

(*) val compose : ('a -> 'b) * ('c -> 'a option) -> 'c -> 'b option
Option.compose (String.size,
                (fn s => if s = "" then NONE
                 else SOME (String.substring (s, 1, String.size s))))
               "";
Option.compose (String.size,
                (fn s => if s = "" then NONE
                 else SOME (String.substring (s, 0, String.size s))))
               "a";
Option.compose (String.size,
                (fn s => if s = "" then NONE
                 else SOME (String.substring (s, 0, String.size s))))
               "";
Sys.plan ();

(*) val composePartial : ('a -> 'b option) * ('c -> 'a option) -> 'c -> 'b option
Option.composePartial (fn i => if i = 0 then NONE else (SOME i),
                       fn s => if s = "" then NONE else SOME (String.size s))
                      "abc";
Option.composePartial (fn i => if i = 0 then NONE else (SOME i),
                       fn s => if s = "" then NONE else SOME (String.size s))
                      "";
Sys.plan ();

(* Real -------------------------------------------------------- *)

(*) val radix : int
Real.radix;

(*) val precision : int
Real.precision;

(*) val maxFinite : real
Real.maxFinite;

(*) val minPos : real
Real.minPos;

(*) val minNormalPos : real
Real.minNormalPos;

(*) val posInf : real
Real.posInf;

(*) val negInf : real
Real.negInf;

(* "r1 + r2" and "r1 - r2" are the sum and difference of r1 and r2. If one
   argument is finite and the other infinite, the result is infinite with the
   correct sign, e.g., 5 - (-infinity) = infinity. We also have infinity +
   infinity = infinity and (-infinity) + (-infinity) = (-infinity). Any other
   combination of two infinities produces NaN. *)
1.0 + ~3.5;

1.0 + Real.posInf;
Real.posInf + 2.5;
Real.posInf - Real.posInf;
Real.posInf + Real.posInf;
Real.posInf + Real.negInf;
Real.negInf + Real.negInf;
5.0 - Real.negInf;

(* "r1 * r2" is the product of r1 and r2. The product of zero and an infinity
   produces NaN. Otherwise, if one argument is infinite, the result is infinite
   with the correct sign, e.g., -5 * (-infinity) = infinity, infinity *
   (-infinity) = -infinity. *)
0.0 * Real.posInf;
0.0 * Real.negInf;
~0.0 * Real.negInf;
0.5 * 34.6;
Real.posInf * 2.0;
Real.posInf * Real.negInf;
Real.negInf * Real.negInf;

(* "r1 / r2" denotes the quotient of r1 and r2. We have 0 / 0 = NaN and
   +-infinity / +-infinity = NaN. Dividing a finite, non-zero number by a zero,
   or an infinity by a finite number produces an infinity with the correct sign.
   (Note that zeros are signed.) A finite number divided by an infinity is 0
   with the correct sign. *)
0.0 / 0.0;
Real.posInf / Real.negInf;
1.5 / Real.posInf;
1.5 / Real.negInf;
~1.5 / Real.negInf;
~0.0 + ~0.0;
~0.0 + 0.0;
0.0 + ~0.0;

(* "rem (x, y)" returns the remainder x - n * y, where n = trunc (x / y). The
    result has the same sign as x and has absolute value less than the absolute
    value of y. If x is an infinity or y is 0, rem returns NaN. If y is an
    infinity, rem returns x. *)
Real.rem;
Real.rem (13.0, 5.0);
Real.rem (~13.0, 5.0);
Real.rem (13.0, ~5.0);
Real.rem (~13.0, ~5.0);
Real.rem (13.0, 0.0);
Real.rem (13.0, ~0.0);
(*) In the following, Morel returns 13.0 per the spec; sml-nj returns nan.
Real.rem (13.0, Real.negInf);
(*) In the following, Morel returns 13.0 per the spec; sml-nj returns nan.
Real.rem (13.0, Real.posInf);
Sys.plan ();

(* "*+ (a, b, c)" and "*- (a, b, c)" return a * b + c and a * b - c,
   respectively. Their behaviors on infinities follow from the behaviors derived
   from addition, subtraction, and multiplication. *)
(*) TODO Real.*+ (2.0, 3.0, 7.0);
(*) TODO val it = 13.0 : real
(*) TODO Real.*- (2.0, 3.0, 7.0);
(*) TODO val it = ~1.0 : real

(* "~ r" produces the negation of r.
   ~ (+-infinity) = -+infinity. *)
~ 2.0;
~ ~3.5;
~ Real.posInf;
~ Real.negInf;
~ nan;

(* "abs r" returns the absolute value |r| of r.
    abs (+-0.0) = +0.0;
    abs (+-infinity) = +infinity;
    abs (+-NaN) = +NaN *)
Real.abs;
Real.abs ~5.5;
Real.abs Real.posInf;
Real.abs Real.negInf;
Real.abs nan;
Sys.plan ();

(* val min : real * real -> real
   val max : real * real -> real
   These return the smaller (respectively, larger) of the arguments. If exactly
   one argument is NaN, they return the other argument. If both arguments are
   NaN, they return NaN. *)
Real.min;
Real.min (3.5, 4.5);
Real.min (3.5, ~4.5);
Real.min (nan, 4.5);
Real.min (~5.5, nan);
Real.min (Real.posInf, 4.5);
Real.min (Real.negInf, 4.5);
Sys.plan ();

Real.max;
Real.max (3.5, 4.5);
Real.max (3.5, ~4.5);
Real.max (nan, 4.5);
Real.max (Real.posInf, 4.5);
Real.max (Real.negInf, 4.5);
Sys.plan ();

(* "sign r" returns ~1 if r is negative, 0 if r is zero, or 1 if r is positive.
    An infinity returns its sign; a zero returns 0 regardless of its sign.
    It raises Domain on NaN. *)
Real.sign;
Real.sign 2.0;
Real.sign ~3.0;
Real.sign 0.0;
Real.sign ~0.0;
Real.sign Real.posInf;
Real.sign Real.negInf;
Real.sign nan;
Sys.plan ();

(* "signBit r" returns true if and only if the sign of r (infinities, zeros,
   and NaN, included) is negative. *)
Real.signBit;
Real.signBit 2.0;
Real.signBit ~3.5;
Real.signBit 0.0;
Real.signBit ~0.0;
Real.signBit Real.posInf;
Real.signBit Real.negInf;
(*) Morel and SMLNJ return true, but spec would suggest false
Real.signBit nan;
(*) Morel and SMLNJ return false, but spec would suggest true
Real.signBit (~nan);
Sys.plan ();

(* "sameSign (r1, r2)" returns true if and only if signBit r1 equals
   signBit r2. *)
Real.sameSign;
Real.sameSign (2.0, 3.5);
Real.sameSign (~2.0, Real.negInf);
Real.sameSign (2.0, nan);
Real.sameSign (~2.0, nan);
Real.sameSign (nan, nan);
Sys.plan ();

(* "copySign (x, y)" returns x with the sign of y, even if y is NaN. *)
Real.copySign;
Real.copySign (2.0, Real.posInf);
Real.copySign (2.0, Real.negInf);
Real.copySign (2.0, nan);
Real.copySign (~3.5, ~nan);
Real.copySign (~3.5, nan);
Real.copySign (2.0, ~0.0);
Sys.plan ();

(* "val compare : real * real -> order" returns LESS, EQUAL, or GREATER
   according to whether its first argument is less than, equal to, or
   greater than the second. It raises IEEEReal.Unordered on unordered
   arguments. *)
Real.compare;
Real.compare (2.0, 2.0);
Real.compare (~0.0, 0.0);
Real.compare (~5.0, Real.posInf);
Real.compare (~5.0, Real.negInf);
Real.compare (Real.negInf, Real.negInf);
Real.compare (Real.negInf, nan);
Real.compare (nan, nan);
Sys.plan ();

(* "val compareReal : real * real -> IEEEReal.real_order" behaves similarly to
   "Real.compare" except that the values it returns have the extended type
   IEEEReal.real_order and it returns IEEEReal.UNORDERED on unordered
   arguments. *)
(*) TODO Real.compareReal (2.0, 2.0);
(*) TODO val it = EQUAL : IEEEReal.real_order
(*) TODO Real.compareReal (~0.0, 0.0);
(*) TODO val it = EQUAL : IEEEReal.real_order
(*) TODO Real.compareReal (~5.0, Real.posInf);
(*) TODO val it = LESS : IEEEReal.real_order
(*) TODO Real.compareReal (~5.0, Real.negInf);
(*) TODO val it = GREATER : IEEEReal.real_order
(*) TODO Real.compareReal (Real.negInf, Real.negInf);
(*) TODO val it = EQUAL : IEEEReal.real_order
(*) TODO Real.compareReal (Real.negInf, nan);
(*) TODO val it = UNORDERED : IEEEReal.real_order
(*) TODO Real.compareReal (nan, nan);
(*) TODO val it = UNORDERED : IEEEReal.real_order
(*) TODO Real.compareReal (~nan, nan);
(*) TODO val it = UNORDERED : IEEEReal.real_order
(*) TODO Real.compareReal (0.0, ~nan);
(*) TODO val it = UNORDERED : IEEEReal.real_order

(* val < : real * real -> bool
   val <= : real * real -> bool
   val > : real * real -> bool
   val >= : real * real -> bool
   These return true if the corresponding relation holds between the two reals.
  Note that these operators return false on unordered arguments, i.e., if
  either argument is NaN, so that the usual reversal of comparison under
  negation does not hold, e.g., a < b is not the same as not (a >= b). *)
3.0 < 3.0;
3.0 < 5.0;
3.0 < nan;
nan < 5.0;
3.0 < Real.posInf;
3.0 < Real.negInf;
Real.posInf < Real.posInf;

3.0 <= 3.0;
3.0 <= 5.0;
3.0 <= nan;
nan <= 5.0;
3.0 <= Real.posInf;
3.0 <= Real.negInf;
Real.posInf <= Real.posInf;

3.0 > 3.0;
3.0 > 5.0;
3.0 > nan;
nan > 5.0;
3.0 > Real.posInf;
3.0 > Real.negInf;
Real.posInf > Real.posInf;

3.0 >= 3.0;
3.0 >= 5.0;
3.0 >= nan;
nan >= 5.0;
3.0 >= Real.posInf;
3.0 >= Real.negInf;
Real.posInf >= Real.posInf;

(* "== (x, y)" eturns true if and only if neither y nor x is NaN, and y and x
   are equal, ignoring signs on zeros. This is equivalent to the IEEE =
   operator. *)
(*) TODO

(* "!= (x, y)" is equivalent to not o op == and the IEEE ?<> operator. *)
(*) TODO

(* "val ?= : real * real -> bool" returns true if either argument is NaN or if
   the arguments are bitwise equal, ignoring signs on zeros. It is equivalent
   to the IEEE ?= operator. *)
(*) TODO

(* "unordered (x, y)" returns true if x and y are unordered, i.e., at least one
   of x and y is NaN. *)
Real.unordered;
Real.unordered (1.0, 1.0);
Real.unordered (~1.0, 1.0);
Real.unordered (Real.negInf, Real.posInf);
Real.unordered (nan, 1.0);
Real.unordered (0.0, nan);

(* "isFinite x" returns true if x is neither NaN nor an infinity. *)
Real.isFinite;
Real.isFinite 0.0;
Real.isFinite ~0.0;
Real.isFinite 1.5;
Real.isFinite Real.posInf;
Real.isFinite Real.negInf;
Real.isFinite nan;

(* "isNan x" returns true if x is NaN. *)
Real.isNan;
Real.isNan 0.0;
Real.isNan ~0.0;
Real.isNan 1.5;
Real.isNan Real.posInf;
Real.isNan Real.negInf;
Real.isNan Real.minNormalPos;
Real.isNan Real.minPos;
Real.isNan nan;

(* "isNormal x" returns true if x is normal, i.e., neither zero, subnormal,
   infinite nor NaN. *)
Real.isNormal;
Real.isNormal 0.0;
Real.isNormal ~0.0;
Real.isNormal 1.5;
Real.isNormal ~0.1;
Real.isNormal Real.posInf;
Real.isNormal Real.negInf;
Real.isNormal Real.minNormalPos;
Real.isNormal Real.minPos;
Real.isNormal nan;

(* "class x" returns the IEEEReal.float_class to which x belongs. *)
(*) TODO

(* "toManExp r" returns {man, exp}, where man and exp are the mantissa and
   exponent of r, respectively. Specifically, we have the relation
     r = man * radix(exp)
   where 1.0 <= man * radix < radix. This function is comparable to frexp in
   the C library. If r is +-0, man is +-0 and exp is +0. If r is +-infinity,
   man is +-infinity and exp is unspecified. If r is NaN, man is NaN and exp
   is unspecified. *)
Real.toManExp;
Real.toManExp 0.0;
Sys.plan ();
Real.toManExp ~0.0;
Real.toManExp 0.5;
Real.toManExp 1.0;
Real.toManExp 2.0;
Real.toManExp 1.25;
Real.toManExp 2.5;
Real.toManExp ~2.5;
Real.toManExp nan;
Real.toManExp Real.posInf;
Real.toManExp Real.negInf;
Real.toManExp Real.maxFinite;
Real.toManExp (~Real.maxFinite);
Real.toManExp Real.minNormalPos;
Real.toManExp (~Real.minNormalPos);
Real.toManExp (Real.minNormalPos / 2.0);
Real.toManExp (Real.minNormalPos / 4.0);
Real.toManExp Real.minPos;
Real.minNormalPos / Real.minPos;
List.map (fn x => (x, Real.toManExp x, Real.fromManExp (Real.toManExp x)))
  [1.0, 0.0, ~0.0, ~1.0, 0.5, 2.0,
   0.0000123456, 0.00000123456, 0.000000123456, ~0.00000123456,
   Real.minPos, Real.minNormalPos, Real.maxFinite, ~Real.maxFinite,
   Real.posInf, Real.negInf, nan];

(* "fromManExp {man, exp}" returns man * radix(exp). This function is comparable
   to ldexp in the C library. Note that, even if man is a non-zero, finite real
   value, the result of fromManExp can be zero or infinity because of underflows
   and overflows. If man is +-0, the result is +-0. If man is +-infinity, the
   result is +-infinity. If man is NaN, the result is NaN. *)
Real.fromManExp;
Real.fromManExp {man = 1.0, exp = 0};
Sys.plan ();
Real.fromManExp {man = ~1.0, exp = 0};
Real.fromManExp {man = 1.0, exp = 2};
Real.fromManExp {man = 1.0, exp = ~3};
List.map (fn x => (x, Real.fromManExp (Real.toManExp x)))
  [1.0, 0.0, ~0.0, ~1.0, 0.5, 2.0,
   0.0000123456, 0.00000123456, 0.000000123456, ~0.00000123456,
   Real.minPos, Real.minNormalPos, Real.maxFinite, ~Real.maxFinite,
   Real.posInf, Real.negInf, nan];

(* "split r" returns {whole, frac}, where frac and whole are the fractional and
   integral parts of r, respectively. Specifically, whole is integral,
   |frac| < 1.0, whole and frac have the same sign as r, and r = whole + frac.
   This function is comparable to modf in the C library. If r is +-infinity,
   whole is +-infinity and frac is +-0. If r is NaN, both whole and frac are
   NaN. *)
Real.split;
Real.split 2.0;
Real.split 0.0;
Real.split ~0.0;
Real.split 2.75;
Real.split ~12.25;
Real.split Real.posInf;
Real.split Real.negInf;
Real.split nan;
Sys.plan ();

(* "realMod r" returns the fractional part of r. "realMod" is equivalent to
   "#frac o split". *)
Real.realMod;
Real.realMod 2.0;
Real.realMod 0.0;
Real.realMod ~0.0;
Real.realMod 2.75;
Real.realMod ~12.25;
Real.realMod Real.posInf;
Real.realMod Real.negInf;
Real.realMod nan;
Sys.plan ();

(* "nextAfter (r, t)" returns the next representable real after r in the
   direction of t. Thus, if t is less than r, nextAfter returns the largest
   representable floating-point number less than r. If r = t then it returns
   r. If either argument is NaN, this returns NaN. If r is +-infinity, it
   returns +-infinity. *)
(*) TODO

(* "checkFloat x" raises Overflow if x is an infinity, and raises Div if x is
   NaN. Otherwise, it returns its argument. This can be used to synthesize
   trapping arithmetic from the non-trapping operations given here. Note,
   however, that infinities can be converted to NaNs by some operations, so
   that if accurate exceptions are required, checks must be done after each
   operation. *)
Real.checkFloat;
Real.checkFloat 0.0;
Real.checkFloat ~0.0;
Real.checkFloat 1.5;
Real.checkFloat Real.posInf;
Real.checkFloat Real.negInf;
Real.checkFloat Real.minNormalPos;
Real.checkFloat Real.minPos;
Real.checkFloat nan;

(* "realFloor r", "realCeil r", "realTrunc r", "realRound r" convert real values
   to integer-valued reals. realFloor produces floor(r), the largest integer not
   larger than r. realCeil produces ceil(r), the smallest integer not less than r.
   realTrunc rounds r towards zero, and realRound rounds to the integer-valued
   real value that is nearest to r. If r is NaN or an infinity, these functions
   return r. *)
Real.realFloor;
Real.realCeil;
Real.realTrunc;
Real.realRound;
fun f x = (Real.realFloor x, Real.realCeil x, Real.realTrunc x, Real.realRound x);
f 0.0;
f ~2.0;
f ~1.75;
f ~2.25;
f ~2.5;
f ~3.5;
f Real.negInf;
f Real.posInf;
f nan;

(* "floor r", "ceil r", "trunc r", "round r" convert reals to integers.
   floor produces floor(r), the largest int not larger than r.
   ceil produces ceil(r), the smallest int not less than r.
   trunc rounds r towards zero.
   round yields the integer nearest to r. In the case of a tie, it rounds to the
   nearest even integer.

   They raise Overflow if the resulting value cannot be represented as an int,
   for example, on infinity. They raise Domain on NaN arguments.

   These are respectively equivalent to:
     toInt IEEEReal.TO_NEGINF r
     toInt IEEEReal.TO_POSINF r
     toInt IEEEReal.TO_ZERO r
     toInt IEEEReal.TO_NEAREST r *)
Real.floor;
Real.ceil;
Real.trunc;
Real.round;
fun f x = (Real.floor x, Real.ceil x, Real.trunc x, Real.round x);
f 0.0;
f ~2.0;
f ~1.75;
f ~2.25;
f ~2.5;
f ~3.5;
f Real.negInf;
f Real.posInf;
f nan;

(* "toInt mode x", "toLargeInt mode x" convert the argument x to an integral
   type using the specified rounding mode. They raise Overflow if the result
   is not representable, in particular, if x is an infinity. They raise
   Domain if the input real is NaN. *)
(*) TODO

(* "fromInt i", "fromLargeInt i" convert the integer i to a real value. If the
   absolute value of i is larger than maxFinite, then the appropriate infinity
   is returned. If i cannot be exactly represented as a real value, then the
   current rounding mode is used to determine the resulting value. The top-level
   function real is an alias for Real.fromInt. *)
Real.fromInt;
Real.fromInt 1;
Real.fromInt ~2;
Sys.plan ();

(*) real is a synonym for Real.fromInt
real;
real ~2;
Sys.plan ();

(*  "fromString s" scans a `real` value from a string. Returns `SOME(r)` if a
    `real` value can be scanned from a prefix of `s`, ignoring any initial
    whitespace; otherwise, it returns `NONE`. This function is equivalent to
    `StringCvt.scanString scan`. *)
Real.fromString;
Real.fromString "~0.0";
Sys.plan ();
Real.fromString "~23.45e~06";
(*) sml-nj allows both '-' and '~', both 'E' and 'e', and 0s after 'E'.
Real.fromString "-1.5";
Real.fromString "~1.5";
Real.fromString "-1.5e-9";
Real.fromString "-1.5e-09";
Real.fromString "-1.5E-9";
Real.fromString "-1.5E~9";
Real.fromString "-1.5E~09";
Real.fromString "-1.5e~09";
(*) In sml-nj, ".", ".e", ".e-", "e5", ".e7" are invalid
Real.fromString ".";
Real.fromString ".x";
Real.fromString ".e";
Real.fromString ".e~";
Real.fromString "e5";
Real.fromString ".e7";
(*) Letters and whitespace at the end, and whitespace at the start, are ignored
Real.fromString "1.5x";
Real.fromString " 1.5 x ";
Real.fromString "1.5e";
Real.fromString "1.5e~";
Real.fromString "1.5e~0";
Real.fromString "1.5e2";
Real.fromString "1.5e2e";
Real.fromString "1.5e2e3";
Real.fromString "2e3.4";
Real.fromString "  2e3.4";
Real.fromString "2.x";
(*) fromString cannot parse "inf", "nan" etc.
Real.fromString "inf";
Real.fromString "~inf";
Real.fromString "-inf";
Real.fromString "nan";

List.map (fn x => (x, Real.fromString (Real.toString x)))
  [1.0, 0.0, ~0.0, ~1.0, 0.5, 2.0,
   0.0000123456, 0.00000123456, 0.000000123456, ~0.00000123456,
   Real.minPos, Real.minNormalPos, Real.maxFinite, ~Real.maxFinite,
   Real.posInf, Real.negInf, nan];

(* "toLarge r", "fromLarge r" convert between values of type real and type
   LargeReal.real. If r is too small or too large to be represented as a real,
   fromLarge will convert it to a zero or an infinity.

   Note that SMLNJ diverges from the the spec. The spec:
      Real.toLarge : real -> LargeReal.real
      Real.fromLarge : LargeReal.real -> real
   SMLNJ:
      Real.toLarge : real -> real
      Real.fromLarge : IEEEReal.rounding_mode -> real -> real *)
(*) TODO

(* "fmt spec r", "toString r" convert reals into strings. The conversion
   provided by the function fmt is parameterized by spec, which has the
   following forms and interpretations.

   SCI arg
     Scientific notation:
       [~]?[0-9].[0-9]+?E[0-9]+
     where there is always one digit before the decimal point, nonzero if the
     number is nonzero. arg specifies the number of digits to appear after the
     decimal point, with 6 the default if arg is NONE. If arg is SOME(0), no
     fractional digits and no decimal point are printed.
   FIX arg
     Fixed-point notation:
       [~]?[0-9]+.[0-9]+?
     arg specifies the number of digits to appear after the decimal point, with
     6 the default if arg is NONE. If arg is SOME(0), no fractional digits and
     no decimal point are printed.
   GEN arg
     Adaptive notation: the notation used is either scientific or fixed-point
     depending on the value converted. arg specifies the maximum number of
     significant digits used, with 12 the default if arg is NONE.
   EXACT
     Exact decimal notation: refer to IEEEReal.toString for a complete
     description of this format.
   In all cases, positive and negative infinities are converted to "inf" and
   "~inf", respectively, and NaN values are converted to the string "nan".

   Refer to StringCvt.realfmt for more details concerning these formats,
   especially the adaptive format GEN.

   fmt raises Size if spec is an invalid precision, i.e., if spec is
     SCI (SOME i) with i < 0
     FIX (SOME i) with i < 0
     GEN (SOME i) with i < 1
   The exception should be raised when fmt spec is evaluated.

  The fmt function allows the user precise control as to the form of the
  resulting string. Note, therefore, that it is possible for fmt to produce
  a result that is not a valid SML string representation of a real value.

  The value returned by toString is equivalent to:
    (fmt (StringCvt.GEN NONE) r)
 *)
(*) TODO Real.fmt

Real.toString;
Real.toString 0.0;
Sys.plan ();
Real.toString ~0.0;
Real.toString 0.01;
Real.toString Real.minPos;
Real.toString Real.minNormalPos;
Real.toString 1234567890123.45;
Real.toString 123456789012.3;
Real.toString 12345678901.23;
Real.toString 123456789.0123;
(*) We return '1.23456E~6' but sml-nj returns '1.23456E~06'.
Real.toString 0.00000123456;
Real.toString 0.0000123456;
Real.toString 0.000123456;
Real.toString 0.0001234567;
Real.toString 0.00012345678;
Real.toString 0.0000123;
Real.toString ~0.000065432;
Real.toString nan;
Real.toString Real.negInf;
Real.toString Real.posInf;
List.map (fn x => (x, Real.toString x))
  [1.0, 0.0, ~0.0, ~1.0, 0.5, 2.0,
   0.0000123456, 0.00000123456, 0.000000123456, ~0.00000123456,
   Real.minPos, Real.minNormalPos, Real.maxFinite, ~Real.maxFinite,
   Real.posInf, Real.negInf, nan];

(* "scan getc strm", "fromString s" scan a real value from character source. The
   first version reads from ARG/strm/ using reader getc, ignoring initial
   whitespace. It returns SOME(r,rest) if successful, where r is the scanned
   real value and rest is the unused portion of the character stream strm.
   Values of too large a magnitude are represented as infinities; values of too
   small a magnitude are represented as zeros. The second version returns
   SOME(r) if a real value can be scanned from a prefix of s, ignoring any
   initial whitespace; otherwise, it returns NONE. This function is equivalent
   to StringCvt.scanString scan.

   The functions accept real numbers with the following format:
     [+~-]?([0-9]+.[0-9]+? | .[0-9]+)(e | E)[+~-]?[0-9]+?

   It also accepts the following string representations of non-finite values:
     [+~-]?(inf | infinity | nan)
   where the alphabetic characters are case-insensitive. *)
(*) TODO

(* "toDecimal r", "fromDecimal d" convert between real values and decimal
   approximations. Decimal approximations are to be converted using the
   IEEEReal.TO_NEAREST rounding mode. toDecimal should produce only as many
   digits as are necessary for fromDecimal to convert back to the same number.
   In particular, for any normal or subnormal real value r, we have the bit-wise
   equality:
     fromDecimal (toDecimal r) = r.

   For toDecimal, when the r is not normal or subnormal, then the exp field is
   set to 0 and the digits field is the empty list. In all cases, the sign and
   class field capture the sign and class of r.

  For fromDecimal, if class is ZERO or INF, the resulting real is the
  appropriate signed zero or infinity. If class is NAN, a signed NaN is
  generated. If class is NORMAL or SUBNORMAL, the sign, digits and exp fields
  are used to produce a real number whose value is

    s * 0.d(1)d(2)...d(n) 10(exp)

  where digits = [d(1), d(2), ..., d(n)] and where s is -1 if sign is true and 1
  otherwise. Note that the conversion itself should ignore the class field, so
  that the resulting value might have class NORMAL, SUBNORMAL, ZERO, or INF. For
  example, if digits is empty or a list of all 0's, the result should be a
  signed zero. More generally, very large or small magnitudes are converted to
  infinities or zeros.

  If the argument to fromDecimal does not have a valid format, i.e., if the
  digits field contains integers outside the range [0,9], it returns NONE.

  Implementation note: Algorithms for accurately and efficiently converting
  between binary and decimal real representations are readily available, e.g.,
  see the technical report by Gay[CITE]. *)
(*) TODO

(* Relational -------------------------------------------------- *)

Relational.count [1, 2, 3];
Relational.count [];
Relational.count [false];
Sys.plan ();

Relational.exists [1, 2, 3];
Relational.exists [];
Relational.exists [false];
Sys.plan ();

Relational.notExists [1, 2, 3];
Relational.notExists [];
Relational.notExists [false];
Sys.plan ();

val emp = [
  {empno=7839, ename="KING", mgr=0},
  {empno=7566, ename="JONES", mgr=7839},
  {empno=7698, ename="BLAKE", mgr=7839},
  {empno=7782, ename="CLARK", mgr=7839},
  {empno=7788, ename="SCOTT", mgr=7566},
  {empno=7902, ename="FORD", mgr=7566},
  {empno=7499, ename="ALLEN", mgr=7698},
  {empno=7521, ename="WARD", mgr=7698},
  {empno=7654, ename="MARTIN", mgr=7698},
  {empno=7844, ename="TURNER", mgr=7698},
  {empno=7900, ename="JAMES", mgr=7698},
  {empno=7934, ename="MILLER", mgr=7782},
  {empno=7876, ename="ADAMS", mgr=7788},
  {empno=7369, ename="SMITH", mgr=7902}];
Relational.iterate
  (from e in emp where e.mgr = 0)
  fn (oldList, newList) =>
      (from d in newList,
          e in emp
      where e.mgr = d.empno
      yield e);
Sys.plan ();

Relational.sum [1, 2, 3];
Relational.sum [1.0, 2.5, 3.5];
Sys.plan ();

Relational.max [1, 2, 3];
Relational.max [1.0, 2.5, 3.5];
Relational.max ["a", "bc", "ab"];
Relational.max [false, true];
Sys.plan ();

Relational.min [1, 2, 3];
Relational.min [1.0, 2.5, 3.5];
Relational.min ["a", "bc", "ab"];
Relational.min [false, true];
Sys.plan ();

Relational.only [2];
Relational.only [1, 2, 3];
Relational.only [];
Sys.plan ();

[1, 2] union [3] union [] union [4, 2, 5];
[] union [];
Sys.plan ();

[1, 2] except [2] except [3] except [];
[] except [];
["a"] except ["a"];
["a", "b", "c", "a"] except ["a"];
["a", "b", "c", "a"] except ["c", "b", "c"];
["a", "b"] except ["a", "c"] except ["a"];
Sys.plan ();

[1, 2] intersect [2] intersect [0, 2, 4];
[1, 2] intersect [];
[] intersect [1, 2];
["a", "b", "a"] intersect ["b", "a"];
[(1, 2), (2, 3)] intersect [(2, 4), (1, 2)];
[1, 2, 3] intersect [2, 3, 4] except [1, 3, 5];
[1, 2, 3] except [1, 3, 5] intersect [2, 3, 4];
Sys.plan ();

1 elem [1, 2, 3];
1 elem [2, 3, 4];
1 elem [];
[] elem [[0], [1, 2]];
[] elem [[0], [], [1, 2]];
(1, 2) elem [(0, 1), (1, 2)];
(1, 2) elem [(0, 1), (2, 3)];
Sys.plan ();

1 notElem [1, 2, 3];
1 notElem [2, 3, 4];
1 notElem [];
[] notElem [[0], [1, 2]];
[] notElem [[0], [], [1, 2]];
(1, 2) notElem [(0, 1), (1, 2)];
(1, 2) notElem [(0, 1), (2, 3)];
Sys.plan ();

(* Sys --------------------------------------------------------- *)

(*) val env : unit -> string list
Sys.env;
Sys.env ();

env;
env ();

(*) val plan : unit -> string
Sys.plan;
1 + 2;
Sys.plan ();

(*) val set : string * 'a -> unit
Sys.set;
Sys.set ("hybrid", false);
Sys.plan ();

(*) val show : string -> string option
Sys.show;
Sys.show "hybrid";
Sys.set ("hybrid", true);
Sys.show "hybrid";
Sys.show "optionalInt";
Sys.plan ();

Sys.set ("optionalInt", ~5);
Sys.show "optionalInt";

(*) val unset : string -> unit
Sys.unset;
Sys.unset "hybrid";
Sys.unset "optionalInt";
Sys.plan ();

(* Vector ------------------------------------------------------ *)

(*) Vector.fromList : 'a list -> 'a vector
Vector.fromList;
Vector.fromList [1,2];
Sys.plan ();

(* supported in sml-nj but not morel:
 #[1,2];
 *)

(* sml-nj says:
  stdIn:3.1-3.19 Warning: type vars not generalized because of
     value restriction are instantiated to dummy types (X1,X2,...)
  val it = #[] : ?.X1 vector
*)
Vector.fromList [];
Sys.plan ();

(*) Vector.maxLen: int
Vector.maxLen;
Sys.plan ();

(*) Vector.tabulate : int * (int -> 'a) -> 'a vector
Vector.tabulate;
Vector.tabulate (5, let fun fact n = if n = 0 then 1 else n * fact (n - 1) in fact end);
Sys.plan ();

(*) Vector.length : 'a vector -> int
Vector.length;
Vector.length (Vector.fromList [1,2,3]);
Sys.plan ();

(*) Vector.sub : 'a vector * int -> 'a
Vector.sub;
Vector.sub (Vector.fromList [3,6,9], 2);
Vector.sub (Vector.fromList [3,6,9], ~1);
Vector.sub (Vector.fromList [3,6,9], 3);
Sys.plan ();

(*) Vector.update : 'a vector * int * 'a -> 'a vector
Vector.update;
Vector.update (Vector.fromList ["a","b","c"], 1, "baz");
Vector.update (Vector.fromList ["a","b","c"], ~1, "baz");
Vector.update (Vector.fromList ["a","b","c"], 3, "baz");
Sys.plan ();

(*) Vector.concat : 'a vector list -> 'a vector
Vector.concat;
Vector.concat [Vector.fromList ["a","b"],
  Vector.fromList [], Vector.fromList ["c"]];
Sys.plan ();

(*) Vector.appi : (int * 'a -> unit) -> 'a vector -> unit
Vector.appi;
Vector.appi (fn (i,s) => ignore s) (Vector.fromList ["a", "b", "c"]);
Sys.plan ();

(*) Vector.app  : ('a -> unit) -> 'a vector -> unit
Vector.app;
Vector.app (fn s => ignore s) (Vector.fromList ["a", "b", "c"]);
Sys.plan ();

(*) Vector.mapi : (int * 'a -> 'b) -> 'a vector -> 'b vector
Vector.mapi;
Vector.mapi (fn (i, s) => String.sub (s, i)) (Vector.fromList ["abc", "xyz"]);
Sys.plan ();

(*) Vector.map  : ('a -> 'b) -> 'a vector -> 'b vector
Vector.map;
Vector.map (fn s => String.sub (s, 0)) (Vector.fromList ["abc", "xyz"]);
Sys.plan ();

(*) Vector.foldli : (int * 'a * 'b -> 'b) -> 'b -> 'a vector -> 'b
Vector.foldli;
Vector.foldli (fn (i,j,a) => a + i * j) 0 (Vector.fromList [2,3,4]);
Sys.plan ();

(*) Vector.foldri : (int * 'a * 'b -> 'b) -> 'b -> 'a vector -> 'b
Vector.foldri;
Vector.foldri (fn (i,j,a) => a + i * j) 0 (Vector.fromList [2,3,4]);
Sys.plan ();

(*) Vector.foldl  : ('a * 'b -> 'b) -> 'b -> 'a vector -> 'b
Vector.foldl;
Vector.foldl (fn (j,a) => a + j) 0 (Vector.fromList [2,3,4]);
Sys.plan ();

(*) Vector.foldr  : ('a * 'b -> 'b) -> 'b -> 'a vector -> 'b
Vector.foldr;
Vector.foldr (fn (j,a) => a + j) 0 (Vector.fromList [2,3,4]);
Sys.plan ();

(*) Vector.findi : (int * 'a -> bool) -> 'a vector -> (int * 'a) option
Vector.findi;
Vector.findi (fn (i,j) => j < i) (Vector.fromList [10,8,6,4,2]);
Sys.plan ();

(*) Vector.find  : ('a -> bool) -> 'a vector -> 'a option
Vector.find;
Vector.find (fn j => j mod 2 = 0) (Vector.fromList [3,5,7,8,9]);
Sys.plan ();

(*) Vector.exists : ('a -> bool) -> 'a vector -> bool
Vector.exists;
Vector.exists (fn j => j mod 2 = 0) (Vector.fromList [3,5,7,8,9]);
Sys.plan ();

(*) Vector.all : ('a -> bool) -> 'a vector -> bool
Vector.all;
Vector.all (fn j => j mod 2 = 0) (Vector.fromList [3,5,7,8,9]);
Sys.plan ();

(*) Vector.collate : ('a * 'a -> order) -> 'a vector * 'a vector -> order
Vector.collate;
Vector.collate
  (fn (i,j) => if i < j then LESS else if i = j then EQUAL else GREATER)
  (Vector.fromList [1,3,5], Vector.fromList [1,3,6]);
Sys.plan ();

(*) End builtIn.sml
