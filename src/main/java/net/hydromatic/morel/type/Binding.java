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
package net.hydromatic.morel.type;

import net.hydromatic.morel.eval.Unit;

import java.util.Objects;

/** Binding of a name to a type and a value.
 *
 * <p>Used in {@link net.hydromatic.morel.compile.Environment}. */
public class Binding {
  public final String name;
  public final Type type;
  public final Object value;
  /** If true, the binding is ignored by inlining. */
  public final boolean parameter;

  Binding(String name, Type type, Object value, boolean parameter) {
    this.name = name;
    this.type = Objects.requireNonNull(type);
    this.value = Objects.requireNonNull(value);
    this.parameter = parameter;
  }

  public static Binding of(String name, Type type) {
    return new Binding(name, type, Unit.INSTANCE, false);
  }

  public static Binding of(String name, Type type, Object value) {
    return new Binding(name, type, value, false);
  }

  public static Binding of(String name, Type type, Object value,
      boolean parameter) {
    return new Binding(name, type, value, parameter);
  }

  @Override public String toString() {
    if (value == Unit.INSTANCE) {
      return name + " = " + type.moniker();
    } else {
      return name + " = " + value + ":" + type.moniker();
    }
  }
}

// End Binding.java
