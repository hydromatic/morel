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
import net.hydromatic.morel.util.Ord;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.function.UnaryOperator;

/** The type of a tuple value. */
public class TupleType extends BaseType implements RecordLikeType {
  private static final String[] INT_STRINGS =
      {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"};

  public final List<Type> argTypes;

  TupleType(ImmutableList<Type> argTypes) {
    super(Op.TUPLE_TYPE);
    this.argTypes = Objects.requireNonNull(argTypes);
  }

  @Override public SortedMap<String, Type> argNameTypes() {
    final ImmutableSortedMap.Builder<String, Type> map =
        ImmutableSortedMap.orderedBy(RecordType.ORDERING);
    Ord.forEach(argTypes, (t, i) -> map.put(Integer.toString(i + 1), t));
    return map.build();
  }

  @Override public Type argType(int i) {
    return argTypes.get(i);
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  public Key key() {
    return Keys.record(toArgNameTypes(argTypes));
  }

  @Override public TupleType copy(TypeSystem typeSystem,
      UnaryOperator<Type> transform) {
    int differenceCount = 0;
    final ImmutableList.Builder<Type> argTypes2 = ImmutableList.builder();
    for (Type argType : argTypes) {
      final Type argType2 = transform.apply(argType);
      if (argType != argType2) {
        ++differenceCount;
      }
      argTypes2.add(argType2);
    }
    return differenceCount == 0
        ? this
        : new TupleType(argTypes2.build());
  }

  /** Converts an integer to its string representation, using a cached value
   * if possible. */
  private static String str(int i) {
    return i >= 0 && i < INT_STRINGS.length ? INT_STRINGS[i]
        : Integer.toString(i);
  }

  /** Returns a list of strings ["1", ..., "size"]. */
  public static List<String> ordinalNames(int size) {
    return new AbstractList<String>() {
      public int size() {
        return size;
      }

      public String get(int index) {
        return str(index + 1);
      }
    };
  }

  /** Given a list of types [t1, t2, ..., tn] returns a sorted map ["1" : t1,
   * "2" : t2, ... "n" : tn]. */
  static ImmutableSortedMap<String, Type> toArgNameTypes(
      List<? extends Type> argTypes) {
    final ImmutableSortedMap.Builder<String, Type> b =
        ImmutableSortedMap.orderedBy(RecordType.ORDERING);
    Ord.forEach(argTypes, (t, i) ->
        b.put(Integer.toString(i + 1), t));
    return b.build();
  }
}

// End TupleType.java
