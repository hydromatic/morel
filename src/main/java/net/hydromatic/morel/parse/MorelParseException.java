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
import net.hydromatic.morel.util.MorelException;

/** Exception caused by a parse error. */
public class MorelParseException extends RuntimeException
    implements MorelException {
  private final Pos pos;

  MorelParseException(Exception cause, Pos pos) {
    super(cause.getMessage(), cause);
    this.pos = pos;
  }

  @Override
  public Pos pos() {
    return pos;
  }

  @Override
  public StringBuilder describeTo(StringBuilder buf) {
    return pos.describeTo(buf).append(getCause().getMessage());
  }
}

// End MorelParseException.java
