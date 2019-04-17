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

from e in emps yield #deptno e;

from e in emps yield {deptno = #deptno e, one = 1};

from e in emps yield ((#id e) + (#deptno e));

from e2 in (from e in emps yield #deptno e) yield e2 + 1;

(* Disabled: '=' should have lower precedence than '#deptno e' fun application
from e in emps where #deptno e = 30 yield #name e;
*)

from e in emps where false yield (#deptno e);

(*) Function defined inside query
from e in emps
where #deptno e < 30
yield
  let
    fun p1 x = x + 1
  in
    p1 (#id e)
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

from e in emps where (#deptno e) = 30 yield (#id e);

(*) cartesian product
from e in emps, e2 in emps yield (#name e) ^ "-" ^ (#name e2);

(*) cartesian product, missing yield
from d in depts, i in integers;

(*) join
from e in emps, d in depts
  where (#deptno e) = (#deptno d)
  yield {id = (#id e), deptno = (#deptno e), ename = (#name e), dname = (#name d)};

(*) exists (defining the "exists" function ourselves)
(*) and correlated sub-query
(* disabled due to "::"
let
  fun exists [] = false
    | exists hd :: tl = true
in
  from e in emps
  where exists (from d in depts
                where (#deptno d) = (#deptno e)
                andalso (#name d) = "Engineering")
  yield (#name e)
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
  where in_ (#deptno e) (from d in depts
                where (#name d) = "Engineering"
                yield (#deptno d))
  yield (#name e)
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
(*
from e in emps
group (#deptno e) as deptno
  compute sum of (#id e) as sumId,
          count of e as count;
val it =
  [{deptno=10,id=100,name="Fred"},{deptno=20,id=101,name="Velma"},
   {deptno=30,id=102,name="Shaggy"},{deptno=30,id=103,name="Scooby"}] : {deptno:int, id:int, name:string} list
*)

(*) 'group' with no aggregates
from e in emps
group (#deptno e) as deptno;

(*) composite 'group' with no aggregates
from e in emps
group (#deptno e) as deptno, (#id e) mod 2 as idMod2;

(*) 'group' with 'where' and complex argument to 'sum'
(*
from e in emps
where (#deptno e) < 30
group (#deptno e) as deptno
  compute sum of (#id e) as sumId,
          min of (#id e) + (#deptno e) as minIdPlusDeptno;
val it = [{deptno=10,id=100,name="Fred"},{deptno=20,id=101,name="Velma"}] : {deptno:int, id:int, name:string} list
*)

(*) 'group' with join
(*
from e in emps, d in depts
where (#deptno e) = (#deptno d)
group (#deptno e) as deptno,
 (#name e) as dname
compute sum of (#id e) as sumId;
val it =
  [{d={deptno=10,name="Sales"},e={deptno=10,id=100,name="Fred"}},
   {d={deptno=20,name="HR"},e={deptno=20,id=101,name="Velma"}},
   {d={deptno=30,name="Engineering"},e={deptno=30,id=102,name="Shaggy"}},
   {d={deptno=30,name="Engineering"},e={deptno=30,id=103,name="Scooby"}}] : {d:{deptno:int, name:string}, e:{deptno:int, id:int, name:string}} list
*)

(*) empty 'group'
(*
from e in emps
group compute sum of (#id e) as sumId;
val it =
  [{deptno=10,id=100,name="Fred"},{deptno=20,id=101,name="Velma"},
   {deptno=30,id=102,name="Shaggy"},{deptno=30,id=103,name="Scooby"}] : {deptno:int, id:int, name:string} list
*)

(*) Should we allow 'yield' following 'group'? Here's a possible syntax.
(*) We need to introduce a variable name, but "as g" syntax isn't great.
(*
from emps as e
group (#deptno e) as deptno
  compute sum of (#id e) as sumId,
          count of e as count
  as g
yield {deptno = (#deptno g), avgId = (#sumId g) / (#count g)}
*)

(*) End relational.sml
