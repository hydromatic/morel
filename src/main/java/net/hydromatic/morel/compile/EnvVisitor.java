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
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;

/**
 * Shuttle that keeps an environment of what variables are in scope.
 */
abstract class EnvVisitor extends Visitor {
  final TypeSystem typeSystem;
  final Environment env;

  /** Creates an EnvVisitor. */
  protected EnvVisitor(TypeSystem typeSystem, Environment env) {
    this.typeSystem = typeSystem;
    this.env = env;
  }

  /** Creates a shuttle the same as this but overriding a binding. */
  protected abstract EnvVisitor bind(Binding binding);

  /** Creates a shuttle the same as this but with overriding bindings. */
  protected abstract EnvVisitor bind(List<Binding> bindingList);

  @Override protected void visit(Core.Fn fn) {
    fn.idPat.accept(this);
    fn.exp.accept(bind(Binding.of(fn.idPat)));
  }

  @Override protected void visit(Core.Match match) {
    final List<Binding> bindings = new ArrayList<>();
    match.pat.accept(this);
    Compiles.bindPattern(typeSystem, bindings, match.pat);
    match.exp.accept(bind(bindings));
  }

  @Override protected void visit(Core.Let let) {
    let.decl.accept(this);
    final List<Binding> bindings = new ArrayList<>();
    Compiles.bindPattern(typeSystem, bindings, let.decl);
    let.exp.accept(bind(bindings));
  }

  @Override protected void visit(Core.Local local) {
    final List<Binding> bindings = new ArrayList<>();
    Compiles.bindDataType(typeSystem, bindings, local.dataType);
    local.exp.accept(bind(bindings));
  }

  @Override protected void visit(Core.From from) {
    final List<Binding> bindings = new ArrayList<>();
    from.sources.forEach((pat, source) -> {
      source.accept(bind(bindings));
      Compiles.bindPattern(typeSystem, bindings, pat);
      pat.accept(this);
    });

    for (Core.FromStep step : from.steps) {
      step.accept(bind(bindings));
      final List<Binding> previousBindings = ImmutableList.copyOf(bindings);
      bindings.clear();
      step.deriveOutBindings(previousBindings, Binding::of, bindings::add);
    }

    from.yieldExp.accept(bind(bindings));
  }
}

// End EnvVisitor.java
