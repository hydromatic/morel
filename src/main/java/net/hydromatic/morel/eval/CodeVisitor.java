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

import java.util.function.Consumer;

/** Implementation of {@link Describer} that just traverses the tree. */
class CodeVisitor implements Describer {
  final Detail detail =
      new Detail() {
        @Override
        public Detail arg(String name, Object value) {
          return this;
        }

        @Override
        public Detail args(String name, Iterable<?> values) {
          for (Object o : values) {
            if (o instanceof Describable) {
              ((Describable) o).describe(CodeVisitor.this);
            }
          }
          return this;
        }

        @Override
        public Detail arg(String name, Describable describable) {
          describable.describe(CodeVisitor.this);
          return this;
        }
      };

  @Override
  public int register(String name, int i) {
    return 0;
  }

  @Override
  public Describer start(String name, Consumer<Detail> consumer) {
    consumer.accept(detail);
    return this;
  }
}

// End CodeVisitor.java
