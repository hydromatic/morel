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
package net.hydromatic.morel.ast;

import static java.util.Objects.requireNonNull;

/** Abstract syntax tree node. */
public abstract class AstNode {
  public final Pos pos;
  public final Op op;

  public AstNode(Pos pos, Op op) {
    this.pos = requireNonNull(pos);
    this.op = requireNonNull(op);
  }

  /**
   * Converts this node into an ML string.
   *
   * <p>The purpose of this string is debugging. If you want to generate an
   * expression, use {@link #unparse}, which will insert parentheses as
   * necessary for operator precedence.
   *
   * <p>Derived classes <em>may</em> override, but they must produce the same
   * result; so the only reason to override is if they can do it more
   * efficiently.
   */
  @Override
  public final String toString() {
    // Marked final because you should override unparse, not toString
    return unparse(new AstWriter());
  }

  /** Converts this node into an ML string, with a given writer. */
  public final String unparse(AstWriter w) {
    return unparse(w, 0, 0).toString();
  }

  abstract AstWriter unparse(AstWriter w, int left, int right);

  /**
   * Accepts a shuttle, calling the {@link
   * net.hydromatic.morel.ast.Shuttle#visit} method appropriate to the type of
   * this node, and returning the result.
   */
  public abstract AstNode accept(Shuttle shuttle);

  /**
   * Accepts a visitor, calling the {@link
   * net.hydromatic.morel.ast.Shuttle#visit} method appropriate to the type of
   * this node, and returning the result.
   */
  public abstract void accept(Visitor visitor);
}

// End AstNode.java
