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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;

import java.util.List;
import java.util.SortedMap;
import java.util.function.UnaryOperator;

import static net.hydromatic.morel.util.Static.transformEager;

import static com.google.common.base.Preconditions.checkArgument;

/** Algebraic type. */
public class DataType extends ParameterizedType {
  public final List<Type> arguments;
  public final SortedMap<String, Key> typeConstructors;

  /** Creates a DataType.
   *
   * <p>Called only from {@link TypeSystem#dataTypes(List)}.
   *
   * <p>If the {@code typeSystem} argument is specified, canonizes the types
   * inside type-constructors. This also allows temporary types (necessary while
   * creating self-referential data types) to be replaced with real DataType
   * instances.
   *
   * <p>During replacement, if a type matches {@code placeholderType} it is
   * replaced with {@code this}. This allows cyclic graphs to be copied. */
  DataType(String name, String moniker, List<? extends Type> arguments,
      SortedMap<String, Key> typeConstructors) {
    this(Op.DATA_TYPE, name, moniker, arguments, typeConstructors);
  }

  /** Called only from DataType constructor. */
  protected DataType(Op op, String name, String moniker,
      List<? extends Type> arguments, SortedMap<String, Key> typeConstructors) {
    super(op, name, moniker, arguments.size());
    this.arguments = ImmutableList.copyOf(arguments);
    this.typeConstructors = ImmutableSortedMap.copyOf(typeConstructors);
    checkArgument(typeConstructors.comparator() == null
        || typeConstructors.comparator() == Ordering.natural());
  }

  @Override public Key key() {
    return Keys.datatype(name, Keys.toKeys(arguments), typeConstructors);
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  public SortedMap<String, Type> typeConstructors(TypeSystem typeSystem) {
    return Maps.transformValues(typeConstructors,
        k -> k.copy(t -> t.substitute(arguments)).toType(typeSystem));
  }

  @Override public DataType copy(TypeSystem typeSystem,
      UnaryOperator<Type> transform) {
    final List<Type> arguments = transformEager(this.arguments, transform);
    if (arguments.equals(this.arguments)) {
      return this;
    }
    return new DataType(name, moniker, arguments, typeConstructors);
  }

  /** Writes out the definition of the datatype. For example,
   *
   * <pre>{@code
   * datatype ('a,'b) tree =
   *     Empty
   *   | Node of ('a,'b) tree * 'b * 'a * ('a,'b) tree
   * }</pre>
   */
  public StringBuilder describe(StringBuilder buf) {
    buf.append("datatype ")
        .append(moniker)
        .append(" = ");
    final int initialSize = buf.length();
    typeConstructors.forEach((name, typeKey) -> {
      if (buf.length() > initialSize) {
        buf.append(" | ");
      }
      buf.append(name);
      if (typeKey.op != Op.DUMMY_TYPE) {
        buf.append(" of ");
        typeKey.describe(buf, 0, 0);
      }
    });
    return buf;
  }
}

// End DataType.java
