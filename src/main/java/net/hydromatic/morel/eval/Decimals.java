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
package net.hydromatic.morel.eval;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Utilities for values of the {@code decimal} type.
 *
 * <p>A {@code decimal} is represented at run time as a {@link BigDecimal} in
 * canonical form: rounded to the decimal128 format (34 significant digits,
 * half-even), with trailing zeros stripped. Canonical form means that each
 * value has one representation, so {@link BigDecimal#equals} and {@link
 * BigDecimal#hashCode} agree with numeric equality.
 *
 * @see net.hydromatic.morel.compile.BuiltIn#DECIMAL_DECIMAL
 */
public final class Decimals {
  private Decimals() {}

  /** Number of significant digits, 34. */
  public static final int PRECISION = 34;

  /** Rounding for arithmetic: 34 digits, half-even. */
  static final MathContext MATH_CONTEXT = MathContext.DECIMAL128;

  /** Largest adjusted exponent, 6144. */
  private static final int E_MAX = 6144;

  /**
   * Largest scale, 6176; the least significant digit of a value is never
   * smaller than 10<sup>-6176</sup>.
   */
  private static final int MAX_SCALE = 6176;

  /** Largest value, 9.999999999999999999999999999999999E6144. */
  public static final BigDecimal MAX_FINITE =
      new BigDecimal(
          BigInteger.TEN.pow(PRECISION).subtract(BigInteger.ONE),
          PRECISION - 1 - E_MAX);

  /** Smallest positive value, 1E-6176. */
  public static final BigDecimal MIN_POS =
      new BigDecimal(BigInteger.ONE, MAX_SCALE);

  /**
   * Syntax of a decimal: an optional sign, digits with an optional decimal
   * point, and an optional exponent.
   */
  private static final Pattern PATTERN =
      Pattern.compile(
          "([~+-]?)([0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE]([~+-]?)([0-9]+))?");

  /**
   * Largest magnitude of exponent that we parse; larger exponents are clamped.
   * Any exponent this large makes a value overflow or underflow.
   */
  private static final int MAX_PARSED_EXPONENT = 100_000;

  /**
   * Converts a value to canonical form, or returns null if it is too large.
   * Rounds half-even to 34 significant digits, truncates toward zero a value
   * too small to represent, and strips trailing zeros.
   */
  public static @Nullable BigDecimal canonical(BigDecimal d) {
    if (d.signum() == 0) {
      return BigDecimal.ZERO;
    }
    final int adjustedExponent = adjustedExponent(d);
    if (adjustedExponent > E_MAX) {
      return null;
    }
    if (adjustedExponent < -MAX_SCALE) {
      return BigDecimal.ZERO;
    }
    if (d.scale() > MAX_SCALE) {
      d = d.setScale(MAX_SCALE, RoundingMode.DOWN);
      if (d.signum() == 0) {
        return BigDecimal.ZERO;
      }
    }
    d = d.round(MATH_CONTEXT).stripTrailingZeros();
    // Rounding up may have increased the exponent, e.g. 9.99...9E6144.
    return adjustedExponent(d) > E_MAX ? null : d;
  }

  /**
   * Returns the exponent of a value in scientific notation; 0 for [1, 10), 1
   * for [10, 100), -1 for [0.1, 1), etc.
   */
  static int adjustedExponent(BigDecimal d) {
    return d.precision() - d.scale() - 1;
  }

  /**
   * Parses a string that is exactly a decimal, returning null if it is
   * malformed or its value cannot be represented exactly.
   *
   * <p>This is the semantics of the {@code decimal} function.
   */
  public static @Nullable BigDecimal parseExact(String s) {
    final Matcher matcher = PATTERN.matcher(s);
    if (!matcher.matches()) {
      return null;
    }
    final BigDecimal d = toBigDecimal(matcher);
    final BigDecimal c = canonical(d);
    return c != null && c.compareTo(d) == 0 ? c : null;
  }

  /**
   * Parses a decimal from a prefix of a string, after skipping leading
   * whitespace, returning null if there is no decimal. The result is not
   * rounded, and may be too large to represent; call {@link #canonical}.
   *
   * <p>This is the semantics of the {@code Decimal.fromString} function.
   */
  static @Nullable BigDecimal parsePrefix(String s) {
    int i = 0;
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      ++i;
    }
    final Matcher matcher = PATTERN.matcher(s);
    matcher.region(i, s.length());
    if (!matcher.lookingAt()) {
      return null;
    }
    return toBigDecimal(matcher);
  }

  /** Converts a successful match of {@link #PATTERN} to a value. */
  private static BigDecimal toBigDecimal(Matcher matcher) {
    final String mantissa = matcher.group(2);
    BigDecimal d =
        new BigDecimal(mantissa.endsWith(".") ? mantissa + "0" : mantissa);
    if (!matcher.group(1).isEmpty() && matcher.group(1).charAt(0) != '+') {
      d = d.negate();
    }
    final String exponentDigits = matcher.group(4);
    if (exponentDigits != null) {
      final int exponent =
          exponentDigits.length() > 6
              ? MAX_PARSED_EXPONENT
              : Math.min(Integer.parseInt(exponentDigits), MAX_PARSED_EXPONENT);
      final String exponentSign = matcher.group(3);
      d =
          d.scaleByPowerOfTen(
              !exponentSign.isEmpty() && exponentSign.charAt(0) != '+'
                  ? -exponent
                  : exponent);
    }
    return d;
  }

  /**
   * Converts a decimal to a string, using {@code ~} for negative values and
   * exponents.
   */
  public static String toString(BigDecimal d) {
    return toString(d, '~');
  }

  /**
   * Converts a decimal to a string, using {@code negation} for negative values
   * and exponents.
   *
   * <p>Uses plain notation if the adjusted exponent is in the range [-7, 34),
   * scientific notation otherwise; for example, "12.3", "1200", "1E100",
   * "1E~8".
   */
  public static String toString(BigDecimal d, char negation) {
    final int adjustedExponent =
        d.signum() == 0 ? 0 : adjustedExponent(d.stripTrailingZeros());
    if (adjustedExponent >= -7 && adjustedExponent < PRECISION) {
      return d.stripTrailingZeros().toPlainString().replace('-', negation);
    }
    final String digits =
        d.stripTrailingZeros().unscaledValue().abs().toString();
    final StringBuilder b = new StringBuilder();
    if (d.signum() < 0) {
      b.append(negation);
    }
    b.append(digits.charAt(0));
    if (digits.length() > 1) {
      b.append('.').append(digits, 1, digits.length());
    }
    b.append('E');
    if (adjustedExponent < 0) {
      b.append(negation);
    }
    return b.append(Math.abs(adjustedExponent)).toString();
  }
}

// End Decimals.java
