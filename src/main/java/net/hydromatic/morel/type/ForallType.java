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

import com.google.common.collect.Maps;

import java.util.List;
import java.util.function.UnaryOperator;

import static java.util.Objects.requireNonNull;

/** Universally quantified type. */
public class ForallType extends BaseType {
  public final int parameterCount;
  public final Type type;

  ForallType(int parameterCount, Type type) {
    super(Op.FORALL_TYPE);
    this.parameterCount = parameterCount;
    this.type = requireNonNull(type);
  }

  public Key key() {
    return Keys.forall(type, parameterCount);
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  @Override public ForallType copy(TypeSystem typeSystem,
      UnaryOperator<Type> transform) {
    final Type type2 = type.copy(typeSystem, transform);
    return type2 == type
        ? this
        : typeSystem.forallType(parameterCount, type2);
  }

  @Override public Type substitute(TypeSystem typeSystem,
      List<? extends Type> types) {
    switch (type.op()) {
    case DATA_TYPE:
      final DataType dataType = (DataType) type;
      Key key =
          Keys.datatype(dataType.name, Keys.toKeys(types),
              Maps.transformValues(dataType.typeConstructors,
                  k -> k.substitute(types)));
      return typeSystem.typeFor(key);

    case FUNCTION_TYPE:
      return type.substitute(typeSystem, types);

    default:
      throw new AssertionError(type.op() + ": " + type);
    }
  }
}

// End ForallType.java
