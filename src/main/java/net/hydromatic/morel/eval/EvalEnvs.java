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

import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.function.BiConsumer;

/** Helpers for {@link EvalEnv}. */
public class EvalEnvs {
  /** Creates an evaluation environment with the given (name, value) map. */
  public static EvalEnv copyOf(Map<String, Object> valueMap) {
    return new MapEvalEnv(valueMap);
  }

  private EvalEnvs() {}

  /** Evaluation environment that inherits from a parent environment and adds
   * one binding. */
  static class SubEvalEnv extends EvalEnv {
    private final EvalEnv parentEnv;
    private final String name;
    private final Object value;

    SubEvalEnv(EvalEnv parentEnv, String name, Object value) {
      this.parentEnv = parentEnv;
      this.name = name;
      this.value = value;
    }

    void visit(BiConsumer<String, Object> consumer) {
      consumer.accept(name, value);
      parentEnv.visit(consumer);
    }

    Object getOpt(String name) {
      for (SubEvalEnv e = this;;) {
        if (name.equals(e.name)) {
          return e.value;
        }
        if (e.parentEnv instanceof SubEvalEnv) {
          e = (SubEvalEnv) e.parentEnv;
        } else {
          return e.parentEnv.getOpt(name);
        }
      }
    }
  }

  /** Evaluation environment that reads from a map. */
  static class MapEvalEnv extends EvalEnv {
    final Map<String, Object> valueMap;

    MapEvalEnv(Map<String, Object> valueMap) {
      this.valueMap = ImmutableMap.copyOf(valueMap);
    }

    Object getOpt(String name) {
      return valueMap.get(name);
    }

    void visit(BiConsumer<String, Object> consumer) {
      valueMap.forEach(consumer);
    }
  }
}

// End EvalEnvs.java
