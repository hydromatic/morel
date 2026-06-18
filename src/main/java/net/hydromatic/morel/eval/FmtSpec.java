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

import static net.hydromatic.morel.util.Static.padRightTo;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import net.hydromatic.morel.ast.Pos;

/**
 * Real formatting specification, after validation: a kind ("SCI", "FIX", "GEN",
 * "EXACT") and a precision {@code n}.
 *
 * @see net.hydromatic.morel.compile.BuiltIn#REAL_FMT
 */
class FmtSpec {
  final String kind;
  final int n;

  FmtSpec(String kind, int n) {
    this.kind = kind;
    this.n = n;
  }

  /**
   * Parses and validates a {@code StringCvt.realfmt} value. Validation happens
   * eagerly (on partial application) so that {@code Real.fmt (StringCvt.SCI
   * (SOME ~1))} raises {@code Size} immediately, matching SML/NJ's behavior;
   * {@code pos} is the position to report.
   */
  static FmtSpec parse(List spec, Pos pos) {
    final String kind = (String) spec.get(0);
    if (kind.equals("EXACT")) {
      return new FmtSpec("EXACT", 0);
    }
    final List opt = (List) spec.get(1);
    final Integer n = opt.size() == 2 ? (Integer) opt.get(1) : null;
    final int defaultN;
    final int minN;
    switch (kind) {
      case "SCI":
        defaultN = 6;
        minN = 0;
        break;
      case "FIX":
        defaultN = 6;
        minN = 0;
        break;
      case "GEN":
        defaultN = 12;
        minN = 1;
        break;
      default:
        throw new AssertionError("unknown realfmt: " + kind);
    }
    if (n != null && n < minN) {
      throw new Codes.MorelRuntimeException(Codes.BuiltInExn.SIZE, pos);
    }
    return new FmtSpec(kind, n != null ? n : defaultN);
  }

  /** Formats {@code r} according to this specification, as a new string. */
  String format(float r) {
    return formatAppend(new StringBuilder(), r).toString();
  }

  /**
   * Formats {@code r} according to this specification, appending to {@code b},
   * and returns {@code b}.
   */
  StringBuilder formatAppend(StringBuilder b, float r) {
    if (Float.isNaN(r)) {
      return b.append("nan");
    }
    if (r == Float.POSITIVE_INFINITY) {
      return b.append("inf");
    }
    if (r == Float.NEGATIVE_INFINITY) {
      return b.append("~inf");
    }
    switch (kind) {
      case "SCI":
        return formatSci(b, r, n);
      case "FIX":
        return formatFix(b, r, n);
      case "GEN":
        return formatGen(b, r, n);
      case "EXACT":
        return formatExact(b, r);
      default:
        throw new AssertionError();
    }
  }

  /** Returns "~" for negative reals (including {@code ~0.0}), else "". */
  private static String signPrefix(float r) {
    return Float.floatToRawIntBits(r) < 0 ? "~" : "";
  }

  /** Formats {@code abs} as a non-negative BigDecimal with the bits of r. */
  private static BigDecimal toBigDecimal(float r) {
    return new BigDecimal(Codes.FLOAT_TO_STRING.apply(Math.abs(r)));
  }

  private static StringBuilder formatFix(StringBuilder sb, float r, int n) {
    sb.append(signPrefix(r));
    if (r == 0.0f) {
      sb.append('0');
      if (n > 0) {
        sb.append('.');
        padRightTo(sb, sb.length() + n, '0');
      }
      return sb;
    }
    final BigDecimal bd = toBigDecimal(r).setScale(n, RoundingMode.HALF_DOWN);
    return sb.append(bd.toPlainString());
  }

  /** Formats r as {@code D.dddE±exp} with n digits after the decimal. */
  private static StringBuilder formatSci(StringBuilder sb, float r, int n) {
    sb.append(signPrefix(r));
    if (r == 0.0f) {
      sb.append('0');
      if (n > 0) {
        sb.append('.');
        padRightTo(sb, sb.length() + n, '0');
      }
      return sb.append("E0");
    }
    // Express |r| as mantissa * 10^exp where mantissa in [1, 10).
    final BigDecimal bd = toBigDecimal(r);
    int exp = decimalExp(bd);
    BigDecimal mantissa =
        bd.movePointLeft(exp).setScale(n, RoundingMode.HALF_DOWN);
    // Rounding may push the mantissa to exactly 10; renormalize.
    if (mantissa.compareTo(BigDecimal.TEN) >= 0) {
      mantissa = mantissa.movePointLeft(1);
      exp++;
    }
    sb.append(mantissa.toPlainString()).append('E');
    return appendSmlExp(sb, exp);
  }

  /** Formats r as {@code 0.dddE±exp} with no trailing zeros. */
  private static StringBuilder formatExact(StringBuilder sb, float r) {
    sb.append(signPrefix(r));
    if (r == 0.0f) {
      return sb.append("0.0");
    }
    // bd is already non-negative because toBigDecimal uses Math.abs.
    final BigDecimal bd = toBigDecimal(r).stripTrailingZeros();
    // Emit as 0.<digits>; the exponent is one greater than the standard
    // scientific exponent because the implied decimal point moves left by 1.
    sb.append("0.").append(bd.unscaledValue().toString());
    final int exp = decimalExp(bd) + 1;
    if (exp == 0) {
      return sb;
    }
    return appendSmlExp(sb.append('E'), exp);
  }

  /**
   * Formats r with at most n significant digits, using fixed-point notation
   * when the exponent is in {@code [-2, n)}, scientific notation otherwise.
   * Trailing zeros are dropped.
   */
  private static StringBuilder formatGen(StringBuilder sb, float r, int n) {
    sb.append(signPrefix(r));
    if (r == 0.0f) {
      return sb.append('0');
    }
    // Round to n significant digits, drop trailing zeros, then compute the
    // exponent (rounding 9.99 to 3 s.f. gives 10.0, which is 1E1).
    final BigDecimal bd =
        toBigDecimal(r)
            .round(new MathContext(n, RoundingMode.HALF_DOWN))
            .stripTrailingZeros();
    final int exp = decimalExp(bd);
    // SML/NJ uses scientific form when exp <= -3 or exp >= n (i.e., the
    // value would otherwise need leading zeros or be very large).
    if (exp <= -3 || exp >= n) {
      sb.append(bd.movePointLeft(exp).toPlainString()).append('E');
      return appendSmlExp(sb, exp);
    }
    // Fixed: emit at full precision, no trailing zeros.
    return sb.append(bd.toPlainString());
  }

  /**
   * The exponent that would appear in standard scientific notation — 0 for [1,
   * 10), 1 for [10, 100), -1 for [0.1, 1), etc. Assumes {@code bd} is non-zero.
   */
  private static int decimalExp(BigDecimal bd) {
    return bd.precision() - bd.scale() - 1;
  }

  /** Appends {@code exp} to {@code sb} using SML's {@code ~} for negative. */
  private static StringBuilder appendSmlExp(StringBuilder sb, int exp) {
    if (exp < 0) {
      sb.append('~');
      exp = -exp;
    }
    return sb.append(exp);
  }
}

// End FmtSpec.java
