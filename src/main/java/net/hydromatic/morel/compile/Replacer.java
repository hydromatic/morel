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

import static java.util.Objects.requireNonNull;

import java.util.Map;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.type.TypeSystem;

/** Replaces identifiers with expressions. */
public class Replacer extends EnvShuttle {
  protected final Map<Core.NamedPat, ? extends Core.Exp> substitution;

  private Replacer(
      TypeSystem typeSystem,
      Environment env,
      Map<Core.NamedPat, ? extends Core.Exp> substitution) {
    super(typeSystem, env);
    this.substitution = requireNonNull(substitution);
  }

  static Core.Exp substitute(
      TypeSystem typeSystem,
      Environment env,
      Map<Core.NamedPat, ? extends Core.Exp> substitution,
      Core.Exp exp) {
    if (substitution.isEmpty()) {
      return exp;
    }
    final Replacer replacer = new Replacer(typeSystem, env, substitution);
    return exp.accept(replacer);
  }

  static Core.FromStep substitute(
      TypeSystem typeSystem,
      Environment env,
      Map<Core.NamedPat, ? extends Core.Exp> substitution,
      Core.FromStep step) {
    if (substitution.isEmpty()) {
      return step;
    }
    final Replacer replacer = new Replacer(typeSystem, env, substitution);
    return step.accept(replacer);
  }

  @Override
  protected Replacer push(Environment env) {
    return new Replacer(typeSystem, env, substitution);
  }

  @Override
  protected Core.Exp visit(Core.Id id) {
    final Core.Exp exp = substitution.get(id.idPat);
    return exp != null ? exp : id;
  }
}

// End Replacer.java
