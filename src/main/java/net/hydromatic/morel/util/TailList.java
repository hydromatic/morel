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

import java.util.AbstractList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** A dynamic list that reads from a given point in a backing list.
 *
 * @param <E> Element type */
public class TailList<E> extends AbstractList<E> {
  private final List<E> list;
  private final int start;

  /** Creates a list that reads elements from a given backing list
   * after a given start point.
   *
   * @param list Backing list
   * @param start Start point
   */
  public TailList(List<E> list, int start) {
    this.list = Objects.requireNonNull(list);
    this.start = start;
  }

  /** Creates a list whose start is the current size of the backing list.
   *
   * <p>Thus only elements appended to the backing list after creation will
   * appear in this list. */
  public TailList(List<E> list) {
    this(list, list.size());
  }

  @Override public E get(int index) {
    return list.get(start + index);
  }

  @Override public int size() {
    return list.size() - start;
  }

  @Override public void clear() {
    while (list.size() > start) {
      list.remove(list.size() - 1);
    }
  }

  @Override public boolean add(E e) {
    return list.add(e);
  }

  @Override public void add(int index, E element) {
    list.add(start + index, element);
  }

  @Override public boolean addAll(int index, Collection<? extends E> c) {
    return list.addAll(start + index, c);
  }

  @Override public boolean addAll(Collection<? extends E> c) {
    return list.addAll(c);
  }

  @Override public E remove(int index) {
    return list.remove(start + index);
  }

  @Override public E set(int index, E element) {
    return list.set(start + index, element);
  }
}

// End TailList.java
