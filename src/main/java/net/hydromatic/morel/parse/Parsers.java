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

/**
 * Utilities for parsing.
 */
public final class Parsers {
  private Parsers() {
  }

  /** Given quoted identifier {@code `abc`} returns {@code abc}.
   * Converts any doubled back-ticks to a single back-tick.
   * Assumes there are no single back-ticks. */
  public static String unquoteIdentifier(String s) {
    assert s.charAt(0) == '`';
    assert s.charAt(s.length() - 1) == '`';
    return s.substring(1, s.length() - 1)
        .replace("``", "`");
  }

  /** Given quoted string {@code "abc"} returns {@code abc}. */
  public static String unquoteString(String s) {
    assert s.charAt(0) == '"';
    assert s.charAt(s.length() - 1) == '"';
    return s.substring(1, s.length() - 1)
        .replace("\\\\", "\\")
        .replace("\\\"", "\"");
  }

  /** Given quoted char literal {@code #"a"} returns {@code a}. */
  public static char unquoteCharLiteral(String s) {
    assert s.charAt(0) == '#';
    assert s.charAt(1) == '"';
    assert s.charAt(s.length() - 1) == '"';
    String image = s.substring(2, s.length() - 1)
        .replace("\\\\", "\\")
        .replace("\\\"", "\"");
    if (image.length() != 1) {
      throw new RuntimeException("Error: character constant not length 1");
    }
    return image.charAt(0);
  }

  /** Appends an identifier. Encloses it in back-ticks if necessary. */
  public static StringBuilder appendId(StringBuilder buf, String id) {
    if (id.contains("`") || id.contains(" ")) {
      return buf.append("`")
          .append(id.replaceAll("`", "``"))
          .append("`");
    } else {
      return buf.append(id);
    }
  }
}

// End Parsers.java
