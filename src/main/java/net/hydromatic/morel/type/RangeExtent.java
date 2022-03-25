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
package net.hydromatic.morel.type;

import com.google.common.collect.ContiguousSet;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import org.apache.calcite.runtime.FlatLists;
import org.apache.calcite.util.RangeSets;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;

/** A type and a range set. */
@SuppressWarnings("UnstableApiUsage")
public class RangeExtent {
  public final RangeSet rangeSet;
  public final Type type;
  private final Iterable iterable;

  private static final List<Boolean> BOOLEANS = ImmutableList.of(false, true);

  /** Creates a RangeExtent. */
  public RangeExtent(RangeSet rangeSet, Type type) {
    this.rangeSet = ImmutableRangeSet.copyOf(rangeSet);
    this.type = type;
    this.iterable = toIterable(type, rangeSet);
  }

  @Override public String toString() {
    return type + " " + rangeSet;
  }

  /** Returns the collection of values in the range. */
  @SuppressWarnings("unchecked")
  public Iterable toIterable() {
    return iterable;
  }

  /** Returns the collection of values in the range. */
  @SuppressWarnings("unchecked")
  private Iterable toIterable(Type type, RangeSet<?> rangeSet) {
    final List<Iterable<?>> setList = new ArrayList<>();
    rangeSet.asRanges()
        .forEach(range -> setList.add(toIterable(type, range)));
    return concat(setList);
  }

  /** Returns the collection of values in the range. */
  @SuppressWarnings("unchecked")
  private Iterable toIterable(Type type, Range range) {
    switch (type.op()) {
    case ID:
      final PrimitiveType primitiveType = (PrimitiveType) type;
      switch (primitiveType) {
      case INT:
        return ContiguousSet.create(
            RangeSets.copy(range, BigDecimal::intValue),
            DiscreteDomain.integers());

      case BOOL:
        return Iterables.filter(BOOLEANS, b -> range.contains(b));
      }
      break;

    case TUPLE_TYPE:
      final TupleType tupleType = (TupleType) type;
      // TODO: copy rangeSet, to convert embedded BigDecimal to Integer
      return ContiguousSet.create(range, discreteDomain(tupleType));
    }
    throw new AssertionError("cannot iterate type '" + type + "'");
  }

  private DiscreteDomain<? extends Comparable> discreteDomain(Type type) {
    switch (type.op()) {
    case ID:
      final PrimitiveType primitiveType = (PrimitiveType) type;
      switch (primitiveType) {
      case BOOL:
        return new BooleanDiscreteDomain();

      case INT:
        return DiscreteDomain.integers();
      }
      break;

    case TUPLE_TYPE:
      final List<DiscreteDomain<? extends Comparable>> domains = new ArrayList<>();
      final TupleType tupleType = (TupleType) type;
      tupleType.argTypes.forEach(t -> domains.add(discreteDomain(t)));
      return new ProductDiscreteDomain(domains);
    }

    throw new AssertionError("cannot convert type '" + type
        + "' to discrete domain");
  }

  /** Calls {@link Iterables#concat(Iterable)}, optimizing for the case with 0
   * or 1 entries. */
  private static <E> Iterable<? extends E> concat(
      List<? extends Iterable<? extends E>> iterableList) {
    switch (iterableList.size()) {
    case 0:
      return ImmutableList.of();
    case 1:
      return iterableList.get(0);
    default:
      return Iterables.concat(iterableList);
    }
  }

  private static class BooleanDiscreteDomain extends DiscreteDomain<Boolean> {
    @CheckForNull
    @Override public Boolean next(Boolean value) {
      return value ? null : true;
    }

    @CheckForNull @Override public Boolean previous(Boolean value) {
      return value ? false : null;
    }

    @Override public long distance(Boolean start, Boolean end) {
      return (end == (boolean) start) ? 0 : (end ? 1 : -1);
    }

    @Override public Boolean minValue() {
      return false;
    }

    @Override public Boolean maxValue() {
      return true;
    }
  }

  private static class ProductDiscreteDomain
      extends DiscreteDomain<FlatLists.ComparableList<Comparable>> {
    private final List<DiscreteDomain> domains;
    private final FlatLists.ComparableList<Comparable> minValue;
    private final FlatLists.ComparableList<Comparable> maxValues;

    ProductDiscreteDomain(List<DiscreteDomain<? extends Comparable>> domains) {
      this.domains = ImmutableList.copyOf(domains);
      this.minValue = FlatLists.ofComparable(
          domains.stream()
              .map(DiscreteDomain::minValue)
              .collect(Collectors.toList()));
      this.maxValues = FlatLists.ofComparable(
          domains.stream()
              .map(DiscreteDomain::maxValue)
              .collect(Collectors.toList()));
    }

    @CheckForNull @Override public FlatLists.ComparableList<Comparable> next(
        FlatLists.ComparableList<Comparable> values) {
      final Comparable[] objects = values.toArray(new Comparable[0]);
      for (int i = 0; i < values.size(); i++) {
        Comparable value = values.get(i);
        final DiscreteDomain domain = domains.get(i);
        Comparable next = domain.next(value);
        if (next != null) {
          objects[i] = next;
          return (FlatLists.ComparableList) FlatLists.of(objects);
        }
        objects[i] = domain.minValue();
      }
      return null;
    }

    @CheckForNull @Override public FlatLists.ComparableList<Comparable> previous(
        FlatLists.ComparableList<Comparable> values) {
      throw new UnsupportedOperationException(); // TODO implement, like next
    }

    @Override public long distance(FlatLists.ComparableList<Comparable> start,
        FlatLists.ComparableList<Comparable> end) {
      // A better implementation might be to compute distances between each
      // pair of values, and multiply by the number of superior values.
      long d = 0;
      for (FlatLists.ComparableList<Comparable> c = start;
           c != null && c.compareTo(end) < 0;
           c = next(c)) {
        ++d;
      }
      return d;
    }
  }
}

// End RangeExtent.java
