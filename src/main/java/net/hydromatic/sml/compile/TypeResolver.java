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

import net.hydromatic.sml.ast.Ast;
import net.hydromatic.sml.ast.AstNode;
import net.hydromatic.sml.ast.Op;
import net.hydromatic.sml.type.PrimitiveType;
import net.hydromatic.sml.type.Type;
import net.hydromatic.sml.util.MartelliUnifier;
import net.hydromatic.sml.util.Ord;
import net.hydromatic.sml.util.Unifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Resolves the type of an expression. */
public class TypeResolver {
  final Unifier unifier = new MartelliUnifier();
  final List<TermVariable> terms = new ArrayList<>();
  final Map<AstNode, Unifier.Term> map = new HashMap<>();

  private static final String TUPLE_TY_CON = "*";
  private static final String FN_TY_CON = "fn";

  public TypeResolver() {
  }

  public static TypeMap deduceType(Environment env,
      AstNode node, TypeSystem typeSystem) {
    return new TypeResolver().deduceType_(env, node, typeSystem);
  }

  private TypeMap deduceType_(Environment env, AstNode node,
      TypeSystem typeSystem) {
    TypeEnv[] typeEnvs = {EmptyTypeEnv.INSTANCE
        .bind("true", toTerm(typeSystem.primitiveType("bool")))
        .bind("false", toTerm(typeSystem.primitiveType("bool")))};
    env.forEachType((name, type) ->
        typeEnvs[0] = typeEnvs[0].bind(name, toTerm(type)));
    final TypeEnv typeEnv = typeEnvs[0];
    final Unifier.Variable v = unifier.variable();
    deduceType(typeEnv, node, v);
    final List<Unifier.TermTerm> termPairs = new ArrayList<>();
    terms.forEach(tv ->
        termPairs.add(new Unifier.TermTerm(tv.term, tv.variable)));
    final Unifier.Substitution substitution = unifier.unify(termPairs);
    return new TypeMap(typeSystem, map, substitution);
  }

  private boolean reg(AstNode node,
      Unifier.Variable variable, Unifier.Term term) {
    Objects.requireNonNull(node);
    Objects.requireNonNull(term);
    map.put(node, term);
    if (variable != null) {
      equiv(term, variable);
    }
    return true;
  }

  private boolean deduceType(TypeEnv env, AstNode node, Unifier.Variable v) {
    final Unifier.Variable v2;
    switch (node.op) {
    case BOOL_LITERAL:
      return reg(node, v, toTerm(PrimitiveType.BOOL));

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

    case LET:
      final Ast.LetExp let = (Ast.LetExp) node;
      final Map<Ast.IdPat, Unifier.Term> termMap = new LinkedHashMap<>();
      deduceDeclType(env, let.decl, termMap);
      deduceType(bindAll(env, termMap), let.e, v);
      return reg(let, null, v);

    case ID:
      final Ast.Id id = (Ast.Id) node;
      return reg(id, v, env.get(id.name));

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

    case FN:
      final Ast.Fn fn = (Ast.Fn) node;
      deduceType(env, fn.match, v);
      return reg(fn, null, v);

    case MATCH:
      final Ast.Match match = (Ast.Match) node;
      return deduceMatchType(env, match, new LinkedHashMap<>(), v);

    case APPLY:
      final Ast.Apply apply = (Ast.Apply) node;
      final Unifier.Variable vFn = unifier.variable();
      final Unifier.Variable vResult = unifier.variable();
      final Unifier.Variable vArg = unifier.variable();
      equiv(unifier.apply(FN_TY_CON, vArg, vResult), vFn);
      deduceType(env, apply.fn, vFn);
      deduceType(env, apply.arg, vArg);
      return reg(apply, null, vResult);

    case PLUS:
    case MINUS:
    case TIMES:
    case DIVIDE:
      return infixOverloaded(env, (Ast.InfixCall) node, v, PrimitiveType.INT);

    default:
      throw new AssertionError("cannot deduce type for " + node.op);
    }
  }

  private boolean deduceMatchType(TypeEnv env, Ast.Match match,
      Map<Ast.IdPat, Unifier.Term> termMap, Unifier.Variable v) {
    final Unifier.Variable vPat = unifier.variable();
    deducePatType(env, match.pat, termMap, vPat);
    TypeEnv env2 = bindAll(env, termMap);
    final Unifier.Variable vResult = unifier.variable();
    deduceType(env2, match.e, vResult);
    return reg(match, v, unifier.apply(FN_TY_CON, vPat, vResult));
  }

  private boolean deduceValBindType(TypeEnv env, Ast.ValBind valBind,
      Map<Ast.IdPat, Unifier.Term> termMap, Unifier.Variable v) {
    final Unifier.Variable vPat = unifier.variable();
    deducePatType(env, valBind.pat, termMap, vPat);
    deduceType(env, valBind.e, vPat);
    return reg(valBind, v, unifier.apply(FN_TY_CON, vPat, vPat));
  }

  private static TypeEnv bindAll(TypeEnv env,
      Map<Ast.IdPat, Unifier.Term> termMap) {
    for (Map.Entry<Ast.IdPat, Unifier.Term> entry : termMap.entrySet()) {
      env = env.bind(entry.getKey().name, entry.getValue());
    }
    return env;
  }

  private boolean deduceDeclType(TypeEnv env, Ast.Decl node,
      Map<Ast.IdPat, Unifier.Term> termMap) {
    switch (node.op) {
    case VAL_DECL:
      final Ast.VarDecl varDecl = (Ast.VarDecl) node;
      for (Ast.ValBind valBind : varDecl.valBinds) {
        deduceValBindType(env, valBind, termMap, unifier.variable());
      }
      map.put(node, toTerm(PrimitiveType.UNIT));
      return true;

    default:
      throw new AssertionError("cannot deduce type for " + node.op);
    }
  }

  /** Derives a type term for a pattern, collecting the names of pattern
   * variables. */
  private boolean deducePatType(TypeEnv env, Ast.Pat pat,
      Map<Ast.IdPat, Unifier.Term> termMap, Unifier.Variable v) {
    switch (pat.op) {
    case WILDCARD_PAT:
      return true;

    case ID_PAT:
      termMap.put((Ast.IdPat) pat, v);
      return reg(pat, null, v);

    case TUPLE_PAT:
      final List<Unifier.Term> typeTerms = new ArrayList<>();
      final Ast.TuplePat tuple = (Ast.TuplePat) pat;
      for (Ast.Pat arg : tuple.args) {
        final Unifier.Variable vArg = unifier.variable();
        deducePatType(env, arg, termMap, vArg);
        typeTerms.add(vArg);
      }
      return reg(pat, v, unifier.apply(TUPLE_TY_CON, typeTerms));

    default:
      throw new AssertionError("cannot deduce type for pattern " + pat.op);
    }
  }

  /** Registers an infix operator whose type is a given type. */
  private boolean infix(TypeEnv env, Ast.InfixCall call, Unifier.Variable v,
      Type type) {
    final Unifier.Term term = toTerm(type);
    call.forEachArg((arg, i) -> deduceType(env, arg, v));
    return reg(call, v, term);
  }

  /** Registers an infix operator whose type is the same as its arguments. */
  private boolean infixOverloaded(TypeEnv env, Ast.InfixCall call,
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
    case FN:
      final FnType fnType = (FnType) type;
      return unifier.apply(FN_TY_CON, toTerm(fnType.paramType),
          toTerm(fnType.resultType));
    case TIMES:
      final TupleType tupleType = (TupleType) type;
      return unifier.apply(FN_TY_CON, tupleType.argTypes.stream()
          .map(this::toTerm).collect(Collectors.toList()));
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
      throw new AssertionError("not found: " + name);
    }

    @Override public TypeEnv bind(String name, Unifier.Term typeTerm) {
      return new BindTypeEnv(name, typeTerm, this);
    }
  }

  /** Type environment. */
  interface TypeEnv {
    Unifier.Term get(String name);
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
      switch (sequence.operator) {
      case "fn":
        assert sequence.terms.size() == 2;
        final Type paramType = sequence.terms.get(0).accept(this);
        final Type resultType = sequence.terms.get(1).accept(this);
        return typeSystem.fnType(paramType, resultType);
      case "*":
        assert sequence.terms.size() >= 2;
        final ImmutableList.Builder<Type> argTypes = ImmutableList.builder();
        for (Unifier.Term term : sequence.terms) {
          argTypes.add(term.accept(this));
        }
        return typeSystem.tupleType(argTypes.build());
      case "bool":
      case "int":
      case "real":
      case "string":
      case "unit":
        final Type type = typeSystem.map.get(sequence.operator);
        if (type == null) {
          throw new AssertionError("unknown type " + type);
        }
        return type;
      default:
        throw new AssertionError("unknown type constructor "
            + sequence.operator);
      }
    }

    public Type visit(Unifier.Variable variable) {
      final Unifier.Term term = substitution.resultMap.get(variable);
      return term.accept(this);
    }
  }

  /** A table that contains all types in use, indexed by their description (e.g.
   * "{@code int -> int}"). */
  public static class TypeSystem {
    final Map<String, Type> map = new HashMap<>();

    public TypeSystem() {
      map.put("int", PrimitiveType.INT);
      map.put("bool", PrimitiveType.BOOL);
      map.put("string", PrimitiveType.STRING);
      map.put("real", PrimitiveType.REAL);
      map.put("unit", PrimitiveType.UNIT);
    }

    /** Creates a primitive type. */
    Type primitiveType(String name) {
      switch (name) {
      case "bool": return PrimitiveType.BOOL;
      case "CHAR": return PrimitiveType.CHAR;
      case "int": return PrimitiveType.INT;
      case "real": return PrimitiveType.REAL;
      case "string": return PrimitiveType.STRING;
      case "unit": return PrimitiveType.UNIT;
      default:
        throw new AssertionError("not a primitive type: " + name);
      }
    }

    /** Creates a function type. */
    Type fnType(Type paramType, Type resultType) {
      final String description =
          unparseList(new StringBuilder(), Op.FN, 0, 0,
              Arrays.asList(paramType, resultType)).toString();
      return map.computeIfAbsent(description,
          d -> new FnType(d, paramType, resultType));
    }

    /** Creates a tuple type. */
    Type tupleType(List<? extends Type> argTypes) {
      final String description =
          unparseList(new StringBuilder(), Op.TIMES, 0, 0, argTypes).toString();
      return map.computeIfAbsent(description, d -> new TupleType(d, argTypes));
    }

    private static StringBuilder unparseList(StringBuilder builder, Op op,
        int left, int right, List<? extends Type> argTypes) {
      for (Ord<? extends Type> argType : Ord.zip(argTypes)) {
        if (argType.i == 0) {
          unparse(builder, argType.e, left, op.left);
        } else {
          builder.append(op.padded);
          if (argType.i < argTypes.size() - 1) {
            unparse(builder, argType.e, op.right, op.left);
          } else {
            unparse(builder, argType.e, op.right, right);
          }
        }
      }
      return builder;
    }

    private static StringBuilder unparse(StringBuilder builder, Type type,
        int left, int right) {
      final Op op = type.op();
      if (left > op.left || op.right < right) {
        return builder.append("(").append(type.description()).append(")");
      } else {
        return builder.append(type.description());
      }
    }
  }

  /** Abstract implementation of Type. */
  abstract static class BaseType implements Type {
    final String description;
    final Op op;

    protected BaseType(Op op, String description) {
      this.op = Objects.requireNonNull(op);
      this.description = Objects.requireNonNull(description);
    }

    public String description() {
      return description;
    }

    public Op op() {
      return op;
    }
  }

  /** The type of a function value. */
  static class FnType extends BaseType {
    public final Type paramType;
    public final Type resultType;

    private FnType(String description, Type paramType, Type resultType) {
      super(Op.FN, description);
      this.paramType = paramType;
      this.resultType = resultType;
    }
  }

  /** The type of a tuple value. */
  static class TupleType extends BaseType {
    public final List<Type> argTypes;

    private TupleType(String description, Iterable<? extends Type> argTypes) {
      super(Op.TUPLE, description);
      this.argTypes = ImmutableList.copyOf(argTypes);
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
  }
}

// End TypeResolver.java
