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

import com.google.common.collect.ImmutableSortedMap;

import java.util.Locale;
import java.util.SortedMap;
import java.util.function.Function;

/** Primitive type. */
public enum PrimitiveType implements RecordLikeType {
  BOOL,
  CHAR,
  INT,
  REAL,
  STRING,
  UNIT {
    @Override public SortedMap<String, Type> argNameTypes() {
      // "unit" behaves like a record/tuple type with no fields
      return ImmutableSortedMap.of();
    }
  };

  /** The name in the language, e.g. {@code bool}. */
  public final String moniker = name().toLowerCase(Locale.ROOT);

  @Override public String toString() {
    return moniker;
  }

  public String description() {
    return moniker;
  }

  public Op op() {
    return Op.ID;
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  public Type copy(TypeSystem typeSystem, Function<Type, Type> transform) {
    return transform.apply(this);
  }

  @Override public SortedMap<String, Type> argNameTypes() {
    throw new UnsupportedOperationException();
  }
}

// End PrimitiveType.java
