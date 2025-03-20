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

import static java.util.Objects.requireNonNull;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Like a list, but {@link #poll} (equivalent to {@code remove(0)} is O(1).
 *
 * @param <E> Element type
 */
public class ArrayQueue<E> {
  private E[] elements;
  private int start;
  private int end;

  /** Creates an empty ArrayQueue. */
  @SuppressWarnings("unchecked")
  public ArrayQueue() {
    elements = (E[]) new Object[16];
  }

  @Override
  public String toString() {
    return asList().toString();
  }

  /** Removes the element at the head of this queue, or returns null. */
  public @Nullable E poll() {
    if (start == end) {
      return null;
    }
    E e = elements[start];
    elements[start] = null;
    start = inc(start, elements.length);
    return e;
  }

  /** Returns the number of elements in this queue. */
  public int size() {
    return sub(end, start, elements.length);
  }

  /** Returns whether this queue is empty. (Same as {@code size() == 0}.) */
  public boolean isEmpty() {
    return start == end;
  }

  /** Returns the element at position {@code i}. */
  public E get(int i) {
    if (i < 0 || i >= size()) {
      throw new IndexOutOfBoundsException();
    }
    return elements[add(start, i, elements.length)];
  }

  /** Sets the element at position {@code i}. */
  public E set(int i, E e) {
    if (i < 0 || i >= size()) {
      throw new IndexOutOfBoundsException();
    }
    requireNonNull(e);
    int k = add(start, i, elements.length);
    E previous = elements[k];
    elements[k] = e;
    return previous;
  }

  /** Adds an element to the tail. */
  public void add(E e) {
    requireNonNull(e);
    elements[end] = e;
    end = inc(end, elements.length);
    if (start == end) {
      grow();
    }
  }

  /** Increases the capacity by 1. */
  private void grow() {
    int oldCapacity = elements.length;
    int newCapacity = oldCapacity * 2;
    final Object[] es = elements = Arrays.copyOf(elements, newCapacity);
    if (end < start || end == start && es[start] != null) {
      int newSpace = newCapacity - oldCapacity;
      System.arraycopy(es, start, es, start + newSpace, oldCapacity - start);
      for (int i = start, to = start += newSpace; i < to; i++) {
        es[i] = null;
      }
    }
  }

  /** Returns a view of the contents as a list. */
  public List<E> asList() {
    return new AbstractList<E>() {
      @Override
      public int size() {
        return ArrayQueue.this.size();
      }

      @Override
      public E get(int index) {
        return ArrayQueue.this.get(index);
      }

      @Override
      public E set(int index, E element) {
        return ArrayQueue.this.set(index, element);
      }

      @Override
      public E remove(int index) {
        return ArrayQueue.this.remove(index);
      }
    };
  }

  private static int inc(int i, int modulus) {
    ++i;
    if (i == modulus) {
      i = 0;
    }
    return i;
  }

  private static int dec(int i, int modulus) {
    if (i == 0) {
      i = modulus - 1;
    } else {
      --i;
    }
    return i;
  }

  private static int add(int i, int j, int modulus) {
    int k = i + j;
    if (k >= modulus) {
      k -= modulus;
    }
    return k;
  }

  private static int sub(int i, int j, int modulus) {
    int k = i - j;
    if (k < 0) {
      k += modulus;
    }
    return k;
  }

  /** Calls a consumer with each element, in order. */
  public void forEach(Consumer<? super E> consumer) {
    if (start <= end) {
      for (int i = start; i < end; i++) {
        consumer.accept(elements[i]);
      }
    } else {
      for (int i = start; i < elements.length; i++) {
        consumer.accept(elements[i]);
      }
      for (int i = 0; i < end; i++) {
        consumer.accept(elements[i]);
      }
    }
  }

  /**
   * Removes element {@code i} from the queue in O(1) time.
   *
   * <p>If {@code i} is the first element, removes it (equivalent to calling
   * {@link #poll()}; if {@code i} is the last element, removes it; otherwise
   * moves the last element into position {@code i} and shortens the queue.
   */
  public E remove(int i) {
    int size = size();
    if (i < 0 || i >= size) {
      throw new IndexOutOfBoundsException();
    }
    final E e;
    if (i == 0) {
      // Remove the head element (similar to poll())
      e = elements[start];
      elements[start] = null;
      start = inc(start, elements.length);
    } else if (i == size - 1) {
      // Remove the tail element
      end = dec(end, elements.length);
      e = elements[end];
      elements[end] = null;
    } else {
      // Remove a middle element, and move the tail element into its place
      int k = add(start, i, elements.length);
      e = elements[k];
      end = dec(end, elements.length);
      elements[k] = elements[end];
      elements[end] = null;
    }
    return e;
  }

  public ListIterator<E> listIterator() {
    return asList().listIterator();
  }
}

// End ArrayQueue.java
