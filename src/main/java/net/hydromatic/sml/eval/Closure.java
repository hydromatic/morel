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
package net.hydromatic.sml.eval;

import com.google.common.collect.ImmutableList;

import net.hydromatic.sml.ast.Ast;
import net.hydromatic.sml.util.Pair;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Value that is sufficient for a function to bind its argument
 * and evaluate its body. */
public class Closure implements Comparable<Closure> {
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
  private final ImmutableList<Pair<Ast.Pat, Code>> patCodes;

  /** Not a public API. */
  public Closure(EvalEnv evalEnv,
      ImmutableList<Pair<Ast.Pat, Code>> patCodes) {
    this.evalEnv = Objects.requireNonNull(evalEnv);
    this.patCodes = Objects.requireNonNull(patCodes);
  }

  @Override public String toString() {
    return "fn";
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
    final EvalEnv env2 = evalEnv.copy();
    for (Pair<Ast.Pat, Code> patCode : patCodes) {
      final Ast.Pat pat = patCode.left;
      if (bindRecurse(pat, env2.valueMap, argValue)) {
        return env2;
      }
    }
    throw new AssertionError("no match");
  }

  /** Similar to {@link #bind}, but evaluates an expression first. */
  EvalEnv evalBind(EvalEnv env) {
    for (Pair<Ast.Pat, Code> patCode : patCodes) {
      final Object argValue = patCode.right.eval(env);
      final Ast.Pat pat = patCode.left;
      if (bindRecurse(pat, env.valueMap, argValue)) {
        return env;
      }
    }
    throw new AssertionError("no match");
  }

  /** Similar to {@link #bind}, but also evaluates. */
  Object bindEval(Object argValue) {
    final EvalEnv env = evalEnv.copy();
    for (Pair<Ast.Pat, Code> patCode : patCodes) {
      final Ast.Pat pat = patCode.left;
      if (bindRecurse(pat, env.valueMap, argValue)) {
        final Code code = patCode.right;
        return code.eval(env);
      }
    }
    throw new AssertionError("no match");
  }

  private boolean bindRecurse(Ast.Pat pat, Map<String, Object> valueMap,
      Object argValue) {
    switch (pat.op) {
    case ID_PAT:
      final Ast.IdPat idPat = (Ast.IdPat) pat;
      valueMap.put(idPat.name, argValue);
      return true;

    case TUPLE_PAT:
      final Ast.TuplePat tuplePat = (Ast.TuplePat) pat;
      List listValue = (List) argValue;
      tuplePat.forEachArg((arg, i) ->
          bindRecurse(arg, valueMap, listValue.get(i)));
      return true;

    case WILDCARD_PAT:
      return true;

    case INT_LITERAL_PAT:
      final Ast.LiteralPat literalPat = (Ast.LiteralPat) pat;
      return literalPat.value.equals(argValue);

    default:
      throw new AssertionError("cannot compile pattern " + pat);
    }
  }
}

// End Closure.java
