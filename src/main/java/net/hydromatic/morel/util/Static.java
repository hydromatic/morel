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

import java.util.stream.Collector;

/**
 * Utilities.
 */
public class Static {
  private Static() {
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
}

// End Static.java
