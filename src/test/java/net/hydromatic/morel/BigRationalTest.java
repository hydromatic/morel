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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import net.hydromatic.morel.util.BigRational;
import org.junit.jupiter.api.Test;

/** Tests for {@link BigRational}. */
public class BigRationalTest {
  private static BigRational r(long n, long d) {
    return BigRational.of(BigInteger.valueOf(n), BigInteger.valueOf(d));
  }

  /**
   * A rational is kept in lowest terms with a positive denominator, so that
   * equal values are equal objects.
   */
  @Test
  void testNormalizes() {
    assertThat(r(2, 4), is(r(1, 2)));
    assertThat(r(1, -2), is(r(-1, 2)));
    assertThat(r(-2, -4), is(r(1, 2)));
    assertThat(r(1, -2).hashCode(), is(r(-1, 2).hashCode()));
    assertThat(r(6, 3), is(BigRational.of(2)));
    assertThat(r(0, 5), is(BigRational.ZERO));
    assertThat(r(10, 3), hasToString("10/3"));
    assertThat(r(6, 3), hasToString("2"));
  }

  /** Arithmetic is exact; in particular a third of one is a third. */
  @Test
  void testArithmetic() {
    assertThat(BigRational.ONE.divide(BigRational.of(3)), is(r(1, 3)));
    assertThat(r(1, 3).multiply(BigRational.of(3)), is(BigRational.ONE));
    assertThat(r(1, 3).add(r(1, 6)), is(r(1, 2)));
    assertThat(r(1, 3).subtract(r(1, 3)), is(BigRational.ZERO));
    assertThat(r(1, 3).negate(), is(r(-1, 3)));
    assertThat(r(-1, 3).signum(), is(-1));
    assertThat(BigRational.ZERO.signum(), is(0));
    // Ten thirds is not three, nor four.
    assertThat(BigRational.of(10).divide(BigRational.of(3)), is(r(10, 3)));
    assertThrows(
        IllegalArgumentException.class,
        () -> BigRational.ONE.divide(BigRational.ZERO));
  }

  /**
   * Comparison is by value, which for rationals means cross-multiplying.
   * Neither numerator nor denominator decides it on its own: 3/4 is greater
   * than 2/3 although both of its parts are the larger, and 1/3 is less than
   * 1/2 although its denominator is the larger.
   */
  @Test
  void testCompare() {
    assertThat(r(1, 3).compareTo(r(1, 2)) < 0, is(true));
    assertThat(r(-1, 3).compareTo(r(1, 300)) < 0, is(true));
    assertThat(r(2, 4).compareTo(r(1, 2)), is(0));
    // 3/4 > 2/3, because 9 > 8.
    assertThat(r(3, 4).compareTo(r(2, 3)) > 0, is(true));
    assertThat(r(2, 3).compareTo(r(3, 4)) < 0, is(true));
    // A positive is greater than a negative of larger magnitude.
    assertThat(r(3, 4).compareTo(r(-6, 7)) > 0, is(true));
    assertThat(r(-6, 7).compareTo(r(3, 4)) < 0, is(true));
    // Among negatives, the one further from zero is the lesser.
    assertThat(r(-6, 7).compareTo(r(-3, 4)) < 0, is(true));
    // A rational written either way round compares the same.
    assertThat(r(3, 4).compareTo(r(-3, -4)), is(0));
  }

  /**
   * {@code floor} and {@code ceil} go the same way on either side of zero: down
   * and up, not towards zero.
   */
  @Test
  void testFloorCeil() {
    assertThat(r(10, 3).floor(), is(BigInteger.valueOf(3)));
    assertThat(r(10, 3).ceil(), is(BigInteger.valueOf(4)));
    assertThat(r(-10, 3).floor(), is(BigInteger.valueOf(-4)));
    assertThat(r(-10, 3).ceil(), is(BigInteger.valueOf(-3)));
    assertThat(BigRational.of(3).floor(), is(BigInteger.valueOf(3)));
    assertThat(BigRational.of(3).ceil(), is(BigInteger.valueOf(3)));
    assertThat(BigRational.of(3).isInteger(), is(true));
    assertThat(r(10, 3).isInteger(), is(false));
  }

  /**
   * Every {@link BigDecimal} is a rational, so converting in loses nothing;
   * converting out may not be possible, and says so.
   */
  @Test
  void testBigDecimal() {
    assertThat(BigRational.of(new BigDecimal("2.5")), is(r(5, 2)));
    assertThat(BigRational.of(new BigDecimal("-0.125")), is(r(-1, 8)));
    assertThat(BigRational.of(new BigDecimal("12")), is(BigRational.of(12)));
    assertThat(r(5, 2).exactBigDecimal(), hasToString("2.5"));
    assertThat(r(1, 3).exactBigDecimal(), nullValue());
    assertThat(r(5, 2).exactBigDecimal(), notNullValue());
    // A value that does not terminate is rounded the way we ask.
    assertThat(
        r(10, 3).toBigDecimal(3, RoundingMode.FLOOR), hasToString("3.333"));
    assertThat(
        r(10, 3).toBigDecimal(3, RoundingMode.CEILING), hasToString("3.334"));
  }
}

// End BigRationalTest.java
