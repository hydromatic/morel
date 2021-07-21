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
 * Tests queries that are to execute mostly in Calcite.
 *)

(*) Query in Hybrid mode (100% Calcite)
Sys.set ("hybrid", true);
from e in scott.emp
where e.deptno = 20
yield e.empno;
Sys.plan();

(*) Query in Hybrid mode (20% Morel -> 80% Calcite)
from e in (List.filter (fn e2 => e2.empno < 7700) scott.emp)
where e.deptno = 20
yield e.empno;
Sys.plan();

(*) Query in Hybrid mode (99% Calcite; there is a variable but it is not
(*) referenced by Calcite code)
let
  val twenty = 20
in
  from e in scott.emp
  where e.deptno = 20
  yield e.empno
end;
Sys.plan();

(*) Query in Hybrid mode (90% Calcite; Calcite code references a variable
(*) and an expression from the enclosing environment)
let
  val ten = 7 + 3
  val deptNos = ten :: 20 :: 30 :: [40]
in
  from e in scott.emp
  where e.deptno = List.nth (deptNos, 1)
  yield e.empno + 13 mod ten
end;
Sys.plan();

(*) Query in Hybrid mode (90% Calcite; Calcite code references a function
(*) from the enclosing environment)
let
  fun double x = x * 2
in
  from d in scott.dept
  yield double d.deptno
end;
Sys.plan();

(*) Query on an empty list
from e in []
  yield {a = 1, b = true};
Sys.plan();

(*) Query on an empty list whose element is referenced
from e in []
  yield {a = 1 + e, b = true};
Sys.plan();

(*) Query on a singleton list whose element is not referenced
from e in [0]
  yield {a = 1, b = true};
Sys.plan();

(*) Simple query with scan, filter, project
from e in scott.emp where e.deptno = 30 yield e.empno;
Sys.plan();

(*) Equivalent #1; result and plan should be the same
let
  val emps = #emp scott
in
  from e in emps
  where e.deptno = 30
  yield e.empno
end;
Sys.plan();

(*) Equivalent #2; result and plan should be the same
let
  val emps = #emp scott
  val thirty = 30
in
  from e in emps
  where e.deptno = thirty
  yield e.empno
end;
Sys.plan();

(*) Equivalent #3; result and plan should be the same
map (fn e => (#empno e))
  (List.filter (fn e => (#deptno e) = 30) (#emp scott));
Sys.plan();

(*) Union followed by group
(*
from x in (from e in scott.emp yield e.deptno)
  union (from d in scott.dept yield d.deptno)
group x compute c = count;
*)

"end";
(*) End hybrid.sml
