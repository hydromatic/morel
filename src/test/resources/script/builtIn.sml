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

(* Miscellaneous ----------------------------------------------- *)
ignore;
ignore (1 + 2);

(*) map is alias for List_map
map;
map (fn x => x) [];

(* String ------------------------------------------------------ *)

(* TODO replace '_' with '.' when we can parse long identifiers;
   e.g. 'String_maxSize' becomes 'String.maxSize' *)

(*) val maxSize : int
String_maxSize;

(*) val size : string -> int
String_size;
String_size "abc";
String_size "";

(*) val sub : string * int -> char
String_sub;
String_sub("abc", 0);
String_sub("abc", 2);
(* TODO: need exceptions
String_sub("abc", 20);
*)

(*) val extract   : string * int * int option -> string
(* TODO: support the 'int option' argument *)
String_extract;
String_extract("abc", 1);

(*) val substring : string * int * int -> string
String_substring;
String_substring("hello, world", 2, 7);
String_substring("hello, world", 0, 1);
String_substring("hello", 5, 0);
String_substring("", 0, 0);

(*) val ^ : string * string -> string
(* TODO *)

(*) val concat : string list -> string
String_concat;
String_concat ["a", "bc", "def"];
String_concat ["a"];
String_concat [];

(*) val concatWith : string -> string list -> string
String_concatWith;
String_concatWith "," ["a", "bc", "def"];
String_concatWith "," ["a"];
String_concatWith "," ["", ""];
String_concatWith "," [];

(*) val str : char -> string
String_str;
String_str #"a";

(*) val implode : char list -> string
String_implode;
String_implode [#"a", #"b", #"c"];
String_implode [];

(*) val explode : string -> char list
String_explode;
String_explode "abc";
String_explode "";

(*) val map : (char -> char) -> string -> string
String_map;
String_map (fn c => if c = #"a" then #"A" else if c = #"c" then #"C" else c) "abc";
String_map (fn c => if c = #"a" then #"A" else if c = #"c" then #"C" else c) "";

(*) val translate : (char -> string) -> string -> string
String_translate;
String_translate (fn c => if c = #"a" then "AA" else if c = #"c" then "CCC" else "-") "abc";
String_translate (fn c => if c = #"a" then "AA" else if c = #"c" then "CCC" else "-") "";

(*) val tokens : (char -> bool) -> string -> string list
(*) val fields : (char -> bool) -> string -> string list
(*) val isPrefix    : string -> string -> bool
String_isPrefix;
String_isPrefix "he" "hello";
String_isPrefix "el" "hello";
String_isPrefix "lo" "hello";
String_isPrefix "bonjour" "hello";
String_isPrefix "el" "";
String_isPrefix "" "hello";
String_isPrefix "" "";

(*) val isSubstring : string -> string -> bool
String_isSubstring;
String_isSubstring "he" "hello";
String_isSubstring "el" "hello";
String_isSubstring "lo" "hello";
String_isSubstring "bonjour" "hello";
String_isSubstring "el" "";
String_isSubstring "" "hello";
String_isSubstring "" "";

(*) val isSuffix    : string -> string -> bool
String_isSuffix;
String_isSuffix "he" "hello";
String_isSuffix "el" "hello";
String_isSuffix "lo" "hello";
String_isSuffix "bonjour" "hello";
String_isSuffix "el" "";
String_isSuffix "" "hello";
String_isSuffix "" "";

(*) val compare : string * string -> order
(*) val collate : (char * char -> order) -> string * string -> order
(*) val <  : string * string -> bool
(*) val <= : string * string -> bool
(*) val >  : string * string -> bool
(*) val >= : string * string -> bool

(*) val toString : string -> String_string
(*) val scan       : (char, 'a) StringCvt.reader
(*)                    -> (string, 'a) StringCvt.reader
(*) val fromString : String_string -> string option
(*) val toCString : string -> String_string
(*) val fromCString : String_string -> string option

(* List -------------------------------------------------------- *)

(*) val nil : 'a list
List_nil;

(*) val null : 'a list -> bool
List_null;
List_null [];
List_null [1];

(*) val length : 'a list -> int
List_length;
List_length [];
List_length [1,2];

(*) val @ : 'a list * 'a list -> 'a list
List_at;
List_at ([1], [2, 3]);
List_at ([1], []);
List_at ([], [2]);
List_at ([], []);

(*) val hd : 'a list -> 'a
List_hd;
List_hd [1,2,3];

(*) val tl : 'a list -> 'a list
List_tl;
List_tl [1,2,3];

(*) val last : 'a list -> 'a
List_last;
List_last [1,2,3];

(*) val getItem : 'a list -> ('a * 'a list) option
List_getItem;
List_getItem [1,2,3];
List_getItem [1];

(*) val nth : 'a list * int -> 'a
List_nth;
List_nth ([1,2,3], 2);
List_nth ([1], 0);

(*) val take : 'a list * int -> 'a list
List_take;
List_take ([1,2,3], 0);
List_take ([1,2,3], 1);
List_take ([1,2,3], 3);

(*) val drop : 'a list * int -> 'a list
List_drop;
List_drop ([1,2,3], 0);
List_drop ([1,2,3], 1);
List_drop ([1,2,3], 3);

(*) val rev : 'a list -> 'a list
List_rev;
List_rev [1,2,3];
List_rev [2,1];
List_rev [1];
List_rev [];

(*) val concat : 'a list list -> 'a list
List_concat;
List_concat [[1],[2,3],[4,5,6]];
List_concat [[1],[],[4,5,6]];
List_concat [[],[],[]];
List_concat [];

(*) val revAppend : 'a list * 'a list -> 'a list
List_revAppend;
List_revAppend ([1,2],[3,4,5]);
List_revAppend ([1],[3,4,5]);
List_revAppend ([],[3,4,5]);
List_revAppend ([1,2],[]);
List_revAppend ([],[]);

(*) val app : ('a -> unit) -> 'a list -> unit
List_app;
List_app (fn x => ignore (x + 2)) [2,3,4];
List_app (fn x => ignore (x + 2)) [];

(*) val map : ('a -> 'b) -> 'a list -> 'b list
List_map;
List_map (fn x => x + 1) [1,2,3];
List_map (fn x => x + 1) [];

(*) val mapPartial : ('a -> 'b option) -> 'a list -> 'b list
List_mapPartial;
List_mapPartial (fn x => x + 1) [1,2,3];
List_mapPartial (fn x => x + 1) [];

(*) val find : ('a -> bool) -> 'a list -> 'a option
List_find;
List_find (fn x => x mod 7 = 0) [2,3,5,8,13,21,34];

(*) val filter : ('a -> bool) -> 'a list -> 'a list
List_filter;
List_filter (fn x => x mod 2 = 0) [0,1,2,3,4,5];
List_filter (fn x => x mod 2 = 0) [1,3];
List_filter (fn x => x mod 2 = 0) [];

(*) val partition : ('a -> bool) -> 'a list -> 'a list * 'a list
List_partition;
List_partition (fn x => x mod 2 = 0) [0,1,2,3,4,5];
List_partition (fn x => x mod 2 = 0) [1];
List_partition (fn x => x mod 2 = 0) [];

(*) val foldl : ('a * 'b -> 'b) -> 'b -> 'a list -> 'b
List_foldl;
List_foldl (fn (a, b) => a + b) 0 [1,2,3];
List_foldl (fn (a, b) => a + b) 0 [];
List_foldl (fn (a, b) => b) 0 [1,2,3];
List_foldl (fn (a, b) => a - b) 0 [1,2,3,4];

(*) val foldr : ('a * 'b -> 'b) -> 'b -> 'a list -> 'b
List_foldr;
List_foldr (fn (a, b) => a + b) 0 [1,2,3];
List_foldr (fn (a, b) => a + b) 0 [];
List_foldr (fn (a, b) => b) 0 [1,2,3];
List_foldr (fn (a, b) => a - b) 0 [1,2,3,4];

(*) val exists : ('a -> bool) -> 'a list -> bool
List_exists;
List_exists (fn x => x mod 2 = 0) [1,3,5];
List_exists (fn x => x mod 2 = 0) [2,4,6];
List_exists (fn x => x mod 2 = 0) [1,2,3];
List_exists (fn x => x mod 2 = 0) [];

(*) val all : ('a -> bool) -> 'a list -> bool
List_all;
List_all (fn x => x mod 2 = 0) [1,3,5];
List_all (fn x => x mod 2 = 0) [2,4,6];
List_all (fn x => x mod 2 = 0) [1,2,3];
List_all (fn x => x mod 2 = 0) [];

(*) val tabulate : int * (int -> 'a) -> 'a list
List_tabulate;
List_tabulate (5, let fun fact n = if n = 0 then 1 else n * fact (n - 1) in fact end);
List_tabulate (1, let fun fact n = if n = 0 then 1 else n * fact (n - 1) in fact end);
List_tabulate (0, let fun fact n = if n = 0 then 1 else n * fact (n - 1) in fact end);

(*) val collate : ('a * 'a -> order) -> 'a list * 'a list -> order
List_collate;
List_collate (fn (x, y) => if x < y then ~1 else if x = y then 0 else 1) ([1,2,3], [1,3,4]);
List_collate (fn (x, y) => if x < y then ~1 else if x = y then 0 else 1) ([1,2,3], [1,2,2]);
List_collate (fn (x, y) => if x < y then ~1 else if x = y then 0 else 1) ([1,2,3], [1,2]);
List_collate (fn (x, y) => if x < y then ~1 else if x = y then 0 else 1) ([1,2,3], [1,2,3,4]);
List_collate (fn (x, y) => if x < y then ~1 else if x = y then 0 else 1) ([1,2,3], []);
List_collate (fn (x, y) => if x < y then ~1 else if x = y then 0 else 1) ([], []);

(* Note: real ML uses "order" not "int", for example
List.collate (fn (x,y) => if x < y then LESS else if x = y then EQUAL else GREATER) ([1,2,3], [1,2,3,4]);
val it = LESS : order
 *)

(* Relational -------------------------------------------------- *)

Relational_count [1, 2, 3];
Relational_count [];
Relational_count [false];

Relational_sum [1, 2, 3];
Relational_sum [1.0, 2.5, 3.5];

Relational_max [1, 2, 3];
Relational_max [1.0, 2.5, 3.5];
Relational_max ["a", "bc", "ab"];
Relational_max [false, true];

Relational_min [1, 2, 3];
Relational_min [1.0, 2.5, 3.5];
Relational_min ["a", "bc", "ab"];
Relational_min [false, true];

(* Sys --------------------------------------------------------- *)

(*) val env : unit -> string list
Sys_env;
Sys_env ();

env;
env ();

(*) End builtIn.sml
