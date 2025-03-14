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

import static java.util.Objects.requireNonNull;

import net.hydromatic.morel.ast.Op;

/** Abstract implementation of Type. */
abstract class BaseType implements Type {
  final Op op;

  protected BaseType(Op op) {
    this.op = requireNonNull(op);
  }

  public Op op() {
    return op;
  }

  @Override
  public String toString() {
    return key().toString();
  }

  @Override
  public boolean equals(Object o) {
    return this == o
        || o instanceof BaseType && key().equals(((BaseType) o).key());
  }

  @Override
  public int hashCode() {
    return key().hashCode();
  }
}

// End BaseType.java
