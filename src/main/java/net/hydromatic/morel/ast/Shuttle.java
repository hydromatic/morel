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

import static net.hydromatic.morel.ast.AstBuilder.ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;

/** Visits and transforms syntax trees. */
public class Shuttle {
  protected final TypeSystem typeSystem;

  /** Creates a Shuttle. */
  public Shuttle(TypeSystem typeSystem) {
    this.typeSystem = typeSystem;
  }

  protected <E extends AstNode> List<E> visitList(List<E> nodes) {
    final List<E> list = new ArrayList<>();
    for (E node : nodes) {
      //noinspection unchecked
      list.add((E) node.accept(this));
    }
    return list;
  }

  protected <K, E extends AstNode> Map<K, E> visitMap(Map<K, E> nodes) {
    final Map<K, E> map = new LinkedHashMap<>();
    //noinspection unchecked
    nodes.forEach((k, v) -> map.put(k, (E) v.accept(this)));
    return map;
  }

  protected <K, E extends AstNode> SortedMap<K, E> visitSortedMap(
      SortedMap<K, E> nodes) {
    final SortedMap<K, E> map = new TreeMap<>(nodes.comparator());
    //noinspection unchecked
    nodes.forEach((k, v) -> map.put(k, (E) v.accept(this)));
    return map;
  }

  protected <K, E extends AstNode> PairList<K, E> visitPairList(
      PairList<K, E> nodes) {
    final PairList<K, E> list = PairList.of();
    //noinspection unchecked
    nodes.forEach((k, v) -> list.add(k, (E) v.accept(this)));
    return list;
  }

  // expressions

  protected Ast.Exp visit(Ast.Literal literal) {
    return literal; // leaf
  }

  protected Ast.Id visit(Ast.Id id) {
    return id; // leaf
  }

  protected Ast.Current visit(Ast.Current current) {
    return current;
  }

  protected Ast.Ordinal visit(Ast.Ordinal ordinal) {
    return ordinal;
  }

  protected Ast.Exp visit(Ast.AnnotatedExp annotatedExp) {
    return ast.annotatedExp(
        annotatedExp.pos,
        annotatedExp.exp.accept(this),
        annotatedExp.type.accept(this));
  }

  protected Ast.Exp visit(Ast.If ifThenElse) {
    return ast.ifThenElse(
        ifThenElse.pos,
        ifThenElse.condition.accept(this),
        ifThenElse.ifTrue.accept(this),
        ifThenElse.ifFalse.accept(this));
  }

  protected Ast.Let visit(Ast.Let let) {
    return ast.let(let.pos, visitList(let.decls), let.exp);
  }

  protected Ast.Exp visit(Ast.Case caseOf) {
    return ast.caseOf(
        caseOf.pos, caseOf.exp.accept(this), visitList(caseOf.matchList));
  }

  // calls

  protected Ast.Exp visit(Ast.InfixCall infixCall) {
    return ast.infixCall(
        infixCall.pos,
        infixCall.op,
        infixCall.a0.accept(this),
        infixCall.a1.accept(this));
  }

  protected Ast.Exp visit(Ast.PrefixCall prefixCall) {
    return ast.prefixCall(
        prefixCall.pos, prefixCall.op, prefixCall.a.accept(this));
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
    return infixPat.copy(infixPat.p0.accept(this), infixPat.p1.accept(this));
  }

  protected Ast.Pat visit(Ast.TuplePat tuplePat) {
    return tuplePat.copy(visitList(tuplePat.args));
  }

  protected Ast.Pat visit(Ast.ListPat listPat) {
    return listPat.copy(visitList(listPat.args));
  }

  protected Ast.Pat visit(Ast.RecordPat recordPat) {
    return recordPat.copy(recordPat.ellipsis, visitMap(recordPat.args));
  }

  protected Ast.Pat visit(Ast.AnnotatedPat annotatedPat) {
    return annotatedPat.copy(
        annotatedPat.pat.accept(this), annotatedPat.type.accept(this));
  }

  protected Ast.Pat visit(Ast.AsPat asPat) {
    return asPat.copy(
        (Ast.IdPat) asPat.id.accept(this), asPat.pat.accept(this));
  }

  protected Ast.ConPat visit(Ast.ConPat conPat) {
    return conPat.copy(conPat.tyCon.accept(this), conPat.pat.accept(this));
  }

  protected Ast.Con0Pat visit(Ast.Con0Pat con0Pat) {
    return con0Pat.copy(con0Pat.tyCon.accept(this));
  }

  // value constructors

  protected Ast.Exp visit(Ast.Tuple tuple) {
    return ast.tuple(tuple.pos, visitList(tuple.args));
  }

  protected Ast.ListExp visit(Ast.ListExp list) {
    return ast.list(list.pos, visitList(list.args));
  }

  protected Ast.Exp visit(Ast.Record record) {
    return ast.record(
        record.pos,
        record.with == null ? null : record.with.accept(this),
        visitPairList(record.args));
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
    return ast.match(match.pos, match.pat.accept(this), match.exp.accept(this));
  }

  // types

  protected Ast.Type visit(Ast.NamedType namedType) {
    return namedType; // leaf
  }

  protected Ast.TyVar visit(Ast.TyVar tyVar) {
    return tyVar; // leaf
  }

  // declarations

  protected Ast.OverDecl visit(Ast.OverDecl overDecl) {
    return ast.overDecl(overDecl.pos, overDecl.pat);
  }

  protected Ast.Decl visit(Ast.FunDecl funDecl) {
    return ast.funDecl(funDecl.pos, visitList(funDecl.funBinds));
  }

  protected Ast.FunBind visit(Ast.FunBind funBind) {
    return ast.funBind(funBind.pos, visitList(funBind.matchList));
  }

  protected Ast.FunMatch visit(Ast.FunMatch funMatch) {
    return ast.funMatch(
        funMatch.pos,
        funMatch.name,
        visitList(funMatch.patList),
        funMatch.returnType == null ? null : funMatch.returnType.accept(this),
        funMatch.exp.accept(this));
  }

  protected Ast.ValDecl visit(Ast.ValDecl valDecl) {
    return ast.valDecl(
        valDecl.pos, valDecl.rec, valDecl.inst, visitList(valDecl.valBinds));
  }

  protected Ast.ValBind visit(Ast.ValBind valBind) {
    return ast.valBind(valBind.pos, valBind.pat, valBind.exp);
  }

  protected Ast.Exp visit(Ast.From from) {
    return ast.from(from.pos, from.steps);
  }

  protected Ast.Exp visit(Ast.Exists exists) {
    return ast.exists(exists.pos, exists.steps);
  }

  protected Ast.Exp visit(Ast.Forall forall) {
    return ast.forall(forall.pos, forall.steps);
  }

  protected AstNode visit(Ast.Order order) {
    return ast.order(order.pos, order.exp);
  }

  protected Ast.Scan visit(Ast.Scan scan) {
    return ast.scan(
        scan.pos,
        scan.pat.accept(this),
        scan.exp.accept(this),
        scan.condition == null ? null : scan.condition.accept(this));
  }

  protected AstNode visit(Ast.Where where) {
    return ast.where(where.pos, where.exp.accept(this));
  }

  protected AstNode visit(Ast.Distinct distinct) {
    return distinct;
  }

  protected AstNode visit(Ast.Require require) {
    return ast.require(require.pos, require.exp.accept(this));
  }

  protected AstNode visit(Ast.Skip skip) {
    return ast.skip(skip.pos, skip.exp.accept(this));
  }

  protected AstNode visit(Ast.Take take) {
    return ast.take(take.pos, take.exp.accept(this));
  }

  protected AstNode visit(Ast.Except except) {
    return except.copy(except.distinct, visitList(except.args));
  }

  protected AstNode visit(Ast.Intersect intersect) {
    return intersect.copy(intersect.distinct, visitList(intersect.args));
  }

  protected AstNode visit(Ast.Union union) {
    return union.copy(union.distinct, visitList(union.args));
  }

  protected AstNode visit(Ast.Unorder unorder) {
    return unorder;
  }

  protected AstNode visit(Ast.Yield yield) {
    return ast.yield(yield.pos, yield.exp.accept(this));
  }

  protected AstNode visit(Ast.Into into) {
    return ast.into(into.pos, into.exp);
  }

  protected AstNode visit(Ast.Through through) {
    return ast.through(through.pos, through.pat, through.exp);
  }

  protected AstNode visit(Ast.Compute compute) {
    return ast.compute(compute.pos, compute.aggregates);
  }

  protected AstNode visit(Ast.Group group) {
    return ast.group(group.pos, group.groupExps, group.aggregates);
  }

  protected AstNode visit(Ast.Aggregate aggregate) {
    return ast.aggregate(
        aggregate.pos, aggregate.aggregate, aggregate.argument, aggregate.id);
  }

  protected Ast.TypeDecl visit(Ast.TypeDecl typeDecl) {
    return ast.typeDecl(typeDecl.pos, visitList(typeDecl.binds));
  }

  protected Ast.TypeBind visit(Ast.TypeBind typeBind) {
    return ast.typeBind(
        typeBind.pos,
        typeBind.name.accept(this),
        visitList(typeBind.tyVars),
        typeBind.type.accept(this));
  }

  protected Ast.DatatypeDecl visit(Ast.DatatypeDecl datatypeDecl) {
    return ast.datatypeDecl(datatypeDecl.pos, visitList(datatypeDecl.binds));
  }

  protected Ast.DatatypeBind visit(Ast.DatatypeBind datatypeBind) {
    return ast.datatypeBind(
        datatypeBind.pos,
        datatypeBind.name.accept(this),
        visitList(datatypeBind.tyVars),
        visitList(datatypeBind.tyCons));
  }

  protected AstNode visit(Ast.TyCon tyCon) {
    return ast.typeConstructor(
        tyCon.pos,
        tyCon.id.accept(this),
        tyCon.type == null ? null : tyCon.type.accept(this));
  }

  protected Ast.RecordType visit(Ast.RecordType recordType) {
    return ast.recordType(recordType.pos, visitMap(recordType.fieldTypes));
  }

  protected Ast.Type visit(Ast.TupleType tupleType) {
    return ast.tupleType(tupleType.pos, visitList(tupleType.types));
  }

  protected Ast.Type visit(Ast.FunctionType functionType) {
    return ast.functionType(
        functionType.pos, functionType.paramType, functionType.resultType);
  }

  protected Ast.Type visit(Ast.CompositeType compositeType) {
    return ast.compositeType(compositeType.pos, visitList(compositeType.types));
  }

  protected Ast.Type visit(Ast.ExpressionType expressionType) {
    return ast.expressionType(
        expressionType.pos, expressionType.exp.accept(this));
  }

  // core expressions, patterns

  protected Core.Exp visit(Core.Apply apply) {
    return apply.copy(apply.fn.accept(this), apply.arg.accept(this));
  }

  protected Core.Exp visit(Core.Id id) {
    return id;
  }

  protected Core.RecordSelector visit(Core.RecordSelector recordSelector) {
    return recordSelector;
  }

  protected Core.Exp visit(Core.Literal literal) {
    return literal;
  }

  protected Core.Exp visit(Core.Tuple tuple) {
    return tuple.copy(typeSystem, visitList(tuple.args));
  }

  protected Core.Exp visit(Core.Let let) {
    return let.copy(let.decl.accept(this), let.exp.accept(this));
  }

  protected Core.Exp visit(Core.Local local) {
    return local.copy(local.dataType, local.exp.accept(this));
  }

  protected Core.TypeDecl visit(Core.TypeDecl datatypeDecl) {
    return datatypeDecl;
  }

  protected Core.DatatypeDecl visit(Core.DatatypeDecl datatypeDecl) {
    return datatypeDecl;
  }

  protected Core.NonRecValDecl visit(Core.NonRecValDecl valDecl) {
    return valDecl.copy(
        valDecl.pat.accept(this),
        valDecl.exp.accept(this),
        valDecl.overloadPat == null ? null : valDecl.overloadPat.accept(this));
  }

  protected Core.RecValDecl visit(Core.RecValDecl valDecl) {
    return valDecl.copy(visitList(valDecl.list));
  }

  protected Core.IdPat visit(Core.IdPat idPat) {
    return idPat;
  }

  protected Core.AsPat visit(Core.AsPat asPat) {
    return asPat.copy(asPat.name, asPat.i, asPat.pat.accept(this));
  }

  protected Core.Pat visit(Core.LiteralPat literalPat) {
    return literalPat;
  }

  protected Core.Pat visit(Core.WildcardPat wildcardPat) {
    return wildcardPat;
  }

  protected Core.Pat visit(Core.ConPat conPat) {
    return conPat.copy(conPat.tyCon, conPat.pat.accept(this));
  }

  protected Core.Pat visit(Core.Con0Pat con0Pat) {
    return con0Pat;
  }

  protected Core.Pat visit(Core.TuplePat tuplePat) {
    return tuplePat.copy(typeSystem, visitList(tuplePat.args));
  }

  protected Core.Pat visit(Core.ListPat listPat) {
    return listPat.copy(typeSystem, visitList(listPat.args));
  }

  protected Core.Pat visit(Core.RecordPat recordPat) {
    return recordPat.copy(
        typeSystem,
        recordPat.type().argNameTypes.keySet(),
        visitList(recordPat.args));
  }

  protected Core.Exp visit(Core.Fn fn) {
    return fn.copy(fn.idPat.accept(this), fn.exp.accept(this));
  }

  protected Core.Exp visit(Core.Case caseOf) {
    return caseOf.copy(caseOf.exp.accept(this), visitList(caseOf.matchList));
  }

  protected Core.Match visit(Core.Match match) {
    return match.copy(match.pat.accept(this), match.exp.accept(this));
  }

  protected Core.Exp visit(Core.From from) {
    return from.copy(typeSystem, null, visitList(from.steps));
  }

  protected Core.Scan visit(Core.Scan scan) {
    return scan.copy(
        scan.env,
        scan.pat.accept(this),
        scan.exp.accept(this),
        scan.condition.accept(this));
  }

  protected Core.Where visit(Core.Where where) {
    return where.copy(where.exp.accept(this), where.env);
  }

  protected Core.Skip visit(Core.Skip skip) {
    return skip.copy(skip.exp.accept(this), skip.env);
  }

  protected Core.Take visit(Core.Take take) {
    return take.copy(take.exp.accept(this), take.env);
  }

  protected Core.Except visit(Core.Except except) {
    return except.copy(except.distinct, visitList(except.args), except.env);
  }

  protected Core.Intersect visit(Core.Intersect intersect) {
    return intersect.copy(
        intersect.distinct, visitList(intersect.args), intersect.env);
  }

  protected Core.Union visit(Core.Union union) {
    return union.copy(union.distinct, visitList(union.args), union.env);
  }

  protected Core.Group visit(Core.Group group) {
    return group.copy(
        group.env.atom,
        visitSortedMap(group.groupExps),
        visitSortedMap(group.aggregates));
  }

  protected Core.Aggregate visit(Core.Aggregate aggregate) {
    return aggregate.copy(
        aggregate.type,
        aggregate.aggregate.accept(this),
        aggregate.argument == null ? null : aggregate.argument.accept(this));
  }

  protected Core.Order visit(Core.Order order) {
    return order.copy(order.env, order.exp.accept(this));
  }

  protected Core.Yield visit(Core.Yield yield) {
    return yield.copy(yield.env, yield.exp.accept(this));
  }

  protected Core.Unorder visit(Core.Unorder unorder) {
    return unorder;
  }

  protected Core.OverDecl visit(Core.OverDecl overDecl) {
    return overDecl;
  }
}

// End Shuttle.java
