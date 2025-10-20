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

import java.util.function.Consumer;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.Type;

/**
 * Statement that has been compiled and is ready to be run from the REPL.
 *
 * <p>If a declaration, it evaluates an expression and also creates a new
 * environment (with new variables bound) and generates a line or two of output
 * for the REPL.
 */
public interface CompiledStatement {
  /**
   * Evaluates this statement, adding lines of feedback to {@code output} and
   * writing bindings (values to variables, and types definitions) to {@code
   * bindings}. The environment for the next statement can be constructed from
   * the bindings.
   *
   * @param session Session
   * @param environment Evaluation environment
   * @param outLines List to which to append lines of output
   * @param outBindings List to which to append bound variables and types
   */
  void eval(
      Session session,
      Environment environment,
      Consumer<String> outLines,
      Consumer<Binding> outBindings);

  Type getType();

  /**
   * Returns the bindings (variables and their types) for this statement without
   * evaluating it. This is useful for type-only mode where we want to know the
   * types but not execute the code.
   *
   * @param outBindings Consumer to receive the bindings
   */
  void getBindings(Consumer<Binding> outBindings);
}

// End CompiledStatement.java
