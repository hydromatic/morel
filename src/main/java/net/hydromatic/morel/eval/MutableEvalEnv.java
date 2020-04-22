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

/** An evaluation environment whose last entry is mutable. */
public interface MutableEvalEnv extends EvalEnv {
  /** Puts a value into this environment. */
  void set(Object value);

  /** Puts a value into this environment in a way that may not succeed.
   *
   * <p>For example, if this environment is based on the pattern (x, 2)
   * then (1, 2) will succeed and will bind x to 1, but (3, 4) will fail.
   *
   * <p>The default implementation calls {@link #set} and always succeeds.
   */
  default boolean setOpt(Object value) {
    set(value);
    return true;
  }
}

// End MutableEvalEnv.java
