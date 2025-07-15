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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;

/** Implementations of {@link RowSink}. */
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class RowSinks {
  private RowSinks() {}

  /**
   * Creates a {@link Code} that implements a query.
   *
   * @see #first(RowSink)
   */
  public static Code from(Supplier<RowSink> rowSinkFactory) {
    return new FromCode(rowSinkFactory);
  }

  /** Creates a {@link RowSink} that starts all downstream row sinks. */
  public static RowSink first(RowSink rowSink) {
    // Use a Describer to walk over the downstream sinks and their codes, and
    // collect the start actions. The only start action, currently, resets
    // ordinals to -1.
    final List<Runnable> startActions = new ArrayList<>();
    rowSink.describe(
        new CodeVisitor() {
          @Override
          public void addStartAction(Runnable runnable) {
            startActions.add(runnable);
          }
        });
    if (startActions.isEmpty()) {
      // There are no start actions, so there's no need to wrap the in a
      // FirstRowSink.
      return rowSink;
    }
    return new FirstRowSink(rowSink, startActions);
  }

  /** Creates a {@link RowSink} for an {@code except} step. */
  public static RowSink except(
      boolean distinct,
      ImmutableList<Code> codes,
      ImmutableList<String> names,
      RowSink rowSink) {
    return distinct
        ? new ExceptDistinctRowSink(codes, names, rowSink)
        : new ExceptAllRowSink(codes, names, rowSink);
  }

  /** Creates a {@link RowSink} for a {@code group} step. */
  public static RowSink group(
      Code keyCode,
      ImmutableList<Applicable> aggregateCodes,
      ImmutableList<String> inNames,
      ImmutableList<String> keyNames,
      ImmutableList<String> outNames,
      RowSink rowSink) {
    return new GroupRowSink(
        keyCode, aggregateCodes, inNames, keyNames, outNames, rowSink);
  }

  /** Creates a {@link RowSink} for an {@code intersect} step. */
  public static RowSink intersect(
      boolean distinct,
      ImmutableList<Code> codes,
      ImmutableList<String> names,
      RowSink rowSink) {
    return distinct
        ? new IntersectDistinctRowSink(codes, names, rowSink)
        : new IntersectAllRowSink(codes, names, rowSink);
  }

  /** Creates a {@link RowSink} for an {@code order} step. */
  public static RowSink order(
      Code code, Comparator comparator, Core.StepEnv env, RowSink rowSink) {
    ImmutableList<String> names = transformEager(env.bindings, b -> b.id.name);
    return new OrderRowSink(code, comparator, names, rowSink);
  }

  /** Creates a {@link RowSink} for a scan or {@code join} step. */
  public static RowSink scan(
      Op op, Core.Pat pat, Code code, Code conditionCode, RowSink rowSink) {
    return new ScanRowSink(op, pat, code, conditionCode, rowSink);
  }

  /** Creates a {@link RowSink} for a {@code skip} step. */
  public static RowSink skip(Code skipCode, RowSink rowSink) {
    return new SkipRowSink(skipCode, rowSink);
  }

  /** Creates a {@link RowSink} for a {@code take} step. */
  public static RowSink take(Code takeCode, RowSink rowSink) {
    return new TakeRowSink(takeCode, rowSink);
  }

  /** Creates a {@link RowSink} for a {@code union} step. */
  public static RowSink union(
      boolean distinct,
      ImmutableList<Code> codes,
      ImmutableList<String> names,
      RowSink rowSink) {
    return new UnionRowSink(distinct, codes, names, rowSink);
  }

  /** Creates a {@link RowSink} for a {@code where} step. */
  public static RowSink where(Code filterCode, RowSink rowSink) {
    return new WhereRowSink(filterCode, rowSink);
  }

  /** Creates a {@link RowSink} for a non-terminal {@code yield} step. */
  public static RowSink yield(Map<String, Code> yieldCodes, RowSink rowSink) {
    return new YieldRowSink(
        ImmutableList.copyOf(yieldCodes.keySet()),
        ImmutableList.copyOf(yieldCodes.values()),
        rowSink);
  }

  /**
   * Creates a {@link RowSink} to collect the results of a {@code from}
   * expression.
   */
  public static RowSink collect(Code code) {
    return new CollectRowSink(code);
  }

  /** Code that evaluates a query. */
  private static class FromCode implements Code {
    private final Supplier<RowSink> rowSinkFactory;

    FromCode(Supplier<RowSink> rowSinkFactory) {
      this.rowSinkFactory = requireNonNull(rowSinkFactory);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start("from", d -> d.arg("sink", rowSinkFactory.get()));
    }

    @Override
    public Object eval(EvalEnv env) {
      final RowSink rowSink = rowSinkFactory.get();
      rowSink.start(env);
      rowSink.accept(env);
      return rowSink.result(env);
    }
  }

  /** Abstract implementation for row sinks that have one successor. */
  private abstract static class BaseRowSink implements RowSink {
    final RowSink rowSink;

    BaseRowSink(RowSink rowSink) {
      this.rowSink = requireNonNull(rowSink);
    }

    @Override
    public void start(EvalEnv env) {
      rowSink.start(env);
    }

    @Override
    public void accept(EvalEnv env) {
      rowSink.accept(env);
    }

    @Override
    public List<Object> result(EvalEnv env) {
      return rowSink.result(env);
    }
  }

  /** Implementation of {@link RowSink} for a {@code join} step. */
  private static class ScanRowSink extends BaseRowSink {
    final Op op; // inner, left, right, full
    final Core.Pat pat;
    final Code code;
    final Code conditionCode;

    ScanRowSink(
        Op op, Core.Pat pat, Code code, Code conditionCode, RowSink rowSink) {
      super(rowSink);
      checkArgument(op == Op.SCAN);
      this.op = op;
      this.pat = pat;
      this.code = code;
      this.conditionCode = conditionCode;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "join",
          d ->
              d.arg("pat", pat)
                  .arg("exp", code)
                  .argIf(
                      "condition",
                      conditionCode,
                      !isConstantTrue(conditionCode))
                  .arg("sink", rowSink));
    }

    static boolean isConstantTrue(Code code) {
      return code.isConstant() && Objects.equals(code.eval(null), true);
    }

    @Override
    public void accept(EvalEnv env) {
      final MutableEvalEnv mutableEvalEnv = env.bindMutablePat(pat);
      final Iterable<Object> elements = (Iterable<Object>) code.eval(env);
      for (Object element : elements) {
        if (mutableEvalEnv.setOpt(element)) {
          Boolean b = (Boolean) conditionCode.eval(mutableEvalEnv);
          if (b != null && b) {
            rowSink.accept(mutableEvalEnv);
          }
        }
      }
    }
  }

  /** Implementation of {@link RowSink} for a {@code where} step. */
  private static class WhereRowSink extends BaseRowSink {
    final Code filterCode;

    WhereRowSink(Code filterCode, RowSink rowSink) {
      super(rowSink);
      this.filterCode = filterCode;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "where", d -> d.arg("condition", filterCode).arg("sink", rowSink));
    }

    @Override
    public void accept(EvalEnv env) {
      if ((Boolean) filterCode.eval(env)) {
        rowSink.accept(env);
      }
    }
  }

  /** Implementation of {@link RowSink} for a {@code skip} step. */
  private static class SkipRowSink extends BaseRowSink {
    final Code skipCode;
    int skip;

    SkipRowSink(Code skipCode, RowSink rowSink) {
      super(rowSink);
      this.skipCode = skipCode;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "skip", d -> d.arg("count", skipCode).arg("sink", rowSink));
    }

    @Override
    public void start(EvalEnv env) {
      skip = (Integer) skipCode.eval(env);
      super.start(env);
    }

    @Override
    public void accept(EvalEnv env) {
      if (skip > 0) {
        --skip;
      } else {
        rowSink.accept(env);
      }
    }
  }

  /** Implementation of {@link RowSink} for a {@code take} step. */
  private static class TakeRowSink extends BaseRowSink {
    final Code takeCode;
    int take;

    TakeRowSink(Code takeCode, RowSink rowSink) {
      super(rowSink);
      this.takeCode = takeCode;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "take", d -> d.arg("count", takeCode).arg("sink", rowSink));
    }

    @Override
    public void start(EvalEnv env) {
      take = (Integer) takeCode.eval(env);
      super.start(env);
    }

    @Override
    public void accept(EvalEnv env) {
      if (take > 0) {
        --take;
        rowSink.accept(env);
      }
    }
  }

  /**
   * Implementation of {@link RowSink} for an {@code except}, {@code intersect},
   * or {@code union} step.
   */
  private abstract static class SetRowSink extends BaseRowSink {
    static final int[] ZERO = {};

    final Op op;
    final boolean distinct;
    final ImmutableList<Code> codes;
    final ImmutableList<String> names;
    final Map<Object, int[]> map = new HashMap<>();
    final Object[] values;

    SetRowSink(
        Op op,
        boolean distinct,
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        RowSink rowSink) {
      super(rowSink);
      checkArgument(
          op == Op.EXCEPT || op == Op.INTERSECT || op == Op.UNION,
          "invalid op %s",
          op);
      this.op = op;
      this.distinct = distinct;
      this.codes = requireNonNull(codes);
      this.names = requireNonNull(names);
      this.values = new Object[names.size()];
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          op.opName,
          d -> {
            d.arg("distinct", distinct);
            forEachIndexed(codes, (code, i) -> d.arg("arg" + i, code));
            d.arg("sink", rowSink);
          });
    }

    /**
     * Adds the current element to the collection, and returns whether it was
     * added.
     */
    boolean add(EvalEnv env) {
      if (names.size() == 1) {
        Object value = env.getOpt(names.get(0));
        return map.put(value, ZERO) == null;
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        return map.put(ImmutableList.copyOf(values), ZERO) == null;
      }
    }

    /** Removes the current element from the collection. */
    void remove(EvalEnv env) {
      if (names.size() == 1) {
        Object value = env.getOpt(names.get(0));
        map.remove(value);
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        map.remove(ImmutableList.copyOf(values));
      }
    }

    /** Increments the count of the current element in the collection. */
    void inc(EvalEnv env) {
      compute(
          env,
          (k, v) -> {
            if (v == null) {
              return new int[] {1};
            }
            ++v[0];
            return v;
          });
    }

    /**
     * Decrements the count of the current element in the collection, if it is
     * present.
     */
    void dec(EvalEnv env) {
      computeIfPresent(
          env,
          (k, v) -> {
            --v[0];
            return v;
          });
    }

    /** Does something to the count of the current element in the collection. */
    void compute(EvalEnv env, BiFunction<Object, int[], int[]> fn) {
      Object value;
      if (names.size() == 1) {
        value = env.getOpt(names.get(0));
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        value = ImmutableList.copyOf(values);
      }
      map.compute(value, fn);
    }

    /** Does something to the count of the current element in the collection. */
    void computeIfPresent(EvalEnv env, BiFunction<Object, int[], int[]> fn) {
      Object value;
      if (names.size() == 1) {
        value = env.getOpt(names.get(0));
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        value = ImmutableList.copyOf(values);
      }
      map.computeIfPresent(value, fn);
    }

    /** Does something to the count of the current element in the collection. */
    void computeIfAbsent(EvalEnv env, Function<Object, int[]> fn) {
      Object value;
      if (names.size() == 1) {
        value = env.getOpt(names.get(0));
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        value = ImmutableList.copyOf(values);
      }
      map.computeIfAbsent(value, fn);
    }
  }

  /** Implementation of {@link RowSink} for non-distinct {@code except} step. */
  private static class ExceptAllRowSink extends SetRowSink {
    ExceptAllRowSink(
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        RowSink rowSink) {
      super(Op.EXCEPT, false, codes, names, rowSink);
    }

    @Override
    public void accept(EvalEnv env) {
      inc(env);
    }

    @Override
    public List<Object> result(EvalEnv env) {
      final MutableEvalEnv mutableEvalEnv = env.bindMutableArray(names);
      for (Code code : codes) {
        final Iterable<Object> elements = (Iterable<Object>) code.eval(env);
        for (Object element : elements) {
          mutableEvalEnv.set(element);
          dec(mutableEvalEnv);
        }
      }
      // Output any elements remaining in the collection.
      if (!map.isEmpty()) {
        final MutableEvalEnv mutableEvalEnv2 = env.bindMutableList(names);
        map.forEach(
            (k, v) -> {
              int v2 = v[0];
              if (v2 > 0) {
                mutableEvalEnv2.set(k);
                for (int i = 0; i < v2; i++) {
                  // Output the element several times.
                  rowSink.accept(mutableEvalEnv2);
                }
              }
            });
      }
      return rowSink.result(env);
    }
  }

  /** Implementation of {@link RowSink} for a distinct {@code except} step. */
  private static class ExceptDistinctRowSink extends SetRowSink {
    ExceptDistinctRowSink(
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        RowSink rowSink) {
      super(Op.EXCEPT, true, codes, names, rowSink);
    }

    @Override
    public void accept(EvalEnv env) {
      add(env);
    }

    @Override
    public List<Object> result(EvalEnv env) {
      final MutableEvalEnv mutableEvalEnv = env.bindMutableArray(names);
      for (Code code : codes) {
        final Iterable<Object> elements = (Iterable<Object>) code.eval(env);
        for (Object element : elements) {
          mutableEvalEnv.set(element);
          remove(mutableEvalEnv);
        }
      }
      // Output any elements remaining in the collection.
      if (!map.isEmpty()) {
        final MutableEvalEnv mutableEvalEnv2 = env.bindMutableList(names);
        map.keySet()
            .forEach(
                element -> {
                  mutableEvalEnv2.set(element);
                  rowSink.accept(mutableEvalEnv2);
                });
      }
      return rowSink.result(env);
    }
  }

  /**
   * Implementation of {@link RowSink} for a non-distinct {@code intersect}
   * step.
   *
   * <p>The algorithm is as follows:
   *
   * <ol>
   *   <li>Populate the map with (k, 1, 0) for each key k from input 0, and
   *       increment the count each time a key repeats.
   *   <li>Read input 1, and populate the second count field.
   *   <li>If there is another input, pass over the map, replacing each entry
   *       (k, x, y) with (k, min(x, y), 0), and removing all entries (k, x, 0).
   *       Then repeat from step 1.
   *   <li>Output all keys min(x, y) times.
   * </ol>
   */
  private static class IntersectAllRowSink extends SetRowSink {
    IntersectAllRowSink(
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        RowSink rowSink) {
      super(Op.INTERSECT, false, codes, names, rowSink);
    }

    @Override
    public void accept(EvalEnv env) {
      // Initialize each key to 1, and increment the count each time the key
      // repeats.
      compute(
          env,
          (k, v) -> {
            if (v == null) {
              return new int[] {1, 0};
            }
            ++v[0];
            return v;
          });
    }

    @Override
    public List<Object> result(EvalEnv env) {
      final MutableEvalEnv mutableEvalEnv = env.bindMutableArray(names);
      int pass = 0;
      for (Code code : codes) {
        final Iterable<Object> elements = (Iterable<Object>) code.eval(env);
        if (pass++ > 0) {
          // If there was a previous input, remove all keys whose most recent
          // count#1 is 0, set count#0 to the minimum of the two counts, and
          // zero count#1.
          map.entrySet().removeIf(e -> e.getValue()[1] == 0);
          map.values()
              .forEach(
                  v -> {
                    v[0] = Math.min(v[0], v[1]);
                    v[1] = 0;
                  });
        }
        // Increment count#1 of each key from the new input, ignoring keys not
        // present.
        for (Object element : elements) {
          mutableEvalEnv.set(element);
          computeIfPresent(
              mutableEvalEnv,
              (k, v) -> {
                ++v[1];
                return v;
              });
        }
      }
      // Output any elements remaining in the collection.
      if (!map.isEmpty()) {
        final MutableEvalEnv mutableEvalEnv2 = env.bindMutableList(names);
        map.forEach(
            (k, v) -> {
              int v2 = Math.min(v[0], v[1]);
              if (v2 > 0) {
                mutableEvalEnv2.set(k);
                for (int i = 0; i < v2; i++) {
                  // Output the element several times.
                  rowSink.accept(mutableEvalEnv2);
                }
              }
            });
      }
      return rowSink.result(env);
    }
  }

  /**
   * Implementation of {@link RowSink} for a distinct {@code intersect} step.
   *
   * <p>The algorithm is as follows:
   *
   * <ol>
   *   <li>Populate the map with (k, 0) for each key k from input 0;
   *   <li>Read input 1, and for each key increments the count.
   *   <li>If there is another input, first remove each key with count zero, and
   *       sets other keys' count to zero. Then repeat from step 1.
   *   <li>Output all keys that have count greater than 0 from the last pass.
   * </ol>
   */
  private static class IntersectDistinctRowSink extends SetRowSink {
    IntersectDistinctRowSink(
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        RowSink rowSink) {
      super(Op.INTERSECT, true, codes, names, rowSink);
    }

    @Override
    public void accept(EvalEnv env) {
      // Initialize each distinct key to 0.
      computeIfAbsent(env, k -> new int[] {0});
    }

    @Override
    public List<Object> result(EvalEnv env) {
      final MutableEvalEnv mutableEvalEnv = env.bindMutableArray(names);
      int pass = 0;
      for (Code code : codes) {
        final Iterable<Object> elements = (Iterable<Object>) code.eval(env);
        // If there was a previous step, remove all keys with count 0, and
        // zero the counts of the other keys.
        if (pass++ > 0) {
          map.entrySet().removeIf(e -> e.getValue()[0] == 0);
          map.forEach((k, v) -> v[0] = 0);
        }

        // Increment the count of each key; ignore keys not present.
        for (Object element : elements) {
          mutableEvalEnv.set(element);
          computeIfPresent(
              mutableEvalEnv,
              (k, v) -> {
                ++v[0];
                return v;
              });
        }
      }
      // Output any elements remaining in the collection.
      if (!map.isEmpty()) {
        final MutableEvalEnv mutableEvalEnv2 = env.bindMutableList(names);
        // Output keys that have a positive count than 0 from the last pass.
        map.forEach(
            (k, v) -> {
              if (v[0] > 0) {
                mutableEvalEnv2.set(k);
                rowSink.accept(mutableEvalEnv2);
              }
            });
      }
      return rowSink.result(env);
    }
  }

  /** Implementation of {@link RowSink} for a {@code union} step. */
  private static class UnionRowSink extends SetRowSink {
    UnionRowSink(
        boolean distinct,
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        RowSink rowSink) {
      super(Op.UNION, distinct, codes, names, rowSink);
    }

    @Override
    public void accept(EvalEnv env) {
      if (!distinct || add(env)) {
        rowSink.accept(env);
      }
    }

    @Override
    public List<Object> result(EvalEnv env) {
      MutableEvalEnv mutableEvalEnv = env.bindMutableArray(names);
      for (Code code : codes) {
        final Iterable<Object> elements = (Iterable<Object>) code.eval(env);
        for (Object element : elements) {
          mutableEvalEnv.set(element);
          if (!distinct || add(mutableEvalEnv)) {
            rowSink.accept(mutableEvalEnv);
          }
        }
      }
      return rowSink.result(env);
    }
  }

  /** Implementation of {@link RowSink} for a {@code group} step. */
  private static class GroupRowSink extends BaseRowSink {
    final Code keyCode;
    final ImmutableList<String> inNames;
    final ImmutableList<String> keyNames;
    /** group names followed by aggregate names */
    final ImmutableList<String> outNames;

    final ImmutableList<Applicable> aggregateCodes;
    final ListMultimap<Object, Object> map = ArrayListMultimap.create();
    final Object[] values;

    GroupRowSink(
        Code keyCode,
        ImmutableList<Applicable> aggregateCodes,
        ImmutableList<String> inNames,
        ImmutableList<String> keyNames,
        ImmutableList<String> outNames,
        RowSink rowSink) {
      super(rowSink);
      this.keyCode = requireNonNull(keyCode);
      this.aggregateCodes = requireNonNull(aggregateCodes);
      this.inNames = requireNonNull(inNames);
      this.keyNames = requireNonNull(keyNames);
      this.outNames = requireNonNull(outNames);
      this.values = inNames.size() == 1 ? null : new Object[inNames.size()];
      checkArgument(isPrefix(keyNames, outNames));
    }

    static <E> boolean isPrefix(List<E> list0, List<E> list1) {
      return list0.size() <= list1.size()
          && list0.equals(list1.subList(0, list0.size()));
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "group",
          d -> {
            d.arg("key", keyCode);
            aggregateCodes.forEach(a -> d.arg("agg", a));
            d.arg("sink", rowSink);
          });
    }

    @Override
    public void accept(EvalEnv env) {
      if (inNames.size() == 1) {
        map.put(keyCode.eval(env), env.getOpt(inNames.get(0)));
      } else {
        for (int i = 0; i < inNames.size(); i++) {
          values[i] = env.getOpt(inNames.get(i));
        }
        map.put(keyCode.eval(env), values.clone());
      }
    }

    @Override
    public List<Object> result(final EvalEnv env) {
      // Derive env2, the environment for our consumer. It consists of our input
      // environment plus output names.
      EvalEnv env2 = env;
      final MutableEvalEnv[] groupEnvs = new MutableEvalEnv[outNames.size()];
      int i = 0;
      for (String name : outNames) {
        env2 = groupEnvs[i++] = env2.bindMutable(name);
      }

      // Also derive env3, the environment wherein the aggregate functions are
      // evaluated.
      final EvalEnv env3 =
          keyNames.isEmpty() ? env : groupEnvs[keyNames.size() - 1];

      final Map<Object, List<Object>> map2;
      if (map.isEmpty()
          && keyCode instanceof Codes.TupleCode
          && ((Codes.TupleCode) keyCode).codes.isEmpty()) {
        // There are no keys, and there were no input rows.
        map2 = ImmutableMap.of(ImmutableList.of(), ImmutableList.of());
      } else {
        //noinspection UnstableApiUsage
        map2 = Multimaps.asMap(map);
      }
      for (Map.Entry<Object, List<Object>> entry : map2.entrySet()) {
        final List list = (List) entry.getKey();
        for (i = 0; i < list.size(); i++) {
          groupEnvs[i].set(list.get(i));
        }
        final List<Object> rows = entry.getValue(); // rows in this bucket
        for (Applicable aggregateCode : aggregateCodes) {
          groupEnvs[i++].set(aggregateCode.apply(env3, rows));
        }
        rowSink.accept(env2);
      }
      return rowSink.result(env);
    }
  }

  /** Implementation of {@link RowSink} for an {@code order} step. */
  private static class OrderRowSink extends BaseRowSink {
    final Code code;
    final Comparator comparator;
    final ImmutableList<String> names;
    final List<Object> rows = new ArrayList<>();
    final Object[] values;

    OrderRowSink(
        Code code,
        Comparator comparator,
        ImmutableList<String> names,
        RowSink rowSink) {
      super(rowSink);
      this.code = code;
      this.comparator = comparator;
      this.names = names;
      this.values = names.size() == 1 ? null : new Object[names.size()];
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "order", d -> d.arg("code", code).arg("sink", rowSink));
    }

    @Override
    public void accept(EvalEnv env) {
      if (values == null) {
        rows.add(env.getOpt(names.get(0)));
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        rows.add(values.clone());
      }
    }

    @Override
    public List<Object> result(final EvalEnv env) {
      final MutableEvalEnv leftEnv = env.bindMutableArray(names);
      final MutableEvalEnv rightEnv = env.bindMutableArray(names);
      rows.sort(
          (left, right) -> {
            leftEnv.set(left);
            rightEnv.set(right);
            final Object leftVal = code.eval(leftEnv);
            final Object rightVal = code.eval(rightEnv);
            return comparator.compare(leftVal, rightVal);
          });
      for (Object row : rows) {
        leftEnv.set(row);
        rowSink.accept(leftEnv);
      }
      return rowSink.result(env);
    }
  }

  /**
   * Implementation of {@link RowSink} for a {@code yield} step.
   *
   * <p>If this is the last step, use instead a {@link CollectRowSink}. It is
   * more efficient; there is no downstream row sink; and a terminal yield step
   * is allowed to generate expressions that are not records. Non-record
   * expressions (e.g. {@code int} expressions) do not have a name, and
   * therefore the value cannot be passed via the {@link EvalEnv}.
   */
  private static class YieldRowSink extends BaseRowSink {
    final ImmutableList<String> names;
    final ImmutableList<Code> codes;
    final Object[] values;

    YieldRowSink(
        ImmutableList<String> names,
        ImmutableList<Code> codes,
        RowSink rowSink) {
      super(rowSink);
      this.names = names;
      this.codes = codes;
      this.values = names.size() == 1 ? null : new Object[names.size()];
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "yield", d -> d.args("codes", codes).arg("sink", rowSink));
    }

    @Override
    public void accept(EvalEnv env) {
      final MutableEvalEnv env2 = env.bindMutableArray(names);
      if (values == null) {
        final Object value = codes.get(0).eval(env);
        env2.set(value);
      } else {
        for (int i = 0; i < codes.size(); i++) {
          values[i] = codes.get(i).eval(env);
        }
        env2.set(values);
      }
      rowSink.accept(env2);
    }
  }

  /**
   * Implementation of {@link RowSink} that the last step of a {@code from}
   * writes into.
   */
  private static class CollectRowSink implements RowSink {
    final List<Object> list = new ArrayList<>();
    final Code code;

    CollectRowSink(Code code) {
      this.code = requireNonNull(code);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start("collect", d -> d.arg("", code));
    }

    @Override
    public void start(EvalEnv env) {
      list.clear();
    }

    @Override
    public void accept(EvalEnv env) {
      list.add(code.eval(env));
    }

    @Override
    public List<Object> result(EvalEnv env) {
      return list;
    }
  }

  /** First row sink in the chain. */
  private static class FirstRowSink extends BaseRowSink {
    final ImmutableList<Runnable> startActions;

    FirstRowSink(RowSink rowSink, List<Runnable> startActions) {
      super(rowSink);
      this.startActions = ImmutableList.copyOf(startActions);
    }

    @Override
    public Describer describe(Describer describer) {
      return rowSink.describe(describer);
    }

    @Override
    public void start(EvalEnv env) {
      startActions.forEach(Runnable::run);
      rowSink.start(env);
    }
  }
}

// End RowSinks.java
