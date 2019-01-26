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

import com.google.common.collect.ImmutableMap;

import net.hydromatic.sml.ast.Ast;
import net.hydromatic.sml.ast.AstNode;
import net.hydromatic.sml.eval.Environment;
import net.hydromatic.sml.type.PrimitiveType;
import net.hydromatic.sml.type.Type;
import net.hydromatic.sml.util.MartelliUnifier;
import net.hydromatic.sml.util.Unifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves the type of an expression. */
public class TypeResolver {
  final Unifier unifier = new MartelliUnifier();
  final List<TermVariable> terms = new ArrayList<>();
  final Map<AstNode, Unifier.Term> map = new HashMap<>();

  public static TypeMap deduceType(Environment env,
      AstNode node, TypeSystem typeSystem) {
    final TypeResolver typeResolver = new TypeResolver();
    TypeEnv[] typeEnvs = {EmptyTypeEnv.INSTANCE
        .bind("true", typeResolver.atom(typeSystem.primitiveType("bool")))
        .bind("false", typeResolver.atom(typeSystem.primitiveType("bool")))};
    env.forEachType((name, type) ->
        typeEnvs[0] = typeEnvs[0].bind(name, typeResolver.atom(type)));
    final TypeEnv typeEnv = typeEnvs[0];
    final Unifier.Term typeTerm = typeResolver.deduceType(typeEnv, node);
    final Unifier.Substitution substitution;
    final List<Unifier.TermTerm> termPairs = new ArrayList<>();
    typeResolver.terms.forEach(tv ->
        termPairs.add(new Unifier.TermTerm(tv.term, tv.variable)));
    substitution = typeResolver.unifier.unify(termPairs);
    return new TypeMap(typeSystem, typeResolver.map, substitution);
  }

  private Unifier.Term deduceType(TypeEnv env, AstNode node) {
    final Unifier.Term term = deduceType2(env, node);
    map.put(node, term);
    return term;
  }

  private Unifier.Term deduceType2(TypeEnv env, AstNode node) {
    final Unifier.Variable v;
    final Ast.Exp exp;
    switch (node.op) {
    case INT_LITERAL:
      return atom(PrimitiveType.INT);

    case REAL_LITERAL:
      return atom(PrimitiveType.REAL);

    case STRING_LITERAL:
      return atom(PrimitiveType.STRING);

    case BOOL_LITERAL:
      return atom(PrimitiveType.BOOL);

    case ANDALSO:
    case ORELSE:
      return infix(env, (Ast.InfixCall) node, PrimitiveType.BOOL);

    case VAL_DECL:
      final Ast.VarDecl varDecl = (Ast.VarDecl) node;
      for (Ast.Exp e : varDecl.patExps.values()) {
        final Unifier.Term type = deduceType(env, e);
      }
      return atom(PrimitiveType.UNIT);

    case LET:
      final Ast.LetExp let = (Ast.LetExp) node;
      final Unifier.Term declType = deduceType(env, let.decl);
      assert declType == atom(PrimitiveType.UNIT);
      TypeEnv env3 = env;
      for (Map.Entry<Ast.Pat, Ast.Exp> e : let.decl.patExps.entrySet()) {
        final Ast.Pat pat = e.getKey();
        final Ast.Exp exp2 = e.getValue();
        final Unifier.Term type = map.get(exp2);
        env3 = env3.bind(((Ast.NamedPat) pat).name, type);
      }
      return deduceType(env3, let.e);

    case ID:
      final Ast.Id id = (Ast.Id) node;
      return env.get(id.name);

    case IF:
      // TODO: check that condition has type boolean
      // TODO: check that ifTrue has same type as ifFalse
      final Ast.If if_ = (Ast.If) node;
      final Unifier.Term trueTerm = deduceType(env, if_.ifTrue);
      final Unifier.Term falseTerm = deduceType(env, if_.ifFalse);
      v = unifier.variable();
      equiv(trueTerm, v);
      equiv(falseTerm, v);
      return v;

    case FN:
      final Ast.Fn fn = (Ast.Fn) node;
      return deduceType(env, fn.match);

    case MATCH:
      final Ast.Match match = (Ast.Match) node;
      final String parameter = ((Ast.NamedPat) match.pat).name;
      final Unifier.Variable parameterType = unifier.variable();
      final TypeEnv env2 = env.bind(parameter, parameterType);
      final Unifier.Term expType = deduceType(env2, match.e);
      return unifier.apply("fn", parameterType, expType);

    case APPLY:
      final Ast.Apply apply = (Ast.Apply) node;
      final Unifier.Term fnType = deduceType(env, apply.fn);
      final Unifier.Term argType = deduceType(env, apply.arg);
      return argType; // TODO:

    case PLUS:
    case MINUS:
    case TIMES:
    case DIVIDE:
      return infixOverloaded(env, (Ast.InfixCall) node, PrimitiveType.INT);

    default:
      throw new AssertionError("cannot deduce type for " + node.op);
    }
  }

  /** Registers an infix operator whose type is a given type. */
  private Unifier.Term infix(TypeEnv env, Ast.InfixCall call, Type type) {
    final Unifier.Atom atom = atom(type);
    Unifier.Variable v = unifier.variable();
    equiv(atom, v);
    call.forEachArg(arg -> equiv(deduceType(env, arg), v));
    return atom;
  }

  /** Registers an infix operator whose type is the same as its arguments. */
  private Unifier.Term infixOverloaded(TypeEnv env, Ast.InfixCall call,
      Type defaultType) {
    final List<Unifier.Term> argTypes = new ArrayList<>();
    call.forEachArg(arg -> argTypes.add(deduceType(env, arg)));
    final Unifier.Term resultType;
    if (argTypes.get(0) instanceof Unifier.Atom) {
      resultType = argTypes.get(0);
    } else if (argTypes.get(1) instanceof Unifier.Atom) {
      resultType = argTypes.get(1);
    } else {
      resultType = atom(defaultType);
    }
    for (Unifier.Term argType : argTypes) {
      if (argType instanceof Unifier.Variable) {
        equiv(resultType, (Unifier.Variable) argType);
      }
    }
    return resultType;
  }

  private void equiv(Unifier.Term term, Unifier.Variable atom) {
    terms.add(new TermVariable(term, atom));
  }

  private Unifier.Atom atom(Type type) {
    return unifier.atom(type.description());
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

    public Type visit(Unifier.Atom atom) {
      final Type type = typeSystem.map.get(atom.name);
      if (type == null) {
        throw new AssertionError("unknown type " + type);
      }
      return type;
    }

    public Type visit(Unifier.Sequence sequence) {
      final Unifier.Atom atom = (Unifier.Atom) sequence.terms.get(0);
      switch (atom.name) {
      case "fn":
        assert sequence.terms.size() == 3;
        final Type paramType = sequence.terms.get(1).accept(this);
        final Type resultType = sequence.terms.get(2).accept(this);
        return typeSystem.fnType(paramType, resultType);
      default:
        throw new AssertionError("unknown type constructor " + atom);
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
      case "unit": return PrimitiveType.INT;
      default:
        throw new AssertionError("not a primitive type: " + name);
      }
    }

    /** Creates a function type. */
    Type fnType(Type paramType, Type resultType) {
      final String description =
          paramType.description() + " -> " + resultType.description();
      return map.computeIfAbsent(description,
          d -> new FnType(d, paramType, resultType));
    }
  }

  /** The type of a function value. */
  static class FnType implements Type {
    public final String description;
    public final Type paramType;
    public final Type resultType;

    private FnType(String description, Type paramType, Type resultType) {
      this.description = description;
      this.paramType = paramType;
      this.resultType = resultType;
    }

    public String description() {
      return description;
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
