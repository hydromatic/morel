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

import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;
import net.hydromatic.morel.util.PairList;
import net.hydromatic.morel.util.Unifier;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.transform;
import static net.hydromatic.morel.util.Static.transformEager;

import static java.util.Objects.requireNonNull;

/** The result of type resolution, a map from AST nodes to types. */
public class TypeMap {
  public final TypeSystem typeSystem;
  private final Map<AstNode, Unifier.Term> nodeTypeTerms;
  final Unifier.Substitution substitution;

  /** Map from type variable name to type variable. The ordinal of the variable
   * is the size of the map at the time it is registered.
   *
   * <p>This map is never iterated over, and therefore the deterministic
   * iteration provided by LinkedHashMap is not necessary, and HashMap is
   * sufficient. */
  private final Map<String, TypeVar> typeVars = new HashMap<>();

  TypeMap(TypeSystem typeSystem, Map<AstNode, Unifier.Term> nodeTypeTerms,
      Unifier.Substitution substitution) {
    this.typeSystem = requireNonNull(typeSystem);
    this.nodeTypeTerms = ImmutableMap.copyOf(nodeTypeTerms);
    this.substitution = requireNonNull(substitution.resolve());
  }

  @Override public String toString() {
    final StringBuilder b = new StringBuilder();
    b.append("terms:\n");
    final List<Map.Entry<AstNode, Unifier.Term>> nodeTerms =
        new ArrayList<>(nodeTypeTerms.entrySet());
    nodeTerms.sort(Comparator.comparing(o -> o.getValue().toString()));
    nodeTerms.forEach(pair ->
        b.append(pair.getValue()).append(": ").append(pair.getKey())
            .append('\n'));
    b.append("substitution:\n");
    substitution.accept(b);
    return b.toString();
  }

  Type termToType(Unifier.Term term) {
    return term.accept(new TermToTypeConverter(this));
  }

  /** Returns the type of an AST node. */
  public Type getType(AstNode node) {
    final Unifier.Term term = requireNonNull(nodeTypeTerms.get(node));
    return termToType(term);
  }

  /** Returns an AST node's type, or null if no type is known. */
  public @Nullable Type getTypeOpt(AstNode node) {
    final Unifier.Term term = nodeTypeTerms.get(node);
    return term == null ? null : termToType(term);
  }

  /** Returns whether an AST node's type will be a type variable. */
  public boolean typeIsVariable(AstNode node) {
    final Unifier.Term term = nodeTypeTerms.get(node);
    if (term instanceof Unifier.Variable) {
      final Type type = termToType(term);
      return type instanceof TypeVar
          || type.isProgressive();
    }
    return false;
  }

  /** Returns whether an AST node has a type.
   *
   * <p>If it does not, perhaps it was ignored by the unification algorithm
   * because it is not relevant to the program. */
  public boolean hasType(AstNode node) {
    return nodeTypeTerms.containsKey(node);
  }

  /** Returns the field names if an AST node has a type that is a record or a
   * tuple, otherwise null. */
  @Nullable SortedSet<String> typeFieldNames(AstNode node) {
    // The term might be a sequence or a variable. We only materialize a type
    // if it is a variable. Materializing a type for every sequence allocated
    // lots of temporary type variables, and created a lot of noise in ref logs.
    final Unifier.Term term = nodeTypeTerms.get(node);
    if (term instanceof Unifier.Sequence) {
      final Unifier.Sequence sequence = (Unifier.Sequence) term;
      // E.g. "record:a:b" becomes record type "{a:t0, b:t1}".
      final List<String> fieldList = TypeResolver.fieldList(sequence);
      if (fieldList != null) {
        return ImmutableSortedSet.copyOf(RecordType.ORDERING, fieldList);
      }
    }
    if (term instanceof Unifier.Variable) {
      final Type type = termToType(term);
      if (type instanceof RecordLikeType) {
        return (SortedSet<String>)
            ((RecordLikeType) type).argNameTypes().keySet();
      }
    }
    return null;
  }

  /** Visitor that converts type terms into actual types. */
  private static class TermToTypeConverter
      implements Unifier.TermVisitor<Type> {
    private final TypeMap typeMap;

    TermToTypeConverter(TypeMap typeMap) {
      this.typeMap = typeMap;
    }

    public Type visit(Unifier.Sequence sequence) {
      final Type type;
      switch (sequence.operator) {
      case TypeResolver.FN_TY_CON:
        assert sequence.terms.size() == 2;
        final Type paramType = sequence.terms.get(0).accept(this);
        final Type resultType = sequence.terms.get(1).accept(this);
        return typeMap.typeSystem.fnType(paramType, resultType);

      case TypeResolver.TUPLE_TY_CON:
        assert sequence.terms.size() != 1;
        final List<Type> argTypes =
            transformEager(sequence.terms, term -> term.accept(this));
        return typeMap.typeSystem.tupleType(argTypes);

      case TypeResolver.LIST_TY_CON:
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
        type = typeMap.typeSystem.lookupOpt(sequence.operator);
        if (type != null) {
          if (sequence.terms.isEmpty()) {
            return type;
          }
          final List<Type> types =
              transform(sequence.terms, t -> t.accept(this));
          return typeMap.typeSystem.apply(type, types);
        }
        if (sequence.operator.startsWith(TypeResolver.RECORD_TY_CON)) {
          // E.g. "record:a:b" becomes record type "{a:t0, b:t1}".
          final List<String> argNames = TypeResolver.fieldList(sequence);
          if (argNames != null) {
            final PairList<String, Type> argNameTypes = PairList.of();
            final AtomicBoolean progressive = new AtomicBoolean(false);
            forEach(argNames, sequence.terms, (name, term) -> {
              if (name.equals(TypeResolver.PROGRESSIVE_LABEL)) {
                progressive.set(true);
              } else {
                argNameTypes.add(name, term.accept(this));
              }
            });
            return progressive.get()
                ? typeMap.typeSystem.progressiveRecordType(argNameTypes)
                : typeMap.typeSystem.recordType(argNameTypes);
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
}

// End TypeMap.java
