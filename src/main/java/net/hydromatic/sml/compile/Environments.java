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

import java.util.Objects;
import java.util.function.BiConsumer;

/** Helpers for {@link Environment}. */
public abstract class Environments {

  /** An environment with the only the built-in stuff. */
  private static final Environment BASIC_ENVIRONMENT =
      EmptyEnvironment.INSTANCE
          // Later, also add "nil", "ref", "!"
          .bind("true", PrimitiveType.BOOL, true)
          .bind("false", PrimitiveType.BOOL, false);

  private Environments() {}

  /** Creates an empty environment. */
  public static Environment empty() {
    return BASIC_ENVIRONMENT;
  }

  /** Environment that inherits from a parent environment and adds one
   * binding. */
  static class SubEnvironment extends Environment {
    private final Environment parent;
    private final String name;
    private final Binding binding;

    SubEnvironment(Environment parent, String name, Binding binding) {
      this.parent = Objects.requireNonNull(parent);
      this.name = Objects.requireNonNull(name);
      this.binding = Objects.requireNonNull(binding);
    }

    public Binding getOpt(String name) {
      if (name.equals(this.name)) {
        return binding;
      }
      return parent.getOpt(name);
    }

    void visit(BiConsumer<String, Binding> consumer) {
      consumer.accept(name, binding);
      parent.visit(consumer);
    }
  }

  /** Empty environment. */
  private static class EmptyEnvironment extends Environment {
    static final EmptyEnvironment INSTANCE = new EmptyEnvironment();

    void visit(BiConsumer<String, Binding> consumer) {
    }

    public Binding getOpt(String name) {
      return null;
    }
  }
}

// End Environments.java
