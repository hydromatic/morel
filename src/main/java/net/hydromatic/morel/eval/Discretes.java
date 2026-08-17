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

import com.google.common.collect.ImmutableList;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.Keys;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Utilities for creating {@link Discrete} instances.
 *
 * <p>Analogous to {@link Comparators}.
 */
public class Discretes {
  private Discretes() {}

  /** Returns a generic Discrete instance. */
  @SuppressWarnings({"unchecked"})
  static Discrete<Object> dummy() {
    return (Discrete<Object>) (Discrete<?>) UNIT;
  }

  /**
   * Returns a {@link Discrete} domain for the given type, or throws {@link
   * CompileException} if the type is not discrete.
   */
  public static Discrete<Object> discreteFor(
      TypeSystem typeSystem, Type type, Pos pos) {
    final Discrete<Object> discrete = discreteForOrNull(typeSystem, type, pos);
    if (discrete == null) {
      throw new CompileException("not a discrete type: " + type, false, pos);
    }
    return discrete;
  }

  /**
   * Returns a {@link Discrete} domain for the given type, or null if the type
   * is not discrete. Use this where not being discrete is an expected answer
   * rather than an error.
   */
  @SuppressWarnings("unchecked")
  public static @Nullable Discrete<Object> discreteForOrNull(
      TypeSystem typeSystem, Type type, Pos pos) {
    if (type instanceof PrimitiveType) {
      switch ((PrimitiveType) type) {
        case INT:
          return (Discrete<Object>) (Discrete<?>) INT;
        case CHAR:
          return (Discrete<Object>) (Discrete<?>) CHAR;
        case BOOL:
          return (Discrete<Object>) (Discrete<?>) BOOL;
        case UNIT:
          return (Discrete<Object>) (Discrete<?>) UNIT;
        default:
          return null;
      }
    }
    if (type instanceof RecordLikeType) {
      return tupleDiscrete(typeSystem, (RecordLikeType) type, pos);
    }
    if (type instanceof DataType) {
      return dataTypeDiscrete(typeSystem, (DataType) type, pos);
    }
    return null;
  }

  /** Creates a {@link Discrete} for a tuple or record type. */
  private static @Nullable Discrete<Object> tupleDiscrete(
      TypeSystem typeSystem, RecordLikeType type, Pos pos) {
    final ImmutableList.Builder<Discrete<Object>> components =
        ImmutableList.builder();
    for (Type argType : type.argTypes()) {
      final Discrete<Object> component =
          discreteForOrNull(typeSystem, argType, pos);
      if (component == null) {
        // A product is discrete only if every component is. Name the component
        // rather than the whole tuple; it is the one at fault.
        throw new CompileException(
            "not a discrete type: " + argType, false, pos);
      }
      components.add(component);
    }
    final Comparator<Object> cmp =
        Comparators.comparatorFor(typeSystem, type, pos);
    return new TupleDiscrete(cmp, components.build());
  }

  /**
   * Advances or retreats a tuple by one step (lexicographic, rightmost
   * component first).
   *
   * <p>In forward mode: tries to increment the rightmost component; if it is at
   * its maximum, resets it to its minimum and carries into the next component
   * to the left.
   *
   * <p>In backward mode: symmetric, using {@code prev} and {@code maxValue}.
   */
  private static @Nullable Object stepTuple(
      List<?> values, List<Discrete<Object>> components, boolean forward) {
    final int n = components.size();
    for (int i = n - 1; i >= 0; i--) {
      final Object stepped =
          forward
              ? components.get(i).next(values.get(i))
              : components.get(i).prev(values.get(i));
      if (stepped != null) {
        final List<Object> result = new ArrayList<>(values);
        result.set(i, stepped);
        // Reset components to the right (forward) or left (backward) to their
        // extreme.
        for (int j = i + 1; j < n; j++) {
          final Object extreme =
              forward
                  ? components.get(j).minValue()
                  : components.get(j).maxValue();
          if (extreme == null) {
            return null;
          }
          result.set(j, extreme);
        }
        return ImmutableList.copyOf(result);
      }
    }
    return null;
  }

  /**
   * Returns the min or max tuple value (null if any component is unbounded).
   */
  private static @Nullable Object tupleExtreme(
      List<Discrete<Object>> components, boolean min) {
    final List<Object> result = new ArrayList<>();
    for (Discrete<Object> d : components) {
      final Object extreme = min ? d.minValue() : d.maxValue();
      if (extreme == null) {
        return null;
      }
      result.add(extreme);
    }
    return ImmutableList.copyOf(result);
  }

  /** Creates a {@link Discrete} for a DataType. */
  private static @Nullable Discrete<Object> dataTypeDiscrete(
      TypeSystem typeSystem, DataType dt, Pos pos) {
    if (dt.name.equals(BuiltIn.Datatype.DESCENDING.mlName())) {
      final Discrete<Object> inner =
          discreteForOrNull(typeSystem, dt.arg(0), pos);
      if (inner == null) {
        return null;
      }
      final Comparator<Object> cmp =
          Comparators.comparatorFor(typeSystem, dt, pos);
      return new DescendingDiscrete(inner, cmp);
    }

    // General sum type: each constructor is either nullary or wraps a discrete
    // argument type. This handles both pure enums (all nullary) and mixed types
    // like '(order, bool) either' (all unary with discrete argument).
    final ImmutableList.Builder<String> ctorNames = ImmutableList.builder();
    final ImmutableList.Builder<Optional<Discrete<Object>>> ctorDiscretes =
        ImmutableList.builder();
    final Map<String, Type> ctorTypes = dt.typeConstructors(typeSystem);
    for (Map.Entry<String, Type.Key> e : dt.typeConstructors.entrySet()) {
      ctorNames.add(e.getKey());
      if (e.getValue().equals(Keys.dummy())) {
        ctorDiscretes.add(Optional.empty());
      } else {
        final Discrete<Object> argDiscrete =
            discreteForOrNull(typeSystem, ctorTypes.get(e.getKey()), pos);
        if (argDiscrete == null) {
          // A constructor's argument type is not discrete, so neither is the
          // datatype. Name the datatype; its constructors are its own affair.
          throw new CompileException("not a discrete type: " + dt, false, pos);
        }
        ctorDiscretes.add(Optional.of(argDiscrete));
      }
    }
    final ImmutableList<String> names = ctorNames.build();
    if (!names.isEmpty()) {
      final Comparator<Object> cmp =
          Comparators.comparatorFor(typeSystem, dt, pos);
      return new SumDiscrete(cmp, names, ctorDiscretes.build());
    }

    return null;
  }

  private static final Comparator<Object> NATURAL = Comparators::compare;

  /** {@link Discrete} for {@code int}. */
  private static final Discrete<Integer> INT = new IntDiscrete();

  /** {@link Discrete} for {@code char} (ordinals 0–255). */
  private static final Discrete<Character> CHAR = new CharDiscrete();

  /** {@link Discrete} for {@code bool} (false &lt; true). */
  private static final Discrete<Boolean> BOOL = new BoolDiscrete();

  /** {@link Discrete} for {@code unit} (a single value). */
  private static final Discrete<Unit> UNIT = new UnitDiscrete();

  /** {@link Discrete} implementation for {@code int}. */
  private static final class IntDiscrete implements Discrete<Integer> {
    @Override
    public Comparator<Object> comparator() {
      return NATURAL;
    }

    @Override
    public @Nullable Integer next(Integer v) {
      return v == Integer.MAX_VALUE ? null : v + 1;
    }

    @Override
    public @Nullable Integer prev(Integer v) {
      return v == Integer.MIN_VALUE ? null : v - 1;
    }

    @Override
    public Integer minValue() {
      return Integer.MIN_VALUE;
    }

    @Override
    public Integer maxValue() {
      return Integer.MAX_VALUE;
    }

    private static final BigInteger SIZE =
        BigInteger.ONE.shiftLeft(Integer.SIZE);

    @Override
    public BigInteger size() {
      return SIZE;
    }

    @Override
    public BigInteger ordinal(Integer v) {
      return BigInteger.valueOf((long) v - Integer.MIN_VALUE);
    }
  }

  /** {@link Discrete} implementation for {@code char} (ordinals 0–255). */
  private static final class CharDiscrete implements Discrete<Character> {
    @Override
    public Comparator<Object> comparator() {
      return NATURAL;
    }

    @Override
    public @Nullable Character next(Character v) {
      return v == '\u00ff' ? null : (char) (v + 1);
    }

    @Override
    public @Nullable Character prev(Character v) {
      return v == '\u0000' ? null : (char) (v - 1);
    }

    @Override
    public Character minValue() {
      return '\u0000';
    }

    @Override
    public Character maxValue() {
      return '\u00ff';
    }

    private static final BigInteger SIZE = BigInteger.valueOf('\u00ff' + 1);

    @Override
    public BigInteger size() {
      return SIZE;
    }

    @Override
    public BigInteger ordinal(Character v) {
      return BigInteger.valueOf(v);
    }
  }

  /** {@link Discrete} implementation for {@code bool} (false &lt; true). */
  private static final class BoolDiscrete implements Discrete<Boolean> {
    @Override
    public Comparator<Object> comparator() {
      return NATURAL;
    }

    @Override
    public @Nullable Boolean next(Boolean v) {
      return v ? null : Boolean.TRUE;
    }

    @Override
    public @Nullable Boolean prev(Boolean v) {
      return v ? Boolean.FALSE : null;
    }

    @Override
    public Boolean minValue() {
      return Boolean.FALSE;
    }

    @Override
    public Boolean maxValue() {
      return Boolean.TRUE;
    }

    @Override
    public BigInteger size() {
      return BigInteger.valueOf(2);
    }

    @Override
    public BigInteger ordinal(Boolean v) {
      return v ? BigInteger.ONE : BigInteger.ZERO;
    }
  }

  /** {@link Discrete} implementation for {@code unit} (a single value). */
  private static final class UnitDiscrete implements Discrete<Unit> {
    private static final Comparator<Object> CMP = (a, b) -> 0;

    @Override
    public Comparator<Object> comparator() {
      return CMP;
    }

    @Override
    public @Nullable Unit next(Unit v) {
      return null;
    }

    @Override
    public @Nullable Unit prev(Unit v) {
      return null;
    }

    @Override
    public Unit minValue() {
      return Unit.INSTANCE;
    }

    @Override
    public Unit maxValue() {
      return Unit.INSTANCE;
    }

    @Override
    public BigInteger size() {
      return BigInteger.ONE;
    }

    @Override
    public BigInteger ordinal(Unit v) {
      return BigInteger.ZERO;
    }
  }

  /** {@link Discrete} implementation for a tuple or record type. */
  private static final class TupleDiscrete implements Discrete<Object> {
    private final Comparator<Object> cmp;
    private final List<Discrete<Object>> components;
    private final BigInteger size;

    TupleDiscrete(Comparator<Object> cmp, List<Discrete<Object>> components) {
      this.cmp = cmp;
      this.components = components;
      BigInteger size = BigInteger.ONE;
      for (Discrete<Object> component : components) {
        size = size.multiply(component.size());
      }
      this.size = size;
    }

    @Override
    public Comparator<Object> comparator() {
      return cmp;
    }

    @Override
    public @Nullable Object next(Object v) {
      return stepTuple((List<?>) v, components, /* forward= */ true);
    }

    @Override
    public @Nullable Object prev(Object v) {
      return stepTuple((List<?>) v, components, /* forward= */ false);
    }

    @Override
    public @Nullable Object minValue() {
      return tupleExtreme(components, /* min= */ true);
    }

    @Override
    public @Nullable Object maxValue() {
      return tupleExtreme(components, /* min= */ false);
    }

    @Override
    public BigInteger size() {
      return size;
    }

    @Override
    public BigInteger ordinal(Object v) {
      // Mixed radix, most significant component first, as the order is
      // lexicographic.
      final List<?> list = (List<?>) v;
      BigInteger ordinal = BigInteger.ZERO;
      for (int i = 0; i < components.size(); i++) {
        final Discrete<Object> component = components.get(i);
        ordinal =
            ordinal
                .multiply(component.size())
                .add(component.ordinal(list.get(i)));
      }
      return ordinal;
    }
  }

  /**
   * {@link Discrete} implementation for the {@code descending} datatype.
   *
   * <p>Runtime value: {@code ["DESC", innerValue]}.
   */
  private static final class DescendingDiscrete implements Discrete<Object> {
    private final Discrete<Object> inner;
    private final Comparator<Object> cmp;

    DescendingDiscrete(Discrete<Object> inner, Comparator<Object> cmp) {
      this.inner = inner;
      this.cmp = cmp;
    }

    @Override
    public Comparator<Object> comparator() {
      return cmp;
    }

    @Override
    public @Nullable Object next(Object v) {
      // In descending order, the successor is the predecessor in the inner
      // order.
      final Object p = inner.prev(((List<?>) v).get(1));
      return p == null ? null : ImmutableList.of("DESC", p);
    }

    @Override
    public @Nullable Object prev(Object v) {
      final Object n = inner.next(((List<?>) v).get(1));
      return n == null ? null : ImmutableList.of("DESC", n);
    }

    @Override
    public @Nullable Object minValue() {
      final Object max = inner.maxValue();
      return max == null ? null : ImmutableList.of("DESC", max);
    }

    @Override
    public @Nullable Object maxValue() {
      final Object min = inner.minValue();
      return min == null ? null : ImmutableList.of("DESC", min);
    }

    @Override
    public BigInteger size() {
      return inner.size();
    }

    @Override
    public BigInteger ordinal(Object v) {
      // The order is reversed, so position is counted from the far end.
      return inner
          .size()
          .subtract(BigInteger.ONE)
          .subtract(inner.ordinal(((List<?>) v).get(1)));
    }
  }

  /**
   * {@link Discrete} implementation for a sum DataType (all constructors are
   * either nullary or wrap a discrete argument type).
   *
   * <p>Handles both pure enums (e.g., {@code order}) and mixed types (e.g.,
   * {@code (order, bool) either}).
   *
   * <p>Runtime values: nullary constructor {@code C} is {@code ["C"]}; unary
   * constructor {@code C of t} is {@code ["C", tValue]}.
   */
  private static final class SumDiscrete implements Discrete<Object> {
    private final Comparator<Object> cmp;
    private final ImmutableList<String> ctorNames;
    /** Empty Optional means the corresponding constructor is nullary. */
    private final ImmutableList<Optional<Discrete<Object>>> ctorDiscretes;

    SumDiscrete(
        Comparator<Object> cmp,
        ImmutableList<String> ctorNames,
        ImmutableList<Optional<Discrete<Object>>> ctorDiscretes) {
      this.cmp = cmp;
      this.ctorNames = ctorNames;
      this.ctorDiscretes = ctorDiscretes;
    }

    @Override
    public Comparator<Object> comparator() {
      return cmp;
    }

    @Override
    public @Nullable Object next(Object v) {
      final List<?> list = (List<?>) v;
      final String name = (String) list.get(0);
      final int i = ctorNames.indexOf(name);
      final Optional<Discrete<Object>> d = ctorDiscretes.get(i);
      if (d.isPresent()) {
        // Unary constructor: try to advance its argument.
        final Object next = d.get().next(list.get(1));
        if (next != null) {
          return ImmutableList.of(name, next);
        }
      }
      // Move to the first value of the next constructor.
      return firstOf(i + 1);
    }

    @Override
    public @Nullable Object prev(Object v) {
      final List<?> list = (List<?>) v;
      final String name = (String) list.get(0);
      final int i = ctorNames.indexOf(name);
      final Optional<Discrete<Object>> d = ctorDiscretes.get(i);
      if (d.isPresent()) {
        // Unary constructor: try to retreat its argument.
        final Object prev = d.get().prev(list.get(1));
        if (prev != null) {
          return ImmutableList.of(name, prev);
        }
      }
      // Move to the last value of the previous constructor.
      return lastOf(i - 1);
    }

    @Override
    public @Nullable Object minValue() {
      return firstOf(0);
    }

    @Override
    public @Nullable Object maxValue() {
      return lastOf(ctorNames.size() - 1);
    }

    @Override
    public BigInteger size() {
      BigInteger size = BigInteger.ZERO;
      for (Optional<Discrete<Object>> d : ctorDiscretes) {
        size = size.add(sizeOf(d));
      }
      return size;
    }

    @Override
    public BigInteger ordinal(Object v) {
      // Constructors run in declaration order, so a value's position is the
      // number of values under the constructors before its own, plus its
      // position among its own constructor's arguments.
      final List<?> list = (List<?>) v;
      final int i = ctorNames.indexOf((String) list.get(0));
      BigInteger ordinal = BigInteger.ZERO;
      for (int j = 0; j < i; j++) {
        ordinal = ordinal.add(sizeOf(ctorDiscretes.get(j)));
      }
      final Optional<Discrete<Object>> d = ctorDiscretes.get(i);
      return d.isPresent()
          ? ordinal.add(d.get().ordinal(list.get(1)))
          : ordinal;
    }

    /** Returns how many values a constructor has; a nullary one has itself. */
    private static BigInteger sizeOf(Optional<Discrete<Object>> d) {
      return d.isPresent() ? d.get().size() : BigInteger.ONE;
    }

    /** Returns the minimum value starting at constructor index {@code i}. */
    private @Nullable Object firstOf(int i) {
      if (i >= ctorNames.size()) {
        return null;
      }
      final Optional<Discrete<Object>> d = ctorDiscretes.get(i);
      if (!d.isPresent()) {
        return ImmutableList.of(ctorNames.get(i));
      }
      final Object min = d.get().minValue();
      return min != null
          ? ImmutableList.of(ctorNames.get(i), min)
          : firstOf(i + 1);
    }

    /** Returns the maximum value ending at constructor index {@code i}. */
    private @Nullable Object lastOf(int i) {
      if (i < 0) {
        return null;
      }
      final Optional<Discrete<Object>> d = ctorDiscretes.get(i);
      if (!d.isPresent()) {
        return ImmutableList.of(ctorNames.get(i));
      }
      final Object max = d.get().maxValue();
      return max != null
          ? ImmutableList.of(ctorNames.get(i), max)
          : lastOf(i - 1);
    }
  }
}

// End Discretes.java
