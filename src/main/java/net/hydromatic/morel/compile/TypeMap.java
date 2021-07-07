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
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.Unifier;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/** The result of type resolution, a map from AST nodes to types. */
public class TypeMap {
  public final TypeSystem typeSystem;
  private final Map<AstNode, Unifier.Term> nodeTypeTerms;
  final Unifier.Substitution substitution;
  private final Map<String, TypeVar> typeVars = new HashMap<>();

  TypeMap(TypeSystem typeSystem, Map<AstNode, Unifier.Term> nodeTypeTerms,
      Unifier.Substitution substitution) {
    this.typeSystem = Objects.requireNonNull(typeSystem);
    this.nodeTypeTerms = ImmutableMap.copyOf(nodeTypeTerms);
    this.substitution = Objects.requireNonNull(substitution.resolve());
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
    final Unifier.Term term = Objects.requireNonNull(nodeTypeTerms.get(node));
    return termToType(term);
  }

  /** Returns the type of an AST node, or null if no type is known. */
  public @Nullable Type getTypeOpt(AstNode node) {
    final Unifier.Term term = nodeTypeTerms.get(node);
    return term == null ? null : termToType(term);
  }

  /** Returns whether an AST node has a type.
   *
   * <p>If it does not, perhaps it was ignored by the unification algorithm
   * because it is not relevant to the program. */
  public boolean hasType(AstNode node) {
    return nodeTypeTerms.containsKey(node);
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
      case TypeResolver.FN_TY_CON:
        assert sequence.terms.size() == 2;
        final Type paramType = sequence.terms.get(0).accept(this);
        final Type resultType = sequence.terms.get(1).accept(this);
        return typeMap.typeSystem.fnType(paramType, resultType);

      case TypeResolver.TUPLE_TY_CON:
        assert sequence.terms.size() != 1;
        argTypes = ImmutableList.builder();
        for (Unifier.Term term : sequence.terms) {
          argTypes.add(term.accept(this));
        }
        return typeMap.typeSystem.tupleType(argTypes.build());

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
        final Type type = typeMap.typeSystem.lookupOpt(sequence.operator);
        if (type != null) {
          if (sequence.terms.isEmpty()) {
            return type;
          }
          final List<Type> types =
              Lists.transform(sequence.terms, t -> t.accept(this));
          return typeMap.typeSystem.apply(type, types);
        }
        if (sequence.operator.startsWith(TypeResolver.RECORD_TY_CON)) {
          // E.g. "record:a:b" becomes record type "{a:t0, b:t1}".
          final List<String> argNames = TypeResolver.fieldList(sequence);
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
}

// End TypeMap.java
