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
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;

// CHECKSTYLE: IGNORE 30 (long line in javadoc)
/**
 * Applicable whose argument is a 3-tuple.
 *
 * <p>Implementations that use {@code Applicable3} are more efficient and
 * concise than {@link ApplicableImpl} because there is no need to create an
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
 * @param <R> return type
 * @param <A0> type of argument 0
 * @param <A1> type of argument 1
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class Applicable2<R, A0, A1> extends ApplicableImpl {
  protected Applicable2(BuiltIn builtIn, Pos pos) {
    super(builtIn, pos);
  }

  protected Applicable2(BuiltIn builtIn) {
    this(builtIn, Pos.ZERO);
  }

  @Override
  public Object apply(EvalEnv env, Object argValue) {
    final List list = (List) argValue;
    return apply((A0) list.get(0), (A1) list.get(1));
  }

  public abstract R apply(A0 a0, A1 a1);
}

// End Applicable2.java
