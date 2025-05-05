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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.compile.Environment;

/**
 * Evaluation environment.
 *
 * <p>Whereas {@link Environment} contains both types and values, because it is
 * used for validation/compilation, EvalEnv contains only values.
 */
public interface EvalEnv {

  /** The name of the variable that contains the {@link Session}. */
  String SESSION = "$session";

  /** Returns the binding of {@code name} if bound, null if not. */
  Object getOpt(String name);

  /**
   * Creates an environment that has the same content as this one, plus the
   * binding (name, value).
   */
  default EvalEnv bind(String name, Object value) {
    return new EvalEnvs.SubEvalEnv(this, name, value);
  }

  /**
   * Creates an evaluation environment that has the same content as this one,
   * plus a mutable slot.
   */
  default MutableEvalEnv bindMutable(String name) {
    return new EvalEnvs.MutableSubEvalEnv(this, name);
  }

  /**
   * Creates an evaluation environment that has the same content as this one,
   * plus mutable slots for each name in a pattern.
   */
  default MutableEvalEnv bindMutablePat(Core.Pat pat) {
    if (pat instanceof Core.IdPat) {
      // Pattern is simple; use a simple implementation.
      return bindMutable(((Core.IdPat) pat).name);
    }
    final List<String> names = new ArrayList<>();
    pat.accept(
        new Visitor() {
          @Override
          protected void visit(Core.IdPat idPat) {
            names.add(idPat.name);
          }

          @Override
          protected void visit(Core.AsPat asPat) {
            names.add(asPat.name);
            super.visit(asPat);
          }
        });
    return new EvalEnvs.MutablePatSubEvalEnv(this, pat, names);
  }

  /**
   * Creates an evaluation environment that has the same content as this one,
   * plus a mutable slot or slots.
   *
   * <p>If {@code names} has one element, calling {@link
   * MutableEvalEnv#set(Object)} will populate the slot will be filled by an
   * object; if {@code names} has more than one element, {@code set} will expect
   * to be given an array with the same number of elements.
   */
  default MutableEvalEnv bindMutableArray(List<String> names) {
    if (names.size() == 1) {
      return bindMutable(names.get(0));
    }
    return new EvalEnvs.MutableArraySubEvalEnv(this, names);
  }

  /**
   * Creates an evaluation environment that has the same content as this one,
   * plus a mutable slot or slots.
   */
  default MutableEvalEnv bindMutableList(List<String> names) {
    if (names.size() == 1) {
      return bindMutable(names.get(0));
    }
    return new EvalEnvs.MutableListSubEvalEnv(this, names);
  }

  /**
   * Visits every variable binding in this environment.
   *
   * <p>Bindings that are obscured by more recent bindings of the same name are
   * visited, but after the more obscuring bindings.
   */
  void visit(BiConsumer<String, Object> consumer);

  /** Returns a map of the values and bindings. */
  default Map<String, Object> valueMap() {
    final Map<String, Object> valueMap = new HashMap<>();
    visit(valueMap::putIfAbsent);
    return valueMap;
  }

  /** Converts this environment to a non-mutable environment. */
  default EvalEnv fix() {
    return this;
  }
}

// End EvalEnv.java
