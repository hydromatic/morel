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

(* Structures -------------------------------------------------- *)
General;
List;
List.rev;
List.rev [1,2,3];
Option;
Option.compose;
String;
Relational;

(* Operators --------------------------------------------------- *)
2 + 3;
2 + 3 * 4;
Sys.plan ();

fn x => x + 1;
Sys.plan ();

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

(*) val toString : string -> String.string
(*) val scan       : (char, 'a) StringCvt.reader
(*)                    -> (string, 'a) StringCvt.reader
(*) val fromString : String.string -> string option
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
