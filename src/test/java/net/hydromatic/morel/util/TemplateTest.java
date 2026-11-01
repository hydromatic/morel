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
package net.hydromatic.morel.util;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/** Tests {@link Template}. */
public class TemplateTest {
  /**
   * Tests the templates that the Spark function table holds for `div` and
   * `mod`, whose whole purpose is that Spark's operators round the other way.
   */
  @Test
  void testRewriteTemplates() {
    assertThat(
        Template.of("cast(floor({0} / {1}) as int)").expand("e.empno", "7"),
        is("cast(floor(e.empno / 7) as int)"));
    assertThat(
        Template.of("(({0} % {1} + {1}) % {1})").expand("e.empno", "7"),
        is("((e.empno % 7 + 7) % 7)"));
  }

  /** Tests a template that takes its arguments in the other order. */
  @Test
  void testReordered() {
    assertThat(
        Template.of("startswith({1}, {0})").expand("'Fred'", "e.ename"),
        is("startswith(e.ename, 'Fred')"));
  }

  /** Tests that a template is given the number of arguments it asks for. */
  @Test
  void testWrongArgCount() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Template.of("element_at({0}, 1)").expand("xs", "ignored"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Template.of("startswith({1}, {0})").expand("x"));
  }

  /** Tests a template that does not use every argument it is given. */
  @Test
  void testArgumentNotUsed() {
    assertThat(
        Template.of("element_at({0}, 1)").expand("xs"),
        is("element_at(xs, 1)"));
  }

  /** Tests a template that uses no arguments at all. */
  @Test
  void testNoArguments() {
    assertThat(Template.of("current_date()").expand(), is("current_date()"));
  }

  /** Tests a literal brace. */
  @Test
  void testEscapedBrace() {
    assertThat(Template.of("f({0}, '{{}')").expand("x"), is("f(x, '{}')"));
  }

  /**
   * Tests that the count comes from the greatest placeholder, so that a
   * template which skips an argument still counts it.
   */
  @Test
  void testArgCount() {
    assertThat(Template.of("cast({0} as int)").argCount(), is(1));
    assertThat(Template.of("substring({0}, {1} + 1, {2})").argCount(), is(3));
    assertThat(Template.of("startswith({1}, {0})").argCount(), is(2));
    assertThat(Template.of("element_at({0}, 1)").argCount(), is(1));
    assertThat(Template.of("current_date()").argCount(), is(0));
  }

  /** Tests that a malformed template is rejected, not guessed at. */
  @Test
  void testMalformed() {
    assertThrows(
        IllegalArgumentException.class, () -> Template.of("f({0").expand("x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Template.of("f({x})").expand("x"));
  }

  /**
   * Tests which arguments the expander asks for, and in what order: it asks as
   * it meets them, so an argument used twice is asked for twice and one the
   * template does not use is never asked for at all. That is what lets a caller
   * render an argument lazily, and refuse one it cannot render only when the
   * template wants it.
   */
  @Test
  void testWhatIsAsked() {
    final List<Integer> asked = new ArrayList<>();
    final IntFunction<String> f =
        i -> {
          asked.add(i);
          return "a" + i;
        };
    assertThat(
        Template.of("(({0} % {1} + {1}) % {1})").expand(f),
        is("((a0 % a1 + a1) % a1)"));
    assertThat(asked, hasToString("[0, 1, 1, 1]"));

    asked.clear();
    assertThat(
        Template.of("startswith({1}, {0})").expand(f),
        is("startswith(a1, a0)"));
    assertThat(asked, hasToString("[1, 0]"));

    asked.clear();
    assertThat(
        Template.of("element_at({0}, 1)").expand(f), is("element_at(a0, 1)"));
    assertThat(asked, hasToString("[0]"));
  }
}

// End TemplateTest.java
