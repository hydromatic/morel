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

import net.hydromatic.morel.compile.BuiltIn;

/**
 * Abstract implementation of {@link Applicable} that describes itself with a
 * constant name.
 */
abstract class ApplicableImpl implements Applicable {
  protected final BuiltIn builtIn;

  /** Creates an ApplicableImpl that directly implements a BuiltIn. */
  protected ApplicableImpl(BuiltIn builtIn) {
    this.builtIn = builtIn;
  }

  protected String name() {
    return builtIn.mlName.startsWith("op ")
        ? builtIn.mlName.substring("op ".length())
        : builtIn.structure + "." + builtIn.mlName;
  }

  @Override
  public String toString() {
    return name();
  }

  @Override
  public Describer describe(Describer describer) {
    return describer.start(name(), d -> {});
  }
}

// End ApplicableImpl.java
