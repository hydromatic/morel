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
import java.util.Collection;
import java.util.Comparator;
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

/** Utilities. */
public class Static {
  private Static() {}

  /**
   * Whether to skip built-in functions.
   *
   * <p>To skip built-in functions, add "-DskipMorelBuiltIns" java's
   * command-line arguments.
   */
  public static final boolean SKIP =
      getBooleanProperty("skipMorelBuiltIns", false);

  /**
   * Returns the value of a system property, converted into a boolean value.
   *
   * <p>Values "", "true", "TRUE" and "1" are treated as true; "false", "FALSE"
   * and "0" treated as false; for {@code null} and other values, returns {@code
   * defaultVal}.
   */
  @SuppressWarnings("SimplifiableConditionalExpression")
  private static boolean getBooleanProperty(String prop, boolean defaultVal) {
    final String value = System.getProperty(prop);
    if (value == null) {
      return defaultVal;
    }
    final String low = value.toLowerCase(Locale.ROOT);
    return low.equals("true") || low.equals("1") || low.isEmpty()
        ? true
        : low.equals("false") || low.equals("0") ? false : defaultVal;
  }

  /**
   * Returns whether an {@link Iterable} has fewer than {@code n} elements.
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
    for (Iterator<E> iterator = iterable.iterator();
        iterator.hasNext();
        iterator.next()) {
      if (++i == n) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns the last element of a list.
   *
   * @throws java.lang.IndexOutOfBoundsException if the list is empty
   */
  public static <E> E last(List<E> list) {
    return list.get(list.size() - 1);
  }

  /** Returns all but the first element of a list. */
  public static <E> List<E> skip(List<E> list) {
    return skip(list, 1);
  }

  /** Returns all but the first {@code count} elements of a list. */
  public static <E> List<E> skip(List<E> list, int count) {
    return list.subList(count, list.size());
  }

  /** Returns every element of a list but its last element. */
  public static <E> List<E> skipLast(List<E> list) {
    return skipLast(list, 1);
  }

  /** Returns every element of a list but its last {@code n} elements. */
  public static <E> List<E> skipLast(List<E> list, int n) {
    return list.subList(0, list.size() - n);
  }

  /**
   * Returns a list with one element appended.
   *
   * @see ConsList
   */
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
    list.forEach(
        e2 -> {
          if (!e2.equals(e)) {
            builder.add(e2);
          }
        });
    return builder.build();
  }

  /** Adds an element to a map. */
  public static <K, V> Map<K, V> plus(Map<K, V> map, K k, V v) {
    return ImmutableMap.<K, V>builder().putAll(map).put(k, v).build();
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

  /** Returns whether a predicate is true for all elements of a list. */
  public static <E> boolean allMatch(
      Iterable<? extends E> iterable, Predicate<E> predicate) {
    for (E e : iterable) {
      if (!predicate.test(e)) {
        return false;
      }
    }
    return true;
  }

  /** Returns whether a predicate is true for at least one element of a list. */
  public static <E> boolean anyMatch(
      Iterable<? extends E> iterable, Predicate<E> predicate) {
    for (E e : iterable) {
      if (predicate.test(e)) {
        return true;
      }
    }
    return false;
  }

  /** Returns whether a predicate is not true for any element of a list. */
  public static <E> boolean noneMatch(
      Iterable<? extends E> iterable, Predicate<E> predicate) {
    for (E e : iterable) {
      if (predicate.test(e)) {
        return false;
      }
    }
    return true;
  }

  /** Lazily transforms a list, applying a mapping function to each element. */
  public static <E, T> List<T> transform(
      List<? extends E> elements, Function<E, T> mapper) {
    return org.apache.calcite.util.Util.transform(elements, mapper);
  }

  /**
   * Lazily transforms an Iterable, applying a mapping function to each element.
   */
  public static <E, T> Iterable<T> transform(
      Iterable<? extends E> elements, Function<E, T> mapper) {
    return Iterables.transform(elements, mapper::apply);
  }

  /**
   * Eagerly converts an Iterable to an ImmutableList, applying a mapping
   * function to each element.
   */
  public static <E, T> ImmutableList<T> transformEager(
      Iterable<? extends E> elements, Function<E, T> mapper) {
    if (elements instanceof List) {
      // If elements is a List, we can optimize by pre-sizing the builder.
      // (We could also check for Collection, but it's not worth the effort.)
      return transformEager((Collection<? extends E>) elements, mapper);
    }
    final ImmutableList.Builder<T> b = ImmutableList.builder();
    elements.forEach(e -> b.add(mapper.apply(e)));
    return b.build();
  }

  /**
   * Eagerly converts a Collection to an ImmutableList, applying a mapping
   * function to each element.
   *
   * <p>More efficient than {@link #transformEager(Iterable, Function)}, because
   * we can optimize the size of the builder for the size of the collection, and
   * can avoid creating a builder if the collection is empty.
   */
  public static <E, T> ImmutableList<T> transformEager(
      Collection<? extends E> elements, Function<E, T> mapper) {
    if (elements.isEmpty()) {
      // Save ourselves the effort of creating a Builder.
      return ImmutableList.of();
    }

    // Optimize by making the builder the same size as the collection.
    final ImmutableList.Builder<T> b =
        ImmutableList.builderWithExpectedSize(elements.size());
    elements.forEach(e -> b.add(mapper.apply(e)));
    return b.build();
  }

  /**
   * Eagerly converts a List to an ImmutableList, applying a mapping function to
   * each element.
   *
   * <p>More efficient than {@link #transformEager(Collection, Function)},
   * because we can avoid creating a builder for a singleton list.
   */
  public static <E, T> ImmutableList<T> transformEager(
      List<? extends E> elements, Function<E, T> mapper) {
    switch (elements.size()) {
      case 0:
        // Save ourselves the effort of creating a Builder.
        return ImmutableList.of();

      case 1:
        // Save ourselves the effort of creating a Builder, and go directly to a
        // singleton list.
        return ImmutableList.of(mapper.apply(elements.get(0)));

      default:
        // Optimize by making the builder the same size as the collection.
        final ImmutableList.Builder<T> b =
            ImmutableList.builderWithExpectedSize(elements.size());
        elements.forEach(e -> b.add(mapper.apply(e)));
        return b.build();
    }
  }

  /**
   * Eagerly converts a List to an ImmutableList, keeping elements that pass a
   * predicate.
   */
  public static <E> ImmutableList<E> filterEager(
      List<? extends E> elements, Predicate<E> predicate) {
    // Do all the elements match?
    for (int i = 0; i < elements.size(); i++) {
      E element = elements.get(i);
      if (predicate.test(element)) {
        continue;
      }
      // Optimize by making the builder the same size as the collection.
      final ImmutableList.Builder<E> b =
          ImmutableList.builderWithExpectedSize(elements.size());
      // Add all elements before the first non-matching element.
      for (int j = 0; j < i; j++) {
        b.add(elements.get(j));
      }
      // Test and add all elements after the first non-matching element.
      for (int j = i + 1; j < elements.size(); j++) {
        E e = elements.get(j);
        if (predicate.test(e)) {
          b.add(e);
        }
      }
      return b.build();
    }
    // All elements match. We can just return the original list.
    return ImmutableList.copyOf(elements);
  }

  /**
   * Given a {@link Map}, returns an {@link ImmutableMap} with the same keys,
   * but with each value transformed by a mapping function.
   */
  public static <K, V, V2> ImmutableMap<K, V2> transformValuesEager(
      Map<K, V> map, Function<V, V2> mapper) {
    if (map.isEmpty()) {
      // Save ourselves the effort of creating a Builder.
      return ImmutableMap.of();
    }
    final ImmutableMap.Builder<K, V2> b =
        ImmutableMap.builderWithExpectedSize(map.size());
    map.forEach((k, v) -> b.put(k, mapper.apply(v)));
    return b.build();
  }

  /**
   * Given a {@link SortedMap}, returns an {@link ImmutableSortedMap} with the
   * same keys, but with each value transformed by a mapping function.
   */
  @SuppressWarnings("unchecked")
  public static <K, V, V2> ImmutableSortedMap<K, V2> transformValuesEager(
      SortedMap<K, V> map, Function<V, V2> mapper) {
    if (map.isEmpty()) {
      // Save ourselves the effort of creating a Builder.
      return ImmutableSortedMap.of();
    }
    final ImmutableSortedMap.Builder<K, V2> b =
        ImmutableSortedMap.orderedBy((Comparator<K>) map.comparator());
    map.forEach((k, v) -> b.put(k, mapper.apply(v)));
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

  /**
   * Returns a list containing the elements of {@code list0} that are also in
   * {@code list1}. The result preserves the order of {@code list1}, and any
   * duplicates it may contain.
   */
  public static <E> List<E> intersect(
      List<E> list0, Iterable<? extends E> list1) {
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

  /** Returns whether a builder ends with a given string. */
  public static boolean endsWith(StringBuilder buf, String s) {
    final int i = buf.length() - s.length();
    return i >= 0 && buf.indexOf(s, i) == i;
  }
}

// End Static.java
