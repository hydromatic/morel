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
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.type.ApplyType;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.DummyType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.NamedType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;
import net.hydromatic.morel.util.ConsList;
import net.hydromatic.morel.util.MapList;
import net.hydromatic.morel.util.MartelliUnifier;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.Tracers;
import net.hydromatic.morel.util.Unifier;

import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.apache.calcite.util.Holder;
import org.apache.calcite.util.Util;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static net.hydromatic.morel.ast.AstBuilder.ast;
import static net.hydromatic.morel.util.Static.toImmutableList;

/** Resolves the type of an expression. */
@SuppressWarnings("StaticPseudoFunctionalStyleMethod")
public class TypeResolver {
  private final TypeSystem typeSystem;
  private final Unifier unifier = new MartelliUnifier();
  private final List<TermVariable> terms = new ArrayList<>();
  private final Map<AstNode, Unifier.Term> map = new HashMap<>();
  private final Map<Unifier.Variable, Unifier.Action> actionMap =
      new HashMap<>();
  private final Map<String, TypeVar> tyVarMap = new HashMap<>();
  private final List<Pair<Unifier.Variable, PrimitiveType>> preferredTypes =
      new ArrayList<>();

  static final String TUPLE_TY_CON = "tuple";
  static final String LIST_TY_CON = "list";
  static final String RECORD_TY_CON = "record";
  static final String FN_TY_CON = "fn";
  private static final String APPLY_TY_CON = "apply";
  private static final String[] INT_STRINGS =
      {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"};

  private TypeResolver(TypeSystem typeSystem) {
    this.typeSystem = Objects.requireNonNull(typeSystem);
  }

  /** Deduces the type of a declaration. */
  public static Resolved deduceType(Environment env, Ast.Decl decl,
      TypeSystem typeSystem) {
    return new TypeResolver(typeSystem).deduceType_(env, decl);
  }

  /** Converts a type AST to a type. */
  public static Type toType(Ast.Type type, TypeSystem typeSystem) {
    return new TypeResolver(typeSystem).toType(type);
  }

  private Resolved deduceType_(Environment env, Ast.Decl decl) {
    final TypeEnvHolder typeEnvs = new TypeEnvHolder(EmptyTypeEnv.INSTANCE);
    BuiltIn.forEach(typeSystem, (builtIn, type) -> {
      if (builtIn.structure == null) {
        typeEnvs.accept(builtIn.mlName, type);
      }
      if (builtIn.alias != null) {
        typeEnvs.accept(builtIn.alias, type);
      }
    });
    BuiltIn.forEachStructure(typeSystem, (structure, type) ->
        typeEnvs.accept(structure.name, type));
    env.forEachType(typeEnvs);
    final TypeEnv typeEnv = typeEnvs.typeEnv;
    final Map<Ast.IdPat, Unifier.Term> termMap = new LinkedHashMap<>();
    final Ast.Decl node2 = deduceDeclType(typeEnv, decl, termMap);
    final boolean debug = false;
    @SuppressWarnings("ConstantConditions")
    final Unifier.Tracer tracer = debug
        ? Tracers.printTracer(System.out)
        : Tracers.nullTracer();
    tryAgain:
    for (;;) {
      final List<Unifier.TermTerm> termPairs = new ArrayList<>();
      terms.forEach(tv ->
          termPairs.add(new Unifier.TermTerm(tv.term, tv.variable)));
      final Unifier.Result result =
          unifier.unify(termPairs, actionMap, tracer);
      if (!(result instanceof Unifier.Substitution)) {
        final String extra = ";\n"
            + " term pairs:\n"
            + terms.stream().map(Object::toString)
            .collect(Collectors.joining("\n"));
        throw new RuntimeException("Cannot deduce type: " + result);
      }
      final TypeMap typeMap =
          new TypeMap(typeSystem, map, (Unifier.Substitution) result);
      while (!preferredTypes.isEmpty()) {
        Pair<Unifier.Variable, PrimitiveType> x = preferredTypes.get(0);
        preferredTypes.remove(0);
        final Type type =
            typeMap.termToType(typeMap.substitution.resultMap.get(x.left));
        if (type instanceof TypeVar) {
          equiv(toTerm(x.right), x.left);
          continue tryAgain;
        }
      }
      return Resolved.of(env, decl, node2, typeMap);
    }
  }

  private <E extends AstNode> E reg(E node,
      Unifier.Variable variable, Unifier.Term term) {
    Objects.requireNonNull(node);
    Objects.requireNonNull(term);
    map.put(node, term);
    if (variable != null) {
      equiv(term, variable);
    }
    return node;
  }

  private Ast.Exp deduceType(TypeEnv env, Ast.Exp node, Unifier.Variable v) {
    final List<Ast.Exp> args2;
    final Unifier.Variable v2;
    Unifier.Variable v3 = null;
    final Map<Ast.IdPat, Unifier.Term> termMap;
    switch (node.op) {
    case BOOL_LITERAL:
      return reg(node, v, toTerm(PrimitiveType.BOOL));

    case CHAR_LITERAL:
      return reg(node, v, toTerm(PrimitiveType.CHAR));

    case INT_LITERAL:
      return reg(node, v, toTerm(PrimitiveType.INT));

    case REAL_LITERAL:
      return reg(node, v, toTerm(PrimitiveType.REAL));

    case STRING_LITERAL:
      return reg(node, v, toTerm(PrimitiveType.STRING));

    case UNIT_LITERAL:
      return reg(node, v, toTerm(PrimitiveType.UNIT));

    case ANDALSO:
    case ORELSE:
      return infix(env, (Ast.InfixCall) node, v, PrimitiveType.BOOL);

    case TUPLE:
      final Ast.Tuple tuple = (Ast.Tuple) node;
      final List<Unifier.Term> types = new ArrayList<>();
      args2 = new ArrayList<>();
      for (Ast.Exp arg : tuple.args) {
        final Unifier.Variable vArg = unifier.variable();
        args2.add(deduceType(env, arg, vArg));
        types.add(vArg);
      }
      return reg(tuple.copy(args2), v, tuple(types));

    case LIST:
      final Ast.ListExp list = (Ast.ListExp) node;
      final Unifier.Variable vArg2 = unifier.variable();
      args2 = new ArrayList<>();
      for (Ast.Exp arg : list.args) {
        args2.add(deduceType(env, arg, vArg2));
      }
      return reg(list.copy(args2), v, unifier.apply(LIST_TY_CON, vArg2));

    case RECORD:
      final Ast.Record record = (Ast.Record) node;
      final NavigableMap<String, Unifier.Term> labelTypes = new TreeMap<>();
      final NavigableMap<String, Ast.Exp> map2 = new TreeMap<>();
      record.args.forEach((name, exp) -> {
        final Unifier.Variable vArg = unifier.variable();
        final Ast.Exp e2 = deduceType(env, exp, vArg);
        labelTypes.put(name, vArg);
        map2.put(name, e2);
      });
      return reg(record.copy(map2), v, record(labelTypes));

    case LET:
      final Ast.Let let = (Ast.Let) node;
      termMap = new LinkedHashMap<>();
      TypeEnv env2 = env;
      final List<Ast.Decl> decls = new ArrayList<>();
      for (Ast.Decl decl : let.decls) {
        decls.add(deduceDeclType(env2, decl, termMap));
        env2 = bindAll(env2, termMap);
        termMap.clear();
      }
      final Ast.Exp e2 = deduceType(env2, let.exp, v);
      final Ast.Let let2 = let.copy(decls, e2);
      return reg(let2, null, v);

    case RECORD_SELECTOR:
      final Ast.RecordSelector recordSelector = (Ast.RecordSelector) node;
      throw new RuntimeException("Error: unresolved flex record\n"
          + "   (can't tell what fields there are besides #"
          + recordSelector.name + ")");

    case IF:
      // TODO: check that condition has type boolean
      // TODO: check that ifTrue has same type as ifFalse
      final Ast.If if_ = (Ast.If) node;
      v2 = unifier.variable();
      final Ast.Exp condition2 = deduceType(env, if_.condition, v2);
      equiv(v2, toTerm(PrimitiveType.BOOL));
      final Ast.Exp ifTrue2 = deduceType(env, if_.ifTrue, v);
      final Ast.Exp ifFalse2 = deduceType(env, if_.ifFalse, v);
      final Ast.If if2 = if_.copy(condition2, ifTrue2, ifFalse2);
      return reg(if2, null, v);

    case CASE:
      final Ast.Case case_ = (Ast.Case) node;
      v2 = unifier.variable();
      final Ast.Exp e2b = deduceType(env, case_.exp, v2);
      final NavigableSet<String> labelNames = new TreeSet<>();
      final Unifier.Term argType = map.get(e2b);
      if (argType instanceof Unifier.Sequence) {
        final List<String> fieldList = fieldList((Unifier.Sequence) argType);
        if (fieldList != null) {
          labelNames.addAll(fieldList);
        }
      }
      final List<Ast.Match> matchList2 =
          deduceMatchListType(env, case_.matchList, labelNames, v2, v);
      return reg(case_.copy(e2b, matchList2), null, v);

    case FROM:
      // "(from exp: v50 as id: v60 [, exp: v51 as id: v61]...
      //  [where filterExp: v5] [yield yieldExp: v4]): v"
      final Ast.From from = (Ast.From) node;
      env2 = env;
      final Map<Ast.Id, Unifier.Variable> fieldVars = new LinkedHashMap<>();
      final List<Ast.FromStep> fromSteps = new ArrayList<>();
      for (Ord<Ast.FromStep> step : Ord.zip(from.steps)) {
        Pair<TypeEnv, Unifier.Variable> p =
            deduceStepType(env, step.e, v3, env2, fieldVars, fromSteps);
        if (step.e.op == Op.COMPUTE
            && step.i != from.steps.size() - 1) {
          throw new AssertionError("'compute' step must be last in 'from'");
        }
        env2 = p.left;
        v3 = p.right;
      }
      final Ast.Exp yieldExp2;
      if (from.implicitYieldExp != null) {
        v3 = unifier.variable();
        yieldExp2 = deduceType(env2, from.implicitYieldExp, v3);
      } else {
        Objects.requireNonNull(v3);
        yieldExp2 = null;
      }
      final Ast.From from2 =
          from.copy(fromSteps,
              from.implicitYieldExp != null ? yieldExp2 : null);
      return reg(from2, v,
          from.isCompute() ? v3 : unifier.apply(LIST_TY_CON, v3));

    case ID:
      final Ast.Id id = (Ast.Id) node;
      final Unifier.Term term = env.get(typeSystem, id.name);
      return reg(id, v, term);

    case FN:
      final Ast.Fn fn = (Ast.Fn) node;
      final Unifier.Variable resultVariable = unifier.variable();
      final List<Ast.Match> matchList = new ArrayList<>();
      for (Ast.Match match : fn.matchList) {
        matchList.add(
            deduceMatchType(env, match, new HashMap<>(), v, resultVariable));
      }
      final Ast.Fn fn2b = fn.copy(matchList);
      return reg(fn2b, null, v);

    case APPLY:
      final Ast.Apply apply = (Ast.Apply) node;
      final Unifier.Variable vFn = unifier.variable();
      final Unifier.Variable vArg = unifier.variable();
      equiv(unifier.apply(FN_TY_CON, vArg, v), vFn);
      final Ast.Exp arg2;
      if (apply.arg instanceof Ast.RecordSelector) {
        // node is "f #field" and has type "v"
        // "f" has type "vArg -> v" and also "vFn"
        // "#field" has type "vArg" and also "vRec -> vField"
        // When we resolve "vRec" we can then deduce "vField".
        final Unifier.Variable vRec = unifier.variable();
        final Unifier.Variable vField = unifier.variable();
        deduceRecordSelectorType(env, vField, vRec,
            (Ast.RecordSelector) apply.arg);
        arg2 = reg(apply.arg, vArg, unifier.apply(FN_TY_CON, vRec, vField));
      } else {
        arg2 = deduceType(env, apply.arg, vArg);
      }
      final Ast.Exp fn2;
      if (apply.fn instanceof Ast.RecordSelector) {
        // node is "#field arg" and has type "v"
        // "#field" has type "vArg -> v"
        // "arg" has type "vArg"
        // When we resolve "vArg" we can then deduce "v".
        deduceRecordSelectorType(env, v, vArg,
            (Ast.RecordSelector) apply.fn);
        fn2 = apply.fn;
      } else {
        fn2 = deduceType(env, apply.fn, vFn);
      }
      if (fn2 instanceof Ast.Id) {
        final BuiltIn builtIn = BuiltIn.BY_ML_NAME.get(((Ast.Id) fn2).name);
        if (builtIn != null) {
          builtIn.prefer(t -> preferredTypes.add(Pair.of(v, t)));
        }
      }
      return reg(apply.copy(fn2, arg2), null, v);

    case AT:
    case CARET:
    case COMPOSE:
    case PLUS:
    case MINUS:
    case TIMES:
    case DIVIDE:
    case DIV:
    case MOD:
    case EQ:
    case NE:
    case GE:
    case GT:
    case LE:
    case LT:
    case ELEM:
    case NOT_ELEM:
    case CONS:
    case UNION:
    case INTERSECT:
    case EXCEPT:
      return infix(env, (Ast.InfixCall) node, v);

    case NEGATE:
      return prefix(env, (Ast.PrefixCall) node, v);

    default:
      throw new AssertionError("cannot deduce type for " + node.op);
    }
  }

  private Pair<TypeEnv, Unifier.Variable> deduceStepType(TypeEnv env,
      Ast.FromStep step, Unifier.Variable v, final TypeEnv env2,
      Map<Ast.Id, Unifier.Variable> fieldVars, List<Ast.FromStep> fromSteps) {
    switch (step.op) {
    case SCAN:
    case INNER_JOIN:
      final Ast.Scan scan = (Ast.Scan) step;
      final Ast.Exp scanExp;
      final boolean eq;
      if (scan.exp.op == Op.FROM_EQ) {
        eq = true;
        scanExp = ((Ast.PrefixCall) scan.exp).a;
      } else {
        eq = false;
        scanExp = scan.exp;
      }
      final Unifier.Variable v15 = unifier.variable();
      final Unifier.Variable v16 = unifier.variable();
      final Ast.Exp scanExp2 = deduceType(env2, scanExp, v15);
      final Ast.Exp scanExp3 = eq ? ast.fromEq(scanExp2) : scanExp2;
      final Map<Ast.IdPat, Unifier.Term> termMap1 = new HashMap<>();
      final Ast.Pat pat2 =
          deducePatType(env2, scan.pat, termMap1, null, v16);
      reg(scanExp, v15, eq ? v16 : unifier.apply(LIST_TY_CON, v16));
      TypeEnv env4 = env2;
      for (Map.Entry<Ast.IdPat, Unifier.Term> e : termMap1.entrySet()) {
        env4 = env4.bind(e.getKey().name, e.getValue());
        fieldVars.put(ast.id(Pos.ZERO, e.getKey().name),
            (Unifier.Variable) e.getValue());
      }
      final Ast.Exp scanCondition2;
      if (scan.condition != null) {
        final Unifier.Variable v5 = unifier.variable();
        scanCondition2 = deduceType(env4, scan.condition, v5);
        equiv(v5, toTerm(PrimitiveType.BOOL));
      } else {
        scanCondition2 = null;
      }
      fromSteps.add(scan.copy(pat2, scanExp3, scanCondition2));
      return Pair.of(env4, v);

    case WHERE:
      final Ast.Where where = (Ast.Where) step;
      final Unifier.Variable v5 = unifier.variable();
      final Ast.Exp filter2 = deduceType(env2, where.exp, v5);
      equiv(v5, toTerm(PrimitiveType.BOOL));
      fromSteps.add(where.copy(filter2));
      return Pair.of(env2, v);

    case YIELD:
      final Ast.Yield yield = (Ast.Yield) step;
      final Unifier.Variable v6 = unifier.variable();
      v = v6;
      final Ast.Exp yieldExp2 = deduceType(env2, yield.exp, v6);
      fromSteps.add(yield.copy(yieldExp2));
      if (yieldExp2.op == Op.RECORD) {
        final Unifier.Sequence sequence =
            (Unifier.Sequence) map.get(yieldExp2);
        final Ast.Record record2 = (Ast.Record) yieldExp2;
        final TypeEnv[] envs = {env};
        Pair.forEach(record2.args.keySet(), sequence.terms, (name, term) ->
            envs[0] = envs[0].bind(name, term));
        return Pair.of(envs[0], v);
      } else {
        return Pair.of(env2, v);
      }

    case ORDER:
      final Ast.Order order = (Ast.Order) step;
      final List<Ast.OrderItem> orderItems = new ArrayList<>();
      for (Ast.OrderItem orderItem : order.orderItems) {
        orderItems.add(
            orderItem.copy(
                deduceType(env2, orderItem.exp, unifier.variable()),
                orderItem.direction));
      }
      fromSteps.add(order.copy(orderItems));
      return Pair.of(env2, v);

    case GROUP:
    case COMPUTE:
      final Ast.Group group = (Ast.Group) step;
      validateGroup(group);
      TypeEnv env3 = env;
      final Map<Ast.Id, Unifier.Variable> inFieldVars =
          ImmutableMap.copyOf(fieldVars);
      fieldVars.clear();
      final List<Pair<Ast.Id, Ast.Exp>> groupExps = new ArrayList<>();
      for (Pair<Ast.Id, Ast.Exp> groupExp : group.groupExps) {
        final Ast.Id id = groupExp.getKey();
        final Ast.Exp exp = groupExp.getValue();
        final Unifier.Variable v7 = unifier.variable();
        final Ast.Exp exp2 = deduceType(env2, exp, v7);
        reg(id, null, v7);
        env3 = env3.bind(id.name, v7);
        fieldVars.put(id, v7);
        groupExps.add(Pair.of(id, exp2));
      }
      final List<Ast.Aggregate> aggregates = new ArrayList<>();
      for (Ast.Aggregate aggregate : group.aggregates) {
        final Ast.Id id = aggregate.id;
        final Unifier.Variable v8 = unifier.variable();
        reg(id, null, v8);
        final Unifier.Variable v9 = unifier.variable();
        final Ast.Exp aggregateFn2 =
            deduceType(env2, aggregate.aggregate, v9);
        final Ast.Exp arg2;
        final Unifier.Term term;
        if (aggregate.argument == null) {
          arg2 = null;
          term = fieldRecord(inFieldVars);
        } else {
          final Unifier.Variable v10 = unifier.variable();
          arg2 = deduceType(env2, aggregate.argument, v10);
          term = v10;
        }
        reg(aggregate.aggregate, null, v9);
        equiv(
            unifier.apply(FN_TY_CON, unifier.apply(LIST_TY_CON, term), v8),
            v9);
        env3 = env3.bind(id.name, v8);
        fieldVars.put(id, v8);
        final Ast.Aggregate aggregate2 =
            aggregate.copy(aggregateFn2, arg2, aggregate.id);
        aggregates.add(aggregate2);
        reg(aggregate2, null, v8);
      }
      fromSteps.add(step.op == Op.GROUP
          ? group.copy(groupExps, aggregates)
          : ((Ast.Compute) step).copy(aggregates));
      return Pair.of(env3, v);

    default:
      throw new AssertionError("unknown step type " + step.op);
    }
  }

  /** Validates a {@code Group}. Throws if there are duplicate names among
   * the keys and aggregates. */
  private void validateGroup(Ast.Group group) {
    final List<String> names = new ArrayList<>();
    group.groupExps.forEach(pair -> names.add(pair.left.name));
    group.aggregates.forEach(aggregate -> names.add(aggregate.id.name));
    int duplicate = Util.firstDuplicate(names);
    if (duplicate >= 0) {
      throw new RuntimeException("Duplicate field name '"
          + names.get(duplicate) + "' in group");
    }
  }

  private Unifier.Term fieldRecord(Map<Ast.Id, Unifier.Variable> fieldVars) {
    switch (fieldVars.size()) {
    case 0:
      return toTerm(PrimitiveType.UNIT);
    case 1:
      return Iterables.getOnlyElement(fieldVars.values());
    default:
      final TreeMap<String, Unifier.Variable> map = new TreeMap<>();
      fieldVars.forEach((k, v) -> map.put(k.name, v));
      return record(map);
    }
  }

  private Unifier.Term record(
      NavigableMap<String, ? extends Unifier.Term> labelTypes) {
    if (labelTypes.isEmpty()) {
      return toTerm(PrimitiveType.UNIT);
    } else if (TypeSystem.areContiguousIntegers(labelTypes.navigableKeySet())
        && labelTypes.size() != 1) {
      return unifier.apply(TUPLE_TY_CON, labelTypes.values());
    } else {
      final StringBuilder b = new StringBuilder(RECORD_TY_CON);
      for (String label : labelTypes.navigableKeySet()) {
        b.append(':').append(label);
      }
      return unifier.apply(b.toString(), labelTypes.values());
    }
  }

  private Unifier.Term tuple(List<Unifier.Term> types) {
    if (types.isEmpty()) {
      return toTerm(PrimitiveType.UNIT);
    } else {
      return unifier.apply(TUPLE_TY_CON, types);
    }
  }

  /** Converts an integer to its string representation, using a cached value
   * if possible. */
  private static String str(int i) {
    return i >= 0 && i < INT_STRINGS.length ? INT_STRINGS[i]
        : Integer.toString(i);
  }

  private Ast.RecordSelector deduceRecordSelectorType(TypeEnv env,
      Unifier.Variable vResult, Unifier.Variable vArg,
      Ast.RecordSelector recordSelector) {
    final String fieldName = recordSelector.name;
    actionMap.put(vArg, (v, t, substitution, termPairs) -> {
      // We now know that the type arg, say "{a: int, b: real}".
      // So, now we can declare that the type of vResult, say "#b", is
      // "real".
      if (t instanceof Unifier.Sequence) {
        final Unifier.Sequence sequence = (Unifier.Sequence) t;
        final List<String> fieldList = fieldList(sequence);
        if (fieldList != null) {
          int i = fieldList.indexOf(fieldName);
          if (i >= 0) {
            final Unifier.Term result2 = substitution.resolve(vResult);
            final Unifier.Term term = sequence.terms.get(i);
            final Unifier.Term term2 = substitution.resolve(term);
            termPairs.add(new Unifier.TermTerm(result2, term2));
          }
        }
      }
    });
    return recordSelector;
  }

  static List<String> fieldList(final Unifier.Sequence sequence) {
    if (sequence.operator.equals(RECORD_TY_CON)) {
      return ImmutableList.of();
    } else if (sequence.operator.startsWith(RECORD_TY_CON + ":")) {
      final String[] fields = sequence.operator.split(":");
      return Util.skip(Arrays.asList(fields));
    } else if (sequence.operator.equals(TUPLE_TY_CON)) {
      return new AbstractList<String>() {
        public int size() {
          return sequence.terms.size();
        }

        public String get(int index) {
          return str(index + 1);
        }
      };
    } else {
      return null;
    }
  }

  private Ast.Match deduceMatchType(TypeEnv env, Ast.Match match,
      Map<Ast.IdPat, Unifier.Term> termMap, Unifier.Variable argVariable,
      Unifier.Variable resultVariable) {
    final Unifier.Variable vPat = unifier.variable();
    Ast.Pat pat2 = deducePatType(env, match.pat, termMap, null, vPat);
    TypeEnv env2 = bindAll(env, termMap);
    Ast.Exp e2 = deduceType(env2, match.exp, resultVariable);
    Ast.Match match2 = match.copy(pat2, e2);
    return reg(match2, argVariable,
        unifier.apply(FN_TY_CON, vPat, resultVariable));
  }

  private List<Ast.Match> deduceMatchListType(TypeEnv env, List<Ast.Match> matchList,
      NavigableSet<String> labelNames, Unifier.Variable argVariable,
      Unifier.Variable resultVariable) {
    for (Ast.Match match : matchList) {
      if (match.pat instanceof Ast.RecordPat) {
        labelNames.addAll(((Ast.RecordPat) match.pat).args.keySet());
      }
    }
    final List<Ast.Match> matchList2 = new ArrayList<>();
    for (Ast.Match match : matchList) {
      final Map<Ast.IdPat, Unifier.Term> termMap = new HashMap<>();
      final Ast.Pat pat2 =
          deducePatType(env, match.pat, termMap, labelNames, argVariable);
      final TypeEnv env2 = bindAll(env, termMap);
      final Ast.Exp e2 = deduceType(env2, match.exp, resultVariable);
      matchList2.add(match.copy(pat2, e2));
    }
    return matchList2;
  }

  private AstNode deduceValBindType(TypeEnv env, Ast.ValBind valBind,
      Map<Ast.IdPat, Unifier.Term> termMap, Unifier.Variable v,
      Unifier.Variable vPat) {
    deducePatType(env, valBind.pat, termMap, null, vPat);
    final Ast.Exp e2 = deduceType(env, valBind.exp, vPat);
    final Ast.ValBind valBind2 = valBind.copy(valBind.pat, e2);
    return reg(valBind2, v, unifier.apply(FN_TY_CON, vPat, vPat));
  }

  private static TypeEnv bindAll(TypeEnv env,
      Map<Ast.IdPat, Unifier.Term> termMap) {
    for (Map.Entry<Ast.IdPat, Unifier.Term> entry : termMap.entrySet()) {
      env = env.bind(entry.getKey().name, entry.getValue());
    }
    return env;
  }

  private Ast.Decl deduceDeclType(TypeEnv env, Ast.Decl node,
      Map<Ast.IdPat, Unifier.Term> termMap) {
    switch (node.op) {
    case VAL_DECL:
      return deduceValDeclType(env, (Ast.ValDecl) node, termMap);

    case FUN_DECL:
      final Ast.ValDecl valDecl = toValDecl(env, (Ast.FunDecl) node);
      return deduceValDeclType(env, valDecl, termMap);

    case DATATYPE_DECL:
      final Ast.DatatypeDecl datatypeDecl = (Ast.DatatypeDecl) node;
      for (Ast.DatatypeBind datatypeBind : datatypeDecl.binds) {
        deduceDatatypeBindType(env, datatypeBind, termMap);
      }
      map.put(node, toTerm(PrimitiveType.UNIT));
      return node;

    default:
      throw new AssertionError("cannot deduce type for " + node.op + " ["
          + node + "]");
    }
  }

  private Ast.Decl deduceValDeclType(TypeEnv env, Ast.ValDecl valDecl,
      Map<Ast.IdPat, Unifier.Term> termMap) {
    final Holder<TypeEnv> envHolder = Holder.of(env);
    final Map<Ast.ValBind, Supplier<Unifier.Variable>> map0 =
        new LinkedHashMap<>();
    valDecl.valBinds.forEach(b ->
        map0.put(b, Suppliers.memoize(unifier::variable)));
    map0.forEach((valBind, vPatSupplier) -> {
      // If recursive, bind each value (presumably a function) to its type
      // in the environment before we try to deduce the type of the expression.
      if (valDecl.rec && valBind.pat instanceof Ast.IdPat) {
        envHolder.set(
            envHolder.get().bind(
                ((Ast.IdPat) valBind.pat).name, vPatSupplier.get()));
      }
    });
    final List<Ast.ValBind> valBinds = new ArrayList<>();
    final TypeEnv env2 = envHolder.get();
    map0.forEach((valBind, vPatSupplier) ->
        valBinds.add((Ast.ValBind)
            deduceValBindType(env2, valBind, termMap, unifier.variable(),
                vPatSupplier.get())));
    Ast.Decl node2 = valDecl.copy(valBinds);
    map.put(node2, toTerm(PrimitiveType.UNIT));
    return node2;
  }

  private void deduceDatatypeBindType(TypeEnv env,
      Ast.DatatypeBind datatypeBind, Map<Ast.IdPat, Unifier.Term> termMap) {
    final Map<String, Type> tyCons = new TreeMap<>();
    final TypeSystem.TemporaryType tempType =
        typeSystem.temporaryType(datatypeBind.name.name);
    for (Ast.TyCon tyCon : datatypeBind.tyCons) {
      tyCons.put(tyCon.id.name,
          tyCon.type == null ? DummyType.INSTANCE : toType(tyCon.type));
    }
    tempType.delete();
    final List<TypeVar> typeVars = new ArrayList<>();
    final DataType dataType =
        typeSystem.dataType(datatypeBind.name.name, typeVars, tyCons);
    for (Ast.TyCon tyCon : datatypeBind.tyCons) {
      final Type type;
      if (tyCon.type != null) {
        type = typeSystem.fnType(toType(tyCon.type), dataType);
      } else {
        type = dataType;
      }
      termMap.put((Ast.IdPat) ast.idPat(tyCon.pos, tyCon.id.name),
          toTerm(type, Subst.EMPTY));
      map.put(tyCon, toTerm(type, Subst.EMPTY));
    }
  }

  private Type toType(Ast.Type type) {
    switch (type.op) {
    case TUPLE_TYPE:
      final Ast.TupleType tupleType = (Ast.TupleType) type;
      return typeSystem.tupleType(toTypes(tupleType.types));

    case NAMED_TYPE:
      final Ast.NamedType namedType = (Ast.NamedType) type;
      final Type genericType = typeSystem.lookup(namedType.name);
      if (namedType.types.isEmpty()) {
        return genericType;
      }
      //noinspection UnstableApiUsage
      final List<Type> typeList = namedType.types.stream().map(this::toType)
          .collect(ImmutableList.toImmutableList());
      return typeSystem.apply(genericType, typeList);

    case TY_VAR:
      final Ast.TyVar tyVar = (Ast.TyVar) type;
      return tyVarMap.computeIfAbsent(tyVar.name,
          name -> typeSystem.typeVariable(tyVarMap.size()));

    default:
      throw new AssertionError("cannot convert type " + type);
    }
  }

  private List<Type> toTypes(List<Ast.Type> typeList) {
    return typeList.stream().map(this::toType)
        .collect(Collectors.toList());
  }

  /** Converts a function declaration to a value declaration.
   * In other words, {@code fun} is syntactic sugar, and this
   * is the de-sugaring machine.
   *
   * <p>For example, {@code fun inc x = x + 1}
   * becomes {@code val rec inc = fn x => x + 1}.
   *
   * <p>If there are multiple arguments, there is one {@code fn} for each
   * argument: {@code fun sum x y = x + y}
   * becomes {@code val rec sum = fn x => fn y => x + y}.
   *
   * <p>If there are multiple clauses, we generate {@code case}:
   * {@code fun gcd a 0 = a | gcd a b = gcd b (a mod b)}
   * becomes
   * {@code val rec gcd = fn x => fn y =>
   * case (x, y) of
   *     (a, 0) => a
   *   | (a, b) = gcd b (a mod b)}.
   */
  private Ast.ValDecl toValDecl(TypeEnv env, Ast.FunDecl funDecl) {
    final List<Ast.ValBind> valBindList = new ArrayList<>();
    for (Ast.FunBind funBind : funDecl.funBinds) {
      valBindList.add(toValBind(env, funBind));
    }
    return ast.valDecl(funDecl.pos, true, valBindList);
  }

  private Ast.ValBind toValBind(TypeEnv env, Ast.FunBind funBind) {
    final List<Ast.Pat> vars;
    Ast.Exp exp;
    if (funBind.matchList.size() == 1) {
      exp = funBind.matchList.get(0).exp;
      vars = funBind.matchList.get(0).patList;
    } else {
      final List<String> varNames =
          MapList.of(funBind.matchList.get(0).patList.size(),
              index -> "v" + index);
      vars = Lists.transform(varNames, v -> ast.idPat(Pos.ZERO, v));
      final List<Ast.Match> matchList = new ArrayList<>();
      for (Ast.FunMatch funMatch : funBind.matchList) {
        matchList.add(
            ast.match(funMatch.pos, patTuple(env, funMatch.patList),
                funMatch.exp));
      }
      exp = ast.caseOf(Pos.ZERO, idTuple(varNames), matchList);
    }
    final Pos pos = funBind.pos;
    for (Ast.Pat var : Lists.reverse(vars)) {
      exp = ast.fn(pos, ast.match(pos, var, exp));
    }
    return ast.valBind(pos, ast.idPat(pos, funBind.name), exp);
  }

  /** Converts a list of variable names to a variable or tuple.
   *
   * <p>For example, ["x"] becomes "{@code x}" (an {@link Ast.Id}),
   * and ["x", "y"] becomes "{@code (x, y)}" (a {@link Ast.Tuple} of
   * {@link Ast.Id Ids}). */
  private static Ast.Exp idTuple(List<String> vars) {
    final List<Ast.Id> idList =
        Lists.transform(vars, v -> ast.id(Pos.ZERO, v));
    if (idList.size() == 1) {
      return idList.get(0);
    }
    return ast.tuple(Pos.ZERO, idList);
  }

  /** Converts a list of patterns to a singleton pattern or tuple pattern. */
  @SuppressWarnings("SwitchStatementWithTooFewBranches")
  private Ast.Pat patTuple(TypeEnv env, List<Ast.Pat> patList) {
    final List<Ast.Pat> list2 = new ArrayList<>();
    for (int i = 0; i < patList.size(); i++) {
      final Ast.Pat pat = patList.get(i);
      switch (pat.op) {
      case ID_PAT:
        final Ast.IdPat idPat = (Ast.IdPat) pat;
        if (env.has(idPat.name)) {
          final Unifier.Term term = env.get(typeSystem, idPat.name);
          if (term instanceof Unifier.Sequence
              && ((Unifier.Sequence) term).operator.equals(FN_TY_CON)) {
            list2.add(
                ast.conPat(idPat.pos, ast.id(idPat.pos, idPat.name),
                    patList.get(++i)));
          } else {
            list2.add(ast.con0Pat(idPat.pos, ast.id(idPat.pos, idPat.name)));
          }
          break;
        }
        // fall through
      default:
        list2.add(pat);
      }
    }
    if (list2.size() == 1) {
      return list2.get(0);
    } else {
      return ast.tuplePat(Pos.sum(list2), list2);
    }
  }

  /** Derives a type term for a pattern, collecting the names of pattern
   * variables.
   *
   * @param env Compile-time environment
   * @param pat Pattern AST
   * @param termMap Map from names to bound terms, populated by this method
   * @param labelNames List of names of labels in this pattern and sibling
   *   patterns in a {@code |} match, or null if not a record pattern
   * @param v Type variable that this method should equate the type term that it
   *   derives for this pattern */
  private Ast.Pat deducePatType(TypeEnv env, Ast.Pat pat,
      Map<Ast.IdPat, Unifier.Term> termMap, NavigableSet<String> labelNames,
      Unifier.Variable v) {
    switch (pat.op) {
    case BOOL_LITERAL_PAT:
      return reg(pat, v, toTerm(PrimitiveType.BOOL));

    case CHAR_LITERAL_PAT:
      return reg(pat, v, toTerm(PrimitiveType.CHAR));

    case INT_LITERAL_PAT:
      return reg(pat, v, toTerm(PrimitiveType.INT));

    case REAL_LITERAL_PAT:
      return reg(pat, v, toTerm(PrimitiveType.REAL));

    case STRING_LITERAL_PAT:
      return reg(pat, v, toTerm(PrimitiveType.STRING));

    case ID_PAT:
      termMap.put((Ast.IdPat) pat, v);
      // fall through

    case WILDCARD_PAT:
      return reg(pat, null, v);

    case TUPLE_PAT:
      final List<Unifier.Term> typeTerms = new ArrayList<>();
      final Ast.TuplePat tuple = (Ast.TuplePat) pat;
      for (Ast.Pat arg : tuple.args) {
        final Unifier.Variable vArg = unifier.variable();
        deducePatType(env, arg, termMap, null, vArg);
        typeTerms.add(vArg);
      }
      return reg(pat, v, tuple(typeTerms));

    case RECORD_PAT:
      // First, determine the set of field names.
      //
      // If the pattern is in a 'case', we know the field names from the
      // argument. But it we are in a function, we require at least one of the
      // patterns to not be a wildcard and not have an ellipsis. For example, in
      //
      //  fun f {a=1,...} = 1 | f {b=2,...} = 2
      //
      // we cannot deduce whether a 'c' field is allowed.
      final Ast.RecordPat recordPat = (Ast.RecordPat) pat;
      final NavigableMap<String, Unifier.Term> labelTerms =
          new TreeMap<>(RecordType.ORDERING);
      if (labelNames == null) {
        labelNames = new TreeSet<>(recordPat.args.keySet());
      }
      final Map<String, Ast.Pat> args = new TreeMap<>(RecordType.ORDERING);
      for (String labelName : labelNames) {
        final Unifier.Variable vArg = unifier.variable();
        labelTerms.put(labelName, vArg);
        final Ast.Pat argPat = recordPat.args.get(labelName);
        if (argPat != null) {
          args.put(labelName,
              deducePatType(env, argPat, termMap, null, vArg));
        }
      }
      final Unifier.Term record = record(labelTerms);
      final Ast.RecordPat recordPat2 = recordPat.copy(recordPat.ellipsis, args);
      if (!recordPat.ellipsis) {
        return reg(recordPat2, v, record);
      }
      final Unifier.Variable v2 = unifier.variable();
      equiv(record, v2);
      actionMap.put(v, (v3, t, substitution, termPairs) -> {
        // We now know the type of the source record, say "{a: int, b: real}".
        // So, now we can fill out the ellipsis.
        assert v == v3;
        if (t instanceof Unifier.Sequence) {
          final Unifier.Sequence sequence = (Unifier.Sequence) t;
          final List<String> fieldList = fieldList(sequence);
          if (fieldList != null) {
            final NavigableMap<String, Unifier.Term> labelTerms2 =
                new TreeMap<>(RecordType.ORDERING);
            Ord.forEach(fieldList, (fieldName, i) -> {
              if (labelTerms.containsKey(fieldName)) {
                labelTerms2.put(fieldName, sequence.terms.get(i));
              }
            });
            final Unifier.Term result2 = substitution.resolve(v2);
            final Unifier.Term term2 = record(labelTerms2);
            termPairs.add(new Unifier.TermTerm(result2, term2));
          }
        }
      });
      return reg(recordPat2, null, record);

    case CON_PAT:
      final Ast.ConPat conPat = (Ast.ConPat) pat;
      // e.g. "SOME x" has type "intoption", "x" has type "int"
      final Pair<DataType, Type> pair =
          typeSystem.lookupTyCon(conPat.tyCon.name);
      if (pair == null) {
        throw new AssertionError("not found: " + conPat.tyCon.name);
      }
      final DataType dataType = pair.left;
      final Type argType = pair.right;
      final Unifier.Variable vArg = unifier.variable();
      deducePatType(env, conPat.pat, termMap, null, vArg);
      equiv(vArg, toTerm(argType, Subst.EMPTY));
      return reg(pat, v, toTerm(dataType, Subst.EMPTY));

    case CON0_PAT:
      final Ast.Con0Pat con0Pat = (Ast.Con0Pat) pat;
      final Pair<DataType, Type> pair0 =
          typeSystem.lookupTyCon(con0Pat.tyCon.name);
      if (pair0 == null) {
        throw new AssertionError();
      }
      final DataType dataType0 = pair0.left;
      return reg(pat, v, toTerm(dataType0, Subst.EMPTY));

    case LIST_PAT:
      final Ast.ListPat list = (Ast.ListPat) pat;
      final Unifier.Variable vArg2 = unifier.variable();
      for (Ast.Pat arg : list.args) {
        deducePatType(env, arg, termMap, null, vArg2);
      }
      return reg(list, v, unifier.apply(LIST_TY_CON, vArg2));

    case CONS_PAT:
      final Unifier.Variable elementType = unifier.variable();
      final Ast.InfixPat call = (Ast.InfixPat) pat;
      deducePatType(env, call.p0, termMap, null, elementType);
      deducePatType(env, call.p1, termMap, null, v);
      return reg(call, v, unifier.apply(LIST_TY_CON, elementType));

    default:
      throw new AssertionError("cannot deduce type for pattern " + pat.op);
    }
  }

  /** Registers an infix operator whose type is a given type. */
  private Ast.Exp infix(TypeEnv env, Ast.InfixCall call, Unifier.Variable v,
      Type type) {
    final Unifier.Term term = toTerm(type, Subst.EMPTY);
    final Ast.Exp a0 = deduceType(env, call.a0, v);
    final Ast.Exp a1 = deduceType(env, call.a1, v);
    return reg(call.copy(a0, a1), v, term);
  }

  /** Registers an infix operator. */
  private Ast.Exp infix(TypeEnv env, Ast.InfixCall call,
      Unifier.Variable v) {
    return deduceType(env,
        ast.apply(ast.id(Pos.ZERO, call.op.opName),
            ast.tuple(Pos.ZERO, ImmutableList.of(call.a0, call.a1))), v);
  }

  /** Registers a prefix operator. */
  private Ast.Exp prefix(TypeEnv env, Ast.PrefixCall call, Unifier.Variable v) {
    return deduceType(env,
        ast.apply(ast.id(Pos.ZERO, call.op.opName), call.a), v);
  }

  private void equiv(Unifier.Term term, Unifier.Variable atom) {
    terms.add(new TermVariable(term, atom));
  }

  private void equiv(Unifier.Term term, Unifier.Term term2) {
    if (term2 instanceof Unifier.Variable) {
      equiv(term, (Unifier.Variable) term2);
    } else if (term instanceof Unifier.Variable) {
      equiv(term2, (Unifier.Variable) term);
    } else {
      final Unifier.Variable variable = unifier.variable();
      equiv(term, variable);
      equiv(term2, variable);
    }
  }

  private List<Unifier.Term> toTerms(Iterable<? extends Type> types,
      Subst subst) {
    final ImmutableList.Builder<Unifier.Term> terms = ImmutableList.builder();
    types.forEach(type -> terms.add(toTerm(type, subst)));
    return terms.build();
  }

  private Unifier.Term toTerm(PrimitiveType type) {
    return unifier.atom(type.moniker);
  }

  private Unifier.Term toTerm(Type type, Subst subst) {
    switch (type.op()) {
    case ID:
      return toTerm((PrimitiveType) type);
    case TY_VAR:
      final Unifier.Variable variable = subst.get((TypeVar) type);
      return variable != null ? variable : unifier.variable();
    case DATA_TYPE:
      final DataType dataType = (DataType) type;
      return unifier.apply(dataType.name(), toTerms(dataType.typeVars, subst));
    case TEMPORARY_DATA_TYPE:
      return unifier.atom(((NamedType) type).name());
    case FUNCTION_TYPE:
      final FnType fnType = (FnType) type;
      return unifier.apply(FN_TY_CON, toTerm(fnType.paramType, subst),
          toTerm(fnType.resultType, subst));
    case APPLY_TYPE:
      final ApplyType applyType = (ApplyType) type;
      final Unifier.Term term = toTerm(applyType.type, subst);
      final List<Unifier.Term> terms = toTerms(applyType.types, subst);
      return unifier.apply(APPLY_TY_CON, ConsList.of(term, terms));
    case TUPLE_TYPE:
      final TupleType tupleType = (TupleType) type;
      return unifier.apply(TUPLE_TY_CON, tupleType.argTypes.stream()
          .map(type1 -> toTerm(type1, subst)).collect(toImmutableList()));
    case RECORD_TYPE:
      final RecordType recordType = (RecordType) type;
      @SuppressWarnings({"rawtypes", "unchecked"})
      final NavigableSet<String> labelNames =
          (NavigableSet) recordType.argNameTypes.keySet();
      final String result;
      if (labelNames.isEmpty()) {
        result = PrimitiveType.UNIT.name();
      } else if (TypeSystem.areContiguousIntegers(labelNames)) {
        result = TUPLE_TY_CON;
      } else {
        final StringBuilder b = new StringBuilder(RECORD_TY_CON);
        for (String label : labelNames) {
          b.append(':').append(label);
        }
        result = b.toString();
      }
      return unifier.apply(result,
          recordType.argNameTypes.values().stream()
              .map(type1 -> toTerm(type1, subst)).collect(toImmutableList()));
    case LIST:
      final ListType listType = (ListType) type;
      return unifier.apply(LIST_TY_CON,
          toTerm(listType.elementType, subst));
    case FORALL_TYPE:
      final ForallType forallType = (ForallType) type;
      Subst subst2 = subst;
      for (TypeVar typeVar : forallType.typeVars) {
        subst2 = subst2.plus(typeVar, unifier.variable());
      }
      return toTerm(forallType.type, subst2);
    default:
      throw new AssertionError("unknown type: " + type.moniker());
    }
  }

  /** Empty type environment. */
  enum EmptyTypeEnv implements TypeEnv {
    INSTANCE;

    @Override public Unifier.Term get(TypeSystem typeSystem, String name) {
      throw new CompileException("unbound variable or constructor: " + name);
    }

    @Override public boolean has(String name) {
      return false;
    }

    @Override public TypeEnv bind(String name,
        Function<TypeSystem, Unifier.Term> termFactory) {
      return new BindTypeEnv(name, termFactory, this);
    }

    @Override public String toString() {
      return "[]";
    }
  }

  /** Type environment. */
  interface TypeEnv {
    Unifier.Term get(TypeSystem typeSystem, String name);
    boolean has(String name);
    TypeEnv bind(String name, Function<TypeSystem, Unifier.Term> termFactory);

    default TypeEnv bind(String name, Unifier.Term term) {
      return bind(name, new Function<TypeSystem, Unifier.Term>() {
        @Override public Unifier.Term apply(TypeSystem typeSystem) {
          return term;
        }

        @Override public String toString() {
          return term.toString();
        }
      });
    }
  }

  /** Pair consisting of a term and a variable. */
  private static class TermVariable {
    final Unifier.Term term;
    final Unifier.Variable variable;

    private TermVariable(Unifier.Term term, Unifier.Variable variable) {
      this.term = term;
      this.variable = variable;
    }

    @Override public String toString() {
      return term + " = " + variable;
    }
  }

  /** A type environment that consists of a type environment plus one
   * binding. */
  private static class BindTypeEnv implements TypeEnv {
    private final String definedName;
    private final Function<TypeSystem, Unifier.Term> termFactory;
    private final TypeEnv parent;

    BindTypeEnv(String definedName,
        Function<TypeSystem, Unifier.Term> termFactory, TypeEnv parent) {
      this.definedName = Objects.requireNonNull(definedName);
      this.termFactory = Objects.requireNonNull(termFactory);
      this.parent = Objects.requireNonNull(parent);
    }

    @Override public Unifier.Term get(TypeSystem typeSystem, String name) {
      for (BindTypeEnv e = this;; e = (BindTypeEnv) e.parent) {
        if (e.definedName.equals(name)) {
          return e.termFactory.apply(typeSystem);
        }
        if (!(e.parent instanceof BindTypeEnv)) {
          return e.parent.get(typeSystem, name);
        }
      }
    }

    @Override public boolean has(String name) {
      return name.equals(definedName) || parent.has(name);
    }

    @Override public TypeEnv bind(String name,
        Function<TypeSystem, Unifier.Term> termFactory) {
      return new BindTypeEnv(name, termFactory, this);
    }

    @Override public String toString() {
      final Map<String, String> map = new LinkedHashMap<>();
      for (BindTypeEnv e = this;;) {
        map.putIfAbsent(e.definedName, e.termFactory.toString());
        if (e.parent instanceof BindTypeEnv) {
          e = (BindTypeEnv) e.parent;
        } else {
          return map.toString();
        }
      }
    }
  }

  /** Contains a {@link TypeEnv} and adds to it by calling
   * {@link TypeEnv#bind(String, Function)}. */
  private class TypeEnvHolder implements BiConsumer<String, Type> {
    private TypeEnv typeEnv;

    TypeEnvHolder(TypeEnv typeEnv) {
      this.typeEnv = Objects.requireNonNull(typeEnv);
    }

    @Override public void accept(String name, Type type) {
      typeEnv = typeEnv.bind(name, new Function<TypeSystem, Unifier.Term>() {
        @Override public Unifier.Term apply(TypeSystem typeSystem_) {
          return TypeResolver.this.toTerm(type, Subst.EMPTY);
        }

        @Override public String toString() {
          return type.moniker();
        }
      });
    }
  }

  /** Result of validating a declaration. */
  public static class Resolved {
    public final Environment env;
    public final Ast.Decl originalNode;
    public final Ast.Decl node;
    public final TypeMap typeMap;

    private Resolved(Environment env,
        Ast.Decl originalNode, Ast.Decl node, TypeMap typeMap) {
      this.env = env;
      this.originalNode = Objects.requireNonNull(originalNode);
      this.node = Objects.requireNonNull(node);
      this.typeMap = Objects.requireNonNull(typeMap);
      Preconditions.checkArgument(originalNode instanceof Ast.FunDecl
          ? node instanceof Ast.ValDecl
          : originalNode.getClass() == node.getClass());
    }

    static Resolved of(Environment env, Ast.Decl originalNode, Ast.Decl node,
        TypeMap typeMap) {
      return new Resolved(env, originalNode, node, typeMap);
    }

    public Ast.Exp exp() {
      if (node instanceof Ast.ValDecl) {
        final Ast.ValDecl valDecl = (Ast.ValDecl) this.node;
        if (valDecl.valBinds.size() == 1) {
          final Ast.ValBind valBind = valDecl.valBinds.get(0);
          return valBind.exp;
        }
      }
      throw new AssertionError("not an expression: " + node);
    }
  }

  /** Substitution. */
  private abstract static class Subst {
    static final Subst EMPTY = new EmptySubst();

    Subst plus(TypeVar typeVar, Unifier.Variable variable) {
      return new PlusSubst(this, typeVar, variable);
    }

    abstract Unifier.Variable get(TypeVar typeVar);
  }

  /** Empty substitution. */
  private static class EmptySubst extends Subst {
    @Override public String toString() {
      return "[]";
    }

    @Override Unifier.Variable get(TypeVar typeVar) {
      return null;
    }
  }

  /** Substitution that adds one (type, variable) assignment to a parent
   * substitution. */
  private static class PlusSubst extends Subst {
    final Subst parent;
    final TypeVar typeVar;
    final Unifier.Variable variable;

    PlusSubst(Subst parent, TypeVar typeVar, Unifier.Variable variable) {
      this.parent = parent;
      this.typeVar = typeVar;
      this.variable = variable;
    }

    @Override Unifier.Variable get(TypeVar typeVar) {
      return typeVar.equals(this.typeVar)
          ? variable
          : parent.get(typeVar);
    }

    @Override public String toString() {
      final Map<TypeVar, Unifier.Term> map = new LinkedHashMap<>();
      for (PlusSubst e = this;;) {
        map.putIfAbsent(e.typeVar, e.variable);
        if (e.parent instanceof PlusSubst) {
          e = (PlusSubst) e.parent;
        } else {
          return map.toString();
        }
      }
    }
  }
}

// End TypeResolver.java
