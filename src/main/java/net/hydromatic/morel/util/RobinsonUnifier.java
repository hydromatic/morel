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

import com.google.common.collect.ImmutableSortedMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

import static net.hydromatic.morel.util.Static.skip;

/** Robinson's unification algorithm. */
public class RobinsonUnifier extends Unifier {
  /**
   * Applies s1 to the elements of s2 and adds them into a single list.
   */
  static Map<Variable, Term> compose(Map<Variable, Term> s1,
      Map<Variable, Term> s2) {
    Map<Variable, Term> composed = new HashMap<>(s1);
    s2.forEach((key, value) -> composed.put(key, value.apply(s1)));
    return composed;
  }

  private @Nonnull Result sequenceUnify(Sequence lhs,
      Sequence rhs) {
    if (lhs.terms.size() != rhs.terms.size()) {
      return failure("sequences have different length: " + lhs + ", " + rhs);
    }
    if (!lhs.operator.equals(rhs.operator)) {
      return failure("sequences have different operator: " + lhs + ", " + rhs);
    }
    if (lhs.terms.isEmpty()) {
      return SubstitutionResult.EMPTY;
    }
    Term firstLhs = lhs.terms.get(0);
    Term firstRhs = rhs.terms.get(0);
    final Result r1 = unify(firstLhs, firstRhs);
    if (!(r1 instanceof Substitution)) {
      return r1;
    }
    final Substitution subs1 = (Substitution) r1;
    Sequence restLhs =
        sequenceApply(lhs.operator, subs1.resultMap, skip(lhs.terms));
    Sequence restRhs =
        sequenceApply(rhs.operator, subs1.resultMap, skip(rhs.terms));
    final Result r2 = sequenceUnify(restLhs, restRhs);
    if (!(r2 instanceof Substitution)) {
      return r2;
    }
    final Substitution subs2 = (Substitution) r2;
    final Map<Variable, Term> joined =
        ImmutableSortedMap.<Variable, Term>naturalOrder()
            .putAll(subs1.resultMap)
            .putAll(subs2.resultMap)
            .build();
    return SubstitutionResult.create(joined);
  }

  public @Nonnull Result unify(List<TermTerm> termPairs,
      Map<Variable, Action> termActions, Tracer tracer) {
    switch (termPairs.size()) {
    case 1:
      return unify(termPairs.get(0).left, termPairs.get(0).right);
    default:
      throw new AssertionError();
    }
  }

  public @Nonnull Result unify(Term lhs, Term rhs) {
    if (lhs instanceof Variable) {
      return SubstitutionResult.create((Variable) lhs, rhs);
    }
    if (rhs instanceof Variable) {
      return SubstitutionResult.create((Variable) rhs, lhs);
    }
    if (lhs instanceof Sequence && rhs instanceof Sequence) {
      return sequenceUnify((Sequence) lhs, (Sequence) rhs);
    }
    return failure("terms have different types: " + lhs + ", " + rhs);
  }

}

// End RobinsonUnifier.java
