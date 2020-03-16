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


let val emp0 = {id = 100, name = "Fred", deptno = 10} in #id emp0 end;

val emp0 = {id = 100, name = "Fred", deptno = 10};
val emp1 = {id = 101, name = "Velma", deptno = 20};
val emp2 = {id = 102, name = "Shaggy", deptno = 30};
val emp3 = {id = 103, name = "Scooby", deptno = 30};

val emps = [emp0, emp1, emp2, emp3];

val emps =
  let
    val emp0 = {id = 100, name = "Fred", deptno = 10}
    and emp1 = {id = 101, name = "Velma", deptno = 20}
    and emp2 = {id = 102, name = "Shaggy", deptno = 30}
    and emp3 = {id = 103, name = "Scooby", deptno = 30}
  in
    [emp0, emp1, emp2, emp3]
  end;

val depts =
  [{deptno = 10, name = "Sales"},
   {deptno = 20, name = "HR"},
   {deptno = 30, name = "Engineering"},
   {deptno = 40, name = "Support"}];

from e in emps yield e;

from e in emps yield #id e;

from e in emps yield (#id e) - 100;

from e in emps yield e.id - 100;

from e in emps yield #deptno e;

from e in emps yield e.deptno;

from e in emps yield {deptno = #deptno e, one = 1};

from e in emps yield {deptno = e.deptno, one = 1};

from e in emps yield {e.deptno, one = 1};

from e in emps yield ((#id e) + (#deptno e));

from e in emps yield (e.id + e.deptno);

from e2 in (from e in emps yield #deptno e) yield e2 + 1;

from e2 in (from e in emps yield e.deptno) yield e2 + 1;

(* Disabled: '=' should have lower precedence than '#deptno e' fun application
from e in emps where #deptno e = 30 yield #name e;
*)

from e in emps where false yield e.deptno;

(*) Function defined inside query
from e in emps
where e.deptno < 30
yield
  let
    fun p1 x = x + 1
  in
    p1 e.id
  end;

(* Disabled due to CCE
fun range i j =
  if i >= j then [] else i :: (range (i + 1) j);
*)

(* Disabled due to NPE in apply
range 0 5;

from i in range 0 5 where i mod 2 = 1 yield i;
*)
val integers = [0,1,2,3,4];

from i in integers where i mod 2 = 1 yield i;

(*) missing yield
from i in integers where i mod 2 = 1;

from e in emps where e.deptno = 30 yield e.id;

(*) cartesian product
from e in emps, e2 in emps yield e.name ^ "-" ^ e2.name;

(*) cartesian product, missing yield
from d in depts, i in integers;

(*) join
from e in emps, d in depts
  where e.deptno = d.deptno
  yield {id = e.id, deptno = e.deptno, ename = e.name, dname = d.name};

(*) as above, using abbreviated record syntax
from e in emps, d in depts
  where e.deptno = d.deptno
  yield {e.id, e.deptno, ename = e.name, dname = d.name};

(*) join, no yield
from e in emps, d in depts;

(*) join where neither variable is referenced
from e in emps, d in depts
  yield 0;

(*) join where right variable is not referenced
from e in emps, d in depts
  yield e.id;

(*) join where left variable is not referenced
from e in emps, d in depts
  yield d.deptno;

(*) join group where neither variable is referenced
from e in emps, d in depts
  group compute sum of 1 as count;

(*) join group where right variable is not referenced
from e in emps, d in depts
  group e.deptno compute sum of 1 as count;

(*) exists (defining the "exists" function ourselves)
(*) and correlated sub-query
(* disabled due to "::"
let
  fun exists [] = false
    | exists hd :: tl = true
in
  from e in emps
  where exists (from d in depts
                where d.deptno = e.deptno
                andalso d.name = "Engineering")
  yield e.name
end;
val it = ["Shaggy","Scooby"] : string list
*)

(*) in (defining the "in_" function ourselves)
(* disabled due to "::"
let
  fun in_ e [] = false
    | in_ e (h :: t) = e = h orelse (in_ e t)
in
  from e in emps
  where in_ e.deptno (from d in depts
                where d.name = "Engineering"
                yield d.deptno)
  yield e.name
end;
val it = ["Shaggy","Scooby"] : string list
*)

(*) foldl function (built into SML)
let
  fun foldl f start [] = start
    | foldl f start (hd :: tl) = foldl f (f (start, hd)) tl
in
  foldl (fn (x, y) => x + y) 0 [2,3,4]
end;

(*) "group by" via higher-order functions
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
  aggregate [(fn {id=id1,name=name1,deptno=deptno1} => id1, sum)] emps
end;
*)

(*) Basic 'group'
from e in emps
group e.deptno as deptno
  compute sum of e.id as sumId,
          count of e as count;

(*) As previous, without the implied "as deptno" in "group"
from e in emps
group e.deptno
  compute sum of e.id as sumId,
          count of e as count;

(*) 'group' with no aggregates
from e in emps
group e.deptno as deptno;

from e in emps
group e.deptno;

(*) composite 'group' with no aggregates
from e in emps
group e.deptno, e.id mod 2 as idMod2;

(*) 'group' with 'where' and complex argument to 'sum'
from e in emps
where e.deptno < 30
group e.deptno as deptno
  compute sum of e.id as sumId,
          sum of e.id + e.deptno as sumIdPlusDeptno;

(*) 'group' with join
from e in emps, d in depts
where e.deptno = d.deptno
group e.deptno,
 e.name as ename,
 d.name as dname
compute sum of e.id as sumId;

(*) empty 'group'
from e in emps
group compute sum of e.id as sumId;

(*) user-defined aggregate function
let
  fun siz [] = 0
    | siz (ht :: tl) = 1 + (siz tl)
in
  from e in emps
  group e.deptno as deptno
  compute siz of e.id as size
end;

(*) Group followed by yield
from e in emps
group e.deptno
  compute sum of e.id as sumId,
          count of e as count
yield {deptno, avgId = sumId / count};

(*) Similar, using a sub-from:
from g in (
  from e in emps
  group e.deptno
    compute sum of e.id as sumId,
            count of e as count)
yield {g.deptno, avgId = g.sumId / g.count};

(*) Group followed by group
from e in emps
  group e.deptno, e.deptno mod 2 as parity
    compute sum of e.id as sumId
  group parity
    compute sum of sumId as sumSumId;

(*) Group followed by group followed by yield
from e in emps
  group e.deptno, e.deptno mod 2 as parity
    compute sum of e.id as sumId
  group parity
    compute sum of sumId as sumSumId
  yield sumSumId * parity;

(*) Join followed by composite group
from e in emps,
    d in depts
  where e.deptno = d.deptno
  group e.id + d.deptno as x, e.deptno
    compute sum of e.id as sumId;

(*) Join followed by single group (from right input)
from e in emps,
    d in depts
  where e.deptno = d.deptno
  group d.deptno;

(*) Join followed by single group (from left input)
from e in emps,
    d in depts
  where e.deptno = d.deptno
  group e.deptno;

(*) Temporary functions
let
  fun abbrev s =
    if String_size s > 5
    then (String_substring (s, 0, 3)) ^ "."
    else s;
  fun shouldPromote e =
    e.id < e.deptno * 4
in
  from e in emps
  where shouldPromote e
  yield {e.id, e.deptno, abbrev_name = abbrev e.name}
end;

(*) There's no flatMap in the standard library, so define one
fun flatMap f l = List_concat (List_map f l);

flatMap String_explode ["ab", "", "def"];

(*) A function that runs a query and returns the result
fun employeesIn deptno =
  from e in emps
  where e.deptno = deptno;

employeesIn 10;
employeesIn 25;
employeesIn 30;

(*) Using 'map' to stick together results
List_map employeesIn [10, 25, 30];

(*) Same, using 'from'
from deptno in [10, 25, 30]
  yield employeesIn deptno;

(*) Flatten (using flatMap)
flatMap employeesIn [10, 25, 30];

(*) Flatten (using a lateral join); compare to SQL 'CROSS APPLY'
from deptno in [10, 25, 30],
    e in employeesIn deptno
  yield e;

(*) A deep nested loop
from e in
  (from e in
    (from e in
      (from e in
        (from e in
          (from e in
            (from e in
              (from e in
                (from e in
                  (from e in
                    (from e in emps
                     yield e)
                   yield e)
                 yield e)
               yield e)
             yield e)
           yield e)
         yield e)
       yield e)
     yield e)
   yield e);

(*) dummy
from message in ["the end"];

(*) End relational.sml
