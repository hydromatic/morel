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

/** Visits syntax trees. */
public class Visitor {

  /** For use as a method reference. */
  protected <E extends AstNode> void accept(E e) {
    e.accept(this);
  }

  // expressions

  protected void visit(Ast.Literal literal) {}

  protected void visit(Ast.Id id) {}

  protected void visit(Ast.Current current) {}

  protected void visit(Ast.Ordinal ordinal) {}

  protected void visit(Ast.AnnotatedExp annotatedExp) {
    annotatedExp.exp.accept(this);
    annotatedExp.type.accept(this);
  }

  protected void visit(Ast.If anIf) {
    anIf.condition.accept(this);
    anIf.ifTrue.accept(this);
    anIf.ifFalse.accept(this);
  }

  protected void visit(Ast.Let let) {
    let.decls.forEach(this::accept);
    let.exp.accept(this);
  }

  protected void visit(Ast.Case kase) {
    kase.exp.accept(this);
    kase.matchList.forEach(this::accept);
  }

  // calls

  protected void visit(Ast.InfixCall infixCall) {
    infixCall.a0.accept(this);
    infixCall.a1.accept(this);
  }

  protected void visit(Ast.PrefixCall prefixCall) {
    prefixCall.a.accept(this);
  }

  // patterns

  protected void visit(Ast.IdPat idPat) {}

  protected void visit(Ast.LiteralPat literalPat) {}

  protected void visit(Ast.WildcardPat wildcardPat) {}

  protected void visit(Ast.InfixPat infixPat) {
    infixPat.p0.accept(this);
    infixPat.p1.accept(this);
  }

  protected void visit(Ast.TuplePat tuplePat) {
    tuplePat.args.forEach(this::accept);
  }

  protected void visit(Ast.ListPat listPat) {
    listPat.args.forEach(this::accept);
  }

  protected void visit(Ast.RecordPat recordPat) {
    recordPat.args.values().forEach(this::accept);
  }

  protected void visit(Ast.AnnotatedPat annotatedPat) {
    annotatedPat.pat.accept(this);
    annotatedPat.type.accept(this);
  }

  protected void visit(Ast.AsPat asPat) {
    asPat.id.accept(this);
    asPat.pat.accept(this);
  }

  protected void visit(Ast.ConPat conPat) {
    conPat.tyCon.accept(this);
    conPat.pat.accept(this);
  }

  protected void visit(Ast.Con0Pat con0Pat) {
    con0Pat.tyCon.accept(this);
  }

  // value constructors

  protected void visit(Ast.Tuple tuple) {
    tuple.args.forEach(this::accept);
  }

  protected void visit(Ast.ListExp list) {
    list.args.forEach(this::accept);
  }

  protected void visit(Ast.Record record) {
    record.args.rightList().forEach(this::accept);
  }

  // functions and matches

  protected void visit(Ast.Fn fn) {
    fn.matchList.forEach(this::accept);
  }

  protected void visit(Ast.Apply apply) {
    apply.fn.accept(this);
    apply.arg.accept(this);
  }

  protected void visit(Ast.RecordSelector recordSelector) {}

  protected void visit(Ast.Match match) {
    match.pat.accept(this);
    match.exp.accept(this);
  }

  // types

  protected void visit(Ast.NamedType namedType) {
    namedType.types.forEach(this::accept);
  }

  protected void visit(Ast.TyVar tyVar) {}

  // declarations

  protected void visit(Ast.OverDecl overDecl) {}

  protected void visit(Ast.FunDecl funDecl) {
    funDecl.funBinds.forEach(this::accept);
  }

  protected void visit(Ast.FunBind funBind) {
    funBind.matchList.forEach(this::accept);
  }

  protected void visit(Ast.FunMatch funMatch) {
    funMatch.patList.forEach(this::accept);
    funMatch.exp.accept(this);
  }

  protected void visit(Ast.ValDecl valDecl) {
    valDecl.valBinds.forEach(this::accept);
  }

  protected void visit(Ast.ValBind valBind) {
    valBind.pat.accept(this);
    valBind.exp.accept(this);
  }

  protected void visit(Ast.From from) {
    from.steps.forEach(this::accept);
  }

  protected void visit(Ast.Exists exists) {
    exists.steps.forEach(this::accept);
  }

  protected void visit(Ast.Forall forall) {
    forall.steps.forEach(this::accept);
  }

  protected void visit(Ast.Scan scan) {
    scan.pat.accept(this);
    if (scan.exp != null) {
      scan.exp.accept(this);
    }
    if (scan.condition != null) {
      scan.condition.accept(this);
    }
  }

  protected void visit(Ast.Order order) {
    order.exp.accept(this);
  }

  protected void visit(Ast.Distinct distinct) {}

  protected void visit(Ast.Where where) {
    where.exp.accept(this);
  }

  protected void visit(Ast.Require require) {
    require.exp.accept(this);
  }

  protected void visit(Ast.Skip skip) {
    skip.exp.accept(this);
  }

  protected void visit(Ast.Take take) {
    take.exp.accept(this);
  }

  protected void visit(Ast.Except except) {
    except.args.forEach(this::accept);
  }

  protected void visit(Ast.Intersect intersect) {
    intersect.args.forEach(this::accept);
  }

  protected void visit(Ast.Union union) {
    union.args.forEach(this::accept);
  }

  protected void visit(Ast.Unorder unorder) {}

  protected void visit(Ast.Yield yield) {
    yield.exp.accept(this);
  }

  protected void visit(Ast.Into into) {
    into.exp.accept(this);
  }

  protected void visit(Ast.Through through) {
    through.pat.accept(this);
    through.exp.accept(this);
  }

  protected void visit(Ast.Compute compute) {
    compute.aggregates.forEach(this::accept);
  }

  protected void visit(Ast.Group group) {
    group.groupExps.forEach(
        (id, exp) -> {
          id.accept(this);
          exp.accept(this);
        });
    group.aggregates.forEach(this::accept);
  }

  protected void visit(Ast.Aggregate aggregate) {
    aggregate.aggregate.accept(this);
    if (aggregate.argument != null) {
      aggregate.argument.accept(this);
    }
    aggregate.id.accept(this);
  }

  protected void visit(Ast.DatatypeDecl datatypeDecl) {
    datatypeDecl.binds.forEach(this::accept);
  }

  protected void visit(Ast.DatatypeBind datatypeBind) {
    datatypeBind.tyVars.forEach(this::accept);
    datatypeBind.tyCons.forEach(this::accept);
  }

  protected void visit(Ast.TyCon tyCon) {
    if (tyCon.type != null) {
      tyCon.type.accept(this);
    }
    tyCon.id.accept(this);
  }

  protected void visit(Ast.RecordType recordType) {
    recordType.fieldTypes.values().forEach(this::accept);
  }

  protected void visit(Ast.TupleType tupleType) {
    tupleType.types.forEach(this::accept);
  }

  protected void visit(Ast.FunctionType functionType) {
    functionType.paramType.accept(this);
    functionType.resultType.accept(this);
  }

  protected void visit(Ast.CompositeType compositeType) {
    compositeType.types.forEach(this::accept);
  }

  // core expressions

  protected void visit(Core.Literal literal) {}

  protected void visit(Core.Id id) {}

  protected void visit(Core.Let let) {
    let.decl.accept(this);
    let.exp.accept(this);
  }

  protected void visit(Core.Local local) {
    local.exp.accept(this);
  }

  protected void visit(Core.Case kase) {
    kase.exp.accept(this);
    kase.matchList.forEach(this::accept);
  }

  protected void visit(Core.Apply apply) {
    apply.fn.accept(this);
    apply.arg.accept(this);
  }

  protected void visit(Core.RecordSelector recordSelector) {}

  protected void visit(Core.Tuple tuple) {
    tuple.args.forEach(this::accept);
  }

  protected void visit(Core.OverDecl overDecl) {}

  protected void visit(Core.DatatypeDecl datatypeDecl) {}

  protected void visit(Core.Fn fn) {
    fn.idPat.accept(this);
    fn.exp.accept(this);
  }

  protected void visit(Core.Match match) {
    match.pat.accept(this);
    match.exp.accept(this);
  }

  protected void visit(Core.From from) {
    from.steps.forEach(step -> step.accept(this));
  }

  protected void visit(Core.Scan scan) {
    scan.pat.accept(this);
    scan.exp.accept(this);
    if (scan.condition != null) {
      scan.condition.accept(this);
    }
  }

  protected void visit(Core.Where where) {
    where.exp.accept(this);
  }

  protected void visit(Core.Skip skip) {
    skip.exp.accept(this);
  }

  protected void visit(Core.Take take) {
    take.exp.accept(this);
  }

  protected void visit(Core.Except except) {
    except.args.forEach(this::accept);
  }

  protected void visit(Core.Intersect intersect) {
    intersect.args.forEach(this::accept);
  }

  protected void visit(Core.Union union) {
    union.args.forEach(this::accept);
  }

  protected void visit(Core.NonRecValDecl valDecl) {
    valDecl.pat.accept(this);
    valDecl.exp.accept(this);
  }

  protected void visit(Core.RecValDecl recValDecl) {
    recValDecl.list.forEach(this::accept);
  }

  protected void visit(Core.Group group) {
    group.groupExps.values().forEach(this::accept);
    group.aggregates.values().forEach(this::accept);
  }

  protected void visit(Core.Aggregate aggregate) {
    aggregate.aggregate.accept(this);
    if (aggregate.argument != null) {
      aggregate.argument.accept(this);
    }
  }

  protected void visit(Core.Order order) {
    order.exp.accept(this);
  }

  protected void visit(Core.Yield yield) {
    yield.exp.accept(this);
  }

  protected void visit(Core.Unorder unorder) {}

  protected void visit(Core.TuplePat tuplePat) {
    tuplePat.args.forEach(this::accept);
  }

  protected void visit(Core.RecordPat recordPat) {
    recordPat.args.forEach(this::accept);
  }

  protected void visit(Core.ListPat listPat) {
    listPat.args.forEach(this::accept);
  }

  protected void visit(Core.ConPat conPat) {
    conPat.pat.accept(this);
  }

  protected void visit(Core.Con0Pat con0Pat) {}

  protected void visit(Core.IdPat idPat) {}

  protected void visit(Core.AsPat asPat) {
    asPat.pat.accept(this);
  }

  protected void visit(Core.LiteralPat idPat) {}

  protected void visit(Core.WildcardPat wildcardPat) {}
}

// End Visitor.java
