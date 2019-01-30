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

import net.hydromatic.sml.ast.Ast;

import java.util.List;
import java.util.Map;

/** Value that is sufficient for a function to bind its argument
 * and evaluate its body. */
public class Closure implements Comparable<Closure> {
  private final EvalEnv evalEnv;
  public final Code resultCode;
  private final Ast.Pat pat;

  public Closure(EvalEnv evalEnv, Code resultCode, Ast.Pat pat) {
    this.evalEnv = evalEnv;
    this.resultCode = resultCode;
    this.pat = pat;
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
    final EvalEnv env2 = new EvalEnv();
    env2.valueMap.putAll(evalEnv.valueMap);
    bindRecurse(pat, env2.valueMap, argValue);
    return env2;
  }

  private void bindRecurse(Ast.Pat pat, Map<String, Object> valueMap,
      Object argValue) {
    switch (pat.op) {
    case ID_PAT:
      final Ast.IdPat idPat = (Ast.IdPat) pat;
      valueMap.put(idPat.name, argValue);
      return;

    case TUPLE_PAT:
      final Ast.TuplePat tuplePat = (Ast.TuplePat) pat;
      List listValue = (List) argValue;
      tuplePat.forEachArg((arg, i) ->
          bindRecurse(arg, valueMap, listValue.get(i)));
      return;

    default:
      throw new AssertionError("cannot compile pattern " + pat);
    }
  }
}

// End Closure.java
