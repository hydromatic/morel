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

import static net.hydromatic.morel.Matchers.isCode;
import static net.hydromatic.morel.Matchers.isUnordered;
import static net.hydromatic.morel.Matchers.list;
import static net.hydromatic.morel.Matchers.whenAppliedTo;
import static net.hydromatic.morel.Ml.ml;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.hasToString;

import net.hydromatic.morel.eval.Unit;
import org.junit.jupiter.api.Test;

/** Test inlining and other optimizations. */
public class InlineTest {
  @Test
  void testAnalyze() {
    final String ml =
        "let\n"
            + "  val aUnused = 0\n"
            + "  val bOnce = 1 + 1\n"
            + "  val cOnce = 2 + 2\n"
            + "  val dOnce = 3 + 3\n"
            + "  val eTwice = bOnce + cOnce\n"
            + "  val fMultiSafe = dOnce + 4\n"
            + "  val gAtomic = 5\n"
            + "  val x = [1, 2]\n"
            + "  val z = case x of\n"
            + "     []            => fMultiSafe + gAtomic\n"
            + "   | 1 :: x2 :: x3 => 2\n"
            + "   | x0 :: xs      => fMultiSafe + x0 + gAtomic\n"
            + "in\n"
            + "  eTwice + eTwice\n"
            + "end";
    final String map =
        "{aUnused=DEAD, bOnce=ONCE_SAFE, cOnce=ONCE_SAFE, "
            + "dOnce=ONCE_SAFE, eTwice=MULTI_UNSAFE, fMultiSafe=MULTI_SAFE, "
            + "gAtomic=ATOMIC, it=MULTI_UNSAFE, op +=MULTI_UNSAFE, "
            + "x=ONCE_SAFE, x0=ONCE_SAFE, x2=DEAD, x3=DEAD, xs=DEAD, z=DEAD}";
    ml(ml).assertAnalyze(hasToString(map));
  }

  @Test
  void testInline() {
    final String ml = "fun f x = let val y = x + 1 in y + 2 end";
    final String plan =
        "match(x, apply2(fnValue +, "
            + "apply2(fnValue +, get(name x), constant(1)), "
            + "constant(2)))";
    ml(ml).assertPlan(isCode(plan));
  }

  @Test
  void testInlineFn() {
    final String ml =
        "fun f x =\n"
            + "  let\n"
            + "    val succ = fn y => y + 1\n"
            + "  in\n"
            + "    succ x\n"
            + "  end";
    final String plan = "match(x, apply2(fnValue +, get(name x), constant(1)))";
    ml(ml).assertPlan(isCode(plan)).assertEval(whenAppliedTo(2, is(3)));
  }

  @Test
  void testInlineFnUnit() {
    final String ml = "fun f () = String.size \"abc\"";
    final String core = "val f = fn v0 => #size String \"abc\"";
    ml(ml)
        .assertEval(whenAppliedTo(list(), is(3)))
        .assertCore(2, hasToString(core));
  }

  /**
   * We inline a variable (y), even though it is used twice, because its value
   * is atomic (x).
   */
  @Test
  void testLetAtomic() {
    final String ml =
        "fun f x =\n"
            + "  let\n"
            + "    val y = x\n"
            + "  in\n"
            + "    y + 1 + y\n"
            + "  end";
    final String core = "val f = fn x => x + 1 + x";
    ml(ml).assertEval(whenAppliedTo(2, is(5))).assertCore(2, hasToString(core));
  }

  @Test
  void testInlineChained() {
    // Generate code "fun f x0 = x0 + 1";
    // calling "f 0" yields value 1.
    checkInlineChained(1);

    // Generate code "fun f x0 =
    //   let val x1 = x0 + 1
    //   in x1 + 2
    //   end";
    // calling "f 0" yields value 1 + 2 = 3.
    checkInlineChained(2);

    // Generate code "fun f x0 = ... in x2 + 3 ... end";
    // calling "f 0" yields value 1 + 2 + 3 = 6.
    checkInlineChained(3);

    // If inlining algorithm is exponential, this one will be super-slow.
    checkInlineChained(200);
  }

  /**
   * Checks that a nested expression of depth {@code n} gives the right answer
   * and completes in a reasonable time.
   */
  private void checkInlineChained(int n) {
    final String ml =
        "fun f x0 =\n" //
            + gen(1, n);
    final int expected = n * (n + 1) / 2;
    ml(ml).assertEval(Matchers.whenAppliedTo(0, is(expected)));
  }

  /**
   * Generates a deeply nested expression, such as
   *
   * <pre>{@code
   * fun f x0 =
   *   let val x1 = x0 + 1 in
   *      let val x2 = x1 + 2 in
   *        ...
   *           xN + (N + 1)
   *        ...
   *      end
   *    end
   * }</pre>
   *
   * <p>Such an expression is a challenge for the inliner, because x0 is inlined
   * into x1, x1 is inlined into x2, and so forth. If done wrong, the algorithm
   * is exponential.
   */
  private String gen(int i, int n) {
    if (i == n) {
      return v(i - 1) + " + " + i;
    } else {
      return " let val "
          + v(i)
          + " = "
          + v(i - 1)
          + " + "
          + i
          + " in\n"
          + gen(i + 1, n)
          + "\n"
          + "end\n";
    }
  }

  /** Returns a variable name such as "x1". */
  private String v(int i) {
    return "x" + i;
  }

  /** Tests that a predicate is inlined inside {@code where}. */
  @Test
  void testFromPredicate() {
    final String ml =
        "let\n"
            + "  fun isEven n = n mod 2 = 0\n"
            + "in\n"
            + "  from e in scott.emp\n"
            + "  where isEven e.empno\n"
            + "  yield e.deptno\n"
            + "end";
    final String core0 =
        "val it = "
            + "let "
            + "val isEven = fn n => n mod 2 = 0 "
            + "in "
            + "from e in #emp scott "
            + "where isEven (#empno e) yield #deptno e end";
    final String core1 =
        "val it = "
            + "from e in #emp scott "
            + "where let val n = #empno e in op mod (n, 2) = 0 end yield #deptno e";
    final String core2 =
        "val it = "
            + "from e in #emp scott "
            + "where op mod (#empno e, 2) = 0 yield #deptno e";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertCoreString(
            hasToString(core0), hasToString(core1), hasToString(core2))
        .assertEval(is(list(20, 30, 30, 10, 20, 30, 20, 30, 20, 10)));
  }

  /** Tests that a predicate is inlined inside {@code where}. */
  @Test
  void testFromView() {
    final String ml =
        "let\n"
            + "  fun evenEmp x =\n"
            + "    from e in scott.emp\n"
            + "    where e.empno mod 2 = 0\n"
            + "in\n"
            + "  from e in (evenEmp 1)\n"
            + "  where e.deptno = 10\n"
            + "  yield e.ename\n"
            + "end";
    final String core0 =
        "val it = "
            + "let"
            + " val evenEmp = fn x =>"
            + " from e in #emp scott"
            + " where #empno e mod 2 = 0 "
            + "in"
            + " from e_1 in evenEmp 1"
            + " where #deptno e_1 = 10"
            + " yield #ename e_1 "
            + "end";
    final String core1 =
        "val it = "
            + "from e_1 in "
            + "(let val x = 1"
            + " in from e in #emp scott"
            + " where op mod (#empno e, 2) = 0 "
            + "end)"
            + " where #deptno e_1 = 10"
            + " yield #ename e_1";
    final String core2 =
        "val it = "
            + "from e in #emp scott "
            + "where op mod (#empno e, 2) = 0 "
            + "yield {e = e} "
            + "where #deptno e_1 = 10 "
            + "yield #ename e_1";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertCoreString(
            hasToString(core0), hasToString(core1), hasToString(core2))
        .assertEval(isUnordered(list("CLARK", "MILLER")));
  }

  /**
   * Tests that an expression involving 'map' and 'filter' is converted to a
   * 'from' expression.
   */
  @Test
  void testMapFilterToFrom() {
    final String ml =
        "map (fn e => (#empno e))\n"
            + "  (List.filter (fn e => (#deptno e) = 30) (#emp scott))";
    final String core0 =
        "val it = "
            + "map (fn e_1 => #empno e_1) "
            + "(#filter List (fn e => #deptno e = 30) "
            + "(#emp scott))";
    final String core1 =
        "val it = "
            + "from v0 in "
            + "#filter List (fn e => #deptno e = 30) (#emp scott) "
            + "yield (fn e_1 => #empno e_1) v0";
    final String core2 =
        "val it = "
            + "from v2 in #emp scott "
            + "where #deptno v2 = 30 "
            + "yield {v0 = v2} "
            + "yield #empno v0";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertCoreString(
            hasToString(core0), hasToString(core1), hasToString(core2))
        .assertEval(isUnordered(list(7499, 7521, 7654, 7698, 7844, 7900)));
  }

  /**
   * Tests that an expression involving 'filter' then 'map' then 'filter' then
   * 'map' is converted to a 'from' expression.
   */
  @Test
  void testFilterMapFilterMapToFrom() {
    final String ml =
        ""
            + "map (fn r => r + 100)\n"
            + "  (map (fn r => #x r + #z r)\n"
            + "    (List.filter (fn r => #y r > #z r)\n"
            + "      (map (fn e => {x = #empno e, y = #deptno e, z = 15})\n"
            + "        (List.filter (fn e => #deptno e = 30)\n"
            + "          (#emp scott)))))";
    final String core0 =
        "val it = "
            + "map (fn r_2 => r_2 + 100)"
            + " (map (fn r_1 => #x r_1 + #z r_1)"
            + " (#filter List (fn r => #y r > #z r)"
            + " (map (fn e_1 => {x = #empno e_1, y = #deptno e_1, z = 15})"
            + " (#filter List (fn e => #deptno e = 30) (#emp scott)))))";
    final String core1 =
        "val it = "
            + "from v0 in #map List (fn r_1 => #x r_1 + #z r_1)"
            + " (#filter List (fn r => #y r > #z r)"
            + " (#map List (fn e_1 => {x = #empno e_1, y = #deptno e_1, z = 15})"
            + " (#filter List (fn e => #deptno e = 30) (#emp scott)))) "
            + "yield (fn r_2 => r_2 + 100) v0";
    final String core2 =
        "val it = "
            + "from v6 in #emp scott "
            + "where #deptno v6 = 30 "
            + "yield {v5 = v6} "
            + "yield {v4 = {x = #empno v5, y = #deptno v5, z = 15}} "
            + "where #y v4 > #z v4 "
            + "yield {v2 = v4} "
            + "yield {v0 = #x v2 + #z v2} "
            + "yield v0 + 100";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertCoreString(
            hasToString(core0), hasToString(core1), hasToString(core2))
        .assertEval(isUnordered(list(7614, 7636, 7769, 7813, 7959, 8015)));
  }

  @Test
  void testFromFrom() {
    final String ml =
        "from i in (\n"
            + "  from e in scott.emp\n"
            + "  yield e.deptno)\n"
            + "where i > 10\n"
            + "yield i / 10";
    final String core0 =
        "val it = "
            + "from e in #emp scott "
            + "yield {i = #deptno e} "
            + "where i > 10 "
            + "yield i / 10";
    final String core1 =
        "val it = "
            + "from e in #emp scott "
            + "yield {i = #deptno e} "
            + "where i > 10 "
            + "yield /:int (i, 10)";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertCoreString(
            hasToString(core0), hasToString(core1), hasToString(core1))
        .assertEval(isUnordered(list(2, 3, 3, 2, 3, 3, 2, 3, 2, 3, 2)));
  }

  @Test
  void testFromEmptyFrom() {
    final String ml =
        "from u in (from)\n" //
            + "where 3 < 4\n"
            + "yield {u, v = 10}";
    final String core0 =
        "val it = "
            + "from u in (from) "
            + "where 3 < 4 "
            + "yield {u = u, v = 10}";
    final String core1 =
        "val it = "
            + "from "
            + "yield {u = ()} "
            + "where 3 < 4 "
            + "yield {u = u, v = 10}";
    ml(ml)
        .assertCoreString(
            hasToString(core0), hasToString(core1), hasToString(core1))
        .assertEval(isUnordered(list(list(Unit.INSTANCE, 10))));
  }

  /** Tests that a singleton {@code case} is inlined. */
  @Test
  void testInlineCase() {
    final String ml =
        "let\n"
            + "  val f = fn x => case x of y => y + 2\n"
            + "in\n"
            + "  f 3\n"
            + "end";
    ml(ml)
        .assertCore(
            0,
            hasToString(
                "val it = "
                    + "let val f = fn x => case x of y => y + 2 in f 3 end"))
        .assertCore(2, hasToString("val it = let val x = 3 in x + 2 end"))
        .assertEval(is(5));
  }

  /** Tests that a singleton {@code case} is inlined. */
  @Test
  void testInlineCase2() {
    final String ml =
        "let\n"
            + "  val f = fn (x, y) => case (x, y) of (x1, y1) => x1 - y1\n"
            + "in\n"
            + "  f (13, 5)\n"
            + "end";
    ml(ml)
        .assertCore(
            0,
            hasToString(
                "val it = "
                    + "let"
                    + " val f = fn v0 => "
                    + "case v0 of (x, y) => "
                    + "case (x, y) of (x1, y1) => x1 - y1 "
                    + "in"
                    + " f (13, 5) "
                    + "end"))
        .assertCore(
            2,
            hasToString(
                "val it = "
                    + "let val v0 = (13, 5) "
                    + "in case v0 of (x, y) => -:int (x, y) "
                    + "end"))
        .assertEval(is(8));
  }
}

// End InlineTest.java
