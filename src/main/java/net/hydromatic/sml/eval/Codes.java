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

import net.hydromatic.sml.type.Binding;
import net.hydromatic.sml.type.Type;

import java.util.List;

/** Helpers for {@link Code}. */
public abstract class Codes {
  private Codes() {}

  /** Returns a Code that evaluates to the same value in all environments. */
  public static Code constant(Comparable value) {
    return env -> value;
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

  /** Returns a Code that evaluates "^". */
  public static Code power(Code code0, Code code1) {
    return env -> (int) code0.eval(env) ^ (int) code1.eval(env);
  }

  /** Returns a Code that returns the value of variable "name" in the current
   * environment. */
  public static Code get(String name) {
    return env -> {
      final Binding binding = env.get(name);
      return binding.value;
    };
  }

  public static Code let(List<NameTypeCode> varCodes, Code e) {
    return env -> {
      for (NameTypeCode varCode : varCodes) {
        final Object value = varCode.code.eval(env);
        env = Environments.add(env, varCode.name, varCode.type, value);
      }
      return e.eval(env);
    };
  }

  public static Code fn(String paramName, Type paramType, Code code) {
    // Evaluating a function returns a function value, regardless of the
    // environment. The interesting stuff happens in apply.
    return env -> new FunctionValue(paramName, paramType, code, env);
  }

  /** Generates the code for applying a function (or function value) to an
   * argument. */
  public static Code apply(Code fnCode, Code argCode) {
    return env -> {
      final FunctionValue fnValue = (FunctionValue) fnCode.eval(env);
      final Object argValue = argCode.eval(env);
      final Environment env2 =
          Environments.add(fnValue.env, fnValue.paramName, fnValue.paramType,
              argValue);
      return fnValue.resultCode.eval(env2);
    };
  }

  /** A (name, type, code) triple. */
  public static class NameTypeCode {
    public final String name;
    public final Type type;
    public final Code code;

    public NameTypeCode(String name, Type type, Code code) {
      this.name = name;
      this.type = type;
      this.code = code;
    }
  }

  /** Value that is sufficient for a function to bind its argument
   * and evalute its body. */
  private static class FunctionValue implements Comparable<FunctionValue> {
    public final String paramName;
    public final Type paramType;
    public final Code resultCode;
    public final Environment env;

    private FunctionValue(String paramName, Type paramType, Code resultCode,
        Environment env) {
      this.paramName = paramName;
      this.paramType = paramType;
      this.resultCode = resultCode;
      this.env = env;
    }

    public int compareTo(FunctionValue o) {
      return 0;
    }
  }
}

// End Codes.java
