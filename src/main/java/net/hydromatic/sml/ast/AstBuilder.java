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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import net.hydromatic.sml.eval.Unit;

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

  /** Creates a unit literal. */
  public Ast.Literal unitLiteral(Pos p) {
    return new Ast.Literal(p, Op.UNIT_LITERAL, Unit.INSTANCE);
  }

  public Ast.Id id(Pos pos, String name) {
    return new Ast.Id(pos, name);
  }

  public Ast.RecordSelector recordSelector(Pos pos, String name) {
    return new Ast.RecordSelector(pos, name);
  }

  public Ast.TypeNode namedType(Pos pos, String name) {
    return new Ast.NamedType(pos, name);
  }

  public Ast.IdPat idPat(Pos pos, String name) {
    return new Ast.IdPat(pos, name);
  }

  public Ast.LiteralPat literalPat(Pos pos, Op op, Comparable value) {
    return new Ast.LiteralPat(pos, op, value);
  }

  public Ast.WildcardPat wildcardPat(Pos pos) {
    return new Ast.WildcardPat(pos);
  }

  public Ast.TuplePat tuplePat(Pos pos, Iterable<? extends Ast.Pat> args) {
    return new Ast.TuplePat(pos, ImmutableList.copyOf(args));
  }

  public Ast.TuplePat tuplePat(Pos pos, Ast.Pat... args) {
    return new Ast.TuplePat(pos, ImmutableList.copyOf(args));
  }

  public Ast.Pat annotatedPat(Pos pos, Ast.Pat pat, Ast.TypeNode type) {
    return new Ast.AnnotatedPat(pos, pat, type);
  }

  public Ast.Tuple tuple(Pos pos, Iterable<? extends Ast.Exp> list) {
    return new Ast.Tuple(pos, list);
  }

  public Ast.Exp record(Pos pos, Map<String, Ast.Exp> map) {
    return new Ast.Record(pos, ImmutableMap.copyOf(map));
  }

  public Ast.Exp andAlso(Ast.Exp a0, Ast.Exp a1) {
    return new Ast.InfixCall(a0.pos.plus(a1.pos), Op.ANDALSO, a0, a1);
  }

  public Ast.Exp orElse(Ast.Exp a0, Ast.Exp a1) {
    return new Ast.InfixCall(a0.pos.plus(a1.pos), Op.ORELSE, a0, a1);
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

  public Ast.VarDecl varDecl(Pos pos,
      Iterable<? extends Ast.ValBind> valBinds) {
    return new Ast.VarDecl(pos, ImmutableList.copyOf(valBinds));
  }

  public Ast.VarDecl varDecl(Pos pos, Ast.ValBind... valBinds) {
    return new Ast.VarDecl(pos, ImmutableList.copyOf(valBinds));
  }

  public Ast.ValBind valBind(Pos pos, Ast.Pat pat, Ast.Exp e) {
    return new Ast.ValBind(pos, pat, e);
  }

  public Ast.Match match(Pos pos, Ast.Pat pat, Ast.Exp e) {
    return new Ast.Match(pos, pat, e);
  }

  public Ast.Case caseOf(Pos pos, Ast.Exp exp,
      Iterable<? extends Ast.Match> matchList) {
    return new Ast.Case(pos, exp, ImmutableList.copyOf(matchList));
  }

  public Ast.Fn fn(Pos pos, Ast.Match match) {
    return new Ast.Fn(pos, ImmutableList.of(match));
  }

  public Ast.Apply apply(Ast.Exp fn, Ast.Exp arg) {
    return new Ast.Apply(fn.pos.plus(arg.pos), fn, arg);
  }

  public Ast.Exp ifThenElse(Pos pos, Ast.Exp condition, Ast.Exp ifTrue,
      Ast.Exp ifFalse) {
    return new Ast.If(pos, condition, ifTrue, ifFalse);
  }
}

// End AstBuilder.java
