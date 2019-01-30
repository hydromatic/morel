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

import com.google.common.collect.ImmutableMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Unification algorithm due to Martelli, Montanari (1976) and
 * Paterson, Wegman (1978). */
public class MartelliUnifier extends Unifier {
  public @Nullable Substitution unify(List<TermTerm> termPairs) {

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
        return new Substitution(result);
      }
      int i = findDelete(termPairs);
      if (i >= 0) {
        termPairs.remove(i); // delete
        continue;
      }

      i = findSeqSeq(termPairs);
      if (i >= 0) {
        final TermTerm pair = termPairs.get(i);
        final Sequence left = (Sequence) pair.left;
        final Sequence right = (Sequence) pair.right;

        if (!left.operator.equals(right.operator)
            || left.terms.size() != right.terms.size()) {
          return null; // conflict
        }
        termPairs.remove(i); // decompose
        for (int j = 0; j < left.terms.size(); j++) {
          termPairs.add(new TermTerm(left.terms.get(j), right.terms.get(j)));
        }
        continue;
      }

      i = findNonVarVar(termPairs);
      if (i >= 0) {
        final TermTerm pair = termPairs.get(i);
        termPairs.set(i, new TermTerm(pair.right, pair.left));
        continue; // swap
      }

      i = findVarAny(termPairs);
      if (i >= 0) {
        final TermTerm pair = termPairs.remove(i);
        final Variable variable = (Variable) pair.left;
        final Term term = pair.right;
        if (term.contains(variable)) {
          return null; // check
        }
        final Map<Variable, Term> map = ImmutableMap.of(variable, term);
        result.put(variable, term);
        for (int j = 0; j < termPairs.size(); j++) {
          final TermTerm pair2 = termPairs.get(j);
          final Term left2 = pair2.left.apply(map);
          final Term right2 = pair2.right.apply(map);
          if (left2 != pair2.left
              || right2 != pair2.right) {
            termPairs.set(j, new TermTerm(left2, right2));
          }
        }
      }
    }
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
