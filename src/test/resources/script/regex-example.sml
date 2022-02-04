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
 *
 * Example of matching regular expressions using combinators.
 *
 * Code and text is based upon sections 3.2 and 3.3 of "Seminaïve Evaluation for
 * a Higher-Order Functional Language" (Arntzenius, Krishnaswami, 2020).
 *)

(* "chars" splits a string into a list of (index, character) pairs. *)
fun chars s =
    List.tabulate (size s, fn i => (i, String.sub (s, i)));
chars "abc";

(* The type of regular expression matchers:
     type re = string -> int * int
   A regular expression takes a string "s" and returns the set of all
   pairs "(i, j)" such that the substring "si, . . . , sj − 1" matches the
   regular expression. *)

(*) Prints a solution
fun matchStrings matches s =
    from (i, j) in matches
      yield substring (s, i, j - i);

(* "sym" finds all matches for a single character "c"; it
    returns the range "(i, i + 1)" whenever "(i, c) is in "chars s": *)
fun sym c s =
    from (i, c2) in chars s
      where c2 = c
      yield (i, i + 1);
sym #"l" "hello";
sym #"z" "hello";

(* "epsilon" finds all matches for the empty regex, i.e. all empty substrings,
   including the one "beyond the last character" *)
fun epsilon s =
    List.tabulate ((size s) + 1, fn i => (i, i));
epsilon "";
epsilon "abc";

(* Appending regexes r1, r2 amounts to relation composition, since we wish to
   find all substrings consisting of adjacent substrings "si . . . sj − 1" and
   "sj . . . sk − 1" matching r1 and r2 respectively: *)
fun seq r1 r2 s =
    from (i, j) in r1 s,
        (j2, k) in r2 s
      where j = j2 + 0
      yield (i + 0, k + 0);
(*) match "l" followed by "o" in "hello"
seq (sym #"l") (sym #"o") "hello";

(*) Match a string by converting it into a sequence of character matches.
fun syms cs s =
    foldr (fn (c, r) => seq (sym c) r) epsilon (explode cs) s;
syms "el" "hello";

(* Similarly, regex alternation "r1 | r2" is accomplished by unioning all
   matches of each: *)
fun alt r1 r2 s = (r1 s) union (r2 s);
(*) match "e" or "o" in "hello"
val matches = alt (sym #"e") (sym #"o") "hello";
matchStrings matches "hello";

(*) Transitive closure
fun trans edges =
    Relational.iterate edges
        fn (oldEdges, newEdges) =>
            from (i, j) in newEdges,
                (j2, k) in edges
              where j = j2
              yield (i, k);
trans [(1, 2), (4, 6), (2, 5), (2, 3)];

(* The most interesting regular expression combinator is Kleene star. Thinking
   relationally, if we consider the set of pairs "(i, j)" matching some regex
   "r", then "r*" matches its reflexive, transitive closure. This can be
   accomplished by combining "epsilon" and "trans". *)
fun star r s =
    (epsilon s) union (trans (r s));
val matches = star (sym #"l") "hello";
matchStrings matches "hello";

(*) regex "(e | r) l*" applied to "hello world"
val matches =
    seq (alt (sym #"e") (sym #"r")) (star (sym #"l")) "hello world";
matchStrings matches "hello world";

(* -------------------------------------------------------------------------- *)
(* The combinators in the previous section found all matches within a given
   substring, but often we are not interested in all matches: we only want to
   know if a string can match starting at a particular location. We can easily
   refactor the combinators above to work in this style, which illustrates
   the benefits of tightly integrating functional and relational styles of
   programming – we can use functions to manage strict input/output divisions,
   and relations to manage nondeterminism and search.

     type re = (string * int) -> int list

   Our new type of combinators takes a string and a starting position, and
   returns a set of ending positions. For example, "sym c" checks if "c" occurs
   at the start position "i", yielding "[i + 1]" if it does and the empty set
   otherwise, while epsilon simply returns the start position i. *)

(* "matchStringsPos" iterates over all positions in a string, returning
    print a pair (index, substring) for each match. It helps with testing. *)
fun range n =
    List.tabulate (n, fn i => i);
fun matchStringsPos r s =
  from i in range (size s),
      j in r (s, i)
    yield (i, substring (s, i, j - i));

fun symPos c (s, i) =
    from (i2, c2) in chars s
      where i2 = i andalso c2 = c
      yield i + 1;
val r = symPos #"l";
r ("hello", 1);
r ("hello", 2);
r ("hello", 3);
matchStringsPos r "hello";

fun epsilonPos (s, i) = [i];
val r = epsilonPos;
matchStringsPos r "hello";

(* Appending regexes "seq r1 r2" simply applies "r2" starting from every ending
   position that "r1" can find: *)
fun seqPos r1 r2 (s, i) =
    from j in r1 (s, i),
        k in r2 (s, j)
      yield k;
val r = seqPos (symPos #"l") (symPos #"o");
matchStringsPos r "hello";

(* Regex alternation "alt" is effectively unchanged: *)
fun altPos r1 r2 x = (r1 x) union (r2 x);
val r = altPos (symPos #"e") (symPos #"o");
matchStringsPos r "hello";

(* Finally, Kleene star is implemented by recursively appending r to a set x of
   matches found so far. It’s worth noting that this definition is effectively
   left-recursive – it takes the endpoints from the fixed point x, and then
   continues matching using the argument r. This should make clear that this is
   not just plain old functional programming – we are genuinely relying upon the
   fixed point semantics. *)
fun starPos r (s, i) =
    Relational.iterate [i]
        fn (oldList, newList) =>
            from j in newList,
                k in r (s, j)
              yield k;
val r = starPos (symPos #"l");
matchStringsPos r "hello";

(*) regex "(e | r) l*" applied to "hello world"
val r = seqPos (altPos (symPos #"e") (symPos #"r")) (starPos (symPos #"l"));
matchStringsPos r "hello world";

(*) End regex-example.sml
