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

import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.util.MorelException;

/** An error occurred during compilation. */
public class CompileException extends RuntimeException
    implements MorelException {
  private final Pos pos;

  public CompileException(String message, Pos pos) {
    super(message);
    this.pos = pos;
  }

  @Override public String toString() {
    return super.toString() + " at " + pos;
  }

  @Override public Pos pos() {
    return pos;
  }

  public StringBuilder describeTo(StringBuilder buf) {
    return pos.describeTo(buf)
        .append(" Error: ")
        .append(getMessage());
  }
}

// End CompileException.java
