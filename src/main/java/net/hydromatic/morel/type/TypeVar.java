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
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import net.hydromatic.morel.ast.Op;

import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/** Type variable (e.g. {@code 'a}). */
public class TypeVar implements Type {
  private static final char[] ALPHAS =
      "abcdefghijklmnopqrstuvwxyz".toCharArray();

  private static final LoadingCache<Integer, String> NAME_CACHE =
      CacheBuilder.newBuilder().build(CacheLoader.from(TypeVar::name));

  final int ordinal;

  /** Creates a type variable with a given ordinal.
   *
   * <p>TypeVar.of(0) returns "'a"; TypeVar.of(1) returns "'b", etc. */
  public TypeVar(int ordinal) {
    Preconditions.checkArgument(ordinal >= 0);
    this.ordinal = ordinal;
  }

  /** Returns a string for debugging; see also {@link #description()}. */
  @Override public String toString() {
    return "'#" + ordinal;
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  /** Generates a name for a type variable.
   *
   * <p>0 &rarr; 'a, 1 &rarr; 'b, 26 &rarr; 'z, 27 &rarr; 'ba, 28 &rarr; 'bb,
   * 675 &rarr; 'zz, 676 &rarr; 'baa, etc. (Think of it is a base 26 number,
   * with "a" as 0, "z" as 25.) */
  private static String name(int i) {
    if (i < 0) {
      throw new IllegalArgumentException();
    }
    final StringBuilder s = new StringBuilder();
    for (;;) {
      final int mod = i % 26;
      s.append(ALPHAS[mod]);
      i /= 26;
      if (i == 0) {
        return s.append("'").reverse().toString();
      }
    }
  }

  @Override public String description() {
    try {
      return NAME_CACHE.get(ordinal);
    } catch (ExecutionException e) {
      throw new RuntimeException(e.getCause());
    }
  }

  @Override public Op op() {
    return Op.TY_VAR;
  }

  public Type copy(TypeSystem typeSystem, Function<Type, Type> transform) {
    return transform.apply(this);
  }
}

// End TypeVar.java
