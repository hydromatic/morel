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
package net.hydromatic.morel.spark;

import static java.util.Objects.requireNonNull;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import net.hydromatic.morel.spark.SparkFunctionTable.Entry;
import net.hydromatic.morel.spark.SparkFunctionTable.Kind;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link SparkFunctionTable} reads {@code functions.sml}.
 *
 * <p>{@code SparkFunctionsTest} tests the file's contents; this tests that the
 * translator can get at them.
 */
public class SparkFunctionTableTest {
  /** Tests that the table loads, and has the entries the file lists. */
  @Test
  void testLoad() {
    final SparkFunctionTable table = SparkFunctionTable.instance();
    assertThat(table.entries().size() > 50, is(true));
  }

  /** Tests the three kinds the first seed query needs. */
  @Test
  void testDirect() {
    final SparkFunctionTable table = SparkFunctionTable.instance();
    final Entry and = requireNonNull(table.get("Bool.andalso"));
    assertThat(and.kind, is(Kind.DIRECT));
    assertThat(and.spark, is("and"));

    final Entry gt = requireNonNull(table.get("op >"));
    assertThat(gt.kind, is(Kind.DIRECT));
    assertThat(gt.spark, is(">"));

    final Entry times = requireNonNull(table.get("op *"));
    assertThat(times.kind, is(Kind.DIRECT));
    assertThat(times.spark, is("*"));
  }

  /** Tests a rewritten entry, whose Spark text is a template. */
  @Test
  void testRewritten() {
    final Entry div =
        requireNonNull(SparkFunctionTable.instance().get("op div"));
    assertThat(div.kind, is(Kind.REWRITTEN));
    assertThat(div.spark, is("cast(floor({0} / {1}) as int)"));
    // A rewritten entry carries its template, made when the table loaded.
    assertThat(requireNonNull(div.template).argCount(), is(2));
    assertThat(
        div.template.expand("e.empno", "7"),
        is("cast(floor(e.empno / 7) as int)"));
    // A divergence is the reason the template exists.
    assertThat(div.divergence.isEmpty(), is(false));
  }

  /**
   * Tests that a name the table does not mention is distinguishable from one it
   * rejects.
   */
  @Test
  void testAbsent() {
    assertThat(SparkFunctionTable.instance().get("op noSuchOp"), nullValue());
  }
}

// End SparkFunctionTableTest.java
