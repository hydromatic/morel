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

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.util.Ord.forEachIndexed;

import java.util.List;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.util.MapList;

/**
 * Base class for types that accept type parameters.
 *
 * <p>These types have not just names but also monikers. For example, the
 * datatype named {@code option} has instantiations whose monikers include
 * {@code int option}, {@code (string * bool) option}, and {@code 'b option}.
 */
public abstract class ParameterizedType extends BaseType implements NamedType {
  public final String name;
  public final String moniker;
  public final List<Type> parameterTypes;

  /** Creates a ParameterizedType. */
  ParameterizedType(Op op, String name, String moniker, int parameterCount) {
    super(op);
    this.name = requireNonNull(name);
    this.moniker = requireNonNull(moniker);
    this.parameterTypes = MapList.of(parameterCount, TypeVar::new);
  }

  public String name() {
    return name;
  }

  @Override
  public String moniker() {
    return moniker;
  }

  @Override
  public String toString() {
    return moniker;
  }

  static String computeMoniker(String name, List<? extends Type> typeVars) {
    if (typeVars.isEmpty()) {
      return name;
    }
    final StringBuilder b = new StringBuilder();
    if (typeVars.size() > 1) {
      b.append('(');
    }
    forEachIndexed(
        typeVars,
        (t, i) -> {
          if (i > 0) {
            b.append(",");
          }
          if (t instanceof TupleType) {
            b.append('(').append(t.moniker()).append(')');
          } else {
            b.append(t.moniker());
          }
        });
    if (typeVars.size() > 1) {
      b.append(')');
    }
    return b.append(' ').append(name).toString();
  }
}

// End ParameterizedType.java
