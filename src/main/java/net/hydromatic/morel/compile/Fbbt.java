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

import static java.util.Objects.requireNonNull;
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
import net.hydromatic.morel.util.Pair;
import org.jspecify.annotations.Nullable;

/**
 * Feasibility-based bound tightening (FBBT).
 *
 * <p>Given the conjunction of conjuncts in a {@code where} clause, FBBT
 * tightens the per-variable feasible interval by propagating each constraint,
 * iterating to a fixed point. Newly deduced bounds are appended to the {@code
 * where} clause as conjuncts; the existing range extractor in {@link
 * Generators} then turns them into finite generators.
 *
 * <p>Current scope: int- or real-valued patterns over (a) linear constraints,
 * for {@code OP} in {@code <, <=, >, >=, =}, with each side a sum of terms
 * {@code c * atom} and a constant, where an atom is a variable or an absolute
 * value {@code abs (e)} whose argument is itself linear; and (b) {@code (a * b)
 * OP c} with non-negative operands. Each constraint is propagated on its own,
 * so what one constraint cannot see, FBBT cannot deduce; combining constraints
 * would be Fourier-Motzkin, which this is not.
 *
 * <p>For real-typed patterns FBBT deduces bounds the same way it does for int,
 * but real extents are uncountable so the downstream "not grounded" check still
 * fires when nothing else makes the pattern finite (e.g. a literal range scan,
 * or a finite source like {@code from e in emps}).
 *
 * <p>Shares the {@link Bounds.LinearForm} decomposition and the {@link
 * Bounds#linearForm}, {@link Bounds#linearTerm}, {@link Bounds#numericLiteral}
 * helpers with {@link Generators} and {@link RangePushdown}.
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
      ImmutableList.of(new SumPropagator(), new MultiplyPropagator());

  /**
   * Scale for the division in {@link SumPropagator}, whose result may not
   * terminate (as 100 / 3 does not).
   */
  private static final int SCALE = 12;

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
      // Track any numeric variable, not only the ones whose bounds we are
      // deducing: a variable that a scan bound, such as 'z' in
      // 'from z in [1, 2, 3], x where x < z', tells us about its neighbours
      // even though it needs no bounds itself.
      if (!isNumeric(pat.type)) {
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
      // First, the bounds that the range extractor can already use, such as
      // 'x > 0'. Re-emitting those would be noise, so they are the baseline.
      for (Core.Exp conjunct : conjuncts) {
        ConstantBounds.applyConstantBound(conjunct, this, true);
      }
      inputs.putAll(intervals);
      // Then the bounds that take arithmetic to see, such as the 'x = 2'
      // implied by 'x + 1 = 3'. They constrain propagation, but the extractor
      // cannot use them as they stand, so if they survive to the end they are
      // worth emitting in a form that it can.
      for (Core.Exp conjunct : conjuncts) {
        ConstantBounds.applyConstantBound(conjunct, this, false);
      }
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
        if (!pats.contains(pat)) {
          // A variable that a scan bound. It needs no bounds of its own.
          continue;
        }
        final ImmutableRangeSet<BigDecimal> finalRs =
            requireNonNull(intervals.get(pat));
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

  /**
   * Returns the span of an interval, or null if the interval is empty.
   *
   * <p>An interval goes empty when the constraints contradict each other, as
   * they do in {@code from x where x > 5 andalso x < 3}. There is nothing more
   * to deduce, and {@link ImmutableRangeSet#span()} would throw.
   */
  private static @Nullable Range<BigDecimal> span(
      ImmutableRangeSet<BigDecimal> rangeSet) {
    return rangeSet.isEmpty() ? null : rangeSet.span();
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
   * Propagator for a linear constraint over any number of atoms, with any
   * coefficients: {@code c1 * a1 + ... + cn * an OP k}.
   *
   * <p>This is FBBT proper. Moving everything to the left gives {@code sum OP
   * 0}. To bound one atom, substitute the extreme values that the others'
   * current intervals allow, and solve. For example, {@code 25q + 10d + 5n + p
   * = 100} with {@code q, d, n, p >= 0} gives {@code 25q <= 100}, that is
   * {@code q <= 4}; and likewise {@code d <= 10}, {@code n <= 20}, {@code p <=
   * 100}.
   *
   * <p>An {@code abs} term is an atom whose interval is {@code [0, inf)}, and
   * so it takes part on the same terms as a variable. In {@code abs (x - 2) +
   * abs (y - 3) < 5} -- the Manhattan distance from a point -- each {@code abs}
   * is bounded by what the other leaves over, and bounding an {@code abs}
   * bounds the variable inside it: {@code ~3 < x < 7} and {@code ~2 < y < 8}.
   *
   * <p>An atom whose siblings are unbounded on the side that matters yields
   * nothing this round; a later round may bound it, once a sibling has a bound.
   * That is why {@link #iterateToFixedPoint} iterates.
   *
   * <p>What this propagator cannot do is combine two constraints, which is what
   * Fourier-Motzkin elimination does. Constraints such as {@code x + y >= 0
   * andalso x - y >= ~3}, where no single constraint bounds a variable, remain
   * ungrounded.
   */
  static class SumPropagator implements Propagator {
    @Override
    public boolean propagate(Core.Exp constraint, State state) {
      if (constraint.op != Op.APPLY) {
        return false;
      }
      final BuiltIn op = constraint.builtIn();
      if (op == null || !ConstantBounds.isComparisonOp(op)) {
        return false;
      }
      final Bounds.@Nullable LinearForm lhs =
          Bounds.linearForm(constraint.arg(0));
      if (lhs == null) {
        return false;
      }
      final Bounds.@Nullable LinearForm rhs =
          Bounds.linearForm(constraint.arg(1));
      if (rhs == null) {
        return false;
      }
      // Rewrite "lhs OP rhs" as "sum OP 0".
      final Bounds.LinearForm sum = lhs.minus(rhs);
      if (sum.coefficients.isEmpty()) {
        // Both sides constant; nothing to deduce.
        return false;
      }
      boolean changed = false;
      for (Map.Entry<Core.Exp, BigDecimal> entry :
          sum.coefficients.entrySet()) {
        changed |= tightenOne(state, sum, entry.getKey(), entry.getValue(), op);
      }
      return changed;
    }

    /**
     * Bounds one atom of {@code sum OP 0}, given the intervals of the others.
     */
    private static boolean tightenOne(
        State state,
        Bounds.LinearForm sum,
        Core.Exp atom,
        BigDecimal coefficient,
        BuiltIn op) {
      if (!canTighten(state, atom)) {
        return false;
      }
      // The rest of the sum, "sum - coefficient * atom", lies in
      // [restMin, restMax]; either may be absent, if some atom is unbounded
      // on that side.
      BigDecimal restMin = sum.constant;
      BigDecimal restMax = sum.constant;
      for (Map.Entry<Core.Exp, BigDecimal> entry :
          sum.coefficients.entrySet()) {
        if (entry.getKey().equals(atom)) {
          continue;
        }
        final @Nullable Range<BigDecimal> span =
            span(interval(state, entry.getKey()));
        if (span == null) {
          return false;
        }
        final BigDecimal c = entry.getValue();
        // A positive coefficient takes its minimum at the atom's lower
        // endpoint, a negative one at its upper endpoint.
        final boolean minAtLower = c.signum() > 0;
        if (restMin != null) {
          restMin =
              endpoint(span, minAtLower) == null
                  ? null
                  : restMin.add(c.multiply(endpoint(span, minAtLower)));
        }
        if (restMax != null) {
          restMax =
              endpoint(span, !minAtLower) == null
                  ? null
                  : restMax.add(c.multiply(endpoint(span, !minAtLower)));
        }
      }

      // "coefficient * atom OP -rest". An upper bound on the atom needs the
      // largest that "-rest" can be, i.e. the smallest rest; and vice versa.
      boolean changed = false;
      switch (op) {
        case OP_LT:
        case OP_LE:
          changed |= bound(state, atom, coefficient, restMin, false, op);
          break;
        case OP_GT:
        case OP_GE:
          changed |= bound(state, atom, coefficient, restMax, true, op);
          break;
        case OP_EQ:
          changed |=
              bound(state, atom, coefficient, restMin, false, BuiltIn.OP_LE);
          changed |=
              bound(state, atom, coefficient, restMax, true, BuiltIn.OP_GE);
          break;
        default:
          break;
      }
      return changed;
    }

    /**
     * Applies one side of a bound: {@code coefficient * atom OP -rest}, where
     * {@code rest} is null if that side is unbounded.
     */
    private static boolean bound(
        State state,
        Core.Exp atom,
        BigDecimal coefficient,
        @Nullable BigDecimal rest,
        boolean lower,
        BuiltIn op) {
      if (rest == null) {
        return false;
      }
      // Dividing by a negative coefficient turns an upper bound into a lower
      // bound, and the other way about.
      final boolean flip = coefficient.signum() < 0;
      final boolean resultLower = flip != lower;
      // Round outwards, so that a bound we cannot represent exactly is
      // weaker than the true one, never stronger. (For an integer variable
      // 'boundConjunct' snaps it back to the tightest integer.)
      final BigDecimal value =
          rest.negate()
              .divide(
                  coefficient,
                  SCALE,
                  resultLower ? RoundingMode.FLOOR : RoundingMode.CEILING);
      final boolean strict = op == BuiltIn.OP_LT || op == BuiltIn.OP_GT;
      final Range<BigDecimal> range;
      if (resultLower) {
        range = strict ? Range.greaterThan(value) : Range.atLeast(value);
      } else {
        range = strict ? Range.lessThan(value) : Range.atMost(value);
      }
      return tighten(state, atom, ImmutableRangeSet.of(range));
    }

    /** Returns one endpoint of {@code span}, or null if it is unbounded. */
    private static @Nullable BigDecimal endpoint(
        Range<BigDecimal> span, boolean lower) {
      if (lower) {
        return span.hasLowerBound() ? span.lowerEndpoint() : null;
      }
      return span.hasUpperBound() ? span.upperEndpoint() : null;
    }

    /** Returns whether we can put an atom's deduced bounds to use. */
    private static boolean canTighten(State state, Core.Exp atom) {
      if (atom.op == Op.ID) {
        return state.knows(((Core.Id) atom).idPat);
      }
      return Bounds.isAbs(atom) && innerVariable(atom, state) != null;
    }

    /** Returns the interval that an atom is known to lie in. */
    private static ImmutableRangeSet<BigDecimal> interval(
        State state, Core.Exp atom) {
      if (atom.op == Op.ID) {
        return state.get(((Core.Id) atom).idPat);
      }
      // An absolute value is never negative. (We do not track how much more
      // than zero it is; the variable inside it is what we are after.)
      return ImmutableRangeSet.of(Range.atLeast(BigDecimal.ZERO));
    }

    /**
     * Tightens an atom to {@code rangeSet}. For a variable that is direct; for
     * {@code abs (e)}, whose upper bound {@code b} says that {@code e} lies in
     * {@code [~b, b]}, it is the variable inside {@code e} that tightens.
     */
    private static boolean tighten(
        State state, Core.Exp atom, ImmutableRangeSet<BigDecimal> rangeSet) {
      if (atom.op == Op.ID) {
        return state.tighten(((Core.Id) atom).idPat, rangeSet);
      }
      final @Nullable Pair<Core.NamedPat, BigDecimal> inner =
          innerVariable(atom, state);
      if (inner == null) {
        return false;
      }
      final Range<BigDecimal> span = rangeSet.span();
      if (!span.hasUpperBound()) {
        return false;
      }
      final BigDecimal b = span.upperEndpoint();
      final boolean strict = span.upperBoundType() == BoundType.OPEN;
      if (b.signum() < 0 || b.signum() == 0 && strict) {
        // 'abs e < 0' cannot be satisfied.
        return state.tighten(inner.left, ImmutableRangeSet.of());
      }
      // 'abs (c * x + k) OP b' is '(~b - k) / c OP x OP (b - k) / c', with
      // the ends swapped if c is negative. Round each end outwards -- the
      // lower down, the upper up -- so that the deduced interval is never
      // tighter than the truth.
      final Bounds.LinearForm form =
          requireNonNull(Bounds.linearForm(Bounds.absArg(atom)));
      final BigDecimal c = inner.right;
      final BigDecimal k = form.constant;
      final BigDecimal end1 = b.negate().subtract(k);
      final BigDecimal end2 = b.subtract(k);
      final BigDecimal lower;
      final BigDecimal upper;
      if (c.signum() > 0) {
        lower = end1.divide(c, SCALE, RoundingMode.FLOOR);
        upper = end2.divide(c, SCALE, RoundingMode.CEILING);
      } else {
        lower = end2.divide(c, SCALE, RoundingMode.FLOOR);
        upper = end1.divide(c, SCALE, RoundingMode.CEILING);
      }
      if (strict && lower.compareTo(upper) >= 0) {
        // The ends have met; no value satisfies the constraint.
        return state.tighten(inner.left, ImmutableRangeSet.of());
      }
      return state.tighten(
          inner.left,
          ImmutableRangeSet.of(
              strict ? Range.open(lower, upper) : Range.closed(lower, upper)));
    }

    /**
     * If {@code atom} is {@code abs (e)} and {@code e} is linear in one
     * variable that {@code state} is deducing bounds for, returns that variable
     * and its coefficient; otherwise null.
     */
    private static @Nullable Pair<Core.NamedPat, BigDecimal> innerVariable(
        Core.Exp atom, State state) {
      final Bounds.@Nullable LinearForm form =
          Bounds.linearForm(Bounds.absArg(atom));
      if (form == null || form.coefficients.size() != 1) {
        return null;
      }
      final Map.Entry<Core.Exp, BigDecimal> entry =
          form.coefficients.entrySet().iterator().next();
      if (entry.getKey().op != Op.ID) {
        // 'abs (abs (x - 2) - 1)', say. One layer is enough.
        return null;
      }
      final Core.NamedPat pat = ((Core.Id) entry.getKey()).idPat;
      return state.knows(pat) ? Pair.of(pat, entry.getValue()) : null;
    }
  }

  /**
   * Reads the bounds that a constraint states outright, such as {@code x <= 3}
   * or {@code x + 1 = 3}.
   *
   * <p>{@link State#captureInputs} uses this to record what the query already
   * says, before propagation begins. It is not a {@link Propagator}: {@link
   * SumPropagator} deduces everything that propagating such a constraint would.
   */
  static class ConstantBounds {

    /**
     * Applies a "var op constant" tightening to {@code state}.
     *
     * <p>If {@code directOnly}, considers only a constraint that the range
     * extractor could itself use, namely one whose variable side has no offset:
     * {@code x <= 3}, but not {@code x + 1 = 3}.
     */
    static boolean applyConstantBound(
        Core.Exp constraint, State state, boolean directOnly) {
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
        if (directOnly && lhs.offset.signum() != 0) {
          return false;
        }
        pat = lhs.var;
        finalOp = op;
        constant = rhs.offset.subtract(lhs.offset);
      } else if (lhs.var == null && rhs.var != null) {
        if (directOnly && rhs.offset.signum() != 0) {
          return false;
        }
        pat = rhs.var;
        finalOp = op.reverse();
        constant = lhs.offset.subtract(rhs.offset);
      } else {
        return false;
      }
      return tightenFromConstant(state, pat, finalOp, constant);
    }

    static boolean isComparisonOp(BuiltIn op) {
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
      return state.tighten(pat, rangeFromOp(op, constant));
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
     *
     * <p>Both {@code self} and {@code other} must have a variable.
     */
    private static boolean tightenSide(
        State state,
        Bounds.Term self,
        Bounds.Term other,
        BuiltIn op,
        BigDecimal c) {
      final Core.NamedPat selfVar = requireNonNull(self.var);
      final @Nullable Range<BigDecimal> otherRange =
          span(state.get(requireNonNull(other.var)));
      if (otherRange == null) {
        return false;
      }
      final Range<BigDecimal> otherSpan = shiftSpan(otherRange, other.offset);
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
              selfVar, ImmutableRangeSet.of(Range.lessThan(varUpper)));
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
              selfVar, ImmutableRangeSet.of(Range.greaterThan(varLower)));
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
      return op == BuiltIn.INT_OP_TIMES
          || op == BuiltIn.OP_TIMES
          || op == BuiltIn.REAL_OP_TIMES;
    }
  }
}

// End Fbbt.java
