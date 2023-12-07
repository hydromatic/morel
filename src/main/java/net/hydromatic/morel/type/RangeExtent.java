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

import net.hydromatic.morel.ast.Op;

import com.google.common.collect.BoundType;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import org.apache.calcite.runtime.FlatLists;
import org.apache.calcite.runtime.Unit;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A type and a range set. */
@SuppressWarnings("rawtypes")
public class RangeExtent {
  /** Map from path to range set.
   *
   * <p>The path designates the item within the type.
   *
   * <p>For example, consider the type {@code int option list}.
   *
   * <ul>
   * <li>"/" relates to the {@code int option list}
   * <li>"/0/" relates to each {@code int option} within the list
   * <li>"/0/SOME/" relates to each {@code int} within a "SOME" element of the
   *       list
   * <li>"/0/NONE/" relates to each "NONE" element of the list
   * </ul>
   *
   * <p>Using a map {@code ["/O/SOME/" "[0, 3], [6]"]} we can generate
   * {@code SOME 0, SOME 1, SOME 2, SOME 3, SOME 6, NONE}
   */
  public final Map<String, ImmutableRangeSet> rangeSetMap;
  public final Type type;
  public final @Nullable Iterable iterable;

  /** Creates a RangeExtent. */
  @SuppressWarnings("unchecked")
  public RangeExtent(TypeSystem typeSystem, Type type,
      Map<String, ImmutableRangeSet> rangeSetMap) {
    this.rangeSetMap =
        ImmutableMap.copyOf(
            Maps.transformValues(rangeSetMap,
                r -> ImmutableRangeSet.copyOf(r)));
    this.type = type;
    this.iterable = toList(type, typeSystem);
  }

  @Override public String toString() {
    if (isUnbounded()) {
      return type.toString(); // range set is unconstrained; don't print it
    }
    return type + " " + rangeSetMap;
  }

  /** Whether this extent returns all, or an unbounded number of, the values of
   * its type.
   *
   * <p>Examples:
   * "(-inf,+inf)" (true),
   * "(-inf,0]" (x &le; 0),
   * "{(-inf,3),(10,+inf)}" (x &lt; 3 or x &gt; 10) are unbounded;
   * "{}" (false),
   * "{3, 10}" (x in [3, 10]),
   * "(3, 10)" (x &ge; 3 andalso x &le; 10) are bounded. */
  public boolean isUnbounded() {
    return rangeSetMap.isEmpty();
  }

  /** Derives the collection of values in the range, or returns empty if
   * the range is infinite. */
  private <E extends Comparable<E>> Iterable<E> toList(Type type,
      TypeSystem typeSystem) {
    final List<E> list = new ArrayList<>();
    if (populate(typeSystem, type, "/", rangeSetMap, (Consumer<E>) list::add)) {
      return list;
    }
    return null;
  }

  /** Populates a list (or other consumer) with all values of this type. Returns
   * false if this type is not finite and the range is open above or below. */
  @SuppressWarnings("unchecked")
  private <E extends Comparable<E>> boolean populate(TypeSystem typeSystem,
      Type type, String path, Map<String, ImmutableRangeSet> rangeSetMap,
      Consumer<E> consumer) {
    final RangeSet<E> rangeSet = rangeSetMap.get(path);
    final Consumer<E> filteredConsumer;
    if (rangeSet != null) {
      filteredConsumer = e -> {
        if (rangeSet.contains(e)) {
          consumer.accept(e);
        }
      };
    } else {
      filteredConsumer = consumer;
    }
    switch (type.op()) {
    case ID:
      switch ((PrimitiveType) type) {
      case BOOL:
        filteredConsumer.accept((E) Boolean.FALSE);
        filteredConsumer.accept((E) Boolean.TRUE);
        return true;

      case UNIT:
        filteredConsumer.accept((E) Unit.INSTANCE);
        return true;

      case CHAR:
        for (int i = 0; i < 256; i++) {
          filteredConsumer.accept((E) Character.valueOf((char) i));
        }
        return true;

      case INT:
        if (rangeSet != null) {
          for (Range<E> range : rangeSet.asRanges()) {
            if (!range.hasLowerBound() || !range.hasUpperBound()) {
              return false;
            }
            final int lower =
                ((BigDecimal) range.lowerEndpoint()).intValue()
                    + (range.lowerBoundType() == BoundType.OPEN ? 1 : 0);
            final int upper =
                ((BigDecimal) range.upperEndpoint()).intValue()
                - (range.upperBoundType() == BoundType.OPEN ? 1 : 0);
            for (int i = lower; i <= upper; i++) {
              consumer.accept((E) Integer.valueOf(i));
            }
          }
          return true;
        }
        // fall through
      }
      return false;

    case DUMMY_TYPE:
      assert type == DummyType.INSTANCE;
      filteredConsumer.accept((E) Unit.INSTANCE);
      return true;

    case DATA_TYPE:
      final DataType dataType = (DataType) type;
      for (Map.Entry<String, Type> entry
          : dataType.typeConstructors(typeSystem).entrySet()) {
        final String name = entry.getKey();
        final Type type2 = entry.getValue();
        final Consumer<E> consumer2 =
            type2.op() == Op.DUMMY_TYPE
                ? v -> filteredConsumer.accept((E) FlatLists.of(name))
                : v -> filteredConsumer.accept((E) FlatLists.of(name, v));
        if (!populate(typeSystem, type2, path + name + "/", rangeSetMap,
            consumer2)) {
          return false;
        }
      }
      return true;

    case RECORD_TYPE:
    case TUPLE_TYPE:
      final RecordLikeType recordType = (RecordLikeType) type;
      final List<List<E>> listList = new ArrayList<>();
      for (Map.Entry<String, Type> entry
          : recordType.argNameTypes().entrySet()) {
        final String name = entry.getKey();
        final Type type2 = entry.getValue();
        final List<E> list2 = new ArrayList<>();
        final Consumer<E> consumer2 = list2::add;
        if (!populate(typeSystem, type2, path + name + '/', rangeSetMap,
            consumer2)) {
          return false;
        }
        listList.add(list2);
      }
      Lists.cartesianProduct(listList)
          .forEach(list ->
              filteredConsumer.accept((E) FlatLists.ofComparable((List) list)));
      return true;

    default:
      // All other types are not enumerable
      return false;
    }
  }
}

// End RangeExtent.java
