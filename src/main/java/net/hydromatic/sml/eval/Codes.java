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
}

// End Codes.java
