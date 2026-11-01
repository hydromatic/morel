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
import java.util.Arrays;
import java.util.Map;
import net.hydromatic.morel.compile.BuiltIn;
import org.jspecify.annotations.Nullable;

/** Definitions of Morel built-in exceptions. */
public enum BuiltInExn {
  // lint: sort until '##public ' where '##[A-Z]'
  BIND(
      "General", BuiltIn.Constructor.EXN_BIND, "nonexhaustive binding failure"),
  CHR("General", BuiltIn.Constructor.EXN_CHR, null),
  CONSTRAINT("General", BuiltIn.Constructor.EXN_CONSTRAINT, null),
  DATE("Date", BuiltIn.Constructor.EXN_DATE, null),
  DIV("General", BuiltIn.Constructor.EXN_DIV, "divide by zero"),
  DOMAIN("General", BuiltIn.Constructor.EXN_DOMAIN, "domain error"),
  EMPTY("List", BuiltIn.Constructor.EXN_EMPTY, null),
  ERROR("Interact", BuiltIn.Constructor.EXN_ERROR, null),
  EVAL_ONLY(
      "Interact",
      BuiltIn.Constructor.EXN_EVAL_ONLY,
      "use is not available in this environment"),
  FAIL("General", BuiltIn.Constructor.EXN_FAIL, null),
  MATCH(
      "General", BuiltIn.Constructor.EXN_MATCH, "nonexhaustive match failure"),
  OPTION("Option", BuiltIn.Constructor.EXN_OPTION, null),
  OVERFLOW("General", BuiltIn.Constructor.EXN_OVERFLOW, "overflow"),
  SIZE("General", BuiltIn.Constructor.EXN_SIZE, "size"),
  SPAN("General", BuiltIn.Constructor.EXN_SPAN, null),
  SPARK("Spark", BuiltIn.Constructor.EXN_SPARK, null),
  SUBSCRIPT(
      "General", BuiltIn.Constructor.EXN_SUBSCRIPT, "subscript out of bounds"),
  TIME("Time", BuiltIn.Constructor.EXN_TIME, null),
  UNEQUAL_LENGTHS("ListPair", BuiltIn.Constructor.EXN_UNEQUAL_LENGTHS, null),
  UNORDERED("IEEEReal", BuiltIn.Constructor.EXN_UNORDERED, null);

  private static final Map<String, BuiltInExn> BY_ML_NAME =
      Arrays.stream(BuiltInExn.values())
          .collect(
              ImmutableMap.toImmutableMap(
                  BuiltInExn::mlName, e -> e, (a, b) -> a));

  /** Returns the BuiltInExn whose ML-level name is {@code name}, or null. */
  public static @Nullable BuiltInExn forMlName(String name) {
    return BY_ML_NAME.get(name);
  }

  public final String structure;
  public final BuiltIn.Constructor constructor;
  public final @Nullable String description;

  BuiltInExn(
      String structure,
      BuiltIn.Constructor constructor,
      @Nullable String description) {
    this.structure = structure;
    this.constructor = constructor;
    this.description = description;
  }

  /** The exception's ML-level name, taken from its {@link #constructor}. */
  public String mlName() {
    return constructor.constructor;
  }
}

// End BuiltInExn.java
