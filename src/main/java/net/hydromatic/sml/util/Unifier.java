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
package net.hydromatic.sml.util;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/** Given pairs of terms, finds a substitution to minimize those pairs of
 * terms. */
public abstract class Unifier {
  static final Substitution EMPTY = new Substitution(ImmutableMap.of());

  private int varId;
  private final Map<String, Variable> variableMap = new HashMap<>();
  private final Map<String, Atom> atomMap = new HashMap<>();
  private final Map<String, Sequence> sequenceMap = new HashMap<>();

  /** Whether this unifier checks for cycles in substitutions. */
  public boolean occurs() {
    return false;
  }

  /** Creates a sequence, or returns an existing one with the same terms. */
  public Sequence apply(String first, Term... args) {
    final Sequence sequence =
        new Sequence(ImmutableList.<Term>builder().add(atom(first))
            .add(args).build());
    return sequenceMap.computeIfAbsent(sequence.toString(), n -> sequence);
  }

  /** Creates a variable, or returns an existing one with the same name. */
  public Variable variable(String name) {
    return variableMap.computeIfAbsent(name, Variable::new);
  }

  /** Creates a new variable, with a new name. */
  public Variable variable() {
    for (;;) {
      final String name = "T" + varId++;
      if (!variableMap.containsKey(name)) {
        final Variable variable = new Variable(name);
        variableMap.put(name, variable);
        return variable;
      }
    }
  }

  /** Creates an atom, or returns an existing one with the same name. */
  public Atom atom(String name) {
    return atomMap.computeIfAbsent(name, Atom::new);
  }

  /** Creates a substitution.
   *
   * <p>The arguments are alternating variable / term pairs. For example,
   * {@code substitution(a, x, b, y)} becomes [a/X, b/Y]. */
  public Substitution substitution(Term... varTerms) {
    final ImmutableMap.Builder<Variable, Term> mapBuilder =
        ImmutableMap.builder();
    if (varTerms.length % 2 != 0) {
      throw new AssertionError();
    }
    for (int i = 0; i < varTerms.length; i += 2) {
      mapBuilder.put((Variable) varTerms[i + 1], varTerms[i]);
    }
    return new Substitution(mapBuilder.build());
  }

  static Sequence sequenceApply(Map<Variable, Term> substitutions,
      Iterable<Term> terms) {
    final ImmutableList.Builder<Term> newTerms = ImmutableList.builder();
    for (Term term : terms) {
      newTerms.add(term.apply(substitutions));
    }
    return new Sequence(newTerms.build());
  }

  public @Nullable abstract Substitution unify(List<TermTerm> termPairs);

  private static void checkCycles(Map<Variable, Term> map,
      Map<Variable, Variable> active) throws CycleException {
    for (Term term : map.values()) {
      term.checkCycle(map, active);
    }
  }

  /** The results of a successful unification. Gives access to the raw variable
   * mapping that resulted from the algorithm, but can also resolve a variable
   * to the fullest extent possible with the {@link #resolve} method. */
  public static final class Substitution {
    /** The result of the unification algorithm proper. This does not have
     * everything completely resolved: some variable substitutions are required
     * before getting the most atom-y representation. */
    final Map<Variable, Term> resultMap;

    Substitution(Map<Variable, Term> resultMap) {
      this.resultMap =
          ImmutableSortedMap.copyOf(resultMap, Ordering.usingToString());
    }

    @Override public int hashCode() {
      return resultMap.hashCode();
    }

    @Override public boolean equals(Object obj) {
      return this == obj
          || obj instanceof Substitution
          && resultMap.equals(((Substitution) obj).resultMap);
    }

    @Override public String toString() {
      final StringBuilder builder = new StringBuilder("[");
      for (Ord<Map.Entry<Variable, Term>> e : Ord.zip(resultMap.entrySet())) {
        if (e.i > 0) {
          builder.append(", ");
        }
        builder.append(e.e.getValue()).append("/").append(e.e.getKey());
      }
      return builder.append("]").toString();
    }

    Term resolve(Term term) {
      Term previous;
      Term current = term;
      do {
        previous = current;
        current = current.apply(resultMap);
      } while (!current.equals(previous));
      return current;
    }

    private boolean hasCycles(Map<Variable, Term> map) {
      try {
        checkCycles(map, new IdentityHashMap<>());
        return false;
      } catch (CycleException e) {
        return true;
      }
    }

    public Substitution resolve() {
      if (hasCycles(resultMap)) {
        return this;
      }
      final ImmutableMap.Builder<Variable, Term> builder =
          ImmutableMap.builder();
      for (Map.Entry<Variable, Term> entry : resultMap.entrySet()) {
        builder.put(entry.getKey(), resolve(entry.getValue()));
      }
      return new Substitution(builder.build());
    }
  }

  /** Term (variable, symbol or node). */
  public interface Term {
    Term apply(Map<Variable, Term> substitutions);

    boolean contains(Variable variable);

    /** Throws CycleException if expanding this term leads to a cycle. */
    void checkCycle(Map<Variable, Term> map, Map<Variable, Variable> active)
        throws CycleException;
  }

  /** Control flow exception, thrown by {@link Term#checkCycle(Map, Map)} if
   * it finds a cycle in a substitution map. */
  private static class CycleException extends Exception {
  }

  /** A symbol that has no children. */
  public static final class Atom implements Term {
    final String name;
    Atom(String name) {
      this.name = Objects.requireNonNull(name);
    }

    @Override public String toString() {
      return name;
    }

    public Term apply(Map<Variable, Term> substitutions) {
      return this;
    }

    public boolean contains(Variable variable) {
      return false;
    }

    public void checkCycle(Map<Variable, Term> map,
        Map<Variable, Variable> active) {
      // cycle not possible
    }
  }

  /** A variable that represents a symbol or a sequence; unification's
   * task is to find the substitutions for such variables. */
  public static final class Variable implements Term {
    final String name;
    Variable(String name) {
      this.name = Objects.requireNonNull(name);
      Preconditions.checkArgument(!name.equals(name.toLowerCase(Locale.ROOT)));
    }

    @Override public String toString() {
      return name;
    }

    public Term apply(Map<Variable, Term> substitutions) {
      return substitutions.getOrDefault(this, this);
    }

    public boolean contains(Variable variable) {
      return variable == this;
    }

    public void checkCycle(Map<Variable, Term> map,
        Map<Variable, Variable> active) throws CycleException {
      final Term term = map.get(this);
      if (term != null) {
        if (active.put(this, this) != null) {
          throw new CycleException();
        }
        term.checkCycle(map, active);
        active.remove(this);
      }
    }
  }

  /** A pair of terms. */
  public static final class TermTerm {
    final Term left;
    final Term right;

    public TermTerm(Term left, Term right) {
      this.left = Objects.requireNonNull(left);
      this.right = Objects.requireNonNull(right);
    }

    @Override public String toString() {
      return left + " = " + right;
    }
  }

  /** A sequence of terms.
   *
   * <p>A sequence [a b c] is often printed "a(b, c)", as if "a" is the type of
   * node and "b" and "c" are its children. */
  public static final class Sequence implements Term {
    final List<Term> terms;

    Sequence(List<Term> terms) {
      this.terms = ImmutableList.copyOf(terms);
    }

    @Override public int hashCode() {
      return terms.hashCode();
    }

    @Override public boolean equals(Object obj) {
      return this == obj
          || obj instanceof Sequence
          && terms.equals(((Sequence) obj).terms);
    }

    @Override public String toString() {
      if (terms.size() == 1) {
        return terms.get(0).toString();
      }
      final StringBuilder builder = new StringBuilder();
      for (int i = 0; i < terms.size(); i++) {
        Term term = terms.get(i);
        builder.append(term);
        builder.append(i == 0 ? "("
            : i == terms.size() - 1 ? ")"
                : ", ");
      }
      return builder.toString();
    }

    public Term apply(Map<Variable, Term> substitutions) {
      final Sequence sequence = sequenceApply(substitutions, terms);
      if (sequence.equalsShallow(this)) {
        return this;
      }
      return sequence;
    }

    public void checkCycle(Map<Variable, Term> map,
        Map<Variable, Variable> active) throws CycleException {
      for (Term term : terms) {
        term.checkCycle(map, active);
      }
    }

    /** Compares whether two sequences have the same terms.
     * Compares addresses, not contents, to avoid hitting cycles
     * if any of the terms are cyclic (e.g. "X = f(X)"). */
    private boolean equalsShallow(Sequence sequence) {
      return this == sequence || listEqual(terms, sequence.terms);
    }

    private static <E> boolean listEqual(List<E> list0, List<E> list1) {
      if (list0.size() != list1.size()) {
        return false;
      }
      for (int i = 0; i < list0.size(); i++) {
        if (list0.get(i) != list1.get(i)) {
          return false;
        }
      }
      return true;
    }

    public boolean contains(Variable variable) {
      for (Term term : terms) {
        if (term.contains(variable)) {
          return true;
        }
      }
      return false;
    }
  }
}

// End Unifier.java
