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

import com.google.common.collect.ImmutableMap;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Unification algorithm due to Martelli, Montanari (1976) and
 * Paterson, Wegman (1978). */
public class MartelliUnifier extends Unifier {
  public @NonNull Result unify(List<TermTerm> termPairs,
      Map<Variable, Action> termActions, Tracer tracer) {

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

    termPairs = new ArrayList<>(termPairs);
    final Map<Variable, Term> result = new LinkedHashMap<>();
    for (;;) {
      if (termPairs.isEmpty()) {
        return SubstitutionResult.create(result);
      }
      int i = findDelete(termPairs);
      if (i >= 0) {
        final TermTerm pair = termPairs.remove(i); // delete
        tracer.onDelete(pair.left, pair.right);
        continue;
      }

      i = findSeqSeq(termPairs);
      if (i >= 0) {
        final TermTerm pair = termPairs.get(i);
        final Sequence left = (Sequence) pair.left;
        final Sequence right = (Sequence) pair.right;

        if (!left.operator.equals(right.operator)
            || left.terms.size() != right.terms.size()) {
          tracer.onConflict(left, right);
          return failure("conflict: " + left + " vs " + right);
        }
        termPairs.remove(i); // decompose
        tracer.onSequence(left, right);
        for (int j = 0; j < left.terms.size(); j++) {
          termPairs.add(new TermTerm(left.terms.get(j), right.terms.get(j)));
        }
        continue;
      }

      i = findNonVarVar(termPairs);
      if (i >= 0) {
        final TermTerm pair = termPairs.get(i);
        termPairs.set(i, new TermTerm(pair.right, pair.left));
        tracer.onSwap(pair.left, pair.right);
        continue; // swap
      }

      i = findVarAny(termPairs);
      if (i >= 0) {
        final TermTerm pair = termPairs.remove(i);
        final Variable variable = (Variable) pair.left;
        final Term term = pair.right;
        if (term.contains(variable)) {
          tracer.onCycle(variable, term);
          return failure("cycle: variable " + variable + " in " + term);
        }
        tracer.onVariable(variable, term);
        result.put(variable, term);
        act(variable, term, termPairs, new Substitution(result),
            termActions, 0);
        substituteList(termPairs, tracer, ImmutableMap.of(variable, term));
      }
    }
  }

  /** Applies a mapping to all term pairs in a list, modifying them in place. */
  private void substituteList(List<TermTerm> termPairs, Tracer tracer,
      Map<Variable, Term> map) {
    for (int j = 0; j < termPairs.size(); j++) {
      final TermTerm pair2 = termPairs.get(j);
      final Term left2 = pair2.left.apply(map);
      final Term right2 = pair2.right.apply(map);
      if (left2 != pair2.left
          || right2 != pair2.right) {
        tracer.onSubstitute(pair2.left, pair2.right, left2, right2);
        termPairs.set(j, new TermTerm(left2, right2));
      }
    }
  }

  private void act(Variable variable, Term term, List<TermTerm> termPairs,
      Substitution substitution, Map<Variable, Action> termActions,
      int depth) {
    final Action action = termActions.get(variable);
    if (action != null) {
      action.accept(variable, term, substitution, termPairs);
    }
    if (term instanceof Variable) {
      // Copy list to prevent concurrent modification, in case the action
      // appends to the list. Limit on depth, to prevent infinite recursion.
      final List<TermTerm> termPairsCopy = new ArrayList<>(termPairs);
      termPairsCopy.forEach(termPair -> {
        if (termPair.left.equals(term) && depth < 2) {
          act(variable, termPair.right, termPairs, substitution, termActions,
              depth + 1);
        }
      });
      // If the term is a variable, recurse to see whether there is an
      // action for that variable. Limit on depth to prevent swapping back.
      if (depth < 1) {
        act((Variable) term, variable, termPairs, substitution, termActions,
            depth + 1);
      }
    }
    substitution.resultMap.forEach((variable2, v) -> {
      // Substitution contains "variable2 -> variable"; call the actions of
      // "variable2", because it too has just been unified.
      if (v.equals(variable)) {
        act(variable2, term, termPairs, substitution, termActions,
            depth + 1);
      }
    });
  }

  private int findDelete(List<TermTerm> termPairs) {
    for (int i = 0; i < termPairs.size(); i++) {
      TermTerm termTerm = termPairs.get(i);
      if (termTerm.left.equals(termTerm.right)) {
        return i;
      }
    }
    return -1;
  }

  private int findSeqSeq(List<TermTerm> termPairs) {
    for (int i = 0; i < termPairs.size(); i++) {
      TermTerm termTerm = termPairs.get(i);
      if (termTerm.left instanceof Sequence
          && termTerm.right instanceof Sequence) {
        return i;
      }
    }
    return -1;
  }

  private int findNonVarVar(List<TermTerm> termPairs) {
    for (int i = 0; i < termPairs.size(); i++) {
      TermTerm termTerm = termPairs.get(i);
      if (!(termTerm.left instanceof Variable)
          && termTerm.right instanceof Variable) {
        return i;
      }
    }
    return -1;
  }

  private int findVarAny(List<TermTerm> termPairs) {
    for (int i = 0; i < termPairs.size(); i++) {
      TermTerm termTerm = termPairs.get(i);
      if (termTerm.left instanceof Variable) {
        return i;
      }
    }
    return -1;
  }

}

// End MartelliUnifier.java
