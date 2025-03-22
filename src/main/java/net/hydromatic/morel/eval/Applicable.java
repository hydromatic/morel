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

/**
 * A compiled expression that can be evaluated by applying to an argument.
 *
 * <p>Similar to {@link Code} but more efficient, because it does not require
 * creating a new runtime environment.
 */
public interface Applicable extends Describable {
  Object apply(EvalEnv env, Object argValue);

  /**
   * Converts this Applicable to a Code that has similar effect (but is less
   * efficient).
   */
  default Code asCode() {
    return new Code() {
      @Override
      public Describer describe(Describer describer) {
        return describer.start(
            "code2", d -> d.arg("applicable", Applicable.this));
      }

      @Override
      public Object eval(EvalEnv env) {
        return Applicable.this;
      }

      @Override
      public boolean isConstant() {
        return true;
      }
    };
  }
}

// End Applicable.java
