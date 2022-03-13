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

import net.hydromatic.morel.foreign.ForeignValue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static net.hydromatic.morel.TestUtils.findDirectory;
import static net.hydromatic.morel.TestUtils.plus;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Tests the Shell. */
public class ShellTest {

  /** Creates a Fixture. */
  static Fixture fixture() {
    return new FixtureImpl(Fixture.DEFAULT_ARG_LIST, "?", false, new File(""));
  }

  static void pauseForTenMilliseconds() {
    try {
      Thread.sleep(10);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  /** Throws "assumption failed" if the environment variable 'morel.ci'
   * is set and is not 0 or false. Allows us to skip tests that are
   * non-deterministic when run in GitHub actions or Travis CI. */
  static void assumeNotInCi() {
    final String ci = System.getProperty("morel.ci");
    assumeTrue(ci == null
            || ci.equalsIgnoreCase("false")
            || ci.equals("0"),
        "test skipped during CI (morel.ci is " + ci + ")");
  }

  static File getUseDirectory() {
    final File rootDirectory = findDirectory();
    return new File(rootDirectory, "use");
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
    assumeNotInCi();
    final String in = "1 + 2;\n";
    final String expected = "1 + 2;\r\n"
        + "- 1 + 2;\r\r\n"
        + "\u001B[?2004lval it = 3 : int\r\n"
        + "- \r\r\n"
        + "\u001B[?2004l";
    fixture().withInputString(in).assertOutput(is2(expected));
  }

  /** Tests {@link Shell} with a continued line. */
  @Test void testTwoLines() {
    assumeNotInCi();
    final String in = "1 +\n"
        + "2;\n";
    final String expected = "1 +\r\n"
        + "2;\r\n"
        + "- 1 +\r\r\n"
        + "\u001B[?2004l= 2;\r\r\n"
        + "\u001B[?2004lval it = 3 : int\r\n"
        + "- \r\r\n"
        + "\u001B[?2004l";
    fixture().withInputString(in).assertOutput(is2(expected));
  }

  /** Tests {@link Shell} printing some tricky real values. */
  @Test void testReal() {
    final String in = "val nan = Real.posInf / Real.negInf;\n"
        + "(nan, Real.posInf, Real.negInf, 0.0, ~0.0);\n";
    final String expected = "val nan = nan : real\n"
        + "val it = (nan,inf,~inf,0.0,~0.0) : real * real * real * real * real\n";
    fixture().withRaw(true).withInputString(in).assertOutput(is(expected));
  }

  /** Tests {@link Shell} with a line that is a comment, another that is empty,
   *  and another that has only a semicolon; all are treated as empty. */
  @Test void testEmptyLines() {
    assumeNotInCi();
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
    fixture().withInputString(in).assertOutput(is2(expected));
  }

  private Matcher<String> is2(String expected) {
    return CoreMatchers.anyOf(is(expected),
        is(expected.replace("\u001B[?2004l", "")));
  }

  /** Tests {@link Shell} with a single-line comment. */
  @Test void testSingleLineComment() {
    assumeNotInCi();
    final String in = "(*) line comment\n"
        + "1 + 2;\n";
    final String expected = "(*) line comment\r\n"
        + "1 + 2;\r\n"
        + "- (*) line comment\r\r\n"
        + "\u001B[?2004l- 1 + 2;\r\r\n"
        + "\u001B[?2004lval it = 3 : int\r\n"
        + "- \r\r\n"
        + "\u001B[?2004l";
    fixture().withInputString(in).assertOutput(is2(expected));
  }

  /** Tests {@link Shell} with a single-line comment that contains a quote. */
  @Test void testSingleLineCommentWithQuote() {
    assumeNotInCi();
    final String in = "(*) it's a single-line comment with a quote\n"
        + "2 + 3;\n";
    final String expected = "(*) it's a single-line comment with a quote\r\n"
        + "2 + 3;\r\n"
        + "- (*) it's a single-line comment with a quote\r\r\n"
        + "\u001B[?2004l- 2 + 3;\r\r\n"
        + "\u001B[?2004lval it = 5 : int\r\n"
        + "- \r\r\n"
        + "\u001B[?2004l";
    fixture().withInputString(in).assertOutput(is2(expected));
  }

  /** Tests {@link Shell} with {@code let} statement spread over multiple
   * lines. */
  @Test void testMultiLineLet() {
    assumeNotInCi();
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
    fixture().withInputString(in).assertOutput(is2(expected));
  }

  /** Tests the {@code use} function. */
  @Test void testUse() {
    assumeNotInCi();
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
    final String expected = "use \"x.sml\";\r\n"
        + "- use \"x.sml\";\r\r\n"
        + "\u001B[?2004l[opening x.sml]\r\n"
        + "val x = 2 : int\r\n"
        + "val y = 5 : int\r\n"
        + "val it = 7 : int\r\n"
        + "[opening z.sml]\r\n"
        + "val z = 7 : int\r\n"
        + "val x = 1 : int\r\n"
        + "val it = 8 : int\r\n"
        + "val it = () : unit\r\n"
        + "val it = 13 : int\r\n"
        + "val it = () : unit\r\n"
        + "- \r\r\n"
        + "\u001B[?2004l";
    fixture()
        .withArgListPlusDirectory()
        .withInputString(in)
        .assertOutput(is2(expected));

    final String expectedRaw = "[opening x.sml]\n"
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

  /** Tests the {@code use} function on an empty file. */
  @Test void testUseEmpty() {
    assumeNotInCi();
    final String in = "use \"empty.sml\";\n";
    final String expected = "use \"empty.sml\";\r\n"
        + "- use \"empty.sml\";\r\r\n"
        + "\u001B[?2004l[opening empty.sml]\r\n"
        + "val it = () : unit\r\n"
        + "- \r\r\n"
        + "\u001B[?2004l";
    fixture()
        .withArgListPlusDirectory()
        .withInputString(in)
        .assertOutput(is2(expected));
  }

  /** Tests the {@code use} function on a missing file. */
  @Test void testUseMissing() {
    assumeNotInCi();
    // SML-NJ gives:
    //   [opening missing.sml]
    //   [use failed: Io: openIn failed on "missing.sml", No such file or
    //   directory]
    //   uncaught exception Error
    //     raised at: ../compiler/TopLevel/interact/interact.sml:24.14-24.28

    final String in = "use \"missing.sml\";\n";
    final String expected = "use \"missing.sml\";\r\n"
        + "- use \"missing.sml\";\r\r\n"
        + "\u001B[?2004l[opening missing.sml]\r\n"
        + "[use failed: Io: openIn failed on missing.sml,"
        + " No such file or directory]\r\n"
        + "uncaught exception Error\r\n"
        + "- \r\r\n"
        + "\u001B[?2004l";
    fixture()
        .withArgListPlusDirectory()
        .withInputString(in)
        .assertOutput(is2(expected));
  }

  /** Tests the {@code use} function on a file that uses itself. */
  @Test void testUseSelfReferential() {
    assumeNotInCi();
    // SML-NJ gives:
    //   [opening self-referential.sml]
    //   [use failed: Io: openIn failed on "self-referential.sml", Too many
    //   open files]
    //   uncaught exception Error
    //     raised at: ../compiler/TopLevel/interact/interact.sml:24.14-24.28

    final String in = "use \"self-referential.sml\";\n";
    final String expected = "use \"self-referential.sml\";\r\n"
        + "- use \"self-referential.sml\";\r\r\n"
        + "\u001B[?2004l[opening self-referential.sml]\r\n"
        + "[opening self-referential.sml]\r\n"
        + "[opening self-referential.sml]\r\n"
        + "[opening self-referential.sml]\r\n"
        + "[use failed: Io: openIn failed on self-referential.sml,"
        + " Too many open files]\r\n"
        + "uncaught exception Error\r\n"
        + "val it = () : unit\r\n"
        + "- \r\r\n"
        + "\u001B[?2004l";
    fixture()
        .withArgListPlusDirectory()
        .withArgList(list -> plus(list, "--maxUseDepth=3"))
        .withInputString(in)
        .assertOutput(is2(expected));
  }

  /** Tests a script running in raw mode.
   * It uses {@link Main} rather than {@link Shell}. */
  @Test void testRaw() {
    String inputString = "val x = 2;\n"
        + "x + 3;\n";
    String expected = "val x = 2 : int\n"
        + "val it = 5 : int\n";
    fixture()
        .withRaw(true)
        .withInputString(inputString)
        .assertOutput(is(expected));
  }

  @Test void testStringDepth() {
    String inputString = "val s = \"a string that is 35 characters long\";\n"
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
    String expected = "val s = \"a string that is 35 characters long\" : string\n"
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

  @Test void testPrintDepth() {
    String inputString = "Sys.set (\"lineWidth\", 70);\n"
        + "val x = {a=1,b=[2,3],c=[{d=4,e=[5,6],f=[{g=7,h=[8],i={j=[9]}}]}]};\n"
        + "Sys.set (\"printDepth\", 6);\n"
        + "x;"
        + "Sys.set (\"printDepth\", 5);\n"
        + "x;"
        + "Sys.set (\"printDepth\", 4);\n"
        + "x;"
        + "Sys.set (\"printDepth\", 3);\n"
        + "x;"
        + "Sys.set (\"printDepth\", 2);\n"
        + "x;"
        + "Sys.set (\"printDepth\", 1);\n"
        + "x;"
        + "Sys.set (\"printDepth\", 0);\n"
        + "x;"
        + "Sys.set (\"printDepth\", ~1);\n"
        + "x;";
    // TODO: wrap types linke this:
    // val it = ...
    //  : {a:int, b:int list,
    //     c:{d:int, e:int list, f:{g:int, h:int list, i:{j:int list}} list} list}
    String expected = "val it = () : unit\n"
        + "val x = {a=1,b=[2,3],c=[{d=4,e=[5,6],f=[{g=#,h=#,i=#}]}]}\n"
        + "  : {a:int, b:int list, c:{d:int, e:int list, f:{g:int, h:int list, i:{j:int list}} list} list}\n"
        + "val it = () : unit\n"
        + "val it = {a=1,b=[2,3],c=[{d=4,e=[5,6],f=[{g=7,h=[#],i={j=#}}]}]}\n"
        + "  : {a:int, b:int list, c:{d:int, e:int list, f:{g:int, h:int list, i:{j:int list}} list} list}\n"
        + "val it = () : unit\n"
        + "val it = {a=1,b=[2,3],c=[{d=4,e=[5,6],f=[{g=#,h=#,i=#}]}]}\n"
        + "  : {a:int, b:int list, c:{d:int, e:int list, f:{g:int, h:int list, i:{j:int list}} list} list}\n"
        + "val it = () : unit\n"
        + "val it = {a=1,b=[2,3],c=[{d=4,e=[#,#],f=[#]}]}\n"
        + "  : {a:int, b:int list, c:{d:int, e:int list, f:{g:int, h:int list, i:{j:int list}} list} list}\n"
        + "val it = () : unit\n"
        + "val it = {a=1,b=[2,3],c=[{d=#,e=#,f=#}]}\n"
        + "  : {a:int, b:int list, c:{d:int, e:int list, f:{g:int, h:int list, i:{j:int list}} list} list}\n"
        + "val it = () : unit\n"
        + "val it = {a=1,b=[#,#],c=[#]}\n"
        + "  : {a:int, b:int list, c:{d:int, e:int list, f:{g:int, h:int list, i:{j:int list}} list} list}\n"
        + "val it = () : unit\n"
        + "val it = {a=#,b=#,c=#}\n"
        + "  : {a:int, b:int list, c:{d:int, e:int list, f:{g:int, h:int list, i:{j:int list}} list} list}\n"
        + "val it = # : unit\n"
        + "val it = #\n"
        + "  : {a:int, b:int list, c:{d:int, e:int list, f:{g:int, h:int list, i:{j:int list}} list} list}\n"
        + "val it = () : unit\n"
        + "val it = {a=1,b=[2,3],c=[{d=4,e=[5,6],f=[{g=7,h=[8],i={j=[9]}}]}]}\n"
        + "  : {a:int, b:int list, c:{d:int, e:int list, f:{g:int, h:int list, i:{j:int list}} list} list}\n";
    fixture()
        .withRaw(true)
        .withInputString(inputString)
        .assertOutput(is(expected));
  }

  @Test void testPrintLength() {
    String inputString = "Sys.set (\"printLength\", 10);\n"
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
    String expected = "val it = () : unit\n"
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

  @Test void testLineWidth() {
    String inputString = "Sys.set (\"lineWidth\", 100);\n"
        + "val x = [[1,2,3], [4,5], [6], []];\n"
        + "Sys.set (\"lineWidth\", 40);\n"
        + "x;"
        + "Sys.set (\"lineWidth\", 20);\n"
        + "x;"
        + "Sys.set (\"lineWidth\", 1);\n"
        + "x;"
        + "Sys.set (\"lineWidth\", 0);\n"
        + "x;"
        + "Sys.set (\"lineWidth\", ~1);\n"
        + "x;\n";
    String expected = "val it = () : unit\n"
        + "val x = [[1,2,3],[4,5],[6],[]] : int list list\n"
        + "val it = () : unit\n"
        + "val it = [[1,2,3],[4,5],[6],[]]\n"
        + "  : int list list\n"
        + "val it = () : unit\n"
        + "val it =\n"
        + "  [[1,2,3],[4,5],[6],\n"
        + "   []]\n"
        + "  : int list list\n"
        + "val it =\n"
        + "  ()\n"
        + "  : unit\n"
        + "val it =\n"
        + "  [\n"
        + "   [\n"
        + "    1,\n"
        + "    2,\n"
        + "    3],\n"
        + "   [\n"
        + "    4,\n"
        + "    5],\n"
        + "   [\n"
        + "    6],\n"
        + "   []]\n"
        + "  : int list list\n"
        + "val it =\n"
        + "  ()\n"
        + "  : unit\n"
        + "val it =\n"
        + "  [\n"
        + "   [\n"
        + "    1,\n"
        + "    2,\n"
        + "    3],\n"
        + "   [\n"
        + "    4,\n"
        + "    5],\n"
        + "   [\n"
        + "    6],\n"
        + "   []]\n"
        + "  : int list list\n"
        + "val it = () : unit\n"
        + "val it = [[1,2,3],[4,5],[6],[]] : int list list\n";
    fixture()
        .withRaw(true)
        .withInputString(inputString)
        .assertOutput(is(expected));
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
            final Map<String, ForeignValue> dictionary = ImmutableMap.of();
            final File directory = getFile();
            new Main(argList, reader, writer, dictionary, directory).run();
            assertThat(writer.toString(), matcher);
            return this;
          }
        }
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
    final boolean raw;
    final File file;

    FixtureImpl(ImmutableList<String> argList, String inputString,
        boolean raw, File file) {
      this.argList = requireNonNull(argList, "argList");
      this.inputString = requireNonNull(inputString, "inputString");
      this.raw = raw;
      this.file = requireNonNull(file, "file");
    }

    @Override public List<String> argList() {
      return argList;
    }

    @Override public Fixture withArgList(List<String> argList) {
      if (this.argList.equals(argList)) {
        return this;
      }
      ImmutableList<String> argList1 = ImmutableList.copyOf(argList);
      return new FixtureImpl(argList1, inputString, raw, file);
    }

    @Override public File getFile() {
      return file;
    }

    @Override public Fixture withFile(File file) {
      if (file.equals(this.file)) {
        return this;
      }
      return new FixtureImpl(argList, inputString, raw, file);
    }

    @Override public String inputString() {
      return inputString;
    }

    @Override public Fixture withInputString(String inputString) {
      if (this.inputString.equals(inputString)) {
        return this;
      }
      return new FixtureImpl(argList, inputString, raw, file);
    }

    @Override public boolean isRaw() {
      return raw;
    }

    @Override public Fixture withRaw(boolean raw) {
      if (raw == this.raw) {
        return this;
      }
      return new FixtureImpl(argList, inputString, raw, file);
    }
  }
}

// End ShellTest.java
