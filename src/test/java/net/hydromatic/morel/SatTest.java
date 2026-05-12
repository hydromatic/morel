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

import static java.util.Collections.frequency;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.core.Is.is;

import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.util.Sat;
import net.hydromatic.morel.util.Sat.Term;
import net.hydromatic.morel.util.Sat.Variable;
import org.junit.jupiter.api.Test;

/** Tests satisfiability. */
public class SatTest {
  /**
   * Tests a formula with three clauses, three terms each. It is in "3SAT" form,
   * and has a solution (i.e. is satisfiable).
   */
  @Test
  void testBuild() {
    final Sat sat = new Sat();
    final Variable x = sat.variable("x");
    final Variable y = sat.variable("y");

    // (x ∨ x ∨ y) ∧ (¬x ∨ ¬y ∨ ¬y) ∧ (¬x ∨ y ∨ y)
    final Term clause0 = sat.or(x, x, y);
    final Term clause1 = sat.or(sat.not(x), sat.not(y), sat.not(y));
    final Term clause2 = sat.or(sat.not(x), y, y);
    final Term formula = sat.and(clause0, clause1, clause2);
    assertThat(
        formula, hasToString("(x ∨ x ∨ y) ∧ (¬x ∨ ¬y ∨ ¬y) ∧ (¬x ∨ y ∨ y)"));

    final Map<Variable, Boolean> solution = sat.solve(formula);
    assertThat(solution, notNullValue());
    assertThat(solution, is(ImmutableMap.of(x, false, y, true)));
  }

  /** Tests true ("and" with zero arguments). */
  @Test
  void testTrue() {
    final Sat sat = new Sat();
    final Term trueTerm = sat.and();
    assertThat(trueTerm, hasToString("true"));

    final Map<Variable, Boolean> solve = sat.solve(trueTerm);
    assertThat("satisfiable", solve, notNullValue());
    assertThat(solve.isEmpty(), is(true));
  }

  /** Tests false ("or" with zero arguments). */
  @Test
  void testFalse() {
    final Sat sat = new Sat();
    final Term falseTerm = sat.or();
    assertThat(falseTerm, hasToString("false"));

    final Map<Variable, Boolean> solve = sat.solve(falseTerm);
    assertThat("not satisfiable", solve, nullValue());
  }

  /**
   * Mirrors the workload that the pattern-coverage checker generates for a
   * datatype with 35 constructors: one boolean variable per constructor,
   * declared as a single one-hot slot. The previous brute-force solver could
   * not solve this, because 2^35 overflows {@link Integer#MAX_VALUE} inside
   * {@code Lists.cartesianProduct}).
   */
  @Test
  void testBigDatatype() {
    final Sat sat = new Sat();
    final int n = 35;
    final List<Variable> cs = new ArrayList<>();
    for (int i = 1; i <= n; i++) {
      cs.add(sat.variable(String.format("C%02d", i)));
    }
    sat.slot(cs);

    // "Is there a value not caught by C01?" — yes, any of C02..C35.
    final Map<Variable, Boolean> solution = sat.solve(sat.not(cs.get(0)));

    assertThat("satisfiable", solution, notNullValue());
    assertThat(
        "exactly one is true", frequency(solution.values(), true), is(1));
    assertThat("C01 is false", solution.get(cs.get(0)), is(false));
  }
}

// End SatTest.java
