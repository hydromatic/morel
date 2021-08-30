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

import java.util.List;
import java.util.function.UnaryOperator;

/** Placeholder for a type that is being recursively defined.
 *
 * <p>For example, while defining datatype "list" as follows,
 *
 * <blockquote>
 *   <code>datatype 'a list = NIL | CONS of ('a, 'a list)</code>
 * </blockquote>
 *
 * <p>we define a temporary type "list", it is used in {@code CONS}, and
 * later we convert it to the real data type "list".
 */
public class TemporaryType extends DataType {
  TemporaryType(String name, List<? extends Type> parameterTypes) {
    super(Op.TEMPORARY_DATA_TYPE, name, Keys.name(name), parameterTypes,
        ImmutableSortedMap.of());
  }

  @Override public TemporaryType copy(TypeSystem typeSystem,
      UnaryOperator<Type> transform) {
    return (TemporaryType) transform.apply(this);
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    throw new UnsupportedOperationException();
  }

  @Override protected Type substitute2(TypeSystem typeSystem,
      List<? extends Type> types, TypeSystem.Transaction transaction) {
    return typeSystem.temporaryType(name, types, transaction, false);
  }
}

// End TemporaryType.java
