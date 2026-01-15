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
  private final Map<Core.Id, ? extends Core.Exp> substitution;

  private Replacer(
      TypeSystem typeSystem,
      Environment env,
      Map<Core.Id, ? extends Core.Exp> substitution) {
    super(typeSystem, env);
    this.substitution = requireNonNull(substitution);
  }

  static Core.Exp substitute(
      TypeSystem typeSystem,
      Map<Core.Id, ? extends Core.Exp> substitution,
      Core.Exp exp) {
    final Replacer replacer =
        new Replacer(typeSystem, Environments.empty(), substitution);
    return exp.accept(replacer);
  }

  @Override
  protected Replacer push(Environment env) {
    return new Replacer(typeSystem, env, substitution);
  }

  @Override
  protected Core.Exp visit(Core.Id id) {
    final Core.Exp exp = substitution.get(id);
    return exp != null ? exp : id;
  }
}

// End Replacer.java
