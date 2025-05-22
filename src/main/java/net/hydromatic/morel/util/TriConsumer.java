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

import java.util.function.Consumer;

/**
 * Represents an operation that accepts three input arguments and returns no
 * result. This is the three-arity specialization of {@link Consumer}. Unlike
 * most other functional interfaces, {@code TriConsumer} is expected to operate
 * via side-effects.
 *
 * <p>This is a {@link FunctionalInterface functional interface} whose
 * functional method is {@link #accept(Object, Object, Object)}.
 *
 * @param <R> the type of the first argument to the operation
 * @param <S> the type of the second argument to the operation
 * @param <T> the type of the third argument to the operation
 * @see java.util.function.Consumer
 * @see java.util.function.BiConsumer
 */
@FunctionalInterface
public interface TriConsumer<R, S, T> {
  /**
   * Performs this operation on the given arguments.
   *
   * @param r the first input argument
   * @param s the second input argument
   * @param t the third input argument
   */
  void accept(R r, S s, T t);
}

// End TriConsumer.java
