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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.hydromatic.sml.ast.AstBuilder.ast;

/** Visits and transforms syntax trees. */
public class Shuttle {
  private <E extends AstNode> List<E> visitList(List<E> nodes) {
    final List<E> list = new ArrayList<>();
    for (E node : nodes) {
      list.add((E) node.accept(this));
    }
    return list;
  }

  private <K, E extends AstNode> Map<K, E> visitMap(Map<K, E> nodes) {
    final Map<K, E> map = new LinkedHashMap<>();
    for (Map.Entry<K, E> entry : nodes.entrySet()) {
      map.put(entry.getKey(), (E) entry.getValue().accept(this));
    }
    return map;
  }

  // expressions

  protected Ast.Exp visit(Ast.Literal literal) {
    return literal; // leaf
  }

  protected Ast.Id visit(Ast.Id id) {
    return id; // leaf
  }

  protected Ast.Exp visit(Ast.AnnotatedExp annotatedExp) {
    return ast.annotatedExp(annotatedExp.pos,
        annotatedExp.type.accept(this),
        annotatedExp.expression.accept(this));
  }

  protected Ast.Exp visit(Ast.If anIf) {
    return ast.ifThenElse(anIf.pos, anIf.condition.accept(this),
        anIf.ifTrue.accept(this), anIf.ifFalse.accept(this));
  }

  protected Ast.LetExp visit(Ast.LetExp e) {
    return ast.let(e.pos, visitList(e.decls), e.e);
  }

  protected Ast.Exp visit(Ast.Case kase) {
    return ast.caseOf(kase.pos, kase.exp.accept(this),
        visitList(kase.matchList));
  }

  // calls

  protected Ast.Exp visit(Ast.InfixCall infixCall) {
    return ast.infixCall(infixCall.pos, infixCall.op,
        infixCall.a0.accept(this), infixCall.a1.accept(this));
  }

  public Ast.Exp visit(Ast.PrefixCall prefixCall) {
    return ast.prefixCall(prefixCall.pos, prefixCall.op,
        prefixCall.a.accept(this));
  }

  // patterns

  protected Ast.Pat visit(Ast.IdPat idPat) {
    return idPat; // leaf
  }

  protected Ast.Pat visit(Ast.LiteralPat literalPat) {
    return literalPat; // leaf
  }

  protected Ast.Pat visit(Ast.WildcardPat wildcardPat) {
    return wildcardPat; // leaf
  }

  protected Ast.Pat visit(Ast.InfixPat infixPat) {
    return ast.infixPat(infixPat.pos, infixPat.op, infixPat.p0.accept(this),
        infixPat.p1.accept(this));
  }

  protected Ast.Pat visit(Ast.TuplePat tuplePat) {
    return ast.tuplePat(tuplePat.pos, visitList(tuplePat.args));
  }

  protected Ast.Pat visit(Ast.ListPat listPat) {
    return ast.listPat(listPat.pos, visitList(listPat.args));
  }

  protected Ast.Pat visit(Ast.RecordPat recordPat) {
    return ast.recordPat(recordPat.pos, recordPat.ellipsis,
        visitMap(recordPat.args));
  }

  protected Ast.Pat visit(Ast.AnnotatedPat annotatedPat) {
    return ast.annotatedPat(annotatedPat.pos, annotatedPat.pat.accept(this),
        annotatedPat.type.accept(this));
  }

  // value constructors

  protected Ast.Exp visit(Ast.Tuple tuple) {
    return ast.tuple(tuple.pos, visitList(tuple.args));
  }

  protected Ast.List visit(Ast.List list) {
    return ast.list(list.pos, visitList(list.args));
  }

  protected Ast.Exp visit(Ast.Record record) {
    return ast.record(record.pos, visitMap(record.args));
  }

  // functions and matches

  protected Ast.Fn visit(Ast.Fn fn) {
    return ast.fn(fn.pos, visitList(fn.matchList));
  }

  protected Ast.Apply visit(Ast.Apply apply) {
    return ast.apply(apply.fn.accept(this), apply.arg.accept(this));
  }

  protected Ast.Exp visit(Ast.RecordSelector recordSelector) {
    return recordSelector; // leaf
  }

  protected Ast.Match visit(Ast.Match match) {
    return ast.match(match.pos, match.pat.accept(this), match.e.accept(this));
  }

  // types

  protected Ast.Type visit(Ast.NamedType namedType) {
    return namedType; // leaf
  }

  // declarations

  protected Ast.Decl visit(Ast.FunDecl funDecl) {
    return ast.funDecl(funDecl.pos, visitList(funDecl.funBinds));
  }

  protected Ast.FunBind visit(Ast.FunBind funBind) {
    return ast.funBind(funBind.pos, visitList(funBind.matchList));
  }

  protected Ast.FunMatch visit(Ast.FunMatch funMatch) {
    return ast.funMatch(funMatch.pos, funMatch.name,
        visitList(funMatch.patList), funMatch.exp.accept(this));
  }

  protected Ast.ValDecl visit(Ast.ValDecl valDecl) {
    return ast.valDecl(valDecl.pos, visitList(valDecl.valBinds));
  }

  protected Ast.ValBind visit(Ast.ValBind valBind) {
    return ast.valBind(valBind.pos, valBind.rec, valBind.pat, valBind.e);
  }

  public Ast.Exp visit(Ast.From from) {
    return ast.from(from.pos, from.sources, from.filterExp, from.yieldExp);
  }
}

// End Shuttle.java
