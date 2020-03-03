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

import net.hydromatic.morel.ast.Op;

import java.util.Objects;
import java.util.function.Function;

/** The type of a list value. */
public class ListType extends BaseType {
  public final Type elementType;

  ListType(String description, Type elementType) {
    super(Op.LIST, description);
    this.elementType = Objects.requireNonNull(elementType);
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  public Type copy(TypeSystem typeSystem, Function<Type, Type> transform) {
    final Type elementType2 = elementType.copy(typeSystem, transform);
    return elementType2 == elementType
        ? this
        : typeSystem.listType(elementType2);
  }
}

// End ListType.java
