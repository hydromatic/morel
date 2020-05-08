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
 * Recursive queries and fixed-point algorithms.
 *
 * State adjacency data is based upon
 * https://writeonly.wordpress.com/2009/03/20/adjacency-list-of-states-of-the-united-states-us/
 *)

(*) State adjacency
val adjacent_states =
 [{state="AK", adjacents=[]},
  {state="AL", adjacents=["MS", "TN", "GA", "FL"]},
  {state="AR", adjacents=["MO", "TN", "MS", "LA", "TX", "OK"]},
  {state="AZ", adjacents=["CA", "NV", "UT", "CO", "NM"]},
  {state="CA", adjacents=["OR", "NV", "AZ"]},
  {state="CO", adjacents=["WY", "NE", "KS", "OK", "NM", "AZ", "UT"]},
  {state="CT", adjacents=["NY", "MA", "RI"]},
  {state="DC", adjacents=["MD", "VA"]},
  {state="DE", adjacents=["MD", "PA", "NJ"]},
  {state="FL", adjacents=["AL", "GA"]},
  {state="GA", adjacents=["FL", "AL", "TN", "NC", "SC"]},
  {state="HI", adjacents=[]},
  {state="IA", adjacents=["MN", "WI", "IL", "MO", "NE", "SD"]},
  {state="ID", adjacents=["MT", "WY", "UT", "NV", "OR", "WA"]},
  {state="IL", adjacents=["IN", "KY", "MO", "IA", "WI"]},
  {state="IN", adjacents=["MI", "OH", "KY", "IL"]},
  {state="KS", adjacents=["NE", "MO", "OK", "CO"]},
  {state="KY", adjacents=["IN", "OH", "WV", "VA", "TN", "MO", "IL"]},
  {state="LA", adjacents=["TX", "AR", "MS"]},
  {state="MA", adjacents=["RI", "CT", "NY", "NH", "VT"]},
  {state="MD", adjacents=["VA", "WV", "PA", "DC", "DE"]},
  {state="ME", adjacents=["NH"]},
  {state="MI", adjacents=["WI", "IN", "OH"]},
  {state="MN", adjacents=["WI", "IA", "SD", "ND"]},
  {state="MO", adjacents=["IA", "IL", "KY", "TN", "AR", "OK", "KS", "NE"]},
  {state="MS", adjacents=["LA", "AR", "TN", "AL"]},
  {state="MT", adjacents=["ND", "SD", "WY", "ID"]},
  {state="NC", adjacents=["VA", "TN", "GA", "SC"]},
  {state="ND", adjacents=["MN", "SD", "MT"]},
  {state="NE", adjacents=["SD", "IA", "MO", "KS", "CO", "WY"]},
  {state="NH", adjacents=["VT", "ME", "MA"]},
  {state="NJ", adjacents=["DE", "PA", "NY"]},
  {state="NM", adjacents=["AZ", "UT", "CO", "OK", "TX"]},
  {state="NV", adjacents=["ID", "UT", "AZ", "CA", "OR"]},
  {state="NY", adjacents=["NJ", "PA", "VT", "MA", "CT"]},
  {state="OH", adjacents=["PA", "WV", "KY", "IN", "MI"]},
  {state="OK", adjacents=["KS", "MO", "AR", "TX", "NM", "CO"]},
  {state="OR", adjacents=["CA", "NV", "ID", "WA"]},
  {state="PA", adjacents=["NY", "NJ", "DE", "MD", "WV", "OH"]},
  {state="RI", adjacents=["CT", "MA"]},
  {state="SC", adjacents=["GA", "NC"]},
  {state="SD", adjacents=["ND", "MN", "IA", "NE", "WY", "MT"]},
  {state="TN", adjacents=["KY", "VA", "NC", "GA", "AL", "MS", "AR", "MO"]},
  {state="TX", adjacents=["NM", "OK", "AR", "LA"]},
  {state="UT", adjacents=["ID", "WY", "CO", "NM", "AZ", "NV"]},
  {state="VA", adjacents=["NC", "TN", "KY", "WV", "MD", "DC"]},
  {state="VT", adjacents=["NY", "NH", "MA"]},
  {state="WA", adjacents=["ID", "OR"]},
  {state="WI", adjacents=["MI", "MN", "IA", "IL"]},
  {state="WV", adjacents=["OH", "PA", "MD", "VA", "KY"]},
  {state="WY", adjacents=["MT", "SD", "NE", "CO", "UT", "ID"]}];

(*) Coastal states
val coastal_states = ["WA", "OR", "CA", "TX", "LA", "MS",
  "AL", "GA", "FL", "SC", "NC", "VA", "MD", "DE", "NJ",
  "NY", "CT", "RI", "MA", "ME", "NH", "AK", "HI"];

(*) Pairs of states that share a border
val pairs =
  from s in adjacent_states,
      adjacent in s.adjacents
  yield {s.state, adjacent};

(*) States that border both TN and FL
from p in pairs,
    q in pairs
  where p.state = "TN"
    andalso p.adjacent = q.state
    andalso q.adjacent = "FL"
  yield p.adjacent;

(*) Is a state adjacent to another?
fun is_adjacent x y =
  case (from p in pairs where p.state = x andalso p.adjacent = y) of
    [] => false
  | _ => true;

is_adjacent "CA" "NY";
is_adjacent "CA" "OR";
is_adjacent "OR" "OR";

(*) States that are n hops of a given state
fun states_within x 0 = [x]
  | states_within x 1 =
    (from p in pairs
     where p.state = x
     yield p.adjacent)
  | states_within x n =
    (from p in (from p in pairs where p.state = x),
        a in states_within p.adjacent (n - 1)
     group a);

states_within "CA" 0;
states_within "CA" 1;
states_within "CA" 2;
from s in states_within "CA" 2 group compute count;
from s in states_within "CA" 3 group compute count;
(* It takes 11 steps to reach to all 48 contiguous states plus DC.
   But it takes 2 minutes, so the following expression is disabled.
   See later, the same expression computed efficiently using semi-naive. *)
if true then [49] else from s in states_within "CA" 11 group compute count;
states_within "HI" 0;
states_within "HI" 1;
states_within "HI" 100;
states_within "ME" 0;
states_within "ME" 1;
states_within "ME" 2;
states_within "ME" 3; (*) maine is not 3 steps from itself

(*) Finding a square root using the Babylonian method
(*) (An example of a scalar fixed-point query.)
fun approx_sqrt n a = (n / a + a) * 0.5;
approx_sqrt 100.0 1.0;

(*) Create a closure for the problem of finding the square root of 100.
(*) Applying the function to its own result, we approach the correct answer.
val as100 = approx_sqrt 100.0;
as100 100.0;
as100 (as100 100.0);
as100 (as100 (as100 100.0));
as100 (as100 (as100 (as100 100.0)));

(*) A fixed-point operator will carry out the iteration for us,
(*) given any scalar function f and an initial approximation a.
(*) "fixp" stands for "fixed-point over projection".
fun fixp f a =
  let
    val a2 = f a
  in
    if a2 = a then
      a
    else
      fixp f a2
  end;
fixp as100 100.0;
fixp as100 1.0;
fixp as100 0.0;
fixp as100 ~1.0;

(*) Given a list of strings, 'prefixes' returns a list of their
(*) prefixes that are one character shorter.
val prefixes = List.map (fn s =>
  if s = "" then s
  else String.substring(s, 0, String.size s - 1));
prefixes ["cat", "dog", "", "car", "cart"];

(*) Fixed-point over union.
(*) A naive algorithm recomputes the whole set each hop,
(*) so is not very efficient.
fun fixu_naive f a =
  let
    val a2 = f a
    val a3 = from i in a union a2 group i
  in
    if a3 = a then
      a
    else
      fixu_naive f a3
  end;
fixu_naive prefixes ["cat", "dog", "", "car", "cart"];

(*) Fixed-point over union, with an iteration limit 'n'.
(*) A semi-naive algorithm applies the function only to
(*) the deltas (the elements added by the function last
(*) time) so is more efficient than the naive algorithm.
fun fixu_semi_naive (f, a, n) =
  let
    fun contains (list, e) =
      List.exists (fn e2 => e = e2) list
    fun minus (list1, list2) =
      List.filter (fn e => not (contains (list2, e))) list1
    fun fixInc (a, delta, i) =
      let
        val a2 = f delta
        val newDelta = minus (a2, a)
      in
        if newDelta = [] orelse i = n then
          a
        else
          fixInc (a union newDelta, newDelta, i + 1)
      end
  in
    fixInc ([], a, 0)
  end;
fixu_semi_naive (prefixes, ["cat", "dog", "", "car", "cart"], ~1);

(*) Now, back to the states.
(*) The semi-naive algorithm gets to 11 hops more efficiently.
fun states_within2 s n =
  fixu_semi_naive ((fn states =>
    from s in states,
        p in pairs
      where p.state = s
      group p.adjacent), [s], n);
states_within2 "CA" 1;
states_within2 "CA" 2;
from s in states_within2 "CA" 8 group compute count;
from s in states_within2 "CA" 9 group compute count;
from s in states_within2 "CA" 10 group compute count;
from s in states_within2 "CA" 11 group compute count;

(*) Floyd-Warshall algorithm (shortest path in weighted graph)
(*) Data from https://en.wikipedia.org/wiki/Floyd%E2%80%93Warshall_algorithm
val edges =
 [{source="b", target="a", weight=4},
  {source="a", target="c", weight=~2},
  {source="b", target="c", weight=3},
  {source="c", target="d", weight=2},
  {source="d", target="b", weight=~1}];
fun shortest_path edges =
  let
    val vertices =
      from v in (from {source, target, weight} in edges yield source)
          union
          (from {source, target, weight} in edges yield target)
        group v
    val edges0 =
      from e in edges
          union
          from v in vertices yield {source = v, target = v, weight = 0}
        group e.source, e.target compute weight = min of e.weight
    fun sp (paths, []) = paths
      | sp (paths, v :: vs) =
        let
          val paths2 =
            from p1 in paths,
                p2 in paths
              where p1.target = v
              andalso p2.source = v
              yield {p1.source, p2.target, weight = p1.weight + p2.weight}
          val paths3 =
            from p in paths union paths2
              group p.source, p.target compute weight = min of p.weight
        in
          sp (paths3, vs)
        end
  in
    from p in sp (edges0, vertices) order p.source, p.target
  end;
shortest_path edges;

(*) End fixedPoint.sml
