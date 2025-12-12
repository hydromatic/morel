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
package net.hydromatic.morel.compile;

import com.google.common.collect.ImmutableSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.function.Consumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.type.TypeSystem;

/** Finds free variables in an expression. */
class FreeFinder extends EnvVisitor {
  final Consumer<Core.NamedPat> consumer;

  protected FreeFinder(
      TypeSystem typeSystem,
      Environment env,
      Deque<FromContext> fromStack,
      Consumer<Core.NamedPat> consumer) {
    super(typeSystem, env, fromStack);
    this.consumer = consumer;
  }

  /** Finds the free variables in an expression. */
  public static Set<Core.NamedPat> freePats(
      TypeSystem typeSystem, Core.Exp exp) {
    final ImmutableSet.Builder<Core.NamedPat> set = ImmutableSet.builder();
    final Environment env = Environments.empty();
    exp.accept(new FreeFinder(typeSystem, env, new ArrayDeque<>(), set::add));
    return set.build();
  }

  @Override
  protected EnvVisitor push(Environment env) {
    return new FreeFinder(typeSystem, env, fromStack, consumer);
  }

  @Override
  protected void visit(Core.Id id) {
    if (env.getOpt(id.idPat) == null) {
      consumer.accept(id.idPat);
    }
  }
}

// End FreeFinder.java
