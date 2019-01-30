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
package net.hydromatic.sml.compile;

import net.hydromatic.sml.type.Binding;
import net.hydromatic.sml.type.PrimitiveType;
import net.hydromatic.sml.type.Type;

/** Helpers for {@link Environment}. */
public abstract class Environments {
  private Environments() {}

  /** Creates an empty environment. */
  public static Environment empty() {
    final Environment env = new Environment();
    env.valueMap.put("true",
        new Binding("true", PrimitiveType.BOOL, true));
    env.valueMap.put("false",
        new Binding("false", PrimitiveType.BOOL, false));
    // TODO: also add "nil", "ref", "!"
    return env;
  }

  /** Creates an environment that is the same as a given environment, plus one
   * more variable. */
  public static Environment add(Environment env, String var, Type type,
      Object value) {
    // Copying the entire table is not very efficient.
    final Environment env2 = new Environment();
    env2.valueMap.putAll(env.valueMap);
    final Binding binding = new Binding(var, type, value);
    env2.valueMap.put(var, binding);
    return env2;
  }
}

// End Environments.java
