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

import com.google.common.collect.ImmutableList;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.StringContains.containsString;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Tests the Shell. */
public class ShellTest {

  /** Creates a Fixture. */
  static Fixture fixture() {
    return new FixtureImpl(Fixture.DEFAULT_ARG_LIST, "?");
  }

  static void pauseForTenMilliseconds() {
    try {
      Thread.sleep(10);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  /** Tests {@link Shell} with empty input. */
  @Test void testShell() {
    final List<String> argList = Collections.singletonList("--system=false");
    fixture().withArgList(argList)
        .withInputString("")
        .assertOutput(containsString("morel version"));
  }

  /** Tests {@link Shell} with empty input and banner disabled. */
  @Test void testShellNoBanner() {
    fixture()
        .withInputString("")
        .assertOutput(containsString("- \r\r\n"));
  }

  /** Tests {@link Shell} with one line. */
  @Test void testOneLine() {
    final String in = "1 + 2;\n";
    final String expected = "1 + 2;\r\n"
        + "- 1 + 2;\r\r\n"
        + "\u001B[?2004lval it = 3 : int\r\n"
        + "- \r\r\n"
        + "\u001B[?2004l";
    fixture().withInputString(in).assertOutput(is(expected));
  }

  /** Tests {@link Shell} with a continued line. */
  @Test void testTwoLines() {
    final String in = "1 +\n"
        + "2;\n";
    final String expected = "1 +\r\n"
        + "2;\r\n"
        + "- 1 +\r\r\n"
        + "\u001B[?2004l= 2;\r\r\n"
        + "\u001B[?2004lval it = 3 : int\r\n"
        + "- \r\r\n"
        + "\u001B[?2004l";
    fixture().withInputString(in).assertOutput(is(expected));
  }

  /** Tests {@link Shell} with a line that is a comment, another that is empty,
   *  and another that has only a semicolon; all are treated as empty. */
  @Test void testEmptyLines() {
    final String in = "(* a comment followed by empty *)\n"
        + "\n"
        + ";\n";
    final String expected = "(* a comment followed by empty *)\r\n"
        + "\r\n"
        + ";\r\n"
        + "- (* a comment followed by empty *)\r\r\n"
        + "\u001B[?2004l- \r\r\n"
        + "\u001B[?2004l- ;\r\r\n"
        + "\u001B[?2004l- \r\r\n"
        + "\u001B[?2004l";
    fixture().withInputString(in).assertOutput(is(expected));
  }

  /** Tests {@link Shell} with a single-line comment. */
  @Test void testSingleLineComment() {
    final String in = "(*) line comment\n"
        + "1 + 2;\n";
    final String expected = "(*) line comment\r\n"
        + "1 + 2;\r\n"
        + "- (*) line comment\r\r\n"
        + "\u001B[?2004l- 1 + 2;\r\r\n"
        + "\u001B[?2004lval it = 3 : int\r\n"
        + "- \r\r\n"
        + "\u001B[?2004l";
    fixture().withInputString(in).assertOutput(is(expected));
  }

  /** Tests {@link Shell} with a single-line comment that contains a quote. */
  @Test void testSingleLineCommentWithQuote() {
    final String in = "(*) it's a single-line comment with a quote\n"
        + "2 + 3;\n";
    final String expected = "(*) it's a single-line comment with a quote\r\n"
        + "2 + 3;\r\n"
        + "- (*) it's a single-line comment with a quote\r\r\n"
        + "\u001B[?2004l- 2 + 3;\r\r\n"
        + "\u001B[?2004lval it = 5 : int\r\n"
        + "- \r\r\n"
        + "\u001B[?2004l";
    fixture().withInputString(in).assertOutput(is(expected));
  }

  /** Tests {@link Shell} with {@code let} statement spread over multiple
   * lines. */
  @Test void testMultiLineLet() {
    final String in = "let\n"
        + "  val x = 1\n"
        + "in\n"
        + "  x + 2\n"
        + "end;\n";
    final String expected = "let\r\n"
        + "  val x = 1\r\n"
        + "in\r\n"
        + "  x + 2\r\n"
        + "end;\r\n"
        + "- let\r\r\n"
        + "\u001B[?2004l=   val x = 1\r\r\n"
        + "\u001B[?2004l= in\r\r\n"
        + "\u001B[?2004l=   x + 2\r\r\n"
        + "\u001B[?2004l= end;\r\r\n"
        + "\u001B[?2004lval it = 3 : int\r\n"
        + "- \r\r\n"
        + "\u001B[?2004l";
    fixture().withInputString(in).assertOutput(is(expected));
  }

  /** Fixture for testing the shell.
   *
   * @see #fixture */
  interface Fixture {
    ImmutableList<String> DEFAULT_ARG_LIST =
        ImmutableList.of("--prompt=false", "--system=false", "--banner=false",
            "--terminal=dumb");

    List<String> argList();

    Fixture withArgList(List<String> argList);

    String inputString();

    Fixture withInputString(String inputString);

    @SuppressWarnings("UnusedReturnValue")
    default Fixture assertOutput(Matcher<String> matcher) {
      try {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final ByteArrayInputStream bais =
            new ByteArrayInputStream(inputString().getBytes(UTF_8));
        final Shell.Config config = Shell.parse(Shell.Config.DEFAULT, argList())
            .withPauseFn(ShellTest::pauseForTenMilliseconds);
        final Shell shell = Shell.create(config, bais, baos);
        shell.run();
        final String outString = baos.toString(UTF_8.name());
        assertThat(outString, matcher);
        return this;
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /** Implementation of Fixture. */
  private static class FixtureImpl implements Fixture {
    final ImmutableList<String> argList;
    final String inputString;

    FixtureImpl(ImmutableList<String> argList, String inputString) {
      this.argList = requireNonNull(argList, "argList");
      this.inputString = requireNonNull(inputString, "inputString");
    }

    @Override public List<String> argList() {
      return argList;
    }

    @Override public Fixture withArgList(List<String> argList) {
      return this.argList.equals(argList) ? this
          : new FixtureImpl(ImmutableList.copyOf(argList), inputString);
    }

    @Override public String inputString() {
      return inputString;
    }

    @Override public Fixture withInputString(String inputString) {
      return this.inputString.equals(inputString) ? this
          : new FixtureImpl(ImmutableList.copyOf(argList), inputString);
    }
  }
}

// End ShellTest.java
