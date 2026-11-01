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

  /**
   * Registers a (name, ordinal) combination, and returns how many occurrences
   * of the same name with a different ordinal have been seen before.
   *
   * <p>For example:
   *
   * <ul>
   *   <li>register("a", 0) returns 0;
   *   <li>register("b", 3) returns 0;
   *   <li>register("b", 2) returns 1;
   *   <li>register("b", 3) returns 0;
   *   <li>register("a", 42) returns 1.
   * </ul>
   *
   * @see net.hydromatic.morel.ast.Core.Pat#describe(Describer)
   */
  int register(String name, int i);

  /**
   * Renumbers a name that the compiler generated, such as {@code v$3727}, by
   * first occurrence, so that a plan's text does not depend on what was
   * compiled before it. Each prefix is numbered in its own sequence; a name
   * that the compiler did not generate is returned unchanged.
   *
   * <p>For example, if called with "v$7", "t$4", "v$3", "v$7", "x" returns
   * "v$0", "t$0", "v$1", "v$0", "x".
   */
  String rename(String name);

  /** Provided as a callback while describing a node. */
  interface Detail {
    /** Prints an atomic argument. */
    Detail arg(String name, Object value);

    /** Warns that the argument is not atomic. */
    @Deprecated
    default Detail arg(String name, Iterable<?> value) {
      return args(name, value);
    }

    /** Prints a collection-valued argument. */
    Detail args(String name, Iterable<?> value);

    /** Prints a complex argument. */
    Detail arg(String name, Describable describable);

    /** Prints an argument if the condition is true. */
    default Detail argIf(
        String name, Describable describable, boolean condition) {
      return condition ? arg(name, describable) : this;
    }
  }
}

// End Describer.java
