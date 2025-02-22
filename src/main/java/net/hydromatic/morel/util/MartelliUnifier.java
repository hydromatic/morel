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
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Unification algorithm due to Martelli, Montanari (1976) and Paterson, Wegman
 * (1978).
 */
public class MartelliUnifier extends Unifier {
  @Override
  public @NonNull Result unify(
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
    final Work work = new Work(tracer, termPairs, constraints, result);
    for (int iteration = 0; ; iteration++) {
      // delete
      if (!work.deleteQueue.isEmpty()) {
        @Nullable TermTerm pair = work.deleteQueue.remove(0);
        if (pair != null) {
          tracer.onDelete(pair.left, pair.right);
          continue;
        }
      }

      if (!work.seqSeqQueue.isEmpty()) {
        TermTerm pair = work.seqSeqQueue.remove(0);
        final Sequence left = (Sequence) pair.left;
        final Sequence right = (Sequence) pair.right;

        if (!left.operator.equals(right.operator)
            || left.terms.size() != right.terms.size()) {
          tracer.onConflict(left, right);
          return failure("conflict: " + left + " vs " + right);
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
        final Term term = pair.right;
        if (term.contains(variable)) {
          tracer.onCycle(variable, term);
          return failure("cycle: variable " + variable + " in " + term);
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
      return SubstitutionResult.create(result);
    }
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
  static class Work {
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
      return "delete "
          + deleteQueue
          + " seqSeq "
          + seqSeqQueue
          + " varAny "
          + varAnyQueue
          + " constraints "
          + constraintQueue;
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
              return failure("no valid overloads");
            case 1:
              Term term1 = constraint.termActions.left(0);
              Constraint.Action consumer = constraint.termActions.right(0);
              consumer.accept(constraint.arg, term1, this::add2);
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
    final Variable v;
    Term arg;
    Variable result;
    final PairList<Term, Constraint.Action> termActions;

    /** Creates a MutableConstraint. */
    MutableConstraint(
        Variable arg, PairList<Term, Constraint.Action> termActions) {
      this.v = requireNonNull(arg);
      this.arg = requireNonNull(arg);
      this.termActions = termActions;
      checkArgument(!termActions.isEmpty());
    }

    MutableConstraint(Constraint constraint) {
      this(constraint.arg, PairList.copyOf(constraint.termActions));
    }

    @Override
    public String toString() {
      return format("{constraint %s = %s %s %s}", v, arg, result, termActions);
    }
  }
}

// End MartelliUnifier.java
