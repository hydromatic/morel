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

import com.google.common.collect.ImmutableList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Unification algorithm due to Martelli, Montanari (1976) and Paterson, Wegman
 * (1978).
 */
public class MartelliUnifier extends Unifier {
  public @NonNull Result unify(
      List<TermTerm> termPairs,
      Map<Variable, Action> termActions,
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

    final Work work = new Work(tracer, termPairs);
    final Map<Variable, Term> result = new LinkedHashMap<>();
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
          work.add(new TermTerm(left.terms.get(j), right.terms.get(j)));
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
        result.put(variable, term);
        if (!termActions.isEmpty()) {
          act(variable, term, work, new Substitution(result), termActions, 0);
        }
        work.substituteList(variable, term);
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
      int depth) {
    final Action action = termActions.get(variable);
    if (action != null) {
      action.accept(
          variable,
          term,
          substitution,
          (leftTerm, rightTerm) -> work.add(new TermTerm(leftTerm, rightTerm)));
    }
    if (term instanceof Variable && depth < 2) {
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
                  depth + 1);
            }
          });
      // If the term is a variable, recurse to see whether there is an
      // action for that variable. Limit on depth to prevent swapping back.
      if (depth < 1) {
        act(
            (Variable) term,
            variable,
            work,
            substitution,
            termActions,
            depth + 1);
      }
    }
    substitution.resultMap.forEach(
        (variable2, v) -> {
          // Substitution contains "variable2 -> variable"; call the actions of
          // "variable2", because it too has just been unified.
          if (v.equals(variable)) {
            act(variable2, term, work, substitution, termActions, depth + 1);
          }
        });
  }

  /** Workspace for {@link MartelliUnifier}. */
  static class Work {
    final Tracer tracer;
    final ArrayQueue<TermTerm> deleteQueue = new ArrayQueue<>();
    final ArrayQueue<TermTerm> seqSeqQueue = new ArrayQueue<>();
    final ArrayQueue<TermTerm> varAnyQueue = new ArrayQueue<>();

    Work(Tracer tracer, List<TermTerm> termPairs) {
      this.tracer = tracer;
      termPairs.forEach(this::add);
    }

    @Override
    public String toString() {
      return "delete "
          + deleteQueue
          + " seqSeq "
          + seqSeqQueue
          + " varAny "
          + varAnyQueue;
    }

    void add(TermTerm pair) {
      switch (Kind.of(pair)) {
        case DELETE:
          deleteQueue.add(pair);
          break;
        case SEQ_SEQ:
          seqSeqQueue.add(pair);
          break;
        case NON_VAR_VAR:
          tracer.onSwap(pair.left, pair.right);
          pair = new TermTerm(pair.right, pair.left);
          // fall through
        case VAR_ANY:
          varAnyQueue.add(pair);
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
    private void substituteList(Variable variable, Term term) {
      sub(variable, term, deleteQueue, Kind.DELETE);
      sub(variable, term, seqSeqQueue, Kind.SEQ_SEQ);
      sub(variable, term, varAnyQueue, Kind.VAR_ANY);
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
          TermTerm pair2 = new TermTerm(left2, right2);
          final Kind kind2 = Kind.of(pair2);
          if (kind2 == kind) {
            // Still belongs in this queue
            iter.set(pair2);
          } else if (kind2 == Kind.NON_VAR_VAR && kind == Kind.VAR_ANY) {
            iter.set(new TermTerm(pair2.right, pair2.left));
          } else {
            // Belongs in another queue
            iter.remove();
            add(pair2);
          }
        }
      }
    }
  }

  private enum Kind {
    DELETE,
    SEQ_SEQ,
    VAR_ANY,
    NON_VAR_VAR;

    static Kind of(TermTerm pair) {
      if (pair.left.equals(pair.right)) {
        return DELETE;
      }
      if (pair.left instanceof Sequence) {
        if (pair.right instanceof Sequence) {
          return SEQ_SEQ;
        } else {
          assert pair.right instanceof Variable;
          return NON_VAR_VAR;
        }
      } else {
        assert pair.left instanceof Variable;
        return VAR_ANY;
      }
    }
  }
}

// End MartelliUnifier.java
