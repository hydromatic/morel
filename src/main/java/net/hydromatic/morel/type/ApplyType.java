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

import java.util.Objects;
import java.util.function.UnaryOperator;

import static net.hydromatic.morel.util.Static.toImmutableList;

/** Type that is a polymorphic type applied to a set of types. */
public class ApplyType extends BaseType {
  public final ParameterizedType type;
  public final ImmutableList<Type> types;

  protected ApplyType(ParameterizedType type, ImmutableList<Type> types) {
    super(Op.APPLY_TYPE);
    this.type = Objects.requireNonNull(type);
    this.types = Objects.requireNonNull(types);
    assert !(type instanceof DataType);
  }

  public Key key() {
    return Keys.apply(type, types);
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  @Override public Type copy(TypeSystem typeSystem,
      UnaryOperator<Type> transform) {
    final Type type2 = type.copy(typeSystem, transform);
    final ImmutableList<Type> types2 =
        types.stream().map(t -> t.copy(typeSystem, transform))
            .collect(toImmutableList());
    return type == type2 && types.equals(types2) ? this
        : typeSystem.apply(type2, types2);
  }
}

// End ApplyType.java
