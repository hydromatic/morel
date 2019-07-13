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
package net.hydromatic.sml;

import org.hamcrest.Matcher;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.assertThat;

/** Tests the Shell. */
public class ShellTest {

  private static final String UTF_8 = "utf-8";

  final List<String> argList =
      Arrays.asList("--system=false", "--banner=false", "--terminal=dumb");

  @SuppressWarnings("CharsetObjectCanBeUsed")
  private void assertShellOutput(List<String> argList, String inputString,
      Matcher<String> matcher) throws IOException {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    final Shell shell = new Shell(argList.toArray(new String[0]),
        new ByteArrayInputStream(inputString.getBytes(UTF_8)),
        new PrintStream(baos));
    shell.run();
    final String outString = baos.toString(UTF_8);
    assertThat(outString, matcher);
  }

  /** Tests {@link Shell} with empty input. */
  @Test public void testShell() throws IOException {
    final List<String> argList = Collections.singletonList("--system=false");
    assertShellOutput(argList, "", containsString("smlj version"));
  }

  /** Tests {@link Shell} with empty input and banner disabled. */
  @Test public void testShellNoBanner() throws IOException {
    assertShellOutput(argList, "", containsString("= \r\r\n"));
  }

  /** Tests {@link Shell} with one line. */
  @Test public void testOneLine() throws IOException {
    final String in = "1 + 2;\n";
    final String expected = "1 + 2;\r\n"
        + "= 1 + 2;\r\r\n"
        + "\u001B[?2004lval it = 3 : int\r\n"
        + "= \r\r\n"
        + "\u001B[?2004l";
    assertShellOutput(argList, in, is(expected));
  }

  /** Tests {@link Shell} with a continued line. */
  @Test public void testTwoLines() throws IOException {
    final String in = "1 +\n"
        + "2;\n";
    final String expected = "1 +\r\n"
        + "2;\r\n"
        + "= 1 +\r\r\n"
        + "\u001B[?2004l- 2;\r\r\n"
        + "\u001B[?2004lval it = 3 : int\r\n"
        + "= \r\r\n"
        + "\u001B[?2004l";
    assertShellOutput(argList, in, is(expected));
  }
}

// End ShellTest.java
