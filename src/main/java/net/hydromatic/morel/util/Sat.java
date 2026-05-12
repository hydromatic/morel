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

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Boolean satisfiability. */
public class Sat {
  private final Map<Integer, Variable> variablesById = new LinkedHashMap<>();
  private final Map<String, Variable> variablesByName = new HashMap<>();
  private int nextVariable = 0;
  /**
   * Each slot is a group of variables of which exactly one is true. Variables
   * not in any slot are searched by brute force (both {@code false} and {@code
   * true}).
   */
  private final List<List<Variable>> slots = new ArrayList<>();

  /**
   * Declares that {@code vars} is a "slot": exactly one of them is true in any
   * satisfying assignment. Allows {@link #solve} to enumerate {@code N}
   * candidates (one per variable in the slot) rather than {@code 2^N}.
   */
  public List<Variable> slot(Variable... vars) {
    return slot(ImmutableList.copyOf(vars));
  }

  /** As {@link #slot(Variable...)}. */
  public List<Variable> slot(Iterable<? extends Variable> vars) {
    final List<Variable> slot = ImmutableList.copyOf(vars);
    slots.add(slot);
    return slot;
  }

  /**
   * Finds an assignment of variables such that a term evaluates to true, or
   * null if there is no solution.
   *
   * <p>Enumerates candidates as the Cartesian product of:
   *
   * <ul>
   *   <li>{@code 2^F} settings of the {@code F} variables that are not in any
   *       slot, and
   *   <li>{@code N_i} one-hot settings for each slot of size {@code N_i}.
   * </ul>
   *
   * <p>This is exact, simpler than a general SAT solver, and avoids the {@code
   * 2^N} blow-up when every variable belongs to a one-hot group.
   */
  public @Nullable Map<Variable, Boolean> solve(Term term) {
    final boolean[] env = new boolean[nextVariable];

    final boolean[] inSlot = new boolean[nextVariable];
    for (List<Variable> slot : slots) {
      for (Variable v : slot) {
        inSlot[v.id] = true;
      }
    }
    final List<Variable> freeVars = new ArrayList<>();
    for (Variable v : variablesById.values()) {
      if (!inSlot[v.id]) {
        freeVars.add(v);
      }
    }

    return enumerate(env, freeVars, 0, term);
  }

  private @Nullable Map<Variable, Boolean> enumerate(
      boolean[] env, List<Variable> freeVars, int freeIdx, Term term) {
    if (freeIdx < freeVars.size()) {
      final Variable v = freeVars.get(freeIdx);
      for (boolean value : new boolean[] {false, true}) {
        env[v.id] = value;
        final Map<Variable, Boolean> r =
            enumerate(env, freeVars, freeIdx + 1, term);
        if (r != null) {
          return r;
        }
      }
      return null;
    }
    return enumerateSlots(env, 0, term);
  }

  private @Nullable Map<Variable, Boolean> enumerateSlots(
      boolean[] env, int slotIdx, Term term) {
    if (slotIdx == slots.size()) {
      if (term.evaluate(env)) {
        final ImmutableMap.Builder<Variable, Boolean> b =
            ImmutableMap.builder();
        for (Variable v : variablesById.values()) {
          b.put(v, env[v.id]);
        }
        return b.build();
      }
      return null;
    }
    final List<Variable> slot = slots.get(slotIdx);
    for (Variable v : slot) {
      env[v.id] = false;
    }
    for (Variable trueVar : slot) {
      env[trueVar.id] = true;
      final Map<Variable, Boolean> r = enumerateSlots(env, slotIdx + 1, term);
      if (r != null) {
        return r;
      }
      env[trueVar.id] = false;
    }
    return null;
  }

  public Variable variable(String name) {
    Variable variable = variablesByName.get(name);
    if (variable != null) {
      return variable;
    }
    int id = nextVariable++;
    variable = new Variable(id, name);
    variablesById.put(id, variable);
    variablesByName.put(name, variable);
    return variable;
  }

  public Term not(Term term) {
    return new Not(term);
  }

  public Term and(Term... terms) {
    return new And(ImmutableList.copyOf(terms));
  }

  public Term and(Iterable<? extends Term> terms) {
    return new And(ImmutableList.copyOf(terms));
  }

  public Term or(Term... terms) {
    return new Or(ImmutableList.copyOf(terms));
  }

  public Term or(Iterable<? extends Term> terms) {
    return new Or(ImmutableList.copyOf(terms));
  }

  /** Base class for all terms (variables, and, or, not). */
  public abstract static class Term {
    final Op op;

    Term(Op op) {
      this.op = requireNonNull(op, "op");
    }

    @Override
    public String toString() {
      return unparse(new StringBuilder(), 0, 0).toString();
    }

    protected abstract StringBuilder unparse(
        StringBuilder buf, int left, int right);

    public abstract boolean evaluate(boolean[] env);
  }

  /** Variable. Its value can be true or false. */
  public static class Variable extends Term {
    public final int id;
    public final String name;

    Variable(int id, String name) {
      super(Op.VARIABLE);
      this.id = id;
      this.name = requireNonNull(name, "name");
    }

    @Override
    protected StringBuilder unparse(StringBuilder buf, int left, int right) {
      return buf.append(name);
    }

    @Override
    public boolean evaluate(boolean[] env) {
      return env[id];
    }
  }

  /** Term that has a variable number of arguments ("and" or "or"). */
  abstract static class Node extends Term {
    final ImmutableList<Term> terms;

    Node(Op op, ImmutableList<Term> terms) {
      super(op);
      this.terms = requireNonNull(terms);
    }

    @Override
    protected StringBuilder unparse(StringBuilder buf, int left, int right) {
      switch (terms.size()) {
        case 0:
          // empty "and" prints as "true";
          // empty "or" prints as "false"
          return buf.append(op.emptyName);
        case 1:
          // singleton "and" and "or" print as the sole term
          return terms.get(0).unparse(buf, left, right);
      }
      if (left > op.left || right > op.right) {
        return unparse(buf.append('('), 0, 0).append(')');
      }
      for (int i = 0; i < terms.size(); i++) {
        final Term term = terms.get(i);
        if (i > 0) {
          buf.append(op.str);
        }
        term.unparse(
            buf,
            i == 0 ? left : op.right,
            i == terms.size() - 1 ? right : op.left);
      }
      return buf;
    }
  }

  /** "And" term. */
  static class And extends Node {
    And(ImmutableList<Term> terms) {
      super(Op.AND, terms);
    }

    @Override
    public boolean evaluate(boolean[] env) {
      for (Term term : terms) {
        if (!term.evaluate(env)) {
          return false;
        }
      }
      return true;
    }
  }

  /** "Or" term. */
  static class Or extends Node {
    Or(ImmutableList<Term> terms) {
      super(Op.OR, terms);
    }

    @Override
    public boolean evaluate(boolean[] env) {
      for (Term term : terms) {
        if (term.evaluate(env)) {
          return true;
        }
      }
      return false;
    }
  }

  /** "Not" term. */
  static class Not extends Term {
    final Term term;

    Not(Term term) {
      super(Op.NOT);
      this.term = requireNonNull(term, "term");
    }

    @Override
    protected StringBuilder unparse(StringBuilder buf, int left, int right) {
      return term.unparse(buf.append(op.str), op.right, right);
    }

    @Override
    public boolean evaluate(boolean[] env) {
      return !term.evaluate(env);
    }
  }

  /**
   * Operator (or type of term), with its left and right precedence and print
   * name.
   */
  private enum Op {
    AND(3, 4, " ∧ ", "true"),
    OR(1, 2, " ∨ ", "false"),
    NOT(5, 5, "¬", ""),
    VARIABLE(0, 0, "", "");

    final int left;
    final int right;
    final String str;
    final String emptyName;

    Op(int left, int right, String str, String emptyName) {
      this.left = left;
      this.right = right;
      this.str = str;
      this.emptyName = emptyName;
    }
  }
}

// End Sat.java
