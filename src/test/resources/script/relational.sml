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
Sys.set ("printDepth", 6);
Sys.set ("printLength", 64);
Sys.set ("stringDepth", ~1);

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

from e in emps yield {x = e.deptno} where x > 10 yield {y = x} where y < 30;

from e in emps yield {x = e.deptno} where x > 10 yield {x = x} where x < 30;

from e in emps yield ((#id e) + (#deptno e));

from e in emps yield (e.id + e.deptno);

from e2 in (from e in emps yield #deptno e) yield e2 + 1;

from e2 in (from e in emps yield e.deptno) yield e2 + 1;

from e in emps where #deptno e = 30 yield #name e;

from e in emps where false yield e.deptno;

(*) 'yield' uses 'case' to deconstruct a tuple
from p in [(1, 2), (3, 5)]
  yield case p of (x, y) => {x, y};

(*) 'yield' uses 'case' to deconstruct a record
from p in [{x = 1, y = 2}, {x = 3, y = 5}]
  yield case p of {x=x, y=y} => (x, y);

(*) 'yield' uses 'case' to deconstruct a record using shorthand syntax
from p in [{x = 1, y = 2},{x = 3, y = 5}]
  yield case p of {x, y} => (x, y);

from e in emps
  yield {x = e.id + e.deptno, y = e.id - e.deptno}
  yield x + y;

from e in emps
  where e.deptno < 30
  yield {x = e.id + e.deptno, y = e.id - e.deptno}
  where x > 120
  yield x + y;

from e in emps
  yield {e.deptno, e.name}
  order deptno desc;

from e in emps
  yield {e.deptno}
  order deptno desc
  yield {deptno};

from e in emps
  yield {e.deptno}
  order deptno desc;

(*) 'yield' followed by 'order'
from e in emps
  yield {e.deptno, x = e.deptno, e.name}
  order deptno desc, name;

(*) 'yield' followed by 'order', then 'yield', then 'order'
from e in emps
  yield {e.deptno, x = e.deptno, e.name}
  order deptno, name desc
  yield {name = x, x = name, z = x + 1}
  order z desc;

(*) singleton record 'yield' followed by 'where'
from e in emps
  yield {d = e.deptno}
  where d > 10;

(*) singleton record 'yield' followed by 'where' followed by 'yield'
from e in emps
  yield {d = e.deptno}
  where d > 10
  yield {d = d};

(*) singleton record 'yield' followed by singleton 'group'
from e in emps
  yield {d = e.deptno}
  group d
  order d;

(*) singleton record 'yield' followed by 'group'
from e in emps
  yield {d = e.deptno}
  group d compute c = count
  order d;

(*) singleton record 'yield' followed by 'order'
from e in emps
  yield {d = e.deptno}
  order d desc;

(*) singleton record 'yield' followed by 'order' then singleton 'yield'
from e in emps
  yield {d = e.deptno}
  order d desc
  yield {d = d};

(*) singleton record 'yield' followed by 'order' then 'yield'
from e in emps
  yield {d = e.deptno}
  order d desc
  yield {tens = d / 10, units = d mod 10};

(*) record whose only field is a record with only one field
from x in [{a = {a = 1}}, {a = {a = 2}}]
  yield {b = x.a};

from x in [{a = {a = 1}}, {a = {a = 2}}]
  yield {b = x.a.a};

from x in [{a = {a = 1}}, {a = {a = 2}}]
  yield {x.a};

from i in [1, 2, 3]
  yield {i = {i = i}};

from e in emps
  yield
    let
      val x = e.id + e.deptno
      and y = e.id - e.deptno
    in
      x + y
    end;

(*) Function defined inside query
from e in emps
where e.deptno < 30
yield
  let
    fun p1 x = x + 1
  in
    p1 e.id
  end;

fun range i j =
  if i >= j then [] else i :: (range (i + 1) j);

range 0 5;

from i in range 0 5 where i mod 2 = 1 yield i;

from i in range 0 5 where i mod 2 = 1 yield i;

(*) missing yield
from i in range 0 5 where i mod 2 = 1;

from e in emps where e.deptno = 30 yield e.id;

(*) cartesian product
from e in emps, e2 in emps yield e.name ^ "-" ^ e2.name;

(*) cartesian product, missing yield
from d in depts, i in range 0 5;

(*) 3-way comma join
from x in ["a", "b"],
    y in ["c", "d"],
    z in ["e", "f"];

(*) same, using 'join'
from x in ["a", "b"]
  join y in ["c", "d"]
  join z in ["e", "f"];

(*) 'join' with '='
from x in [1, 2]
  join y = 3;

(*) 'join' with '=' and 'on'
from x in [1, 2]
  join y = 3 on y = x + 1;

(*) 'join' with '=' and 'on false'
from x in [1, 2]
  join y = 3 on false;

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
  group compute count = sum of 1;

(*) as above, without 'of'
from e in emps, d in depts
  group compute count = count;

(*) join group where right variable is not referenced
from e in emps, d in depts
  group e.deptno compute count = sum of 1
  order deptno;

(*) join with intervening 'where'
(*) we can't write ', d in depts' after 'where'
from e in emps
  where e.name elem ["Shaggy", "Fred"]
  join d in depts
  where e.deptno = d.deptno;

(*) 'where' then 'join on'
from x in [0,3,6,9,12]
where x > 1
join y in [0,2,4,6,8,10,12] on x = y;

(*) join with intervening 'group'
(* TODO: resolve ambiguity
from e in emps
  group e.deptno compute count
  join d in depts
  where deptno = d.deptno;

(*) as previous, using 'on' rather than 'where'
from e in emps
  group e.deptno compute count
  join d in depts on deptno = d.deptno;
*)

(*) exists (defining the "exists" function ourselves)
(*) and correlated sub-query
let
  fun exists [] = false
    | exists (hd :: tl) = true
in
  from e in emps
  where exists (from d in depts
                where d.deptno = e.deptno
                andalso d.name = "Engineering")
  yield e.name
end;

(*) in (defining the "in_" function ourselves)
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

(*) elem (equivalent to SQL's IN)
from e in emps
  where e.deptno elem (from d in depts
    where d.name = "Engineering"
    yield d.deptno)
yield e.name;

(*) notelem (equivalent to SQL's NOT IN, also to 'not ... elem')
from e in emps
  where e.deptno notelem (from d in depts
    where d.name = "Engineering"
    yield d.deptno)
yield e.name;

(*) equivalent to previous
from e in emps
  where not (e.deptno elem (from d in depts
    where d.name = "Engineering"
    yield d.deptno))
yield e.name;

(*) equivalent to previous
from e in emps
  where e.deptno elem (from d in depts
    where d.name = "Engineering"
    yield d.deptno) = false
yield e.name;

(*) union (matches SQL's UNION ALL)
(from e in emps yield e.deptno)
union
(from d in depts yield d.deptno);

(*) simulate SQL's UNION DISTINCT
from deptno in (
  (from e in emps yield e.deptno)
  union
  (from d in depts yield d.deptno))
group deptno
order deptno;

(*) except
(from d in depts yield d.deptno)
except
(from e in emps yield e.deptno);

(*) simulate SQL's EXCEPT DISTINCT
fun exceptDistinct l1 l2 =
  from v in l1 except l2
  group v;
exceptDistinct (from e in emps yield e.deptno)
  (from d in depts where d.deptno <> 20 yield d.deptno);

(*) simulate SQL's EXCEPT ALL
fun exceptAll l1 l2 =
  from e in (
      from e in
        (from v in l1 yield {v, c = 1})
        union
        (from v in l2 yield {v, c = ~1})
      group e.v compute c = sum of e.c
      where c > 0),
    r in (
      let
        fun units 0 = []
          | units n = () :: (units (n - 1))
      in
        units e.c
      end)
    yield e.v;
exceptAll (from e in emps yield e.deptno)
  (from d in depts yield d.deptno);

(*) intersect
(from e in emps yield e.deptno)
intersect
(from d in depts yield d.deptno);

(*) simulate SQL's INTERSECT DISTINCT
fun intersectDistinct l1 l2 =
  from v in l1 intersect l2
  group v;
from d in intersectDistinct (from e in emps yield e.deptno)
    (from d in depts yield d.deptno)
  order d;

(*) simulate SQL's INTERSECT ALL
fun intersectAll l1 l2 =
  from e in (
      from e in
        (from v in l1 group v compute c = count)
        union
        (from v in l2 group v compute c = count)
      group e.v compute c = min of e.c, c2 = count
      where c2 = 2
      yield {v, c}),
    r in (
      let
        fun units 0 = []
          | units n = () :: (units (n - 1))
      in
        units e.c
      end)
    yield e.v;
from d in intersectAll (from e in emps yield e.deptno)
    (from d in depts yield d.deptno)
  order d;

(*) union followed by group
from x in (from e in emps yield e.deptno)
    union (from d in depts yield d.deptno)
group x compute c = count
order c, x;

(*) except followed by group
from x in (from e in emps yield e.deptno)
    except (from d in depts yield d.deptno)
group x compute c = count
order c, x;

(*) intersect followed by group
from x in (from e in emps yield e.deptno)
    intersect (from d in depts yield d.deptno)
group x compute c = count
order c, x;

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
group deptno = e.deptno
  compute sum = sum of e.id,
          count = count
order deptno;

(*) As previous, without the implied "deptno =" in "group",
(*) and "sum =" and "count =" in "compute".
from e in emps
group e.deptno
  compute sum of e.id,
          count
order deptno;

(*) 'group' with no aggregates
from e in emps
group deptno = e.deptno
order deptno;

from e in emps
group e.deptno
order deptno;

(*) composite 'group' with no aggregates
from e in emps
group e.deptno, idMod2 = e.id mod 2
order deptno;

(*) 'group' with empty key produces one output row
from e in emps
group compute count, sid = sum of e.id;

(*) 'group' with empty key produces one output row even if input is empty
from e in emps
where false
group compute count;

(*) 'group' with empty key, empty input, no aggregate functions
from e in emps
where false
group;

(*) 'group' with 'where' and complex argument to 'sum'
from e in emps
where e.deptno < 30
group deptno = e.deptno
  compute sumId = sum of e.id,
          sumIdPlusDeptno = sum of e.id + e.deptno
order deptno;

(*) 'group' with 'exists' as an aggregate function
from e in emps
group e.deptno
  compute sumId = sum of e.id,
          existsId = exists of e.id,
          existsStar = exists
order deptno;

(*) 'group' with record key
(*) (useful if we want to refer to 'e' later in the pipeline)
from e in emps
group e = {e.deptno, odd = e.id mod 2 = 1} compute c = count
yield {e.deptno, c1 = c + 1}
order deptno;

(*) 'group' with join
from e in emps, d in depts
where e.deptno = d.deptno
group e.deptno, ename = e.name, dname = d.name
  compute sumId = sum of e.id
order ename;

(*) empty 'group'
from e in emps
group compute sumId = sum of e.id;

(*) 'group' with aggregate function that references the key
from (k, v) in [(1, 5), (1, 6), (2, 7), (2, 10)]
group k compute x = (fn vs => k + (sum vs)) of v;

from (x, y, z) in [("a", "p", "e"), ("m", "a", "n"), ("a", "l", "e"), ("a", "w", "e")]
  group x compute z = (fn ys => concat (x :: ys)) of y;

(*) similar, but with composite key
from (x, y, z) in [("a", "p", "e"), ("m", "a", "n"), ("a", "l", "e"), ("a", "w", "e")]
  group x, z compute a = (fn ys => x ^ ":" ^ z ^ ":" ^ (concat ys)) of y;

(*) similar, but aggregate does not reference key
from (x, y, z) in [("a", "p", "e"), ("m", "a", "n"), ("a", "l", "e"), ("a", "w", "e")]
  group x compute z = (fn ys => concat ys) of y;

(*) equivalent to previous
from (x, y, z) in [("a", "p", "e"), ("m", "a", "n"), ("a", "l", "e"), ("a", "w", "e")]
  group x compute z = concat of y;

(*) user-defined aggregate function
let
  fun siz [] = 0
    | siz (ht :: tl) = 1 + (siz tl)
in
  from e in emps
  group deptno = e.deptno compute size = siz of e.id
  order deptno
end;

(*) as previous, but 'e' rather than 'e.id'
let
  fun siz [] = 0
    | siz (ht :: tl) = 1 + (siz tl)
in
  from e in emps
  group deptno = e.deptno compute size = siz of e
  order deptno
end;

(*) user-defined aggregate function #3
let
  fun my_sum [] = 0
    | my_sum (head :: tail) = head + (my_sum tail)
in
  from e in emps
  group e.deptno compute my_sum of e.id
  order deptno
end;

(*) Identity aggregate function (equivalent to SQL's COLLECT)
let
  fun id x = x
in
  from e in emps
  group e.deptno compute rows = id of e
  order deptno
end;

(*) Identity aggregate function, without 'of'
let
  fun id x = x
in
  from e in emps
  group e.deptno compute rows = id
  order deptno
end;

(*) Identity aggregate function, using lambda
from e in emps
group e.deptno compute rows = (fn x => x)
order deptno;

(*) Identity aggregate function with multiple input variables
from e in emps, d in depts
where e.deptno = d.deptno
group e.deptno compute rows = (fn x => x)
order deptno;

(*) Group followed by yield
from e in emps
group e.deptno
  compute sumId = sum of e.id,
          count = count of e
yield {deptno, avgId = sumId / count}
order deptno;

(*) Similar, using a sub-from:
from g in (
  from e in emps
  group e.deptno
    compute sumId = sum of e.id,
            count = count of e)
yield {g.deptno, avgId = g.sumId / g.count}
order deptno;

(*) Group followed by order and yield
from e in emps
group e.deptno
  compute sumId = sum of e.id,
          count = count of e
order deptno desc
yield {deptno, avgId = sumId / count};

(*) Group followed by group
from e in emps
  group e.deptno, parity = e.deptno mod 2
    compute sumId = sum of e.id
  group parity
    compute sumSumId = sum of sumId,
      c = count;

(*) Group followed by group followed by yield
from e in emps
  group e.deptno, parity = e.deptno mod 2
    compute sumId = sum of e.id
  group parity
    compute sumSumId = sum of sumId
  yield sumSumId * parity;

(*) Join followed by composite group
from e in emps,
    d in depts
  where e.deptno = d.deptno
  group x = e.id + d.deptno, e.deptno
    compute sumId = sum of e.id
  order x desc;

(*) Join followed by single group (from right input)
from e in emps,
    d in depts
  where e.deptno = d.deptno
  group d.deptno
  order deptno;

(*) Join followed by single group (from left input)
from e in emps,
    d in depts
  where e.deptno = d.deptno
  group e.deptno
  order deptno;

(*) Join followed by single group and order
from e in emps,
    d in depts
  where e.deptno = d.deptno
  group e.deptno
  order deptno desc;

(*) Join followed by order
from e in emps,
    d in depts
  where e.deptno = d.deptno
  order e.deptno desc, e.name;

(*) Join followed by order and yield
from e in emps,
    d in depts
  where e.deptno = d.deptno
  order e.deptno desc, e.name
  yield e.name;

(*) Only (scalar sub-query)
from e in emps
  yield only (from d in depts
              where d.deptno = e.deptno
              yield d.name);

from e in emps
  yield {ename = e.name,
         dname = only (from d in depts
                       where d.deptno = e.deptno
                       yield d.name)};

from e in emps
  yield {ename = e.name,
         dname = (only (from d in depts
                        where d.deptno = e.deptno)).name};

(*) Single-row aggregates using 'only' function and 'compute' clause
from e in emps
  group compute si = sum of e.id, c = count, si1 = sum of e.id + 1;
(*) similar to previous, but the 'only' function converts a singleton set to a record
only (
  from e in emps
    group compute si = sum of e.id, c = count, si1 = sum of e.id + 1);
(*) equivalent to previous, using group-less 'compute' rather than 'only'
from e in emps
  compute si = sum of e.id, c = count, si1 = sum of e.id + 1;

(*) Empty from
from;

(*) Empty from with where
from where true;

from where false;

from i in (from j in (from));

from i in (from),
    j in (from);

from i in (from),
    j in (from)
  yield j;

let
  val b = 1 < 2
in
  from
    where b
end;

(*) Empty from with yield
let
  val ten = 6 + 4;
in
  from
    yield {ten, nine = ten - 1}
end;

(*) Empty from with empty group
from
  group;

(*) Empty from with empty group and one aggregate function
from
  group compute c = count of "a";

(*) Empty from with group
let
  val ten = 6 + 4;
  val eleven = ten + 1;
in
  from
    group ten compute sumEleven = sum of eleven
end;

(*) Empty from with composite group
from
  group x = "a", y = 6;

from
  group z = "a", y = 6;

(*) Empty from with group and yield
from
  group one = 1 compute two = sum of 2, three = sum of 3
  yield {c1 = one, c5 = two + three};

(*) Patterns left of 'in'
fun sumPairs pairs =
  from (l, r) in pairs
  yield l + r;
sumPairs [];
sumPairs [(1, 2), (3, 4)];

(*) Skip rows that do not match the pattern
from (l, 2) in [(1, 2), (3, 4), (5, 2)]
  yield l;

(*) Record pattern
from {b = b, a = a} in [{a=1,b=2}];
from {b, a} in [{a=1,b=2}];
from {a = a, b = b} in [{a=1,b=2}];
from {b = a, a = b} in [{a=1,b=2}];
from {b = c, c = a, a = b} in [{a=1,b=2,c=3}];
from {b = a, c = b, a = c} in [{a=1,b=2,c=3}];
from {c = b, a = c, b = a} in [{a=1,b=2,c=3}];
from {a = c, b = a, c = b} in [{a=1,b=2,c=3}];

(*) Record with wildcards
from {a = a, ...} in [{a=1,b=2}];
from {a = a, b = true, c = c} in [{a=1,b=true,c=3}];
from {a = c, b = true, c = a} in [{a=1,b=true,c=3}];
from {a = c, b = true, c = a} in [{a=1,b=true,c=3},{a=1,b=true,c=4}] group c compute sum of a;
from {a = a, b = b, c = _} in [{a=1,b=true,c=3},{a=1,b=true,c=4}];
from {a = a, b = b, c = _} in [{a=1,b=true,c=3},{a=1,b=true,c=4}], d in ["a", "b"];
from {a = a, b = b, ...} in [{a=1,b=true,c=3},{a=1,b=true,c=4}];
from {a = a, c = c, ...} in [{a=1,b=true,c=3},{a=1,b=true,c=4}];
from {a, c, ...} in [{a=1,b=true,c=3},{a=1,b=true,c=4}];
from {b = y, ...} in [{a=1,b=2}];
from {b = y, a = (p, q)} in [{a=(1,true),b=2}];
from {b = y, a = (2, q)} in [{a=(1,true),b=2},{a=(2,false),b=3}];
from {b = y, a = x} in [{a=1,b=2}];
from {a = x, ...} in [{a=1,b=2,c=3}];
from {a = x, b = y, ...} in [{a=1,b=2,c=3}];

fun listHeads lists =
  from hd :: tl in lists
  yield hd + 1;
listHeads [];
listHeads [[1, 2], [3], [4, 5, 6]];

fun listFields lists =
  from {a = x, b = y} in lists
  yield x + 1;
listFields [];
listFields [{a = 1, b = 2}, {a = 3, b = 0}, {a = 4, b = 5}];

(*) As above, using abbreviated record pattern
fun listFields2 lists =
  from {a, b} in lists
  yield a + 1;
listFields [];
listFields [{a = 1, b = 2}, {a = 3, b = 0}, {a = 4, b = 5}];

(*) Layered patterns
fun listHeads2 lists =
  from list as (hd :: tl) in lists
  yield hd + 1 + (length list);
listHeads2 [];
listHeads2 [[1, 2], [3], [4, 5, 6]];

fun sumPairs2 pairs =
  from p as (l, r) in pairs
  yield (#1 p) + l + r;
sumPairs2 [];
sumPairs2 [(1, 2), (3, 4)];

(*) '=' in from
from x = 1;
from x = 1 yield x + 2;
from (x, y, z) = (1, 2, 3);
from (x, y, z) = (1, 2, 3) yield x + 2 * z;
from x in [1, 2],
    y = x + 1
  where x = 2
  yield y;
from x in [1, 2],
    y = x + 1,
    z = y + 2
  group x, y, z compute c = count;

(*) Temporary functions
let
  fun abbrev s =
    if size s > 5
    then (substring (s, 0, 3)) ^ "."
    else s;
  fun shouldPromote e =
    e.id < e.deptno * 4
in
  from e in emps
  where shouldPromote e
  yield {e.id, e.deptno, abbrev_name = abbrev e.name}
end;

(*) There's no flatMap in the standard library, so define one
fun flatMap f l = List.concat (map f l);
flatMap explode ["ab", "", "def"];

(*) Here's another way to define flatMap
fun flatMap2 f l = foldl List.at [] (map f l);
flatMap2 explode ["ab", "", "def"];

(*) A function that runs a query and returns the result
fun employeesIn deptno =
  from e in emps
  where e.deptno = deptno;

employeesIn 10;
employeesIn 25;
employeesIn 30;

(*) Using 'map' to stick together results
map employeesIn [10, 25, 30];

(*) Same, using 'from'
from deptno in [10, 25, 30]
  yield employeesIn deptno;

(*) Flatten (using flatMap)
flatMap employeesIn [10, 25, 30];

(*) Flatten (using a lateral join); compare to SQL 'CROSS APPLY'
from deptno in [10, 25, 30],
    e in employeesIn deptno
  yield e;

(*) Function in yield
let
  val intFns = from a in [2, 3, 4] yield {a, f = fn x => x + a}
in
  from intFn in intFns yield {intFn.a, f1 = intFn.f 1, f2 = intFn.f 2}
end;

(*) Function in order
val triples =
  from t in [{x=1,y=2}, {x=2,y=6}, {x=3, y=5}]
    yield {t.x, t.y, foo = fn z => t.x * z + t.y};
from t in triples order t.foo 1; (* 1+2 < 2+6 = 3+5 *)
from t in triples order t.foo 1, t.x; (* (1+2,1) < (2+6,2) < (3+5,3) *)
from t in triples order t.foo 1, t.x desc; (* (1+2,~1) < (3+5,~3) < (2+6,~2) *)
from t in triples order t.foo ~1, t.y; (* (~1+2,2) < (~3+5,5) < (~2+6,6) *)
from {foo,x,y} in triples
  order foo ~1, x; (* (~1+2,2) < (~3+5,5) < (~2+6,6) *)
from t1 in triples, t2 in triples
  where t1.y = t2.y
  order t1.foo ~1, t2.x; (* (~1+2,2) < (~3+5,5) < (~2+6,6) *)

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

(*) Hybrid
Sys.set ("hybrid", false);
from r in
  List.tabulate (6, fn i =>
    {i, j = i + 3, s = substring ("morel", 0, i)})
yield {r.j, r.s};
Sys.plan();

Sys.set ("hybrid", true);
from r in
  List.tabulate (6, fn i =>
    {i, j = i + 3, s = substring ("morel", 0, i)})
yield {r.j, r.s};
Sys.plan();

(*) List expression in Hybrid mode (0% Calcite), with various
(*) row types: record, tuple, primitive
List.filter (fn {i, j} => i mod 2 = 0) [{i = 1, j = 1}, {i = 2, j = 2}, {i = 3, j = 3}, {i = 4, j = 5}];
List.filter (fn (i, j) => i mod 2 = 0) [(1, 1), (2, 2), (3, 3), (4, 5)];
List.filter (fn i => i mod 2 = 0) [1, 2, 3, 4];

(*) Parameterized view
Sys.set ("hybrid", false);
let
  fun empsIn (emps, deptno) =
    from e in emps
    where e.deptno = deptno
in
  from e in (empsIn (emps, 30))
  yield e.name
end;
Sys.plan();

(*) Same, via a predicate
let
  fun empsIn emps predicate =
    from e in emps
    where predicate e
in
  from e in (empsIn emps (fn e => e.deptno = 30))
  yield e.name
end;
Sys.plan();

(*) dummy
from message in ["the end"];

(*) End relational.sml
