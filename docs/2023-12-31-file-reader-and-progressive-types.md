<!--
{% comment %}
Licensed to Julian Hyde under one or more contributor license
agreements.  See the NOTICE file distributed with this work
for additional information regarding copyright ownership.
Julian Hyde licenses this file to you under the Apache
License, Version 2.0 (the "License"); you may not use this
file except in compliance with the License.  You may obtain a
copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied.  See the License for the specific
language governing permissions and limitations under the
License.
{% endcomment %}
-->

<!--
This post started as a script for a screencast.

Setup for recording:
* In 'morel' script, add '2>/dev/null' to the last java command.
* In bash, export PS1='$ '
* So that the screencast starts with title and author,
  create a title file, /tmp/title.txt, and 'cat /tmp/title.txt'
  before pressing 'record'.
-->

# File reader and progressive types in Morel version 0.4

## Abstract

In Morel 0.4 we have added a file reader, to make it easy to browse
your file system for data sets in CSV files. A directory appears to
Morel a record value, and the fields of that record are the files or
subdirectories. If a file is a list of records, such as a CSV file,
its type in Morel will be a list of records.

To make this work, we extended Morel's type system with a feature we
call *progressive types*. Progressive types give you the benefits of
static typing when it's not possible (or is inefficient or
inconvenient) to gather all the type information up front.

There is also a
[screencast](https://www.youtube.com/watch?v=uybUjCYsBKI&t=1s)
based on this article;
[[MOREL-209](https://github.com/hydromatic/morel/issues/209)]
is the feature description.

## File reader

I wanted to give a quick demo of a feature we've just added to Morel.

We've added a file reader so that you can easily load and work on data
sets such as CSV files. We'll be using a data set in a directory
called "data".

```bash
$ ls -lR src/test/resources/data

src/test/resources/data/:
total 8
drwxrwxr-x 2 jhyde jhyde 4096 Dec 31 14:23 scott
drwxrwxr-x 2 jhyde jhyde 4096 Dec 31 14:38 wordle

src/test/resources/data/scott:
total 16
-rw-rw-r-- 1 jhyde jhyde  50 Dec 24 19:10 bonus.csv
-rw-rw-r-- 1 jhyde jhyde 131 Dec 31 14:23 dept.csv
-rw-rw-r-- 1 jhyde jhyde 420 Dec 24 19:10 emp.csv.gz
-rw-rw-r-- 1 jhyde jhyde 127 Dec 24 19:10 salgrade.csv

src/test/resources/data/wordle:
total 92
-rw-rw-r-- 1 jhyde jhyde 11944 Dec 24 19:10 answers.csv
-rw-rw-r-- 1 jhyde jhyde 77844 Dec 24 19:10 words.csv
```

That directory has subdirectories `scott` and `wordle`.  Each of
those has some CSV files, and there's one compressed CSV file.

Now let's start the Morel shell:

```bash
$ ./morel --directory=src/test/resources/data
morel version 0.4.0 (java version "21.0.1", JLine terminal, xterm-256color)
-
```

Morel is a functional programming language that is also a query
language. It is statically typed, and its main type constructors are
lists and records.

In the following, we create two record values, a list of records,
and write a simple query.

```
- val fred = {name="Fred", age=27};
val fred = {age=27,name="Fred"} : {age:int, name:string}

- val velma = {name="Velma", age=20};
val velma = {age=20,name="Velma"} : {age:int, name:string}

- val employees = [fred, velma];
val employees = [{age=27,name="Fred"},{age=20,name="Velma"}]
  : {age:int, name:string} list

- from e in employees yield e.age;
val it = [27,20] : int list
```

We wanted to make the file reader interactive.  You shouldn't have to
leave the Morel shell to see what files are available.

So you can browse the whole file system as if you are looking at the
fields of a record. The `file` object is where you start.

```
- file;
val it = {scott={},wordle={}} : {scott:{...}, wordle:{...}, ...}
```

As you can see, it is a record with fields `scott` and `wordle`. In
the file reader, every directory is a record, and the fields are the
files or subdirectories.

Now let's look at `file.scott`:

```
- file.scott;
val it = {bonus=<relation>,dept=<relation>,emp=<relation>,salgrade=<relation>}
  : {bonus:{...} list, dept:{...} list, emp:{...} list, salgrade:{...} list,
     ...}
```

It, too, is a record, but fields such as `dept` and `emp` are listed
as relations, because they are CSV files.  We can run queries on those
data sets. Here is a query to compute the total salary budget for each
department. You could write a similar query in SQL using `JOIN` and
`GROUP BY`:

```
- from d in file.scott.dept
=   join e in file.scott.emp on d.deptno = e.deptno
=   group d.dname compute sum of e.sal;
val it =
  [{dname="RESEARCH",sum=10875.0},{dname="SALES",sum=9400.0},
   {dname="ACCOUNTING",sum=8750.0}] : {dname:string, sum:real} list
```

After we have traversed into `scott` and `dept`, the type of the
`file` value has changed:

```
- file;
val it =
  {scott={bonus=<relation>,dept=<relation>,emp=<relation>,salgrade=<relation>},
   wordle={}}
  : {
     scott:{bonus:{...} list, dept:{deptno:int, dname:string, loc:string} list,
            emp:
                {comm:real, deptno:int, empno:int, ename:string,
                 hiredate:string, job:string, mgrno:int, sal:real} list,
            salgrade:{...} list, ...}, wordle:{...}, ...}
```

Note that the `scott` field has been expanded, and so have the `dept`
and `emp` fields. This is called *progressive typing*. What is it, and
why did we add it?

## Static, dynamic and progressive typing

Progressive typing defers collecting the type of certain record fields
until they are referencing in a program.  Adding it to Morel was the
hardest part of building the file reader.

Why is it necessary? Imagine if that `data` directory had contained a
thousand subdirectories and a million files. The type of the `file`
object would be so large that it would fill many screens. More to the
point, the Morel system would take an age to start up, because it is
opening all those files and directories to find out their types.

Static and dynamic typing each have their strengths (and legions of
passionate fans).  Static typing improves performance, code quality
and maintenance, and helps auto-suggestion in IDEs, but requires a
'closed world' where everything is known.  Dynamic typing is better
for interacting with the 'open world' of loosely coupled systems,
where not everything is known, and things are forever
changing. Reading a file system is definitely in its sweet spot.

But using dynamic typing is not an option in a strong, statically
typed language like Morel. By deferring the collection of types,
progressive typing can handle the 'open world' of the file system. By
only ever expanding types, progressive typing retains the guarantees
of static typing.  Say I compile my program and `file` has a
particular type.  Later, the type of `file` later expands due to
progressive typing.  My program will still be valid, because all the
fields and sub-fields it needs are still there.

Morel's type system remains static. Progressive types can be injected
into programs at particular points (to date, the `file` value is the
only injection point) and do not affect how the rest of the program is
typed.

## Variables

You don't lose the benefits of progressive typing if you use
variables.

For example, this query replaces the expression `file.scott` in the
previous query with a variable `s` to make the query more concise. It
gives the same results as the previous query.

```
- val s = file.scott;
val s = {bonus=<relation>,dept=<relation>,emp=<relation>,salgrade=<relation>}
  : {bonus:{...} list, dept:{...} list, emp:{...} list, salgrade:{...} list,
     ...}

- from d in s.dept
=   join e in s.emp on d.deptno = e.deptno
=   group d.dname compute sum of e.sal;
val it =
  [{dname="RESEARCH",sum=10875.0},{dname="SALES",sum=9400.0},
   {dname="ACCOUNTING",sum=8750.0}] : {dname:string, sum:real} list
```

## Conclusion

The file reader lets you browse the file system starting
from a single variable called `file`, and load data sets from CSV
files. Progressive types give you the benefits of static typing but
without filling your screen with useless type information.
