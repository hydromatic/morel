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

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.util.ImmutablePairList;
import net.hydromatic.morel.util.PairList;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Builds parse tree nodes. */
public enum AstBuilder {
  /**
   * The singleton instance of the AST builder. The short name is convenient for
   * use via 'import static', but checkstyle does not approve.
   */
  // CHECKSTYLE: IGNORE 1
  ast;

  private static final Ast.Id MISSING_ID = new Ast.Id(Pos.ZERO, "");

  /**
   * Returns the implicit label for when an expression occurs within a record,
   * or null if no label can be deduced.
   *
   * <p>For example,
   *
   * <pre>{@code {x.a, y, z = x.b + 2} }</pre>
   *
   * <p>is equivalent to
   *
   * <pre>{@code {a = x.a, y = y, z = x.b + 2} }</pre>
   *
   * <p>because a field reference {@code x.a} has implicit label {@code a}, and
   * a variable reference {@code y} has implicit label {@code y}. The expression
   * {@code x.b + 2} has no implicit label.
   */
  public @Nullable String implicitLabelOpt(Ast.Exp exp) {
    switch (exp.op) {
      case CURRENT:
      case ELEMENTS:
      case ORDINAL:
        return exp.op.lowerName();
      case ID:
        return ((Ast.Id) exp).name;
      case AGGREGATE:
        final Ast.Aggregate aggregate = (Ast.Aggregate) exp;
        return implicitLabelOpt(aggregate.aggregate);
      case APPLY:
        final Ast.Apply apply = (Ast.Apply) exp;
        if (apply.fn instanceof Ast.RecordSelector) {
          final Ast.RecordSelector selector = (Ast.RecordSelector) apply.fn;
          return selector.name;
        }
        // fall through
      default:
        return null;
    }
  }

  /** Returns an expression's implicit label, or throws. */
  public Ast.Id implicitLabel(Ast.Exp exp) {
    String value = implicitLabelOpt(exp);
    if (value != null) {
      return id(exp.pos, value);
    }
    String message = format("cannot derive label for expression %s", exp);
    throw new CompileException(message, false, exp.pos);
  }

  /** Returns an expression's implicit label, or uses a default. */
  public Ast.Id implicitLabel(Ast.Exp exp, String defaultLabel) {
    requireNonNull(defaultLabel);
    String value = implicitLabelOpt(exp);
    if (value != null) {
      return id(exp.pos, value);
    }
    return id(exp.pos, defaultLabel);
  }

  /**
   * Converts an expression to a record.
   *
   * <p>If it is a record, ensures that every field has a label. Throws if an
   * implicit label cannot be derived for any field expression.
   *
   * <p>If it is not a record, returns a singleton record, using the implicit
   * label. Throws if an implicit label cannot be derived.
   */
  public Ast.Record toRecord(Ast.Exp exp, String defaultLabel) {
    if (exp instanceof Ast.Record) {
      return ((Ast.Record) exp).validate();
    }
    final Ast.Id label = implicitLabel(exp, defaultLabel);
    return ast.record(Pos.ZERO, null, ImmutablePairList.of(label, exp));
  }

  /** Returns whether an expression is a record with one field. */
  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  public boolean isSingletonRecord(Ast.@Nullable Exp exp) {
    return exp instanceof Ast.Record && ((Ast.Record) exp).args.size() == 1;
  }

  /** Returns whether an expression is a record with no fields. */
  public boolean isEmptyRecord(AstNode exp) {
    return exp instanceof Ast.Record && ((Ast.Record) exp).args.isEmpty();
  }

  /** Returns the number of fields emitted by an expression. */
  public int fieldCount(Ast.@Nullable Exp exp) {
    return exp == null
        ? 0
        : exp instanceof Ast.Record ? ((Ast.Record) exp).args.size() : 1;
  }

  /** Creates a call to an infix operator. */
  private Ast.InfixCall infix(Op op, Ast.Exp a0, Ast.Exp a1) {
    return new Ast.InfixCall(a0.pos.plus(a1.pos), op, a0, a1);
  }

  /** Creates a call to a prefix operator. */
  public Ast.PrefixCall prefixCall(Pos p, Op op, Ast.Exp a) {
    return new Ast.PrefixCall(p.plus(a.pos), op, a);
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
  public Ast.Literal intLiteral(Pos pos, BigDecimal value) {
    return new Ast.Literal(pos, Op.INT_LITERAL, value);
  }

  /** Creates a {@code float} literal. */
  public Ast.Literal realLiteral(Pos pos, BigDecimal value) {
    return new Ast.Literal(pos, Op.REAL_LITERAL, value);
  }

  /**
   * Creates a {@code float} literal for a special IEEE floating point value:
   * NaN, negative zero, positive and negative infinity.
   */
  public Ast.Literal realLiteral(Pos pos, Float value) {
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

  public Ast.Current current(Pos pos) {
    return new Ast.Current(pos);
  }

  public Ast.Elements elements(Pos pos) {
    return new Ast.Elements(pos);
  }

  public Ast.Ordinal ordinal(Pos pos) {
    return new Ast.Ordinal(pos);
  }

  public Ast.Id id(Pos pos, String name) {
    return new Ast.Id(pos, name);
  }

  public Ast.OpSection opSection(Pos pos, String name) {
    return new Ast.OpSection(pos, name);
  }

  /**
   * Returns an identifier that is the empty string, and is used to indicate a
   * missing label in a record expression.
   *
   * <p>An empty identifier can never be created by the parser, so this
   * identifier is clearly distinguished.
   *
   * <p>An example of a missing label is the first field in {@code {w.x, y =
   * 2}}. This expression is equivalent to {@code {x = w.x, y = 2}} when the
   * implicit "{@code x =}" label has been added.
   *
   * @see #implicitLabelOpt(Ast.Exp)
   */
  public Ast.Id missingId() {
    return MISSING_ID;
  }

  public Ast.TyVar tyVar(Pos pos, String name) {
    return new Ast.TyVar(pos, name);
  }

  public Ast.RecordType recordType(Pos pos, Map<String, Ast.Type> fieldTypes) {
    return new Ast.RecordType(pos, ImmutableMap.copyOf(fieldTypes));
  }

  public Ast.RecordSelector recordSelector(Pos pos, String name) {
    return new Ast.RecordSelector(pos, name);
  }

  public Ast.Type namedType(
      Pos pos, Iterable<? extends Ast.Type> types, String name) {
    return new Ast.NamedType(pos, ImmutableList.copyOf(types), name);
  }

  public Ast.Type expressionType(Pos pos, Ast.Exp exp) {
    return new Ast.ExpressionType(pos, exp);
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

  @SuppressWarnings("rawtypes")
  public Ast.LiteralPat literalPat(Pos pos, Op op, Comparable value) {
    return new Ast.LiteralPat(pos, op, value);
  }

  public Ast.WildcardPat wildcardPat(Pos pos) {
    return new Ast.WildcardPat(pos);
  }

  public Ast.AsPat asPat(Pos pos, Ast.IdPat id, Ast.Pat pat) {
    return new Ast.AsPat(pos, id, pat);
  }

  public Ast.ConPat conPat(Pos pos, Ast.Id tyCon, Ast.Pat pat) {
    return new Ast.ConPat(pos, tyCon, pat);
  }

  public Ast.Con0Pat con0Pat(Pos pos, Ast.Id tyCon) {
    return new Ast.Con0Pat(pos, tyCon);
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

  public Ast.RecordPat recordPat(
      Pos pos, boolean ellipsis, Map<String, ? extends Ast.Pat> args) {
    return new Ast.RecordPat(
        pos, ellipsis, ImmutableSortedMap.copyOf(args, RecordType.ORDERING));
  }

  public Ast.AnnotatedPat annotatedPat(Pos pos, Ast.Pat pat, Ast.Type type) {
    return new Ast.AnnotatedPat(pos, pat, type);
  }

  public Ast.InfixPat consPat(Ast.Pat p0, Ast.Pat p1) {
    return infixPat(p0.pos.plus(p1.pos), Op.CONS_PAT, p0, p1);
  }

  public Ast.Tuple tuple(Pos pos, Iterable<? extends Ast.Exp> list) {
    return new Ast.Tuple(pos, list);
  }

  public Ast.ListExp list(Pos pos, Iterable<? extends Ast.Exp> list) {
    return new Ast.ListExp(pos, list);
  }

  public Ast.Record record(
      Pos pos, Ast.@Nullable Exp with, PairList<Ast.Id, Ast.Exp> args) {
    return new Ast.Record(pos, with, ImmutablePairList.copyOf(args));
  }

  public Ast.Record record(
      Pos pos,
      Ast.@Nullable Exp with,
      Collection<Map.Entry<Ast.Id, Ast.Exp>> args) {
    return record(pos, with, PairList.copyOf(args));
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

  public Ast.Exp elem(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.ELEM, a0, a1);
  }

  public Ast.Exp notElem(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.NOT_ELEM, a0, a1);
  }

  public Ast.Exp andAlso(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.ANDALSO, a0, a1);
  }

  public Ast.Exp orElse(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.ORELSE, a0, a1);
  }

  public Ast.Exp implies(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.IMPLIES, a0, a1);
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

  public Ast.Exp o(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.COMPOSE, a0, a1);
  }

  public Ast.Exp negate(Pos p, Ast.Exp a) {
    return prefixCall(p, Op.NEGATE, a);
  }

  public Ast.Exp cons(Ast.Exp a0, Ast.Exp a1) {
    return infix(Op.CONS, a0, a1);
  }

  public Ast.Exp foldCons(List<Ast.Exp> list) {
    return foldRight(list, this::cons);
  }

  public Ast.Let let(Pos pos, Iterable<? extends Ast.Decl> decls, Ast.Exp exp) {
    return new Ast.Let(pos, ImmutableList.copyOf(decls), exp);
  }

  public Ast.ValDecl valDecl(
      Pos pos,
      boolean rec,
      boolean inst,
      Iterable<? extends Ast.ValBind> valBinds) {
    return new Ast.ValDecl(pos, rec, inst, ImmutableList.copyOf(valBinds));
  }

  public Ast.ValDecl valDecl(
      Pos pos, boolean rec, boolean inst, Ast.ValBind... valBinds) {
    return new Ast.ValDecl(pos, rec, inst, ImmutableList.copyOf(valBinds));
  }

  public Ast.ValBind valBind(Pos pos, Ast.Pat pat, Ast.Exp exp) {
    return new Ast.ValBind(pos, pat, exp);
  }

  public Ast.Match match(Pos pos, Ast.Pat pat, Ast.Exp exp) {
    return new Ast.Match(pos, pat, exp);
  }

  public Ast.Case caseOf(
      Pos pos, Ast.Exp exp, Iterable<? extends Ast.Match> matchList) {
    return new Ast.Case(pos, exp, ImmutableList.copyOf(matchList));
  }

  public Ast.Exists exists(Pos pos, List<Ast.FromStep> steps) {
    return new Ast.Exists(pos, ImmutableList.copyOf(steps));
  }

  public Ast.Forall forall(Pos pos, List<Ast.FromStep> steps) {
    return new Ast.Forall(pos, ImmutableList.copyOf(steps));
  }

  public Ast.From from(Pos pos, List<Ast.FromStep> steps) {
    return new Ast.From(pos, ImmutableList.copyOf(steps));
  }

  /** Wraps an expression to distinguish "from x = e" from "from x in e". */
  public Ast.Exp fromEq(Ast.Exp exp) {
    return new Ast.PrefixCall(exp.pos, Op.FROM_EQ, exp);
  }

  public Ast.Fn fn(Pos pos, Ast.Match... matchList) {
    return new Ast.Fn(pos, ImmutableList.copyOf(matchList));
  }

  public Ast.Fn fn(Pos pos, Iterable<? extends Ast.Match> matchList) {
    return new Ast.Fn(pos, ImmutableList.copyOf(matchList));
  }

  public Ast.OverDecl overDecl(Pos pos, Ast.IdPat id) {
    return new Ast.OverDecl(pos, id);
  }

  public Ast.FunDecl funDecl(
      Pos pos, Iterable<? extends Ast.FunBind> valBinds) {
    return new Ast.FunDecl(pos, ImmutableList.copyOf(valBinds));
  }

  public Ast.FunBind funBind(
      Pos pos, Iterable<? extends Ast.FunMatch> matchList) {
    return new Ast.FunBind(pos, ImmutableList.copyOf(matchList));
  }

  public Ast.FunMatch funMatch(
      Pos pos,
      String name,
      Iterable<? extends Ast.Pat> patList,
      Ast.@Nullable Type returnType,
      Ast.Exp exp) {
    return new Ast.FunMatch(
        pos, name, ImmutableList.copyOf(patList), returnType, exp);
  }

  public Ast.Apply apply(Ast.Exp fn, Ast.Exp arg) {
    return new Ast.Apply(fn.pos.plus(arg.pos), fn, arg);
  }

  public Ast.Exp ifThenElse(
      Pos pos, Ast.Exp condition, Ast.Exp ifTrue, Ast.Exp ifFalse) {
    return new Ast.If(pos, condition, ifTrue, ifFalse);
  }

  public Ast.InfixPat infixPat(Pos pos, Op op, Ast.Pat p0, Ast.Pat p1) {
    return new Ast.InfixPat(pos, op, p0, p1);
  }

  public Ast.AnnotatedExp annotatedExp(
      Pos pos, Ast.Exp expression, Ast.Type type) {
    return new Ast.AnnotatedExp(pos, expression, type);
  }

  public Ast.Exp infixCall(Pos pos, Op op, Ast.Exp a0, Ast.Exp a1) {
    return new Ast.InfixCall(pos, op, a0, a1);
  }

  public Ast.TypeDecl typeDecl(Pos pos, Iterable<Ast.TypeBind> binds) {
    return new Ast.TypeDecl(pos, ImmutableList.copyOf(binds));
  }

  public Ast.TypeBind typeBind(
      Pos pos, Ast.Id name, Iterable<Ast.TyVar> tyVars, Ast.Type type) {
    return new Ast.TypeBind(pos, ImmutableList.copyOf(tyVars), name, type);
  }

  public Ast.DatatypeDecl datatypeDecl(
      Pos pos, Iterable<Ast.DatatypeBind> binds) {
    return new Ast.DatatypeDecl(pos, ImmutableList.copyOf(binds));
  }

  public Ast.DatatypeBind datatypeBind(
      Pos pos,
      Ast.Id name,
      Iterable<Ast.TyVar> tyVars,
      Iterable<Ast.TyCon> tyCons) {
    return new Ast.DatatypeBind(
        pos, ImmutableList.copyOf(tyVars), name, ImmutableList.copyOf(tyCons));
  }

  public Ast.TyCon typeConstructor(Pos pos, Ast.Id id, Ast.Type type) {
    return new Ast.TyCon(pos, id, type);
  }

  public Ast.Type tupleType(Pos pos, Iterable<Ast.Type> types) {
    return new Ast.TupleType(pos, ImmutableList.copyOf(types));
  }

  public Ast.Type compositeType(Pos pos, Iterable<Ast.Type> types) {
    return new Ast.CompositeType(pos, ImmutableList.copyOf(types));
  }

  public Ast.FunctionType functionType(
      Pos pos, Ast.Type fromType, Ast.Type toType) {
    return new Ast.FunctionType(pos, fromType, toType);
  }

  public Ast.Type foldFunctionType(List<Ast.Type> types) {
    return foldRight(
        types, (t1, t2) -> functionType(t1.pos.plus(t2.pos), t1, t2));
  }

  private <E> E foldRight(List<E> list, BiFunction<E, E, E> fold) {
    E e = list.get(list.size() - 1);
    for (int i = list.size() - 2; i >= 0; i--) {
      e = fold.apply(list.get(i), e);
    }
    return e;
  }

  public Ast.Aggregate aggregate(Pos pos, Ast.Exp aggregate, Ast.Exp argument) {
    return new Ast.Aggregate(pos, aggregate, argument);
  }

  /**
   * Returns a reference to a built-in: either a name (e.g. "true") or a field
   * reference (e.g. "#hd List").
   */
  public Ast.Exp ref(Pos pos, BuiltIn builtIn) {
    if (builtIn.structure == null) {
      return id(pos, builtIn.mlName);
    } else {
      return apply(
          recordSelector(pos, builtIn.mlName), id(pos, builtIn.structure));
    }
  }

  public Ast.Exp map(Pos pos, Ast.Exp e1, Ast.Exp e2) {
    return apply(apply(ref(pos, BuiltIn.LIST_MAP), e1), e2);
  }

  public Ast.Scan scan(
      Pos pos, Ast.Pat pat, Ast.Exp exp, Ast.@Nullable Exp condition) {
    return new Ast.Scan(pos, pat, exp, condition);
  }

  public Ast.Order order(Pos pos, Ast.Exp exp) {
    return new Ast.Order(pos, exp);
  }

  public Ast.Compute compute(Pos pos, Ast.Exp aggregate) {
    return new Ast.Compute(pos, aggregate);
  }

  public Ast.Group group(Pos pos, Ast.Exp groupExp, Ast.Exp aggregate) {
    return new Ast.Group(pos, Op.GROUP, groupExp, aggregate);
  }

  public Ast.FromStep where(Pos pos, Ast.Exp exp) {
    return new Ast.Where(pos, exp);
  }

  public Ast.FromStep distinct(Pos pos) {
    return new Ast.Distinct(pos);
  }

  public Ast.FromStep require(Pos pos, Ast.Exp exp) {
    return new Ast.Require(pos, exp);
  }

  public Ast.FromStep skip(Pos pos, Ast.Exp exp) {
    return new Ast.Skip(pos, exp);
  }

  public Ast.FromStep take(Pos pos, Ast.Exp exp) {
    return new Ast.Take(pos, exp);
  }

  public Ast.Except except(
      Pos pos, boolean distinct, Iterable<? extends Ast.Exp> args) {
    return new Ast.Except(pos, distinct, ImmutableList.copyOf(args));
  }

  public Ast.Intersect intersect(
      Pos pos, boolean distinct, Iterable<? extends Ast.Exp> args) {
    return new Ast.Intersect(pos, distinct, ImmutableList.copyOf(args));
  }

  public Ast.Union union(
      Pos pos, boolean distinct, Iterable<? extends Ast.Exp> args) {
    return new Ast.Union(pos, distinct, ImmutableList.copyOf(args));
  }

  public Ast.FromStep unorder(Pos pos) {
    return new Ast.Unorder(pos);
  }

  public Ast.FromStep yield(Pos pos, Ast.Exp exp) {
    return new Ast.Yield(pos, exp);
  }

  public Ast.FromStep into(Pos pos, Ast.Exp exp) {
    return new Ast.Into(pos, exp);
  }

  public Ast.FromStep through(Pos pos, Ast.Pat pat, Ast.Exp exp) {
    return new Ast.Through(pos, pat, exp);
  }
}

// End AstBuilder.java
