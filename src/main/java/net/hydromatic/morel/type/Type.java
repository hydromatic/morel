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

import java.util.List;
import java.util.function.UnaryOperator;

/** Type. */
public interface Type {
  /** Description of the type, e.g. "{@code int}", "{@code int -> int}",
   * "{@code NONE | SOME of 'a}". */
  Key key();

  /** Key of the type.
   *
   * <p>Often the same as {@link #key()}, but an exception is datatype.
   * For example, datatype "{@code 'a option}" has moniker and name
   * "{@code option}" and description "{@code NONE | SOME of 'a}".
   *
   * <p>Use the description if you are looking for a type that is structurally
   * equivalent. Use the moniker to identify it when printing. */
  default String moniker() {
    return key().moniker();
  }

  /** Type operator. */
  Op op();

  /** Copies this type, applying a given transform to component types,
   * and returning the original type if the component types are unchanged. */
  Type copy(TypeSystem typeSystem, UnaryOperator<Type> transform);

  <R> R accept(TypeVisitor<R> typeVisitor);

  /** Returns a copy of this type, specialized by substituting type
   * parameters. */
  default Type substitute(TypeSystem typeSystem, List<? extends Type> types,
      TypeSystem.Transaction transaction) {
    if (types.isEmpty()) {
      return this;
    }
    return accept(
        new TypeShuttle(typeSystem) {
          @Override public Type visit(TypeVar typeVar) {
            return types.get(typeVar.ordinal);
          }
        });
  }

  /** Structural identifier of a type. */
  interface Key {
    Type toType(TypeSystem typeSystem);

    default String moniker() {
      return describe(new StringBuilder(), 0, 0).toString();
    }

    StringBuilder describe(StringBuilder buf, int left, int right);
  }

  /** Definition of a type. */
  interface Def {
    StringBuilder describe(StringBuilder buf);

    DataType toType(TypeSystem typeSystem);
  }
}

// End Type.java
