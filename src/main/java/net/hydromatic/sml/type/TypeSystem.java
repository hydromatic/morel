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

import net.hydromatic.sml.ast.Op;
import net.hydromatic.sml.util.Ord;
import net.hydromatic.sml.util.Pair;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A table that contains all types in use, indexed by their description (e.g.
 * "{@code int -> int}"). */
public class TypeSystem {
  private final Map<String, Type> typeByName = new HashMap<>();

  public TypeSystem() {
    for (PrimitiveType primitiveType : PrimitiveType.values()) {
      typeByName.put(primitiveType.description(), primitiveType);
    }
  }

  /** Looks up a primitive type. */
  public PrimitiveType primitiveType(String name) {
    return Objects.requireNonNull((PrimitiveType) typeByName.get(name));
  }

  /** Looks up a type by name. */
  public Type lookup(String name) {
    final Type type = typeByName.get(name);
    if (type == null) {
      throw new AssertionError("unknown type: " + name);
    }
    return type;
  }

  /** Looks up a type by name, returning null if not found. */
  public Type lookupOpt(String name) {
    return typeByName.get(name);
  }

  /** Creates a function type. */
  public Type fnType(Type paramType, Type resultType) {
    final String description =
        unparseList(new StringBuilder(), Op.FUNCTION_TYPE, 0, 0,
            Arrays.asList(paramType, resultType)).toString();
    return typeByName.computeIfAbsent(description,
        d -> new FnType(d, paramType, resultType));
  }

  /** Creates a tuple type. */
  public Type tupleType(List<? extends Type> argTypes) {
    final String description =
        unparseList(new StringBuilder(), Op.TIMES, 0, 0, argTypes).toString();
    return typeByName.computeIfAbsent(description,
        d -> new TupleType(d, ImmutableList.copyOf(argTypes)));
  }

  /** Creates a list type. */
  public Type listType(Type elementType) {
    final String description =
        unparse(new StringBuilder(), elementType, 0, Op.LIST.right)
            .append(" list")
            .toString();
    return typeByName.computeIfAbsent(description,
        d -> new ListType(d, elementType));
  }

  /** Creates a record type. (Or a tuple type if the fields are named "1", "2"
   * etc.; or "unit" if the field list is empty.) */
  public Type recordType(List<String> argNames, List<? extends Type> argTypes) {
    Preconditions.checkArgument(argNames.size() == argTypes.size());
    if (argNames.isEmpty()) {
      return PrimitiveType.UNIT;
    }
    final ImmutableSortedMap.Builder<String, Type> mapBuilder =
        ImmutableSortedMap.orderedBy(RecordType.ORDERING);
    Pair.forEach(argNames, argTypes, mapBuilder::put);
    final StringBuilder builder = new StringBuilder("{");
    final ImmutableSortedMap<String, Type> map = mapBuilder.build();
    map.forEach((name, type) -> {
      if (builder.length() > 1) {
        builder.append(", ");
      }
      builder.append(name).append(':').append(type.description());
    });
    if (areContiguousIntegers(map.keySet())) {
      return tupleType(ImmutableList.copyOf(map.values()));
    }
    final String description = builder.append('}').toString();
    return this.typeByName.computeIfAbsent(description,
        d -> new RecordType(d, map));
  }

  /** Returns whether the collection is ["1", "2", ... n]. */
  private boolean areContiguousIntegers(Iterable<String> strings) {
    int i = 1;
    for (String string : strings) {
      if (!string.equals(Integer.toString(i++))) {
        return false;
      }
    }
    return true;
  }

  private static StringBuilder unparseList(StringBuilder builder, Op op,
      int left, int right, List<? extends Type> argTypes) {
    Ord.forEach(argTypes, (e, i) -> {
      if (i == 0) {
        unparse(builder, e, left, op.left);
      } else {
        builder.append(op.padded);
        if (i < argTypes.size() - 1) {
          unparse(builder, e, op.right, op.left);
        } else {
          unparse(builder, e, op.right, right);
        }
      }
    });
    return builder;
  }

  private static StringBuilder unparse(StringBuilder builder, Type type,
      int left, int right) {
    final Op op = type.op();
    if (left > op.left || op.right < right) {
      return builder.append("(").append(type.description()).append(")");
    } else {
      return builder.append(type.description());
    }
  }
}

// End TypeSystem.java
