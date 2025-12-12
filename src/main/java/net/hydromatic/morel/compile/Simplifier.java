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
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.ImmutablePairList;

/** Simplifier of expressions. */
class Simplifier {
  private final TypeSystem typeSystem;
  private final ImmutablePairList<Core.NamedPat, Generator> generators;

  Simplifier(
      TypeSystem typeSystem, Multimap<Core.NamedPat, Generator> generators) {
    this.typeSystem = typeSystem;
    this.generators = ImmutablePairList.copyOf(generators.entries());
  }

  Simplifier(TypeSystem typeSystem) {
    this(typeSystem, ImmutableMultimap.of());
  }

  /**
   * Checks if two expressions are structurally equal.
   *
   * @param e1 First expression
   * @param e2 Second expression
   * @return True if expressions are structurally equal
   */
  private static boolean expEquals(Core.Exp e1, Core.Exp e2) {
    if (e1.op != e2.op) {
      return false;
    }

    if (e1.op == Op.ID) {
      Core.Id id1 = (Core.Id) e1;
      Core.Id id2 = (Core.Id) e2;
      return id1.idPat.equals(id2.idPat);
    }

    if (e1.op == Op.APPLY) {
      Core.Apply a1 = (Core.Apply) e1;
      Core.Apply a2 = (Core.Apply) e2;
      return expEquals(a1.fn, a2.fn) && expEquals(a1.arg, a2.arg);
    }

    // For other cases, use toString comparison (not perfect but works for
    // literals)
    return e1.toString().equals(e2.toString());
  }

  /**
   * Simplifies an expression.
   *
   * <ul>
   *   <li>{@code (x + y) - x} &rarr; {@code y} (for any expressions <i>x,
   *       y</i>)
   *   <li>{@code (x + y) - (x + z)} &rarr; {@code y - z} (for any expressions
   *       <i>x, y, z</i>)
   *   <li>{@code (x + c1) + c2} &rarr; {@code x + (c1 + c2)} (for any
   *       expression <i>x</i> and literals <i>c1, c2</i>)
   *   <li>{@code 3 + 1} &rarr; {@code 4}
   *   <li>{@code 3 - 1} &rarr; {@code 2}
   * </ul>
   */
  public static Core.Exp simplify(TypeSystem typeSystem, Core.Exp exp) {
    return new Simplifier(typeSystem).simplify(exp);
  }

  Core.Exp simplify(Core.Exp exp) {
    for (Map.Entry<Core.NamedPat, Generator> entry : generators) {
      exp = entry.getValue().simplify(typeSystem, entry.getKey(), exp);
    }
    switch (exp.op) {
      case FN:
        final Core.Fn fn = (Core.Fn) exp;
        Core.Exp simplifiedBody = simplify(fn.exp);
        if (simplifiedBody == fn.exp) {
          return fn;
        }
        return core.fn((FnType) fn.type, fn.idPat, simplifiedBody);
      case CASE:
        final Core.Case caseExp = (Core.Case) exp;
        Core.Exp simplifiedExp = simplify(caseExp.exp);
        List<Core.Match> simplifiedMatches = new ArrayList<>();
        boolean changed = simplifiedExp != caseExp.exp;
        for (Core.Match match : caseExp.matchList) {
          Core.Exp simplifiedMatchExp = simplify(match.exp);
          if (simplifiedMatchExp != match.exp) {
            changed = true;
            simplifiedMatches.add(
                core.match(match.pos, match.pat, simplifiedMatchExp));
          } else {
            simplifiedMatches.add(match);
          }
        }
        if (!changed) {
          return caseExp;
        }
        return core.caseOf(
            caseExp.pos, caseExp.type, simplifiedExp, simplifiedMatches);
      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;

        // Simplify the arguments.
        final List<Core.Exp> args;
        final List<Core.Exp> originalArgs;
        if (apply.arg.op == Op.TUPLE) {
          args = transformEager(apply.args(), this::simplify);
          originalArgs = apply.args();
        } else {
          // Single argument function
          args = ImmutableList.of(simplify(apply.arg));
          originalArgs = ImmutableList.of(apply.arg);
        }

        if (apply.isCallTo(BuiltIn.OP_MINUS)
            || apply.isCallTo(BuiltIn.Z_MINUS_INT)) {
          // Check for
          //   (x + y) - x => y
          //   (y + x) - x => y
          //   (x + y) - (x + z) => y - z
          if (args.get(0).op == Op.APPLY) {
            Core.Apply left = (Core.Apply) args.get(0);
            if (left.isCallTo(BuiltIn.OP_PLUS)
                || left.isCallTo(BuiltIn.Z_PLUS_INT)) {
              // Check if leftApply.arg(0) equals right: (x + y) - x => y
              if (expEquals(left.arg(0), args.get(1))) {
                return left.arg(1);
              }
              // Check if leftApply.arg(1) equals right: (y + x) - x => y
              if (expEquals(left.arg(1), args.get(1))) {
                return left.arg(0);
              }

              if (args.get(1).op == Op.APPLY) {
                Core.Apply rightApply = (Core.Apply) args.get(1);
                if (rightApply.isCallTo(BuiltIn.OP_PLUS)
                    || rightApply.isCallTo(BuiltIn.Z_PLUS_INT)) {
                  // Check if leftApply.arg(0) equals rightApply.arg(0):
                  // (x + y) - (x + z) => y - z
                  if (expEquals(left.arg(0), rightApply.arg(0))) {
                    return simplify(
                        apply.withArgs(left.arg(1), rightApply.arg(1)));
                  }
                  // Check if leftApply.arg(0) equals rightApply.arg(1):
                  // (x + y) - (z + x) => y - z
                  if (expEquals(left.arg(0), rightApply.arg(1))) {
                    return simplify(
                        apply.withArgs(left.arg(1), rightApply.arg(0)));
                  }
                  // Check if leftApply.arg(1) equals rightApply.arg(0):
                  // (y + x) - (x + z) => y - z
                  if (expEquals(left.arg(1), rightApply.arg(0))) {
                    return simplify(
                        apply.withArgs(left.arg(0), rightApply.arg(1)));
                  }
                  // Check if leftApply.arg(1) equals rightApply.arg(1):
                  // (y + x) - (z + x) => y - z
                  if (expEquals(left.arg(1), rightApply.arg(1))) {
                    return core.call(
                        typeSystem,
                        BuiltIn.OP_MINUS,
                        left.type,
                        Pos.ZERO,
                        left.arg(0),
                        rightApply.arg(0));
                  }
                }
              }

              // Check for (x + c1) - c2 => x + (c1 - c2)
              if (args.get(1).op == Op.INT_LITERAL
                  && left.arg(1).op == Op.INT_LITERAL) {
                BigDecimal c1 =
                    ((Core.Literal) left.arg(1)).unwrap(BigDecimal.class);
                BigDecimal c2 =
                    ((Core.Literal) args.get(1)).unwrap(BigDecimal.class);
                Core.Exp c3 = core.intLiteral(c1.subtract(c2));
                return simplify(
                    core.call(typeSystem, BuiltIn.Z_PLUS_INT, left.arg(0), c3));
              }
            }
          }
          // Check for constant subtraction. 6 - 2 => 4
          if (args.get(0).op == Op.INT_LITERAL
              && args.get(1).op == Op.INT_LITERAL) {
            BigDecimal left =
                ((Core.Literal) args.get(0)).unwrap(BigDecimal.class);
            BigDecimal rightVal =
                ((Core.Literal) args.get(1)).unwrap(BigDecimal.class);
            return core.intLiteral(left.subtract(rightVal));
          }
          // Return simplified subtraction
          if (args.get(0) != apply.arg(0) || args.get(1) != apply.arg(1)) {
            return simplify(
                core.call(
                    typeSystem,
                    BuiltIn.OP_MINUS,
                    args.get(0).type,
                    Pos.ZERO,
                    args.get(0),
                    args.get(1)));
          }
          return apply;
        } else if (apply.isCallTo(BuiltIn.OP_PLUS)
            || apply.isCallTo(BuiltIn.Z_PLUS_INT)) {
          // Check for constant addition
          if (args.get(0).op == Op.INT_LITERAL
              && args.get(1).op == Op.INT_LITERAL) {
            BigDecimal left =
                ((Core.Literal) args.get(0)).unwrap(BigDecimal.class);
            BigDecimal right =
                ((Core.Literal) args.get(1)).unwrap(BigDecimal.class);
            return core.intLiteral(left.add(right));
          }

          // Check for:
          //   (x + y) + c => x + (y + c)  if c is a literal
          if (args.get(0).op == Op.APPLY) {
            Core.Apply left = (Core.Apply) args.get(0);
            if (left.isCallTo(BuiltIn.OP_PLUS)
                || left.isCallTo(BuiltIn.Z_PLUS_INT)) {
              Core.Exp x = left.arg(0);
              Core.Exp y = left.arg(1);
              if (args.get(1).op == Op.INT_LITERAL) {
                return simplify(
                    core.call(
                        typeSystem,
                        BuiltIn.Z_PLUS_INT,
                        x,
                        core.call(
                            typeSystem, BuiltIn.Z_PLUS_INT, y, args.get(1))));
              }
            }
          }

          // Return simplified addition
          if (args.get(0) != apply.arg(0) || args.get(1) != apply.arg(1)) {
            return core.call(
                typeSystem,
                BuiltIn.OP_PLUS,
                args.get(0).type,
                Pos.ZERO,
                args.get(0),
                args.get(1));
          }
          return apply;
        }
        // Reconstruct the Apply if args changed
        if (!args.equals(originalArgs)) {
          return apply.withArgs(args);
        }
        return exp;
      default:
        return exp;
    }
  }
}

// End Simplifier.java
