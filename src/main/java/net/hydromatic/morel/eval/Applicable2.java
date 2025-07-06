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

// CHECKSTYLE: IGNORE 30 (long line in javadoc)
/**
 * Applicable whose argument is a 2-tuple.
 *
 * <p>Implementations that use {@code Applicable3} are more efficient and
 * concise than {@link Applicable} because there is no need to create an
 * ephemeral tuple (Java {@link List}) to pass the arguments, and Java's
 * generics provide the casting.
 *
 * <p>But the rewrite assumes that the function is <b>strict</b> (always
 * evaluates all arguments, even if the function throws) and doesn't use {@link
 * EvalEnv}, so it is not appropriate for all functions. For example, {@link
 * Codes#andAlso(Code, Code) andalso} evaluates its arguments lazily and
 * therefore cannot be an {@code Applicable2}.
 *
 * <p>If a function has an {@code Applicable2} implementation and the argument
 * tuple is evaluated whole, the old evaluation path will be used. For example,
 * the first call below uses {@code apply} and the second uses {@code apply}:
 *
 * <pre>{@code
 * - Math.pow (2.0, 3.0);
 * val it = 8.0 : real
 * - Sys.plan ();
 * val it = "apply2(fnValue Math.pow, constant(2.0), constant(3.0))" : string
 * - Math.pow (List.hd [(2.0, 3.0)]);
 * val it = 8.0 : real
 * - Sys.plan ();
 * val it =
 *   "apply(fnValue Math.pow, argCode apply(fnValue List.hd, argCode tuple(tuple(constant(2.0), constant(3.0)))))"
 *   : string
 * }</pre>
 *
 * @see Applicable3
 * @see Applicable4
 * @see Codes.BaseApplicable2
 * @see Codes.BasePositionedApplicable2
 * @param <R> return type
 * @param <A0> type of argument 0
 * @param <A1> type of argument 1
 */
public interface Applicable2<R, A0, A1> {
  /** Applies this function to its two arguments. */
  R apply(A0 a0, A1 a1);

  /**
   * Converts this function {@code f(a, b)} into a function that can be called
   * {@code f(a)(b)}.
   */
  Applicable1<Applicable1<R, A1>, A0> curry();
}

// End Applicable2.java
