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

import static net.hydromatic.morel.util.Static.transform;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;

/** Shuttle that keeps an environment of what variables are in scope. */
abstract class EnvVisitor extends Visitor {
  final TypeSystem typeSystem;
  final Environment env;
  final Deque<FromContext> fromStack;

  /** Creates an EnvVisitor. */
  protected EnvVisitor(
      TypeSystem typeSystem, Environment env, Deque<FromContext> fromStack) {
    this.typeSystem = typeSystem;
    this.env = env;
    this.fromStack = fromStack;
  }

  /** Creates a visitor the same as this but with a new environment. */
  protected abstract EnvVisitor push(Environment env);

  /** Creates a visitor the same as this but overriding a binding. */
  protected EnvVisitor bind(Binding binding) {
    return push(env.bind(binding));
  }

  /** Creates a visitor the same as this but with overriding bindings. */
  protected EnvVisitor bind(Iterable<Binding> bindingList) {
    // The "env2 == env" check is an optimization.
    // If you remove it, this method will have the same effect, just slower.
    final Environment env2 = env.bindAll(bindingList);
    if (env2 != env) {
      return push(env2);
    }
    return this;
  }

  @Override
  protected void visit(Core.Fn fn) {
    fn.idPat.accept(this);
    fn.exp.accept(bind(Binding.of(fn.idPat)));
  }

  @Override
  protected void visit(Core.Match match) {
    final List<Binding> bindings = new ArrayList<>();
    match.pat.accept(this);
    Compiles.bindPattern(typeSystem, bindings, match.pat);
    match.exp.accept(bind(bindings));
  }

  @Override
  protected void visit(Core.Let let) {
    let.decl.accept(this);
    final List<Binding> bindings = new ArrayList<>();
    Compiles.bindPattern(typeSystem, bindings, let.decl);
    let.exp.accept(bind(bindings));
  }

  @Override
  protected void visit(Core.Local local) {
    final List<Binding> bindings = new ArrayList<>();
    Compiles.bindDataType(typeSystem, bindings, local.dataType);
    local.exp.accept(bind(bindings));
  }

  @Override
  protected void visit(Core.RecValDecl recValDecl) {
    final List<Binding> bindings = new ArrayList<>();
    recValDecl.list.forEach(
        decl -> Compiles.bindPattern(typeSystem, bindings, decl.pat));
    final EnvVisitor v2 = bind(bindings);
    recValDecl.list.forEach(v2::accept);
  }

  @Override
  protected void visit(Core.From from) {
    List<Binding> bindings = ImmutableList.of();
    for (Core.FromStep step : from.steps) {
      visitStep(step, bindings);
      bindings = step.bindings;
    }
  }

  public void visitStep(Core.FromStep step, List<Binding> bindings) {
    try {
      fromStack.push(new FromContext(this, step));
      step.accept(bind(bindings));
    } finally {
      fromStack.pop();
    }
  }

  @Override
  protected void visit(Core.Aggregate aggregate) {
    // Aggregates need an environment that includes the group keys.
    // For example,
    //   from (i, j) in [(1, 2), (2, 3)]
    //     group k = i + 2
    //     compute fn list => List.size list + j of i + j
    // the aggregate "fn list => List.size list + j" needs an environment [k];
    // the argument "i + j" needs an environment [i, j].
    EnvVisitor v2 = fromStack.element().visitor;
    Core.Group group = (Core.Group) fromStack.element().step;
    EnvVisitor v3 = v2.bind(transform(group.groupExps.keySet(), Binding::of));
    aggregate.aggregate.accept(v3);
    if (aggregate.argument != null) {
      aggregate.argument.accept(this);
    }
  }

  /**
   * Where we are in an iteration through the steps of a {@code from}. Allows
   * the step handlers to retrieve the original environment and make a custom
   * environment for each step (or part of a step).
   */
  public static class FromContext {
    final EnvVisitor visitor;
    final Core.FromStep step;

    FromContext(EnvVisitor visitor, Core.FromStep step) {
      this.visitor = visitor;
      this.step = step;
    }
  }
}

// End EnvVisitor.java
