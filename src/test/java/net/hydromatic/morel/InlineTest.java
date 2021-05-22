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

import org.junit.jupiter.api.Test;

import static net.hydromatic.morel.Matchers.list;
import static net.hydromatic.morel.Matchers.whenAppliedTo;
import static net.hydromatic.morel.Ml.ml;

import static org.hamcrest.CoreMatchers.is;

/**
 * Test inlining and other optimizations.
 */
public class InlineTest {
  @Test void testAnalyze() {
    final String ml = "let\n"
        + "  val aUnused = 0\n"
        + "  val bOnce = 1\n"
        + "  val cOnce = 2\n"
        + "  val dOnce = 3\n"
        + "  val eTwice = bOnce + cOnce\n"
        + "  val fMultiSafe = dOnce\n"
        + "  val x = [1, 2]\n"
        + "  val z = case x of\n"
        + "     []            => fMultiSafe + 1\n"
        + "   | 1 :: x2 :: x3 => 2\n"
        + "   | x0 :: xs      => fMultiSafe + x0\n"
        + "in\n"
        + "  eTwice + eTwice\n"
        + "end";
    final String map = "{aUnused=DEAD, bOnce=ONCE_SAFE, cOnce=ONCE_SAFE, "
        + "dOnce=ONCE_SAFE, eTwice=MULTI_UNSAFE, fMultiSafe=MULTI_SAFE, "
        + "it=MULTI_UNSAFE, op +=MULTI_UNSAFE, x=ONCE_SAFE, x0=ONCE_SAFE, "
        + "x2=DEAD, x3=DEAD, xs=DEAD, z=DEAD}";
    ml(ml)
        .assertAnalyze(is(map));
  }

  @Test void testInline() {
    final String ml = "fun f x = let val y = x + 1 in y + 2 end";
    final String plan = "match(x, apply(fnValue +, argCode "
        + "tuple(apply(fnValue +, argCode tuple(get(name x), constant(1))), "
        + "constant(2))))";
    ml(ml).assertPlan(is(plan));
  }

  @Test void testInlineFn() {
    final String ml = "fun f x =\n"
        + "  let\n"
        + "    val succ = fn y => y + 1\n"
        + "  in\n"
        + "    succ x\n"
        + "  end";
    final String plan =
        "match(x, apply(fnValue +, argCode tuple(get(name x), constant(1))))";
    ml(ml).assertPlan(is(plan))
        .assertEval(whenAppliedTo(2, is(3)));
  }

  @Test void testInlineFnUnit() {
    final String ml = "fun f () = String.size \"abc\"";
    final String core = "fn v0 => #size String \"abc\"";
    ml(ml)
        .assertEval(whenAppliedTo(list(), is(3)))
        .assertCoreString(is(core));
  }

  @Test void testInlineChained() {
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

  /** Checks that a nested expression of depth {@code n} gives the right
   * answer and completes in a reasonable time. */
  private void checkInlineChained(int n) {
    final String ml = "fun f x0 =\n"
        + gen(1, n);
    final int expected = n * (n + 1) / 2;
    ml(ml).assertEval(Matchers.whenAppliedTo(0, is(expected)));
  }

  /** Generates a deeply nested expression,
   * such as
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
   * <p>Such an expression is a challenge for the inliner, because x0 is
   * inlined into x1, x1 is inlined into x2, and so forth. If done wrong, the
   * algorithm is exponential.
   */
  private String gen(int i, int n) {
    if (i == n) {
      return v(i - 1) + " + " + i;
    } else {
      return " let val " + v(i) + " = " + v(i - 1) + " + " + i + " in\n"
          + gen(i + 1, n) + "\n"
          + "end\n";
    }
  }

  /** Returns a variable name such as "x1". */
  private String v(int i) {
    return "x" + i;
  }

  /** Tests that a predicate is inlined inside {@code where}. */
  @Test void testFromPredicate() {
    final String ml = "let\n"
        + "  fun isEven n = n mod 2 = 0\n"
        + "in\n"
        + "  from e in scott.emp\n"
        + "  where isEven e.empno\n"
        + "  yield e.deptno\n"
        + "end";
    final String core0 = "let "
        + "val rec isEven = fn n => op = (op mod (n, 2), 0) "
        + "in "
        + "from e in #emp scott "
        + "where isEven (#empno e) yield #deptno e end";
    final String core1 = "from e in #emp scott "
        + "where let val n = #empno e in op mod (n, 2) = 0 end yield #deptno e";
    final String core2 = "from e in #emp scott "
        + "where op mod (#empno e, 2) = 0 yield #deptno e";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertCoreString(is(core0), is(core1), is(core2))
        .assertEval(is(list(20, 30, 30, 10, 20, 30, 20, 30, 20, 10)));
  }

  /** Tests that a predicate is inlined inside {@code where}. */
  @Test void testFromView() {
    final String ml = "let\n"
        + "  fun evenEmp x =\n"
        + "    from e in scott.emp\n"
        + "    where e.empno mod 2 = 0\n"
        + "in\n"
        + "  from e in (evenEmp 1)\n"
        + "  where e.deptno = 10\n"
        + "  yield e.ename\n"
        + "end";
    final String core0 = "let"
        + " val rec evenEmp = fn x =>"
        + " from e in #emp scott"
        + " where op = (op mod (#empno e, 2), 0)"
        + " yield e "
        + "in"
        + " from e#1 in evenEmp 1"
        + " where op = (#deptno e#1, 10)"
        + " yield #ename e#1 "
        + "end";
    final String core1 = "from e#1 in"
        + " let val x = 1"
        + " in from e in #emp scott"
        + " where op mod (#empno e, 2) = 0"
        + " yield e "
        + "end"
        + " where #deptno e#1 = 10"
        + " yield #ename e#1";
    final String core2 = "from e#1 in"
        + " from e in #emp scott"
        + " where op mod (#empno e, 2) = 0"
        + " yield e "
        + "where #deptno e#1 = 10 "
        + "yield #ename e#1";
    ml(ml)
        .withBinding("scott", BuiltInDataSet.SCOTT)
        .assertCoreString(is(core0), is(core1), is(core2));
  }

}

// End InlineTest.java
