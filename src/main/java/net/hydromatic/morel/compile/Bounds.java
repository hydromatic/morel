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

import java.math.BigDecimal;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import org.checkerframework.checker.nullness.qual.Nullable;

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
   * <p>Examples: {@code x} -> {@code (x, 0)}; {@code x + 3} -> {@code (x, 3)};
   * {@code 5} -> {@code (null, 5)}; {@code x + y} -> {@code null}.
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
    if (op != BuiltIn.Z_PLUS_INT
        && op != BuiltIn.OP_PLUS
        && op != BuiltIn.Z_MINUS_INT
        && op != BuiltIn.OP_MINUS
        && op != BuiltIn.Z_PLUS_REAL
        && op != BuiltIn.Z_MINUS_REAL) {
      return null;
    }
    final Term a = linearTerm(apply.arg(0));
    final Term b = linearTerm(apply.arg(1));
    if (a == null || b == null) {
      return null;
    }
    final boolean minus =
        op == BuiltIn.Z_MINUS_INT
            || op == BuiltIn.OP_MINUS
            || op == BuiltIn.Z_MINUS_REAL;
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
   * (e.g. {@code #"a"} -> 97).
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
