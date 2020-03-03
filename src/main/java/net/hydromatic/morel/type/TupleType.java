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

import com.google.common.collect.ImmutableList;

import net.hydromatic.morel.ast.Op;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** The type of a tuple value. */
public class TupleType extends BaseType {
  public final List<Type> argTypes;

  TupleType(String description, ImmutableList<Type> argTypes) {
    super(Op.TUPLE_TYPE, description);
    this.argTypes = Objects.requireNonNull(argTypes);
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  public Type copy(TypeSystem typeSystem, Function<Type, Type> transform) {
    int differenceCount = 0;
    final ImmutableList.Builder<Type> argTypes2 = ImmutableList.builder();
    for (Type argType : argTypes) {
      final Type argType2 = argType.copy(typeSystem, transform);
      if (argType != argType2) {
        ++differenceCount;
      }
      argTypes2.add(argType2);
    }
    return differenceCount == 0
        ? this
        : new TupleType(description, argTypes2.build());
  }
}

// End TupleType.java
