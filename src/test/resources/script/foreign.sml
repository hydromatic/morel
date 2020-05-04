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
 * Tests for foreign data sources ("scott" and "foodmart").
 *)

scott;
#dept scott;
scott.dept;
from d in scott.dept;
from d in scott.dept yield {d.dname, d.loc};
foodmart;
#days foodmart;

from d in scott.dept
where d.deptno elem (from e in scott.emp
                     where e.job elem ["ANALYST", "PRESIDENT"]
                     yield e.deptno);

from d in scott.dept
where d.deptno notElem (from e in scott.emp
                        where e.job notElem ["ANALYST", "PRESIDENT"]
                        yield e.deptno);

(*) Functions
(*) Clerks get a 20% raise each year; everyone else gets 5%
val emp2 =
  from e in scott.emp
  yield {e.deptno, e.job, e.ename,
    salIn = fn year => e.sal
       + e.sal
         * (year - 2019.0)
         * if e.job = "CLERK" then 0.2 else 0.05};
from e in emp2
  yield {e.ename, e.job, salIn2020 = e.salIn 2020.0, salIn2021 = e.salIn 2021.0};

(*) End foreign.sml
