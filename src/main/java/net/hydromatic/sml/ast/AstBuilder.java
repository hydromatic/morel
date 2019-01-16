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

/** Builds parse tree nodes. */
public enum AstBuilder {
  INSTANCE;

  /** Creates an {@code int} literal. */
  public Ast.Literal intLiteral(BigDecimal value, Pos pos) {
    return new Ast.Literal(pos, value);
  }

  /** Creates a {@code float} literal. */
  public Ast.Literal floatLiteral(BigDecimal value, Pos pos) {
    return new Ast.Literal(pos, value);
  }

  /** Creates a string literal. */
  public Ast.Literal stringLiteral(Pos pos, String value) {
    return new Ast.Literal(pos, value);
  }

  /** Creates a boolean literal. */
  public Ast.Literal boolLiteral(Pos p, boolean b) {
    return new Ast.Literal(p, b);
  }

  public Ast.TypeNode namedType(Pos pos, String name) {
    return new Ast.NamedType(pos, name);
  }

  public Ast.PatNode namedPat(Pos pos, String name) {
    return new Ast.NamedPat(pos, name);
  }

  public Ast.PatNode annotatedPat(Pos pos, Ast.PatNode pat, Ast.TypeNode type) {
    return new Ast.AnnotatedPatNode(pos, pat, type);
  }
}

// End AstBuilder.java
