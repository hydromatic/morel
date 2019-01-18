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

import com.google.common.collect.ImmutableMap;

import java.math.BigDecimal;
import java.util.Map;

/** Builds parse tree nodes. */
public enum AstBuilder {
  INSTANCE;

  /** Creates an {@code int} literal. */
  public Ast.Literal intLiteral(BigDecimal value, Pos pos) {
    return new Ast.Literal(pos, Op.INT_LITERAL, value);
  }

  /** Creates a {@code float} literal. */
  public Ast.Literal realLiteral(BigDecimal value, Pos pos) {
    return new Ast.Literal(pos, Op.REAL_LITERAL, value);
  }

  /** Creates a string literal. */
  public Ast.Literal stringLiteral(Pos pos, String value) {
    return new Ast.Literal(pos, Op.STRING_LITERAL, value);
  }

  /** Creates a boolean literal. */
  public Ast.Literal boolLiteral(Pos p, boolean b) {
    return new Ast.Literal(p, Op.BOOL_LITERAL, b);
  }

  public Ast.TypeNode namedType(Pos pos, String name) {
    return new Ast.NamedType(pos, name);
  }

  public Ast.PatNode namedPat(Pos pos, String name) {
    return new Ast.NamedPat(pos, name);
  }

  public Ast.PatNode annotatedPat(Pos pos, Ast.PatNode pat, Ast.TypeNode type) {
    return new Ast.AnnotatedPat(pos, pat, type);
  }

  public Ast.Exp plus(Ast.Exp a0, Ast.Exp a1) {
    return new Ast.InfixCall(a0.pos.plus(a1.pos), Op.PLUS, a0, a1);
  }

  public Ast.Exp minus(Ast.Exp a0, Ast.Exp a1) {
    return new Ast.InfixCall(a0.pos.plus(a1.pos), Op.MINUS, a0, a1);
  }

  public Ast.Exp times(Ast.Exp a0, Ast.Exp a1) {
    return new Ast.InfixCall(a0.pos.plus(a1.pos), Op.TIMES, a0, a1);
  }

  public Ast.Exp divide(Ast.Exp a0, Ast.Exp a1) {
    return new Ast.InfixCall(a0.pos.plus(a1.pos), Op.DIVIDE, a0, a1);
  }

  public Ast.Exp caret(Ast.Exp a0, Ast.Exp a1) {
    return new Ast.InfixCall(a0.pos.plus(a1.pos), Op.CARET, a0, a1);
  }

  public Ast.LetExp let(Pos pos, Ast.VarDecl decl, Ast.Exp exp) {
    return new Ast.LetExp(pos, decl, exp);
  }

  public Ast.VarDecl varDecl(Pos pos, Map<Ast.PatNode, Ast.Exp> patExps) {
    return new Ast.VarDecl(pos, ImmutableMap.copyOf(patExps));
  }
}

// End AstBuilder.java
