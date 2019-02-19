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
import com.google.common.collect.Iterables;

import net.hydromatic.sml.ast.Ast;
import net.hydromatic.sml.ast.AstBuilder;
import net.hydromatic.sml.ast.Pos;
import net.hydromatic.sml.util.Pair;

import java.util.Arrays;
import java.util.List;

/** Helpers for {@link Code}. */
public abstract class Codes {
  private Codes() {}

  /** Returns a Code that evaluates to the same value in all environments. */
  public static Code constant(Comparable value) {
    return env -> value;
  }

  /** Returns a Code that evaluates "{@code =}". */
  public static Code eq(Code code0, Code code1) {
    return env -> code0.eval(env).equals(code1.eval(env));
  }

  /** Returns a Code that evaluates "{@code <>}". */
  public static Code ne(Code code0, Code code1) {
    return env -> !code0.eval(env).equals(code1.eval(env));
  }

  /** Returns a Code that evaluates "{@code <}". */
  public static Code lt(Code code0, Code code1) {
    return env -> {
      final Comparable v0 = (Comparable) code0.eval(env);
      final Comparable v1 = (Comparable) code1.eval(env);
      return v0.compareTo(v1) < 0;
    };
  }

  /** Returns a Code that evaluates "{@code >}". */
  public static Code gt(Code code0, Code code1) {
    return env -> {
      final Comparable v0 = (Comparable) code0.eval(env);
      final Comparable v1 = (Comparable) code1.eval(env);
      return v0.compareTo(v1) > 0;
    };
  }

  /** Returns a Code that evaluates "{@code <=}". */
  public static Code le(Code code0, Code code1) {
    return env -> {
      final Comparable v0 = (Comparable) code0.eval(env);
      final Comparable v1 = (Comparable) code1.eval(env);
      return v0.compareTo(v1) <= 0;
    };
  }

  /** Returns a Code that evaluates "{@code >=}". */
  public static Code ge(Code code0, Code code1) {
    return env -> {
      final Comparable v0 = (Comparable) code0.eval(env);
      final Comparable v1 = (Comparable) code1.eval(env);
      return v0.compareTo(v1) >= 0;
    };
  }

  /** Returns a Code that evaluates "andalso". */
  public static Code andAlso(Code code0, Code code1) {
    // Lazy evaluation. If code0 returns false, code1 is never evaluated.
    return env -> (boolean) code0.eval(env) && (boolean) code1.eval(env);
  }

  /** Returns a Code that evaluates "orelse". */
  public static Code orElse(Code code0, Code code1) {
    // Lazy evaluation. If code0 returns true, code1 is never evaluated.
    return env -> (boolean) code0.eval(env) || (boolean) code1.eval(env);
  }

  /** Returns a Code that evaluates "+". */
  public static Code plus(Code code0, Code code1) {
    return env -> (int) code0.eval(env) + (int) code1.eval(env);
  }

  /** Returns a Code that evaluates "-". */
  public static Code minus(Code code0, Code code1) {
    return env -> (int) code0.eval(env) - (int) code1.eval(env);
  }

  /** Returns a Code that evaluates "*". */
  public static Code times(Code code0, Code code1) {
    return env -> (int) code0.eval(env) * (int) code1.eval(env);
  }

  /** Returns a Code that evaluates "/". */
  public static Code divide(Code code0, Code code1) {
    return env -> (int) code0.eval(env) / (int) code1.eval(env);
  }

  /** Returns a Code that evaluates "div". */
  public static Code div(Code code0, Code code1) {
    return env -> Math.floorDiv((int) code0.eval(env), (int) code1.eval(env));
  }

  /** Returns a Code that evaluates "mod". */
  public static Code mod(Code code0, Code code1) {
    return env -> Math.floorMod((int) code0.eval(env), (int) code1.eval(env));
  }

  /** Returns a Code that evaluates "^" (string concatenation). */
  public static Code caret(Code code0, Code code1) {
    return env -> ((String) code0.eval(env)) + ((String) code1.eval(env));
  }

  /** Returns a Code that returns the value of variable "name" in the current
   * environment. */
  public static Code get(String name) {
    return env -> env.get(name);
  }

  public static Code let(List<Code> fnCodes, Code argCode) {
    if (fnCodes.size() == 1) {
      // Use a more efficient runtime path if the list has only one element.
      // The effect is the same.
      final Code fnCode = Iterables.getOnlyElement(fnCodes);
      return env -> {
        final Closure fnValue = (Closure) fnCode.eval(env);
        EvalEnv env2 = fnValue.evalBind(env.copy());
        return argCode.eval(env2);
      };
    } else {
      return env -> {
        EvalEnv env2 = env.copy();
        for (Code fnCode : fnCodes) {
          final Closure fnValue = (Closure) fnCode.eval(env);
          env2 = fnValue.evalBind(env2);
        }
        return argCode.eval(env2);
      };
    }
  }

  /** Generates the code for applying a function (or function value) to an
   * argument. */
  public static Code apply(Code fnCode, Code argCode) {
    return env -> {
      final Closure fnValue = (Closure) fnCode.eval(env);
      final Object argValue = argCode.eval(env);
      return fnValue.bindEval(argValue);
    };
  }

  public static Code ifThenElse(Code condition, Code ifTrue,
      Code ifFalse) {
    return env -> {
      final boolean b = (Boolean) condition.eval(env);
      return (b ? ifTrue : ifFalse).eval(env);
    };
  }

  public static Code tuple(List<Code> codes) {
    return env -> {
      final Object[] values = new Object[codes.size()];
      for (int i = 0; i < values.length; i++) {
        values[i] = codes.get(i).eval(env);
      }
      return Arrays.asList(values);
    };
  }

  /** Returns a code that returns the {@code slot}th field of a tuple or
   * record. */
  public static Code nth(int slot) {
    final Ast.IdPat pat = AstBuilder.INSTANCE.idPat(Pos.ZERO, "x");
    final Code code = env -> {
      final List values = (List) env.get("x");
      return values.get(slot);
    };
    final ImmutableList<Pair<Ast.Pat, Code>> patCodes =
        ImmutableList.of(Pair.of(pat, code));
    return env -> new Closure(env, patCodes);
  }

  /** Returns a code that negates a boolean value. */
  public static Code not(Code arg) {
    return env -> !(Boolean) arg.eval(env);
  }

  /** Creates an empty evaluation environment. */
  public static EvalEnv emptyEnv() {
    final EvalEnv env = new EvalEnv();
    final AstBuilder ast = AstBuilder.INSTANCE;
    env.valueMap.put("true", true);
    env.valueMap.put("false", false);
    env.valueMap.put("not",
        new Closure(env,
            ImmutableList.of(
                Pair.of(ast.idPat(Pos.ZERO, "x"), not(get("x"))))));
    return env;
  }

  /** Creates an evaluation environment that is the same as a given evaluation
   * environment, plus one more variable. */
  public static EvalEnv add(EvalEnv env, String var, Object value) {
    // Copying the entire table is not very efficient.
    final EvalEnv env2 = env.copy();
    env2.valueMap.put(var, value);
    return env2;
  }
}

// End Codes.java
