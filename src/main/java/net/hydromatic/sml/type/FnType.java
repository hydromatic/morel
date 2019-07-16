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

import net.hydromatic.sml.ast.Op;

import java.util.function.Function;

/** The type of a function value. */
public class FnType extends BaseType {
  public final Type paramType;
  public final Type resultType;

  FnType(String description, Type paramType, Type resultType) {
    super(Op.FUNCTION_TYPE, description);
    this.paramType = paramType;
    this.resultType = resultType;
  }

  public Type copy(TypeSystem typeSystem, Function<Type, Type> transform) {
    final Type paramType2 = paramType.copy(typeSystem, transform);
    final Type resultType2 = resultType.copy(typeSystem, transform);
    return paramType2 == paramType
        && resultType2 == resultType
        ? this
        : new FnType(description, paramType2, resultType2);
  }
}

// End FnType.java
