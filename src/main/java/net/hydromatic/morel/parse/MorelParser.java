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
package net.hydromatic.morel.parse;

import net.hydromatic.morel.ast.Pos;

/** Parser for Morel, a variant of Standard ML. */
public interface MorelParser {
  /** Returns the position of the last token returned by the parser. */
  Pos pos();

  /** Sets the current file, and sets the current line to zero. */
  void zero(String file);

  /**
   * Wraps a parse exception in a {@link
   * net.hydromatic.morel.util.MorelException} with the current position.
   */
  MorelParseException wrap(Exception parseException);
}

// End MorelParser.java
