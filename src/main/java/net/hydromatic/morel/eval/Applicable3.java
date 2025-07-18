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
package net.hydromatic.morel.eval;

import java.util.List;

/**
 * Applicable whose argument is a 3-tuple.
 *
 * <p>Implementations that use {@code Applicable3} are more efficient and
 * concise than {@link Applicable} because there is no need to create an
 * ephemeral tuple (Java {@link List}) to pass the arguments, and Java's
 * generics provide the casting.
 *
 * <p>But the rewrite assumes that the function is <b>strict</b> (always
 * evaluates all arguments, even if the function throws) and doesn't use {@link
 * EvalEnv}, so it is not appropriate for all functions.
 *
 * <p>If a function has an {@code Applicable3} implementation and the argument
 * tuple is evaluated whole, the old evaluation path will be used.
 *
 * @see Applicable2
 * @see Applicable4
 * @see Codes.BaseApplicable3
 * @see Codes.BasePositionedApplicable3
 * @param <R> return type
 * @param <A0> type of argument 0
 * @param <A1> type of argument 1
 * @param <A2> type of argument 2
 */
public interface Applicable3<R, A0, A1, A2> {
  /** Applies this function to its three arguments. */
  R apply(A0 a0, A1 a1, A2 a2);

  /**
   * Converts this function {@code f(a, b, c)} into a function that can be
   * called {@code f(a)(b)(c)}.
   */
  Applicable1<Applicable1<Applicable1<R, A2>, A1>, A0> curry();
}

// End Applicable3.java
