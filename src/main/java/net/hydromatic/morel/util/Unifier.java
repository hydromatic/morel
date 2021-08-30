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
package net.hydromatic.morel.util;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Given pairs of terms, finds a substitution to minimize those pairs of
 * terms. */
public abstract class Unifier {
  private int varId;
  private final Map<String, Variable> variableMap = new HashMap<>();
  private final Map<String, Sequence> atomMap = new HashMap<>();
  private final Map<String, Sequence> sequenceMap = new HashMap<>();

  /** Whether this unifier checks for cycles in substitutions. */
  public boolean occurs() {
    return false;
  }

  /** Creates a sequence, or returns an existing one with the same terms. */
  public Sequence apply(String operator, Term... args) {
    return apply(operator, ImmutableList.copyOf(args));
  }

  /** Creates a sequence, or returns an existing one with the same terms. */
  public Sequence apply(String operator, Iterable<? extends Term> args) {
    final Sequence sequence =
        new Sequence(operator, ImmutableList.copyOf(args));
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
  public Term atom(String name) {
    return atomMap.computeIfAbsent(name, Sequence::new);
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

  static Sequence sequenceApply(String operator,
      Map<Variable, Term> substitutions, Iterable<Term> terms) {
    final ImmutableList.Builder<Term> newTerms = ImmutableList.builder();
    for (Term term : terms) {
      newTerms.add(term.apply(substitutions));
    }
    return new Sequence(operator, newTerms.build());
  }

  public @Nonnull abstract Result unify(List<TermTerm> termPairs,
      Map<Variable, Action> termActions, Tracer tracer);

  private static void checkCycles(Map<Variable, Term> map,
      Map<Variable, Variable> active) throws CycleException {
    for (Term term : map.values()) {
      term.checkCycle(map, active);
    }
  }

  protected Failure failure(String reason) {
    return new Failure() {
      @Override public String toString() {
        return reason;
      }
    };
  }

  /** Called by the unifier when a Term's type becomes known. */
  @FunctionalInterface
  public interface Action {
    void accept(Variable variable, Term term, Substitution substitution,
        List<TermTerm> termPairs);
  }

  /** Result of attempting unification. A success is {@link Substitution},
   * but there are other failures. */
  public interface Result {
  }

  /** Result indicating that unification was not possible. */
  public static class Failure implements Result {
  }

  /** The results of a successful unification. Gives access to the raw variable
   * mapping that resulted from the algorithm, but can also resolve a variable
   * to the fullest extent possible with the {@link #resolve} method. */
  public static final class SubstitutionResult extends Substitution
      implements Result {
    private SubstitutionResult(Map<Variable, Term> resultMap) {
      super(ImmutableSortedMap.copyOf(resultMap));
    }

    /** Empty substitution result. */
    public static final SubstitutionResult EMPTY =
        create(ImmutableSortedMap.of());

    /** Creates a substitution result from a map. */
    public static SubstitutionResult create(Map<Variable, Term> resultMap) {
      return new SubstitutionResult(ImmutableSortedMap.copyOf(resultMap));
    }

    /** Creates a substitution result with one (variable, term) entry. */
    public static SubstitutionResult create(Variable v, Term t) {
      return new SubstitutionResult(ImmutableSortedMap.of(v, t));
    }
  }

  /** Map from variables to terms.
   *
   * <p>Quicker to create than its sub-class {@link SubstitutionResult}
   * because the map is mutable and not sorted. */
  public static class Substitution {
    /** The result of the unification algorithm proper. This does not have
     * everything completely resolved: some variable substitutions are required
     * before getting the most atom-y representation. */
    public final Map<Variable, Term> resultMap;

    Substitution(Map<Variable, Term> resultMap) {
      this.resultMap = resultMap;
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
      return accept(new StringBuilder()).toString();
    }

    public StringBuilder accept(StringBuilder buf) {
      buf.append("[");
      Pair.forEachIndexed(resultMap, (i, variable, term) ->
          buf.append(i > 0 ? ", " : "").append(term)
              .append("/").append(variable));
      return buf.append("]");
    }

    public Term resolve(Term term) {
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
      resultMap.forEach((key, value) -> builder.put(key, resolve(value)));
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

    <R> R accept(TermVisitor<R> visitor);
  }

  /** Visitor for terms.
   *
   * @param <R> return type
   *
   * @see Term#accept(TermVisitor) */
  public interface TermVisitor<R> {
    R visit(Sequence sequence);
    R visit(Variable variable);
  }

  /** Control flow exception, thrown by {@link Term#checkCycle(Map, Map)} if
   * it finds a cycle in a substitution map. */
  private static class CycleException extends Exception {
  }

  /** A variable that represents a symbol or a sequence; unification's
   * task is to find the substitutions for such variables. */
  public static final class Variable implements Term, Comparable<Variable> {
    final String name;

    Variable(String name) {
      this.name = Objects.requireNonNull(name);
      Preconditions.checkArgument(name.equals(name.toUpperCase(Locale.ROOT)),
          "must be upper case: %s", name);
    }

    @Override public String toString() {
      return name;
    }

    @Override public int compareTo(Variable o) {
      final int i = ordinal();
      final int i2 = o.ordinal();
      int c = Integer.compare(i, i2);
      if (c == 0) {
        c = name.compareTo(o.name);
      }
      return c;
    }

    /** If the name is "T3", returns 3. If the name is not of the form
     * "T{integer}" returns -1. */
    private int ordinal() {
      try {
        return Integer.parseInt(name.substring(1));
      } catch (NumberFormatException e) {
        return -1;
      }
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

    public <R> R accept(TermVisitor<R> visitor) {
      return visitor.visit(this);
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
    public final String operator;
    public final List<Term> terms;

    Sequence(String operator, List<Term> terms) {
      this.operator = Objects.requireNonNull(operator);
      this.terms = ImmutableList.copyOf(terms);
    }

    Sequence(String operator) {
      this(operator, ImmutableList.of());
    }

    @Override public int hashCode() {
      return Objects.hash(operator, terms);
    }

    @Override public boolean equals(Object obj) {
      return this == obj
          || obj instanceof Sequence
          && operator.equals(((Sequence) obj).operator)
          && terms.equals(((Sequence) obj).terms);
    }

    @Override public String toString() {
      if (terms.isEmpty()) {
        return operator;
      }
      final StringBuilder builder = new StringBuilder(operator).append('(');
      for (int i = 0; i < terms.size(); i++) {
        Term term = terms.get(i);
        if (i > 0) {
          builder.append(", ");
        }
        builder.append(term);
      }
      return builder.append(')').toString();
    }

    public Term apply(Map<Variable, Term> substitutions) {
      final Sequence sequence = sequenceApply(operator, substitutions, terms);
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

    public <R> R accept(TermVisitor<R> visitor) {
      return visitor.visit(this);
    }
  }

  /** Called on various events during unification. */
  public interface Tracer {
    void onDelete(Term left, Term right);
    void onConflict(Sequence left, Sequence right);
    void onSequence(Sequence left, Sequence right);
    void onSwap(Term left, Term right);
    void onCycle(Variable variable, Term term);
    void onVariable(Variable variable, Term term);
    void onSubstitute(Term left, Term right, Term left2, Term right2);
  }
}

// End Unifier.java
