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

from emps as e yield e;

from emps as e yield #id e;

from emps as e yield (#id e) - 100;

from emps as e yield #deptno e;

from emps as e yield {deptno = #deptno e, one = 1};

from emps as e yield ((#id e) + (#deptno e));

from (from emps as e yield #deptno e) as e2 yield e2 + 1;

(* Disabled: '=' should have lower precedence than '#deptno e' fun application
from emps as e where #deptno e = 30 yield #name e;
*)

from emps as e where false yield (#deptno e);

(* Disabled due to CCE
fun range i j =
  if i >= j then [] else i :: (range (i + 1) j);
*)

(* Disabled due to NPE in apply
range 0 5;

from range 0 5 as i where i mod 2 = 1 yield i;
*)
val integers = [0,1,2,3,4];

from integers as i where i mod 2 = 1 yield i;

(*) missing yield
from integers as i where i mod 2 = 1;

from emps as e where (#deptno e) = 30 yield (#id e);

(*) cartesian product
from emps as e, emps as e2 yield (#name e) ^ "-" ^ (#name e2);

(*) cartesian product, missing yield
from depts as d, integers as i;

(*) join
from emps as e, depts as d
  where (#deptno e) = (#deptno d)
  yield {id = (#id e), deptno = (#deptno e), ename = (#name e), dname = (#name d)};

(*) exists (defining the "exists" function ourselves)
(*) and correlated sub-query
(* disabled due to "::"
let
  fun exists [] = false
    | exists hd :: tl = true
in
  from emps as e
  where exists (from depts as d
                where (#deptno d) = (#deptno e)
                andalso (#name d) = "Engineering")
  yield (#name e)
end;
*)

(*) in (defining the "in_" function ourselves)
(* disabled due to "::"
let
  fun in_ e [] = false
    | in_ e (h :: t) = e = h orelse (in_ e t)
in
  from emps as e
  where in_ (#deptno e) (from depts as d
                where (#name d) = "Engineering"
                yield (#deptno d))
  yield (#name e)
end;
*)

(*) End relational.sml
