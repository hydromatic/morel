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
package net.hydromatic.morel.parse;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.hydromatic.morel.ast.Pos;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Utilities for parsing. */
public final class Parsers {
  private Parsers() {}

  private static final Pattern LEXICAL_POS =
      Pattern.compile("line (\\d+), column (\\d+)");

  /**
   * Returns the position embedded in a lexical-error message (e.g. "Lexical
   * error at line 1, column 25"), adjusted by {@code lineOffset}, or {@link
   * Pos#ZERO} if the message contains no position.
   */
  public static Pos lexicalPos(
      @Nullable String message, String file, int lineOffset) {
    if (message != null) {
      final Matcher matcher = LEXICAL_POS.matcher(message);
      if (matcher.find()) {
        final int line = Integer.parseInt(matcher.group(1)) - lineOffset;
        final int column = Integer.parseInt(matcher.group(2));
        return new Pos(file, line, column, line, column);
      }
    }
    return Pos.ZERO;
  }

  /**
   * Reserved words. These cannot be used as identifiers unless quoted with
   * back-ticks, so {@link #appendId} quotes them. Must be kept in sync with the
   * keyword tokens in {@code MorelParser.jj}.
   */
  public static final Set<String> RESERVED_WORDS =
      ImmutableSet.of(
          "and",
          "andalso",
          "as",
          "case",
          "compute",
          "current",
          "datatype",
          "distinct",
          "div",
          "elem",
          "elements",
          "else",
          "end",
          "eqtype",
          "except",
          "exception",
          "exists",
          "fn",
          "forall",
          "from",
          "full",
          "fun",
          "group",
          "if",
          "implies",
          "in",
          "inst",
          "intersect",
          "into",
          "join",
          "left",
          "let",
          "mod",
          "notelem",
          "o",
          "of",
          "on",
          "op",
          "order",
          "ordinal",
          "orelse",
          "over",
          "raise",
          "rec",
          "require",
          "right",
          "sig",
          "signature",
          "skip",
          "take",
          "then",
          "through",
          "type",
          "typeof",
          "union",
          "unorder",
          "val",
          "where",
          "with",
          "yield");

  /**
   * Given quoted identifier {@code `abc`} returns {@code abc}. Converts any
   * doubled back-ticks to a single back-tick. Assumes there are no single
   * back-ticks.
   */
  public static String unquoteIdentifier(String s) {
    checkArgument(s.length() >= 2);
    checkArgument(s.charAt(0) == '`');
    checkArgument(s.charAt(s.length() - 1) == '`');
    s = s.substring(1, s.length() - 1);
    return s.replace("``", "`");
  }

  /**
   * Given quoted string {@code "abc"} returns {@code abc}; {@code "\t"} returns
   * the tab character; {@code "\^A"} returns character 1; {@code "\255"}
   * returns character 255.
   */
  public static String unquoteString(String s) {
    checkArgument(s.length() >= 2);
    checkArgument(s.charAt(0) == '"');
    checkArgument(s.charAt(s.length() - 1) == '"');
    s = s.substring(1, s.length() - 1);
    if (!s.contains("\\")) {
      // There are no escaped characters. Take the quick route.
      return s;
    }
    final StringParser p = new StringParser(s);
    final StringBuilder b = new StringBuilder();
    while (p.i < p.s.length()) {
      b.append(p.parseChar());
    }
    return b.toString();
  }

  /** Given quoted char literal {@code #"a"} returns {@code a}. */
  public static char unquoteCharLiteral(String s) {
    checkArgument(s.length() >= 3);
    checkArgument(s.charAt(0) == '#');
    checkArgument(s.charAt(1) == '"');
    checkArgument(s.charAt(s.length() - 1) == '"');
    s = s.substring(2, s.length() - 1);
    final StringParser p = new StringParser(s);
    char c = p.parseChar();
    if (p.i != s.length()) {
      throw new RuntimeException("Error: character literal not length 1");
    }
    return c;
  }

  /** Given string {@code "a"} returns {@code a}. */
  public static @Nullable Character fromString(String s) {
    if (s.isEmpty()) {
      return null;
    }
    final StringParser p = new StringParser(s);
    return p.parseChar();
  }

  /**
   * Converts a character to how it appears in a character literal.
   *
   * <p>For example, '{@code a}' becomes '{@code #"a"}' and therefore {@code
   * charToString('a')} returns "a". Character 0 becomes {@code "\\^@"}.
   * Character 255 becomes {@code "\\255"}. Character 9 becomes {@code "\t"}.
   *
   * <p>Inverse of {@link #unquoteCharLiteral}.
   */
  public static String charToString(char c) {
    switch (c) {
        // Alert (ASCII 0x07) "\\a"
        // Backspace (ASCII 0x08) "\\b"
        // Horizontal tab (ASCII 0x09) "\\t"
        // Linefeed or newline (ASCII 0x0A) "\\n"
        // Vertical tab (ASCII 0x0B) "\\v"
        // Form feed (ASCII 0x0C) "\\f"
        // Carriage return (ASCII 0x0D) "\\r"
      case 7:
        return "\\a";
      case 8:
        return "\\b";
      case 9:
        return "\\t";
      case 10:
        return "\\n";
      case 11:
        return "\\v";
      case 12:
        return "\\f";
      case 13:
        return "\\r";
      case 34:
        // Double-quote requires escape
        return "\\\"";
      case 92:
        // Backslash requires escape
        return "\\\\";
      default:
        if (c < 32) {
          // chr(0) = "\\^@", chr(1) = "\\^A", ..., chr(31) = "\\^_".
          return "\\^" + (char) (c + 64);
        } else if (c >= 127 && c < 256) {
          return "\\" + (int) c;
        } else {
          return String.valueOf(c);
        }
    }
  }

  /**
   * Converts an internal string to a string using Standard ML escapes,
   * appending to a builder.
   */
  public static void stringToString(String s, StringBuilder b) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      b.append(charToString(c));
    }
  }

  /** Converts an internal string to a string using Standard ML escapes. */
  public static String stringToString(String s) {
    if (!requiresEscape(s)) {
      return s;
    }
    final StringBuilder b = new StringBuilder();
    stringToString(s, b);
    return b.toString();
  }

  private static boolean requiresEscape(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 32 || c == '"' || c == '\\' || c > 127) {
        // Control characters, double-quote, backslash, non-ASCII require escape
        return true;
      }
    }
    return false;
  }

  /**
   * Appends an identifier or label, enclosing it in back-ticks if necessary
   * (because it contains a back-tick or space, or is a reserved word).
   */
  public static StringBuilder appendId(StringBuilder buf, String id) {
    if (id.contains("`")) {
      return buf.append("`").append(id.replaceAll("`", "``")).append("`");
    } else if (id.contains(" ") || RESERVED_WORDS.contains(id)) {
      return buf.append("`").append(id).append("`");
    } else {
      return buf.append(id);
    }
  }

  static class StringParser {
    final String s;
    int i = 0;

    StringParser(String s) {
      this.s = s;
    }

    /**
     * Parses a single character in a string literal or character literal.
     * Advances {@code i[0]} to the next character in the string.
     */
    char parseChar() {
      final char c = s.charAt(i++);
      if (c != '\\') {
        return c;
      }
      if (i >= s.length()) {
        throw new RuntimeException("illegal escape; no character after \\");
      }
      final char c2 = s.charAt(i++);
      switch (c2) {
        case '"':
        case '\\':
          // Escaped double-quote or backslash
          return c2;

        case 'a':
          // Alert (ASCII 0x07) "\\a"
          return '\u0007';

        case 'b':
          // Backspace (ASCII 0x08) "\\b"
          return '\b';

        case 't':
          // Horizontal tab (ASCII 0x09) "\\t"
          return '\t';

        case 'n':
          // Linefeed or newline (ASCII 0x0A) "\\n"
          return '\n';

        case 'v':
          // Vertical tab (ASCII 0x0B) "\\v"
          return '\u000B';

        case 'f':
          // Form feed (ASCII 0x0C) "\\f"
          return '\f';

        case 'r':
          // Carriage return (ASCII 0x0D) "\\r"
          return '\r';

        case '0':
        case '1':
        case '2':
        case '3':
        case '4':
        case '5':
        case '6':
        case '7':
        case '8':
        case '9':
          if (i + 2 <= s.length()) {
            final char c3 = s.charAt(i++);
            final char c4 = s.charAt(i++);
            if (c3 >= '0' && c3 <= '9' && c4 >= '0' && c4 <= '9') {
              // Characters "0123456789" are contiguous.
              // 0 = 48, 1 = 49, ..., 9 = 57.
              int d2 = c2 - '0';
              int d3 = c3 - '0';
              int d4 = c4 - '0';
              return (char) (d2 * 100 + d3 * 10 + d4);
            }
          }
          throw new RuntimeException(
              "illegal control escape; too few digits after ^");

        case '^':
          if (i >= s.length()) {
            throw new RuntimeException(
                "illegal control escape; no character after ^");
          }
          final char c3 = s.charAt(i++);
          if (c3 >= '@' && c3 <= '_') {
            // Characters "@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\]^_" are contiguous.
            // @ = 64, A = 65, ..., Z = 90, \ = 91, ] = 92, ^ = 93, _ = 94.
            return (char) (c3 - '@');
          }
          throw new RuntimeException(
              "illegal control escape; "
                  + "must be one of @ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_");

        default:
          throw new RuntimeException(
              "illegal escape; invalid character after \\");
      }
    }
  }
}

// End Parsers.java
