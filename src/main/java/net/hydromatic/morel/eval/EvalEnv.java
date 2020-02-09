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

import net.hydromatic.morel.compile.Environment;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Evaluation environment.
 *
 * <p>Whereas {@link Environment} contains both types and values,
 * because it is used for validation/compilation, EvalEnv contains
 * only values. */
public abstract class EvalEnv {

  @Override public String toString() {
    return valueMap().toString();
  }

  /** Returns the binding of {@code name} if bound, null if not. */
  abstract Object getOpt(String name);

  /** Creates an environment that has the same content as this one, plus
   * the binding (name, value). */
  public EvalEnv bind(String name, Object value) {
    return new EvalEnvs.SubEvalEnv(this, name, value);
  }

  /** Visits every variable binding in this environment.
   *
   * <p>Bindings that are obscured by more recent bindings of the same name
   * are visited, but after the more obscuring bindings. */
  abstract void visit(BiConsumer<String, Object> consumer);

  /** Returns a map of the values and bindings. */
  public final Map<String, Object> valueMap() {
    final Map<String, Object> valueMap = new HashMap<>();
    visit(valueMap::putIfAbsent);
    return valueMap;
  }
}

// End EvalEnv.java
