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
package net.hydromatic.morel.util;

import static com.google.common.base.Preconditions.checkArgument;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import org.jspecify.annotations.Nullable;

/**
 * An exact rational number, {@code numerator / denominator}.
 *
 * <p>Always in lowest terms, with a positive denominator, so that two rationals
 * of equal value are equal objects and hash alike. Arithmetic is exact: unlike
 * {@link BigDecimal}, dividing 1 by 3 loses nothing, so a calculation that
 * divides does not have to choose a scale and a rounding mode, and cannot
 * quietly return a value that is not the one it computed.
 *
 * <p>Bounds arithmetic wants that. Deducing {@code x <= 10 / 3} and then
 * rounding to the tightest integer gives {@code x <= 3}; rounding the division
 * first, to a scale chosen in advance, invites a bound that is wrong in the
 * last place, and wrong in a direction that may drop a solution. Rounding
 * happens once, where the result is used, rather than at every step. See {@link
 * #floor}, {@link #ceil} and {@link #toBigDecimal}.
 */
public final class BigRational implements Comparable<BigRational> {
  public static final BigRational ZERO =
      new BigRational(BigInteger.ZERO, BigInteger.ONE);
  public static final BigRational ONE =
      new BigRational(BigInteger.ONE, BigInteger.ONE);

  /** Numerator; carries the sign. */
  public final BigInteger numerator;

  /** Denominator; always positive. */
  public final BigInteger denominator;

  private BigRational(BigInteger numerator, BigInteger denominator) {
    this.numerator = numerator;
    this.denominator = denominator;
  }

  /** Creates a rational {@code numerator / denominator}, in lowest terms. */
  public static BigRational of(BigInteger numerator, BigInteger denominator) {
    checkArgument(denominator.signum() != 0, "division by zero");
    BigInteger n = numerator;
    BigInteger d = denominator;
    if (d.signum() < 0) {
      n = n.negate();
      d = d.negate();
    }
    final BigInteger gcd = n.gcd(d);
    if (!gcd.equals(BigInteger.ONE)) {
      n = n.divide(gcd);
      d = d.divide(gcd);
    }
    return new BigRational(n, d);
  }

  /** Creates a whole number. */
  public static BigRational of(long value) {
    return new BigRational(BigInteger.valueOf(value), BigInteger.ONE);
  }

  /** Creates a whole number. */
  public static BigRational of(BigInteger value) {
    return new BigRational(value, BigInteger.ONE);
  }

  /**
   * Creates the rational equal to {@code value}.
   *
   * <p>Every {@link BigDecimal} is a rational -- it is an integer over a power
   * of ten -- so nothing is lost.
   */
  public static BigRational of(BigDecimal value) {
    final int scale = value.scale();
    if (scale <= 0) {
      return of(value.toBigInteger());
    }
    return of(value.unscaledValue(), BigInteger.TEN.pow(scale));
  }

  public BigRational add(BigRational that) {
    return of(
        numerator
            .multiply(that.denominator)
            .add(that.numerator.multiply(denominator)),
        denominator.multiply(that.denominator));
  }

  public BigRational subtract(BigRational that) {
    return add(that.negate());
  }

  public BigRational multiply(BigRational that) {
    return of(
        numerator.multiply(that.numerator),
        denominator.multiply(that.denominator));
  }

  /** Returns this divided by {@code that}; exact. */
  public BigRational divide(BigRational that) {
    checkArgument(that.signum() != 0, "division by zero");
    return of(
        numerator.multiply(that.denominator),
        denominator.multiply(that.numerator));
  }

  public BigRational negate() {
    return new BigRational(numerator.negate(), denominator);
  }

  /** Returns the sign: -1, 0 or 1. */
  public int signum() {
    return numerator.signum();
  }

  /** Returns whether this is a whole number. */
  public boolean isInteger() {
    return denominator.equals(BigInteger.ONE);
  }

  /** Returns the greatest integer that is not greater than this. */
  public BigInteger floor() {
    final BigInteger[] parts = numerator.divideAndRemainder(denominator);
    // Java truncates towards zero, so a negative with a remainder is one
    // too high.
    return parts[1].signum() < 0 ? parts[0].subtract(BigInteger.ONE) : parts[0];
  }

  /** Returns the least integer that is not less than this. */
  public BigInteger ceil() {
    final BigInteger[] parts = numerator.divideAndRemainder(denominator);
    return parts[1].signum() > 0 ? parts[0].add(BigInteger.ONE) : parts[0];
  }

  /**
   * Returns this as a {@link BigDecimal}, rounded as {@code roundingMode} says
   * if it has no exact decimal form (as 1/3 has not).
   */
  public BigDecimal toBigDecimal(int scale, RoundingMode roundingMode) {
    return new BigDecimal(numerator)
        .divide(new BigDecimal(denominator), scale, roundingMode);
  }

  /**
   * Returns this as a {@link BigDecimal} with no trailing zeros, if it has an
   * exact decimal form; otherwise null.
   */
  public @Nullable BigDecimal exactBigDecimal() {
    try {
      return new BigDecimal(numerator)
          .divide(new BigDecimal(denominator))
          .stripTrailingZeros();
    } catch (ArithmeticException e) {
      // A non-terminating decimal expansion, such as 1/3.
      return null;
    }
  }

  @Override
  public int compareTo(BigRational that) {
    return numerator
        .multiply(that.denominator)
        .compareTo(that.numerator.multiply(denominator));
  }

  @Override
  public boolean equals(@Nullable Object o) {
    return this == o
        || o instanceof BigRational
            && numerator.equals(((BigRational) o).numerator)
            && denominator.equals(((BigRational) o).denominator);
  }

  @Override
  public int hashCode() {
    return numerator.hashCode() * 31 + denominator.hashCode();
  }

  /** Returns "3" for a whole number, "10/3" otherwise. */
  @Override
  public String toString() {
    return isInteger() ? numerator.toString() : numerator + "/" + denominator;
  }
}

// End BigRational.java
