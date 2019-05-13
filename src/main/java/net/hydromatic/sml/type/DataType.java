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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;

import net.hydromatic.sml.ast.Op;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;

/** Algebraic type. */
public class DataType extends BaseType implements NamedType {
  public final String name;
  public final List<TypeVar> typeVars;
  public final SortedMap<String, Type> typeConstructors;

  DataType(String name, String description,
      ImmutableList<TypeVar> typeVars,
      ImmutableSortedMap<String, Type> typeConstructors) {
    super(Op.DATA_TYPE, description);
    this.name = Objects.requireNonNull(name);
    this.typeVars = Objects.requireNonNull(typeVars);
    this.typeConstructors = Objects.requireNonNull(typeConstructors);
    Preconditions.checkArgument(typeConstructors.comparator()
        == Ordering.natural());
  }

  public String name() {
    return name;
  }

  static String computeDescription(Map<String, Type> tyCons) {
    final StringBuilder buf = new StringBuilder("(");
    tyCons.forEach((tyConName, tyConType) -> {
      if (buf.length() > 1) {
        buf.append(" | ");
      }
      buf.append(tyConName);
      if (tyConType != DummyType.INSTANCE) {
        buf.append(" of ");
        buf.append(tyConType.description());
      }
    });
    return buf.append(")").toString();
  }
}

// End DataType.java
