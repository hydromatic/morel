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
import java.util.RandomAccess;
import java.util.function.IntFunction;

/** Read-only list that generates elements between 0 and {@code size} - 1
 * by applying a function.
 *
 * @param <E> Element type
 */
public final class MapList<E> extends AbstractList<E> implements RandomAccess {
  private final int size;
  private final IntFunction<E> fn;

  private MapList(int size, IntFunction<E> fn) {
    this.size = size;
    this.fn = fn;
  }

  /** Creates a MapList. */
  public static <E> MapList<E> of(int size, IntFunction<E> fn) {
    return new MapList<>(size, fn);
  }

  public E get(int index) {
    return fn.apply(index);
  }

  public int size() {
    return size;
  }
}

// End MapList.java
