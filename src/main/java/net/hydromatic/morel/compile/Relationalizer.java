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
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.TypeSystem;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.List;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.compile.Resolver.append;

/**
 * Shuttle that converts calls to {@link BuiltIn#LIST_FILTER}
 * and {@link BuiltIn#LIST_MAP} into {@link Core.From} expressions.
 */
public class Relationalizer extends EnvShuttle {
  /** Private constructor. */
  private Relationalizer(TypeSystem typeSystem, Environment env) {
    super(typeSystem, env);
  }

  /** Creates a Relationalizer.
   *
   * <p>If {@code analysis} is null, no variables are inlined. */
  public static Relationalizer of(TypeSystem typeSystem, Environment env) {
    return new Relationalizer(typeSystem, env);
  }

  @Override protected EnvShuttle bind(Binding binding) {
    return new Relationalizer(typeSystem, env.bind(binding));
  }

  @Override protected Relationalizer bind(List<Binding> bindingList) {
    // The "env2 != env" check is an optimization. If you remove it, this method
    // will have the same effect, just slower.
    final Environment env2 = env.bindAll(bindingList);
    if (env2 != env) {
      return new Relationalizer(typeSystem, env2);
    }
    return this;
  }

  @Override protected Core.Exp visit(Core.Apply apply) {
    switch (apply.fn.op) {
    case APPLY:
      final Core.Apply apply2 = (Core.Apply) apply.fn;
      switch (apply2.fn.op) {
      case FN_LITERAL:
        final Core.Literal literal = (Core.Literal) apply2.fn;
        if (literal.value == BuiltIn.LIST_MAP) {
          // List.map f list
          //  =>
          // from e in list yield (f e)
          final Core.Exp f = apply2.arg;
          final FnType fnType = (FnType) f.type;
          final Core.From from = toFrom(apply.arg);
          // TODO: if the last step is a non-record yield, there is no
          // "defaultYieldExp", and therefore we cannot add another yield
          // step. We will have to inline the yield expression as a let.
          final Core.Yield yieldStep = core.yield_(typeSystem,
              core.apply(fnType.resultType, f,
                  core.defaultYieldExp(typeSystem,
                      from.initialBindings, from.steps)));
          return core.from(typeSystem, from.sources, from.initialBindings,
              append(from.steps, yieldStep));
        }
        if (literal.value == BuiltIn.LIST_FILTER) {
          // List.filter f list
          //  =>
          // from e in list where (f e)
          final Core.Exp f = apply2.arg;
          final FnType fnType = (FnType) f.type;
          final Core.From from = toFrom(apply.arg);
          final Core.Where whereStep =
              core.where(core.lastBindings(from.initialBindings, from.steps),
                  core.apply(fnType.resultType, f,
                      core.defaultYieldExp(typeSystem,
                          from.initialBindings, from.steps)));
          return core.from(typeSystem, from.sources, from.initialBindings,
              append(from.steps, whereStep));
        }
      }
    }
    return super.visit(apply);
  }

  private Core.From toFrom(Core.Exp exp) {
    if (exp instanceof Core.From) {
      return (Core.From) exp;
    } else {
      final ListType listType = (ListType) exp.type;
      final String name = typeSystem.nameGenerator.get();
      final Core.IdPat id =
          core.idPat(listType.elementType, name, typeSystem.nameGenerator);
      return core.from(typeSystem, ImmutableMap.of(id, exp),
          ImmutableList.of());
    }
  }
}

// End Relationalizer.java
