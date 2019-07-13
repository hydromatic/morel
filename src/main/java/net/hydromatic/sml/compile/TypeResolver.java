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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import net.hydromatic.sml.ast.Ast;
import net.hydromatic.sml.ast.AstNode;
import net.hydromatic.sml.ast.Pos;
import net.hydromatic.sml.type.DataType;
import net.hydromatic.sml.type.DummyType;
import net.hydromatic.sml.type.FnType;
import net.hydromatic.sml.type.ListType;
import net.hydromatic.sml.type.NamedType;
import net.hydromatic.sml.type.PrimitiveType;
import net.hydromatic.sml.type.RecordType;
import net.hydromatic.sml.type.TupleType;
import net.hydromatic.sml.type.Type;
import net.hydromatic.sml.type.TypeSystem;
import net.hydromatic.sml.type.TypeVar;
import net.hydromatic.sml.util.MapList;
import net.hydromatic.sml.util.MartelliUnifier;
import net.hydromatic.sml.util.Pair;
import net.hydromatic.sml.util.Unifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static net.hydromatic.sml.ast.AstBuilder.ast;

/** Resolves the type of an expression. */
@SuppressWarnings("StaticPseudoFunctionalStyleMethod")
public class TypeResolver {
  final TypeSystem typeSystem;
  final Unifier unifier = new MartelliUnifier();
  final List<TermVariable> terms = new ArrayList<>();
  final Map<AstNode, Unifier.Term> map = new HashMap<>();
  final Map<Unifier.Variable, Unifier.Action> actionMap = new HashMap<>();

  private static final String TUPLE_TY_CON = "*";
  private static final String LIST_TY_CON = "[]";
  private static final String RECORD_TY_CON = "record";
  private static final String FN_TY_CON = "fn";

  private TypeResolver(TypeSystem typeSystem) {
    this.typeSystem = Objects.requireNonNull(typeSystem);
  }

  public static <E extends AstNode> Resolved<E> deduceType(Environment env,
      E node, TypeSystem typeSystem) {
    return new TypeResolver(typeSystem).deduceType_(env, node);
  }

  private <E extends AstNode> Resolved<E> deduceType_(Environment env,
      E node) {
    final Type boolTerm = PrimitiveType.BOOL;
    final Type intTerm = PrimitiveType.INT;
    final Type boolToBool = typeSystem.fnType(boolTerm, boolTerm);
    final Type intToInt = typeSystem.fnType(intTerm, intTerm);
    final TypeEnvHolder typeEnvs =
        new TypeEnvHolder(EmptyTypeEnv.INSTANCE)
            .bind("true", boolTerm)
            .bind("false", boolTerm)
            .bind("not", boolToBool)
            .bind("abs", intToInt);
    env.forEachType(typeEnvs::bind);
    final TypeEnv typeEnv = typeEnvs.typeEnv;
    final Unifier.Variable v = unifier.variable();
    final AstNode node2 = deduceType(typeEnv, node, v);
    final List<Unifier.TermTerm> termPairs = new ArrayList<>();
    terms.forEach(tv ->
        termPairs.add(new Unifier.TermTerm(tv.term, tv.variable)));
    final Unifier.Result result =
        unifier.unify(termPairs, actionMap);
    if (result instanceof Unifier.Substitution) {
      return Resolved.of((E) node2,
          new TypeMap(typeSystem, map, (Unifier.Substitution) result));
    } else {
      throw new RuntimeException("Cannot deduce type: " + result
          + ";\n"
          + " term pairs:\n"
          + terms.stream().map(Object::toString)
              .collect(Collectors.joining("\n")));
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

  private AstNode deduceType(TypeEnv env, AstNode node, Unifier.Variable v) {
    final Unifier.Variable v2;
    final Unifier.Variable v3;
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

    case VAL_DECL:
      return deduceDeclType(env, (Ast.Decl) node, new LinkedHashMap<>());

    case TUPLE:
      final Ast.Tuple tuple = (Ast.Tuple) node;
      final List<Unifier.Term> types = new ArrayList<>();
      for (Ast.Exp arg : tuple.args) {
        final Unifier.Variable vArg = unifier.variable();
        deduceType(env, arg, vArg);
        types.add(vArg);
      }
      return reg(tuple, v, unifier.apply(TUPLE_TY_CON, types));

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
      final Map<Ast.IdPat, Unifier.Term> termMap = new LinkedHashMap<>();
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
        final List<String> fieldList = ((Unifier.Sequence) argType).fieldList();
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
      if (from.filterExp != null) {
        final Unifier.Variable v5 = unifier.variable();
        deduceType(env2, from.filterExp, v5);
        equiv(v5, toTerm(PrimitiveType.BOOL));
      }
      v3 = unifier.variable();
      deduceType(env2, from.yieldExpOrDefault, v3);
      return reg(from, v, unifier.apply(LIST_TY_CON, v3));

    case ID:
      final Ast.Id id = (Ast.Id) node;
      return reg(id, v, env.get(id.name));

    case FN:
      final Ast.Fn fn = (Ast.Fn) node;
      final Unifier.Variable resultVariable = unifier.variable();
      for (Ast.Match match : fn.matchList) {
        deduceMatchType(env, match, new HashMap<>(), v, resultVariable);
      }
      return reg(fn, null, v);

    case MATCH:
      final Ast.Match match = (Ast.Match) node;
      return deduceMatchType(env, match, new LinkedHashMap<>(), v,
          unifier.variable());

    case APPLY:
      final Ast.Apply apply = (Ast.Apply) node;
      final Unifier.Variable vFn = unifier.variable();
      final Unifier.Variable vArg = unifier.variable();
      equiv(unifier.apply(FN_TY_CON, vArg, v), vFn);
      deduceType(env, apply.arg, vArg);
      if (apply.fn instanceof Ast.RecordSelector) {
        deduceRecordSelectorType(env, v, vArg,
            (Ast.RecordSelector) apply.fn);
      } else {
        deduceType(env, apply.fn, vFn);
      }
      return reg(apply, null, v);

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

  private boolean deduceRecordSelectorType(TypeEnv env,
      Unifier.Variable vResult, Unifier.Variable vArg,
      Ast.RecordSelector recordSelector) {
    actionMap.put(vArg, (v, t, termPairs) -> {
      // We now know that the type arg, say "{a: int, b: real}".
      // So, now we can declare that the type of vResult, say "#b", is
      // "real".
      if (t instanceof Unifier.Sequence) {
        final Unifier.Sequence sequence = (Unifier.Sequence) t;
        final List<String> fieldList = sequence.fieldList();
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
    return true;
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
    final Ast.Exp e2 = (Ast.Exp) deduceType(env2, valBind.e, vPat);
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
    final Ast.Decl node2;
    switch (node.op) {
    case VAL_DECL:
      final Ast.ValDecl valDecl = (Ast.ValDecl) node;
      final List<Ast.ValBind> valBinds = new ArrayList<>();
      for (Ast.ValBind valBind : valDecl.valBinds) {
        valBinds.add((Ast.ValBind)
            deduceValBindType(env, valBind, termMap, unifier.variable()));
      }
      node2 = valDecl.copy(valBinds);
      map.put(node2, toTerm(PrimitiveType.UNIT));
      return node2;

    case FUN_DECL:
      final Ast.FunDecl funDecl = (Ast.FunDecl) node;
      node2 = toValDecl(env, funDecl);
      return deduceDeclType(env, node2, termMap);

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
      Type type = dataType;
      if (tyCon.type != null) {
        type = typeSystem.fnType(toType(tyCon.type), type);
      }
      termMap.put((Ast.IdPat) ast.idPat(tyCon.pos, tyCon.id.name),
          toTerm(type));
    }
    env.bind(datatypeBind.name.name,
        unifier.atom(dataType.description()));
  }

  private Type toType(Ast.Type type) {
    switch (type.op) {
    case TUPLE_TYPE:
      final Ast.TupleType tupleType = (Ast.TupleType) type;
      return typeSystem.tupleType(toTypes(tupleType.types));

    case NAMED_TYPE:
      final Ast.NamedType namedType = (Ast.NamedType) type;
      if (namedType.types.isEmpty()) {
        return typeSystem.lookup(namedType.name);
      }
      // fall through

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
  private static Ast.ValDecl toValDecl(TypeEnv env, Ast.FunDecl funDecl) {
    final List<Ast.ValBind> valBindList = new ArrayList<>();
    for (Ast.FunBind funBind : funDecl.funBinds) {
      valBindList.add(toValBind(env, funBind));
    }
    return ast.valDecl(funDecl.pos, valBindList);
  }

  private static Ast.ValBind toValBind(TypeEnv env, Ast.FunBind funBind) {
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
  private static Ast.Pat patTuple(TypeEnv env, List<Ast.Pat> patList) {
    final List<Ast.Pat> list2 = new ArrayList<>();
    for (int i = 0; i < patList.size(); i++) {
      final Ast.Pat pat = patList.get(i);
      switch (pat.op) {
      case ID_PAT:
        final Ast.IdPat idPat = (Ast.IdPat) pat;
        if (env.has(idPat.name)) {
          final Unifier.Term term = env.get(idPat.name);
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
        throw new AssertionError();
      }
      final DataType dataType = pair.left;
      final Type argType = pair.right;
      final Unifier.Variable vArg = unifier.variable();
      deducePatType(env, conPat.pat, termMap, null, vArg);
      equiv(vArg, toTerm(argType));
      return reg(pat, v, toTerm(dataType));

    case CON0_PAT:
      final Ast.Con0Pat con0Pat = (Ast.Con0Pat) pat;
      final Pair<DataType, Type> pair0 =
          typeSystem.lookupTyCon(con0Pat.tyCon.name);
      if (pair0 == null) {
        throw new AssertionError();
      }
      final DataType dataType0 = pair0.left;
      return reg(pat, v, toTerm(dataType0));

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
  private AstNode infix(TypeEnv env, Ast.InfixCall call, Unifier.Variable v,
      Type type) {
    final Unifier.Term term = toTerm(type);
    call.forEachArg((arg, i) -> deduceType(env, arg, v));
    return reg(call, v, term);
  }

  /** Registers an infix operator whose type is a given type
   * and whose arguments are the same type. */
  private AstNode comparison(TypeEnv env, Ast.InfixCall call,
      Unifier.Variable v) {
    final Unifier.Term term = toTerm(PrimitiveType.BOOL);
    final Unifier.Variable argVariable = unifier.variable();
    call.forEachArg((arg, i) -> deduceType(env, arg, argVariable));
    return reg(call, v, term);
  }

  /** Registers an infix or prefix operator whose type is the same as its
   * arguments. */
  private AstNode infixOverloaded(TypeEnv env, Ast.InfixCall call,
      Unifier.Variable v, Type defaultType) {
    return opOverloaded(env, call, v, defaultType);
  }

  /** Registers an infix or prefix operator whose type is the same as its
   * arguments. */
  private AstNode prefixOverloaded(TypeEnv env, Ast.PrefixCall call,
      Unifier.Variable v, Type defaultType) {
    return opOverloaded(env, call, v, defaultType);
  }

  /** Registers an infix or prefix operator whose type is the same as its
   * arguments. */
  private AstNode opOverloaded(TypeEnv env, Ast.Exp call,
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
    equiv(v, toTerm(types[0]));
    return reg(call, null, v);
  }

  private AstNode deduceConsType(TypeEnv env, Ast.InfixCall call,
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

  private Unifier.Term toTerm(PrimitiveType type) {
    return unifier.atom(type.description());
  }

  private Unifier.Term toTerm(Type type) {
    switch (type.op()) {
    case ID:
      return toTerm((PrimitiveType) type);
    case DATA_TYPE:
    case TEMPORARY_DATA_TYPE:
      return unifier.atom(((NamedType) type).name());
    case FUNCTION_TYPE:
      final FnType fnType = (FnType) type;
      return unifier.apply(FN_TY_CON, toTerm(fnType.paramType),
          toTerm(fnType.resultType));
    case TUPLE_TYPE:
      final TupleType tupleType = (TupleType) type;
      return unifier.apply(TUPLE_TY_CON, tupleType.argTypes.stream()
          .map(this::toTerm).collect(Collectors.toList()));
    case RECORD_TYPE:
      final RecordType recordType = (RecordType) type;
      return unifier.apply(recordOp((NavigableSet) recordType.argNameTypes.keySet()),
          recordType.argNameTypes.values().stream()
          .map(this::toTerm).collect(Collectors.toList()));
    case LIST:
      final ListType listType = (ListType) type;
      return unifier.apply(LIST_TY_CON,
          toTerm(listType.elementType));
    default:
      throw new AssertionError("unknown type: " + type);
    }
  }

  static <E> List<E> skip(List<E> list) {
    return list.subList(1, list.size());
  }

  /** Empty type environment. */
  enum EmptyTypeEnv implements TypeEnv {
    INSTANCE;

    @Override public Unifier.Term get(String name) {
      throw new CompileException("unbound variable or constructor: " + name);
    }

    @Override public boolean has(String name) {
      return false;
    }

    @Override public TypeEnv bind(String name, Unifier.Term typeTerm) {
      return new BindTypeEnv(name, typeTerm, this);
    }

    @Override public String toString() {
      return "[]";
    }
  }

  /** Type environment. */
  interface TypeEnv {
    Unifier.Term get(String name);
    boolean has(String name);
    TypeEnv bind(String name, Unifier.Term typeTerm);
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
    private TypeSystem typeSystem;
    private final Unifier.Substitution substitution;

    TermToTypeConverter(TypeSystem typeSystem,
        Unifier.Substitution substitution) {
      this.typeSystem = typeSystem;
      this.substitution = substitution;
    }

    public Type visit(Unifier.Sequence sequence) {
      final ImmutableList.Builder<Type> argTypes;
      switch (sequence.operator) {
      case "fn":
        assert sequence.terms.size() == 2;
        final Type paramType = sequence.terms.get(0).accept(this);
        final Type resultType = sequence.terms.get(1).accept(this);
        return typeSystem.fnType(paramType, resultType);

      case "*":
        assert sequence.terms.size() >= 2;
        argTypes = ImmutableList.builder();
        for (Unifier.Term term : sequence.terms) {
          argTypes.add(term.accept(this));
        }
        return typeSystem.tupleType(argTypes.build());

      case "[]":
        assert sequence.terms.size() == 1;
        final Type elementType = sequence.terms.get(0).accept(this);
        return typeSystem.listType(elementType);

      case "bool":
      case "int":
      case "real":
      case "string":
      case "unit":
      default:
        final Type type = typeSystem.lookupOpt(sequence.operator);
        if (type != null) {
          return type;
        }
        if (sequence.operator.startsWith(RECORD_TY_CON)) {
          // E.g. "record:a:b" becomes record type "{a:t0, b:t1}".
          final List<String> argNames = sequence.fieldList();
          if (argNames != null) {
            argTypes = ImmutableList.builder();
            for (Unifier.Term term : sequence.terms) {
              argTypes.add(term.accept(this));
            }
            return typeSystem.recordType(argNames, argTypes.build());
          }
        }
        throw new AssertionError("unknown type constructor "
            + sequence.operator);
      }
    }

    public Type visit(Unifier.Variable variable) {
      final Unifier.Term term = substitution.resultMap.get(variable);
      return term.accept(this);
    }
  }

  /** A type environment that consists of a type environment plus one
   * binding. */
  private static class BindTypeEnv implements TypeEnv {
    private final String definedName;
    private final Unifier.Term typeTerm;
    private final TypeEnv parent;

    BindTypeEnv(String definedName, Unifier.Term typeTerm, TypeEnv parent) {
      this.definedName = Objects.requireNonNull(definedName);
      this.typeTerm = Objects.requireNonNull(typeTerm);
      this.parent = Objects.requireNonNull(parent);
    }

    @Override public Unifier.Term get(String name) {
      if (name.equals(definedName)) {
        return typeTerm;
      } else {
        return parent.get(name);
      }
    }

    @Override public boolean has(String name) {
      return name.equals(definedName) || parent.has(name);
    }

    @Override public TypeEnv bind(String name, Unifier.Term typeTerm) {
      return new BindTypeEnv(name, typeTerm, this);
    }

    @Override public String toString() {
      final Map<String, Unifier.Term> map = new LinkedHashMap<>();
      for (BindTypeEnv e = this;;) {
        map.putIfAbsent(e.definedName, e.typeTerm);
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

    TypeMap(TypeSystem typeSystem, Map<AstNode, Unifier.Term> nodeTypeTerms,
        Unifier.Substitution substitution) {
      this.typeSystem = Objects.requireNonNull(typeSystem);
      this.nodeTypeTerms = ImmutableMap.copyOf(nodeTypeTerms);
      this.substitution = Objects.requireNonNull(substitution);
    }

    private Type termToType(Unifier.Term term,
        Unifier.Substitution substitution) {
      return term.accept(new TermToTypeConverter(typeSystem, substitution));
    }

    /** Returns a type of an AST node. */
    public Type getType(AstNode node) {
      final Unifier.Term term = Objects.requireNonNull(nodeTypeTerms.get(node));
      return termToType(term, substitution);
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
   * {@link TypeEnv#bind(String, Unifier.Term)}. */
  private class TypeEnvHolder {
    private TypeEnv typeEnv;

    TypeEnvHolder(TypeEnv typeEnv) {
      this.typeEnv = Objects.requireNonNull(typeEnv);
    }

    TypeEnvHolder bind(String name, Type type) {
      typeEnv = typeEnv.bind(name, toTerm(type));
      return this;
    }
  }

  /** Result of validating a statement or expression.
   *
   * @param <E> Node type */
  public static class Resolved<E extends AstNode> {
    public final E node;
    public final TypeMap typeMap;

    private Resolved(E node, TypeMap typeMap) {
      this.node = Objects.requireNonNull(node);
      this.typeMap = Objects.requireNonNull(typeMap);
    }

    static <E2 extends AstNode> Resolved<E2> of(E2 node, TypeMap typeMap) {
      return new Resolved<>(node, typeMap);
    }
  }
}

// End TypeResolver.java
