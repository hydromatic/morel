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

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.util.Pair.allMatch;
import static net.hydromatic.morel.util.Static.skip;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.hydromatic.morel.ast.Core;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Helpers for {@link EvalEnv}. */
public class EvalEnvs {
  private static final EvalEnv EMPTY = copyOf(ImmutableMap.of());

  private EvalEnvs() {}

  /** Returns an empty evaluation environment. */
  public static EvalEnv empty() {
    return EMPTY;
  }

  /** Creates an evaluation environment with the given (name, value) map. */
  public static EvalEnv copyOf(Map<String, Object> valueMap) {
    return new MapEvalEnv(valueMap);
  }

  /**
   * Evaluation environment that inherits from a parent environment and adds one
   * binding.
   */
  static class SubEvalEnv implements EvalEnv {
    protected final EvalEnv parentEnv;
    protected final String name;
    protected Object value;

    SubEvalEnv(EvalEnv parentEnv, String name, Object value) {
      this.parentEnv = parentEnv;
      this.name = name;
      this.value = value;
    }

    public void visit(BiConsumer<String, Object> consumer) {
      consumer.accept(name, value);
      parentEnv.visit(consumer);
    }

    public @Nullable Object getOpt(String name) {
      for (SubEvalEnv e = this; ; ) {
        if (name.equals(e.name)) {
          return e.value;
        }
        if (e.parentEnv instanceof SubEvalEnv) {
          e = (SubEvalEnv) e.parentEnv;
        } else {
          return e.parentEnv.getOpt(name);
        }
      }
    }
  }

  /** Similar to {@link SubEvalEnv} but mutable. */
  static class MutableSubEvalEnv extends SubEvalEnv implements MutableEvalEnv {
    MutableSubEvalEnv(EvalEnv parentEnv, String name) {
      super(parentEnv, name, null);
    }

    public void set(Object value) {
      this.value = value;
    }

    @Override
    public EvalEnv fix() {
      return new SubEvalEnv(parentEnv, name, value);
    }
  }

  /** Similar to {@link MutableEvalEnv} but binds several names. */
  static class ArraySubEvalEnv implements EvalEnv {
    protected final EvalEnv parentEnv;
    protected final ImmutableList<String> names;
    protected @Nullable Object[] values;

    ArraySubEvalEnv(
        EvalEnv parentEnv,
        ImmutableList<String> names,
        @Nullable Object[] values) {
      this.parentEnv = requireNonNull(parentEnv);
      this.names = requireNonNull(names);
      this.values = values; // may be null
    }

    public void visit(BiConsumer<String, Object> consumer) {
      for (int i = 0; i < names.size(); i++) {
        consumer.accept(names.get(i), values[i]);
      }
      parentEnv.visit(consumer);
    }

    public @Nullable Object getOpt(String name) {
      final int i = names.indexOf(name);
      if (i >= 0) {
        return values[i];
      }
      return parentEnv.getOpt(name);
    }
  }

  /**
   * Similar to {@link MutableEvalEnv} but binds several names; extends {@link
   * ArraySubEvalEnv} adding mutability.
   */
  static class MutableArraySubEvalEnv extends ArraySubEvalEnv
      implements MutableEvalEnv {
    MutableArraySubEvalEnv(EvalEnv parentEnv, List<String> names) {
      super(parentEnv, ImmutableList.copyOf(names), null);
    }

    public void set(Object value) {
      values = (Object[]) value;
      assert values.length == names.size();
    }

    @Override
    public ArraySubEvalEnv fix() {
      return new ArraySubEvalEnv(parentEnv, names, values.clone());
    }
  }

  /** Immutable copy of {@link MutablePatSubEvalEnv}. */
  static class PatSubEvalEnv extends ArraySubEvalEnv {
    protected final Core.Pat pat;

    PatSubEvalEnv(
        EvalEnv parentEnv,
        Core.Pat pat,
        ImmutableList<String> names,
        Object[] values) {
      super(parentEnv, ImmutableList.copyOf(names), values);
      this.pat = requireNonNull(pat);
      assert !(pat instanceof Core.IdPat);
    }
  }

  /** Evaluation environment that binds several slots based on a pattern. */
  static class MutablePatSubEvalEnv extends PatSubEvalEnv
      implements MutableEvalEnv {
    private int slot;

    MutablePatSubEvalEnv(EvalEnv parentEnv, Core.Pat pat, List<String> names) {
      super(
          parentEnv,
          pat,
          ImmutableList.copyOf(names),
          new Object[names.size()]);
    }

    @Override
    public EvalEnv fix() {
      return new PatSubEvalEnv(parentEnv, pat, names, values.clone());
    }

    @Override
    public void set(Object value) {
      if (!setOpt(value)) {
        // If this error happens, perhaps your code should be calling "setOpt"
        // and handling a false result appropriately.
        throw new AssertionError("bind failed");
      }
    }

    @Override
    public boolean setOpt(Object value) {
      slot = 0;
      return bindRecurse(pat, value);
    }

    boolean bindRecurse(Core.Pat pat, Object argValue) {
      final List<Object> listValue;
      final Core.LiteralPat literalPat;
      switch (pat.op) {
        case ID_PAT:
          this.values[slot++] = argValue;
          return true;

        case AS_PAT:
          final Core.AsPat asPat = (Core.AsPat) pat;
          final int oldSlot = slot++;
          if (bindRecurse(asPat.pat, argValue)) {
            this.values[oldSlot] = argValue;
            return true;
          } else {
            return false;
          }

        case WILDCARD_PAT:
          return true;

        case BOOL_LITERAL_PAT:
        case CHAR_LITERAL_PAT:
        case STRING_LITERAL_PAT:
          literalPat = (Core.LiteralPat) pat;
          return literalPat.value.equals(argValue);

        case INT_LITERAL_PAT:
          literalPat = (Core.LiteralPat) pat;
          return ((BigDecimal) literalPat.value).intValue()
              == (Integer) argValue;

        case REAL_LITERAL_PAT:
          literalPat = (Core.LiteralPat) pat;
          return ((BigDecimal) literalPat.value).doubleValue()
              == (Double) argValue;

        case TUPLE_PAT:
          final Core.TuplePat tuplePat = (Core.TuplePat) pat;
          listValue = (List) argValue;
          return allMatch(tuplePat.args, listValue, this::bindRecurse);

        case RECORD_PAT:
          final Core.RecordPat recordPat = (Core.RecordPat) pat;
          listValue = (List) argValue;
          return allMatch(recordPat.args, listValue, this::bindRecurse);

        case LIST_PAT:
          final Core.ListPat listPat = (Core.ListPat) pat;
          listValue = (List) argValue;
          return listValue.size() == listPat.args.size()
              && allMatch(listPat.args, listValue, this::bindRecurse);

        case CONS_PAT:
          final Core.ConPat infixPat = (Core.ConPat) pat;
          @SuppressWarnings("unchecked")
          final List<Object> consValue = (List) argValue;
          if (consValue.isEmpty()) {
            return false;
          }
          final Object head = consValue.get(0);
          final List<Object> tail = skip(consValue);
          List<Core.Pat> patArgs = ((Core.TuplePat) infixPat.pat).args;
          return bindRecurse(patArgs.get(0), head)
              && bindRecurse(patArgs.get(1), tail);

        case CON0_PAT:
          final Core.Con0Pat con0Pat = (Core.Con0Pat) pat;
          // Handle instances for "variant" datatype.
          if (argValue instanceof Variant) {
            final Variant value = (Variant) argValue;
            return value.constructor().constructor.equals(con0Pat.tyCon);
          } else {
            @SuppressWarnings("unchecked")
            final List<Object> list = (List<Object>) argValue;
            return list.get(0).equals(con0Pat.tyCon);
          }

        case CON_PAT:
          final Core.ConPat conPat = (Core.ConPat) pat;
          // Handle instances for "variant" datatype.
          if (argValue instanceof Variant) {
            final Variant value = (Variant) argValue;
            // Extract constructor name and payload.
            if (!value.constructor().constructor.equals(conPat.tyCon)) {
              return false;
            }
            // Extract payload without converting to List representation.
            final Object payload = value.value;
            return bindRecurse(conPat.pat, payload);
          } else {
            @SuppressWarnings("unchecked")
            final List<Object> list = (List<Object>) argValue;
            return list.get(0).equals(conPat.tyCon)
                && bindRecurse(conPat.pat, list.get(1));
          }

        default:
          throw new AssertionError("cannot compile " + pat.op + ": " + pat);
      }
    }
  }

  /** Similar to {@link MutableEvalEnv} but binds several names. */
  static class ListSubEvalEnv implements EvalEnv {
    protected final EvalEnv parentEnv;
    protected final ImmutableList<String> names;
    protected List<?> values;

    ListSubEvalEnv(
        EvalEnv parentEnv,
        ImmutableList<String> names,
        @Nullable List<?> values) {
      this.parentEnv = requireNonNull(parentEnv);
      this.names = requireNonNull(names);
      this.values = values; // may be null
    }

    public void visit(BiConsumer<String, Object> consumer) {
      for (int i = 0; i < names.size(); i++) {
        consumer.accept(names.get(i), values.get(i));
      }
      parentEnv.visit(consumer);
    }

    public @Nullable Object getOpt(String name) {
      final int i = names.indexOf(name);
      if (i >= 0) {
        return values.get(i);
      }
      return parentEnv.getOpt(name);
    }
  }

  /**
   * Similar to {@link MutableEvalEnv} but binds several names; extends {@link
   * ListSubEvalEnv} adding mutability.
   */
  static class MutableListSubEvalEnv extends ListSubEvalEnv
      implements MutableEvalEnv {
    MutableListSubEvalEnv(EvalEnv parentEnv, List<String> names) {
      super(parentEnv, ImmutableList.copyOf(names), null);
    }

    public void set(Object value) {
      values = (List<?>) value;
      assert values.size() == names.size();
    }

    @Override
    public ListSubEvalEnv fix() {
      throw new UnsupportedOperationException("fix");
    }
  }

  /** Evaluation environment that reads from a map. */
  static class MapEvalEnv implements EvalEnv {
    final Map<String, Object> valueMap;

    MapEvalEnv(Map<String, Object> valueMap) {
      this.valueMap = ImmutableMap.copyOf(valueMap);
    }

    public @Nullable Object getOpt(String name) {
      return valueMap.get(name);
    }

    public void visit(BiConsumer<String, Object> consumer) {
      valueMap.forEach(consumer);
    }
  }
}

// End EvalEnvs.java
