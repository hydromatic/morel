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
package net.hydromatic.sml.compile;

import net.hydromatic.sml.type.PrimitiveType;
import net.hydromatic.sml.type.Type;
import net.hydromatic.sml.type.TypeSystem;

import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Built-in constants and functions. */
public enum BuiltIn {
  /** Literal "true", of type "bool". */
  TRUE(typeSystem -> PrimitiveType.BOOL),
  /** Literal "false", of type "bool". */
  FALSE(typeSystem -> PrimitiveType.BOOL),
  /** Function "map", of type "('a -> b') -> 'a list -> 'b list". */
  MAP(typeSystem -> {
    final Type alpha = typeSystem.typeVariable(0);
    final Type beta = typeSystem.typeVariable(1);
    return typeSystem.fnType(
        typeSystem.fnType(typeSystem.fnType(alpha, beta),
            typeSystem.listType(alpha)),
        typeSystem.listType(beta));
  }),
  /** Function "not", of type "bool -> bool". */
  NOT(typeSystem -> typeSystem.fnType(PrimitiveType.BOOL, PrimitiveType.BOOL)),
  /** Function "abs", of type "int -> int". */
  ABS(typeSystem -> typeSystem.fnType(PrimitiveType.INT, PrimitiveType.INT));

  /** The name as it appears in ML's symbol table. */
  public final String mlName = name().toLowerCase(Locale.ROOT);

  /** Derives a type, in a particular type system, for this constant or
   * function. */
  public final Function<TypeSystem, Type> typeFunction;

  BuiltIn(Function<TypeSystem, Type> typeFunction) {
    this.typeFunction = typeFunction;
  }

  /** Calls a consumer once per value. */
  public static void forEachType(TypeSystem typeSystem,
      BiConsumer<String, Type> consumer) {
    for (BuiltIn builtIn : values()) {
      consumer.accept(builtIn.mlName, builtIn.typeFunction.apply(typeSystem));
    }
  }
}

// End BuiltIn.java
