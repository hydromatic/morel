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

import java.util.function.UnaryOperator;
import net.hydromatic.morel.ast.Op;

/** The type of a function value. */
public class FnType extends BaseType {
  public final Type paramType;
  public final Type resultType;

  FnType(Type paramType, Type resultType) {
    super(Op.FUNCTION_TYPE);
    this.paramType = paramType;
    this.resultType = resultType;
  }

  public Key key() {
    return Keys.fn(paramType.key(), resultType.key());
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  @Override
  public FnType copy(TypeSystem typeSystem, UnaryOperator<Type> transform) {
    final Type paramType2 = paramType.copy(typeSystem, transform);
    final Type resultType2 = resultType.copy(typeSystem, transform);
    return paramType2 == paramType && resultType2 == resultType
        ? this
        : typeSystem.fnType(paramType2, resultType2);
  }
}

// End FnType.java
