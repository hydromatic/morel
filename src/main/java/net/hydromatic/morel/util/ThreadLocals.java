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

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Utilities for {@link ThreadLocal}.
 */
public class ThreadLocals {
  private ThreadLocals() {
  }

  /** Performs an action with a thread-local set to a particular value. */
  public static <T> void let(ThreadLocal<T> threadLocal, T value,
      Runnable runnable) {
    final T originalValue = threadLocal.get();
    threadLocal.set(value);
    try {
      runnable.run();
    } finally {
      // Restore the original value. I wish there were a way to know that the
      // original value was present because threadLocal was initially not set.
      threadLocal.set(originalValue);
    }
  }

  /** Performs an action with a thread-local set to a particular value,
   * and returns the result. */
  public static <T, R> R let(ThreadLocal<T> threadLocal, T value,
      Supplier<R> supplier) {
    final T originalValue = threadLocal.get();
    threadLocal.set(value);
    try {
      return supplier.get();
    } finally {
      // Restore the original value. I wish there were a way to know that the
      // original value was present because threadLocal was initially not set.
      threadLocal.set(originalValue);
    }
  }

  /** Performs an action with a thread-local set to a value derived from its
   * current value via a transformer. */
  public static <T> void mutate(ThreadLocal<T> threadLocal,
      UnaryOperator<T> transform, Runnable runnable) {
    let(threadLocal, transform.apply(threadLocal.get()), runnable);
  }

  /** Performs an action with a thread-local set to a value derived from its
   * current value via a transformer,
   * and returns the result. */
  public static <T, R> R mutate(ThreadLocal<T> threadLocal,
      UnaryOperator<T> transform, Supplier<R> supplier) {
    return let(threadLocal, transform.apply(threadLocal.get()), supplier);
  }
}

// End ThreadLocals.java
