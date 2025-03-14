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

import com.google.common.collect.ImmutableSortedMap;
import java.util.Locale;
import java.util.SortedMap;
import java.util.function.UnaryOperator;
import net.hydromatic.morel.ast.Op;

/** Primitive type. */
public enum PrimitiveType implements RecordLikeType {
  BOOL,
  CHAR,
  INT,
  REAL,
  STRING,
  UNIT {
    @Override
    public SortedMap<String, Type> argNameTypes() {
      // "unit" behaves like a record/tuple type with no fields
      return ImmutableSortedMap.of();
    }

    @Override
    public Type argType(int i) {
      throw new IndexOutOfBoundsException();
    }
  };

  /** The name in the language, e.g. {@code bool}. */
  public final String moniker = name().toLowerCase(Locale.ROOT);

  @Override
  public String toString() {
    return moniker;
  }

  @Override
  public Key key() {
    return Keys.name(moniker);
  }

  @Override
  public Op op() {
    return Op.ID;
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  @Override
  public boolean isFinite() {
    return this == BOOL || this == UNIT;
  }

  @Override
  public PrimitiveType copy(
      TypeSystem typeSystem, UnaryOperator<Type> transform) {
    return this;
  }

  @Override
  public SortedMap<String, Type> argNameTypes() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Type argType(int i) {
    throw new UnsupportedOperationException();
  }
}

// End PrimitiveType.java
