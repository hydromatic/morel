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
package net.hydromatic.morel.compile;

import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.util.ConsList;
import net.hydromatic.morel.util.Pair;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static net.hydromatic.morel.ast.CoreBuilder.core;

/** Converts AST expressions to Core expressions. */
public class Resolver {
  /** Map from {@link Op} to {@link BuiltIn}. */
  public static final ImmutableMap<Op, BuiltIn> OP_BUILT_IN_MAP =
      Init.INSTANCE.opBuiltInMap;

  /** Map from {@link BuiltIn}, to {@link Op};
   * the reverse of {@link #OP_BUILT_IN_MAP}, and needed when we convert
   * an optimized expression back to human-readable Morel code. */
  public static final ImmutableMap<BuiltIn, Op> BUILT_IN_OP_MAP =
      Init.INSTANCE.builtInOpMap;

  final TypeMap typeMap;

  public Resolver(TypeMap typeMap) {
    this.typeMap = typeMap;
  }

  private static <E, T> ImmutableList<T> transform(Iterable<? extends E> elements,
      Function<E, T> mapper) {
    final ImmutableList.Builder<T> b = ImmutableList.builder();
    elements.forEach(e -> b.add(mapper.apply(e)));
    return b.build();
  }

  public Core.Decl toCore(Ast.Decl node) {
    switch (node.op) {
    case VAL_DECL:
      return toCore((Ast.ValDecl) node);

    case DATATYPE_DECL:
      return toCore((Ast.DatatypeDecl) node);

    default:
      throw new AssertionError("unknown decl [" + node.op + ", " + node + "]");
    }
  }

  public Core.ValDecl toCore(Ast.ValDecl valDecl) {
    if (valDecl.valBinds.size() > 1) {
      // Transform "let val v1 = e1 and v2 = e2 in e"
      // to "let val (v1, v2) = (e1, e2) in e"
      final Map<Ast.Pat, Ast.Exp> matches = new LinkedHashMap<>();
      boolean rec = false;
      for (Ast.ValBind valBind : valDecl.valBinds) {
        flatten(matches, valBind.pat, valBind.e);
        rec |= valBind.rec;
      }
      final List<Type> types = new ArrayList<>();
      final List<Core.Pat> pats = new ArrayList<>();
      final List<Core.Exp> exps = new ArrayList<>();
      matches.forEach((pat, exp) -> {
        types.add(typeMap.getType(pat));
        pats.add(toCore(pat));
        exps.add(toCore(exp));
      });
      final RecordLikeType tupleType = typeMap.typeSystem.tupleType(types);
      final Core.Pat pat = core.tuplePat(tupleType, pats);
      final Core.Exp e2 = core.tuple(tupleType, exps);
      return core.valDecl(rec, pat, e2);
    } else {
      Ast.ValBind valBind = valDecl.valBinds.get(0);
      return core.valDecl(valBind.rec, toCore(valBind.pat), toCore(valBind.e));
    }
  }

  private Core.DatatypeDecl toCore(Ast.DatatypeDecl datatypeDecl) {
    return core.datatypeDecl(transform(datatypeDecl.binds, this::toCore));
  }

  private DataType toCore(Ast.DatatypeBind bind) {
    return (DataType) typeMap.typeSystem.lookup(bind.name.name);
  }

  private Core.Exp toCore(Ast.Exp e) {
    switch (e.op) {
    case BOOL_LITERAL:
      return core.boolLiteral((Boolean) ((Ast.Literal) e).value);
    case CHAR_LITERAL:
      return core.charLiteral((Character) ((Ast.Literal) e).value);
    case INT_LITERAL:
      return core.intLiteral((BigDecimal) ((Ast.Literal) e).value);
    case REAL_LITERAL:
      return core.realLiteral((BigDecimal) ((Ast.Literal) e).value);
    case STRING_LITERAL:
      return core.stringLiteral((String) ((Ast.Literal) e).value);
    case UNIT_LITERAL:
      return core.unitLiteral();
    case ID:
      return toCore((Ast.Id) e);
    case ANDALSO:
    case ORELSE:
      return toCore((Ast.InfixCall) e);
    case APPLY:
      return toCore((Ast.Apply) e);
    case FN:
      return toCore((Ast.Fn) e);
    case IF:
      return toCore((Ast.If) e);
    case CASE:
      return toCore((Ast.Case) e);
    case LET:
      return toCore((Ast.Let) e);
    case FROM:
      return toCore((Ast.From) e);
    case TUPLE:
      return toCore((Ast.Tuple) e);
    case RECORD:
      return toCore((Ast.Record) e);
    case RECORD_SELECTOR:
      return toCore((Ast.RecordSelector) e);
    case LIST:
      return toCore((Ast.ListExp) e);
    default:
      throw new AssertionError("unknown exp " + e.op);
    }
  }

  private Core.Id toCore(Ast.Id id) {
    return core.id(typeMap.getType(id), id.name);
  }

  private Core.Tuple toCore(Ast.Tuple tuple) {
    return core.tuple((RecordLikeType) typeMap.getType(tuple),
        transform(tuple.args, this::toCore));
  }

  private Core.Tuple toCore(Ast.Record record) {
    return core.tuple((RecordLikeType) typeMap.getType(record),
        transform(record.args(), this::toCore));
  }

  private Core.Exp toCore(Ast.ListExp list) {
    final ListType type = (ListType) typeMap.getType(list);
    return core.apply(type,
        core.functionLiteral(typeMap.typeSystem, BuiltIn.Z_LIST),
        core.tuple(typeMap.typeSystem, null,
            transform(list.args, this::toCore)));
  }

  private Core.Apply toCore(Ast.Apply apply) {
    Core.Exp coreArg = toCore(apply.arg);
    Type type = typeMap.getType(apply);
    Core.Exp coreFn;
    if (apply.fn.op == Op.RECORD_SELECTOR) {
      final Ast.RecordSelector recordSelector = (Ast.RecordSelector) apply.fn;
      coreFn = core.recordSelector(typeMap.typeSystem,
          (RecordLikeType) coreArg.type, recordSelector.name);
    } else {
      coreFn = toCore(apply.fn);
    }
    return core.apply(type, coreFn, coreArg);
  }

  private Core.RecordSelector toCore(Ast.RecordSelector recordSelector) {
    final FnType fnType = (FnType) typeMap.getType(recordSelector);
    return core.recordSelector(typeMap.typeSystem,
        (RecordLikeType) fnType.paramType, recordSelector.name);
  }

  private Core.Apply toCore(Ast.InfixCall call) {
    Core.Exp core0 = toCore(call.a0);
    Core.Exp core1 = toCore(call.a1);
    final BuiltIn builtIn = toBuiltIn(call.op);
    return core.apply(typeMap.getType(call),
        core.functionLiteral(typeMap.typeSystem, builtIn),
        core.tuple(typeMap.typeSystem, null, ImmutableList.of(core0, core1)));
  }

  private BuiltIn toBuiltIn(Op op) {
    return OP_BUILT_IN_MAP.get(op);
  }

  private Core.Fn toCore(Ast.Fn fn) {
    return core.fn((FnType) typeMap.getType(fn),
        transform(fn.matchList, this::toCore));
  }

  private Core.Case toCore(Ast.If if_) {
    return core.ifThenElse(toCore(if_.condition), toCore(if_.ifTrue),
        toCore(if_.ifFalse));
  }

  private Core.Case toCore(Ast.Case case_) {
    Iterable<? extends Ast.Match> matchList = case_.matchList;
    Function<Ast.Match, Core.Match> toCore = this::toCore;
    return core.caseOf(typeMap.getType(case_), toCore(case_.e),
        transform(matchList, toCore));
  }

  private Core.Let toCore(Ast.Let let) {
    return flattenLet(let.decls, let.e);
  }

  private Core.Let flattenLet(List<Ast.Decl> decls, Ast.Exp e) {
    final Core.Exp e2 = decls.size() == 1
        ? toCore(e)
        : flattenLet(decls.subList(1, decls.size()), e);
    return core.let(toCore(decls.get(0)), e2);
  }

  private void flatten(Map<Ast.Pat, Ast.Exp> matches,
      Ast.Pat pat, Ast.Exp exp) {
    switch (pat.op) {
    case TUPLE_PAT:
      final Ast.TuplePat tuplePat = (Ast.TuplePat) pat;
      if (exp.op == Op.TUPLE) {
        final Ast.Tuple tuple = (Ast.Tuple) exp;
        Pair.forEach(tuplePat.args, tuple.args,
            (p, e) -> flatten(matches, p, e));
        break;
      }
      // fall through
    default:
      matches.put(pat, exp);
    }
  }

  private Core.Pat toCore(Ast.Pat pat) {
    final Type type = typeMap.getType(pat);
    return toCore(pat, type, type);
  }

  private Core.Pat toCore(Ast.Pat pat, Type targetType) {
    final Type type = typeMap.getType(pat);
    return toCore(pat, type, targetType);
  }

  /** Converts a pattern to Core.
   *
   * <p>Expands a pattern if it is a record pattern that has an ellipsis
   * or if the arguments are not in the same order as the labels in the type. */
  private Core.Pat toCore(Ast.Pat pat, Type type, Type targetType) {
    final TupleType tupleType;
    switch (pat.op) {
    case BOOL_LITERAL_PAT:
    case CHAR_LITERAL_PAT:
    case INT_LITERAL_PAT:
    case REAL_LITERAL_PAT:
    case STRING_LITERAL_PAT:
      return core.literalPat(pat.op, type, ((Ast.LiteralPat) pat).value);

    case WILDCARD_PAT:
      return core.wildcardPat(type);

    case ID_PAT:
      final Ast.IdPat idPat = (Ast.IdPat) pat;
      if (type.op() == Op.DATA_TYPE
          && ((DataType) type).typeConstructors.containsKey(idPat.name)) {
        return core.con0Pat((DataType) type, idPat.name);
      }
      return core.idPat(type, idPat.name);

    case CON_PAT:
      final Ast.ConPat conPat = (Ast.ConPat) pat;
      return core.conPat(type, conPat.tyCon.name, toCore(conPat.pat));

    case CON0_PAT:
      final Ast.Con0Pat con0Pat = (Ast.Con0Pat) pat;
      return core.con0Pat((DataType) type, con0Pat.tyCon.name);

    case CONS_PAT:
      // Cons "::" is an infix operator in Ast, a type constructor in Core, so
      // Ast.InfixPat becomes Core.ConPat.
      final Ast.InfixPat infixPat = (Ast.InfixPat) pat;
      final Type type0 = typeMap.getType(infixPat.p0);
      final Type type1 = typeMap.getType(infixPat.p1);
      tupleType = typeMap.typeSystem.tupleType(type0, type1);
      return core.consPat(type, BuiltIn.OP_CONS.mlName,
          core.tuplePat(tupleType, toCore(infixPat.p0), toCore(infixPat.p1)));

    case LIST_PAT:
      final Ast.ListPat listPat = (Ast.ListPat) pat;
      return core.listPat(type, transform(listPat.args, this::toCore));

    case RECORD_PAT:
      final RecordType recordType = (RecordType) targetType;
      final Ast.RecordPat recordPat = (Ast.RecordPat) pat;
      final ImmutableList.Builder<Core.Pat> args = ImmutableList.builder();
      recordType.argNameTypes.forEach((label, argType) -> {
        final Ast.Pat argPat = recordPat.args.get(label);
        final Core.Pat corePat = argPat != null ? toCore(argPat)
            : core.wildcardPat(argType);
        args.add(corePat);
      });
      return core.recordPat(recordType, args.build());

    case TUPLE_PAT:
      final Ast.TuplePat tuplePat = (Ast.TuplePat) pat;
      final List<Core.Pat> argList = transform(tuplePat.args, this::toCore);
      return core.tuplePat(type, argList);

    default:
      throw new AssertionError("unknown pat " + pat.op);
    }
  }

  private Core.Match toCore(Ast.Match match) {
    return core.match(toCore(match.pat), toCore(match.e));
  }

  Core.From toCore(Ast.From from) {
    final Map<Core.Pat, Core.Exp> sources = new LinkedHashMap<>();
    from.sources.forEach((pat, exp) -> {
      Core.Exp coreExp = toCore(exp);
      Core.Pat corePat = toCore(pat, ((ListType) coreExp.type).elementType);
      sources.put(corePat, coreExp);
    });
    return fromStepToCore(sources, from.steps,
        ImmutableList.of(), from.yieldExpOrDefault);
  }

  /** Returns a list with one element appended.
   *
   * @see ConsList */
  private static <E> List<E> append(List<E> list, E e) {
    return ImmutableList.<E>builder().addAll(list).add(e).build();
  }

  private Core.From fromStepToCore(Map<Core.Pat, Core.Exp> sources,
      List<Ast.FromStep> steps, List<Core.FromStep> coreSteps,
      Ast.Exp yieldExp) {
    if (steps.isEmpty()) {
      final Core.Exp coreYieldExp = toCore(yieldExp);
      final ListType listType = typeMap.typeSystem.listType(coreYieldExp.type);
      return core.from(listType, sources, coreSteps, coreYieldExp);
    }
    final Ast.FromStep step = steps.get(0);
    switch (step.op) {
    case WHERE:
      final Ast.Where where = (Ast.Where) step;
      final Core.FromStep coreWhere = core.where(toCore(where.exp));
      return fromStepToCore(sources, Util.skip(steps),
          append(coreSteps, coreWhere), yieldExp);

    case ORDER:
      final Ast.Order order = (Ast.Order) step;
      final Core.FromStep coreOrder =
          core.order(transform(order.orderItems, this::toCore));
      return fromStepToCore(sources, Util.skip(steps),
          append(coreSteps, coreOrder), yieldExp);

    case GROUP:
      final Ast.Group group = (Ast.Group) step;
      final ImmutableSortedMap.Builder<String, Core.Exp> groupExps =
          ImmutableSortedMap.orderedBy(RecordType.ORDERING);
      final ImmutableSortedMap.Builder<String, Core.Aggregate> aggregates =
          ImmutableSortedMap.orderedBy(RecordType.ORDERING);
      Pair.forEach(group.groupExps, (id, exp) ->
          groupExps.put(id.name, toCore(exp)));
      group.aggregates.forEach(aggregate ->
          aggregates.put(aggregate.id.name, toCore(aggregate)));
      final Core.FromStep coreGroup =
          core.group(groupExps.build(), aggregates.build());
      return fromStepToCore(sources, Util.skip(steps),
          append(coreSteps, coreGroup), yieldExp);

    default:
      throw new AssertionError("unknown step type " + step.op);
    }
  }

  private Core.Aggregate toCore(Ast.Aggregate aggregate) {
    return core.aggregate(typeMap.getType(aggregate),
        toCore(aggregate.aggregate),
        aggregate.argument == null ? null : toCore(aggregate.argument));
  }

  private Core.OrderItem toCore(Ast.OrderItem orderItem) {
    return core.orderItem(toCore(orderItem.exp), orderItem.direction);
  }

  /** Helper for initialization. */
  private enum Init {
    INSTANCE;

    final ImmutableMap<Op, BuiltIn> opBuiltInMap;
    final ImmutableMap<BuiltIn, Op> builtInOpMap;

    Init() {
      Object[] values = {
          BuiltIn.LIST_OP_AT, Op.AT,
          BuiltIn.OP_CONS, Op.CONS,
          BuiltIn.OP_EQ, Op.EQ,
          BuiltIn.OP_EXCEPT, Op.EXCEPT,
          BuiltIn.OP_GE, Op.GE,
          BuiltIn.OP_GT, Op.GT,
          BuiltIn.OP_INTERSECT, Op.INTERSECT,
          BuiltIn.OP_LE, Op.LE,
          BuiltIn.OP_LT, Op.LT,
          BuiltIn.OP_NE, Op.NE,
          BuiltIn.OP_UNION, Op.UNION,
          BuiltIn.Z_ANDALSO, Op.ANDALSO,
          BuiltIn.Z_ORELSE, Op.ORELSE,
          BuiltIn.Z_PLUS_INT, Op.PLUS,
          BuiltIn.Z_PLUS_REAL, Op.PLUS,
      };
      final ImmutableMap.Builder<BuiltIn, Op> b2o = ImmutableMap.builder();
      final Map<Op, BuiltIn> o2b = new HashMap<>();
      for (int i = 0; i < values.length / 2; i++) {
        BuiltIn builtIn = (BuiltIn) values[i * 2];
        Op op = (Op) values[i * 2 + 1];
        b2o.put(builtIn, op);
        o2b.put(op, builtIn);
      }
      builtInOpMap = b2o.build();
      opBuiltInMap = ImmutableMap.copyOf(o2b);
    }
  }
}

// End Resolver.java
