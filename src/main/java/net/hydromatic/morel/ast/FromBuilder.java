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
package net.hydromatic.morel.ast;

import static com.google.common.base.Preconditions.checkArgument;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.allMatch;
import static net.hydromatic.morel.util.Static.append;
import static net.hydromatic.morel.util.Static.last;
import static net.hydromatic.morel.util.Static.skipLast;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Range;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.RefChecker;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.PairList;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Builds a {@link Core.From}.
 *
 * <p>Simplifies the following patterns:
 *
 * <ul>
 *   <li>Converts "from v in list" to "list" (only works in {@link
 *       #buildSimplify()}, not {@link #build()});
 *   <li>Removes "where true" steps;
 *   <li>Removes empty "order" steps;
 *   <li>Removes trivial {@code yield}, e.g. "from v in list where condition
 *       yield v" becomes "from v in list where condition";
 *   <li>Inlines {@code from} expressions, e.g. "from v in (from w in list)"
 *       becomes "from w in list yield {v = w}".
 * </ul>
 */
public class FromBuilder {
  private final TypeSystem typeSystem;
  private final @Nullable Supplier<Environment> envSupplier;
  private final List<Core.FromStep> steps = new ArrayList<>();
  private final List<Binding> bindings = new ArrayList<>();
  private boolean atom;

  /**
   * If non-negative, flags that particular step should be removed if it is not
   * the last step. (For example, "yield {i = i}", which changes the result
   * shape if the last step but is otherwise a no-op.)
   */
  private int removeIfNotLastIndex = Integer.MIN_VALUE;

  /**
   * If non-negative, flags that particular step should be removed if it is the
   * last step. (For example, we flatten "from p in (from q in list)", to "from
   * q in list yield {p = q}" but we want to remove "yield {p = q}" if it turns
   * out to be the last step.)
   */
  private int removeIfLastIndex = Integer.MIN_VALUE;

  /** Use {@link net.hydromatic.morel.ast.CoreBuilder#fromBuilder}. */
  FromBuilder(
      TypeSystem typeSystem, @Nullable Supplier<Environment> envSupplier) {
    this.typeSystem = typeSystem;
    this.envSupplier = envSupplier;
  }

  /** Resets state as if this {@code FromBuilder} had just been created. */
  public void clear() {
    steps.clear();
    bindings.clear();
    removeIfNotLastIndex = Integer.MIN_VALUE;
    removeIfLastIndex = Integer.MIN_VALUE;
  }

  @Override
  public String toString() {
    return steps.toString();
  }

  /** Returns the environment available after the most recent step. */
  public Core.StepEnv stepEnv() {
    final boolean ordered = steps.isEmpty() || last(steps).env.ordered;
    return Core.StepEnv.of(bindings, atom, ordered);
  }

  private FromBuilder addStep(Core.FromStep step) {
    if (envSupplier != null) {
      // Validate the step. (Not necessary, but helps find bugs.)
      Core.StepEnv previousEnv =
          steps.isEmpty() ? Core.StepEnv.EMPTY : last(steps).env;
      final Environment env = envSupplier.get();
      RefChecker.of(typeSystem, env).visitStep(step, previousEnv);
    }
    if (removeIfNotLastIndex == steps.size() - 1) {
      // A trivial record yield with a single yield, e.g. 'yield {i = i}', has
      // a purpose only if it is the last step. (It forces the return to be a
      // record, e.g. '{i: int}' rather than a scalar 'int'.)
      // We've just about to add a new step, so this is no longer necessary.
      removeIfNotLastIndex = Integer.MIN_VALUE;
      removeIfLastIndex = Integer.MIN_VALUE;
      final Core.FromStep lastStep = last(steps);
      if (lastStep.op == Op.YIELD) {
        final Core.Yield yield = (Core.Yield) lastStep;
        if (yield.exp.op == Op.TUPLE) {
          final Core.Tuple tuple = (Core.Tuple) yield.exp;
          final Core.FromStep previousStep = steps.get(steps.size() - 2);
          final Core.StepEnv previousEnv = previousStep.env;
          if (tuple.args.size() == 1
              && isTrivial(tuple, previousEnv, yield.env)) {
            steps.remove(steps.size() - 1);
          }
        }
      }
    }
    checkArgument(
        step.env.ordered
            == step.isOrdered(
                steps.isEmpty() || step.isOrdered(last(steps).env.ordered)),
        "step [%s] has wrong ordered [%s]",
        step,
        step.env.ordered);
    steps.add(step);
    if (!bindings.equals(step.env.bindings)) {
      bindings.clear();
      bindings.addAll(step.env.bindings);
    }
    atom = step.env.atom;
    return this;
  }

  /** Creates an unbounded scan, "from pat". */
  public FromBuilder scan(Core.Pat pat) {
    final Core.Exp extent =
        core.extent(typeSystem, pat.type, ImmutableRangeSet.of(Range.all()));
    return scan(pat, extent, core.boolLiteral(true));
  }

  /** Creates a bounded scan, "from pat in exp". */
  public FromBuilder scan(Core.Pat pat, Core.Exp exp) {
    return scan(pat, exp, core.boolLiteral(true));
  }

  public FromBuilder scan(Core.Pat pat, Core.Exp exp, Core.Exp condition) {
    if (exp.op == Op.FROM
        && core.boolLiteral(true).equals(condition)
        && isSimplePat(pat, (Core.From) exp)
        && !containsOrdinal(exp)) {
      final Core.From from = (Core.From) exp;
      final Core.FromStep lastStep = last(from.steps);
      final List<Core.FromStep> steps =
          lastStep.op == Op.YIELD ? skipLast(from.steps) : from.steps;

      // This is an atom only if this is the first step.
      // Even if the previous step was empty, e.g.
      // "from () in [()], i in [1,2]" has type "{i:int} list" not "int list".
      final boolean atom1 = this.steps.isEmpty() && lastStep.env.atom;

      final PairList<String, Core.Exp> nameExps = PairList.of();
      boolean uselessIfLast = this.bindings.isEmpty();
      final Core.StepEnv env;
      if (pat instanceof Core.RecordPat) {
        final Core.RecordPat recordPat = (Core.RecordPat) pat;
        this.bindings.forEach(b -> nameExps.add(b.id.name, core.id(b.id)));
        forEach(
            recordPat.type().argNameTypes.keySet(),
            recordPat.args,
            (name, arg) -> nameExps.add(name, core.id((Core.IdPat) arg)));
        env = null;
      } else if (pat instanceof Core.TuplePat) {
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        forEach(
            tuplePat.args,
            lastStep.env.bindings,
            (arg, binding) ->
                nameExps.add(((Core.IdPat) arg).name, core.id(binding.id)));
        env = null;
      } else if (!this.bindings.isEmpty()) {
        // With at least one binding, and one new variable, the output will be
        // a record type.
        final Core.IdPat idPat = (Core.IdPat) pat;
        this.bindings.forEach(b -> nameExps.add(b.id.name, core.id(b.id)));
        lastStep.env.bindings.forEach(
            b -> nameExps.add(idPat.name, core.id(b.id)));
        env = null;
      } else {
        final Core.IdPat idPat = (Core.IdPat) pat;
        if (lastStep instanceof Core.Yield
            && ((Core.Yield) lastStep).exp.op != Op.RECORD) {
          // The last step is a yield scalar, say 'yield x + 1'.
          // Translate it to a yield singleton record, say 'yield {y = x + 1}'
          addAll(steps);
          if (((Core.Yield) lastStep).exp.op == Op.ID
              && this.bindings.size() == 1) {
            // The last step is 'yield e'. Skip it.
            return this;
          }
          nameExps.add(idPat.name, ((Core.Yield) lastStep).exp);
          final Core.StepEnv env2 =
              Core.StepEnv.of(
                  ImmutableList.of(Binding.of(idPat)),
                  true,
                  lastStep.env.ordered);
          return yield_(false, env2, core.record(typeSystem, nameExps), true);
        }
        final Binding binding = lastStep.env.bindings.get(0);
        nameExps.add(idPat.name, core.id(binding.id));

        env =
            lastStep.env.withBindings(append(this.bindings, Binding.of(idPat)));
      }
      addAll(steps);
      return yield_(
          uselessIfLast, env, core.record(typeSystem, nameExps), atom1);
    }
    Compiles.acceptBinding(typeSystem, pat, bindings);
    atom = bindings.size() == 1;
    return addStep(core.scan(stepEnv(), pat, exp, condition));
  }

  /** Returns whether a expression calls {@code ordinal}. */
  private static boolean containsOrdinal(Core.Exp exp) {
    final AtomicBoolean b = new AtomicBoolean();
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Apply apply) {
            if (apply.isCallTo(BuiltIn.Z_ORDINAL)) {
              b.set(true);
            }
            super.visit(apply);
          }
        });
    return b.get();
  }

  private static boolean isSimplePat(Core.Pat pat, Core.From exp) {
    switch (pat.op) {
      case ID_PAT:
        return !exp.steps.isEmpty() && last(exp.steps).env.bindings.size() == 1;
      case RECORD_PAT:
        return allMatch(
            ((Core.RecordPat) pat).args, a -> a instanceof Core.IdPat);
      case TUPLE_PAT:
        return allMatch(
            ((Core.TuplePat) pat).args, a -> a instanceof Core.IdPat);
      default:
        return false;
    }
  }

  public FromBuilder addAll(Iterable<? extends Core.FromStep> steps) {
    final StepHandler stepHandler = new StepHandler();
    steps.forEach(stepHandler::accept);
    return this;
  }

  public FromBuilder where(Core.Exp condition) {
    if (condition.op == Op.BOOL_LITERAL
        && ((Core.Literal) condition).unwrap(Boolean.class)) {
      // skip "where true"
      return this;
    }
    return addStep(core.where(stepEnv(), condition));
  }

  public FromBuilder skip(Core.Exp count) {
    if (count.op == Op.INT_LITERAL
        && ((Core.Literal) count).value.equals(BigDecimal.ZERO)) {
      // skip "skip 0"
      return this;
    }
    return addStep(core.skip(stepEnv(), count));
  }

  public FromBuilder take(Core.Exp count) {
    return addStep(core.take(stepEnv(), count));
  }

  public FromBuilder except(boolean distinct, List<Core.Exp> args) {
    final Core.StepEnv env = stepEnv();
    final Core.StepEnv env2 =
        env.withOrdered(
            env.ordered && allMatch(args, arg -> arg.type instanceof ListType));
    return addStep(core.except(env2, distinct, args));
  }

  public FromBuilder intersect(boolean distinct, List<Core.Exp> args) {
    final Core.StepEnv env = stepEnv();
    final Core.StepEnv env2 =
        env.withOrdered(
            env.ordered && allMatch(args, arg -> arg.type instanceof ListType));
    return addStep(core.intersect(env2, distinct, args));
  }

  public FromBuilder union(boolean distinct, List<Core.Exp> args) {
    final Core.StepEnv env = stepEnv();
    final Core.StepEnv env2 =
        env.withOrdered(
            env.ordered && allMatch(args, arg -> arg.type instanceof ListType));
    return addStep(core.union(env2, distinct, args));
  }

  /** Makes the query unordered. No-op if already unordered. */
  public FromBuilder unorder() {
    final Core.StepEnv env = stepEnv();
    if (!env.ordered) {
      return this;
    }
    return addStep(core.unorder(env));
  }

  public FromBuilder distinct() {
    final ImmutableSortedMap.Builder<Core.IdPat, Core.Exp> groupExpsB =
        ImmutableSortedMap.naturalOrder();
    bindings.forEach(b -> groupExpsB.put((Core.IdPat) b.id, core.id(b.id)));
    return group(stepEnv().atom, groupExpsB.build(), ImmutableSortedMap.of());
  }

  public FromBuilder group(
      boolean atom,
      SortedMap<Core.IdPat, Core.Exp> groupExps,
      SortedMap<Core.IdPat, Core.Aggregate> aggregates) {
    final Core.StepEnv env = stepEnv();
    return addStep(core.group(atom, env.ordered, groupExps, aggregates));
  }

  public FromBuilder order(Core.Exp exp) {
    return addStep(core.order(stepEnv(), exp));
  }

  public FromBuilder yield_(Core.Exp exp) {
    boolean atom = exp.op != Op.TUPLE || exp.type.op() != Op.RECORD_TYPE;
    return yield_(false, exp, atom);
  }

  public FromBuilder yield_(boolean uselessIfLast, Core.Exp exp, boolean atom) {
    return yield_(uselessIfLast, null, exp, atom);
  }

  /**
   * Creates a "yield" step.
   *
   * <p>When copying, the {@code env2} parameter is the {@link
   * net.hydromatic.morel.ast.Core.Yield#env} value of the current Yield, so
   * that we don't generate new variables (with different ordinals). Later steps
   * are relying on the variables remaining the same. For example, in
   *
   * <pre>{@code
   * from ... yield {a = b} where a > 5
   * }</pre>
   *
   * <p>the {@code a} in {@code a > 5} references {@code IdPat('a', 0)} and we
   * don't want yield to generate an {@code IdPat('a', 1)}.
   *
   * @param uselessIfLast Whether this Yield will be useless if it is the last
   *     step. The expression {@code {x = y} } is an example of this
   * @param env2 Desired step environment, or null
   * @param exp Expression to yield
   * @param atom Whether the expression is an atom (as opposed to a record); all
   *     atoms have just one binding, but records may also have one binding
   * @return This FromBuilder, with a Yield added to the list of steps
   */
  public FromBuilder yield_(
      boolean uselessIfLast,
      Core.@Nullable StepEnv env2,
      Core.Exp exp,
      boolean atom) {
    checkArgument(env2 == null || env2.atom == atom);
    final Core.StepEnv env = stepEnv();
    boolean uselessIfNotLast = false;
    switch (exp.op) {
      case TUPLE:
        final TupleType tupleType = tupleType((Core.Tuple) exp, env, env2);
        switch (tupleType) {
          case IDENTITY:
            // A trivial record does not rename, so its only purpose is to
            // change from a scalar to a record, and even then only when a
            // singleton.
            if (bindings.size() == 1) {
              // Singleton record that does not rename, e.g. 'yield {x=x}'.
              // It only has meaning as the last step.
              if (env2 == null) {
                env2 = Core.StepEnv.of(bindings, false, env.ordered);
              }
              uselessIfNotLast = true;
              break;
            } else {
              // Non-singleton record that does not rename,
              // e.g. 'yield {x=x,y=y}'. It is useless.
              return this;
            }
          case RENAME:
            if (bindings.size() == 1) {
              // Singleton record that renames, e.g. 'yield {y=x}'.
              // It is always useful.
              break;
            } else {
              // Non-singleton record that renames, e.g. 'yield {y=x,z=y}'.
              // It is always useful.
              break;
            }
        }
        break;

      case ID:
        if (bindings.size() == 1
            && ((Core.Id) exp).idPat.equals(bindings.get(0).id)
            // After 'yield {x = something}', 'yield x' may seem trivial, but
            // it converts a singleton record to an atom, so don't remove it.
            && (steps.isEmpty() || last(steps).env.atom)) {
          return this;
        }
    }
    Core.Yield step =
        env2 != null
            ? core.yield_(env2.withOrdered(env.ordered), exp)
            : core.yield_(typeSystem, exp, atom, env.ordered);
    addStep(step);
    removeIfNotLastIndex =
        uselessIfNotLast ? steps.size() - 1 : Integer.MIN_VALUE;
    removeIfLastIndex = uselessIfLast ? steps.size() - 1 : Integer.MIN_VALUE;
    return this;
  }

  /** Returns whether tuple is something like "{i = i, j = j}". */
  private static boolean isTrivial(
      Core.Tuple tuple, Core.StepEnv env, Core.@Nullable StepEnv env2) {
    return tupleType(tuple, env, env2) == TupleType.IDENTITY;
  }

  /** Returns whether tuple is something like "{i = i, j = j}". */
  private static TupleType tupleType(
      Core.Tuple tuple, Core.StepEnv env, Core.@Nullable StepEnv env2) {
    if (tuple.args.size() != env.bindings.size()) {
      return TupleType.OTHER;
    }
    boolean identity = env2 == null || env.bindings.equals(env2.bindings);
    for (Pair<Core.Exp, String> argName :
        Pair.zip(tuple.args, tuple.type().argNames())) {
      Core.Exp arg = argName.left;
      String name = argName.right;
      if (arg.op != Op.ID) {
        return TupleType.OTHER;
      }
      if (!((Core.Id) arg).idPat.name.equals(name)) {
        identity = false;
      }
    }
    return identity ? TupleType.IDENTITY : TupleType.RENAME;
  }

  private Core.Exp build(boolean simplify) {
    if (removeIfLastIndex == steps.size() - 1) {
      removeIfLastIndex = Integer.MIN_VALUE;
      final Core.Yield yield = (Core.Yield) last(steps);
      if (yield.exp.op != Op.TUPLE
          || ((Core.Tuple) yield.exp).args.size() != 1) {
        throw new AssertionError(yield.exp);
      }
      steps.remove(steps.size() - 1);
    }
    if (simplify && steps.size() == 1 && steps.get(0).op == Op.SCAN) {
      final Core.Scan scan = (Core.Scan) steps.get(0);
      if (scan.pat.op == Op.ID_PAT) {
        return scan.exp;
      }
    }
    return core.from(typeSystem, steps);
  }

  public Core.From build() {
    return (Core.From) build(false);
  }

  /** As {@link #build}, but also simplifies "from x in list" to "list". */
  public Core.Exp buildSimplify() {
    return build(true);
  }

  /** Calls the method to re-register a step. */
  private class StepHandler extends Visitor {
    @Override
    protected void visit(Core.Except except) {
      except(except.distinct, except.args);
    }

    @Override
    protected void visit(Core.Group group) {
      group(group.env.atom, group.groupExps, group.aggregates);
    }

    @Override
    protected void visit(Core.Intersect intersect) {
      intersect(intersect.distinct, intersect.args);
    }

    @Override
    protected void visit(Core.Order order) {
      order(order.exp);
    }

    @Override
    protected void visit(Core.Scan scan) {
      scan(scan.pat, scan.exp, scan.condition);
    }

    @Override
    protected void visit(Core.Where where) {
      where(where.exp);
    }

    @Override
    protected void visit(Core.Skip skip) {
      skip(skip.exp);
    }

    @Override
    protected void visit(Core.Take take) {
      take(take.exp);
    }

    @Override
    protected void visit(Core.Union union) {
      union(union.distinct, union.args);
    }

    @Override
    protected void visit(Core.Unorder unorder) {
      unorder();
    }

    @Override
    protected void visit(Core.Yield yield) {
      yield_(false, yield.env, yield.exp, yield.env.atom);
    }
  }

  /** Category of expression passed to "yield". */
  private enum TupleType {
    /**
     * Tuple whose right side are the current fields, e.g. "{a = deptno, b =
     * dname}".
     */
    RENAME,
    /**
     * Tuple whose right side are the current fields and left side are the same
     * as the right, e.g. "{deptno = deptno, dname = dname}".
     */
    IDENTITY,
    /**
     * Any other tuple, e.g. "{a = deptno + 1, dname = dname}", "{deptno =
     * deptno}" (too few fields).
     */
    OTHER
  }
}

// End FromBuilder.java
