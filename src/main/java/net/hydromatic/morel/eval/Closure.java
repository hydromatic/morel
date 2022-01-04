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
package net.hydromatic.morel.eval;

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.util.Pair;

import com.google.common.collect.ImmutableList;
import org.apache.calcite.util.Util;

import java.math.BigDecimal;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Value that is sufficient for a function to bind its argument
 * and evaluate its body. */
public class Closure implements Comparable<Closure>, Applicable {
  /** Environment for evaluation. Contains the variables "captured" from the
   * environment when the closure was created. */
  private final EvalEnv evalEnv;

  /** A list of (pattern, code) pairs. During bind, the value being bound is
   * matched against each pattern. When a match is found, the code for that
   * pattern is used to evaluate.
   *
   * <p>For example, when applying
   * {@code fn x => case x of 0 => "yes" | _ => "no"}
   * to the value {@code 1}, the first pattern ({@code 0} fails) but the second
   * pattern pattern ({@code _}) succeeds, and therefore we evaluate the second
   * code {@code "no"}. */
  private final ImmutableList<Pair<Core.Pat, Code>> patCodes;

  /** Not a public API. */
  public Closure(EvalEnv evalEnv,
      ImmutableList<Pair<Core.Pat, Code>> patCodes) {
    this.evalEnv = requireNonNull(evalEnv).fix();
    this.patCodes = requireNonNull(patCodes);
  }

  @Override public String toString() {
    return "Closure(evalEnv = " + evalEnv + ", patCodes = " + patCodes + ")";
  }

  public int compareTo(Closure o) {
    return 0;
  }

  /** Binds an argument value to create a new environment for a closure.
   *
   * <p>When calling a simple function such as {@code (fn x => x + 1) 2},
   * the binder sets just contains one variable, {@code x}, and the
   * new environment contains {@code x = 1}.  If the function's
   * parameter is a match, more variables might be bound. For example,
   * when you invoke {@code (fn (x, y) => x + y) (3, 4)}, the binder
   * sets {@code x} to 3 and {@code y} to 4. */
  EvalEnv bind(Object argValue) {
    final EvalEnv[] envRef = {evalEnv};
    for (Pair<Core.Pat, Code> patCode : patCodes) {
      final Core.Pat pat = patCode.left;
      if (bindRecurse(pat, envRef, argValue)) {
        return envRef[0];
      }
    }
    throw new AssertionError("no match");
  }

  /** Similar to {@link #bind}, but evaluates an expression first. */
  EvalEnv evalBind(EvalEnv env) {
    final EvalEnv[] envRef = {env};
    for (Pair<Core.Pat, Code> patCode : patCodes) {
      final Object argValue = patCode.right.eval(env);
      final Core.Pat pat = patCode.left;
      if (bindRecurse(pat, envRef, argValue)) {
        return envRef[0];
      }
    }
    throw new AssertionError("no match");
  }

  /** Similar to {@link #bind}, but also evaluates. */
  Object bindEval(Object argValue) {
    final EvalEnv[] envRef = {evalEnv};
    for (Pair<Core.Pat, Code> patCode : patCodes) {
      final Core.Pat pat = patCode.left;
      if (bindRecurse(pat, envRef, argValue)) {
        final Code code = patCode.right;
        return code.eval(envRef[0]);
      }
    }
    throw new AssertionError("no match: " + Pair.left(patCodes));
  }

  @Override public Object apply(EvalEnv env, Object argValue) {
    return bindEval(argValue);
  }

  @Override public Describer describe(Describer describer) {
    return describer.start("closure", d -> {});
  }

  private boolean bindRecurse(Core.Pat pat, EvalEnv[] envRef,
      Object argValue) {
    final List<Object> listValue;
    final Core.LiteralPat literalPat;
    switch (pat.op) {
    case ID_PAT:
      final Core.IdPat idPat = (Core.IdPat) pat;
      envRef[0] = envRef[0].bind(idPat.name, argValue);
      return true;

    case WILDCARD_PAT:
      return true;

    case BOOL_LITERAL_PAT:
    case CHAR_LITERAL_PAT:
    case STRING_LITERAL_PAT:
      literalPat = (Core.LiteralPat) pat;
      return literalPat.value.equals(argValue);

    case INT_LITERAL_PAT:
      literalPat = (Core.LiteralPat) pat;
      return ((BigDecimal) literalPat.value).intValue() == (Integer) argValue;

    case REAL_LITERAL_PAT:
      literalPat = (Core.LiteralPat) pat;
      return ((BigDecimal) literalPat.value).doubleValue() == (Double) argValue;

    case TUPLE_PAT:
      final Core.TuplePat tuplePat = (Core.TuplePat) pat;
      listValue = (List) argValue;
      for (Pair<Core.Pat, Object> pair : Pair.zip(tuplePat.args, listValue)) {
        if (!bindRecurse(pair.left, envRef, pair.right)) {
          return false;
        }
      }
      return true;

    case RECORD_PAT:
      final Core.RecordPat recordPat = (Core.RecordPat) pat;
      listValue = (List) argValue;
      for (Pair<Core.Pat, Object> pair : Pair.zip(recordPat.args, listValue)) {
        if (!bindRecurse(pair.left, envRef, pair.right)) {
          return false;
        }
      }
      return true;

    case LIST_PAT:
      final Core.ListPat listPat = (Core.ListPat) pat;
      listValue = (List) argValue;
      if (listValue.size() != listPat.args.size()) {
        return false;
      }
      for (Pair<Core.Pat, Object> pair : Pair.zip(listPat.args, listValue)) {
        if (!bindRecurse(pair.left, envRef, pair.right)) {
          return false;
        }
      }
      return true;

    case CONS_PAT:
      final Core.ConPat consPat = (Core.ConPat) pat;
      @SuppressWarnings("unchecked") final List<Object> consValue =
          (List) argValue;
      if (consValue.isEmpty()) {
        return false;
      }
      final Object head = consValue.get(0);
      final List<Object> tail = Util.skip(consValue);
      List<Core.Pat> patArgs = ((Core.TuplePat) consPat.pat).args;
      return bindRecurse(patArgs.get(0), envRef, head)
          && bindRecurse(patArgs.get(1), envRef, tail);

    case CON0_PAT:
      final Core.Con0Pat con0Pat = (Core.Con0Pat) pat;
      final List con0Value = (List) argValue;
      return con0Value.get(0).equals(con0Pat.tyCon);

    case CON_PAT:
      final Core.ConPat conPat = (Core.ConPat) pat;
      final List conValue = (List) argValue;
      return conValue.get(0).equals(conPat.tyCon)
          && bindRecurse(conPat.pat, envRef, conValue.get(1));

    default:
      throw new AssertionError("cannot compile " + pat.op + ": " + pat);
    }
  }
}

// End Closure.java
