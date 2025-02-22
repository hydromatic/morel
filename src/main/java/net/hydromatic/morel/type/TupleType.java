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

import static java.lang.String.format;
import static net.hydromatic.morel.util.Pair.allMatch;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.transform;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.util.List;
import java.util.SortedMap;
import java.util.function.UnaryOperator;
import net.hydromatic.morel.ast.Op;

/** The type of a tuple value. */
public class TupleType extends BaseType implements RecordLikeType {
  private static final IntStringCache INT_STRING_CACHE = new IntStringCache();

  public final List<Type> argTypes;

  TupleType(List<? extends Type> argTypes) {
    super(Op.TUPLE_TYPE);
    this.argTypes = ImmutableList.copyOf(argTypes);
  }

  @Override
  public SortedMap<String, Type> argNameTypes() {
    final ImmutableSortedMap.Builder<String, Type> map =
        ImmutableSortedMap.orderedBy(RecordType.ORDERING);
    forEach(argNames(), argTypes, map::put);
    return map.build();
  }

  @Override
  public List<String> argNames() {
    return ordinalNames(argTypes.size());
  }

  @Override
  public List<Type> argTypes() {
    return argTypes;
  }

  @Override
  public Type argType(int i) {
    return argTypes.get(i);
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  public Key key() {
    return Keys.record(recordMap(transform(argTypes, Type::key)));
  }

  @Override
  public TupleType copy(TypeSystem typeSystem, UnaryOperator<Type> transform) {
    int differenceCount = 0;
    final ImmutableList.Builder<Type> argTypes2 = ImmutableList.builder();
    for (Type argType : argTypes) {
      final Type argType2 = transform.apply(argType);
      if (argType != argType2) {
        ++differenceCount;
      }
      argTypes2.add(argType2);
    }
    return differenceCount == 0 ? this : new TupleType(argTypes2.build());
  }

  @Override
  public boolean specializes(Type type) {
    return type instanceof TupleType
            && argTypes.size() == ((TupleType) type).argTypes.size()
            && allMatch(
                argTypes, ((TupleType) type).argTypes, Type::specializes)
        || type instanceof TypeVar;
  }

  /** Returns a list of strings ["1", ..., "size"]. */
  public static List<String> ordinalNames(int size) {
    return INT_STRING_CACHE.subList(1, size + 1);
  }

  /**
   * Given a list of types [t1, t2, ..., tn] returns a sorted map ["1" : t1, "2"
   * : t2, ... "n" : tn].
   */
  static <E> ImmutableSortedMap<String, E> recordMap(
      List<? extends E> argTypes) {
    final ImmutableSortedMap.Builder<String, E> b =
        ImmutableSortedMap.orderedBy(RecordType.ORDERING);
    forEach(ordinalNames(argTypes.size()), argTypes, b::put);
    return b.build();
  }

  /**
   * Cache of the string representations of integers.
   *
   * <p>Ensures that we do not continually re-compute the strings, and provides
   * lists of the first N integers (0-based or 1-based) as needed.
   */
  private static class IntStringCache {
    /** Marked volatile to ensure that the cache is not optimized away. */
    volatile ImmutableList<String> list =
        ImmutableList.of(
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12",
            "13", "14", "15");

    /** Returns a list of strings that is at least {@code minimumSize} long. */
    ImmutableList<String> ensure(int minimumSize) {
      ImmutableList<String> newList = list;
      for (; ; ) {
        if (newList.size() >= minimumSize) {
          return newList;
        }
        // Resize to a power of 2 that is at least 8 larger than the current
        // size, and at least minimumSize. Since current size is a power of 2,
        // it will typically double each time.
        final int minimumSize2 = Math.max(newList.size() + 8, minimumSize);
        final int newSize = Integer.highestOneBit(minimumSize2 * 2 - 1);
        assert newSize > newList.size()
            : format(
                "newSize = %d, newList.size() = %d, minimumSize2 = %d",
                newSize, newList.size(), minimumSize2);
        ImmutableList.Builder<String> b =
            ImmutableList.builderWithExpectedSize(newSize);
        b.addAll(newList);
        for (int i = newList.size(); i < newSize; i++) {
          b.add(Integer.toString(i));
        }
        newList = list = b.build();
      }
    }

    List<String> subList(int start, int end) {
      return ensure(end).subList(start, end);
    }
  }
}

// End TupleType.java
