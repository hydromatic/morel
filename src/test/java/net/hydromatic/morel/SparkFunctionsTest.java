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
package net.hydromatic.morel;

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.Ml.ml;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.hydromatic.morel.compile.BuiltIn;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.jupiter.api.Test;

/**
 * Tests that the Spark translator's function table, {@code
 * net/hydromatic/morel/spark/functions.sml}, is well-formed: it is a Morel
 * expression, a list of records; every record names a built-in that exists;
 * every kind is one of the known kinds; and a rewritten entry's template uses
 * its arguments.
 */
public class SparkFunctionsTest {
  private static final Set<String> KINDS =
      ImmutableSet.of(
          "direct", "rewritten", "aggregate", "subquery", "unsupported");

  /** The record's fields, in label order. */
  private static final int DIVERGENCE = 0;

  private static final int KIND = 1;
  private static final int MOREL = 2;
  private static final int SPARK = 3;

  static String source() throws IOException {
    return Resources.toString(
        requireNonNull(
            SparkFunctionsTest.class.getResource(
                "/net/hydromatic/morel/spark/functions.sml")),
        StandardCharsets.UTF_8);
  }

  @Test
  void testFunctionTable() throws IOException {
    final AtomicReference<Object> ref = new AtomicReference<>();
    ml(source())
        .assertEval(
            new TypeSafeMatcher<Object>() {
              @Override
              protected boolean matchesSafely(Object o) {
                ref.set(o);
                return true;
              }

              @Override
              public void describeTo(Description description) {
                description.appendText("any value");
              }
            });
    @SuppressWarnings("unchecked")
    final List<List<Object>> entries =
        (List<List<Object>>) requireNonNull(ref.get());
    assertThat(entries.size() > 50, is(true));

    final Set<String> seen = new HashSet<>();
    for (List<Object> entry : entries) {
      assertThat(entry.size(), is(4));
      final String morel = (String) entry.get(MOREL);
      final String kind = (String) entry.get(KIND);
      final String spark = (String) entry.get(SPARK);
      final String divergence = (String) entry.get(DIVERGENCE);
      assertThat("duplicate " + morel, seen.add(morel), is(true));
      assertThat("kind of " + morel, KINDS.contains(kind), is(true));
      assertThat("built-in " + morel + " exists", exists(morel), is(true));
      switch (kind) {
        case "direct":
          assertThat("spark name of " + morel, spark.isEmpty(), is(false));
          assertThat(
              "spark name of " + morel + " is a name, not a template",
              spark.contains("$"),
              is(false));
          break;
        case "rewritten":
          assertThat(
              "template of " + morel + " uses {0}",
              spark.contains("{0}"),
              is(true));
          break;
        case "aggregate":
        case "subquery":
          assertThat("spark name of " + morel, spark.isEmpty(), is(false));
          break;
        default:
          assertThat("unsupported " + morel + " has no spark", spark, is(""));
          assertThat(
              "unsupported " + morel + " says why",
              divergence.isEmpty(),
              is(false));
      }
    }
  }

  /** Returns whether a name in the table denotes a built-in. */
  private static boolean exists(String morel) {
    final int dot = morel.indexOf('.');
    if (dot < 0) {
      return BuiltIn.BY_ML_NAME.containsKey(morel);
    }
    final String structureName = morel.substring(0, dot);
    final String memberName = morel.substring(dot + 1);
    if (structureName.equals("$")) {
      // An internal operator is absent from BY_STRUCTURE, by design, but the
      // table names it because it is what the core tree carries. Its own name
      // is inconsistent about the sigil -- "andalso" but "$current" -- so
      // either spelling denotes it.
      return Arrays.stream(BuiltIn.values())
          .anyMatch(
              b ->
                  b.structure.equals("$")
                      && (b.mlName.equals(memberName)
                          || b.mlName.equals("$" + memberName)));
    }
    final BuiltIn.Structure structure = BuiltIn.BY_STRUCTURE.get(structureName);
    return structure != null && structure.memberMap.containsKey(memberName);
  }
}

// End SparkFunctionsTest.java
