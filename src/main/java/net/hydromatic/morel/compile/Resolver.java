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

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.util.ConsList;
import net.hydromatic.morel.util.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import org.apache.calcite.util.Util;

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
  private final NameGenerator nameGenerator;
  private final Environment env;

  /** Contains variable declarations whose type at the point they are used is
   * different (more specific) than in their declaration.
   *
   * <p>For example, the infix operator "op +" has type
   * "&alpha; * &alpha; &rarr;" in the base environment, but at point of use
   * might instead be "int * int &rarr; int". This map will contain a new
   * {@link Core.IdPat} for all points that use it with that second type.
   * Effectively, it is a phantom declaration, in a {@code let} that doesn't
   * exist. Without this shared declaration, all points have their own distinct
   * {@link Core.IdPat}, which the {@link Analyzer} will think is used just
   * once.
   */
  private final Map<Pair<Core.IdPat, Type>, Core.IdPat> variantIdMap;

  private Resolver(TypeMap typeMap, NameGenerator nameGenerator,
      Map<Pair<Core.IdPat, Type>, Core.IdPat> variantIdMap, Environment env) {
    this.typeMap = typeMap;
    this.nameGenerator = nameGenerator;
    this.variantIdMap = variantIdMap;
    this.env = env;
  }

  /** Creates a root Resolver. */
  public static Resolver of(TypeMap typeMap, Environment env) {
    return new Resolver(typeMap, new NameGenerator(), new HashMap<>(), env);
  }

  /** Binds a Resolver to a new environment. */
  public Resolver withEnv(Environment env) {
    return env == this.env ? this
        : new Resolver(typeMap, nameGenerator, variantIdMap, env);
  }

  /** Binds a Resolver to an environment that consists of the current
   * environment plus some bindings. */
  public final Resolver withEnv(Iterable<Binding> bindings) {
    return withEnv(Environments.bind(env, bindings));
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

  /** Converts a simple {@link net.hydromatic.morel.ast.Ast.ValDecl},
   *  of the form {@code val v = e},
   *  to a Core {@link net.hydromatic.morel.ast.Core.ValDecl}.
   *
   *  <p>Declarations such as {@code val (x, y) = (1, 2)}
   *  and {@code val emp :: rest = emps} are considered complex,
   *  and are not handled by this method. */
  public Core.ValDecl toCore(Ast.ValDecl valDecl) {
    final List<Binding> bindings = new ArrayList<>(); // discard
    final ResolvedValDecl resolvedValDecl = resolveValDecl(valDecl, bindings);
    return core.valDecl(resolvedValDecl.rec, (Core.IdPat) resolvedValDecl.pat,
        resolvedValDecl.exp);
  }

  public Core.DatatypeDecl toCore(Ast.DatatypeDecl datatypeDecl) {
    final List<Binding> bindings = new ArrayList<>(); // populated, never read
    final ResolvedDatatypeDecl resolvedDatatypeDecl =
        resolveDatatypeDecl(datatypeDecl, bindings);
    return resolvedDatatypeDecl.toDecl();
  }

  private ResolvedDecl resolve(Ast.Decl decl, List<Binding> bindings) {
    if (decl instanceof Ast.DatatypeDecl) {
      return resolveDatatypeDecl((Ast.DatatypeDecl) decl, bindings);
    } else {
      return resolveValDecl((Ast.ValDecl) decl, bindings);
    }
  }

  private ResolvedDatatypeDecl resolveDatatypeDecl(Ast.DatatypeDecl decl,
      List<Binding> bindings) {
    final List<DataType> dataTypes = new ArrayList<>();
    for (Ast.DatatypeBind bind : decl.binds) {
      final DataType dataType = toCore(bind);
      dataTypes.add(dataType);
      dataType.typeConstructors.keySet().forEach(name ->
          bindings.add(typeMap.typeSystem.bindTyCon(dataType, name)));
    }
    return new ResolvedDatatypeDecl(ImmutableList.copyOf(dataTypes));
  }

  private ResolvedValDecl resolveValDecl(Ast.ValDecl valDecl,
      List<Binding> bindings) {
    final boolean rec;
    final Core.Pat pat2;
    final Core.Exp exp2;
    if (valDecl.valBinds.size() > 1) {
      // Transform "let val v1 = E1 and v2 = E2 in E end"
      // to "let val v = (v1, v2) in case v of (E1, E2) => E end"
      final Map<Ast.Pat, Ast.Exp> matches = new LinkedHashMap<>();
      boolean rec0 = false;
      for (Ast.ValBind valBind : valDecl.valBinds) {
        flatten(matches, valBind.pat, valBind.exp);
        rec0 |= valBind.rec;
      }
      rec = rec0;
      final List<Type> types = new ArrayList<>();
      final List<Core.Pat> pats = new ArrayList<>();
      final List<Core.Exp> exps = new ArrayList<>();
      matches.forEach((pat, exp) -> {
        types.add(typeMap.getType(pat));
        pats.add(toCore(pat));
      });
      final RecordLikeType tupleType = typeMap.typeSystem.tupleType(types);
      pat2 = core.tuplePat(tupleType, pats);
      Compiles.acceptBinding(typeMap.typeSystem, pat2, bindings);
      final Resolver r = rec ? withEnv(bindings) : this;
      matches.forEach((pat, exp) -> exps.add(r.toCore(exp)));
      exp2 = core.tuple(tupleType, exps);
    } else {
      final Ast.ValBind valBind = valDecl.valBinds.get(0);
      rec = valBind.rec;
      pat2 = toCore(valBind.pat);
      Compiles.acceptBinding(typeMap.typeSystem, pat2, bindings);
      final Resolver r = rec ? withEnv(bindings) : this;
      exp2 = r.toCore(valBind.exp);
    }
    return new ResolvedValDecl(rec, pat2, exp2);
  }

  private DataType toCore(Ast.DatatypeBind bind) {
    return (DataType) typeMap.typeSystem.lookup(bind.name.name);
  }

  private Core.Exp toCore(Ast.Exp exp) {
    switch (exp.op) {
    case BOOL_LITERAL:
      return core.boolLiteral((Boolean) ((Ast.Literal) exp).value);
    case CHAR_LITERAL:
      return core.charLiteral((Character) ((Ast.Literal) exp).value);
    case INT_LITERAL:
      return core.intLiteral((BigDecimal) ((Ast.Literal) exp).value);
    case REAL_LITERAL:
      return core.realLiteral((BigDecimal) ((Ast.Literal) exp).value);
    case STRING_LITERAL:
      return core.stringLiteral((String) ((Ast.Literal) exp).value);
    case UNIT_LITERAL:
      return core.unitLiteral();
    case ID:
      return toCore((Ast.Id) exp);
    case ANDALSO:
    case ORELSE:
      return toCore((Ast.InfixCall) exp);
    case APPLY:
      return toCore((Ast.Apply) exp);
    case FN:
      return toCore((Ast.Fn) exp);
    case IF:
      return toCore((Ast.If) exp);
    case CASE:
      return toCore((Ast.Case) exp);
    case LET:
      return toCore((Ast.Let) exp);
    case FROM:
      return toCore((Ast.From) exp);
    case TUPLE:
      return toCore((Ast.Tuple) exp);
    case RECORD:
      return toCore((Ast.Record) exp);
    case RECORD_SELECTOR:
      return toCore((Ast.RecordSelector) exp);
    case LIST:
      return toCore((Ast.ListExp) exp);
    case FROM_EQ:
      return toCoreFromEq(((Ast.PrefixCall) exp).a);
    default:
      throw new AssertionError("unknown exp " + exp.op);
    }
  }

  private Core.Id toCore(Ast.Id id) {
    final Binding binding = env.get(id.name);
    assert binding != null;
    final Core.IdPat idPat = getIdPat(id, binding);
    return core.id(idPat);
  }

  /** Converts an id in a declaration to Core. */
  private Core.IdPat toCorePat(Ast.Id id) {
    final Type type = typeMap.getType(id);
    return core.idPat(type, id.name, nameGenerator);
  }

  /** Converts an Id that is a reference to a variable into an IdPat that
   * represents its declaration. */
  private Core.IdPat getIdPat(Ast.Id id, Binding binding) {
    final Type type = typeMap.getType(id);
    if (type == binding.id.type) {
      return binding.id;
    }
    // The required type is different from the binding type, presumably more
    // specific. Create a new IdPat, reusing an existing IdPat if there was
    // one for the same type.
    return variantIdMap.computeIfAbsent(Pair.of(binding.id, type),
        k -> k.left.withType(k.right));
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

  /** Translates "x" in "from e = x". Desugar to the same as if they had
   * written "from e in [x]". */
  private Core.Exp toCoreFromEq(Ast.Exp exp) {
    final Type type = typeMap.getType(exp);
    final ListType listType = typeMap.typeSystem.listType(type);
    return core.apply(listType,
        core.functionLiteral(typeMap.typeSystem, BuiltIn.Z_LIST),
        core.tuple(typeMap.typeSystem, null, ImmutableList.of(toCore(exp))));
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
    final FnType type = (FnType) typeMap.getType(fn);
    final ImmutableList<Core.Match> matchList =
        transform(fn.matchList, this::toCore);
    if (matchList.size() == 1) {
      final Core.Match match = matchList.get(0);
      if (match.pat instanceof Core.IdPat) {
        // Simple function, "fn x => exp". Does not need 'case'.
        return core.fn(type, (Core.IdPat) match.pat, match.exp);
      }
      if (match.pat instanceof Core.TuplePat
          && ((Core.TuplePat) match.pat).args.isEmpty()) {
        // Simple function with unit arg, "fn () => exp";
        // needs a new variable, but doesn't need case, "fn (v0: unit) => exp"
        final Core.IdPat idPat = core.idPat(type.paramType, nameGenerator);
        return core.fn(type, idPat, match.exp);
      }
    }
    // Complex function, "fn (x, y) => exp";
    // needs intermediate variable and case, "fn v => case v of (x, y) => exp"
    final Core.IdPat idPat = core.idPat(type.paramType, nameGenerator);
    final Core.Id id = core.id(idPat);
    return core.fn(type, idPat, core.caseOf(type.resultType, id, matchList));
  }

  private Core.Case toCore(Ast.If if_) {
    return core.ifThenElse(toCore(if_.condition), toCore(if_.ifTrue),
        toCore(if_.ifFalse));
  }

  private Core.Case toCore(Ast.Case case_) {
    return core.caseOf(typeMap.getType(case_), toCore(case_.exp),
        transform(case_.matchList, this::toCore));
  }

  private Core.Exp toCore(Ast.Let let) {
    return flattenLet(let.decls, let.exp);
  }

  private Core.Exp flattenLet(List<Ast.Decl> decls, Ast.Exp exp) {
    //   flattenLet(val x :: xs = [1, 2, 3] and (y, z) = (2, 4), x + y)
    // becomes
    //   let v = ([1, 2, 3], (2, 4)) in case v of (x :: xs, (y, z)) => x + y end
    if (decls.size() == 0) {
      return toCore(exp);
    }
    final Ast.Decl decl = decls.get(0);
    final List<Binding> bindings = new ArrayList<>();
    final ResolvedDecl resolvedDecl = resolve(decl, bindings);
    final Core.Exp e2 = withEnv(bindings).flattenLet(Util.skip(decls), exp);
    return resolvedDecl.toExp(e2);
  }

  @SuppressWarnings("SwitchStatementWithTooFewBranches")
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
      return core.idPat(type, idPat.name, nameGenerator);

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
    final Core.Pat pat = toCore(match.pat);
    final List<Binding> bindings = new ArrayList<>();
    Compiles.acceptBinding(typeMap.typeSystem, pat, bindings);
    final Core.Exp exp = withEnv(bindings).toCore(match.exp);
    return core.match(pat, exp);
  }

  Core.Exp toCore(Ast.From from) {
    final List<Binding> bindings = new ArrayList<>();
    final Type type = typeMap.getType(from);
    if (from.isCompute()) {
      final ListType listType = typeMap.typeSystem.listType(type);
      final Core.From coreFrom =
          fromStepToCore(bindings, listType, from.steps,
              ImmutableList.of());
      return core.apply(type,
          core.functionLiteral(typeMap.typeSystem, BuiltIn.RELATIONAL_ONLY),
          coreFrom);
    } else {
      return fromStepToCore(bindings, (ListType) type, from.steps,
          ImmutableList.of());
    }
  }

  /** Returns a list with one element appended.
   *
   * @see ConsList */
  static <E> List<E> append(List<E> list, E e) {
    return ImmutableList.<E>builder().addAll(list).add(e).build();
  }

  private Core.From fromStepToCore(List<Binding> bindings, ListType type,
      List<Ast.FromStep> steps, List<Core.FromStep> coreSteps) {
    final Resolver r = withEnv(bindings);
    if (steps.isEmpty()) {
      return core.from(type, coreSteps);
    }
    final Ast.FromStep step = steps.get(0);
    switch (step.op) {
    case SCAN:
    case INNER_JOIN:
      final Ast.Scan scan = (Ast.Scan) step;
      final Core.Exp coreExp = r.toCore(scan.exp);
      final ListType listType = (ListType) coreExp.type;
      final Core.Pat corePat = r.toCore(scan.pat, listType.elementType);
      final Op op = step.op == Op.SCAN
          ? Op.INNER_JOIN
          : step.op;
      final List<Binding> bindings2 = new ArrayList<>(bindings);
      Compiles.acceptBinding(typeMap.typeSystem, corePat, bindings2);
      Core.Exp coreCondition = scan.condition == null
          ? core.boolLiteral(true)
          : r.withEnv(bindings2).toCore(scan.condition);
      final Core.Scan coreScan =
          core.scan(op, bindings2, corePat, coreExp, coreCondition);
      return fromStepToCore(coreScan.bindings, type,
          Util.skip(steps), append(coreSteps, coreScan));

    case WHERE:
      final Ast.Where where = (Ast.Where) step;
      final Core.Where coreWhere =
          core.where(bindings, r.toCore(where.exp));
      return fromStepToCore(coreWhere.bindings, type,
          Util.skip(steps), append(coreSteps, coreWhere));

    case YIELD:
      final Ast.Yield yield = (Ast.Yield) step;
      final Core.Yield coreYield =
          core.yield_(typeMap.typeSystem, r.toCore(yield.exp));
      return fromStepToCore(coreYield.bindings, type,
          Util.skip(steps), append(coreSteps, coreYield));

    case ORDER:
      final Ast.Order order = (Ast.Order) step;
      final Core.Order coreOrder =
          core.order(bindings, transform(order.orderItems, r::toCore));
      return fromStepToCore(coreOrder.bindings, type,
          Util.skip(steps), append(coreSteps, coreOrder));

    case GROUP:
    case COMPUTE:
      final Ast.Group group = (Ast.Group) step;
      final ImmutableSortedMap.Builder<Core.IdPat, Core.Exp> groupExps =
          ImmutableSortedMap.naturalOrder();
      final ImmutableSortedMap.Builder<Core.IdPat, Core.Aggregate> aggregates =
          ImmutableSortedMap.naturalOrder();
      Pair.forEach(group.groupExps, (id, exp) ->
          groupExps.put(toCorePat(id), r.toCore(exp)));
      group.aggregates.forEach(aggregate ->
          aggregates.put(toCorePat(aggregate.id), r.toCore(aggregate)));
      final Core.Group coreGroup =
          core.group(groupExps.build(), aggregates.build());
      return fromStepToCore(coreGroup.bindings, type,
          Util.skip(steps), append(coreSteps, coreGroup));

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

  /** Resolved declaration. It can be converted to an expression given a
   * result expression; depending on sub-type, that expression will either be
   * a {@code let} (for a {@link net.hydromatic.morel.ast.Ast.ValDecl} or a
   * {@code local} (for a {@link net.hydromatic.morel.ast.Ast.DatatypeDecl}. */
  public abstract static class ResolvedDecl {
    /** Converts the declaration to a {@code let} or a {@code local}. */
    abstract Core.Exp toExp(Core.Exp resultExp);
  }

  /** Resolved value declaration. */
  class ResolvedValDecl extends ResolvedDecl {
    final boolean rec;
    final Core.Pat pat;
    final Core.Exp exp;

    ResolvedValDecl(boolean rec, Core.Pat pat, Core.Exp exp) {
      this.rec = rec;
      this.pat = pat;
      this.exp = exp;
    }

    @Override Core.Let toExp(Core.Exp resultExp) {
      if (pat instanceof Core.IdPat) {
        return core.let(core.valDecl(rec, (Core.IdPat) pat, exp), resultExp);
      } else {
        // This is a complex pattern. Allocate an intermediate variable.
        final String name = nameGenerator.get();
        final Core.IdPat idPat = core.idPat(pat.type, name, nameGenerator);
        final Core.Id id = core.id(idPat);
        return core.let(core.valDecl(rec, idPat, exp),
            core.caseOf(resultExp.type, id,
                ImmutableList.of(core.match(pat, resultExp))));
      }
    }
  }

  /** Resolved datatype declaration. */
  static class ResolvedDatatypeDecl extends ResolvedDecl {
    private final ImmutableList<DataType> dataTypes;

    ResolvedDatatypeDecl(ImmutableList<DataType> dataTypes) {
      this.dataTypes = dataTypes;
    }

    @Override Core.Exp toExp(Core.Exp resultExp) {
      return toExp(dataTypes, resultExp);
    }

    private Core.Exp toExp(List<DataType> dataTypes, Core.Exp resultExp) {
      if (dataTypes.isEmpty()) {
        return resultExp;
      } else {
        return core.local(dataTypes.get(0),
            toExp(Util.skip(dataTypes), resultExp));
      }
    }

    /** Creates a datatype declaration that may have multiple datatypes.
     *
     * <p>Only the REPL needs this. Because datatypes are not recursive,
     * a composite declaration
     *
     * <pre>{@code
     * datatype d1 ... and d2 ...}</pre>
     *
     * <p>can always be converted to a chained local,
     *
     * <pre>{@code
     * local datatype d1 ... in local datatype d2 ... end end}</pre>
     */
    public Core.DatatypeDecl toDecl() {
      return core.datatypeDecl(dataTypes);
    }
  }

}

// End Resolver.java
