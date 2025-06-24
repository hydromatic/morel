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
    Compiles.acceptBinding(typeSystem, match.pat, bindings);
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
    recValDecl.list.forEach(decl -> Compiles.acceptBinding(decl.pat, bindings));
    final EnvVisitor v2 = bind(bindings);
    recValDecl.list.forEach(v2::accept);
  }

  @Override
  protected void visit(Core.From from) {
    Core.StepEnv env = Core.StepEnv.EMPTY;
    for (Core.FromStep step : from.steps) {
      visitStep(step, env);
      env = step.env;
    }
  }

  public void visitStep(Core.FromStep step, Core.StepEnv stepEnv) {
    try {
      fromStack.push(new FromContext(this, step, stepEnv));
      step.accept(bind(stepEnv.bindings));
    } finally {
      fromStack.pop();
    }
  }

  @Override
  protected void visit(Core.Scan scan) {
    scan.pat.accept(this);
    scan.exp.accept(this);
    if (scan.condition != null) {
      scan.condition.accept(bind(scan.env.bindings));
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
    aggregate.aggregate.accept(push(aggEnv(false)));
    if (aggregate.argument != null) {
      aggregate.argument.accept(push(aggEnv(true)));
    }
  }

  /**
   * Returns an environment for aggregate function or its argument.
   *
   * @param includeInput Whether to include input variables
   */
  private Environment aggEnv(boolean includeInput) {
    final FromContext fromContext = fromStack.element();
    final Core.Group group = (Core.Group) fromContext.step;
    Environment env = fromContext.visitor.env;
    if (includeInput) {
      env = env.bindAll(fromContext.stepEnv.bindings);
    }
    return env.bindAll(transform(group.groupExps.keySet(), Binding::of));
  }

  /**
   * Where we are in an iteration through the steps of a {@code from}. Allows
   * the step handlers to retrieve the original environment and make a custom
   * environment for each step (or part of a step).
   */
  public static class FromContext {
    final EnvVisitor visitor;
    final Core.FromStep step;
    /** Environment produced by previous step. */
    final Core.StepEnv stepEnv;

    FromContext(EnvVisitor visitor, Core.FromStep step, Core.StepEnv stepEnv) {
      this.visitor = visitor;
      this.step = step;
      this.stepEnv = stepEnv;
    }
  }
}

// End EnvVisitor.java
