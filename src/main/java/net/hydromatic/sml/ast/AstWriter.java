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

/** Context for writing an AST out as a string. */
public class AstWriter {
  private final StringBuilder b = new StringBuilder();

  /** Appends a string to the output. */
  public AstWriter append(String s) {
    b.append(s);
    return this;
  }

  /** Appends a call to an infix operator. */
  public AstWriter infix(int left, AstNode a0, Op op, AstNode a1, int right) {
    if (left > op.left || op.right < right) {
      return append("(").infix(0, a0, op, a1, 0).append(")");
    }
    a0.unparse(this, left, op.left);
    append(op.padded);
    a1.unparse(this, op.right, right);
    return this;
  }

  /** Appends a call to a binary operator (e.g. "val ... = ..."). */
  public AstWriter binary(String left, AstNode a0, String mid, AstNode a1,
      int right) {
    append(left);
    a0.unparse(this, 0, 0);
    append(mid);
    a1.unparse(this, 0, right);
    return this;
  }

  /** Appends a call to a binary operator (e.g. "let ... in ... end"). */
  public AstWriter binary(String left, AstNode a0, String mid, AstNode a1,
      String right) {
    append(left);
    a0.unparse(this, 0, 0);
    append(mid);
    a1.unparse(this, 0, 0);
    append(right);
    return this;
  }

  @Override public String toString() {
    return b.toString();
  }

  public AstWriter append(AstNode node, int left, int right) {
    return node.unparse(this, left, right);
  }
}

// End AstWriter.java
