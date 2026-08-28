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

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/**
 * Evaluation environment.
 *
 * <p>Whereas {@code Environment} contains both types and values, because it is
 * used for validation/compilation, EvalEnv contains only values.
 */
public interface EvalEnv {

  /** The name of the variable that contains the {@link Session}. */
  String SESSION = "$session";

  /** Returns the binding of {@code name} if bound, null if not. */
  @Nullable
  Object getOpt(String name);

  /** Returns the binding of {@code name} if bound, throws if not. */
  default Object get(String name) {
    return requireNonNull(getOpt(name), name);
  }

  /** Returns the current session. */
  default Session getSession() {
    return (Session) get(SESSION);
  }
  /**
   * Creates an environment that has the same content as this one, plus the
   * binding (name, value).
   */
  default EvalEnv bind(String name, Object value) {
    return new EvalEnvs.SubEvalEnv(this, name, value);
  }

  /**
   * Visits every variable binding in this environment.
   *
   * <p>Bindings that are obscured by more recent bindings of the same name are
   * visited, but after the more obscuring bindings.
   */
  void visit(BiConsumer<String, Object> consumer);

  /** Returns a map of the values and bindings. */
  default Map<String, Object> valueMap() {
    final Map<String, Object> valueMap = new HashMap<>();
    visit(valueMap::putIfAbsent);
    return valueMap;
  }

  /** Converts this environment to a non-mutable environment. */
  default EvalEnv fix() {
    return this;
  }
}

// End EvalEnv.java
