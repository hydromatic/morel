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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.TestUtils.findDirectory;
import static net.hydromatic.morel.TestUtils.plus;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.StringContains.containsString;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.hydromatic.morel.compile.Tracer;
import net.hydromatic.morel.compile.Tracers;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.foreign.ForeignValue;
import org.hamcrest.Matcher;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

/** Tests the Shell. */
public class ShellTest {

  /** Creates a Fixture. */
  static Fixture fixture() {
    return new FixtureImpl(Fixture.DEFAULT_ARG_LIST, "?", false, new File(""));
  }

  static File getUseDirectory() {
    final File rootDirectory = findDirectory();
    return new File(rootDirectory, "use");
  }

  /** Tests {@link Shell} with empty input. */
  @Test
  void testShell() {
    final List<String> argList = Collections.singletonList("--system=false");
    fixture()
        .withArgList(argList)
        .withInputString("")
        .assertOutput(containsString("morel-java version"));
  }

  /**
   * Builds a terminal that reads from empty input, so its OSC 11 background
   * query gets no reply. The terminal's type is {@code type} (e.g. {@code
   * "xterm"} for a color terminal or {@code "dumb"} for none).
   */
  private static Terminal terminal(String type) throws IOException {
    return TerminalBuilder.builder()
        .system(false)
        .type(type)
        .streams(
            new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
        .build();
  }

  /**
   * Tests {@link Shell#queryTerminalBackground(Terminal, String, String,
   * String)}, the environment-free method that decides the terminal's
   * background color. The terminal here never answers the OSC 11 query, so the
   * result comes from the {@code noColor}/{@code term}/{@code colorFgBg}
   * arguments and the terminal type.
   */
  @Test
  void testQueryTerminalBackground() throws IOException {
    final Terminal xterm = terminal("xterm");
    // NO_COLOR set: color is disabled, regardless of COLORFGBG.
    assertThat(
        Shell.queryTerminalBackground(xterm, "1", null, "0;15"), nullValue());
    // TERM=dumb: color is disabled.
    assertThat(
        Shell.queryTerminalBackground(xterm, null, "dumb", "0;15"),
        nullValue());
    // A dumb terminal: color is disabled, even though TERM is not "dumb".
    assertThat(
        Shell.queryTerminalBackground(terminal("dumb"), null, "xterm", "0;15"),
        nullValue());
    // No OSC reply; COLORFGBG background is 15 (bright white), so light.
    assertThat(
        Shell.queryTerminalBackground(xterm, null, "xterm", "0;15"),
        is("rgb:ffff/ffff/ffff"));
    // COLORFGBG background is 7 (white), so light.
    assertThat(
        Shell.queryTerminalBackground(xterm, null, "xterm", "15;7"),
        is("rgb:ffff/ffff/ffff"));
    // COLORFGBG background is 0 (black), so dark.
    assertThat(
        Shell.queryTerminalBackground(xterm, null, "xterm", "15;0"),
        is("rgb:0000/0000/0000"));
    // COLORFGBG absent: default to dark.
    assertThat(
        Shell.queryTerminalBackground(xterm, null, "xterm", null),
        is("rgb:0000/0000/0000"));
    // COLORFGBG unparsable: default to dark.
    assertThat(
        Shell.queryTerminalBackground(xterm, null, "xterm", "bogus"),
        is("rgb:0000/0000/0000"));
  }

  /** Tests {@link Shell} with -e (eval) option. */
  @Test
  void testEval() throws IOException {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    final ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);
    final Shell.Config config =
        Shell.parse(
            Shell.Config.DEFAULT,
            ImmutableList.of(
                "--system=false",
                "--terminal=dumb",
                "--banner=false",
                "-e",
                "from i in [1,2] yield i + 3"));
    final Shell shell = Shell.create(config, bais, baos);
    shell.run();
    final String outString = baos.toString(UTF_8.name()).replace("\r\n", "\n");
    assertThat(outString, is("val it = [4,5] : int list\n"));
  }

  /** Tests {@link Shell} with --eval= option. */
  @Test
  void testEvalEquals() throws IOException {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    final ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);
    final Shell.Config config =
        Shell.parse(
            Shell.Config.DEFAULT,
            ImmutableList.of(
                "--system=false",
                "--terminal=dumb",
                "--banner=false",
                "--eval=1 + 2"));
    final Shell shell = Shell.create(config, bais, baos);
    shell.run();
    final String outString = baos.toString(UTF_8.name()).replace("\r\n", "\n");
    assertThat(outString, is("val it = 3 : int\n"));
  }

  /** Tests {@link Shell} with empty input and banner disabled. */
  @Test
  void testShellNoBanner() {
    fixture().withInputString("").assertOutput(containsString("- \r\n"));
  }

  /** Tests {@link Shell} with one line. */
  @Test
  void testOneLine() {
    final String in = "1 + 2;\n";
    final String expected =
        "- 1 + 2;\r\n" //
            + "val it = 3 : int\n"
            + "- \r\n";
    fixture().withInputString(in).assertOutput(is(expected));
  }

  /** Tests {@link Shell} with a continued line. */
  @Test
  void testTwoLines() {
    final String in =
        "1 +\n" //
            + "2;\n";
    final String expected =
        "- 1 +\r\n" //
            + "= 2;\r\n"
            + "val it = 3 : int\n"
            + "- \r\n";
    fixture().withInputString(in).assertOutput(is(expected));
  }

  /**
   * Tests that a statement containing a type variable (e.g. {@code 'a}) is
   * recognized as complete. The single quote must not be treated as an opening
   * string quote, which would make the shell wait for a closing quote.
   */
  @Test
  void testTypeVariableIsComplete() {
    fixture()
        .withInputString("fn x: 'a => x;\n")
        .assertOutput(containsString("val it = fn : 'a -> 'a"));
  }

  /**
   * Tests {@link Shell} with a line that is a comment, another that is empty,
   * and another that has only a semicolon; all are treated as empty.
   */
  @Test
  void testEmptyLines() {
    final String in =
        "(* a comment followed by empty *)\n" //
            + "\n"
            + ";\n";
    final String expected =
        "- (* a comment followed by empty *)\r\n" //
            + "- \r\n"
            + "- ;\r\n"
            + "- \r\n";
    fixture().withInputString(in).assertOutput(is(expected));
  }

  /**
   * Tests that the shell writes each line of input once.
   *
   * <p>A terminal built over streams rather than a tty echoes the input from a
   * pump thread of its own. That thread and the one that renders the line the
   * reader has read both write to the output, in whatever order they are
   * scheduled, so the output was not deterministic: usually the echo came first
   * and the input appeared twice, but under load the pump could be descheduled
   * after a character or two and the two interleaved, which made these tests
   * fail once in a while. {@link Shell#create} now turns the echo off, leaving
   * one writer.
   */
  @Test
  void testInputWrittenOnce() {
    final String in =
        "1 + 2;\n" //
            + "3 + 4;\n";
    fixture()
        .withInputString(in)
        .assertOutput(
            is(
                "- 1 + 2;\r\n"
                    + "val it = 3 : int\n"
                    + "- 3 + 4;\r\n"
                    + "val it = 7 : int\n"
                    + "- \r\n"));
  }

  /**
   * Tests that a statement followed, on the same line, by a comment does not
   * swallow the statement that comes after it. The line does not end with
   * {@code ;}, so the buffer holds two statements when it is finally parsed,
   * and both must be evaluated.
   */
  @Test
  void testStatementFollowedByComment() {
    final String in =
        "val a = 1; (* a comment *)\n" //
            + "val b = 2;\n";
    final String expected =
        "- val a = 1; (* a comment *)\r\n" //
            + "val a = 1 : int\n"
            + "- val b = 2;\r\n"
            + "val b = 2 : int\n"
            + "- \r\n";
    fixture().withInputString(in).assertOutput(is(expected));
  }

  /**
   * Tests that a statement followed, on the same line, by trailing whitespace
   * does not swallow the statement that comes after it.
   */
  @Test
  void testStatementFollowedBySpaces() {
    final String in =
        "val a = 1;  \n" //
            + "val b = 2;\n";
    final String expected =
        "- val a = 1;  \r\n" //
            + "val a = 1 : int\n"
            + "- val b = 2;\r\n"
            + "val b = 2 : int\n"
            + "- \r\n";
    fixture().withInputString(in).assertOutput(is(expected));
  }

  /** Tests that two statements on the same line are both evaluated. */
  @Test
  void testTwoStatementsOnOneLine() {
    final String in = "val a = 1; val b = 2;\n";
    final String expected =
        "- val a = 1; val b = 2;\r\n" //
            + "val a = 1 : int\n"
            + "val b = 2 : int\n"
            + "- \r\n";
    fixture().withInputString(in).assertOutput(is(expected));
  }

  /**
   * Tests that an unmatched bracket inside a comment does not make the shell
   * wait for a line that never comes. Brackets do not decide where a statement
   * ends, and one inside a comment is not even code; if the reader treated the
   * input as incomplete it would swallow the rest of the session.
   */
  @Test
  void testBracketInComment() {
    final String in =
        "(* ) *)\n" //
            + "val a = 1;\n";
    final String expected =
        "- (* ) *)\r\n" //
            + "- val a = 1;\r\n"
            + "val a = 1 : int\n"
            + "- \r\n";
    fixture().withInputString(in).assertOutput(is(expected));
    // The same for an unmatched open bracket.
    final String in2 =
        "(* ( *)\n" //
            + "val b = 2;\n";
    final String expected2 =
        "- (* ( *)\r\n" //
            + "- val b = 2;\r\n"
            + "val b = 2 : int\n"
            + "- \r\n";
    fixture().withInputString(in2).assertOutput(is(expected2));
  }

  /**
   * Tests that a semicolon inside a comment does not end the statement. If it
   * did, the parser would be given a comment that is not yet closed, and would
   * report an unterminated comment at end of input.
   */
  @Test
  void testSemicolonInComment() {
    final String in =
        "(* a );\n" //
            + "*)\n"
            + "val a = 1;\n";
    final String expected =
        "- (* a );\r\n" //
            + "= *)\r\n"
            + "= val a = 1;\r\n"
            + "val a = 1 : int\n"
            + "- \r\n";
    fixture().withInputString(in).assertOutput(is(expected));
  }

  /** Tests {@link Shell} with a single-line comment. */
  @Test
  void testSingleLineComment() {
    final String in =
        "(*) line comment\n" //
            + "1 + 2;\n";
    final String expected =
        "- (*) line comment\r\n" //
            + "- 1 + 2;\r\n"
            + "val it = 3 : int\n"
            + "- \r\n";
    fixture().withInputString(in).assertOutput(is(expected));
  }

  /** Tests {@link Shell} with a single-line comment that contains a quote. */
  @Test
  void testSingleLineCommentWithQuote() {
    final String in =
        "(*) it's a single-line comment with a quote\n" //
            + "2 + 3;\n";
    final String expected =
        "- (*) it's a single-line comment with a quote\r\n" //
            + "- 2 + 3;\r\n"
            + "val it = 5 : int\n"
            + "- \r\n";
    fixture().withInputString(in).assertOutput(is(expected));
  }

  /**
   * Tests that the shell passes escape sequences to the parser unchanged. The
   * line reader must not treat backslash as an escape character; if it did,
   * {@code #"\n"} would reach the parser as {@code #"n"} (character 110), and a
   * newline would need to be written {@code #"\\n"}.
   */
  @Test
  void testEscapeInCharLiteral() {
    fixture()
        .withInputString("Char.ord #\"\\n\";\n")
        .assertOutput(containsString("val it = 10 : int"));
  }

  /**
   * Tests that a string literal ending in an escaped backslash is recognized as
   * complete. Were backslash an escape character to the line reader, it would
   * consume the closing quote and wait for a line that never comes.
   */
  @Test
  void testStringLiteralEndingInBackslash() {
    fixture()
        .withInputString("String.size \"a\\\\\";\n")
        .assertOutput(containsString("val it = 2 : int"));
  }

  /**
   * Tests {@link Shell} with {@code let} statement spread over multiple lines.
   */
  @Test
  void testMultiLineLet() {
    final String in =
        "let\n" //
            + "  val x = 1\n"
            + "in\n"
            + "  x + 2\n"
            + "end;\n";
    final String expected =
        "- let\r\n" //
            + "=   val x = 1\r\n"
            + "= in\r\n"
            + "=   x + 2\r\n"
            + "= end;\r\n"
            + "val it = 3 : int\n"
            + "- \r\n";
    fixture().withInputString(in).assertOutput(is(expected));
  }

  /** Tests the {@code use} function. */
  @Test
  void testUse() {
    // In SML-NJ, given x.sml as follows:
    //   val x = 2;
    //   val y = x + 3;
    //   x + y;
    //   use "z.sml";
    //   x + y + z;
    // and z.sml as follows:
    //   val z = 7;
    //   val x = 1;
    //   x + z;
    // running
    //   use "x.sml";
    //   x;
    // gives
    //   - use "x.sml";
    //   [opening x.sml]
    //   val x = 2 : int
    //   val y = 5 : int
    //   val it = 7 : int
    //   [opening z.sml]
    //   val z = 7 : int
    //   val x = 1 : int
    //   val it = 8 : int
    //   val it = () : unit
    //   val it = 13 : int
    //   val it = () : unit
    //   val it = 1;
    // Note that x = 1 after /tmp/x.sml has finished;
    // and that z has been assigned after /tmp/z.sml has finished.
    final String in = "use \"x.sml\";\n";
    final String expected =
        "- use \"x.sml\";\r\n"
            + "[opening x.sml]\n"
            + "val x = 2 : int\n"
            + "val y = 5 : int\n"
            + "val it = 7 : int\n"
            + "[opening z.sml]\n"
            + "val z = 7 : int\n"
            + "val x = 1 : int\n"
            + "val it = 8 : int\n"
            + "val it = () : unit\n"
            + "val it = 13 : int\n"
            + "val it = () : unit\n"
            + "- \r\n";
    fixture()
        .withArgListPlusDirectory()
        .withInputString(in)
        .assertOutput(is(expected));

    final String expectedRaw =
        "[opening x.sml]\n"
            + "val x = 2 : int\n"
            + "val y = 5 : int\n"
            + "val it = 7 : int\n"
            + "[opening z.sml]\n"
            + "val z = 7 : int\n"
            + "val x = 1 : int\n"
            + "val it = 8 : int\n"
            + "val it = () : unit\n"
            + "val it = 13 : int\n"
            + "val it = () : unit\n";
    fixture()
        .withRaw(true)
        .withArgListPlusDirectory()
        .withInputString(in)
        .assertOutput(is(expectedRaw));
  }

  /** Tests a warning. */
  @Test
  void testMatchWarning() {
    final String in =
        "fun f 1 = 1;\n" //
            + "f 1;\n";
    final String expected =
        "stdIn:1.5-1.12 Warning: match nonexhaustive\n" //
            + "  raised at: stdIn:1.5-1.12\n"
            + "val f = fn : int -> int\n"
            + "val it = 1 : int\n";
    fixture().withRaw(true).withInputString(in).assertOutput(is(expected));
  }

  /** Tests the {@code use} function on an empty file. */
  @Test
  void testUseEmpty() {
    final String in = "use \"empty.sml\";\n";
    final String expected =
        "- use \"empty.sml\";\r\n"
            + "[opening empty.sml]\n"
            + "val it = () : unit\n"
            + "- \r\n";
    fixture()
        .withArgListPlusDirectory()
        .withInputString(in)
        .assertOutput(is(expected));
  }

  /** Tests the {@code use} function on a missing file. */
  @Test
  void testUseMissing() {
    // SML-NJ gives:
    //   [opening missing.sml]
    //   [use failed: Io: openIn failed on "missing.sml", No such file or
    //   directory]
    //   uncaught exception Error
    //     raised at: ../compiler/TopLevel/interact/interact.sml:24.14-24.28

    final String in = "use \"missing.sml\";\n";
    final String expected =
        "- use \"missing.sml\";\r\n"
            + "[opening missing.sml]\n"
            + "[use failed: Io: openIn failed on missing.sml, No such file or directory]\n"
            + "uncaught exception Error\n"
            + "  raised at: stdIn:1.1-1.18\n"
            + "- \r\n";
    fixture()
        .withArgListPlusDirectory()
        .withInputString(in)
        .assertOutput(is(expected));
  }

  /** Tests the {@code use} function on a file that uses itself. */
  @Test
  void testUseSelfReferential() {
    // SML-NJ gives:
    //   [opening self-referential.sml]
    //   [use failed: Io: openIn failed on "self-referential.sml", Too many
    //   open files]
    //   uncaught exception Error
    //     raised at: ../compiler/TopLevel/interact/interact.sml:24.14-24.28

    final String in = "use \"self-referential.sml\";\n";
    final String expected =
        "- use \"self-referential.sml\";\r\n"
            + "[opening self-referential.sml]\n"
            + "[opening self-referential.sml]\n"
            + "[opening self-referential.sml]\n"
            + "[opening self-referential.sml]\n"
            + "[use failed: Io: openIn failed on self-referential.sml, Too many open files]\n"
            + "uncaught exception Error\n"
            + "  raised at: stdIn:1.1-1.27\n"
            + "val it = () : unit\n"
            + "- \r\n";
    fixture()
        .withArgListPlusDirectory()
        .withArgList(list -> plus(list, "--maxUseDepth=3"))
        .withInputString(in)
        .assertOutput(is(expected));
  }

  /**
   * Tests a script running in raw mode. It uses {@link Main} rather than {@link
   * Shell}.
   */
  @Test
  void testRaw() {
    String inputString =
        "val x = 2;\n" //
            + "x + 3;\n";
    String expected =
        "val x = 2 : int\n" //
            + "val it = 5 : int\n";
    fixture()
        .withRaw(true)
        .withInputString(inputString)
        .assertOutput(is(expected));
  }

  @Test
  void testStringDepth() {
    String inputString =
        "val s = \"a string that is 35 characters long\";\n"
            + "val c = #\"a\";\n"
            + "Sys.set (\"stringDepth\", 20);\n"
            + "s;\n"
            + "c;\n"
            + "\"abc\";\n"
            + "Sys.set (\"stringDepth\", 1);\n"
            + "s;\n"
            + "c;\n"
            + "Sys.set (\"stringDepth\", 0);\n"
            + "s;\n"
            + "c;\n"
            + "Sys.set (\"stringDepth\", 5);\n"
            + "\"a\\\\b\\\"cdef\";";
    String expected =
        "val s = \"a string that is 35 characters long\" : string\n"
            + "val c = #\"a\" : char\n"
            + "val it = () : unit\n"
            + "val it = \"a string that is 35 #\" : string\n"
            + "val it = #\"a\" : char\n"
            + "val it = \"abc\" : string\n"
            + "val it = () : unit\n"
            + "val it = \"a#\" : string\n"
            + "val it = #\"a\" : char\n"
            + "val it = () : unit\n"
            + "val it = \"#\" : string\n"
            + "val it = #\"a\" : char\n"
            + "val it = () : unit\n"
            + "val it = \"a\\\\b\\\"c#\" : string\n";
    fixture()
        .withRaw(true)
        .withInputString(inputString)
        .assertOutput(is(expected));
  }

  @Test
  void testPrintLength() {
    String inputString =
        "Sys.set (\"printLength\", 10);\n"
            + "val x = [[1,2,3], [4,5], [6], []];\n"
            + "Sys.set (\"printLength\", 4);\n"
            + "x;"
            + "Sys.set (\"printLength\", 3);\n"
            + "x;"
            + "Sys.set (\"printLength\", 2);\n"
            + "x;"
            + "Sys.set (\"printLength\", 1);\n"
            + "x;"
            + "Sys.set (\"printLength\", 0);\n"
            + "x;"
            + "Sys.set (\"printLength\", ~1);\n"
            + "x;\n";
    String expected =
        "val it = () : unit\n"
            + "val x = [[1,2,3],[4,5],[6],[]] : int list list\n"
            + "val it = () : unit\n"
            + "val it = [[1,2,3],[4,5],[6],[]] : int list list\n"
            + "val it = () : unit\n"
            + "val it = [[1,2,3],[4,5],[6],...] : int list list\n"
            + "val it = () : unit\n"
            + "val it = [[1,2,...],[4,5],...] : int list list\n"
            + "val it = () : unit\n"
            + "val it = [[1,...],...] : int list list\n"
            + "val it = () : unit\n"
            + "val it = [...] : int list list\n"
            + "val it = () : unit\n"
            + "val it = [[1,2,3],[4,5],[6],[]] : int list list\n";
    fixture()
        .withRaw(true)
        .withInputString(inputString)
        .assertOutput(is(expected));
  }

  /**
   * Fixture for testing the shell.
   *
   * @see #fixture
   */
  interface Fixture {
    ImmutableList<String> DEFAULT_ARG_LIST =
        ImmutableList.of(
            "--prompt=false",
            "--system=false",
            "--banner=false",
            "--terminal=dumb");

    List<String> argList();

    Fixture withArgList(List<String> argList);

    default Fixture withArgList(UnaryOperator<List<String>> transform) {
      return withArgList(transform.apply(argList()));
    }

    default Fixture withArgListPlusDirectory() {
      File useDirectory = getUseDirectory();
      return withArgList(list -> plus(list, "--directory=" + useDirectory))
          .withFile(useDirectory);
    }

    Fixture withFile(File file);

    File getFile();

    String inputString();

    Fixture withInputString(String inputString);

    Fixture withRaw(boolean raw);

    boolean isRaw();

    @SuppressWarnings("UnusedReturnValue")
    default Fixture assertOutput(Matcher<String> matcher) {
      try {
        if (isRaw()) {
          try (Reader reader = new StringReader(inputString());
              StringWriter writer = new StringWriter()) {
            final List<String> argList = ImmutableList.of();
            final Map<String, ForeignValue> valueMap = ImmutableMap.of();
            final Map<Prop, Object> propMap = new LinkedHashMap<>();
            Prop.DIRECTORY.set(propMap, getFile());
            Prop.SCRIPT_DIRECTORY.set(propMap, getFile());
            final Tracer tracer = Tracers.empty();
            final Main main =
                new Main(
                    argList, reader, writer, valueMap, propMap, false, tracer);
            main.run();
            assertThat(writer.toString(), matcher);
            return this;
          }
        }
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final ByteArrayInputStream bais =
            new ByteArrayInputStream(inputString().getBytes(UTF_8));
        final Shell.Config config =
            Shell.parse(Shell.Config.DEFAULT, argList());
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
    final boolean raw;
    final File file;

    FixtureImpl(
        ImmutableList<String> argList,
        String inputString,
        boolean raw,
        File file) {
      this.argList = requireNonNull(argList, "argList");
      this.inputString = requireNonNull(inputString, "inputString");
      this.raw = raw;
      this.file = requireNonNull(file, "file");
    }

    @Override
    public List<String> argList() {
      return argList;
    }

    @Override
    public Fixture withArgList(List<String> argList) {
      if (this.argList.equals(argList)) {
        return this;
      }
      ImmutableList<String> argList1 = ImmutableList.copyOf(argList);
      return new FixtureImpl(argList1, inputString, raw, file);
    }

    @Override
    public File getFile() {
      return file;
    }

    @Override
    public Fixture withFile(File file) {
      if (file.equals(this.file)) {
        return this;
      }
      return new FixtureImpl(argList, inputString, raw, file);
    }

    @Override
    public String inputString() {
      return inputString;
    }

    @Override
    public Fixture withInputString(String inputString) {
      if (this.inputString.equals(inputString)) {
        return this;
      }
      return new FixtureImpl(argList, inputString, raw, file);
    }

    @Override
    public boolean isRaw() {
      return raw;
    }

    @Override
    public Fixture withRaw(boolean raw) {
      if (raw == this.raw) {
        return this;
      }
      return new FixtureImpl(argList, inputString, raw, file);
    }
  }
}

// End ShellTest.java
