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

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Universally quantified type. */
public class ForallType extends BaseType {
  public final List<TypeVar> typeVars;
  public final Type type;

  ForallType(ImmutableList<TypeVar> typeVars, Type type) {
    super(Op.FORALL_TYPE);
    this.typeVars = Objects.requireNonNull(typeVars);
    this.type = Objects.requireNonNull(type);
  }

  public Key key() {
    return Keys.forall(type, typeVars);
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  @Override public ForallType copy(TypeSystem typeSystem,
      UnaryOperator<Type> transform) {
    final Type type2 = type.copy(typeSystem, transform);
    return type2 == type
        ? this
        : typeSystem.forallType(typeVars, type2);
  }
}

// End ForallType.java
