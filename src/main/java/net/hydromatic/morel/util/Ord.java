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
import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.function.BiConsumer;
import java.util.function.ObjIntConsumer;

/**
 * Pair of an element and an ordinal.
 *
 * @param <E> Element type
 */
public class Ord<E> implements Map.Entry<Integer, E> {
  public final int i;
  public final E e;

  /** Creates an Ord. */
  private Ord(int i, E e) {
    this.i = i;
    this.e = e;
  }

  /** Creates an Ord. */
  public static <E> Ord<E> of(int n, E e) {
    return new Ord<>(n, e);
  }

  /** Creates an iterable of {@code Ord}s over an iterable. */
  public static <E> Iterable<Ord<E>> zip(final Iterable<? extends E> iterable) {
    return () -> zip(iterable.iterator());
  }

  /** Creates an iterator of {@code Ord}s over an iterator. */
  public static <E> Iterator<Ord<E>> zip(final Iterator<? extends E> iterator) {
    return new Iterator<Ord<E>>() {
      int n = 0;

      public boolean hasNext() {
        return iterator.hasNext();
      }

      public Ord<E> next() {
        return Ord.of(n++, iterator.next());
      }

      public void remove() {
        iterator.remove();
      }
    };
  }

  /** Returns a numbered list based on an array. */
  public static <E> List<Ord<E>> zip(final E[] elements) {
    return new OrdArrayList<>(elements);
  }

  /** Returns a numbered list. */
  public static <E> List<Ord<E>> zip(final List<? extends E> elements) {
    return elements instanceof RandomAccess
        ? new OrdRandomAccessList<>(elements)
        : new OrdList<>(elements);
  }

  /** Performs the given action for each element of the {@code Iterable}. */
  public static <E> void forEachIndexed(
      final Iterable<E> iterable, ObjIntConsumer<E> consumer) {
    int i = 0;
    for (E e : iterable) {
      consumer.accept(e, i++);
    }
  }

  /**
   * Performs the given action for each element of the {@code List}.
   *
   * <p>More efficient than {@link #forEachIndexed(Iterable, ObjIntConsumer)} if
   * the list implements {@link RandomAccess}.
   */
  public static <E> void forEach(
      final List<E> list, ObjIntConsumer<E> consumer) {
    for (int i = 0; i < list.size(); i++) {
      consumer.accept(list.get(i), i);
    }
  }

  /** Performs the given action for each entry of a {@code Map}. */
  public static <K, V> void forEachIndexed(
      final Map<K, V> map, IntObjObjConsumer<K, V> consumer) {
    int i = 0;
    for (Map.Entry<K, V> e : map.entrySet()) {
      consumer.accept(i++, e.getKey(), e.getValue());
    }
  }

  /**
   * Iterates over an array in reverse order.
   *
   * <p>Given the array ["a", "b", "c"], returns (2, "c") then (1, "b") then (0,
   * "a").
   */
  public static <E> Iterable<Ord<E>> reverse(E... elements) {
    return reverse(ImmutableList.copyOf(elements));
  }

  /**
   * Iterates over a list in reverse order.
   *
   * <p>Given the list ["a", "b", "c"], returns (2, "c") then (1, "b") then (0,
   * "a").
   */
  public static <E> Iterable<Ord<E>> reverse(Iterable<? extends E> elements) {
    final ImmutableList<E> elementList = ImmutableList.copyOf(elements);
    return () ->
        new Iterator<Ord<E>>() {
          int i = elementList.size() - 1;

          public boolean hasNext() {
            return i >= 0;
          }

          public Ord<E> next() {
            return Ord.of(i, elementList.get(i--));
          }

          public void remove() {
            throw new UnsupportedOperationException("remove");
          }
        };
  }

  public Integer getKey() {
    return i;
  }

  public E getValue() {
    return e;
  }

  public E setValue(E value) {
    throw new UnsupportedOperationException();
  }

  /**
   * List of {@link Ord} backed by a list of elements.
   *
   * @param <E> element type
   */
  private static class OrdList<E> extends AbstractList<Ord<E>> {
    private final List<? extends E> elements;

    OrdList(List<? extends E> elements) {
      this.elements = elements;
    }

    public Ord<E> get(int index) {
      return Ord.of(index, elements.get(index));
    }

    public int size() {
      return elements.size();
    }
  }

  /**
   * List of {@link Ord} backed by a random-access list of elements.
   *
   * @param <E> element type
   */
  private static class OrdRandomAccessList<E> extends OrdList<E>
      implements RandomAccess {
    OrdRandomAccessList(List<? extends E> elements) {
      super(elements);
    }
  }

  /**
   * List of {@link Ord} backed by an array of elements.
   *
   * @param <E> element type
   */
  private static class OrdArrayList<E> extends AbstractList<Ord<E>>
      implements RandomAccess {
    private final E[] elements;

    OrdArrayList(E[] elements) {
      this.elements = elements;
    }

    @Override
    public Ord<E> get(int index) {
      return Ord.of(index, elements[index]);
    }

    @Override
    public int size() {
      return elements.length;
    }
  }

  /**
   * Consumer that receives an ordinal, a key, and a value.
   *
   * <p>Analogous to {@link BiConsumer}, but with an extra ordinal.
   *
   * @see #forEachIndexed(Map, IntObjObjConsumer)
   * @param <K> Key type
   * @param <V> Value type
   */
  @FunctionalInterface
  public interface IntObjObjConsumer<K, V> {
    void accept(int i, K key, V value);
  }
}

// End Ord.java
