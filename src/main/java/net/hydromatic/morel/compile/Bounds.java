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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableMap;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import org.jspecify.annotations.Nullable;

/**
 * Shared primitives for inspecting bound-shaped Core expressions.
 *
 * <p>Used by {@link Fbbt} (interval propagation), {@link Generators} (range
 * extractor), and {@link RangePushdown} (where-to-scan range tightening) so
 * they speak the same dialect of "what does a linear term / literal / variable
 * reference look like in Core".
 */
final class Bounds {
  private Bounds() {}

  /**
   * A linear term of the form {@code (var + offset)} or a pure constant {@code
   * (offset)} (when {@link #var} is null).
   */
  static final class Term {
    final Core.@Nullable NamedPat var;
    final BigDecimal offset;

    Term(Core.@Nullable NamedPat var, BigDecimal offset) {
      this.var = var;
      this.offset = offset;
    }
  }

  /**
   * Decomposes {@code exp} into a linear term {@code (var ?, offset)}. Returns
   * null if {@code exp} is not a linear combination of one variable and an
   * integer constant.
   *
   * <p>Examples: {@code x} &rarr; {@code (x, 0)}; {@code x + 3} &rarr; {@code
   * (x, 3)}; {@code 5} &rarr; {@code (null, 5)}; {@code x + y} &rarr; {@code
   * null}.
   */
  static @Nullable Term linearTerm(Core.Exp exp) {
    if (exp instanceof Core.Id) {
      final Core.NamedPat p = ((Core.Id) exp).idPat;
      return new Term(p, BigDecimal.ZERO);
    }
    if (exp instanceof Core.Literal) {
      final Core.Literal lit = numericLiteral(exp);
      if (lit == null) {
        return null;
      }
      return new Term(null, lit.unwrap(BigDecimal.class));
    }
    if (!(exp instanceof Core.Apply)) {
      return null;
    }
    final Core.Apply apply = (Core.Apply) exp;
    final BuiltIn op = apply.builtIn();
    if (op != BuiltIn.INT_OP_PLUS
        && op != BuiltIn.OP_PLUS
        && op != BuiltIn.INT_OP_MINUS
        && op != BuiltIn.OP_MINUS
        && op != BuiltIn.REAL_OP_PLUS
        && op != BuiltIn.REAL_OP_MINUS) {
      return null;
    }
    final Term a = linearTerm(apply.arg(0));
    final Term b = linearTerm(apply.arg(1));
    if (a == null || b == null) {
      return null;
    }
    final boolean minus =
        op == BuiltIn.INT_OP_MINUS
            || op == BuiltIn.OP_MINUS
            || op == BuiltIn.REAL_OP_MINUS;
    final BigDecimal otherOffset = minus ? b.offset.negate() : b.offset;
    if (a.var != null && b.var != null) {
      // Linear combination of two distinct variables; we don't model
      // that as a single Term.
      return null;
    }
    if (a.var == null && b.var == null) {
      return new Term(null, a.offset.add(otherOffset));
    }
    if (a.var != null) {
      // var + const, or var - const
      return new Term(a.var, a.offset.add(otherOffset));
    }
    // const + var. The "const - var" case (i.e. minus with var on rhs) would
    // introduce a -1 coefficient on var, which we don't model.
    if (minus) {
      return null;
    }
    return new Term(b.var, a.offset.add(b.offset));
  }

  /**
   * A linear combination of atoms, {@code c1 * a1 + ... + cn * an + k}.
   *
   * <p>Where {@link Term} models one variable with a coefficient of 1, a {@code
   * LinearForm} models any number of atoms with any integer or real
   * coefficients. It is what FBBT needs to reason about a constraint such as
   * {@code 25 * q + 10 * d + 5 * n + p = 100}.
   *
   * <p>An atom is a variable, or an {@code abs} term. An {@code abs} term is an
   * atom because, like a variable, it is a quantity that the arithmetic cannot
   * see into but whose value lies in a known interval -- in its case {@code [0,
   * inf)}. That is what lets FBBT bound the terms around it, and then the term
   * itself.
   */
  static final class LinearForm {
    /** Coefficients, keyed by atom. No coefficient is zero. */
    final Map<Core.Exp, BigDecimal> coefficients;

    final BigDecimal constant;

    LinearForm(Map<Core.Exp, BigDecimal> coefficients, BigDecimal constant) {
      this.coefficients = ImmutableMap.copyOf(coefficients);
      this.constant = constant;
    }

    /** Creates a form with no variables. */
    static LinearForm constant(BigDecimal constant) {
      return new LinearForm(ImmutableMap.of(), constant);
    }

    /** Returns whether this form has no variables. */
    boolean isConstant() {
      return coefficients.isEmpty();
    }

    /** Returns the sum of this form and {@code that}. */
    LinearForm plus(LinearForm that) {
      return combine(that, BigDecimal.ONE);
    }

    /** Returns the difference of this form and {@code that}. */
    LinearForm minus(LinearForm that) {
      return combine(that, BigDecimal.ONE.negate());
    }

    private LinearForm combine(LinearForm that, BigDecimal scale) {
      final Map<Core.Exp, BigDecimal> map = new LinkedHashMap<>(coefficients);
      that.coefficients.forEach(
          (v, c) ->
              map.merge(
                  v,
                  c.multiply(scale),
                  (c0, c1) -> {
                    final BigDecimal sum = c0.add(c1);
                    // A variable whose coefficients cancel (as 'x' does in
                    // 'x + y - x') drops out of the form.
                    return sum.signum() == 0 ? null : sum;
                  }));
      return new LinearForm(map, constant.add(that.constant.multiply(scale)));
    }

    /** Returns this form with every coefficient and the constant scaled. */
    LinearForm times(BigDecimal scale) {
      if (scale.signum() == 0) {
        return constant(BigDecimal.ZERO);
      }
      final Map<Core.Exp, BigDecimal> map = new LinkedHashMap<>();
      coefficients.forEach((v, c) -> map.put(v, c.multiply(scale)));
      return new LinearForm(map, constant.multiply(scale));
    }
  }

  /**
   * Decomposes {@code exp} into a {@link LinearForm}, or returns null if {@code
   * exp} is not linear.
   *
   * <p>Handles addition, subtraction, negation, and multiplication where one
   * side is constant. A product of two variables (say {@code x * y}) is not
   * linear, and gives null.
   *
   * <p>Examples: {@code 2 * x + 3} &rarr; {@code 2x + 3}; {@code x - y} &rarr;
   * {@code x - y}; {@code abs (x - 2) + abs (y - 3)} &rarr; a sum of two atoms;
   * {@code x * y} &rarr; null.
   */
  static @Nullable LinearForm linearForm(Core.Exp exp) {
    if (exp instanceof Core.Id || isAbs(exp)) {
      return new LinearForm(
          ImmutableMap.of(exp, BigDecimal.ONE), BigDecimal.ZERO);
    }
    if (exp instanceof Core.Literal) {
      final Core.Literal lit = numericLiteral(exp);
      return lit == null
          ? null
          : LinearForm.constant(lit.unwrap(BigDecimal.class));
    }
    if (!(exp instanceof Core.Apply)) {
      return null;
    }
    final Core.Apply apply = (Core.Apply) exp;
    final BuiltIn op = apply.builtIn();
    if (op == null) {
      return null;
    }
    switch (op) {
      case OP_NEGATE:
      case INT_OP_NEGATE:
      case REAL_OP_NEGATE:
        {
          final LinearForm f = linearForm(apply.arg);
          return f == null ? null : f.times(BigDecimal.ONE.negate());
        }
      case OP_PLUS:
      case INT_OP_PLUS:
      case REAL_OP_PLUS:
      case OP_MINUS:
      case INT_OP_MINUS:
      case REAL_OP_MINUS:
      case OP_TIMES:
      case INT_OP_TIMES:
      case REAL_OP_TIMES:
        break;
      default:
        return null;
    }
    final LinearForm a = linearForm(apply.arg(0));
    if (a == null) {
      return null;
    }
    final LinearForm b = linearForm(apply.arg(1));
    if (b == null) {
      return null;
    }
    switch (op) {
      case OP_PLUS:
      case INT_OP_PLUS:
      case REAL_OP_PLUS:
        return a.plus(b);
      case OP_MINUS:
      case INT_OP_MINUS:
      case REAL_OP_MINUS:
        return a.minus(b);
      default:
        // Multiplication is linear only if one side is constant.
        if (a.isConstant()) {
          return b.times(a.constant);
        }
        if (b.isConstant()) {
          return a.times(b.constant);
        }
        return null;
    }
  }

  /** Returns whether {@code exp} is a call to {@code abs}. */
  static boolean isAbs(Core.Exp exp) {
    if (!(exp instanceof Core.Apply)) {
      return false;
    }
    final BuiltIn b = ((Core.Apply) exp).builtIn();
    return b == BuiltIn.INT_ABS || b == BuiltIn.REAL_ABS;
  }

  /** Returns the argument of an {@code abs} term. */
  static Core.Exp absArg(Core.Exp exp) {
    checkArgument(isAbs(exp), "not abs: %s", exp);
    return ((Core.Apply) exp).arg;
  }

  /**
   * If {@code exp} is an integer or real literal, returns the {@link
   * Core.Literal}; otherwise null. Callers extract the numeric value with
   * {@link Core.Literal#unwrap(Class) unwrap(BigDecimal.class)}.
   *
   * <p>Keeping the {@code Literal} rather than pre-extracting a {@code
   * BigDecimal} lets the caller distinguish int from real (different {@link
   * Core.Literal#op}) and rebuild a literal of the same type later.
   */
  static Core.@Nullable Literal numericLiteral(Core.Exp exp) {
    if (!(exp instanceof Core.Literal)) {
      return null;
    }
    final Core.Literal lit = (Core.Literal) exp;
    switch (lit.op) {
      case INT_LITERAL:
      case REAL_LITERAL:
        return lit;
      default:
        return null;
    }
  }

  /**
   * Like {@link #numericLiteral} but also accepts {@code char} literals. Used
   * by {@link RangePushdown}, where bound endpoints can be int, real, or char.
   */
  static Core.@Nullable Literal scalarLiteral(Core.Exp exp) {
    if (!(exp instanceof Core.Literal)) {
      return null;
    }
    final Core.Literal lit = (Core.Literal) exp;
    switch (lit.op) {
      case INT_LITERAL:
      case REAL_LITERAL:
      case CHAR_LITERAL:
        return lit;
      default:
        return null;
    }
  }

  /**
   * Returns {@code lit}'s value as a {@link BigDecimal} suitable for arithmetic
   * and comparison. Char literals are encoded as the integer character code
   * (e.g. {@code #"a"} &rarr; 97).
   */
  static BigDecimal asBigDecimal(Core.Literal lit) {
    switch (lit.op) {
      case INT_LITERAL:
      case REAL_LITERAL:
        return lit.unwrap(BigDecimal.class);
      case CHAR_LITERAL:
        return BigDecimal.valueOf(
            (int) lit.unwrap(Character.class).charValue());
      default:
        throw new AssertionError("not a scalar literal: " + lit);
    }
  }

  /**
   * Returns whether {@code exp} is an {@link Core.Id} that references {@code
   * pat}.
   */
  static boolean isIdRef(Core.Exp exp, Core.Pat pat) {
    return exp.op == Op.ID && ((Core.Id) exp).idPat.equals(pat);
  }
}

// End Bounds.java
