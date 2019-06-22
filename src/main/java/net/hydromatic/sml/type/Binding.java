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
package net.hydromatic.sml.type;

import net.hydromatic.sml.eval.Unit;

import java.util.Objects;

/** Binding of a name to a type and a value.
 *
 * <p>Used in {@link net.hydromatic.sml.compile.Environment}. */
public class Binding {
  public final String name;
  public final Type type;
  public final Object value;

  public Binding(String name, Type type, Object value) {
    this.name = name;
    this.type = Objects.requireNonNull(type);
    this.value = Objects.requireNonNull(value);
  }

  @Override public String toString() {
    if (value == Unit.INSTANCE) {
      return name + " = " + type.description();
    } else {
      return name + " = " + value + ":" + type.description();
    }
  }
}

// End Binding.java
