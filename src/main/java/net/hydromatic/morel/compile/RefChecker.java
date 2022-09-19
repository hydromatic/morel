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
import net.hydromatic.morel.type.TypeSystem;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static com.google.common.base.Verify.verifyNotNull;

/** Validates expressions, making sure that every {@link Core.Id}
 * exists in the environment. */
public class RefChecker extends EnvVisitor {
  /** Creates a reference checker. */
  public static RefChecker of(TypeSystem typeSystem, Environment env) {
    return new RefChecker(typeSystem, env, new ArrayDeque<>());
  }

  private RefChecker(TypeSystem typeSystem, Environment env,
      Deque<FromContext> fromStack) {
    super(typeSystem, env, fromStack);
  }

  @Override protected EnvVisitor bind(Binding binding) {
    return new RefChecker(typeSystem, env.bind(binding), fromStack);
  }

  @Override protected EnvVisitor bind(List<Binding> bindingList) {
    // The "env2 == env" check is an optimization. If you remove it, this method
    // will have the same effect, just slower.
    final Environment env2 = env.bindAll(bindingList);
    if (env2 == env) {
      return this;
    }
    return new RefChecker(typeSystem, env2, fromStack);
  }

  @Override protected void visit(Core.Id id) {
    verifyNotNull(env.getOpt(id.idPat), "not found", id);
  }
}

// End RefChecker.java
