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

import static com.google.common.base.Verify.verifyNotNull;

import java.util.ArrayDeque;
import java.util.Deque;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Validates expressions, making sure that every {@link Core.Id} exists in the
 * environment.
 */
public class RefChecker extends EnvVisitor {
  /** Creates a reference checker. */
  public static RefChecker of(TypeSystem typeSystem, Environment env) {
    return new RefChecker(typeSystem, env, new ArrayDeque<>());
  }

  private RefChecker(
      TypeSystem typeSystem, Environment env, Deque<FromContext> fromStack) {
    super(typeSystem, env, fromStack);
  }

  @Override
  protected RefChecker push(Environment env) {
    return new RefChecker(typeSystem, env, fromStack);
  }

  @Override
  protected void visit(Core.Id id) {
    verifyNotNull(env.getOpt(id.idPat), "not found", id);
  }
}

// End RefChecker.java
