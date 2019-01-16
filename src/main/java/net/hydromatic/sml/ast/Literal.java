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
package net.hydromatic.sml.ast;

import java.math.BigDecimal;
import java.util.Objects;

/** Parse tree node of a literal (constant). */
public class Literal extends AstNode {
  public final Comparable value;

  /** Creates a Literal. */
  private Literal(Pos pos, Comparable value) {
    super(pos);
    this.value = Objects.requireNonNull(value);
  }

  public static Literal createExactNumeric(String image, Pos pos) {
    final BigDecimal value;
    if (image.startsWith("~")) {
      value = new BigDecimal(image.substring(1)).negate();
    } else {
      value = new BigDecimal(image);
    }
    return new Literal(pos, value);
  }

  public static Literal createString(Pos pos, String image) {
    assert image.charAt(0) == '"';
    assert image.charAt(image.length() - 1) == '"';
    image = image.substring(1, image.length() - 1);
    return new Literal(pos, image.replace("''", "'"));
  }

  public static Literal createBoolean(Pos pos, boolean value) {
    return new Literal(pos, value);
  }

  @Override public int hashCode() {
    return value.hashCode();
  }

  @Override public boolean equals(Object o) {
    return o == this
        || o instanceof Literal
        && this.value.equals(((Literal) o).value);
  }

  @Override public String toString() {
    return value.toString();
  }
}

// End Literal.java
