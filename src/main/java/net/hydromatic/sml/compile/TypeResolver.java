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
package net.hydromatic.sml.compile;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;

import net.hydromatic.sml.ast.Ast;
import net.hydromatic.sml.ast.AstNode;
import net.hydromatic.sml.ast.Pos;
import net.hydromatic.sml.type.ApplyType;
import net.hydromatic.sml.type.DataType;
import net.hydromatic.sml.type.DummyType;
import net.hydromatic.sml.type.FnType;
import net.hydromatic.sml.type.ForallType;
import net.hydromatic.sml.type.ListType;
import net.hydromatic.sml.type.NamedType;
import net.hydromatic.sml.type.PrimitiveType;
import net.hydromatic.sml.type.RecordType;
import net.hydromatic.sml.type.TupleType;
import net.hydromatic.sml.type.Type;
import net.hydromatic.sml.type.TypeSystem;
import net.hydromatic.sml.type.TypeVar;
import net.hydromatic.sml.util.ConsList;
import net.hydromatic.sml.util.MapList;
import net.hydromatic.sml.util.MartelliUnifier;
import net.hydromatic.sml.util.Pair;
import net.hydromatic.sml.util.Unifier;

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
import java.util.stream.Collectors;

import static net.hydromatic.sml.ast.AstBuilder.ast;
import static net.hydromatic.sml.util.Static.toImmutableList;

/** Resolves the type of an expression. */
@SuppressWarnings("StaticPseudoFunctionalStyleMethod")
public class TypeResolver {
  final TypeSystem typeSystem;
  final Unifier unifier = new MartelliUnifier();
  final List<TermVariable> terms = new ArrayList<>();
  final Map<AstNode, Unifier.Term> map = new HashMap<>();
  final Map<Unifier.Variable, Unifier.Action> actionMap = new HashMap<>();
  final Map<String, TypeVar> tyVarMap = new HashMap<>();

  private static final String TUPLE_TY_CON = "tuple";
  private static final String LIST_TY_CON = "list";
  private static final String RECORD_TY_CON = "record";
  private static final String FN_TY_CON = "fn";
  private static final String APPLY_TY_CON = "apply";

  private TypeResolver(TypeSystem typeSystem) {
    this.typeSystem = Objects.requireNonNull(typeSystem);
  }

  /** Deduces the type of a declaration. */
  public static Resolved deduceType(Environment env, Ast.Decl decl,
      TypeSystem typeSystem) {
    return new TypeResolver(typeSystem).deduceType_(env, decl);
  }

  private Resolved deduceType_(Environment env, Ast.Decl decl) {
    final TypeEnvHolder typeEnvs = new TypeEnvHolder(EmptyTypeEnv.INSTANCE);
    BuiltIn.forEachType(typeSystem, typeEnvs);
    env.forEachType(typeEnvs);
    final TypeEnv typeEnv = typeEnvs.typeEnv;
    final Map<Ast.IdPat, Unifier.Term> termMap = new LinkedHashMap<>();
    final Ast.Decl node2 = deduceDeclType(typeEnv, decl, termMap);
    final List<Unifier.TermTerm> termPairs = new ArrayList<>();
    terms.forEach(tv ->
        termPairs.add(new Unifier.TermTerm(tv.term, tv.variable)));
    final Unifier.Result result =
        unifier.unify(termPairs, actionMap);
    if (result instanceof Unifier.Substitution) {
      return Resolved.of(decl, node2,
          new TypeMap(typeSystem, map, (Unifier.Substitution) result));
    } else {
      final String extra = ";\n"
          + " term pairs:\n"
          + terms.stream().map(Object::toString)
          .collect(Collectors.joining("\n"));
      throw new RuntimeException("Cannot deduce type: " + result);
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
    final Unifier.Variable v2;
    final Unifier.Variable v3;
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

    case EQ:
    case NE:
    case LT:
    case GT:
    case LE:
    case GE:
      return comparison(env, (Ast.InfixCall) node, v);

    case TUPLE:
      final Ast.Tuple tuple = (Ast.Tuple) node;
      final List<Unifier.Term> types = new ArrayList<>();
      final List<Ast.Exp> args2 = new ArrayList<>();
      for (Ast.Exp arg : tuple.args) {
        final Unifier.Variable vArg = unifier.variable();
        args2.add(deduceType(env, arg, vArg));
        types.add(vArg);
      }
      return reg(tuple.copy(args2), v, unifier.apply(TUPLE_TY_CON, types));

    case LIST:
      final Ast.List list = (Ast.List) node;
      final Unifier.Variable vArg2 = unifier.variable();
      for (Ast.Exp arg : list.args) {
        deduceType(env, arg, vArg2);
      }
      return reg(list, v, unifier.apply(LIST_TY_CON, vArg2));

    case RECORD:
      final Ast.Record record = (Ast.Record) node;
      final NavigableMap<String, Unifier.Term> labelTypes = new TreeMap<>();
      record.args.forEach((name, exp) -> {
        final Unifier.Variable vArg = unifier.variable();
        deduceType(env, exp, vArg);
        labelTypes.put(name, vArg);
      });
      final Unifier.Sequence recordTerm =
          unifier.apply(recordOp(labelTypes.navigableKeySet()),
              labelTypes.values());
      return reg(record, v, recordTerm);

    case LET:
      final Ast.LetExp let = (Ast.LetExp) node;
      termMap = new LinkedHashMap<>();
      TypeEnv env2 = env;
      final List<Ast.Decl> decls = new ArrayList<>();
      for (Ast.Decl decl : let.decls) {
        decls.add(deduceDeclType(env2, decl, termMap));
        env2 = bindAll(env2, termMap);
        termMap.clear();
      }
      final Ast.LetExp let2 = let.copy(decls, let.e);
      deduceType(env2, let2.e, v);
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
      deduceType(env, if_.condition, v2);
      equiv(v2, toTerm(PrimitiveType.BOOL));
      deduceType(env, if_.ifTrue, v);
      deduceType(env, if_.ifFalse, v);
      return reg(if_, null, v);

    case CASE:
      final Ast.Case case_ = (Ast.Case) node;
      v2 = unifier.variable();
      deduceType(env, case_.e, v2);
      final NavigableSet<String> labelNames = new TreeSet<>();
      final Unifier.Term argType = map.get(case_.e);
      if (argType instanceof Unifier.Sequence) {
        final List<String> fieldList = fieldList((Unifier.Sequence) argType);
        if (fieldList != null) {
          labelNames.addAll(fieldList);
        }
      }
      deduceMatchListType(env, case_.matchList, labelNames, v2, v);
      return reg(case_, null, v);

    case FROM:
      // "(from exp: v50 as id: v60 [, exp: v51 as id: v61]...
      //  [where filterExp: v5] [yield yieldExp: v4]): v"
      final Ast.From from = (Ast.From) node;
      env2 = env;
      final Map<String, Unifier.Variable> fieldVars = new LinkedHashMap<>();
      for (Map.Entry<Ast.Id, Ast.Exp> source : from.sources.entrySet()) {
        final Ast.Id id = source.getKey();
        final Ast.Exp exp = source.getValue();
        final Unifier.Variable v5 = unifier.variable();
        final Unifier.Variable v6 = unifier.variable();
        deduceType(env, exp, v5);
        reg(exp, v5, unifier.apply(LIST_TY_CON, v6));
        env2 = env2.bind(id.name, v6);
        fieldVars.put(id.name, v6);
      }
      final Ast.Exp filter2;
      if (from.filterExp != null) {
        final Unifier.Variable v5 = unifier.variable();
        filter2 = deduceType(env2, from.filterExp, v5);
        equiv(v5, toTerm(PrimitiveType.BOOL));
      } else {
        filter2 = null;
      }
      v3 = unifier.variable();
      final Ast.Exp yieldExpOrDefault2 =
          deduceType(env2, from.yieldExpOrDefault, v3);
      final Ast.Exp yieldExp2 =
          from.yieldExp == null ? null : yieldExpOrDefault2;
      final Ast.From from2 = from.copy(from.sources, filter2, yieldExp2,
          from.groupExps, from.aggregates);
      return reg(from2, v, unifier.apply(LIST_TY_CON, v3));

    case ID:
      final Ast.Id id = (Ast.Id) node;
      final Unifier.Term term = env.get(typeSystem, id.name);
      return reg(id, v, term);

    case FN:
      final Ast.Fn fn = (Ast.Fn) node;
      final Unifier.Variable resultVariable = unifier.variable();
      for (Ast.Match match : fn.matchList) {
        deduceMatchType(env, match, new HashMap<>(), v, resultVariable);
      }
      return reg(fn, null, v);

    case APPLY:
      final Ast.Apply apply = (Ast.Apply) node;
      final Unifier.Variable vFn = unifier.variable();
      final Unifier.Variable vArg = unifier.variable();
      equiv(unifier.apply(FN_TY_CON, vArg, v), vFn);
      final Ast.Exp arg2 = deduceType(env, apply.arg, vArg);
      final Ast.Exp fn2;
      if (apply.fn instanceof Ast.RecordSelector) {
        fn2 = deduceRecordSelectorType(env, v, vArg,
            (Ast.RecordSelector) apply.fn);
      } else {
        fn2 = deduceType(env, apply.fn, vFn);
      }
      return reg(apply.copy(fn2, arg2), null, v);

    case PLUS:
    case MINUS:
    case TIMES:
    case DIVIDE:
    case DIV:
    case MOD:
      return infixOverloaded(env, (Ast.InfixCall) node, v, PrimitiveType.INT);

    case NEGATE:
      return prefixOverloaded(env, (Ast.PrefixCall) node, v, PrimitiveType.INT);

    case CARET:
      return infix(env, (Ast.InfixCall) node, v, PrimitiveType.STRING);

    case CONS:
      return deduceConsType(env, (Ast.InfixCall) node, v);

    default:
      throw new AssertionError("cannot deduce type for " + node.op);
    }
  }

  private String recordOp(NavigableSet<String> labelNames) {
    final StringBuilder b = new StringBuilder(RECORD_TY_CON);
    for (String label : labelNames) {
      b.append(':').append(label);
    }
    return b.toString();
  }

  private Ast.RecordSelector deduceRecordSelectorType(TypeEnv env,
      Unifier.Variable vResult, Unifier.Variable vArg,
      Ast.RecordSelector recordSelector) {
    actionMap.put(vArg, (v, t, termPairs) -> {
      // We now know that the type arg, say "{a: int, b: real}".
      // So, now we can declare that the type of vResult, say "#b", is
      // "real".
      if (t instanceof Unifier.Sequence) {
        final Unifier.Sequence sequence = (Unifier.Sequence) t;
        final List<String> fieldList = fieldList(sequence);
        if (fieldList != null) {
          int i = fieldList.indexOf(recordSelector.name);
          if (i >= 0) {
            termPairs.add(
                new Unifier.TermTerm(vResult,
                    sequence.terms.get(i)));
            recordSelector.slot = i;
          }
        }
      }
    });
    return recordSelector;
  }

  private static List<String> fieldList(final Unifier.Sequence sequence) {
    if (sequence.operator.equals(RECORD_TY_CON)) {
      return ImmutableList.of();
    } else if (sequence.operator.startsWith(RECORD_TY_CON + ":")) {
      final String[] fields = sequence.operator.split(":");
      return Arrays.asList(fields).subList(1, fields.length);
    } else if (sequence.operator.equals(TUPLE_TY_CON)) {
      return new AbstractList<String>() {
        public int size() {
          return sequence.terms.size();
        }

        public String get(int index) {
          return Integer.toString(index + 1);
        }
      };
    } else {
      return null;
    }
  }

  private AstNode deduceMatchType(TypeEnv env, Ast.Match match,
      Map<Ast.IdPat, Unifier.Term> termMap, Unifier.Variable argVariable,
      Unifier.Variable resultVariable) {
    final Unifier.Variable vPat = unifier.variable();
    deducePatType(env, match.pat, termMap, null, vPat);
    TypeEnv env2 = bindAll(env, termMap);
    deduceType(env2, match.e, resultVariable);
    return reg(match, argVariable,
        unifier.apply(FN_TY_CON, vPat, resultVariable));
  }

  private void deduceMatchListType(TypeEnv env, List<Ast.Match> matchList,
      NavigableSet<String> labelNames, Unifier.Variable argVariable,
      Unifier.Variable resultVariable) {
    for (Ast.Match match : matchList) {
      if (match.pat instanceof Ast.RecordPat) {
        labelNames.addAll(((Ast.RecordPat) match.pat).args.keySet());
      }
    }
    for (Ast.Match match : matchList) {
      final Map<Ast.IdPat, Unifier.Term> termMap = new HashMap<>();
      deducePatType(env, match.pat, termMap, labelNames, argVariable);
      final TypeEnv env2 = bindAll(env, termMap);
      deduceType(env2, match.e, resultVariable);
    }
  }

  private AstNode deduceValBindType(TypeEnv env, Ast.ValBind valBind,
      Map<Ast.IdPat, Unifier.Term> termMap, Unifier.Variable v) {
    final Unifier.Variable vPat = unifier.variable();
    deducePatType(env, valBind.pat, termMap, null, vPat);
    TypeEnv env2 = env;
    if (valBind.rec
        && valBind.pat instanceof Ast.IdPat) {
      // If recursive, bind the value (presumably a function) to its type
      // in the environment before we try to deduce the type of the expression.
      env2 = env2.bind(((Ast.IdPat) valBind.pat).name, vPat);
    }
    final Ast.Exp e2 = deduceType(env2, valBind.e, vPat);
    final Ast.ValBind valBind2 = valBind.copy(valBind.rec, valBind.pat, e2);
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
    Ast.Decl node2;
    final List<Ast.ValBind> valBinds = new ArrayList<>();
    for (Ast.ValBind valBind : valDecl.valBinds) {
      valBinds.add((Ast.ValBind)
          deduceValBindType(env, valBind, termMap, unifier.variable()));
    }
    node2 = valDecl.copy(valBinds);
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
    return ast.valDecl(funDecl.pos, valBindList);
  }

  private Ast.ValBind toValBind(TypeEnv env, Ast.FunBind funBind) {
    final List<Ast.Pat> vars;
    Ast.Exp e;
    if (funBind.matchList.size() == 1) {
      e = funBind.matchList.get(0).e;
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
                funMatch.e));
      }
      e = ast.caseOf(Pos.ZERO, idTuple(varNames), matchList);
    }
    final Pos pos = funBind.pos;
    for (Ast.Pat var : Lists.reverse(vars)) {
      e = ast.fn(pos, ast.match(pos, var, e));
    }
    return ast.valBind(pos, true, ast.idPat(pos, funBind.name), e);
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
    case WILDCARD_PAT:
      return pat;

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
      return reg(pat, null, v);

    case TUPLE_PAT:
      final List<Unifier.Term> typeTerms = new ArrayList<>();
      final Ast.TuplePat tuple = (Ast.TuplePat) pat;
      for (Ast.Pat arg : tuple.args) {
        final Unifier.Variable vArg = unifier.variable();
        deducePatType(env, arg, termMap, null, vArg);
        typeTerms.add(vArg);
      }
      return reg(pat, v, unifier.apply(TUPLE_TY_CON, typeTerms));

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
      final Ast.RecordPat record = (Ast.RecordPat) pat;
      final NavigableMap<String, Unifier.Term> labelTerms = new TreeMap<>();
      if (labelNames == null) {
        labelNames = new TreeSet<>(record.args.keySet());
      }
      for (String labelName : labelNames) {
        final Unifier.Variable vArg = unifier.variable();
        labelTerms.put(labelName, vArg);
        final Ast.Pat argPat = record.args.get(labelName);
        if (argPat != null) {
          deducePatType(env, argPat, termMap, null, vArg);
        }
      }
      return reg(pat, v,
          unifier.apply(recordOp(labelTerms.navigableKeySet()),
              labelTerms.values()));

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
    call.forEachArg((arg, i) -> deduceType(env, arg, v));
    return reg(call, v, term);
  }

  /** Registers an infix operator whose type is a given type
   * and whose arguments are the same type. */
  private Ast.Exp comparison(TypeEnv env, Ast.InfixCall call,
      Unifier.Variable v) {
    final Unifier.Term term = toTerm(PrimitiveType.BOOL);
    final Unifier.Variable argVariable = unifier.variable();
    call.forEachArg((arg, i) -> deduceType(env, arg, argVariable));
    return reg(call, v, term);
  }

  /** Registers an infix or prefix operator whose type is the same as its
   * arguments. */
  private Ast.Exp infixOverloaded(TypeEnv env, Ast.InfixCall call,
      Unifier.Variable v, Type defaultType) {
    return opOverloaded(env, call, v, defaultType);
  }

  /** Registers an infix or prefix operator whose type is the same as its
   * arguments. */
  private Ast.Exp prefixOverloaded(TypeEnv env, Ast.PrefixCall call,
      Unifier.Variable v, Type defaultType) {
    return opOverloaded(env, call, v, defaultType);
  }

  /** Registers an infix or prefix operator whose type is the same as its
   * arguments. */
  private Ast.Exp opOverloaded(TypeEnv env, Ast.Exp call,
      Unifier.Variable v, Type defaultType) {
    Type[] types = {defaultType};
    call.forEachArg((arg, i) -> {
      deduceType(env, arg, v);

      // The following is for the "overloaded" operators '+', '*' etc.
      // Ideally we would treat them as polymorphic (viz 'a + 'a) and then
      // constraint 'a to be either int or real. But for now, we set the type
      // to 'int' unless one of the arguments is obviously 'real'.
      if (map.get(arg) instanceof Unifier.Sequence
          && ((Unifier.Sequence) map.get(arg)).operator.equals("real")) {
        types[0] = PrimitiveType.REAL;
      }
    });
    equiv(v, toTerm(types[0], Subst.EMPTY));
    return reg(call, null, v);
  }

  private Ast.Exp deduceConsType(TypeEnv env, Ast.InfixCall call,
      Unifier.Variable v) {
    final Unifier.Variable elementType = unifier.variable();
    deduceType(env, call.a0, elementType);
    deduceType(env, call.a1, v);
    return reg(call, v, unifier.apply(LIST_TY_CON, elementType));
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
    return unifier.atom(type.description());
  }

  private Unifier.Term toTerm(Type type, Subst subst) {
    switch (type.op()) {
    case ID:
      return toTerm((PrimitiveType) type);
    case TY_VAR:
      final Unifier.Variable variable = subst.get((TypeVar) type);
      return variable != null ? variable : unifier.variable();
    case DATA_TYPE:
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
      //noinspection unchecked
      return unifier.apply(
          recordOp((NavigableSet) recordType.argNameTypes.keySet()),
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
      throw new AssertionError("unknown type: " + type.description());
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

  /** Visitor that converts type terms into actual types. */
  private static class TermToTypeConverter
      implements Unifier.TermVisitor<Type> {
    private final TypeMap typeMap;

    TermToTypeConverter(TypeMap typeMap) {
      this.typeMap = typeMap;
    }

    public Type visit(Unifier.Sequence sequence) {
      final ImmutableList.Builder<Type> argTypes;
      final ImmutableSortedMap.Builder<String, Type> argNameTypes;
      switch (sequence.operator) {
      case FN_TY_CON:
        assert sequence.terms.size() == 2;
        final Type paramType = sequence.terms.get(0).accept(this);
        final Type resultType = sequence.terms.get(1).accept(this);
        return typeMap.typeSystem.fnType(paramType, resultType);

      case TUPLE_TY_CON:
        assert sequence.terms.size() >= 2;
        argTypes = ImmutableList.builder();
        for (Unifier.Term term : sequence.terms) {
          argTypes.add(term.accept(this));
        }
        return typeMap.typeSystem.tupleType(argTypes.build());

      case LIST_TY_CON:
        assert sequence.terms.size() == 1;
        final Type elementType = sequence.terms.get(0).accept(this);
        return typeMap.typeSystem.listType(elementType);

      case "bool":
      case "char":
      case "int":
      case "real":
      case "string":
      case "unit":
      default:
        final Type type = typeMap.typeSystem.lookupOpt(sequence.operator);
        if (type != null) {
          return type;
        }
        if (sequence.operator.startsWith(RECORD_TY_CON)) {
          // E.g. "record:a:b" becomes record type "{a:t0, b:t1}".
          final List<String> argNames = fieldList(sequence);
          if (argNames != null) {
            argNameTypes = ImmutableSortedMap.orderedBy(RecordType.ORDERING);
            Pair.forEach(argNames, sequence.terms, (name, term) ->
                argNameTypes.put(name, term.accept(this)));
            return typeMap.typeSystem.recordType(argNameTypes.build());
          }
        }
        throw new AssertionError("unknown type constructor "
            + sequence.operator);
      }
    }

    public Type visit(Unifier.Variable variable) {
      final Unifier.Term term = typeMap.substitution.resultMap.get(variable);
      if (term == null) {
        return typeMap.typeVars.computeIfAbsent(variable.toString(),
            varName -> new TypeVar(typeMap.typeVars.size()));
      }
      return term.accept(this);
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

  /** The result of type resolution, a map from AST nodes to types. */
  public static class TypeMap {
    final TypeSystem typeSystem;
    final Map<AstNode, Unifier.Term> nodeTypeTerms;
    final Unifier.Substitution substitution;
    final Map<String, TypeVar> typeVars = new HashMap<>();

    TypeMap(TypeSystem typeSystem, Map<AstNode, Unifier.Term> nodeTypeTerms,
        Unifier.Substitution substitution) {
      this.typeSystem = Objects.requireNonNull(typeSystem);
      this.nodeTypeTerms = ImmutableMap.copyOf(nodeTypeTerms);
      this.substitution = Objects.requireNonNull(substitution.resolve());
    }

    private Type termToType(Unifier.Term term) {
      return term.accept(new TermToTypeConverter(this));
    }

    /** Returns a type of an AST node. */
    public Type getType(AstNode node) {
      final Unifier.Term term = Objects.requireNonNull(nodeTypeTerms.get(node));
      return termToType(term);
    }

    /** Returns whether an AST node has a type.
     *
     * <p>If it does not, perhaps it was ignored by the unification algorithm
     * because it is not relevant to the program. */
    public boolean hasType(AstNode node) {
      return nodeTypeTerms.containsKey(node);
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
          return type.description();
        }
      });
    }
  }

  /** Result of validating a declaration. */
  public static class Resolved {
    public final Ast.Decl originalNode;
    public final Ast.Decl node;
    public final TypeMap typeMap;

    private Resolved(Ast.Decl originalNode, Ast.Decl node, TypeMap typeMap) {
      this.originalNode = Objects.requireNonNull(originalNode);
      this.node = Objects.requireNonNull(node);
      this.typeMap = Objects.requireNonNull(typeMap);
      Preconditions.checkArgument(originalNode instanceof Ast.FunDecl
          ? node instanceof Ast.ValDecl
          : originalNode.getClass() == node.getClass());
    }

    static Resolved of(Ast.Decl originalNode, Ast.Decl node, TypeMap typeMap) {
      return new Resolved(originalNode, node, typeMap);
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
