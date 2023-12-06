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
package net.hydromatic.morel.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import org.apache.calcite.util.Util;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.RandomAccess;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collector;

/**
 * Utilities.
 */
public class Static {
  private Static() {
  }

  /** Whether to skip built-in functions.
   *
   * <p>To skip built-in functions, add "-DskipMorelBuiltIns" java's
   * command-line arguments. */
  public static final boolean SKIP =
      getBooleanProperty("skipMorelBuiltIns", false);

  /** Returns the value of a system property, converted into a boolean value.
   *
   * <p>Values "", "true", "TRUE" and "1" are treated as true;
   * "false", "FALSE" and "0" treated as false;
   * for {@code null} and other values, returns {@code defaultVal}.
   */
  @SuppressWarnings("SimplifiableConditionalExpression")
  private static boolean getBooleanProperty(String prop, boolean defaultVal) {
    final String value = System.getProperty(prop);
    if (value == null) {
      return defaultVal;
    }
    final String low = value.toLowerCase(Locale.ROOT);
    return low.equals("true") || low.equals("1") || low.isEmpty() ? true
        : low.equals("false") || low.equals("0") ? false
        : defaultVal;
  }

  /**
   * Returns a {@code Collector} that accumulates the input elements into a
   * Guava {@link ImmutableList} via a {@link ImmutableList.Builder}.
   *
   * <p>It will be obsolete when we move to Guava 21.0,
   * which has {@code ImmutableList.toImmutableList()}.
   *
   * @param <T> Type of the input elements
   *
   * @return a {@code Collector} that collects all the input elements into an
   * {@link ImmutableList}, in encounter order
   */
  public static <T> Collector<T, ImmutableList.Builder<T>, ImmutableList<T>>
      toImmutableList() {
    return Collector.of(ImmutableList::builder, ImmutableList.Builder::add,
        (t, u) -> {
          t.addAll(u.build());
          return t;
        },
        ImmutableList.Builder::build);
  }

  /** Returns whether an {@link Iterable} has fewer than {@code n} elements.
   *
   * @param <E> Element type
   */
  public static <E> boolean shorterThan(Iterable<E> iterable, int n) {
    if (iterable instanceof Collection) {
      return ((Collection<E>) iterable).size() < n;
    }
    if (n <= 0) {
      return false;
    }
    int i = 0;
    for (Iterator<E> iterator = iterable.iterator(); iterator.hasNext();
         iterator.next()) {
      if (++i == n) {
        return false;
      }
    }
    return true;
  }

  /** Returns all but the first element of a list. */
  public static <E> List<E> skip(List<E> list) {
    return skip(list, 1);
  }

  /** Returns all but the first {@code count} elements of a list. */
  public static <E> List<E> skip(List<E> list, int count) {
    return list.subList(count, list.size());
  }

  /** Returns a list with one element appended.
   *
   * @see ConsList */
  public static <E> List<E> append(List<E> list, E e) {
    return ImmutableList.<E>builder().addAll(list).add(e).build();
  }

  /** Prepends an element to a list. */
  public static <E> List<E> plus(E e, List<E> list) {
    return ConsList.of(e, list);
  }

  /** Removes all occurrences of an element from a list. */
  public static <E> List<E> minus(List<E> list, E e) {
    final ImmutableList.Builder<E> builder = ImmutableList.builder();
    list.forEach(e2 -> {
      if (!e2.equals(e)) {
        builder.add(e2);
      }
    });
    return builder.build();
  }

  /** Adds an element to a map. */
  public static <K, V> Map<K, V> plus(Map<K, V> map, K k, V v) {
    return ImmutableMap.<K, V>builder()
        .putAll(map)
        .put(k, v)
        .build();
  }

  /** Adds an element to a sorted map. */
  public static <K extends Comparable<K>, V> SortedMap<K, V> plus(
      SortedMap<K, V> map, K k, V v) {
    return new ImmutableSortedMap.Builder<K, V>(map.comparator())
        .putAll(map)
        .put(k, v)
        .build();
  }

  /** Next power of two. */
  public static int nextPowerOfTwo(int n) {
    final int p = Integer.numberOfLeadingZeros(n);
    return 1 << (Integer.SIZE - p);
  }

  /** Lazily transforms a list, applying a mapping function to each element. */
  public static <E, T> List<T> transform(List<? extends E> elements,
      Function<E, T> mapper) {
    return Util.transform(elements, mapper);
  }

  /** Lazily transforms an Iterable, applying a mapping function to each
   * element. */
  public static <E, T> Iterable<T> transform(Iterable<? extends E> elements,
      Function<E, T> mapper) {
    return Iterables.transform(elements, mapper::apply);
  }

  /** Eagerly converts an Iterable to an ImmutableList, applying a mapping
   * function to each element. */
  public static <E, T> ImmutableList<T> transformEager(
      Iterable<? extends E> elements, Function<E, T> mapper) {
    if (elements instanceof Collection
        && ((Collection<? extends E>) elements).isEmpty()) {
      // Save ourselves the effort of creating a Builder.
      return ImmutableList.of();
    }
    final ImmutableList.Builder<T> b = ImmutableList.builder();
    elements.forEach(e -> b.add(mapper.apply(e)));
    return b.build();
  }

  /** Eagerly converts a List to an ImmutableList, applying a mapping
   * function to each element. */
  public static <E, T> ImmutableList<T> transformEager(
      List<? extends E> elements, Function<E, T> mapper) {
    if (elements.isEmpty()) {
      // Save ourselves the effort of creating a Builder.
      return ImmutableList.of();
    }
    final ImmutableList.Builder<T> b = ImmutableList.builder();
    elements.forEach(e -> b.add(mapper.apply(e)));
    return b.build();
  }

  /** Returns the first index in a list where a predicate is true, or -1. */
  public static <E> int find(List<? extends E> list, Predicate<E> predicate) {
    if (list instanceof RandomAccess) {
      for (int i = 0; i < list.size(); i++) {
        if (predicate.test(list.get(i))) {
          return i;
        }
      }
    } else {
      int i = 0;
      for (E e : list) {
        if (predicate.test(e)) {
          return i;
        }
        ++i;
      }
    }
    return -1;
  }

  public static <E> List<E> intersect(List<E> list0,
      Iterable<? extends E> list1) {
    final ImmutableList.Builder<E> list2 = ImmutableList.builder();
    final Set<E> set = new HashSet<>(list0);
    for (E e : list1) {
      if (set.contains(e)) {
        list2.add(e);
      }
    }
    return list2.build();
  }

  /** Flushes a builder and returns its contents. */
  public static String str(StringBuilder b) {
    String s = b.toString();
    b.setLength(0);
    return s;
  }
}

// End Static.java
