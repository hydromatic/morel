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

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.util.Pair.allMatch;
import static net.hydromatic.morel.util.Pair.forEachIndexed;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Given pairs of terms, finds a substitution to minimize those pairs of terms.
 */
public abstract class Unifier {
  private final Map<String, Variable> variableMap = new HashMap<>();
  private final Map<String, Sequence> atomMap = new HashMap<>();
  private final Map<String, Sequence> sequenceMap = new HashMap<>();

  /**
   * Assists with the generation of unique names by recording the lowest
   * ordinal, for a given prefix, for which a name has not yet been generated.
   *
   * <p>For example, if we have called {@code name("T")} twice, and thereby
   * generated "T0" and "T1, then the map will contain {@code code("T", 2)},
   * indicating that the next call to {@code name("T")} should generate "T2".
   */
  private final Map<String, AtomicInteger> nameMap = new HashMap<>();

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

  /** Creates a variable, or returns an existing one with the same ordinal. */
  public Variable variable(int ordinal) {
    String name = "T" + ordinal;
    return variableMap.computeIfAbsent(name, name2 -> new Variable(ordinal));
  }

  /** Creates a new variable, with a new name. */
  public Variable variable() {
    return newName(
        "T",
        (name, ordinal) -> {
          final Variable variable = new Variable(name, ordinal);
          variableMap.put(variable.name, variable);
          return variable;
        });
  }

  /** Creates an atom, or returns an existing one with the same name. */
  public Term atom(String name) {
    return atomMap.computeIfAbsent(name, Sequence::new);
  }

  /**
   * Creates a constraint arising from a call to an overloaded function.
   *
   * <p>Consider a call to an overloaded function with two overloads {@code c ->
   * d} and {@code e -> f}. If the argument ({@code a}) unifies to {@code c}
   * then the result ({@code b}) is {@code d}; if the argument unifies to {@code
   * e} then the result is {@code f}.
   *
   * <p>This is created with the following call:
   *
   * <pre>{@code
   * Constraint constraint =
   *   unifier.constraint(a, b, PairList.of(c, d, e, f));
   * }</pre>
   */
  public Constraint constraint(
      Unifier.Variable arg,
      Unifier.Variable result,
      PairList<Unifier.Term, Unifier.Term> argResults) {
    PairList<Term, Constraint.Action> termActions = PairList.of();
    argResults.forEach(
        (arg0, result0) ->
            termActions.add(
                arg0,
                new Constraint.Action() {
                  @Override
                  public void accept(
                      Term actualArg,
                      Term term,
                      BiConsumer<Term, Term> consumer) {
                    consumer.accept(actualArg, term);
                    consumer.accept(result, result0);
                  }

                  @Override
                  public String toString() {
                    return "equiv(" + result + ", " + result0 + ")";
                  }
                }));
    return constraint(arg, termActions);
  }

  /**
   * Creates a Constraint.
   *
   * <p>The following code creates a constraint where {@code a} is allowed to be
   * either {@code term1} or {@code term2}. When the unifier has narrowed down
   * the options to just {@code term2}, it will call {@code action2} with a
   * consumer that accepts a pair of {@link Term} arguments.
   *
   * <pre>{@code
   * Constraint constraint =
   *   unifier.constraint(a, PairList.of(term1, action1, term2, action2));
   * }</pre>
   */
  public Constraint constraint(
      Variable arg, PairList<Term, Constraint.Action> termActions) {
    return new Constraint(arg, termActions);
  }

  /** Creates an atom with a unique name. */
  public Term atomUnique(String prefix) {
    return newName(
        prefix,
        (name, ordinal) -> {
          final Sequence sequence = new Sequence(name);
          atomMap.put(name, sequence);
          return sequence;
        });
  }

  /** Finds an ordinal that makes a name unique among atoms and variables. */
  private <T> T newName(
      String prefix, BiFunction<String, Integer, T> consumer) {
    final AtomicInteger sequence =
        nameMap.computeIfAbsent(prefix, prefix2 -> new AtomicInteger());
    for (; ; ) {
      final int ordinal = sequence.getAndIncrement();
      final String name = prefix + ordinal;

      // Make sure that there is no variable or atom with the same name. This
      // might happen if they have been created directly, without using this
      // 'newName' method. This time we had to go around the loop a few times,
      // but because we called sequence.getAndIncrement(), the next call to
      // 'newName' with the same prefix will be more efficient.
      if (!variableMap.containsKey(name) && !atomMap.containsKey(name)) {
        return consumer.apply(name, ordinal);
      }
    }
  }

  /**
   * Creates a substitution.
   *
   * <p>The arguments are alternating variable / term pairs. For example, {@code
   * substitution(a, x, b, y)} becomes [a/X, b/Y].
   */
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

  static Sequence sequenceApply(
      String operator,
      Map<Variable, Term> substitutions,
      Iterable<Term> terms) {
    final ImmutableList.Builder<Term> newTerms = ImmutableList.builder();
    for (Term term : terms) {
      newTerms.add(term.apply(substitutions));
    }
    return new Sequence(operator, newTerms.build());
  }

  public abstract Result unify(
      List<TermTerm> termPairs,
      Map<Variable, Action> termActions,
      List<Constraint> constraints,
      Tracer tracer);

  private static void checkCycles(
      Map<Variable, Term> map, Map<Variable, Variable> active)
      throws CycleException {
    for (Term term : map.values()) {
      term.checkCycle(map, active);
    }
  }

  protected Failure failure(String reason) {
    return () -> reason;
  }

  /** Called by the unifier when a Term's type becomes known. */
  @FunctionalInterface
  public interface Action {
    void accept(
        Variable variable,
        Term term,
        Substitution substitution,
        BiConsumer<Term, Term> termPairs);
  }

  /**
   * Result of attempting unification. A success is {@link Substitution}, but
   * there are other failures.
   */
  public interface Result {}

  /** Result indicating that unification was not possible. */
  public interface Failure extends Result {
    String reason();
  }

  /**
   * The results of a successful unification. Gives access to the raw variable
   * mapping that resulted from the algorithm, but can also resolve a variable
   * to the fullest extent possible with the {@link #resolve} method.
   */
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

  /**
   * Map from variables to terms.
   *
   * <p>Quicker to create than its subclass {@link SubstitutionResult} because
   * the map is mutable and not sorted.
   */
  public static class Substitution {
    /**
     * The result of the unification algorithm proper. This does not have
     * everything completely resolved: some variable substitutions are required
     * before getting the most atom-y representation.
     */
    public final Map<Variable, Term> resultMap;

    Substitution(Map<Variable, Term> resultMap) {
      this.resultMap = resultMap;
    }

    @Override
    public int hashCode() {
      return resultMap.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return this == obj
          || obj instanceof Substitution
              && resultMap.equals(((Substitution) obj).resultMap);
    }

    @Override
    public String toString() {
      return accept(new StringBuilder()).toString();
    }

    public StringBuilder accept(StringBuilder buf) {
      buf.append("[");
      forEachIndexed(
          resultMap,
          (i, variable, term) ->
              buf.append(i > 0 ? ", " : "")
                  .append(term)
                  .append("/")
                  .append(variable));
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
    /** Applies a substitution to this term. */
    Term apply(Map<Variable, Term> substitutions);

    /** Applies a single-variable substitution to this term. */
    Term apply1(Variable variable, Term term);

    /** Returns whether this term references a given variable. */
    boolean contains(Variable variable);

    /** Throws CycleException if expanding this term leads to a cycle. */
    void checkCycle(Map<Variable, Term> map, Map<Variable, Variable> active)
        throws CycleException;

    /** Accepts a visitor. */
    <R> R accept(TermVisitor<R> visitor);

    /**
     * Returns whether this term could possibly unify with another term.
     *
     * <p>Returns true if {@code this} or {@code term} is a variable, or if they
     * are sequences with the same operator and number of terms, and if all
     * pairs of those terms could unify.
     */
    default boolean couldUnifyWith(Term term) {
      return true;
    }
  }

  /**
   * Visitor for terms.
   *
   * @param <R> return type
   * @see Term#accept(TermVisitor)
   */
  public interface TermVisitor<R> {
    R visit(Sequence sequence);

    R visit(Variable variable);
  }

  /**
   * Control flow exception, thrown by {@link Term#checkCycle(Map, Map)} if it
   * finds a cycle in a substitution map.
   */
  public static class CycleException extends Exception {}

  /**
   * A variable that represents a symbol or a sequence; unification's task is to
   * find the substitutions for such variables.
   */
  public static final class Variable implements Term, Comparable<Variable> {
    final String name;
    final int ordinal;

    Variable(String name, int ordinal) {
      this.name = requireNonNull(name);
      this.ordinal = ordinal;
      checkArgument(
          name.equals(name.toUpperCase(Locale.ROOT)),
          "must be upper case: %s",
          name);
    }

    /**
     * Creates a variable with a name. The name must not be like "T0" or "T123",
     * because those are the names created for variables with ordinals.
     */
    Variable(String name) {
      this(name, -1);
      checkArgument(!name.matches("T[0-9]+"), name);
    }

    /**
     * Creates a variable with an ordinal. If the ordinal is "34" the name will
     * be "T34". The ordinal must be non-negative.
     */
    Variable(int ordinal) {
      this("T" + ordinal, ordinal);
      checkArgument(ordinal >= 0, ordinal);
    }

    @Override
    public String toString() {
      return name;
    }

    @Override
    public int hashCode() {
      return name.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || obj instanceof Variable
              && this.ordinal == ((Variable) obj).ordinal
              && this.name.equals(((Variable) obj).name);
    }

    @Override
    public int compareTo(Variable o) {
      int c = Integer.compare(ordinal, o.ordinal);
      if (c == 0) {
        c = name.compareTo(o.name);
      }
      return c;
    }

    @Override
    public Term apply(Map<Variable, Term> substitutions) {
      return substitutions.getOrDefault(this, this);
    }

    @Override
    public Term apply1(Variable variable, Term term) {
      return variable == this ? term : this;
    }

    @Override
    public boolean contains(Variable variable) {
      return variable == this;
    }

    @Override
    public void checkCycle(
        Map<Variable, Term> map, Map<Variable, Variable> active)
        throws CycleException {
      final Term term = map.get(this);
      if (term != null) {
        if (active.put(this, this) != null) {
          throw new CycleException();
        }
        term.checkCycle(map, active);
        active.remove(this);
      }
    }

    @Override
    public <R> R accept(TermVisitor<R> visitor) {
      return visitor.visit(this);
    }
  }

  /** A pair of terms. */
  public static final class TermTerm {
    final Term left;
    final Term right;

    public TermTerm(Term left, Term right) {
      this.left = requireNonNull(left);
      this.right = requireNonNull(right);
    }

    @Override
    public String toString() {
      return left + " = " + right;
    }
  }

  /**
   * A sequence of terms.
   *
   * <p>A sequence [a b c] is often printed "a(b, c)", as if "a" is the type of
   * node and "b" and "c" are its children.
   */
  public static final class Sequence implements Term {
    public final String operator;
    public final List<Term> terms;

    Sequence(String operator, List<Term> terms) {
      this.operator = requireNonNull(operator);
      this.terms = ImmutableList.copyOf(terms);
    }

    Sequence(String operator) {
      this(operator, ImmutableList.of());
    }

    @Override
    public int hashCode() {
      return Objects.hash(operator, terms);
    }

    @Override
    public boolean equals(Object obj) {
      return this == obj
          || obj instanceof Sequence
              && operator.equals(((Sequence) obj).operator)
              && terms.equals(((Sequence) obj).terms);
    }

    @Override
    public boolean couldUnifyWith(Term term) {
      if (term instanceof Variable) {
        return true;
      }
      final Sequence sequence = (Sequence) term;
      if (!operator.equals(((Sequence) term).operator)
          || terms.size() != sequence.terms.size()) {
        return false;
      }
      return allMatch(terms, sequence.terms, Term::couldUnifyWith);
    }

    @Override
    public String toString() {
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
      if (terms.isEmpty()) {
        return this;
      }
      final List<Term> newTerms = new ArrayList<>(terms.size());
      for (Term term : terms) {
        newTerms.add(term.apply(substitutions));
      }
      if (listEqual(terms, newTerms)) {
        return this;
      }
      return new Sequence(operator, newTerms);
    }

    @Override
    public Term apply1(Variable variable, Term term) {
      if (terms.isEmpty()) {
        return this;
      }
      if (!contains(variable)) {
        return this;
      }
      final ImmutableList.Builder<Term> newTerms = ImmutableList.builder();
      for (Term term1 : terms) {
        newTerms.add(term1.apply1(variable, term));
      }
      return new Sequence(operator, newTerms.build());
    }

    public void checkCycle(
        Map<Variable, Term> map, Map<Variable, Variable> active)
        throws CycleException {
      for (Term term : terms) {
        term.checkCycle(map, active);
      }
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

  /**
   * Constraint arising from a call to an overloaded function.
   *
   * <p>Consider a call to an overloaded function with two overloads {@code c ->
   * d} and {@code e -> f}. If the argument ({@code a}) unifies to {@code c}
   * then the result ({@code b}) is {@code d}; if the argument unifies to {@code
   * e} then the result is {@code f}.
   *
   * <p>This is represented as the following constraint:
   *
   * <pre>{@code
   * {arg: a, result: b, argResults [{c, d}, {e, f}]}
   * }</pre>
   */
  public static final class Constraint {
    public final Variable arg;
    public final PairList<Term, Action> termActions;

    /** Creates a Constraint. */
    Constraint(Variable arg, PairList<Term, Action> termActions) {
      this.arg = requireNonNull(arg);
      this.termActions = termActions.immutable();
      checkArgument(!termActions.isEmpty());
    }

    /** Returns an {@link Action} that marks two terms as equivalent. */
    public static Action equiv(Term term1, Term term2) {
      return new EquivAction(term1, term2);
    }

    @Override
    public String toString() {
      return format("{constraint %s %s}", arg, termActions);
    }

    /** Called when a constraint is narrowed down to one possibility. */
    public interface Action {
      void accept(Term actualArg, Term term, BiConsumer<Term, Term> consumer);
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

  /** An action that, when executed, marks two terms as equivalent. */
  private static class EquivAction implements Constraint.Action {
    private final Term term1;
    private final Term term2;

    EquivAction(Term term1, Term term2) {
      this.term1 = term1;
      this.term2 = term2;
    }

    @Override
    public String toString() {
      return "equiv(" + term1 + ", " + term2 + ")";
    }

    @Override
    public void accept(
        Term actualArg, Term actualTerm, BiConsumer<Term, Term> consumer) {
      consumer.accept(term1, term2);
    }
  }
}

// End Unifier.java
