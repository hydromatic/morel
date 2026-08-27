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

import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.function.UnaryOperator;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Op;

/**
 * Type that is an alias for another type.
 *
 * <p>For example, "{@code type point = real list}" creates a type "point" as an
 * alias for "real list".
 *
 * <p>An alias is a <em>type function</em>: a name, a list of head type
 * variables (its {@link #arguments}), and a body ({@link #type}). For example,
 * "{@code type 'a my_list = 'a list}" has one head type variable and body
 * "{@code 'a list}". Unlike a {@link DataType}, which is nominal, an alias is
 * transparent: it is applied by substituting its arguments into the body (e.g.
 * "{@code int my_list}" expands to "{@code int list}"), not by creating a
 * distinct type. The {@link #parameterTypes parameter count} records its arity.
 *
 * <p><b>Checked types.</b> An alias may carry {@code check} conditions (see
 * {@link #checks}), and is then a <em>checked type</em>: its base type plus a
 * predicate that every value of the type satisfies. For example "{@code type
 * nat = int check i => i >= 0}".
 *
 * <p>A checked type is erased. Its representation is that of the type it
 * abbreviates, and the condition does not survive {@link #unalias}, so
 * everything that examines a type structurally -- choosing an overload,
 * aggregating, printing -- behaves as it does for the base type.
 *
 * <p>Erasure is what makes <b>widening free and narrowing checked</b>. Using a
 * {@code nat} as an {@code int} needs no coercion and none is generated; the
 * condition is simply dropped, so "{@code n - 100}" is ordinary {@code int}
 * subtraction and has type {@code int}. The other direction can fail, and is
 * checked where the surface type is known.
 *
 * <p>The soundness argument is one invariant: <b>a condition is claimed only
 * where the type says so, and a check is inserted wherever a value flows into a
 * claim.</b> Everywhere else the name has reduced to the base type, so nothing
 * is claimed and nothing can be breached. {@link
 * net.hydromatic.morel.compile.Resolver} is where those checks are inserted --
 * at a binding, a parameter, an ascription, a conversion, a constructor, and
 * inside a composite value -- and raising is a check's only effect.
 */
public class AliasType extends ParameterizedType {
  public final Type type;
  public final List<Type> arguments;

  /**
   * Constraints on this type, one per {@code check} clause, each a function
   * from a value of the type to {@code bool}. Empty if the type is unchecked.
   *
   * <p>A constraint is part of the type's identity -- two checked types are the
   * same type when their conditions are textually equal -- but it does not
   * survive {@link #unalias}, so nothing that examines a type structurally sees
   * it.
   */
  public final List<Ast.Fn> checks;

  AliasType(String name, Type type, List<Type> arguments, List<Ast.Fn> checks) {
    super(
        Op.ALIAS_TYPE,
        name,
        moniker(name, type, arguments, checks),
        arguments.size());
    this.type = type;
    this.arguments = ImmutableList.copyOf(arguments);
    this.checks = ImmutableList.copyOf(checks);
  }

  /**
   * Returns how to write this type.
   *
   * <p>A named type is written by its name. One that is not named has only its
   * body and its conditions to be written by, so it is written in full.
   */
  private static String moniker(
      String name, Type type, List<Type> arguments, List<Ast.Fn> checks) {
    if (!name.isEmpty()) {
      return computeMoniker(name, arguments);
    }
    final StringBuilder b = new StringBuilder(type.moniker());
    checks.forEach(c -> c.appendMatchList(b, " check "));
    return b.toString();
  }

  @Override
  public Type unalias() {
    return type.unalias();
  }

  @Override
  public Key key() {
    return Keys.alias(
        name, type.key(), transformEager(arguments, Type::key), checks);
  }

  @Override
  public AliasType copy(TypeSystem typeSystem, UnaryOperator<Type> transform) {
    final Type type = transform.apply(this.type);
    final List<Type> arguments = transformEager(this.arguments, transform);
    if (type.equals(this.type) && arguments.equals(this.arguments)) {
      return this;
    }
    return (AliasType) key().substitute(arguments).toType(typeSystem);
  }

  @Override
  public Type arg(int i) {
    return arguments.get(i);
  }

  @Override
  public boolean containsAlias() {
    return true;
  }

  @Override
  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }
}

// End AliasType.java
