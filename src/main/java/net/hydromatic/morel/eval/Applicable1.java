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

/**
 * Applicable whose argument is an atomic value.
 *
 * <p>Note that this interface is rather similar to {@link Applicable}. The
 * difference is that {@link #apply(Object)} has only one argument whereas
 * {@link Applicable#apply(EvalEnv, Object)} takes an {@link EvalEnv}. Unless
 * the function you are implementing requires an environment, you should use
 * {@link Applicable1} rather than {@link Applicable}.
 *
 * <p>The interfaces for functions with multiple arguments, {@link Applicable2},
 * {@link Applicable3}, and {@link Applicable4}, each have a {@code curry}
 * method that returns an {@link Applicable1}.
 *
 * @see Applicable2#curry()
 * @see Applicable3#curry()
 * @see Applicable4#curry()
 * @see Codes.BaseApplicable1
 * @see Codes.BasePositionedApplicable1
 * @param <R> return type
 * @param <A0> type of argument
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public interface Applicable1<R, A0> extends Describable {
  /** Applies this function to its argument. */
  R apply(A0 a0);

  /**
   * {@inheritDoc}
   *
   * <p>This default implementation throws; this allows us to have lightweight
   * implementations of {@link Applicable1} that are quick to instantiate
   * (because they will be created at runtime as a curried function is applied
   * to arguments in turn) but will never be asked to describe themselves as
   * part of a plan.
   */
  @Override
  default Describer describe(Describer describer) {
    throw new UnsupportedOperationException("describe");
  }
}

// End Applicable1.java
