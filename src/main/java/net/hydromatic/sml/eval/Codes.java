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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Helpers for {@link Code}. */
public abstract class Codes {
  private Codes() {}

  /** Returns a Code that evaluates to the same value in all environments. */
  public static Code constant(Comparable value) {
    return new Code() {
      public Object eval(EvalEnv env) {
        return value;
      }

      @Override public boolean isConstant() {
        return true;
      }
    };
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

  /** Returns a Code that evaluates "::" (list cons). */
  public static Code cons(Code code0, Code code1) {
    return env -> ImmutableList.builder().add(code0.eval(env))
        .addAll((Iterable) code1.eval(env)).build();
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
    assert !fnCode.isConstant(); // if constant, use "apply(Closure, Code)"
    return env -> {
      final Applicable fnValue = (Applicable) fnCode.eval(env);
      final Object argValue = argCode.eval(env);
      return fnValue.apply(env, argValue);
    };
  }

  /** Generates the code for applying a function value to an argument. */
  public static Code apply(Applicable fnValue, Code argCode) {
    return env -> {
      final Object argValue = argCode.eval(env);
      return fnValue.apply(env, argValue);
    };
  }

  public static Code ifThenElse(Code condition, Code ifTrue,
      Code ifFalse) {
    return env -> {
      final boolean b = (Boolean) condition.eval(env);
      return (b ? ifTrue : ifFalse).eval(env);
    };
  }

  public static Code list(List<Code> codes) {
    return tuple(codes);
  }

  public static Code tuple(List<Code> codes) {
    return new TupleCode(codes);
  }

  public static Code from(Map<Ast.Id, Code> sources, Code filterCode,
      Code yieldCode) {
    final ImmutableList<Ast.Id> ids = ImmutableList.copyOf(sources.keySet());
    return new Code() {
      @Override public Object eval(EvalEnv env) {
        final List<Iterable> values = new ArrayList<>();
        for (Code code : sources.values()) {
          values.add((Iterable) code.eval(env));
        }
        final List list = new ArrayList();
        loop(0, values, env, list);
        return list;
      }

      /** Generates the {@code i}th nested lopp of a cartesian product of the
       * values in {@code iterables}. */
      void loop(int i, List<Iterable> iterables, EvalEnv env,
          List<Object> list) {
        if (i == iterables.size()) {
          if ((Boolean) filterCode.eval(env)) {
            list.add(yieldCode.eval(env));
          }
        } else {
          final String name = ids.get(i).name;
          final Iterable iterable = iterables.get(i);
          for (Object o : iterable) {
            EvalEnv env2 = add(env, name, o);
            loop(i + 1, iterables, env2, list);
          }
        }
      }
    };
  }

  /** Returns a code that returns the {@code slot}th field of a tuple or
   * record. */
  public static Applicable nth(int slot) {
    assert slot >= 0;
    return (env, argValue) -> ((List) argValue).get(slot);
  }

  /** Returns an applicable that negates a boolean value. */
  public static Applicable not() {
    return (env, argValue) -> !(Boolean) argValue;
  }

  /** Returns an applicable that returns the absolute value of an int. */
  public static Applicable abs() {
    return (env, argValue) -> {
      final Integer integer = (Integer) argValue;
      return integer >= 0 ? integer : -integer;
    };
  }

  /** Creates an empty evaluation environment. */
  public static EvalEnv emptyEnv() {
    final EvalEnv env = new EvalEnv();
    env.valueMap.put("true", true);
    env.valueMap.put("false", false);
    env.valueMap.put("not", not());
    env.valueMap.put("abs", abs());
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

  public static Code negate(Code code) {
    return env -> -((Integer) code.eval(env));
  }

  /** A code that evaluates expressions and creates a tuple with the results.
   *
   * <p>An inner class so that we can pick apart the results of multiply
   * defined functions: {@code fun f = ... and g = ...}.
   */
  public static class TupleCode implements Code {
    public final List<Code> codes;

    TupleCode(List<Code> codes) {
      this.codes = codes;
    }

    public Object eval(EvalEnv env) {
      final Object[] values = new Object[codes.size()];
      for (int i = 0; i < values.length; i++) {
        values[i] = codes.get(i).eval(env);
      }
      return Arrays.asList(values);
    }
  }
}

// End Codes.java
