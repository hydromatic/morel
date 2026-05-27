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

import com.google.common.collect.BoundType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Range;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Feasibility-based bound tightening (FBBT).
 *
 * <p>Given the conjunction of conjuncts in a {@code where} clause, FBBT
 * tightens the per-variable feasible interval by propagating each constraint,
 * iterating to a fixed point. Newly deduced bounds are appended to the {@code
 * where} clause as conjuncts; the existing range extractor in {@link
 * Generators} then turns them into finite generators.
 *
 * <p>Current scope: int- or real-valued patterns over (a) linear constraints of
 * the form {@code (varA + kA) OP (varB + kB)} for {@code OP} in {@code <, <=,
 * >, >=, =}, where {@code kA} and {@code kB} are numeric literal offsets and
 * either side may be a bare constant; (b) {@code abs(x) OP c} for the
 * connected-interval cases ({@code <}, {@code <=}, and {@code = 0}); and (c)
 * {@code (a * b) OP c} with non-negative operands. For real-typed patterns FBBT
 * deduces bounds the same way it does for int, but real extents are uncountable
 * so the downstream "not grounded" check still fires when nothing else makes
 * the pattern finite (e.g. a literal range scan, or a finite source like {@code
 * from e in emps}).
 *
 * <p>Shares the {@link Bounds.Term} decomposition and {@link
 * Bounds#linearTerm}, {@link Bounds#numericLiteral} helpers with {@link
 * Generators} and {@link RangePushdown}.
 *
 * <p>See <a href="https://github.com/hydromatic/morel/issues/373">issue
 * #373</a>.
 */
class Fbbt {
  /**
   * Maximum number of fixed-point iterations. FBBT typically converges in a
   * small number of rounds; this is a safety cap.
   */
  private static final int MAX_ROUNDS = 8;

  private static final ImmutableRangeSet<BigDecimal> ALL =
      ImmutableRangeSet.of(Range.all());

  private static final ImmutableList<Propagator> PROPAGATORS =
      ImmutableList.of(
          new LinearPropagator(),
          new AbsPropagator(),
          new MultiplyPropagator());

  private Fbbt() {}

  /**
   * Tightens the bounds of each pattern in {@code unboundedPats} by propagating
   * the conjuncts of {@code whereExp} to a fixed point.
   *
   * <p>Returns a strengthened where-expression with newly-deduced bounds
   * appended as additional conjuncts, or the original expression unchanged if
   * FBBT made no progress beyond what the input already expressed.
   *
   * @param typeSystem Type system
   * @param unboundedPats Patterns to deduce bounds for (typically the extent
   *     patterns of a {@code from})
   * @param whereExp Conjunction of constraints from a {@code where} clause
   */
  static Core.Exp strengthen(
      TypeSystem typeSystem,
      Set<Core.NamedPat> unboundedPats,
      Core.Exp whereExp) {
    final State state = new State(unboundedPats);
    if (state.isEmpty()) {
      return whereExp;
    }
    final List<Core.Exp> conjuncts = core.decomposeAnd(whereExp);
    // Snapshot the input-implied intervals so the materializer can tell
    // which bounds are *newly deduced* (and worth appending) versus
    // already-expressed by an input conjunct (which would just be noise).
    state.captureInputs(conjuncts);
    iterateToFixedPoint(state, conjuncts);
    return augmentWhere(typeSystem, whereExp, state);
  }

  /** Runs propagators on each conjunct, iterating until no bound tightens. */
  private static void iterateToFixedPoint(
      State state, List<Core.Exp> conjuncts) {
    for (int round = 0; round < MAX_ROUNDS; round++) {
      boolean changed = false;
      for (Core.Exp conjunct : conjuncts) {
        for (Propagator p : PROPAGATORS) {
          changed |= p.propagate(conjunct, state);
        }
      }
      if (!changed) {
        return;
      }
    }
  }

  /**
   * If FBBT deduced bounds tighter than the input already expressed, appends
   * them as new conjuncts to {@code whereExp}; otherwise returns it unchanged.
   */
  private static Core.Exp augmentWhere(
      TypeSystem typeSystem, Core.Exp whereExp, State state) {
    final ImmutableList.Builder<Core.Exp> extras = ImmutableList.builder();
    state.forEachDeducedBound(
        (pat, side, value, strict) ->
            extras.add(boundConjunct(typeSystem, pat, side, value, strict)));
    final ImmutableList<Core.Exp> extraConjuncts = extras.build();
    if (extraConjuncts.isEmpty()) {
      return whereExp;
    }
    // Prepend the deduced conjuncts. The existing range extractor in
    // Generators.lowerBound / upperBound returns the *first* matching
    // constraint, so putting our (constant-valued) bounds in front makes
    // them win over any same-side bound that references another variable
    // (which would create a cyclic generator dependency).
    return core.andAlso(
        typeSystem,
        ImmutableList.<Core.Exp>builder()
            .addAll(extraConjuncts)
            .add(whereExp)
            .build());
  }

  /**
   * Builds a conjunct expressing one side of a deduced bound. For example,
   * {@code (pat=x, lower=true, value=1, strict=false)} returns {@code x >= 1}.
   */
  private static Core.Exp boundConjunct(
      TypeSystem typeSystem,
      Core.NamedPat pat,
      boolean lower,
      BigDecimal value,
      boolean strict) {
    // Multiplication-style propagators can produce fractional bound values
    // (e.g. 30/4 = 7.5). For an integer-typed pattern, snap the bound to
    // the tightest integer endpoint: x > 7.5 => x >= 8, x < 7.5 => x <= 7.
    // For real-typed patterns no snap is needed — the BigDecimal carries
    // the exact value and real comparisons are well-defined at any
    // precision.
    if (pat.type == PrimitiveType.INT) {
      final BigDecimal floor = value.setScale(0, RoundingMode.FLOOR);
      if (floor.compareTo(value) != 0) {
        if (lower) {
          value = value.setScale(0, RoundingMode.CEILING);
        } else {
          value = floor;
        }
        strict = false;
      } else {
        // Value is integer-valued; strip any trailing zeros introduced by
        // earlier division so the literal prints as e.g. "27" not
        // "27.00000000".
        value = value.setScale(0, RoundingMode.UNNECESSARY);
      }
    }
    final Core.Exp idExp = core.id(pat);
    final Core.Exp constExp = core.literal((PrimitiveType) pat.type, value);
    if (lower) {
      return strict
          ? core.greaterThan(typeSystem, idExp, constExp)
          : core.greaterThanOrEqualTo(typeSystem, idExp, constExp);
    } else {
      return strict
          ? core.lessThan(typeSystem, idExp, constExp)
          : core.call(
              typeSystem,
              BuiltIn.OP_LE,
              PrimitiveType.BOOL,
              Pos.ZERO,
              idExp,
              constExp);
    }
  }

  /** Per-pattern feasible interval (an {@link ImmutableRangeSet}). */
  static class State {
    private final Set<Core.NamedPat> pats;
    private final Map<Core.NamedPat, ImmutableRangeSet<BigDecimal>> intervals =
        new HashMap<>();
    /**
     * Snapshot of intervals after applying only the constant-bound conjuncts of
     * the original where-clause. Used to identify which deductions are newly
     * produced by cross-variable propagation.
     */
    private final Map<Core.NamedPat, ImmutableRangeSet<BigDecimal>> inputs =
        new HashMap<>();

    State(Set<Core.NamedPat> pats) {
      this.pats = pats;
    }

    boolean isEmpty() {
      for (Core.NamedPat p : pats) {
        if (isNumeric(p.type)) {
          return false;
        }
      }
      return true;
    }

    boolean knows(Core.NamedPat pat) {
      return pats.contains(pat) && isNumeric(pat.type);
    }

    /**
     * Returns whether {@code t} is a numeric primitive type FBBT can track:
     * {@code int} or {@code real}. Both store values as {@code BigDecimal} in
     * the interval map.
     */
    private static boolean isNumeric(Type t) {
      return t == PrimitiveType.INT || t == PrimitiveType.REAL;
    }

    ImmutableRangeSet<BigDecimal> get(Core.NamedPat pat) {
      return intervals.getOrDefault(pat, ALL);
    }

    /**
     * Intersects {@code pat}'s current interval with {@code rangeSet}. Returns
     * whether the interval actually tightened.
     */
    boolean tighten(Core.NamedPat pat, ImmutableRangeSet<BigDecimal> rangeSet) {
      if (!knows(pat)) {
        return false;
      }
      final ImmutableRangeSet<BigDecimal> current = get(pat);
      final ImmutableRangeSet<BigDecimal> next = current.intersection(rangeSet);
      if (next.equals(current)) {
        return false;
      }
      intervals.put(pat, next);
      return true;
    }

    /**
     * Initializes {@link #inputs} by scanning {@code conjuncts} for
     * constant-side bounds (i.e. {@code (var + k) OP c}). Mirrors the intervals
     * into {@link #inputs} and {@link #intervals} so iteration starts from the
     * same place but the snapshot is preserved.
     */
    void captureInputs(List<Core.Exp> conjuncts) {
      for (Core.Exp conjunct : conjuncts) {
        LinearPropagator.applyConstantBound(conjunct, this, true);
      }
      // After capturing, intervals == inputs. The fixed-point loop will
      // diverge them.
      inputs.putAll(intervals);
    }

    /**
     * Streams every side (lower/upper) where the final bound is strictly
     * tighter than the input bound. Patterns are visited in name order so the
     * emitted conjuncts are deterministic; lower side is visited before upper
     * for the same pattern.
     */
    void forEachDeducedBound(DeducedBoundConsumer consumer) {
      final List<Core.NamedPat> sortedPats =
          new ArrayList<>(intervals.keySet());
      sortedPats.sort(Comparator.comparing(p -> p.name));
      for (Core.NamedPat pat : sortedPats) {
        final ImmutableRangeSet<BigDecimal> finalRs = intervals.get(pat);
        if (finalRs.isEmpty()) {
          continue;
        }
        final ImmutableRangeSet<BigDecimal> inputRs =
            inputs.getOrDefault(pat, ALL);
        final Range<BigDecimal> finalSpan = finalRs.span();
        final Range<BigDecimal> inputSpan =
            inputRs.isEmpty() ? Range.all() : inputRs.span();
        if (finalSpan.hasLowerBound() && isLowerTighter(finalSpan, inputSpan)) {
          consumer.accept(
              pat,
              true,
              finalSpan.lowerEndpoint(),
              finalSpan.lowerBoundType() == BoundType.OPEN);
        }
        if (finalSpan.hasUpperBound() && isUpperTighter(finalSpan, inputSpan)) {
          consumer.accept(
              pat,
              false,
              finalSpan.upperEndpoint(),
              finalSpan.upperBoundType() == BoundType.OPEN);
        }
      }
    }

    /**
     * Returns whether {@code finalSpan}'s lower endpoint is strictly tighter
     * than {@code inputSpan}'s. Tighter means: input had no lower bound but
     * final does; or the final lower exceeds the input lower; or they share the
     * same value but final is closed-strict (OPEN) and input is closed (CLOSED
     * — same value reachable). For our use the values come from the same
     * propagator, so the equal-value case never triggers a "newly deduced"
     * emission.
     */
    private static boolean isLowerTighter(
        Range<BigDecimal> finalSpan, Range<BigDecimal> inputSpan) {
      if (!inputSpan.hasLowerBound()) {
        return true;
      }
      final int cmp =
          finalSpan.lowerEndpoint().compareTo(inputSpan.lowerEndpoint());
      if (cmp > 0) {
        return true;
      }
      if (cmp < 0) {
        return false;
      }
      // Same value: tighter only if final is OPEN and input is CLOSED.
      return finalSpan.lowerBoundType() == BoundType.OPEN
          && inputSpan.lowerBoundType() == BoundType.CLOSED;
    }

    private static boolean isUpperTighter(
        Range<BigDecimal> finalSpan, Range<BigDecimal> inputSpan) {
      if (!inputSpan.hasUpperBound()) {
        return true;
      }
      final int cmp =
          finalSpan.upperEndpoint().compareTo(inputSpan.upperEndpoint());
      if (cmp < 0) {
        return true;
      }
      if (cmp > 0) {
        return false;
      }
      return finalSpan.upperBoundType() == BoundType.OPEN
          && inputSpan.upperBoundType() == BoundType.CLOSED;
    }
  }

  /** Receives one newly-deduced bound side. */
  @FunctionalInterface
  interface DeducedBoundConsumer {
    /**
     * Receives one tightened bound for materialization.
     *
     * @param pat Pattern
     * @param lower True for lower bound ({@code x >= v} / {@code x > v}), false
     *     for upper
     * @param value Bound value
     * @param strict True for strict inequality ({@code >} / {@code <}), false
     *     for non-strict
     */
    void accept(
        Core.NamedPat pat, boolean lower, BigDecimal value, boolean strict);
  }

  /**
   * Examines a single constraint and (possibly) tightens the bounds of one or
   * more patterns in {@code state}.
   */
  @FunctionalInterface
  interface Propagator {
    /** Returns whether any pattern's interval tightened. */
    boolean propagate(Core.Exp constraint, State state);
  }

  /**
   * Propagator for linear constraints of the form {@code (varA + kA) OP (varB +
   * kB)} where {@code kA}, {@code kB} are integer-literal offsets (possibly
   * zero, possibly missing on one side) and {@code OP} is one of {@code <, <=,
   * >, >=, =}.
   */
  static class LinearPropagator implements Propagator {
    @Override
    public boolean propagate(Core.Exp constraint, State state) {
      if (constraint.op != Op.APPLY) {
        return false;
      }
      final BuiltIn op = constraint.builtIn();
      if (op == null || !isComparisonOp(op)) {
        return false;
      }
      final Bounds.Term lhs = Bounds.linearTerm(constraint.arg(0));
      final Bounds.Term rhs = Bounds.linearTerm(constraint.arg(1));
      if (lhs == null || rhs == null) {
        return false;
      }
      // Both sides constant: nothing to deduce.
      if (lhs.var == null && rhs.var == null) {
        return false;
      }
      // Constant on one side: a "x + k OP c" constraint. Reduce to
      //   x OP (c - k)
      if (lhs.var == null) {
        return tightenFromConstant(
            state, rhs.var, op.reverse(), lhs.offset.subtract(rhs.offset));
      }
      if (rhs.var == null) {
        return tightenFromConstant(
            state, lhs.var, op, rhs.offset.subtract(lhs.offset));
      }
      // Both sides have a variable: "varA + kA OP varB + kB"
      // Reduce to "varA OP varB + (kB - kA)".
      final BigDecimal delta = rhs.offset.subtract(lhs.offset);
      boolean changed = false;
      changed |= tightenFromOther(state, lhs.var, op, rhs.var, delta);
      // And solving for varB: "varB OP' varA - (kB - kA)" where OP' is the
      // reverse. (E.g. "x < y + 1" becomes "y > x - 1".)
      changed |=
          tightenFromOther(
              state, rhs.var, op.reverse(), lhs.var, delta.negate());
      return changed;
    }

    /** Applies a "var op constant" tightening to {@code state}. */
    static boolean applyConstantBound(
        Core.Exp constraint, State state, boolean recordOnly) {
      if (constraint.op != Op.APPLY) {
        return false;
      }
      final BuiltIn op = constraint.builtIn();
      if (op == null || !isComparisonOp(op)) {
        return false;
      }
      final Bounds.Term lhs = Bounds.linearTerm(constraint.arg(0));
      final Bounds.Term rhs = Bounds.linearTerm(constraint.arg(1));
      if (lhs == null || rhs == null) {
        return false;
      }
      // We only want the constant case here.
      final Core.NamedPat pat;
      final BuiltIn finalOp;
      final BigDecimal constant;
      if (lhs.var != null && rhs.var == null) {
        pat = lhs.var;
        finalOp = op;
        constant = rhs.offset.subtract(lhs.offset);
      } else if (lhs.var == null && rhs.var != null) {
        pat = rhs.var;
        finalOp = op.reverse();
        constant = lhs.offset.subtract(rhs.offset);
      } else {
        return false;
      }
      return tightenFromConstant(state, pat, finalOp, constant);
    }

    private static boolean isComparisonOp(BuiltIn op) {
      switch (op) {
        case OP_LT:
        case OP_LE:
        case OP_GT:
        case OP_GE:
        case OP_EQ:
          return true;
        default:
          return false;
      }
    }

    /** Tightens {@code pat}'s interval by {@code pat OP constant}. */
    private static boolean tightenFromConstant(
        State state, Core.NamedPat pat, BuiltIn op, BigDecimal constant) {
      if (!state.knows(pat)) {
        return false;
      }
      return state.tighten(pat, rangeFromOp(op, constant));
    }

    /**
     * Tightens {@code targetPat}'s interval by {@code targetPat OP (otherPat +
     * delta)}, using {@code otherPat}'s current interval.
     */
    private static boolean tightenFromOther(
        State state,
        Core.NamedPat targetPat,
        BuiltIn op,
        Core.NamedPat otherPat,
        BigDecimal delta) {
      if (!state.knows(targetPat)) {
        return false;
      }
      final ImmutableRangeSet<BigDecimal> otherRs = state.get(otherPat);
      if (otherRs.isEmpty()) {
        return false;
      }
      final Range<BigDecimal> otherSpan = otherRs.span();
      final ImmutableRangeSet<BigDecimal> bound =
          rangeFromOther(op, otherSpan, delta);
      if (bound == null) {
        return false;
      }
      return state.tighten(targetPat, bound);
    }

    /**
     * Returns the range that {@code v} must lie in to satisfy {@code v OP c}.
     */
    private static ImmutableRangeSet<BigDecimal> rangeFromOp(
        BuiltIn op, BigDecimal c) {
      switch (op) {
        case OP_LT:
          return ImmutableRangeSet.of(Range.lessThan(c));
        case OP_LE:
          return ImmutableRangeSet.of(Range.atMost(c));
        case OP_GT:
          return ImmutableRangeSet.of(Range.greaterThan(c));
        case OP_GE:
          return ImmutableRangeSet.of(Range.atLeast(c));
        case OP_EQ:
          return ImmutableRangeSet.of(Range.singleton(c));
        default:
          throw new AssertionError(op);
      }
    }

    /**
     * Given {@code v OP (otherPat + delta)} and {@code otherPat}'s span,
     * returns the range that {@code v} must lie in, or {@code null} if no
     * useful bound can be derived (e.g. the relevant side of {@code otherSpan}
     * is unbounded).
     */
    private static @Nullable ImmutableRangeSet<BigDecimal> rangeFromOther(
        BuiltIn op, Range<BigDecimal> otherSpan, BigDecimal delta) {
      switch (op) {
        case OP_LT:
          // v < other + delta; need other's upper.
          if (!otherSpan.hasUpperBound()) {
            return null;
          }
          // Always open: v can approach but never equal other_hi + delta.
          return ImmutableRangeSet.of(
              Range.lessThan(otherSpan.upperEndpoint().add(delta)));
        case OP_LE:
          // v <= other + delta; need other's upper.
          if (!otherSpan.hasUpperBound()) {
            return null;
          }
          return ImmutableRangeSet.of(
              otherSpan.upperBoundType() == BoundType.CLOSED
                  ? Range.atMost(otherSpan.upperEndpoint().add(delta))
                  : Range.lessThan(otherSpan.upperEndpoint().add(delta)));
        case OP_GT:
          if (!otherSpan.hasLowerBound()) {
            return null;
          }
          return ImmutableRangeSet.of(
              Range.greaterThan(otherSpan.lowerEndpoint().add(delta)));
        case OP_GE:
          if (!otherSpan.hasLowerBound()) {
            return null;
          }
          return ImmutableRangeSet.of(
              otherSpan.lowerBoundType() == BoundType.CLOSED
                  ? Range.atLeast(otherSpan.lowerEndpoint().add(delta))
                  : Range.greaterThan(otherSpan.lowerEndpoint().add(delta)));
        case OP_EQ:
          // v = other + delta. Shift other's range by delta.
          if (!otherSpan.hasLowerBound() && !otherSpan.hasUpperBound()) {
            return null;
          }
          final Range<BigDecimal> shifted = shift(otherSpan, delta);
          return ImmutableRangeSet.of(shifted);
        default:
          throw new AssertionError(op);
      }
    }

    /** Translates {@code r} by {@code delta} along the number line. */
    private static Range<BigDecimal> shift(
        Range<BigDecimal> r, BigDecimal delta) {
      if (r.hasLowerBound() && r.hasUpperBound()) {
        return Range.range(
            r.lowerEndpoint().add(delta), r.lowerBoundType(),
            r.upperEndpoint().add(delta), r.upperBoundType());
      }
      if (r.hasLowerBound()) {
        return r.lowerBoundType() == BoundType.CLOSED
            ? Range.atLeast(r.lowerEndpoint().add(delta))
            : Range.greaterThan(r.lowerEndpoint().add(delta));
      }
      if (r.hasUpperBound()) {
        return r.upperBoundType() == BoundType.CLOSED
            ? Range.atMost(r.upperEndpoint().add(delta))
            : Range.lessThan(r.upperEndpoint().add(delta));
      }
      return Range.all();
    }
  }

  /**
   * Propagator for {@code abs(x) OP c} (or {@code c OP abs(x)}) where {@code c}
   * is an integer literal.
   *
   * <p>Handles the connected-interval cases:
   *
   * <ul>
   *   <li>{@code abs(x) < c} -> {@code x} in {@code (-c, c)}
   *   <li>{@code abs(x) <= c} -> {@code x} in {@code [-c, c]}
   *   <li>{@code abs(x) = 0} -> {@code x = 0}
   * </ul>
   *
   * <p>The {@code >}, {@code >=}, and non-zero {@code =} cases produce a
   * disjoint range set ({@code x < -c} OR {@code x > c}, etc.). FBBT can track
   * these via {@code ImmutableRangeSet.union}, but the materializer currently
   * emits per-side conjuncts that assume a contiguous span; until the
   * materializer is taught to emit unions, these cases are skipped (no
   * tightening). The constraint remains in the where as a filter.
   */
  static class AbsPropagator implements Propagator {
    @Override
    public boolean propagate(Core.Exp constraint, State state) {
      if (constraint.op != Op.APPLY) {
        return false;
      }
      final BuiltIn op = constraint.builtIn();
      if (op == null) {
        return false;
      }
      switch (op) {
        case OP_LT:
        case OP_LE:
        case OP_GT:
        case OP_GE:
        case OP_EQ:
          break;
        default:
          return false;
      }
      final Core.Exp lhs = constraint.arg(0);
      final Core.Exp rhs = constraint.arg(1);
      // Normalize so that abs(x) is on the left.
      final Core.NamedPat absArg;
      final BigDecimal constant;
      final BuiltIn normalized;
      final Core.@Nullable NamedPat lhsAbsArg = extractAbsArg(lhs);
      final Core.@Nullable NamedPat rhsAbsArg = extractAbsArg(rhs);
      if (lhsAbsArg != null) {
        final Core.@Nullable Literal lit = Bounds.numericLiteral(rhs);
        if (lit == null) {
          return false;
        }
        absArg = lhsAbsArg;
        constant = lit.unwrap(BigDecimal.class);
        normalized = op;
      } else if (rhsAbsArg != null) {
        final Core.@Nullable Literal lit = Bounds.numericLiteral(lhs);
        if (lit == null) {
          return false;
        }
        absArg = rhsAbsArg;
        constant = lit.unwrap(BigDecimal.class);
        normalized = op.reverse();
      } else {
        return false;
      }
      if (!state.knows(absArg)) {
        return false;
      }
      // Only handle cases that yield a single connected interval. Note
      // that abs(x) < c for c <= 0 is infeasible; we treat as empty range.
      switch (normalized) {
        case OP_LT:
          // abs(x) < c: x in (-c, c). If c <= 0, infeasible.
          if (constant.signum() <= 0) {
            return state.tighten(absArg, ImmutableRangeSet.of());
          }
          return state.tighten(
              absArg,
              ImmutableRangeSet.of(Range.open(constant.negate(), constant)));
        case OP_LE:
          // abs(x) <= c: x in [-c, c]. If c < 0, infeasible.
          if (constant.signum() < 0) {
            return state.tighten(absArg, ImmutableRangeSet.of());
          }
          return state.tighten(
              absArg,
              ImmutableRangeSet.of(Range.closed(constant.negate(), constant)));
        case OP_EQ:
          // abs(x) = 0: x = 0. Otherwise the solution is two disjoint
          // points (x = c or x = -c), which we don't materialize per-side.
          if (constant.signum() == 0) {
            return state.tighten(
                absArg, ImmutableRangeSet.of(Range.singleton(BigDecimal.ZERO)));
          }
          return false;
        case OP_GT:
        case OP_GE:
        default:
          // Disjoint cases — see class comment.
          return false;
      }
    }

    /**
     * If {@code exp} is a call to {@code Int.abs} or {@code Real.abs} with a
     * bare-variable argument, returns that variable; otherwise {@code null}.
     */
    private static Core.@Nullable NamedPat extractAbsArg(Core.Exp exp) {
      if (!(exp instanceof Core.Apply)) {
        return null;
      }
      final Core.Apply apply = (Core.Apply) exp;
      final BuiltIn b = apply.builtIn();
      if (b != BuiltIn.INT_ABS && b != BuiltIn.REAL_ABS) {
        return null;
      }
      // abs is unary; its single argument is at .arg.
      final Core.Exp arg = apply.arg;
      if (!(arg instanceof Core.Id)) {
        return null;
      }
      return ((Core.Id) arg).idPat;
    }
  }

  /**
   * Propagator for {@code A * B OP c} (or {@code c OP A * B}) where {@code A}
   * and {@code B} are each linear in a single variable and {@code c} is an
   * integer literal.
   *
   * <p>Currently handles only the non-negative quadrant: both {@code A} and
   * {@code B} are known to be {@code >= 0} on their current intervals, with at
   * least one side bounded strictly positive so division is well-defined.
   *
   * <ul>
   *   <li>{@code A * B < c} (or {@code <=}): {@code A < c / B.lo} when {@code
   *       B.lo > 0}; symmetric for {@code B}.
   *   <li>{@code A * B > c} (or {@code >=}): {@code A > c / B.hi} when {@code
   *       B.hi > 0}; symmetric for {@code B}.
   * </ul>
   *
   * <p>Mixed-sign and fully-negative quadrants, and exact equality, are
   * deferred. The constraint remains in the where clause as a filter.
   */
  static class MultiplyPropagator implements Propagator {
    @Override
    public boolean propagate(Core.Exp constraint, State state) {
      if (constraint.op != Op.APPLY) {
        return false;
      }
      final BuiltIn op = constraint.builtIn();
      if (op == null) {
        return false;
      }
      switch (op) {
        case OP_LT:
        case OP_LE:
        case OP_GT:
        case OP_GE:
          break;
        default:
          return false;
      }
      // Determine which side is the product and which is the constant.
      final Core.Exp lhs = constraint.arg(0);
      final Core.Exp rhs = constraint.arg(1);
      final Core.Apply product;
      final BigDecimal constant;
      final BuiltIn normalized;
      if (isMultiply(lhs)) {
        product = (Core.Apply) lhs;
        final Core.@Nullable Literal lit = Bounds.numericLiteral(rhs);
        if (lit == null) {
          return false;
        }
        constant = lit.unwrap(BigDecimal.class);
        normalized = op;
      } else if (isMultiply(rhs)) {
        product = (Core.Apply) rhs;
        final Core.@Nullable Literal lit = Bounds.numericLiteral(lhs);
        if (lit == null) {
          return false;
        }
        constant = lit.unwrap(BigDecimal.class);
        normalized = op.reverse();
      } else {
        return false;
      }
      // Decompose the product's two operands as linear-in-single-variable.
      final Bounds.Term a = Bounds.linearTerm(product.arg(0));
      final Bounds.Term b = Bounds.linearTerm(product.arg(1));
      if (a == null || b == null) {
        return false;
      }
      // Each side must reference a distinct variable.
      if (a.var == null || b.var == null) {
        return false;
      }
      if (!state.knows(a.var) || !state.knows(b.var)) {
        return false;
      }
      boolean changed = false;
      changed |= tightenSide(state, a, b, normalized, constant);
      changed |= tightenSide(state, b, a, normalized, constant);
      return changed;
    }

    /**
     * Tightens {@code self.var}'s interval given {@code self * other OP c}. The
     * propagation uses {@code other}'s current interval shifted by its offset.
     */
    private static boolean tightenSide(
        State state,
        Bounds.Term self,
        Bounds.Term other,
        BuiltIn op,
        BigDecimal c) {
      final Range<BigDecimal> otherSpan =
          shiftSpan(state.get(other.var).span(), other.offset);
      // For OP_LT / OP_LE: need other.lo > 0 to divide.
      // For OP_GT / OP_GE: need other.hi > 0.
      switch (op) {
        case OP_LT:
        case OP_LE:
          if (!otherSpan.hasLowerBound()) {
            return false;
          }
          if (otherSpan.lowerEndpoint().signum() <= 0) {
            return false;
          }
          final BigDecimal selfUpper = divide(c, otherSpan.lowerEndpoint());
          // self < c / otherLow. Open because A * B < c is strict, or because
          // otherLow is open (smaller other gives looser self bound).
          // Translate back to self.var (subtract self.offset).
          final BigDecimal varUpper = selfUpper.subtract(self.offset);
          return state.tighten(
              self.var, ImmutableRangeSet.of(Range.lessThan(varUpper)));
        case OP_GT:
        case OP_GE:
          if (!otherSpan.hasUpperBound()) {
            return false;
          }
          if (otherSpan.upperEndpoint().signum() <= 0) {
            return false;
          }
          final BigDecimal selfLower = divide(c, otherSpan.upperEndpoint());
          final BigDecimal varLower = selfLower.subtract(self.offset);
          return state.tighten(
              self.var, ImmutableRangeSet.of(Range.greaterThan(varLower)));
        default:
          return false;
      }
    }

    /**
     * Returns {@code num/den} as a {@link BigDecimal} with enough precision to
     * capture a finite decimal expansion when possible.
     */
    private static BigDecimal divide(BigDecimal num, BigDecimal den) {
      return num.divide(den, 20, RoundingMode.HALF_EVEN);
    }

    /** Translates {@code r} by {@code delta} along the number line. */
    private static Range<BigDecimal> shiftSpan(
        Range<BigDecimal> r, BigDecimal delta) {
      if (delta.signum() == 0) {
        return r;
      }
      if (r.hasLowerBound() && r.hasUpperBound()) {
        return Range.range(
            r.lowerEndpoint().add(delta), r.lowerBoundType(),
            r.upperEndpoint().add(delta), r.upperBoundType());
      }
      if (r.hasLowerBound()) {
        return r.lowerBoundType() == BoundType.CLOSED
            ? Range.atLeast(r.lowerEndpoint().add(delta))
            : Range.greaterThan(r.lowerEndpoint().add(delta));
      }
      if (r.hasUpperBound()) {
        return r.upperBoundType() == BoundType.CLOSED
            ? Range.atMost(r.upperEndpoint().add(delta))
            : Range.lessThan(r.upperEndpoint().add(delta));
      }
      return Range.all();
    }

    private static boolean isMultiply(Core.Exp exp) {
      if (!(exp instanceof Core.Apply)) {
        return false;
      }
      final BuiltIn op = exp.builtIn();
      return op == BuiltIn.Z_TIMES_INT
          || op == BuiltIn.OP_TIMES
          || op == BuiltIn.Z_TIMES_REAL;
    }
  }
}

// End Fbbt.java
