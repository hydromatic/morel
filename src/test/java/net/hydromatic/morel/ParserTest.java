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

import static net.hydromatic.morel.parse.Parsers.unquoteCharLiteral;
import static net.hydromatic.morel.parse.Parsers.unquoteIdentifier;
import static net.hydromatic.morel.parse.Parsers.unquoteString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import net.hydromatic.morel.parse.Parsers;
import org.junit.jupiter.api.Test;

/** Tests the parser. */
public class ParserTest {
  @SuppressWarnings("UnnecessaryUnicodeEscape")
  @Test
  void testUnquoteString() {
    assertThat("abc", unquoteString("\"abc\""), is("abc"));
    assertThat("empty", unquoteString("\"\""), is(""));
    assertThat("double-quote", unquoteString("\"\\\"\""), is("\""));
    assertThat("single-quote", unquoteString("\"'\""), is("'"));
    assertThat("abc double-quote", unquoteString("\"ab\\\"c\""), is("ab\"c"));
    assertThat("chr 0", unquoteString("\"ab\\^@c\""), is("ab\u0000c"));
    assertThat("chr 1", unquoteString("\"ab\\^Ac\""), is("ab\u0001c"));
    assertThat("chr 26", unquoteString("\"ab\\^Zc\""), is("ab\u001Ac"));
    assertThat("chr 27", unquoteString("\"ab\\^[c\""), is("ab\u001Bc"));
    assertThat("chr 28", unquoteString("\"ab\\^\\c\""), is("ab\u001Cc"));
    assertThat("chr 29", unquoteString("\"ab\\^]c\""), is("ab\u001Dc"));
    assertThat("chr 30", unquoteString("\"ab\\^^c\""), is("ab\u001Ec"));
    assertThat("chr 31", unquoteString("\"ab\\^_c\""), is("ab\u001Fc"));
    assertThat("chr 32", unquoteString("\"ab c\""), is("ab\u0020c"));
    assertThat("chr 32", unquoteString("\"ab c\""), is("ab c"));
    assertThat("chr 255", unquoteString("\"ab\\255c\""), is("ab\u00FFc"));
    assertThat("tab", unquoteString("\"ab\\tc\""), is("ab\tc"));
    assertThat(
        "newline", unquoteString("\"ab\\nc\""), is("ab\nc")); // lint:skip
    assertThat("return", unquoteString("\"ab\\rc\""), is("ab\rc"));
    assertThat("form-feed", unquoteString("\"ab\\fc\""), is("ab\fc"));
    assertThat("bel", unquoteString("\"ab\\bc\""), is("ab\bc"));
  }

  @Test
  void testUnquoteIdentifier() {
    assertThat(unquoteIdentifier("`abc`"), is("abc"));
    assertThat(unquoteIdentifier("`ab``c`"), is("ab`c"));
  }

  @Test
  void testUnquoteCharLiteral() {
    assertUnquote("\"a\"", 'a');
    assertUnquote("\"!\"", '!');
    assertUnquote("\"\\\"\"", '"');
    assertUnquote("\"\\^@\"", (char) 0);
    assertUnquote("\"\\^Z\"", (char) 26);
    assertUnquote("\"\\^^\"", (char) 30);
    assertUnquote("\"\\^_\"", (char) 31);
    assertUnquote("\"\\255\"", (char) 255);

    // A few control characters have two representations.
    // Alert (ASCII 0x07) "\\a"
    // Backspace (ASCII 0x08) "\\b"
    // Horizontal tab (ASCII 0x09) "\\t"
    // Linefeed or newline (ASCII 0x0A) "\\n"
    // Vertical tab (ASCII 0x0B) "\\v"
    // Form feed (ASCII 0x0C) "\\f"
    // Carriage return (ASCII 0x0D) "\\r"
    assertUnquote("\"\\^G\"", (char) 7);
    assertUnquote("\"\\a\"", (char) 7);
    assertUnquote("\"\\^H\"", (char) 8);
    assertUnquote("\"\\b\"", (char) 8);
    assertUnquote("\"\\^I\"", (char) 9);
    assertUnquote("\"\\t\"", (char) 9);
    assertUnquote("\"\\^J\"", (char) 10);
    assertUnquote("\"\\n\"", (char) 10);
    assertUnquote("\"\\^K\"", (char) 11);
    assertUnquote("\"\\v\"", (char) 11);
    assertUnquote("\"\\^L\"", (char) 12);
    assertUnquote("\"\\f\"", (char) 12);
    assertUnquote("\"\\^M\"", (char) 13);
    assertUnquote("\"\\r\"", (char) 13);
  }

  private void assertUnquote(String s, char c) {
    assertThat(unquoteCharLiteral("#" + s), is(c));
    assertThat(unquoteString(s), is("" + c));
  }

  @Test
  void testRoundTrip() {
    for (char c = 0; c < 255; c++) {
      String s = Parsers.charToString(c);
      String charLiteral = "#\"" + s + "\"";
      assertThat("char: " + (int) c, unquoteCharLiteral(charLiteral), is(c));

      String stringLiteral = "\"" + s + "\"";
      assertThat("char: " + (int) c, unquoteString(stringLiteral), is("" + c));
    }
  }
}

// End ParserTest.java
