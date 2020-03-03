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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;

import net.hydromatic.morel.ast.Op;

import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.function.Function;

/** The type of a record value. */
public class RecordType extends BaseType {
  public final SortedMap<String, Type> argNameTypes;

  RecordType(String description,
      ImmutableSortedMap<String, Type> argNameTypes) {
    super(Op.RECORD_TYPE, description);
    this.argNameTypes = Objects.requireNonNull(argNameTypes);
    Preconditions.checkArgument(argNameTypes.comparator() == ORDERING);
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  public Type copy(TypeSystem typeSystem, Function<Type, Type> transform) {
    int differenceCount = 0;
    final ImmutableSortedMap.Builder<String, Type> argNameTypes2 =
        ImmutableSortedMap.orderedBy(ORDERING);
    for (Map.Entry<String, Type> entry : argNameTypes.entrySet()) {
      final Type type = entry.getValue();
      final Type type2 = type.copy(typeSystem, transform);
      if (type != type2) {
        ++differenceCount;
      }
      argNameTypes2.put(entry.getKey(), type2);
    }
    return differenceCount == 0
        ? this
        : typeSystem.recordType(argNameTypes2.build());
  }

  /** Ordering that compares integer values numerically,
   * string values lexicographically,
   * and integer values before string values.
   *
   * <p>Thus: 2, 22, 202, a, a2, a202, a22. */
  public static final Ordering<String> ORDERING =
      Ordering.from(
          (o1, o2) -> {
            Integer i1 = parseInt(o1);
            Integer i2 = parseInt(o2);
            if (i1 == null && i2 == null) {
              return o1.compareTo(o2);
            }
            if (i1 != null && i2 != null) {
              return i1.compareTo(i2);
            }
            return i1 != null ? -1 : 1;
          });

  /** Parses a string that contains an integer value, or returns null if
   * the string does not contain an integer. */
  private static Integer parseInt(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}

// End RecordType.java
