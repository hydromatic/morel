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

import java.util.List;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.eval.Code;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Called on various events during compilation. */
public interface Tracer {
  /** Called when the expression is converted to core. */
  void onCore(int pass, Core.Decl e);

  /** Called when code is generated. */
  void onPlan(Code code);

  /** Called on the result of an evaluation. */
  void onResult(Object o);

  /** Called with the list of warnings after evaluation. */
  void onWarnings(List<Throwable> warningList);

  /**
   * Called with the exception thrown during evaluation, or null if no exception
   * was thrown. Returns whether a handler was found.
   */
  boolean onException(@Nullable Throwable e);

  /**
   * Called with the exception thrown during type resolution. Returns whether a
   * handler was found.
   */
  boolean onTypeException(TypeResolver.TypeException e);

  /**
   * Called with the exception thrown during validation, or null if no exception
   * was thrown. Returns whether a handler was found.
   */
  boolean handleCompileException(@Nullable CompileException e);
}

// End Tracer.java
