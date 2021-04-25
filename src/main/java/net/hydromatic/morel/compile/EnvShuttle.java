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

import com.google.common.collect.ImmutableList;

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

  /** Creates a shuttle the same as this but with overriding bindings. */
  protected abstract EnvShuttle bind(List<Binding> bindingList);

  @Override protected Core.Match visit(Core.Match match) {
    final List<Binding> bindings = new ArrayList<>();
    final Core.Pat pat2 = match.pat.accept(this);
    pat2.accept(Compiles.binding(typeSystem, bindings));
    return core.match(pat2, match.e.accept(bind(bindings)));
  }

  @Override public Core.Exp visit(Core.Let let) {
    final List<Binding> bindings = new ArrayList<>();
    let.decl.accept(Compiles.binding(typeSystem, bindings));
    return let.copy(let.decl.accept(this), let.e.accept(bind(bindings)));
  }

  @Override public Core.Exp visit(Core.From from) {
    final List<Binding> bindings = new ArrayList<>();
    final Map<Core.Pat, Core.Exp> sources = new LinkedHashMap<>();
    from.sources.forEach((pat, source) -> {
      sources.put(pat, source.accept(bind(bindings)));
      pat.accept(Compiles.binding(typeSystem, bindings));
    });

    final List<Core.FromStep> steps = new ArrayList<>();
    for (Core.FromStep step : from.steps) {
      final Core.FromStep step2 = step.accept(bind(bindings));
      steps.add(step2);
      final List<Binding> previousBindings = ImmutableList.copyOf(bindings);
      bindings.clear();
      step2.deriveOutBindings(previousBindings, Binding::of, bindings::add);
    }

    final Core.Exp yieldExp2 = from.yieldExp.accept(bind(bindings));
    return from.copy(typeSystem, sources, steps, yieldExp2);
  }

}

// End EnvShuttle.java
