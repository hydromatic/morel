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

/** Describes a plan (tree of {@link Code} or {@link Applicable} objects). */
public interface Describer {
  Describer start(String name, Consumer<Detail> detail);

  /** Provided as a callback while describing a node. */
  interface Detail {
    Detail arg(String name, Object value);

    Detail arg(String name, Describable describable);

    default Detail argIf(
        String name, Describable describable, boolean condition) {
      return condition ? arg(name, describable) : this;
    }
  }
}

// End Describer.java
