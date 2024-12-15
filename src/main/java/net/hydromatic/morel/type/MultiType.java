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

import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.function.UnaryOperator;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.compile.BuiltIn;

/**
 * Overloaded type.
 *
 * <p>Not really a type, just a way for a {@link BuiltIn#typeFunction} to
 * indicate that it is overloaded. An overloaded function has a type that is a
 * {@link MultiType}. Each of those types must be a function type and must
 * differ on the argument type.
 *
 * <p>For example, {@code int -> int} and {@code int * string -> real} is a good
 * pair of overloads. {@code int -> int} and {@code int -> int -> string} is not
 * a good pair of overloads because both have {@code int} as the argument type.
 */
public class MultiType implements Type {
  public final List<Type> types;

  MultiType(Iterable<? extends Type> types) {
    this.types = ImmutableList.copyOf(types);
  }

  @Override
  public Key key() {
    return Keys.multi(transformEager(types, Type::key));
  }

  @Override
  public Op op() {
    return Op.MULTI_TYPE;
  }

  @Override
  public Type copy(TypeSystem typeSystem, UnaryOperator<Type> transform) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <R> R accept(TypeVisitor<R> typeVisitor) {
    throw new UnsupportedOperationException();
  }
}

// End MultiType.java
