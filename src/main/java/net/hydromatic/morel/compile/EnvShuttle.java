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

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;

import static net.hydromatic.morel.ast.CoreBuilder.core;

/**
 * Shuttle that keeps an environment of what variables are in scope.
 */
abstract class EnvShuttle extends Shuttle {
  final Environment env;

  /** Creates an EnvShuttle. */
  protected EnvShuttle(TypeSystem typeSystem, Environment env) {
    super(typeSystem);
    this.env = env;
  }

  /** Creates a shuttle the same as this but overriding a binding. */
  protected abstract EnvShuttle bind(Binding binding);

  /** Creates a shuttle the same as this but with overriding bindings. */
  protected abstract EnvShuttle bind(List<Binding> bindingList);

  @Override protected Core.Fn visit(Core.Fn fn) {
    final Core.IdPat idPat2 = fn.idPat.accept(this);
    final Binding binding = Binding.of(fn.idPat);
    return fn.copy(idPat2, fn.exp.accept(bind(binding)));
  }

  @Override protected Core.Match visit(Core.Match match) {
    final List<Binding> bindings = new ArrayList<>();
    final Core.Pat pat2 = match.pat.accept(this);
    Compiles.bindPattern(typeSystem, bindings, pat2);
    return core.match(pat2, match.exp.accept(bind(bindings)), match.pos);
  }

  @Override public Core.Exp visit(Core.Let let) {
    final List<Binding> bindings = new ArrayList<>();
    Compiles.bindPattern(typeSystem, bindings, let.decl);
    return let.copy(let.decl.accept(this), let.exp.accept(bind(bindings)));
  }

  @Override public Core.Exp visit(Core.Local local) {
    final List<Binding> bindings = new ArrayList<>();
    Compiles.bindDataType(typeSystem, bindings, local.dataType);
    return local.copy(local.dataType, local.exp.accept(bind(bindings)));
  }

  @Override public Core.RecValDecl visit(Core.RecValDecl recValDecl) {
    final List<Binding> bindings = new ArrayList<>();
    recValDecl.list.forEach(decl ->
        Compiles.bindPattern(typeSystem, bindings, decl.pat));
    return recValDecl.copy(bind(bindings).visitList(recValDecl.list));
  }

  @Override public Core.Exp visit(Core.From from) {
    List<Binding> bindings = ImmutableList.of();
    final List<Core.FromStep> steps = new ArrayList<>();
    for (Core.FromStep step : from.steps) {
      final Core.FromStep step2 = step.accept(bind(bindings));
      steps.add(step2);
      bindings = step2.bindings;
    }

    return from.copy(typeSystem, env, steps);
  }

}

// End EnvShuttle.java
