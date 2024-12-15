/*
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
 */
package net.hydromatic.morel;

import static net.hydromatic.morel.Matchers.equalsOrdered;
import static net.hydromatic.morel.Matchers.equalsUnordered;
import static net.hydromatic.morel.Matchers.isCode;
import static net.hydromatic.morel.Matchers.isFullyCalcite;
import static net.hydromatic.morel.Matchers.list;
import static net.hydromatic.morel.Ml.ml;
import static org.hamcrest.core.Is.is;

import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import net.hydromatic.morel.eval.Prop;
import org.junit.jupiter.api.Test;

/** Tests translation of Morel programs to Apache Calcite relational algebra. */
public class AlgebraTest {
  /**
   * Tests a program that uses an external collection from the "scott" JDBC
   * database.
   */
  @Test
  void testScott() {
    final String ml =
        "let\n"
            + "  val emps = #emps scott\n"
            + "in\n"
            + "  from e in emps yield #deptno e\n"
            + "end\n";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertType("int bag")
        .assertEvalIter(
            equalsOrdered(
                20, 30, 30, 20, 30, 30, 10, 20, 10, 30, 20, 30, 20, 10));
  }

  /** As previous, but with more concise syntax. */
  @Test
  void testScott2() {
    final String ml = "from e in scott.emps yield e.deptno";
    final String plan =
        "LogicalProject(deptno=[$7])\n"
            + "  JdbcTableScan(table=[[scott, EMP]])\n";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertType("int bag")
        .assertCalcite(is(plan))
        .assertEvalIter(
            equalsOrdered(
                20, 30, 30, 20, 30, 30, 10, 20, 10, 30, 20, 30, 20, 10));
  }

  @Test
  void testScottOrder() {
    final String ml =
        "from e in scott.emps\n"
            + " yield {e.empno, e.deptno}\n"
            + " order empno desc\n"
            + " skip 2 take 4";
    final String plan =
        ""
            + "LogicalSort(sort0=[$1], dir0=[DESC], offset=[2], fetch=[4])\n"
            + "  LogicalProject(deptno=[$7], empno=[$0])\n"
            + "    JdbcTableScan(table=[[scott, EMP]])\n";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertType("{deptno:int, empno:int} list")
        .assertCalcite(is(plan))
        .assertEvalIter(
            equalsOrdered(
                list(30, 7900),
                list(20, 7876),
                list(30, 7844),
                list(10, 7839)));
  }

  @Test
  void testScottJoin() {
    final String ml =
        "let\n"
            + "  val emps = #emps scott\n"
            + "  and depts = #depts scott\n"
            + "in\n"
            + "  from e in emps, d in depts\n"
            + "    where #deptno e = #deptno d\n"
            + "    andalso #empno e >= 7900\n"
            + "    yield {empno = #empno e, dname = #dname d}\n"
            + "end\n";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertType("{dname:string, empno:int} bag")
        .assertEvalIter(
            equalsOrdered(
                list("SALES", 7900),
                list("RESEARCH", 7902),
                list("ACCOUNTING", 7934)));
  }

  /** As {@link #testScottJoin()} but without intermediate variables. */
  @Test
  void testScottJoin2() {
    final String ml =
        "from e in #emps scott, d in #depts scott\n"
            + "  where #deptno e = #deptno d\n"
            + "  andalso #empno e >= 7900\n"
            + "  yield {empno = #empno e, dname = #dname d}\n";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertType("{dname:string, empno:int} bag")
        .assertEvalIter(
            equalsOrdered(
                list("SALES", 7900),
                list("RESEARCH", 7902),
                list("ACCOUNTING", 7934)));
  }

  /**
   * As {@link #testScottJoin2()} but using dot notation ('e.field' rather than
   * '#field e').
   */
  @Test
  void testScottJoin2Dot() {
    final String ml =
        "from e in scott.emps, d in scott.depts\n"
            + "  where e.deptno = d.deptno\n"
            + "  andalso e.empno >= 7900\n"
            + "  yield {empno = e.empno, dname = d.dname}\n";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertType("{dname:string, empno:int} bag")
        .assertEvalIter(
            equalsOrdered(
                list("SALES", 7900),
                list("RESEARCH", 7902),
                list("ACCOUNTING", 7934)));
  }

  /** Tests that Morel gives the same answer with and without Calcite. */
  @Test
  void testQueryList() {
    final String[] queries = {
      "from",
      "from e in scott.emps",
      "from e in scott.emps yield e.deptno",
      "from e in scott.emps yield {e.deptno, e.ename}",
      "from e in scott.emps yield {e.ename, e.deptno}",
      "from e in scott.emps\n"
          + "  yield {e.ename, x = e.deptno + e.empno, b = true, "
          // + "c = #\"c\", "
          + "i = 3, r = 3.14, "
          // + "u = (), "
          + "s = \"hello\"}",
      "from e in scott.emps yield ()",
      "from e in scott.emps yield e",
      "from e in scott.emps where e.job = \"CLERK\" yield e",
      "from n in [1,2,3] yield n",
      "from n in [1,2,3] where n mod 2 = 1 andalso n < 3 yield n",
      "from n in [1,2,3] where false yield n",
      "from n in [1,2,3] where n < 2 orelse n > 2 yield n * 3",
      "from r in [{a=1,b=2},{a=1,b=0},{a=2,b=1}]\n"
          + "  order r.a desc, r.b\n"
          + "  yield {r.a, b10 = r.b * 10}",
      "from r in [{a=2,b=3},{a=2,b=1},{a=1,b=1}]\n" //
          + "  group r.a",
      "from r in [{a=2,b=3},{a=2,b=1},{a=1,b=1}]\n"
          + "  group compute sb = sum of r.b",
      "from r in [{a=2,b=3},{a=2,b=1},{a=1,b=1}]\n"
          + "  group r.a\n"
          + "  yield a",
      "from r in [{a=2,b=3}]\n"
          + "group r.b compute sb = sum of r.b,\n"
          + "    mb = min of r.b, a = count",
      "from r in [{a=2,b=3}]\n"
          + "group r.b compute sb = sum of r.b,\n"
          + "    mb = min of r.b, a = count\n"
          + "yield {a, a2 = a + b, sb}",
      "from e in scott.emps\n" //
          + "yield {e.ename, x = e.deptno * 2}",
      "from e in scott.emps\n" //
          + "order e.ename",
      "from e in scott.emps\n" //
          + "order e.ename desc\n"
          + "take 3",
      "from e in scott.emps,\n"
          + "  d in scott.depts\n"
          + "where e.deptno = d.deptno\n"
          + "yield {e.ename, d.dname}",
      "from e in scott.emps,\n"
          + "  d in scott.depts\n"
          + "where e.deptno = d.deptno",
      "from e in scott.emps,\n"
          + "  d in scott.depts\n"
          + "where e.deptno = d.deptno\n"
          + "yield e",
      "from e in scott.emps,\n"
          + "  d in scott.depts\n"
          + "where e.deptno = d.deptno\n"
          + "andalso e.job = \"CLERK\"\n"
          + "yield d",
      "from e in scott.emps,\n"
          + "  d in scott.depts\n"
          + "where e.deptno = d.deptno\n"
          + "andalso e.job = \"CLERK\"\n"
          + "group e.mgr",
      "from e in scott.emps,\n"
          + "  g in scott.salgrades\n"
          + "where e.sal >= g.losal\n"
          + "  andalso e.sal < g.hisal",
      "from e in scott.emps,\n"
          + "  d in scott.depts,"
          + "  g in scott.salgrades\n"
          + "where e.sal >= g.losal\n"
          + "  andalso e.sal < g.hisal\n"
          + "  andalso d.deptno = e.deptno",
      "from e in scott.emps,\n"
          + "  d in scott.depts,"
          + "  g in scott.salgrades\n"
          + "where e.sal >= g.losal\n"
          + "  andalso e.sal < g.hisal\n"
          + "  andalso d.deptno = e.deptno\n"
          + "group g.grade compute c = count",
      "from x in (from e in scott.emps yield {e.deptno, z = 1})\n"
          + "  union (from d in scott.depts yield {d.deptno, z = 2})",
      "from x in (from e in scott.emps yield e.deptno)\n"
          + "  union (from d in scott.depts yield d.deptno)\n"
          + "group x compute c = count",
      "from i in [1, 2, 3] union [2, 5, 4]",
      "from i in [1, 2, 3] union [2, 5, 4], [2, 1, 7]",
      "from i in [1, 2, 3] intersect [2, 5, 4]",
      "from i in [1, 2, 3] intersect [2, 5, 4], [2, 1, 6]",
      "from i in [1, 2, 3] except [2, 5, 4]",
      // Disabled because Calcite's interpreter gets the wrong answer:
      //   "from i in [1, 2, 3] except [2, 5, 4], [2, 1, 6]",
      "from i in [10, 15, 20] union (from d in scott.depts yield d.deptno)",
      // Disabled because Calcite's interpreter gets the wrong answer:
      //   "from i in [10, 15, 20]"
      //       + " except (from d in scott.depts yield d.deptno)",
      //   "from i in [10, 15, 20]"
      //       + " intersect (from d in scott.depts yield d.deptno)",

      // the following 4 are equivalent
      "from e in scott.emps where e.deptno = 30 yield e.empno",
      "let\n"
          + "  val emps = #emps scott\n"
          + "in\n"
          + "  from e in emps\n"
          + "  where e.deptno = 30\n"
          + "  yield e.empno\n"
          + "end",
      "let\n"
          + "  val emps = #emps scott\n"
          + "  val thirty = 30\n"
          + "in\n"
          + "  from e in emps\n"
          + "  where e.deptno = thirty\n"
          + "  yield e.empno\n"
          + "end",
      "Bag.map (fn e => (#empno e))\n"
          + "  (Bag.filter (fn e => (#deptno e) = 30) (#emps scott))",
    };
    Stream.of(queries)
        .filter(q -> !q.startsWith("#"))
        .forEach(
            query -> {
              try {
                ml(query)
                    .withBinding("scott", BuiltInDataSet.SCOTT)
                    .assertEvalSame();
              } catch (AssertionError | RuntimeException e) {
                throw new RuntimeException("during query [" + query + "]", e);
              }
            });
  }

  /**
   * Translates a hybrid expression. The leaf cannot be translated to Calcite
   * and therefore becomes a Morel table function; the root can.
   */
  @Test
  void testNative() {
    String query =
        ""
            + "from r in\n"
            + "  List.tabulate (6, fn i =>\n"
            + "    {i, j = i + 3, s = String.substring (\"morel\", 0, i)})\n"
            + "yield {r.j, r.s}";
    ml(query).withBinding("scott", BuiltInDataSet.SCOTT).assertEvalSame();
  }

  /**
   * Tests a query that can mostly be executed in Calcite, but is followed by
   * List.filter, which must be implemented in Morel. Therefore Morel calls into
   * the internal "calcite" function, passing the Calcite plan to be executed.
   */
  @Test
  void testHybridCalciteToMorel() {
    final String ml =
        "Bag.filter\n"
            + "  (fn x => x.empno < 7500)\n"
            + "  (from e in scott.emps\n"
            + "  where e.job = \"CLERK\"\n"
            + "  yield {e.empno, e.deptno, d5 = e.deptno + 5})";
    String plan =
        ""
            + "apply("
            + "fnCode apply(fnValue Bag.filter, "
            + "argCode match(x, apply2(fnValue <, "
            + "apply(fnValue nth:2, argCode get(name x)),"
            + " constant(7500)))), "
            + "argCode calcite("
            + "plan LogicalProject(d5=[+($1, 5)], deptno=[$1], empno=[$2])\n"
            + "  LogicalFilter(condition=[=($5, 'CLERK')])\n"
            + "    LogicalProject(comm=[$6], deptno=[$7], empno=[$0], ename=[$1], "
            + "hiredate=[$4], job=[$2], mgr=[$3], sal=[$5])\n"
            + "      JdbcTableScan(table=[[scott, EMP]])\n"
            + "))";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .with(Prop.HYBRID, true)
        .assertType("{d5:int, deptno:int, empno:int} bag")
        .assertEvalIter(equalsOrdered(list(25, 20, 7369)))
        .assertPlan(isCode(plan));
  }

  /** Tests a query that can be fully executed in Calcite. */
  @Test
  void testFullCalcite() {
    final String ml =
        "from e in scott.emps\n"
            + "  where e.empno < 7500\n"
            + "  yield {e.empno, e.deptno, d5 = e.deptno + 5}";
    checkFullCalcite(ml);
  }

  /** As {@link #testFullCalcite()} but table is via a {@code let}. */
  @Test
  void testFullCalcite2() {
    final String ml =
        "let\n"
            + "  val emps = scott.emps\n"
            + "in\n"
            + "  from e in emps\n"
            + "  where e.empno < 7500\n"
            + "  yield {e.empno, e.deptno, d5 = e.deptno + 5}\n"
            + "end";
    checkFullCalcite(ml);
  }

  /**
   * As {@link #testFullCalcite()} but query is a function, and table is passed
   * via an argument.
   */
  @Test
  void testFullCalcite3() {
    final String ml =
        "let\n"
            + "  fun query emps =\n"
            + "    from e in emps\n"
            + "    where e.empno < 7500\n"
            + "    yield {e.empno, e.deptno, d5 = e.deptno + 5}\n"
            + "in\n"
            + "  query scott.emps\n"
            + "end";
    checkFullCalcite(ml);
  }

  private void checkFullCalcite(String ml) {
    String plan =
        "calcite(plan "
            + "LogicalProject(d5=[+($1, 5)], deptno=[$1], empno=[$2])\n"
            + "  LogicalFilter(condition=[<($2, 7500)])\n"
            + "    LogicalProject(comm=[$6], deptno=[$7], empno=[$0], ename=[$1], "
            + "hiredate=[$4], job=[$2], mgr=[$3], sal=[$5])\n"
            + "      JdbcTableScan(table=[[scott, EMP]])\n"
            + ")";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .with(Prop.HYBRID, true)
        .assertType("{d5:int, deptno:int, empno:int} bag")
        .assertEvalIter(equalsOrdered(list(25, 20, 7369), list(35, 30, 7499)))
        .assertPlan(isCode(plan));
  }

  /**
   * Tests a query that is "from" over no variables. The result has one row and
   * zero columns.
   */
  @Test
  void testCalciteFrom() {
    final String ml = "from";
    String plan =
        "calcite(plan LogicalValues(tuples=[[{  }]])\n" //
            + ")";
    ml(ml)
        .with(Prop.HYBRID, true)
        .assertType("unit list")
        .assertPlan(isCode(plan))
        .assertEvalIter(equalsOrdered(list()));
  }

  /**
   * Tests a query that is executed in Calcite except for a variable, 'five',
   * whose value happens to always be '2 + 3'.
   */
  @Test
  void testCalciteWithVariable() {
    final String plan =
        "let(matchCode0 match(five, "
            + "apply2(fnValue +, constant(2), constant(3))), "
            + "resultCode calcite(plan "
            + "LogicalProject(d5=[+($1, morelScalar('five', '{\n"
            + "  \"type\": \"INTEGER\",\n"
            + "  \"nullable\": false\n"
            + "}'))], deptno=[$1], empno=[$2])\n"
            + "  LogicalFilter(condition=[<($2, +(7500, +(morelScalar('five', '{\n"
            + "  \"type\": \"INTEGER\",\n"
            + "  \"nullable\": false\n"
            + "}'), morelScalar('five', '{\n"
            + "  \"type\": \"INTEGER\",\n"
            + "  \"nullable\": false\n"
            + "}'))))])\n"
            + "    LogicalProject(comm=[$6], deptno=[$7], empno=[$0], ename=[$1], "
            + "hiredate=[$4], job=[$2], mgr=[$3], sal=[$5])\n"
            + "      JdbcTableScan(table=[[scott, EMP]])\n"
            + "))";
    final int inlinePassCount = 1; // limit inlining
    checkCalciteWithVariable(inlinePassCount, plan);
  }

  @Test
  void testCalciteWithVariableNoInlining() {
    final String plan =
        "let(matchCode0 match(five, "
            + "apply2(fnValue +, constant(2), constant(3))), "
            + "resultCode let(matchCode0 match(ten, "
            + "apply2(fnValue +, get(name five), get(name five))), "
            + "resultCode calcite(plan "
            + "LogicalProject(d5=[+($1, morelScalar('five', '{\n"
            + "  \"type\": \"INTEGER\",\n"
            + "  \"nullable\": false\n"
            + "}'))], deptno=[$1], empno=[$2])\n"
            + "  LogicalFilter(condition=[<($2, +(7500, morelScalar('ten', '{\n"
            + "  \"type\": \"INTEGER\",\n"
            + "  \"nullable\": false\n"
            + "}')))])\n"
            + "    LogicalProject(comm=[$6], deptno=[$7], empno=[$0], ename=[$1], "
            + "hiredate=[$4], job=[$2], mgr=[$3], sal=[$5])\n"
            + "      JdbcTableScan(table=[[scott, EMP]])\n"
            + ")))";
    final int inlinePassCount = 0;
    checkCalciteWithVariable(inlinePassCount, plan);
  }

  private void checkCalciteWithVariable(int inlinePassCount, String plan) {
    final String ml =
        "let\n"
            + "  val five = 2 + 3\n"
            + "  val ten = five + five\n"
            + "in\n"
            + "  from e in scott.emps\n"
            + "  where e.empno < 7500 + ten\n"
            + "  yield {e.empno, e.deptno, d5 = e.deptno + five}\n"
            + "end";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .with(Prop.HYBRID, true)
        .with(Prop.INLINE_PASS_COUNT, inlinePassCount)
        .assertType("{d5:int, deptno:int, empno:int} bag")
        .assertPlan(isCode(plan))
        .assertEvalIter(equalsOrdered(list(25, 20, 7369), list(35, 30, 7499)));
  }

  /**
   * Tests a query that is executed in Calcite except for a function, 'twice'.
   */
  @Test
  void testCalciteWithFunction() {
    final String ml =
        "let\n"
            + "  fun twice x = x + x\n"
            + "in\n"
            + "  from d in scott.depts\n"
            + "  yield twice d.deptno\n"
            + "end";
    String plan =
        "let(matchCode0 match(twice, match(x, "
            + "apply2(fnValue +, get(name x), get(name x)))), "
            + "resultCode calcite(plan "
            + "LogicalProject($f0=[morelScalar('int', "
            + "morelScalar('twice', '{\n"
            + "  \"type\": \"ANY\",\n"
            + "  \"nullable\": false,\n"
            + "  \"precision\": -1,\n"
            + "  \"scale\": -2147483648\n"
            + "}'), $0)])\n"
            + "  JdbcTableScan(table=[[scott, DEPT]])\n"
            + "))";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .with(Prop.HYBRID, true)
        .with(Prop.INLINE_PASS_COUNT, 0)
        .assertType("int bag")
        .assertPlan(isCode(plan))
        .assertEvalIter(equalsOrdered(20, 40, 60, 80));
  }

  /**
   * Tests a query that is executed in Calcite except for a function, 'plus';
   * one of its arguments comes from a relational record, and another from the
   * Morel environment.
   */
  @Test
  void testCalciteWithHybridFunction() {
    final String ml =
        "let\n"
            + "  fun plus (x, y) = x + y\n"
            + "  val five = 5\n"
            + "in\n"
            + "  from d in scott.depts\n"
            + "  yield plus (d.deptno, five)\n"
            + "end";
    String plan =
        "let(matchCode0 match(plus, match(v, "
            + "apply(fnCode match((x, y), apply2(fnValue +, "
            + "get(name x), get(name y))), "
            + "argCode get(name v)))), "
            + "resultCode let(matchCode0 match(five, constant(5)), "
            + "resultCode calcite(plan "
            + "LogicalProject($f0=[morelScalar('int * int', "
            + "morelScalar('plus', '{\n"
            + "  \"type\": \"ANY\",\n"
            + "  \"nullable\": false,\n"
            + "  \"precision\": -1,\n"
            + "  \"scale\": -2147483648\n"
            + "}'), ROW($0, morelScalar('five', '{\n"
            + "  \"type\": \"INTEGER\",\n"
            + "  \"nullable\": false\n"
            + "}')))])\n"
            + "  JdbcTableScan(table=[[scott, DEPT]])\n"
            + ")))";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .with(Prop.HYBRID, true)
        .with(Prop.INLINE_PASS_COUNT, 0)
        .assertType("int bag")
        .assertPlan(isCode(plan))
        .assertEvalIter(equalsOrdered(15, 25, 35, 45));
  }

  /** Tests that we can send {@code union} to Calcite. */
  @Test
  void testUnion() {
    final String ml =
        "from x in\n"
            + "(from e in scott.emps where e.job = \"CLERK\" yield e.deptno)\n"
            + "union\n"
            + "(from d in scott.depts yield d.deptno)\n";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .with(Prop.HYBRID, true)
        .assertType("int bag")
        .assertPlan(isFullyCalcite())
        .assertEvalIter(equalsUnordered(20, 20, 20, 40, 10, 10, 30, 30));
  }

  /** Tests that we can send {@code except} to Calcite. */
  @Test
  void testExcept() {
    final String ml =
        "from x in\n"
            + "(from d in scott.depts yield d.deptno)"
            + "except\n"
            + "(from e in scott.emps where e.job = \"CLERK\" yield e.deptno)\n";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .with(Prop.HYBRID, true)
        .assertType("int bag")
        .assertPlan(isFullyCalcite())
        .assertEvalIter(equalsUnordered(40));
  }

  /** Tests that we can send {@code intersect} to Calcite. */
  @Test
  void testIntersect() {
    final String ml =
        "from x in\n"
            + "(from e in scott.emps where e.job = \"CLERK\" yield e.deptno)\n"
            + "intersect\n"
            + "(from d in scott.depts yield d.deptno)\n";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .with(Prop.HYBRID, true)
        .assertType("int bag")
        .assertPlan(isFullyCalcite())
        .assertEvalIter(equalsUnordered(10, 20, 30));
  }

  /**
   * Tests that we can send (what in SQL would be) an uncorrelated {@code IN}
   * sub-query to Calcite.
   */
  @Test
  void testElem() {
    final String ml =
        "from d in scott.depts\n"
            + "where d.deptno elem (from e in scott.emps\n"
            + "    where e.job elem [\"ANALYST\", \"PRESIDENT\"]\n"
            + "    yield e.deptno)\n"
            + "yield d.dname";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .with(Prop.HYBRID, true)
        .assertType("string bag")
        .assertPlan(isFullyCalcite())
        .assertEvalIter(equalsUnordered("ACCOUNTING", "RESEARCH"));
  }

  /**
   * Tests that we can send (what in SQL would be) an uncorrelated {@code IN}
   * sub-query to Calcite.
   */
  @Test
  void testNotElem() {
    final UnaryOperator<Ml> fn =
        ml ->
            ml.withBinding("scott", BuiltInDataSet.SCOTT)
                .with(Prop.HYBRID, true)
                .assertType("string bag")
                .assertPlan(isFullyCalcite())
                .assertEvalIter(equalsUnordered("SALES", "OPERATIONS"));

    final String ml0 =
        "from d in scott.depts\n"
            + "where not (d.deptno elem\n"
            + "    (from e in scott.emps\n"
            + "        where e.job elem [\"ANALYST\", \"PRESIDENT\"]\n"
            + "        yield e.deptno))\n"
            + "yield d.dname";
    final String ml1 =
        "from d in scott.depts\n"
            + "where d.deptno notelem (from e in scott.emps\n"
            + "    where e.job elem [\"ANALYST\", \"PRESIDENT\"]\n"
            + "    yield e.deptno)\n"
            + "yield d.dname";
    fn.apply(ml(ml0));
    fn.apply(ml(ml1));
  }

  /**
   * Tests that {@code exists} is pushed down to Calcite. (There are no
   * correlating variables.)
   */
  @Test
  void testExists() {
    String plan =
        "calcite(plan "
            + "LogicalProject(deptno=[$0])\n"
            + "  LogicalProject(deptno=[$0], dname=[$1], loc=[$2])\n"
            + "    LogicalJoin(condition=[true], joinType=[inner])\n"
            + "      LogicalProject(deptno=[$0], dname=[$1], loc=[$2])\n"
            + "        JdbcTableScan(table=[[scott, DEPT]])\n"
            + "      LogicalAggregate(group=[{0}])\n"
            + "        LogicalProject(i=[true])\n"
            + "          LogicalFilter(condition=[=($5, 'CLERK')])\n"
            + "            LogicalProject(comm=[$6], deptno=[$7], empno=[$0], "
            + "ename=[$1], hiredate=[$4], job=[$2], mgr=[$3], sal=[$5])\n"
            + "              JdbcTableScan(table=[[scott, EMP]])\n"
            + ")";
    final UnaryOperator<Ml> fn =
        ml ->
            ml.withBinding("scott", BuiltInDataSet.SCOTT)
                .with(Prop.HYBRID, true)
                .assertType("int bag")
                .assertPlan(isFullyCalcite())
                .assertPlan(isCode(plan))
                .assertEvalIter(equalsOrdered(10, 20, 30, 40));
    final String ml0 =
        "from d in scott.depts\n"
            + "where Relational.nonEmpty (\n"
            + "  from e in scott.emps\n"
            + "  where e.job = \"CLERK\")\n"
            + "yield d.deptno";
    final String ml1 =
        "from d in scott.depts\n"
            + "where (\n"
            + "  exists e in scott.emps\n"
            + "  where e.job = \"CLERK\")\n"
            + "yield d.deptno";
    fn.apply(ml(ml0));
    fn.apply(ml(ml1));
  }

  /**
   * Tests that {@code not exists} (uncorrelated), also {@code empty} and {@code
   * List.null}, is pushed down to Calcite.
   */
  @Test
  void testNotExists() {
    final String plan =
        "calcite(plan "
            + "LogicalProject(deptno=[$0])\n"
            + "  LogicalProject(deptno=[$0], dname=[$1], loc=[$2])\n"
            + "    LogicalFilter(condition=[IS NULL($3)])\n"
            + "      LogicalJoin(condition=[true], joinType=[left])\n"
            + "        LogicalProject(deptno=[$0], dname=[$1], loc=[$2])\n"
            + "          JdbcTableScan(table=[[scott, DEPT]])\n"
            + "        LogicalAggregate(group=[{0}])\n"
            + "          LogicalProject(i=[true])\n"
            + "            LogicalFilter(condition=[=($5, 'CLARK KENT')])\n"
            + "              LogicalProject(comm=[$6], deptno=[$7], empno=[$0], "
            + "ename=[$1], hiredate=[$4], job=[$2], mgr=[$3], sal=[$5])\n"
            + "                JdbcTableScan(table=[[scott, EMP]])\n"
            + ")";
    final UnaryOperator<Ml> fn =
        ml ->
            ml.withBinding("scott", BuiltInDataSet.SCOTT)
                .with(Prop.HYBRID, true)
                .assertType("int bag")
                .assertPlan(isFullyCalcite())
                .assertEvalIter(equalsOrdered(10, 20, 30, 40))
                .assertPlan(isCode(plan));
    final String ml0 =
        "from d in scott.depts\n"
            + "where Relational.empty (\n"
            + "  from e in scott.emps\n"
            + "  where e.job = \"CLARK KENT\")\n"
            + "yield d.deptno";
    final String ml1 =
        "from d in scott.depts\n"
            + "where empty (\n"
            + "  from e in scott.emps\n"
            + "  where e.job = \"CLARK KENT\")\n"
            + "yield d.deptno";
    final String ml2 =
        "from d in scott.depts\n"
            + "where List.null (\n"
            + "  from e in scott.emps\n"
            + "  where e.job = \"CLARK KENT\")\n"
            + "yield d.deptno";
    final String ml3 =
        "from d in scott.depts\n"
            + "where not (\n"
            + "  exists e in scott.emps\n"
            + "  where e.job = \"CLARK KENT\")\n"
            + "yield d.deptno";
    fn.apply(ml(ml0));
  }

  /** Tests that correlated {@code exists} is pushed down to Calcite. */
  @Test
  void testExistsCorrelated() {
    String plan =
        "calcite(plan "
            + "LogicalProject(deptno=[$0], dname=[$1], loc=[$2])\n"
            + "  LogicalJoin(condition=[=($0, $3)], joinType=[inner])\n"
            + "    LogicalProject(deptno=[$0], dname=[$1], loc=[$2])\n"
            + "      JdbcTableScan(table=[[scott, DEPT]])\n"
            + "    LogicalProject(deptno=[$0], $f1=[true])\n"
            + "      LogicalAggregate(group=[{0}])\n"
            + "        LogicalProject(deptno=[$1])\n"
            + "          LogicalFilter(condition=[AND(=($5, 'CLERK'), "
            + "IS NOT NULL($1))])\n"
            + "            LogicalProject(comm=[$6], deptno=[$7], empno=[$0], "
            + "ename=[$1], hiredate=[$4], job=[$2], mgr=[$3], sal=[$5])\n"
            + "              JdbcTableScan(table=[[scott, EMP]])\n"
            + ")";
    final UnaryOperator<Ml> fn =
        ml ->
            ml.withBinding("scott", BuiltInDataSet.SCOTT)
                .with(Prop.HYBRID, true)
                .assertType("{deptno:int, dname:string, loc:string} bag")
                .assertPlan(isFullyCalcite())
                .assertPlan(isCode(plan))
                .assertEvalIter(
                    equalsOrdered(
                        list(10, "ACCOUNTING", "NEW YORK"),
                        list(20, "RESEARCH", "DALLAS"),
                        list(30, "SALES", "CHICAGO")));
    final String ml0 =
        "from d in scott.depts\n"
            + "where Relational.nonEmpty (\n"
            + "  from e in scott.emps\n"
            + "  where e.deptno = d.deptno\n"
            + "  andalso e.job = \"CLERK\")";
    final String ml1 =
        "from d in scott.depts\n"
            + "where (\n"
            + "  exists e in scott.emps\n"
            + "  where e.deptno = d.deptno\n"
            + "  andalso e.job = \"CLERK\")";
    fn.apply(ml(ml0));
    fn.apply(ml(ml1));
  }

  @Test
  void testCorrelatedListSubQuery() {
    final String ml =
        "from d in scott.depts\n"
            + "yield {d.dname, empCount = (from e in scott.emps\n"
            + "                            group e.deptno compute c = count\n"
            + "                            where deptno = d.deptno\n"
            + "                            yield c)}";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        // TODO: enable in hybrid; will require new method RexSubQuery.array
        // .with(Prop.HYBRID, true)
        .assertType("{dname:string, empCount:int bag} bag")
        .assertEvalIter(
            equalsOrdered(
                list("ACCOUNTING", list(3)),
                list("RESEARCH", list(5)),
                list("SALES", list(6)),
                list("OPERATIONS", list())));
  }

  @Test
  void testCorrelatedScalar() {
    final String ml =
        "from d in scott.depts\n"
            + "yield {d.dname, empCount =\n"
            + "    only (from e in scott.emps\n"
            + "          where e.deptno = d.deptno\n"
            + "          group compute count)}";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .with(Prop.HYBRID, true)
        .assertType("{dname:string, empCount:int} bag")
        .assertPlan(isFullyCalcite())
        .assertEvalIter(
            equalsOrdered(
                list("ACCOUNTING", 3),
                list("RESEARCH", 5),
                list("SALES", 6),
                list("OPERATIONS", 0)));
  }

  /**
   * Tests a recursive query that computes a transitive closure by successive
   * union operations. We cannot execute hybrid yet.
   */
  @Test
  void testRecursive() {
    final String ml =
        "let\n"
            + "  fun descendants2 descendants newDescendants =\n"
            + "    if Bag.null newDescendants then\n"
            + "      descendants\n"
            + "    else\n"
            + "      descendants2\n"
            + "          (from d in descendants union newDescendants)\n"
            + "          (from d in newDescendants,\n"
            + "              e in scott.emps\n"
            + "            where e.mgr = d.e.empno\n"
            + "            yield {e, level = d.level + 1})\n"
            + "in\n"
            + "  from d in descendants2 Bag.nil\n"
            + "      (from e in scott.emps\n"
            + "        where e.mgr = 0\n"
            + "        yield {e, level = 0})\n"
            + "    yield {d.e.empno, d.e.mgr, d.e.ename, d.level}\n"
            + "end";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertType("{empno:int, ename:string, level:int, mgr:int} bag")
        .assertEvalIter(
            equalsOrdered(
                list(7839, "KING", 0, 0),
                list(7566, "JONES", 1, 7839),
                list(7698, "BLAKE", 1, 7839),
                list(7782, "CLARK", 1, 7839),
                list(7788, "SCOTT", 2, 7566),
                list(7902, "FORD", 2, 7566),
                list(7499, "ALLEN", 2, 7698),
                list(7521, "WARD", 2, 7698),
                list(7654, "MARTIN", 2, 7698),
                list(7844, "TURNER", 2, 7698),
                list(7900, "JAMES", 2, 7698),
                list(7934, "MILLER", 2, 7782),
                list(7876, "ADAMS", 3, 7788),
                list(7369, "SMITH", 3, 7902)));
  }

  /**
   * Similar to {@link #testRecursive()} but uses the {@link
   * net.hydromatic.morel.compile.BuiltIn#RELATIONAL_ITERATE Relatonal.iterate}
   * function.
   */
  @Test
  void testRecursive2() {
    final String ml =
        "from i in iterate\n"
            + "    (from e in scott.emps\n"
            + "      where e.mgr = 0\n"
            + "      yield {e, level = 0})\n"
            + "    fn (oldList, newList) =>\n"
            + "      (from d in newList,\n"
            + "          e in scott.emps\n"
            + "        where e.mgr = d.e.empno\n"
            + "        yield {e, level = d.level + 1})\n"
            + "  yield {i.e.empno, i.e.ename, i.level, i.e.mgr}";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertType("{empno:int, ename:string, level:int, mgr:int} bag")
        .assertEvalIter(
            equalsOrdered(
                list(7839, "KING", 0, 0),
                list(7566, "JONES", 1, 7839),
                list(7698, "BLAKE", 1, 7839),
                list(7782, "CLARK", 1, 7839),
                list(7788, "SCOTT", 2, 7566),
                list(7902, "FORD", 2, 7566),
                list(7499, "ALLEN", 2, 7698),
                list(7521, "WARD", 2, 7698),
                list(7654, "MARTIN", 2, 7698),
                list(7844, "TURNER", 2, 7698),
                list(7900, "JAMES", 2, 7698),
                list(7934, "MILLER", 2, 7782),
                list(7876, "ADAMS", 3, 7788),
                list(7369, "SMITH", 3, 7902)));
  }
}

// End AlgebraTest.java
