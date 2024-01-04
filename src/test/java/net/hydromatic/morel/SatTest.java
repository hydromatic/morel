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

import net.hydromatic.morel.util.Sat;
import net.hydromatic.morel.util.Sat.Term;
import net.hydromatic.morel.util.Sat.Variable;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.core.Is.is;

/** Tests satisfiability. */
public class SatTest {
  /** Tests a formula with three clauses, three terms each.
   * It is in "3SAT" form, and has a solution (i.e. is satisfiable). */
  @Test void testBuild() {
    final Sat sat = new Sat();
    final Variable x = sat.variable("x");
    final Variable y = sat.variable("y");

    // (x ∨ x ∨ y) ∧ (¬x ∨ ¬y ∨ ¬y) ∧ (¬x ∨ y ∨ y)
    final Term clause0 = sat.or(x, x, y);
    final Term clause1 = sat.or(sat.not(x), sat.not(y), sat.not(y));
    final Term clause2 = sat.or(sat.not(x), y, y);
    final Term formula = sat.and(clause0, clause1, clause2);
    assertThat(formula.toString(),
        is("(x ∨ x ∨ y) ∧ (¬x ∨ ¬y ∨ ¬y) ∧ (¬x ∨ y ∨ y)"));

    final Map<Variable, Boolean> solution = sat.solve(formula);
    assertThat(solution, notNullValue());
    assertThat(solution,
        is(ImmutableMap.of(x, false, y, true)));
  }

  /** Tests true ("and" with zero arguments). */
  @Test void testTrue() {
    final Sat sat = new Sat();
    final Term trueTerm = sat.and();
    assertThat(trueTerm, hasToString("true"));

    final Map<Variable, Boolean> solve = sat.solve(trueTerm);
    assertThat("satisfiable", solve, notNullValue());
    assertThat(solve.isEmpty(), is(true));
  }

  /** Tests false ("or" with zero arguments). */
  @Test void testFalse() {
    final Sat sat = new Sat();
    final Term falseTerm = sat.or();
    assertThat(falseTerm, hasToString("false"));

    final Map<Variable, Boolean> solve = sat.solve(falseTerm);
    assertThat("not satisfiable", solve, nullValue());
  }

}

// End SatTest.java
