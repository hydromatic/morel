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
import static java.util.Objects.requireNonNull;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.UnaryOperator;
import net.hydromatic.morel.ast.Op;

/** Type variable (e.g. {@code 'a}). */
public class TypeVar implements Type {
  private static final char[] ALPHAS =
      "abcdefghijklmnopqrstuvwxyz".toCharArray();

  private static final LoadingCache<Integer, String> NAME_CACHE =
      CacheBuilder.newBuilder().build(CacheLoader.from(TypeVar::name));

  public final int ordinal;
  private final String name;

  /**
   * Creates a type variable with a given ordinal.
   *
   * <p>TypeVar.of(0) returns "'a"; TypeVar.of(1) returns "'b", etc.
   */
  public TypeVar(int ordinal) {
    checkArgument(ordinal >= 0);
    this.ordinal = ordinal;
    try {
      this.name = requireNonNull(NAME_CACHE.get(ordinal));
    } catch (ExecutionException e) {
      throw new RuntimeException(e.getCause());
    }
  }

  @Override
  public int hashCode() {
    return ordinal + 6563;
  }

  @Override
  public boolean equals(Object obj) {
    return obj == this
        || obj instanceof TypeVar && this.ordinal == ((TypeVar) obj).ordinal;
  }

  /** Returns a string for debugging. */
  @Override
  public String toString() {
    return name;
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  /**
   * Generates a name for a type variable.
   *
   * <p>0 &rarr; 'a, 1 &rarr; 'b, 26 &rarr; 'z, 27 &rarr; 'ba, 28 &rarr; 'bb,
   * 675 &rarr; 'zz, 676 &rarr; 'baa, etc. (Think of it is a base 26 number,
   * with "a" as 0, "z" as 25.)
   */
  static String name(int i) {
    if (i < 0) {
      throw new IllegalArgumentException();
    }
    final StringBuilder s = new StringBuilder();
    for (; ; ) {
      final int mod = i % 26;
      s.append(ALPHAS[mod]);
      i /= 26;
      if (i == 0) {
        return s.append("'").reverse().toString();
      }
    }
  }

  @Override
  public Key key() {
    return Keys.ordinal(ordinal);
  }

  @Override
  public Op op() {
    return Op.TY_VAR;
  }

  @Override
  public Type copy(TypeSystem typeSystem, UnaryOperator<Type> transform) {
    return transform.apply(this);
  }

  @Override
  public Type substitute(TypeSystem typeSystem, List<? extends Type> types) {
    return types.get(ordinal);
  }

  @Override
  public boolean specializes(Type type) {
    return type instanceof TypeVar;
  }
}

// End TypeVar.java
