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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.PairList;

/** Record type. */
public class RecordType extends BaseType implements RecordLikeType {
  public final SortedMap<String, Type> argNameTypes;

  RecordType(SortedMap<String, Type> argNameTypes) {
    super(Op.RECORD_TYPE);
    this.argNameTypes = ImmutableSortedMap.copyOfSorted(argNameTypes);
    checkArgument(argNameTypes.comparator() == ORDERING);
  }

  @Override
  public SortedMap<String, Type> argNameTypes() {
    return argNameTypes;
  }

  @Override
  public Type argType(int i) {
    // No copy is made: values() is already a list.
    return ImmutableList.copyOf(argNameTypes.values()).get(i);
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  public Key key() {
    return Keys.record(Keys.toKeys(argNameTypes));
  }

  @Override
  public RecordType copy(TypeSystem typeSystem, UnaryOperator<Type> transform) {
    int differenceCount = 0;
    final PairList<String, Type> argNameTypes2 = PairList.of();
    for (Map.Entry<String, Type> entry : argNameTypes.entrySet()) {
      final Type type = entry.getValue();
      final Type type2 = type.copy(typeSystem, transform);
      if (type != type2) {
        ++differenceCount;
      }
      argNameTypes2.add(entry.getKey(), type2);
    }
    return differenceCount == 0
        ? this
        : (RecordType) typeSystem.recordType(argNameTypes2);
  }

  @Override
  public boolean specializes(Type type) {
    return type instanceof RecordType
            && argNameTypes.size() == ((RecordType) type).argNameTypes.size()
            && argNameTypes
                .keySet()
                .equals(((RecordType) type).argNameTypes.keySet())
            && Pair.allMatch(
                argNameTypes.values(),
                ((RecordType) type).argNameTypes.values(),
                Type::specializes)
        || type instanceof TypeVar;
  }

  /**
   * Ordering that compares integer values numerically, string values
   * lexicographically, and integer values before string values.
   *
   * <p>Thus: 2, 22, 202, a, a2, a202, a22.
   */
  public static final Ordering<String> ORDERING =
      Ordering.from(RecordType::compareNames);

  /** Creates a constant map, sorted by {@link #ORDERING}. */
  @SuppressWarnings("unchecked")
  public static <V> SortedMap<String, V> map(
      String name, V v0, Object... entries) {
    checkArgument(entries.length % 2 == 0);
    final ImmutableSortedMap.Builder<String, V> builder =
        ImmutableSortedMap.orderedBy(ORDERING);
    builder.put(name, v0);
    for (int i = 0; i < entries.length; i += 2) {
      builder.put((String) entries[i], (V) entries[i + 1]);
    }
    return builder.build();
  }

  /** Creates a mutable map, sorted by {@link #ORDERING}. */
  public static <V> NavigableMap<String, V> mutableMap() {
    return new TreeMap<>(ORDERING);
  }

  /** Helper for {@link #ORDERING}. */
  public static int compareNames(String o1, String o2) {
    int i1 = parseInt(o1);
    int i2 = parseInt(o2);
    int c = Integer.compare(i1, i2);
    if (c != 0) {
      return c;
    }
    return o1.compareTo(o2);
  }

  /**
   * Parses a string that contains an integer value; returns {@link
   * Integer#MAX_VALUE} if the string does not contain an integer, or if the
   * value is less than or equal to zero, or if the string starts with '0', or
   * if the value is greater than or equal to 1 billion.
   *
   * <p>This approach is much faster for our purposes than {@link
   * Integer#parseInt(String)}, which has to create and throw an exception if
   * the value is not an integer.
   */
  private static int parseInt(String s) {
    final int length = s.length();
    if (length > 9) {
      // We treat values that are 1 billion (1,000,000,000) or higher as if they
      // are Integer.MAX_VALUE (2,147,483,648). Therefore, we do not need to
      // check for overflow in the loop below.
      return Integer.MAX_VALUE;
    }
    int n = 0;
    for (int i = 0; i < length; i++) {
      char c = s.charAt(i);
      if (i == 0 && c == '0') {
        // We do not regard '0' or '01' or '007' as positive integer values.
        return Integer.MAX_VALUE;
      }
      if (c < '0' || c > '9') {
        return Integer.MAX_VALUE;
      }
      int digit = c - '0';
      n = n * 10 + digit;
    }
    return n;
  }
}

// End RecordType.java
