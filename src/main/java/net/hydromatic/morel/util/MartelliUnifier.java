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

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Unification algorithm due to Martelli, Montanari (1976) and Paterson, Wegman
 * (1978).
 */
public class MartelliUnifier extends Unifier {
  @Override
  public Result unify(
      List<TermTerm> termPairs,
      Map<Variable, Action> termActions,
      List<Constraint> constraints,
      Tracer tracer) {
    final long start = System.nanoTime();

    // delete: G u { t = t }
    //   => G

    // decompose: G u { f(s0, ..., sk) = f(t0, ..., tk) }
    //   => G u {s0 = t0, ..., sk = tk}

    // conflict: G u { f(s0, ..., sk) = g(t0, ..., tm) }
    //   => fail
    // if f <> g or k <> m

    // swap: G u { f(s0, ..., sk) = x }
    //  => G u { x = f(s0, ..., sk) }

    // eliminate: G u { x = t }
    //  => G { x |-> t } u { x = t }
    // if x not in vars(t) and x in vars(G)

    // check: G u { x = f(s0, ..., sk)}
    //  => fail
    // if x in vars(f(s0, ..., sk))

    final Map<Variable, Term> result = new LinkedHashMap<>();
    // Alias terms that met a different type, and what they expand to.
    final Map<Term, Term> weakened = new LinkedHashMap<>();
    final Work work = new Work(tracer, termPairs, constraints, result);
    for (int iteration = 0; ; iteration++) {
      // delete
      if (!work.deleteQueue.isEmpty()) {
        TermTerm pair = work.deleteQueue.remove(0);
        tracer.onDelete(pair.left, pair.right);
        continue;
      }

      if (!work.seqSeqQueue.isEmpty()) {
        TermTerm pair = work.seqSeqQueue.remove(0);
        final Sequence left = (Sequence) pair.left;
        final Sequence right = (Sequence) pair.right;

        if (!left.operator.equals(right.operator)
            || left.terms.size() != right.terms.size()) {
          // Head-reduce. A type alias is a term whose first argument is its
          // expanded body; if it meets a term with a different operator, the
          // aliases are expanded and the pair retried, so that an alias
          // unifies with the type it abbreviates.
          final Term left2 = headReduce(left);
          final Term right2 = headReduce(right);
          if (left2 != left || right2 != right) {
            // An alias met a different type, so it is only as strong as what
            // it abbreviates: remember to weaken it in the substitution.
            if (left2 != left) {
              weakened.put(left, left2);
            }
            if (right2 != right) {
              weakened.put(right, right2);
            }
            if (conflictsAtHead(left2, right2)) {
              // The expansions still disagree, so report the aliases that were
              // written rather than what they expand to.
              tracer.onConflict(left, right);
              return failure(
                  "conflict: " + render(left) + " vs " + render(right));
            }
            work.add(left2, right2);
            continue;
          }
          tracer.onConflict(left, right);
          return failure("conflict: " + render(left) + " vs " + render(right));
        }

        // Two collections that differ only in orderedness (list vs bag) share
        // the same operator, so they would otherwise decompose and report a
        // conflict on the internal orderedness atom. Report the parent terms
        // instead -- and before their elements are unified -- so the message
        // reads "T list vs T bag".
        if (isOrderednessConflict(left, right)) {
          tracer.onConflict(left, right);
          return failure("conflict: " + render(left) + " vs " + render(right));
        }

        // decompose
        tracer.onSequence(left, right);
        for (int j = 0; j < left.terms.size(); j++) {
          work.add(left.terms.get(j), right.terms.get(j));
        }
        continue;
      }

      if (!work.varAnyQueue.isEmpty()) {
        TermTerm pair = work.varAnyQueue.remove(0);
        final Variable variable = (Variable) pair.left;
        Term term = pair.right;

        // Occurs check
        if (term.contains(variable)) {
          tracer.onCycle(variable, term);
          return failure("cycle: variable " + variable + " in " + term);
        }

        // If 'term' is already in the table, map 'variable' to its ultimate
        // target.
        while (term instanceof Variable) {
          final Term term2 = result.get(term);
          if (term2 == null) {
            break;
          }
          term = term2;
        }

        if (term.equals(variable)) {
          // We already knew that 'pair.left' and 'pair.right' were equivalent.
          continue;
        }

        tracer.onVariable(variable, term);
        final Term priorTerm = result.put(variable, term);
        if (priorTerm != null && !priorTerm.equals(term)) {
          work.add(priorTerm, term);
        }
        if (!termActions.isEmpty()) {
          final Set<Variable> set = new HashSet<>();
          act(variable, term, work, new Substitution(result), termActions, set);
          checkArgument(set.isEmpty(), "Working set not empty: %s", set);
        }
        Failure failure = work.substituteList(variable, term);
        if (failure != null) {
          return failure;
        }
        continue;
      }

      final long duration = System.nanoTime() - start;
      if (false) {
        System.out.printf(
            "Term count %,d iterations %,d duration %,d nanos"
                + " (%,d nanos per iteration)%n",
            termPairs.size(), iteration, duration, duration / (iteration + 1));
      }
      // Any overload constraint that still has more than one candidate never
      // had its argument type pinned down; surface it so that it can become a
      // predicate of a qualified type.
      final List<Constraint> residualConstraints = new ArrayList<>();
      for (MutableConstraint constraint : work.constraintQueue) {
        if (constraint.constraint.name != null
            && constraint.termActions.size() > 1) {
          residualConstraints.add(constraint.constraint);
        }
      }
      if (!weakened.isEmpty()) {
        result.replaceAll((v, t) -> weaken(t, weakened));
      }
      return SubstitutionResult.create(result, residualConstraints);
    }
  }

  // Collection terms are represented as "$collection(element, orderedness)",
  // where orderedness is the atom "ordered" (a list) or "unordered" (a bag).
  // These constants mirror those in TypeResolver, and let error messages render
  // a collection as "element list"/"element bag" instead of leaking internals.
  private static final String COLLECTION_OP = "$collection";
  private static final String ORDERED_OP = "ordered";
  private static final String UNORDERED_OP = "unordered";

  /**
   * Whether {@code left} and {@code right} are collection terms whose
   * orderedness atoms are both concrete and differ (i.e. one is a list and the
   * other a bag).
   */
  private static boolean isOrderednessConflict(Sequence left, Sequence right) {
    if (!left.operator.equals(COLLECTION_OP) || left.terms.size() != 2) {
      return false;
    }
    final String o1 = orderednessAtom(left.terms.get(1));
    final String o2 = orderednessAtom(right.terms.get(1));
    return o1 != null && o2 != null && !o1.equals(o2);
  }

  private static @Nullable String orderednessAtom(Term term) {
    if (term instanceof Sequence) {
      final String op = ((Sequence) term).operator;
      if (op.equals(ORDERED_OP) || op.equals(UNORDERED_OP)) {
        return op;
      }
    }
    return null;
  }

  /**
   * Renders a term for an error message, in the syntax a type is written in
   * rather than the unifier's internal form: {@code int * int} rather than
   * {@code tuple(int, int)}, {@code T list} rather than {@code list(T)}.
   */
  private static String render(Term term) {
    if (term instanceof Sequence) {
      final Sequence seq = (Sequence) term;
      final String ord = orderednessAtom(seq);
      if (ord != null) {
        // A bare orderedness atom surfaces when two collections are unified on
        // a shared orderedness variable and clash; render it as "list"/"bag".
        return ord.equals(ORDERED_OP) ? "list" : "bag";
      }
      if (seq.operator.equals(COLLECTION_OP) && seq.terms.size() == 2) {
        final String kind =
            ORDERED_OP.equals(orderednessAtom(seq.terms.get(1)))
                ? "list"
                : "bag";
        return atomic(seq.terms.get(0)) + " " + kind;
      }
      if (seq.operator.startsWith(ALIAS_PREFIX)) {
        // "$alias:t(int)" reads "t (alias for int)".
        return seq.operator.substring(ALIAS_PREFIX.length())
            + " (alias for "
            + render(seq.terms.get(0))
            + ")";
      }
      if (seq.operator.equals(TUPLE_OP) && !seq.terms.isEmpty()) {
        return join(seq.terms, " * ");
      }
      if (seq.operator.equals(FN_OP) && seq.terms.size() == 2) {
        return render(seq.terms.get(0)) + " -> " + render(seq.terms.get(1));
      }
      if (seq.operator.startsWith(RECORD_OP + ":")) {
        final List<String> names = fieldNames(seq);
        if (names.size() == seq.terms.size()) {
          final StringBuilder b = new StringBuilder("{");
          for (int i = 0; i < seq.terms.size(); i++) {
            if (i > 0) {
              b.append(", ");
            }
            b.append(names.get(i)).append(':').append(render(seq.terms.get(i)));
          }
          return b.append('}').toString();
        }
      }
      if (!seq.terms.isEmpty()) {
        // A type constructor applied to arguments, e.g. "int option".
        return (seq.terms.size() == 1
                ? atomic(seq.terms.get(0))
                : "(" + join(seq.terms, ", ") + ")")
            + " "
            + seq.operator;
      }
    }
    return term.toString();
  }

  /**
   * Operator prefix of a type-alias sequence; matches {@code
   * TypeResolver.ALIAS_TY_CON}.
   */
  private static final String ALIAS_PREFIX = "$alias:";

  /**
   * Expands a type alias, repeatedly, or returns the term unchanged.
   *
   * <p>A type alias is a sequence whose operator starts with "$alias:" and
   * whose first argument is its expanded body. Expanding lets an alias unify
   * with the type it abbreviates, while leaving it in place everywhere the two
   * never meet, so that it survives inference.
   */
  private static Term headReduce(Term term) {
    while (term instanceof Sequence
        && ((Sequence) term).operator.startsWith(ALIAS_PREFIX)) {
      term = ((Sequence) term).terms.get(0);
    }
    return term;
  }

  /**
   * Whether two expanded terms are sequences that disagree at the head, and so
   * can never unify.
   */
  private static boolean conflictsAtHead(Term left, Term right) {
    return left instanceof Sequence
        && right instanceof Sequence
        && !((Sequence) left).operator.equals(((Sequence) right).operator);
  }

  private static final String TUPLE_OP = "tuple";
  private static final String FN_OP = "fn";
  private static final String RECORD_OP = "record";

  /**
   * Renders a term, parenthesized if it would otherwise bind less tightly than
   * the postfix type constructor it is the argument of: {@code (int * int)
   * list}, not {@code int * int list}.
   */
  private static String atomic(Term term) {
    final String s = render(term);
    return s.indexOf(' ') < 0 || s.startsWith("(") || s.startsWith("{")
        ? s
        : "(" + s + ")";
  }

  /** Renders terms, separated. */
  private static String join(List<Term> terms, String separator) {
    final StringBuilder b = new StringBuilder();
    for (int i = 0; i < terms.size(); i++) {
      if (i > 0) {
        b.append(separator);
      }
      b.append(render(terms.get(i)));
    }
    return b.toString();
  }

  /** Field names of a record term, whose operator is e.g. "record:a:b". */
  private static List<String> fieldNames(Sequence seq) {
    return ImmutableList.copyOf(
        seq.operator.substring(RECORD_OP.length() + 1).split(":"));
  }

  private void act(
      Variable variable,
      Term term,
      Work work,
      Substitution substitution,
      Map<Variable, Action> termActions,
      Set<Variable> set) {
    // To prevent infinite recursion, this method is a no-op if the variable
    // is already in the working set.
    if (set.add(variable)) {
      act2(variable, term, work, substitution, termActions, set);

      // Remove the variable from the working set.
      set.remove(variable);
    }
  }

  private void act2(
      Variable variable,
      Term term,
      Work work,
      Substitution substitution,
      Map<Variable, Action> termActions,
      Set<Variable> set) {
    final Action action = termActions.get(variable);
    if (action != null) {
      action.accept(variable, term, substitution, work::add);
    }
    if (term instanceof Variable) {
      // Create a temporary list to prevent concurrent modification, in case the
      // action appends to the list. Limit on depth, to prevent infinite
      // recursion.
      final Iterable<TermTerm> termPairsCopy = work.allTermPairs();
      termPairsCopy.forEach(
          termPair -> {
            if (termPair.left.equals(term)) {
              act(
                  variable,
                  termPair.right,
                  work,
                  substitution,
                  termActions,
                  set);
            }
          });
      // If the term is a variable, recurse to see whether there is an
      // action for that variable. Limit on depth to prevent swapping back.
      if (set.size() < 2) {
        act((Variable) term, variable, work, substitution, termActions, set);
      }
    }
    substitution.resultMap.forEach(
        (variable2, v) -> {
          // Substitution contains "variable2 -> variable"; call the actions of
          // "variable2", because it too has just been unified.
          if (v.equals(variable)) {
            act(variable2, term, work, substitution, termActions, set);
          }
        });
  }

  /** Workspace for {@link MartelliUnifier}. */
  class Work {
    final Tracer tracer;
    final ArrayQueue<TermTerm> deleteQueue = new ArrayQueue<>();
    final ArrayQueue<TermTerm> seqSeqQueue = new ArrayQueue<>();
    final ArrayQueue<TermTerm> varAnyQueue = new ArrayQueue<>();
    final List<MutableConstraint> constraintQueue = new ArrayList<>();
    final Map<Variable, Term> result;

    Work(
        Tracer tracer,
        List<TermTerm> termPairs,
        List<Constraint> constraints,
        Map<Variable, Term> result) {
      this.tracer = tracer;
      this.result = result;
      termPairs.forEach(pair -> add(pair.left, pair.right));
      constraints.forEach(c -> constraintQueue.add(new MutableConstraint(c)));
    }

    @Override
    public String toString() {
      return format(
          "delete %s seqSeq %s varAny %s constraints %s result %s",
          deleteQueue, seqSeqQueue, varAnyQueue, constraintQueue, result);
    }

    void add2(Term left, Term right) {
      add(left.apply(result), right.apply(result));
    }

    void add(Term left, Term right) {
      switch (Kind.of(left, right)) {
        case DELETE:
          deleteQueue.add(new TermTerm(left, right));
          break;
        case SEQ_SEQ:
          seqSeqQueue.add(new TermTerm(left, right));
          break;
        case NON_VAR_VAR:
          tracer.onSwap(left, right);
          varAnyQueue.add(new TermTerm(right, left));
          break;
        case VAR_ANY:
          varAnyQueue.add(new TermTerm(left, right));
      }
    }

    /** Returns a list of all term pairs. */
    List<TermTerm> allTermPairs() {
      final ImmutableList.Builder<TermTerm> builder = ImmutableList.builder();
      deleteQueue.forEach(builder::add);
      seqSeqQueue.forEach(builder::add);
      varAnyQueue.forEach(builder::add);
      return builder.build();
    }

    /**
     * Applies a mapping to all term pairs in a list, modifying them in place.
     */
    private @Nullable Failure substituteList(Variable variable, Term term) {
      sub(variable, term, deleteQueue, Kind.DELETE);
      sub(variable, term, seqSeqQueue, Kind.SEQ_SEQ);
      sub(variable, term, varAnyQueue, Kind.VAR_ANY);
      return subConstraint(variable, term);
    }

    private void sub(
        Variable variable, Term term, ArrayQueue<TermTerm> queue, Kind kind) {
      for (ListIterator<TermTerm> iter = queue.listIterator();
          iter.hasNext(); ) {
        final TermTerm pair = iter.next();
        final Term left2 = pair.left.apply1(variable, term);
        final Term right2 = pair.right.apply1(variable, term);
        if (left2 != pair.left || right2 != pair.right) {
          tracer.onSubstitute(pair.left, pair.right, left2, right2);
          final Kind kind2 = Kind.of(left2, right2);
          if (kind2 == kind) {
            // Still belongs in this queue
            iter.set(new TermTerm(left2, right2));
          } else if (kind2 == Kind.NON_VAR_VAR && kind == Kind.VAR_ANY) {
            iter.set(new TermTerm(right2, left2));
          } else {
            // Belongs in another queue
            iter.remove();
            add(left2, right2);
          }
        }
      }
    }

    private @Nullable Failure subConstraint(Variable variable, Term term) {
      for (MutableConstraint constraint : constraintQueue) {
        final Term arg2 = constraint.arg.apply1(variable, term);
        int changeCount = 0;
        if (arg2 != constraint.arg) {
          ++changeCount;
          constraint.arg = arg2;
          constraint
              .termActions
              .leftList()
              .removeIf(arg1 -> !arg2.couldUnifyWith(arg1));
        }
        for (ListIterator<Term> iterator =
                constraint.termActions.leftList().listIterator();
            iterator.hasNext(); ) {
          final Term subArg = iterator.next();
          final Term subArg2 = subArg.apply1(variable, term);
          if (subArg != subArg2) {
            ++changeCount;
            iterator.set(subArg2);
            if (!arg2.couldUnifyWith(subArg2)) {
              iterator.remove();
            }
          }
        }
        if (changeCount > 0) {
          switch (constraint.termActions.size()) {
            case 0:
              final Constraint c = constraint.constraint;
              if (c.name != null) {
                return failure(
                    format(
                        "no instance of '%s' matches argument type '%s'",
                        c.name, render(constraint.arg)));
              }
              return failure("no valid overloads");
            case 1:
              Term term1 = constraint.termActions.left(0);
              Constraint.Action action = constraint.termActions.right(0);
              action.accept(constraint.arg, term1, this::add2);
              break;
          }
        }
      }
      return null;
    }
  }

  private enum Kind {
    DELETE,
    SEQ_SEQ,
    VAR_ANY,
    NON_VAR_VAR;

    static Kind of(Term left, Term right) {
      if (left.equals(right)) {
        return DELETE;
      }
      if (left instanceof Sequence) {
        if (right instanceof Sequence) {
          return SEQ_SEQ;
        } else {
          assert right instanceof Variable;
          return NON_VAR_VAR;
        }
      } else {
        assert left instanceof Variable;
        return VAR_ANY;
      }
    }
  }

  /** As {@link Constraint}, but mutable. */
  private static class MutableConstraint {
    final Constraint constraint;
    final Variable v;
    Term arg;
    final PairList<Term, Constraint.Action> termActions;

    /** Creates a MutableConstraint. */
    MutableConstraint(
        Constraint constraint,
        Variable arg,
        PairList<Term, Constraint.Action> termActions) {
      this.constraint = requireNonNull(constraint);
      this.v = requireNonNull(arg);
      this.arg = requireNonNull(arg);
      this.termActions = termActions;
      checkArgument(!termActions.isEmpty());
    }

    MutableConstraint(Constraint constraint) {
      this(constraint, constraint.arg, PairList.copyOf(constraint.termActions));
    }

    @Override
    public String toString() {
      return format("{constraint %s = %s %s}", v, arg, termActions);
    }
  }
}

// End MartelliUnifier.java
