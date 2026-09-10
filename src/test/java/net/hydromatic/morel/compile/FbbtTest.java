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
package net.hydromatic.morel.compile;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.startsWith;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.math.BigDecimal;
import java.util.ArrayList;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/** Tests for {@link Fbbt}. */
public class FbbtTest {
  private final TypeSystem typeSystem = new TypeSystem();

  {
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());
  }

  private final Core.IdPat xPat = core.idPat(PrimitiveType.INT, "x", 0);
  private final Core.IdPat yPat = core.idPat(PrimitiveType.INT, "y", 0);
  private final Core.Id xId = core.id(xPat);
  private final Core.Id yId = core.id(yPat);

  private final Core.IdPat zPat = core.idPat(PrimitiveType.INT, "z", 0);
  private final Core.Id zId = core.id(zPat);

  private final Core.IdPat rPat = core.idPat(PrimitiveType.REAL, "r", 0);
  private final Core.Id rId = core.id(rPat);

  private Core.Literal i(int n) {
    return core.intLiteral(BigDecimal.valueOf(n));
  }

  /** Returns {@code n * exp}. */
  private Core.Exp times(int n, Core.Exp exp) {
    return core.call(typeSystem, BuiltIn.INT_OP_TIMES, i(n), exp);
  }

  /** Returns {@code a + b}. */
  private Core.Exp plus(Core.Exp a, Core.Exp b) {
    return core.call(typeSystem, BuiltIn.INT_OP_PLUS, a, b);
  }

  /** Returns {@code a - b}. */
  private Core.Exp minus(Core.Exp a, Core.Exp b) {
    return core.call(typeSystem, BuiltIn.INT_OP_MINUS, a, b);
  }

  /** Returns {@code abs exp}. */
  private Core.Exp abs(Core.Exp exp) {
    return core.call(typeSystem, BuiltIn.INT_ABS, exp);
  }

  /** Returns {@code abs exp}, for a real-valued {@code exp}. */
  private Core.Exp realAbs(Core.Exp exp) {
    return core.call(typeSystem, BuiltIn.REAL_ABS, exp);
  }

  /** Returns {@code x * exp}, for a real-valued {@code exp}. */
  private Core.Exp realTimes(String x, Core.Exp exp) {
    return core.call(
        typeSystem,
        BuiltIn.REAL_OP_TIMES,
        core.realLiteral(new BigDecimal(x)),
        exp);
  }

  /** Returns {@code a <= b}. */
  private Core.Exp le(Core.Exp a, Core.Exp b) {
    return core.call(
        typeSystem, BuiltIn.OP_LE, PrimitiveType.BOOL, Pos.ZERO, a, b);
  }

  /** Returns the conjunction of {@code exps}. */
  private Core.Exp and(Core.Exp... exps) {
    return core.andAlso(typeSystem, ImmutableList.copyOf(exps));
  }

  /**
   * Strengthens {@code whereExp} treating {@code pats} as the unbounded
   * patterns; asserts the result matches {@code expected}.
   */
  private void checkStrengthen(
      Core.Exp whereExp, ImmutableSet<Core.NamedPat> pats, String expected) {
    final Core.Exp result = Fbbt.strengthen(typeSystem, pats, whereExp);
    assertThat(result, hasToString(expected));
  }

  /**
   * {@code x > 0 andalso x < 10} produces no deductions beyond what the input
   * already says, so the strengthen pass returns the input unchanged.
   */
  @Test
  void testConstantBoundsNoNewDeductions() {
    final Core.Exp w =
        core.andAlso(
            typeSystem,
            core.greaterThan(typeSystem, xId, i(0)),
            core.lessThan(typeSystem, xId, i(10)));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat), w);
    assertThat(result, equalTo(w));
  }

  /**
   * {@code x > 0 andalso x < y andalso y < 10} (the issue's cyclic-bound
   * example): FBBT deduces {@code x < 10} and {@code y > 0} by combining the
   * three input conjuncts.
   */
  @Test
  void testCyclicBoundDeduction() {
    // x > 0 andalso x < y andalso y < 10
    final Core.Exp w =
        core.andAlso(
            typeSystem,
            ImmutableSet.of(
                core.greaterThan(typeSystem, xId, i(0)),
                core.lessThan(typeSystem, xId, yId),
                core.lessThan(typeSystem, yId, i(10))));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat, yPat), w);
    // FBBT appends "x < 10" (from x < y, y < 10) and "y > 0" (from y > x,
    // x > 0). Both are strict, both open at the propagated endpoint.
    assertThat(
        result,
        hasToString(
            "x < 10 andalso (y > 0 andalso (x > 0 andalso (x < y andalso y < 10)))"));
  }

  /**
   * A constraint that references an unknown pattern (e.g. {@code y < 5}) leaves
   * {@code x}'s interval unchanged.
   */
  @Test
  void testOtherPatternUntouched() {
    final Core.Exp w = core.lessThan(typeSystem, yId, i(5));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat), w);
    // No bound on x; whereExp unchanged.
    assertThat(result, equalTo(w));
  }

  /**
   * {@code from} with no integer patterns: framework returns input unchanged.
   */
  @Test
  void testNoIntPatsIsNoOp() {
    final Core.Exp w = core.boolLiteral(true);
    final Core.Exp result = Fbbt.strengthen(typeSystem, ImmutableSet.of(), w);
    assertThat(result, equalTo(w));
  }

  /**
   * {@code abs x < 5}: FBBT deduces {@code x > ~5} and {@code x < 5} (open on
   * both sides because {@code <} is strict).
   */
  @Test
  void testAbsLessThan() {
    final Core.Apply absX = core.call(typeSystem, BuiltIn.INT_ABS, xId);
    final Core.Exp w = core.lessThan(typeSystem, absX, i(5));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat), w);
    assertThat(
        result, hasToString("x > ~5 andalso (x < 5 andalso #abs Int x < 5)"));
  }

  /**
   * A constraint with coefficients over two variables: {@code 3t + 5f = 30}
   * with {@code t, f >= 0} gives {@code t <= 10} and {@code f <= 6}.
   */
  @Test
  void testCoefficients() {
    final Core.Exp w =
        and(
            core.greaterThanOrEqualTo(typeSystem, xId, i(0)),
            core.greaterThanOrEqualTo(typeSystem, yId, i(0)),
            core.equal(typeSystem, plus(times(3, xId), times(5, yId)), i(30)));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat, yPat), w);
    assertThat(result, hasToString(startsWith("x <= 10 andalso (y <= 6")));
  }

  /**
   * An upper bound that does not divide exactly rounds down, and a lower bound
   * rounds up. {@code 3x <= 10} gives {@code x <= 3}, not {@code x <= 3.33};
   * rounding the wrong way would exclude {@code x = 3}, which satisfies the
   * constraint.
   */
  @Test
  void testUpperBoundRoundsDown() {
    final Core.Exp w =
        and(
            core.greaterThanOrEqualTo(typeSystem, xId, i(0)),
            le(times(3, xId), i(10)));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat), w);
    assertThat(result, hasToString(startsWith("x <= 3 andalso")));
  }

  /** As {@link #testUpperBoundRoundsDown}, but a lower bound rounds up. */
  @Test
  void testLowerBoundRoundsUp() {
    final Core.Exp w =
        and(
            le(xId, i(100)),
            core.greaterThanOrEqualTo(typeSystem, times(3, xId), i(10)));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat), w);
    assertThat(result, hasToString(startsWith("x >= 4 andalso")));
  }

  /**
   * A negative coefficient bounds a variable from the other side: {@code x - y
   * <= 5} with {@code 0 <= y <= 2} gives {@code x <= 7}.
   */
  @Test
  void testNegativeCoefficient() {
    final Core.Exp w =
        and(
            core.greaterThanOrEqualTo(typeSystem, yId, i(0)),
            le(yId, i(2)),
            le(minus(xId, yId), i(5)));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat, yPat), w);
    assertThat(result, hasToString(startsWith("x <= 7 andalso")));
  }

  /**
   * Constraints that contradict each other leave an empty interval. There is
   * nothing to deduce, and nothing to throw: an empty interval has no span.
   */
  @Test
  void testContradictionDeducesNothing() {
    final Core.Exp w =
        and(
            core.greaterThan(typeSystem, xId, i(5)),
            core.lessThan(typeSystem, xId, i(3)),
            core.lessThan(typeSystem, yId, xId));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat, yPat), w);
    assertThat(result, equalTo(w));
  }

  /**
   * As {@link #testContradictionDeducesNothing}, via a sum: {@code x + y = ~1}
   * with {@code x, y >= 0} deduces {@code x <= ~1}, which leaves {@code x} with
   * an empty interval. An empty interval emits no bound -- there is no pair of
   * endpoints to emit -- so the where-clause is unchanged, and the query goes
   * on to report that {@code x} is not grounded.
   */
  @Test
  void testContradictionViaSum() {
    final Core.Exp w =
        and(
            core.greaterThanOrEqualTo(typeSystem, xId, i(0)),
            core.greaterThanOrEqualTo(typeSystem, yId, i(0)),
            core.equal(typeSystem, plus(xId, yId), i(-1)));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat, yPat), w);
    assertThat(result, equalTo(w));
  }

  /** {@code abs (x - 2) < 5} gives {@code ~3 < x < 7}. */
  @Test
  void testAbsOfOffset() {
    final Core.Exp w = core.lessThan(typeSystem, abs(minus(xId, i(2))), i(5));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat), w);
    assertThat(result, hasToString(startsWith("x > ~3 andalso (x < 7")));
  }

  /**
   * A coefficient outside the absolute value works as one inside: {@code 3 *
   * abs (x - 2) < 9} gives {@code ~1 < x < 5}.
   */
  @Test
  void testCoefficientOutsideAbs() {
    final Core.Exp w =
        core.lessThan(typeSystem, times(3, abs(minus(xId, i(2)))), i(9));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat), w);
    assertThat(result, hasToString(startsWith("x > ~1 andalso (x < 5")));
  }

  /**
   * A negative coefficient inside the absolute value swaps the ends of the
   * interval: {@code abs (~3 * x) < 10} is {@code ~10/3 < x < 10/3}. Each end
   * still rounds outwards, so {@code ~4 < x < 4} -- weaker than the truth, but
   * never excluding a solution.
   */
  @Test
  void testNegativeCoefficientInsideAbs() {
    final Core.Exp w = core.lessThan(typeSystem, abs(times(-3, xId)), i(10));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat), w);
    assertThat(result, hasToString(startsWith("x >= ~3 andalso (x <= 3")));
  }

  /**
   * A large negative coefficient makes both ends round to the same place if
   * they round inwards. They must not: rounding outwards keeps the interval
   * non-empty, so {@code Range.open} has something to hold.
   */
  @Test
  void testLargeNegativeCoefficientInsideAbs() {
    final Core.Exp w =
        core.lessThan(
            typeSystem,
            realAbs(realTimes("-10000000000000.0", rId)),
            core.realLiteral(BigDecimal.ONE));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(rPat), w);
    assertThat(
        result, hasToString(startsWith("r > ~1E-12 andalso (r < 1E-12")));
  }

  /**
   * The argument of an absolute value must be linear in one variable. {@code
   * abs (x * y) < 5} is not, so FBBT declines rather than deducing something
   * wrong.
   */
  @Test
  void testAbsOfNonLinearDeclines() {
    final Core.Exp w =
        core.lessThan(
            typeSystem,
            abs(core.call(typeSystem, BuiltIn.INT_OP_TIMES, xId, yId)),
            i(5));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat, yPat), w);
    assertThat(result, equalTo(w));
  }

  /**
   * A variable that a scan bound -- 'z' here, which is not one of the patterns
   * we are deducing bounds for -- constrains its neighbours, and gets no bounds
   * of its own.
   */
  @Test
  void testScanBoundVariableConstrainsButIsNotBounded() {
    final Core.Exp w =
        and(
            core.greaterThanOrEqualTo(typeSystem, zId, i(1)),
            le(zId, i(3)),
            core.lessThan(typeSystem, xId, zId));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat), w);
    // 'x < 3' is deduced, and the conjuncts about 'z' are the ones we
    // started with: nothing is deduced for 'z'.
    assertThat(
        result,
        hasToString("x < 3 andalso (z >= 1 andalso (z <= 3 andalso x < z))"));
  }
}

// End FbbtTest.java
