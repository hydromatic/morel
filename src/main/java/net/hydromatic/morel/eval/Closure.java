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

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.util.Pair.allMatch;
import static net.hydromatic.morel.util.Static.skip;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.util.ImmutablePairList;

/**
 * Value that is sufficient for a function to bind its argument and evaluate its
 * body.
 */
public class Closure implements Comparable<Closure>, Applicable, Applicable1 {
  /**
   * Environment for evaluation. Contains the variables "captured" from the
   * environment when the closure was created.
   */
  private final EvalEnv evalEnv;

  /**
   * A list of (pattern, code) pairs. During bind, the value being bound is
   * matched against each pattern. When a match is found, the code for that
   * pattern is used to evaluate.
   *
   * <p>For example, when applying {@code fn x => case x of 0 => "yes" | _ =>
   * "no"} to the value {@code 1}, the first pattern ({@code 0} fails) but the
   * second pattern ({@code _}) succeeds, and therefore we evaluate the second
   * code {@code "no"}.
   */
  private final ImmutablePairList<Core.Pat, Code> patCodes;

  private final Pos pos;

  /** Not a public API. */
  public Closure(
      EvalEnv evalEnv, ImmutablePairList<Core.Pat, Code> patCodes, Pos pos) {
    this.evalEnv = requireNonNull(evalEnv).fix();
    this.patCodes = requireNonNull(patCodes);
    this.pos = pos;
  }

  @Override
  public String toString() {
    return "Closure(evalEnv = " + evalEnv + ", patCodes = " + patCodes + ")";
  }

  public int compareTo(Closure o) {
    return 0;
  }

  /**
   * Binds an argument value to create a new environment for a closure.
   *
   * <p>When calling a simple function such as {@code (fn x => x + 1) 2}, the
   * binder sets just contains one variable, {@code x}, and the new environment
   * contains {@code x = 1}. If the function's parameter is a match, more
   * variables might be bound. For example, when you invoke {@code (fn (x, y) =>
   * x + y) (3, 4)}, the binder sets {@code x} to 3 and {@code y} to 4.
   */
  EvalEnv bind(Object argValue) {
    final EvalEnvHolder envRef = new EvalEnvHolder(evalEnv);
    for (Core.Pat pat : patCodes.leftList()) {
      if (bindRecurse(pat, argValue, envRef)) {
        return envRef.env;
      }
    }
    throw new AssertionError("no match");
  }

  /** Similar to {@link #bind}, but evaluates an expression first. */
  EvalEnv evalBind(EvalEnv env) {
    final EvalEnvHolder envRef = new EvalEnvHolder(env);
    for (Map.Entry<Core.Pat, Code> patCode : patCodes) {
      final Object argValue = patCode.getValue().eval(env);
      final Core.Pat pat = patCode.getKey();
      if (bindRecurse(pat, argValue, envRef)) {
        return envRef.env;
      }
    }
    throw new AssertionError("no match");
  }

  /** Similar to {@link #bind}, but also evaluates. */
  Object bindEval(Object argValue) {
    final EvalEnvHolder envRef = new EvalEnvHolder(evalEnv);
    for (Map.Entry<Core.Pat, Code> patCode : patCodes) {
      final Core.Pat pat = patCode.getKey();
      if (bindRecurse(pat, argValue, envRef)) {
        final Code code = patCode.getValue();
        return code.eval(envRef.env);
      }
    }
    throw new Codes.MorelRuntimeException(Codes.BuiltInExn.BIND, pos);
  }

  @Override
  public Object apply(EvalEnv env, Object argValue) {
    return bindEval(argValue);
  }

  @Override
  public Object apply(Object argValue) {
    return bindEval(argValue);
  }

  @Override
  public Describer describe(Describer describer) {
    return describer.start("closure", d -> {});
  }

  /**
   * Attempts to bind a value to a pattern. Returns whether it has succeeded in
   * matching the whole pattern.
   *
   * <p>Each time it matches a name, calls a consumer. It's possible that the
   * consumer is called a few times even if the whole pattern ultimately fails
   * to match.
   */
  public static boolean bindRecurse(
      Core.Pat pat, Object argValue, BiConsumer<Core.NamedPat, Object> envRef) {
    final List<Object> listValue;
    final Core.LiteralPat literalPat;
    switch (pat.op) {
      case ID_PAT:
        final Core.IdPat idPat = (Core.IdPat) pat;
        envRef.accept(idPat, argValue);
        return true;

      case WILDCARD_PAT:
        return true;

      case AS_PAT:
        final Core.AsPat asPat = (Core.AsPat) pat;
        envRef.accept(asPat, argValue);
        return bindRecurse(asPat.pat, argValue, envRef);

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
        return ((BigDecimal) literalPat.value).doubleValue()
            == (Double) argValue;

      case TUPLE_PAT:
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        listValue = (List) argValue;
        return allMatch(
            tuplePat.args,
            listValue,
            (pat1, value) -> bindRecurse(pat1, value, envRef));

      case RECORD_PAT:
        final Core.RecordPat recordPat = (Core.RecordPat) pat;
        listValue = (List) argValue;
        return allMatch(
            recordPat.args,
            listValue,
            (pat1, value) -> bindRecurse(pat1, value, envRef));

      case LIST_PAT:
        final Core.ListPat listPat = (Core.ListPat) pat;
        listValue = (List) argValue;
        if (listValue.size() != listPat.args.size()) {
          return false;
        }
        return allMatch(
            listPat.args,
            listValue,
            (pat1, value) -> bindRecurse(pat1, value, envRef));

      case CONS_PAT:
        final Core.ConPat consPat = (Core.ConPat) pat;
        @SuppressWarnings("unchecked")
        final List<Object> consValue = (List) argValue;
        if (consValue.isEmpty()) {
          return false;
        }
        final Object head = consValue.get(0);
        final List<Object> tail = skip(consValue);
        List<Core.Pat> patArgs = ((Core.TuplePat) consPat.pat).args;
        return bindRecurse(patArgs.get(0), head, envRef)
            && bindRecurse(patArgs.get(1), tail, envRef);

      case CON0_PAT:
        final Core.Con0Pat con0Pat = (Core.Con0Pat) pat;
        final List con0Value = (List) argValue;
        return con0Value.get(0).equals(con0Pat.tyCon);

      case CON_PAT:
        final Core.ConPat conPat = (Core.ConPat) pat;
        final List conValue = (List) argValue;
        return conValue.get(0).equals(conPat.tyCon)
            && bindRecurse(conPat.pat, conValue.get(1), envRef);

      default:
        throw new AssertionError("cannot compile " + pat.op + ": " + pat);
    }
  }

  /**
   * Callback for {@link #bindRecurse(Core.Pat, Object, BiConsumer)} that
   * modifies an environment.
   */
  private static class EvalEnvHolder
      implements BiConsumer<Core.NamedPat, Object> {
    EvalEnv env;

    EvalEnvHolder(EvalEnv env) {
      this.env = env;
    }

    @Override
    public void accept(Core.NamedPat namedPat, Object o) {
      env = env.bind(namedPat.name, o);
    }
  }
}

// End Closure.java
