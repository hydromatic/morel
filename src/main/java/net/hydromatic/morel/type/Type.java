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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.function.UnaryOperator;
import net.hydromatic.morel.ast.Op;

/** Type. */
public interface Type {
  /**
   * Description of the type, e.g. "{@code int}", "{@code int -> int}", "{@code
   * NONE | SOME of 'a}".
   */
  Key key();

  /**
   * Key of the type.
   *
   * <p>Often the same as {@link #key()}, but an exception is datatype. For
   * example, datatype "{@code 'a option}" has moniker and name "{@code option}"
   * and description "{@code NONE | SOME of 'a}".
   *
   * <p>Use the description if you are looking for a type that is structurally
   * equivalent. Use the moniker to identify it when printing.
   */
  default String moniker() {
    return key().toString();
  }

  /** Type operator. */
  Op op();

  /**
   * Copies this type, applying a given transform to component types, and
   * returning the original type if the component types are unchanged.
   */
  Type copy(TypeSystem typeSystem, UnaryOperator<Type> transform);

  <R> R accept(TypeVisitor<R> typeVisitor);

  /**
   * Returns a copy of this type, specialized by substituting type parameters.
   */
  default Type substitute(TypeSystem typeSystem, List<? extends Type> types) {
    if (types.isEmpty()) {
      return this;
    }
    return accept(
        new TypeShuttle(typeSystem) {
          @Override
          public Type visit(TypeVar typeVar) {
            return types.get(typeVar.ordinal);
          }
        });
  }

  /**
   * Returns whether this type is progressive.
   *
   * <p>Progressive types are records, but can have additional fields each time
   * you look.
   *
   * <p>The "file" value is an example.
   */
  default boolean isProgressive() {
    return false;
  }

  /**
   * Whether this type has a small, fixed set of instances. True for {@code
   * bool}, data types on finite types.
   */
  default boolean isFinite() {
    return false;
  }

  /** Structural identifier of a type. */
  abstract class Key {
    public final Op op;

    /** Creates a key. */
    protected Key(Op op) {
      this.op = requireNonNull(op);
    }

    /**
     * Returns a description of this key.
     *
     * <p>The default implementation calls {@link #describe(StringBuilder, int,
     * int)}, but subclasses may override to provide a more efficient
     * implementation.
     */
    @Override
    public String toString() {
      return describe(new StringBuilder(), 0, 0).toString();
    }

    /** Writes a description of this key to a string builder. */
    abstract StringBuilder describe(StringBuilder buf, int left, int right);

    /**
     * Converts this key to a type, and ensures that it is registered in the
     * type system.
     */
    public abstract Type toType(TypeSystem typeSystem);

    /**
     * If this is a type variable {@code ordinal}, returns the {@code ordinal}th
     * type in the list, otherwise this.
     */
    Key substitute(List<? extends Type> types) {
      return this;
    }

    /** Copies this key, applying a transform to constituent keys. */
    Key copy(UnaryOperator<Key> transform) {
      return this;
    }
  }
}

// End Type.java
