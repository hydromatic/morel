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
 * This script contains Morel fragments that are used in Morel's
 * web site, documentation, and blog posts. Just, you know, to keep
 * us honest.
 *)
Sys.set ("printDepth", ~1);
Sys.set ("lineWidth", 78);
Sys.set ("stringDepth", ~1);

(*) === README.md ===================================================

(*) Auxiliary declarations
val e = {deptno = 10, name = "Fred"};
val d = 10;
val filter = List.filter;

"Hello, world!";

(*) In Morel, you can omit label = if the expression is an identifier,
(*) label application, or field reference. Thus
{#deptno e, e.name, d};
(*) is short-hand for
{deptno = #deptno e, name = e.name, d = d};

(*) In a sense, from is syntactic sugar. For example, given emps and
(*) depts, relations defined as lists of records as follows
val emps =
  [{id = 100, name = "Fred", deptno = 10},
   {id = 101, name = "Velma", deptno = 20},
   {id = 102, name = "Shaggy", deptno = 30},
   {id = 103, name = "Scooby", deptno = 30}];
val depts =
  [{deptno = 10, name = "Sales"},
   {deptno = 20, name = "Marketing"},
   {deptno = 30, name = "Engineering"},
   {deptno = 40, name = "Support"}];
(*) the expression
from e in emps where e.deptno = 30 yield e.id;
(*) is equivalent to standard ML
map (fn e => (#id e)) (filter (fn e => (#deptno e) = 30) emps);

(*) You can iterate over more than one collection, and therefore
(*) generate a join or a cartesian product:
from e in emps, d in depts
  where e.deptno = d.deptno
  yield {e.id, e.deptno, ename = e.name, dname = d.name};
(*) As in any ML expression, you can define functions within a from
(*) expression, and those functions can operate on lists. Thus we can
(*) implement equivalents of SQL's IN and EXISTS operators:
(* FIXME
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
*)
(* FIXME
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
*)

(*) === Screen cast =================================================

(*) Now we're in morel's shell, for interactive commands.
(*) First of all, we need to talk about comments.

(* This is a block comment, which can span multiple lines... *)

(*) ... and this is a single-line comment.

(*) Now, the basics.
(*) Everything in ML is an expression.
"a string literal";
1 + 2;

(*) The Morel shell deduces the type of each expression,
(*) and assigns it to a variable called "it".
(*) We can use "it" in the next expression...
it + 4;

(*) We just saw string and int expressions.
(*) There are also boolean, list, record and tuple types:
1 = 2;
[1, 2, 3];
{id = 10, name = "Alex"};
(1, true, "yes");

(*) You can assign values to variables.
val x = 7;
val y =  x mod 3;

(*) Functions are expressions, too.
(*) "fn" makes a lambda expression.
val plusOne = fn x => x + 1;
plusOne 2;

(*) Functions are widely used, so they have a shorthand.
(*) "fun" is short for "val ... = fn".
fun plusOne x = x + 1;
plusOne 1000;

(*) Functions can have multiple arguments, separated by spaces.
fun plus x y = x + y;
plus 3 4;

(*) If we supply too few arguments, we get a closure that captures
(*) the argument value and can be applied later.
val plusTen = plus 10;
plusTen 2;

(*) Functions can be recursive.
fun fact n = if n = 1 then 1 else n * fact (n - 1);
fact 1;
fact 5;

(*) A higher-order function is a function that operates on other
(*) functions. Here are a couple.

(*) "map" applies another function to each element of a list
let
  fun map f [] = []
    | map f (head :: tail) = (f head) :: (map f tail)
  fun double n = n * 2
in
  map double [1, 2, 3, 4]
end;

(*) "filter" keeps only those elements of a list for which
(*) a predicate evaluates to true.
let
  fun filter p [] = []
    | filter p (head :: tail) =
      if (p head) then
        (head :: (filter p tail))
      else
        (filter p tail)
  fun even n = n mod 2 = 0
in
  filter even [1, 2, 3, 4]
end;

(*) You may notice that "map" and "filter" are very similar to the
(*) "select" and "where" clauses of a SQL statement.
(*)
(*) This is no surprise: relational algebra, which underlies SQL, is
(*) basically a collection of higher-order functions applied to
(*) lists of records (relations).
(*)
(*) Can we extend ML syntax to make it easier to write relational
(*) algebra expressions? You bet!

(*) Let's start by defining "emp" and "dept" relations as lists of
(*) records.
val emps =
  [{id = 100, name = "Fred", deptno = 10},
   {id = 101, name = "Velma", deptno = 20},
   {id = 102, name = "Shaggy", deptno = 30},
   {id = 103, name = "Scooby", deptno = 30}];
val depts =
  [{deptno = 10, name = "Sales"},
   {deptno = 20, name = "HR"},
   {deptno = 30, name = "Engineering"},
   {deptno = 40, name = "Support"}];

(*) Now our first query, equivalent to "select * from emps as e".
from e in emps yield e;

(*) Now "select e.id from emps as e where e.deptno = 30"
from e in emps where (#deptno e) = 30 yield (#id e);

(*) Join two relations
from e in emps, d in depts
  where (#deptno e) = (#deptno d)
  yield {id = (#id e), deptno = (#deptno e),
         ename = (#name e), dname = (#name d)};

(*) A query with "exists" and a correlated sub-query.
(*) We define the "exists" function ourselves: no need for a
(*) built-in!
let
  fun exists [] = false
    | exists (head :: tail) = true
in
  from e in emps
    where exists (from d in depts
                  where (#deptno d) = (#deptno e)
                  andalso (#name d) = "Engineering")
    yield (#name e)
end;

(*) That's all, folks!
(*) To recap, Morel has:
(*)  * expressions of int, string, boolean, float, char, list,
(*)    tuple and record types;
(*)  * lambda expressions and recursive functions;
(*)  * algebraic datatypes and pattern-matching;
(*)  * polymorphism and powerful type-inference;
(*)  * relational expressions (an extension to Standard ML).
(*)
(*) Follow our progress at https://github.com/julianhyde/morel.
(*) This is only release 0.1, so there's more to come!

(*) === 2020/02/25: Morel: A functional language for data ===========

(*) Auxiliary declarations
val hr = {
  emps = [
    {id = 100, deptno = 10, name = "SCOTT"}],
  depts = [
    {deptno = 10, name = "SALES"}]};

(*) here is a query in Morel:
from e in hr.emps,
    d in hr.depts
where e.deptno = d.deptno
yield {e.id, e.deptno, ename = e.name, dname = d.name};

(*) === 2020/03/03: Morel: The basic language =======================

(* As a functional language, everything in Morel is an expression.
The basic types are `bool`, `int`, `float`, `string` and `char`.  Here
are literals in each. *)
false;
10;
~4.5;
"morel";
#"a";
();

(* As you'd expect, there are built-in operators for each data
type. Here are a few examples: *)
true andalso false;
true orelse false;
not false;
1 + 2;
~(5 - 2);
10 mod 3;
"mo" ^ "rel";

(* You can assign values to variables. *)
val x = 7;
val y = x mod 3;
x + y;

(* The shell deduces the type of each expression,
   and assigns it to a variable called `it`.
   We can use `it` in the next expression. *)
"morel";
String.size it;
it + 4;

(* A let expression binds one or more values and evaluates an expression *)
let
  val x = 3
  val y = 2
in
  x + y
end;

(* In addition to primitive types, there are list, record and tuple
   types. *)
[1, 2, 3];
{id = 10, name = "Scooby"};
(1, true, "yes");

(* Tuples are actually just records with fields named "1", "2",
   etc.: *)
(1, true, "yes");
{1 = 1, 2 = true, 3 = "yes"};
(1, true, "yes") = {1 = 1, 2 = true, 3 = "yes"};

(* The empty record and empty tuple are equal, and are the only value
   of the type unit. *)
{};
();
{} = ();

(* Functions are expressions, too.  `fn` makes a lambda expression.
   After we have bound the lambda value to `plusOne`, we can use
   `plusOne` as a function. *)
val plusOne = fn x => x + 1;
plusOne 2;

(* Functions declarations are common, so the `fun` keyword provides a
   shorthand. *)
fun plusOne x = x + 1;
plusOne 2;

(* Functions can have multiple arguments, separated by spaces. *)
fun plus x y = x + y;
plus 3 4;

(* If we supply too few arguments, we get a closure that captures the
   argument value and can be applied later. *)
val plusTen = plus 10;
plusTen 2;

(* Functions can be recursive. *)
fun factorial n =
  if n = 1 then
    1
  else
     n * factorial (n - 1);
factorial 1;
factorial 5;

(* A higher-order function is a function that operates on other
   functions. Here are a couple of examples.

   The map function applies a given function `f` to each element of a
   list, returning a list. *)
fun map f [] = []
  | map f (head :: tail) = (f head) :: (map f tail);
fun double n = n * 2;
map double [1, 2, 3, 4];

(* The filter function keeps only those elements of a list for which a
   predicate `p` evaluates to true. *)
fun filter p [] = []
  | filter p (head :: tail) =
    if (p head) then
      (head :: (filter p tail))
    else
      (filter p tail);
fun even n = n mod 2 = 0;
filter even [1, 2, 3, 4];

(* Let’s start by defining emps and depts relations as lists of
   records. *)

val emps =
  [{id = 100, name = "Fred", deptno = 10},
   {id = 101, name = "Velma", deptno = 20},
   {id = 102, name = "Shaggy", deptno = 30},
   {id = 103, name = "Scooby", deptno = 30}];
val depts =
  [{deptno = 10, name = "Sales"},
   {deptno = 20, name = "HR"},
   {deptno = 30, name = "Engineering"},
   {deptno = 40, name = "Support"}];

(* Now let's run our first query. *)
from e in emps yield e;

(* There is no difference between a query, a table and a list-valued
   expression, so we could have instead written just `emps`. *)
emps;

(* A where clause filters out rows. *)
from e in emps
  where #deptno e = 30
  yield {id = #id e};

(* The following is equivalent. *)
from e in emps
  where e.deptno = 30
  yield {e.id};

(* If you omit 'yield' you get the raw values of 'e'. *)
from e in emps
  where #deptno e = 30;

(* Shorthand. The following 3 queries are equivalent. *)
from e in emps
  yield {e = #id e};
from e in emps
  yield {e = e.id};
from e in emps
  yield {e.id};

(* Joins and sub-queries. *)
from e in emps,
    d in depts
  where e.deptno = d.deptno
  yield {e.id, e.deptno, ename = e.name, dname = d.name};

(* The following query would, in SQL, be described as having 'EXISTS
   and a correlated sub-query'. But 'exists' is not a built-in keyword
   in Morel, just a function that we define in the query, and a
   sub-query is just an expression that happens to return a list. *)
let
  fun exists [] = false
    | exists (head :: tail) = true
in
  from e in emps
    where exists (from d in depts
                  where d.deptno = e.deptno
                  andalso d.name = "Engineering")
    yield e.name
end;

(*) === 2020/03/03: Morel: The basic language =======================

(*) WordCount in Standard ML
(* Note: The blog post used Standard ML. Here, to accommodate missing
   language features in Morel, we have changed "(op +)" to
   "(fn (x, y) => x + y)". *)
fun mapReduce mapper reducer list =
  let
    fun update (key, value, []) = [(key, [value])]
      | update (key, value, ((key2, values) :: tail)) =
          if key = key2 then
            (key, (value :: values)) :: tail
          else
            (key2, values) :: (update (key, value, tail))
    fun dedup ([], dict) = dict
      | dedup ((key, value) :: tail, dict) =
          dedup (tail, update (key, value, dict))
    fun flatMap f list = List.foldl List.at [] (List.map f list)
    val keyValueList = flatMap mapper list
    val keyValuesList = dedup (keyValueList, [])
  in
    List.map (fn (key, values) => (key, reducer (key, values))) keyValuesList
  end;

fun wc_mapper line =
  let
    fun split0 [] word words = word :: words
      | split0 (#" " :: s) word words = split0 s "" (word :: words)
      | split0 (c :: s) word words = split0 s (word ^ (String.str c)) words
    fun split s = List.rev (split0 (String.explode s) "" [])
  in
    List.map (fn w => (w, 1)) (split line)
  end;
fun wc_reducer (key, values) = List.foldl (fn (x, y) => x + y) 0 values;

(*) Check that they work on discrete values
wc_mapper "a skunk sat on a stump";
wc_reducer ("hello", [1, 4, 2]);

(*) Bind them to mapReduce, and run
fun wordCount lines = mapReduce wc_mapper wc_reducer lines;
wordCount ["a skunk sat on a stump",
    "and thunk the stump stunk",
    "but the stump thunk the skunk stunk"];

(*) WordCount in Morel
val lines = ["a skunk sat on a stump",
  "and thunk the stump stunk",
  "but the stump thunk the skunk stunk"];
fun split s =
  let
    fun split0 [] word words = word :: words
      | split0 (#" " :: s) word words = split0 s "" (word :: words)
      | split0 (c :: s) word words = split0 s (word ^ (String.str c)) words
  in
    List.rev (split0 (String.explode s) "" [])
  end;
from line in lines,
    word in split line
  group word compute count;

(*) A more complete solution
fun wordCount lines =
  let
    fun split0 [] word words = word :: words
      | split0 (#" " :: s) word words = split0 s "" (word :: words)
      | split0 (c :: s) word words = split0 s (word ^ (String.str c)) words
    fun split s = List.rev (split0 (String.explode s) "" [])
  in
    from line in lines,
        word in split line
    group word compute count
  end;
wordCount lines;

(*) === Aggregate functions =========================================

val emps = scott.emp;
val depts = scott.dept;

from e in emps
  group e.deptno compute sumSal = sum of e.sal;

from e in emps,
    d in depts
  where e.deptno = d.deptno
  group e.deptno, d.dname, e.job
    compute sumSal = sum of e.sal,
      minRemuneration = min of e.sal + e.comm;

(*) In this example, we define our own version of the `sum` function:
let
  fun my_sum [] = 0
    | my_sum (head :: tail) = head + (my_sum tail)
in
  from e in emps
    group e.deptno
    compute sumEmpno = my_sum of e.empno
end;

(*) The equivalent of SQL's COLLECT aggregate function is trivial
from e in emps
  group e.deptno
  compute names = (fn x => x) of e.ename;

(*) === StrangeLoop 2021 talk =======================================
(*) Standard ML: values
"Hello, world!";
1 + 2;
~1.5;
[1, 1, 2, 3, 5];
fn i => i mod 2 = 1;
(1, "a");
{name = "Fred", empno = 100};

(*) Standard ML: types
true;
#"a";
~1;
3.14;
"foo";
();
String.size;
fn (x, y) => x + y * y;
(10, "Fred");
{empno=10, name="Fred"};
[1, 2, 3];
[(true, fn i => i + 1)];
List.length;

(*) Standard ML: variables and functions
val x = 1;
val isOdd = fn i => i mod 2 = 1;
fun isOdd i = i mod 2 = 0;
isOdd x;
let
  val x = 6
  fun isOdd i = i mod 2 = 1
in
  isOdd x
end;

(*) Algebraic data types, case, and recursion
(* TODO fix bug in parameterized datatype, and remove intTree below
datatype 'a tree =
    EMPTY
  | LEAF of 'a
  | NODE of ('a * 'a tree * 'a tree);
*)
datatype intTree =
    EMPTY
  | LEAF of int
  | NODE of (int * intTree * intTree);
fun sumTree EMPTY = 0
  | sumTree (LEAF i) = i
  | sumTree (NODE (i, l, r)) =
        i + sumTree l + sumTree r;
val t = NODE (1, LEAF 2, NODE (3, EMPTY, LEAF 7));
sumTree t;
val rec sumTree = fn t =>
  case t of EMPTY => 0
    | LEAF i => i
    | NODE (i, l, r) => i + sumTree l + sumTree r;

(*) Relations and higher-order functions
val emps = [
  {id = 100, name = "Fred", deptno = 10},
  {id = 101, name = "Velma", deptno = 20},
  {id = 102, name = "Shaggy", deptno = 30},
  {id = 103, name = "Scooby", deptno = 30}];
List.filter (fn e => #deptno e = 30) emps;

(*) Implementing Join using higher-order functions
val depts = [
  {deptno = 10, name = "Sales"},
  {deptno = 20, name = "Marketing"},
  {deptno = 30, name = "R&D"}];
fun flatMap f xs = List.concat (List.map f xs);
List.map
  (fn (d, e) => {deptno = #deptno d, name = #name e})
  (List.filter
    (fn (d, e) => #deptno d = #deptno e)
    (flatMap
      (fn e => (List.map (fn d => (d, e)) depts))
      emps));

(*) Implementing Join in Morel using `from`
from e in emps,
    d in depts
  where e.deptno = d.deptno
  yield {d.deptno, e.name};
from e in emps,
    d in depts
  where #deptno e = #deptno d
  yield {deptno = #deptno d, name = #name e};

(*) WordCount
let
  fun split0 [] word words = word :: words
    | split0 (#" " :: s) word words = split0 s "" (word :: words)
    | split0 (c :: s) word words = split0 s (word ^ (String.str c)) words
  fun split s = List.rev (split0 (String.explode s) "" [])
in
  from line in lines,
    word in split line
  group word compute c = count
end;
wordCount ["a skunk sat on a stump",
    "and thunk the stump stunk",
    "but the stump thunk the skunk stunk"];

(*) Functions as views, functions as values
fun emps2 () =
   from e in emps
     yield {e.id,
       e.name,
       e.deptno,
       comp = fn revenue => case e.deptno of
           30 => e.id + revenue / 2
         | _ => e.id};
from e in emps2 ()
  yield {e.name, e.id, c = e.comp 1000};

(*) Chaining relational operators
from e in emps;
from e in emps
  order e.deptno, e.id desc;
from e in emps
  order e.deptno, e.id desc
  yield {e.name, nameLength = String.size e.name, e.id, e.deptno};
from e in emps
  order e.deptno, e.id desc
  yield {e.name, nameLength = String.size e.name, e.id, e.deptno}
  where nameLength > 4;
from e in emps
  order e.deptno, e.id desc
  yield {e.name, nameLength = String.size e.name, e.id, e.deptno}
  where nameLength > 4
  group deptno compute c = count, s = sum of nameLength;
from e in emps
  order e.deptno, e.id desc
  yield {e.name, nameLength = String.size e.name, e.id, e.deptno}
  where nameLength > 4
  group deptno compute c = count, s = sum of nameLength
  where s > 10;
from e in emps
  order e.deptno, e.id desc
  yield {e.name, nameLength = String.size e.name, e.id, e.deptno}
  where nameLength > 4
  group deptno compute c = count, s = sum of nameLength
  where s > 10
  yield c + s;

(*) Integration with Apache Calcite - schemas
foodmart;
scott;
scott.dept;
from d in scott.dept
  where notExists (from e in scott.emp
    where e.deptno = d.deptno
    andalso e.job = "CLERK");

(*) Integration with Apache Calcite - relational algebra
Sys.set ("hybrid", true);
from d in scott.dept
  where notExists (from e in scott.emp
    where e.deptno = d.deptno
    andalso e.job = "CLERK");
Sys.plan();

(*) Functional programming <=> relational programming
fun squareList [] = []
  | squareList (x :: xs) = x * x :: squareList xs;
squareList [1, 2, 3];
fun squareList xs = List.map (fn x => x * 2) xs;
squareList [1, 2, 3];
fun squareList xs =
  from x in xs
    yield x * x;
squareList [1, 2, 3];

(*) wordCount again
fun mapReduce mapper reducer list =
  from e in list,
      (k, v) in mapper e
    group k compute c = (fn vs => reducer (k, vs)) of v;
fun wc_mapper line =
  List.map (fn w => (w, 1)) (split line);
fun wc_reducer (key, values) =
  List.foldl (fn (x, y) => x + y) 0 values;
val wordCount = mapReduce wc_mapper wc_reducer;
wordCount lines;
from line in lines,
   word in split line
 group word compute c = count;

(*) === Coda ========================================================
from message in ["the end"];

(*) End blog.sml
