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
package net.hydromatic.morel.util;

import net.hydromatic.morel.ast.Pos;

/** Interface implemented by all exceptions in Morel. */
public interface MorelException {
  /** Returns the position. */
  Pos pos();

  /** Appends to {@code buf} a description of this exception. */
  StringBuilder describeTo(StringBuilder buf);

  /**
   * Appends to {@code buf} the standard two-line representation of this
   * exception used by both compile-time and run-time error reporting.
   *
   * <p>For example:
   *
   * <pre>{@code
   * - val x = abc;
   * stdIn:1.9-1.12 Error: unbound variable or constructor: abc
   *    raised at: stdIn:1.9-1.12
   * }</pre>
   */
  default StringBuilder describe(StringBuilder buf) {
    describeTo(buf).append("\n").append("  raised at: ");
    return pos().describeTo(buf);
  }

  /** As {@link #describe}, but returns a {@code String}. */
  default String description() {
    return describe(new StringBuilder()).toString();
  }
}

// End MorelException.java
