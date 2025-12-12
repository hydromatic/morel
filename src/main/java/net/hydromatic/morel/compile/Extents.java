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

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.ast.Core;

/**
 * Generates an expression for the set of values that a variable can take in a
 * program.
 *
 * <p>If {@code i} is a variable of type {@code int} then one approximation is
 * the set of all 2<sup>32</sup> values of the {@code int} data type. (Every
 * data type, primitive data types and those built using sum ({@code datatype})
 * or product (record and tuple), has a finite set of values, but the set is
 * usually too large to iterate over.)
 *
 * <p>There is often a better approximation that can be deduced from the uses of
 * the variable. For example,
 *
 * <pre>{@code
 * let
 *   fun isOdd i = i % 2 = 0
 * in
 *   from e in emps,
 *       i
 *     where isOdd i
 *       andalso i < 100
 *       andalso i = e.deptno
 * end
 * }</pre>
 *
 * <p>we can deduce a better extent for {@code i}, namely
 *
 * <pre>{@code
 * from e in emps
 *   yield e.deptno
 *   where deptno % 2 = 0
 *     andalso deptno < 100
 * }</pre>
 */
public class Extents {
  private Extents() {}

  /** Returns whether an expression is an infinite extent. */
  public static boolean isInfinite(Core.Exp exp) {
    return exp.isExtent() && exp.getRangeExtent().iterable == null;
  }

  /**
   * Intersects a collection of range set maps (maps from prefix to {@link
   * RangeSet}) into one.
   */
  public static <C extends Comparable<C>>
      Map<String, ImmutableRangeSet<C>> intersect(
          List<Map<String, ImmutableRangeSet<C>>> rangeSetMaps) {
    switch (rangeSetMaps.size()) {
      case 0:
        // No filters, therefore the extent allows all values.
        // An empty map expresses this.
        return ImmutableMap.of();

      case 1:
        return rangeSetMaps.get(0);

      default:
        final Multimap<String, ImmutableRangeSet<C>> rangeSetMultimap =
            HashMultimap.create();
        for (Map<String, ImmutableRangeSet<C>> rangeSetMap : rangeSetMaps) {
          rangeSetMap.forEach(rangeSetMultimap::put);
        }
        final ImmutableMap.Builder<String, ImmutableRangeSet<C>> rangeSetMap =
            ImmutableMap.builder();
        rangeSetMultimap
            .asMap()
            .forEach(
                (path, rangeSets) ->
                    rangeSetMap.put(path, intersectRangeSets(rangeSets)));
        return rangeSetMap.build();
    }
  }

  /**
   * Unions a collection of range set maps (maps from prefix to {@link
   * RangeSet}) into one.
   */
  public static <C extends Comparable<C>>
      Map<String, ImmutableRangeSet<C>> union(
          List<Map<String, ImmutableRangeSet<C>>> rangeSetMaps) {
    switch (rangeSetMaps.size()) {
      case 0:
        // No filters, therefore the extent is empty.
        // A map containing an empty RangeSet for path "/" expresses this.
        return ImmutableMap.of("/", ImmutableRangeSet.of());

      case 1:
        return rangeSetMaps.get(0);

      default:
        final Multimap<String, ImmutableRangeSet<C>> rangeSetMultimap =
            HashMultimap.create();
        for (Map<String, ImmutableRangeSet<C>> rangeSetMap : rangeSetMaps) {
          rangeSetMap.forEach(rangeSetMultimap::put);
        }
        final ImmutableMap.Builder<String, ImmutableRangeSet<C>> rangeSetMap =
            ImmutableMap.builder();
        rangeSetMultimap
            .asMap()
            .forEach(
                (path, rangeSets) ->
                    rangeSetMap.put(path, unionRangeSets(rangeSets)));
        return rangeSetMap.build();
    }
  }

  /**
   * Intersects a collection of {@link RangeSet} into one.
   *
   * @see ImmutableRangeSet#intersection(RangeSet)
   */
  private static <C extends Comparable<C>>
      ImmutableRangeSet<C> intersectRangeSets(
          Collection<ImmutableRangeSet<C>> rangeSets) {
    return rangeSets.stream()
        .reduce(
            ImmutableRangeSet.of(Range.all()), ImmutableRangeSet::intersection);
  }

  /**
   * Unions a collection of {@link RangeSet} into one.
   *
   * @see ImmutableRangeSet#union(RangeSet)
   */
  private static <C extends Comparable<C>> ImmutableRangeSet<C> unionRangeSets(
      Collection<ImmutableRangeSet<C>> rangeSets) {
    return rangeSets.stream()
        .reduce(ImmutableRangeSet.of(), ImmutableRangeSet::union);
  }
}

// End Extents.java
