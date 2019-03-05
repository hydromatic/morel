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
import com.google.common.collect.ImmutableSortedMap;

import net.hydromatic.sml.eval.Unit;

import java.math.BigDecimal;
import java.util.Map;

/** Builds parse tree nodes. */
public enum AstBuilder {
  INSTANCE;

  /** The singleton instance of the AST builder.
   * The short name is convenient for use via 'import static',
   * but checkstyle does not approve. */
  // CHECKSTYLE: IGNORE 1
  public static final AstBuilder ast = INSTANCE;

  /** Creates a call to an infix operator. */
  private Ast.InfixCall infix(Op op, Ast.Exp a0, Ast.Exp a1) {
    return new Ast.InfixCall(a0.pos.plus(a1.pos), op, a0, a1);
  }

  /** Creates a {@code boolean} literal. */
  public Ast.Literal boolLiteral(Pos p, boolean b) {
    return new Ast.Literal(p, Op.BOOL_LITERAL, b);
  }

  /** Creates a {@code char} literal. */
  public Ast.Literal charLiteral(Pos p, char c) {
    return new Ast.Literal(p, Op.CHAR_LITERAL, c);
  }

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

  public Ast.Type namedType(Pos pos, String name) {
    return new Ast.NamedType(pos, name);
  }

  public Ast.Pat idPat(Pos pos, String name) {
    // Don't treat built-in constants as identifiers.
    // If we did, matching the pattern would rebind the name
    // to some other value.
    switch (name) {
    case "false":
      return literalPat(pos, Op.BOOL_LITERAL_PAT, false);
    case "true":
      return literalPat(pos, Op.BOOL_LITERAL_PAT, true);
    case "nil":
      return listPat(pos);
    default:
      return new Ast.IdPat(pos, name);
    }
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

  public Ast.ListPat listPat(Pos pos, Iterable<? extends Ast.Pat> args) {
    return new Ast.ListPat(pos, ImmutableList.copyOf(args));
  }

  public Ast.ListPat listPat(Pos pos, Ast.Pat... args) {
    return new Ast.ListPat(pos, ImmutableList.copyOf(args));
  }

  public Ast.RecordPat recordPat(Pos pos, boolean ellipsis,
      Map<String, ? extends Ast.Pat> args) {
    return new Ast.RecordPat(pos, ellipsis, ImmutableMap.copyOf(args));
  }

  public Ast.Pat annotatedPat(Pos pos, Ast.Pat pat, Ast.Type type) {
    return new Ast.AnnotatedPat(pos, pat, type);
  }

  public Ast.Pat consPat(Ast.Pat p0, Ast.Pat p1) {
    return infixPat(p0.pos.plus(p1.pos), Op.CONS_PAT, p0, p1);
  }

  public Ast.Tuple tuple(Pos pos, Iterable<? extends Ast.Exp> list) {
    return new Ast.Tuple(pos, list);
  }

  public Ast.List list(Pos pos, Iterable<? extends Ast.Exp> list) {
    return new Ast.List(pos, list);
  }

  public Ast.Exp record(Pos pos, Map<String, Ast.Exp> map) {
    return new Ast.Record(pos, ImmutableSortedMap.copyOf(map));
  }

  public Ast.Exp equal(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.EQ, a0, a1);
  }

  public Ast.Exp notEqual(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.NE, a0, a1);
  }

  public Ast.Exp lessThan(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.LT, a0, a1);
  }

  public Ast.Exp greaterThan(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.GT, a0, a1);
  }

  public Ast.Exp lessThanOrEqual(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.LE, a0, a1);
  }

  public Ast.Exp greaterThanOrEqual(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.GE, a0, a1);
  }

  public Ast.Exp andAlso(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.ANDALSO, a0, a1);
  }

  public Ast.Exp orElse(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.ORELSE, a0, a1);
  }

  public Ast.Exp plus(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.PLUS, a0, a1);
  }

  public Ast.Exp minus(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.MINUS, a0, a1);
  }

  public Ast.Exp times(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.TIMES, a0, a1);
  }

  public Ast.Exp divide(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.DIVIDE, a0, a1);
  }

  public Ast.Exp div(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.DIV, a0, a1);
  }

  public Ast.Exp mod(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.MOD, a0, a1);
  }

  public Ast.Exp caret(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.CARET, a0, a1);
  }

  public Ast.Exp cons(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.CONS, a0, a1);
  }

  public Ast.LetExp let(Pos pos, Iterable<? extends Ast.Decl> decls,
      Ast.Exp exp) {
    return new Ast.LetExp(pos, ImmutableList.copyOf(decls), exp);
  }

  public Ast.VarDecl varDecl(Pos pos,
      Iterable<? extends Ast.ValBind> valBinds) {
    return new Ast.VarDecl(pos, ImmutableList.copyOf(valBinds));
  }

  public Ast.VarDecl varDecl(Pos pos, Ast.ValBind... valBinds) {
    return new Ast.VarDecl(pos, ImmutableList.copyOf(valBinds));
  }

  public Ast.ValBind valBind(Pos pos, boolean rec, Ast.Pat pat, Ast.Exp e) {
    return new Ast.ValBind(pos, rec, pat, e);
  }

  public Ast.Match match(Pos pos, Ast.Pat pat, Ast.Exp e) {
    return new Ast.Match(pos, pat, e);
  }

  public Ast.Case caseOf(Pos pos, Ast.Exp exp,
      Iterable<? extends Ast.Match> matchList) {
    return new Ast.Case(pos, exp, ImmutableList.copyOf(matchList));
  }

  public Ast.Fn fn(Pos pos, Ast.Match... matchList) {
    return new Ast.Fn(pos, ImmutableList.copyOf(matchList));
  }

  public Ast.Fn fn(Pos pos,
      Iterable<? extends Ast.Match> matchList) {
    return new Ast.Fn(pos, ImmutableList.copyOf(matchList));
  }

  public Ast.FunDecl funDecl(Pos pos,
      Iterable<? extends Ast.FunBind> valBinds) {
    return new Ast.FunDecl(pos, ImmutableList.copyOf(valBinds));
  }

  public Ast.FunBind funBind(Pos pos,
      Iterable<? extends Ast.FunMatch> matchList) {
    return new Ast.FunBind(pos, ImmutableList.copyOf(matchList));
  }

  public Ast.FunMatch funMatch(Pos pos, String name,
      Iterable<? extends Ast.Pat> patList, Ast.Exp exp) {
    return new Ast.FunMatch(pos, name, ImmutableList.copyOf(patList), exp);
  }

  public Ast.Apply apply(Ast.Exp fn, Ast.Exp arg) {
    return new Ast.Apply(fn.pos.plus(arg.pos), fn, arg);
  }

  public Ast.Exp ifThenElse(Pos pos, Ast.Exp condition, Ast.Exp ifTrue,
      Ast.Exp ifFalse) {
    return new Ast.If(pos, condition, ifTrue, ifFalse);
  }

  public Ast.InfixPat infixPat(Pos pos, Op op, Ast.Pat p0, Ast.Pat p1) {
    return new Ast.InfixPat(pos, op, p0, p1);
  }

  public Ast.Exp annotatedExp(Pos pos, Ast.Type type, Ast.Exp expression) {
    return new Ast.AnnotatedExp(pos, type, expression);
  }

  public Ast.Exp infixCall(Pos pos, Op op, Ast.Exp a0, Ast.Exp a1) {
    return new Ast.InfixCall(pos, op, a0, a1);
  }
}

// End AstBuilder.java
