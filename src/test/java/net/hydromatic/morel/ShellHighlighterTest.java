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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.collect.ImmutableMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.ColorScheme;
import net.hydromatic.morel.util.ColorScheme.Category;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

/** Tests {@link ShellHighlighter} and {@link ColorScheme}. */
public class ShellHighlighterTest {
  private static ShellHighlighter highlighter(@Nullable String scheme) {
    final Map<Prop, Object> map = new LinkedHashMap<>();
    if (scheme != null) {
      Prop.COLOR_SCHEME.set(map, scheme);
    }
    final Session session = new Session(map, new TypeSystem());
    return new ShellHighlighter(session);
  }

  /** Each token gets the style of its category in the active scheme. */
  @Test
  void testHighlightDark() {
    // Indexes:               0123456789
    final AttributedString s =
        highlighter("dark").highlight(null, "fun f = 1;");
    // "fun" is a keyword
    assertThat(s.styleAt(0), is(ColorScheme.DARK.style(Category.KEYWORD)));
    // "f" is an identifier; the dark scheme leaves identifiers default
    assertThat(s.styleAt(4), is(AttributedStyle.DEFAULT));
    // "=" is a symbol
    assertThat(s.styleAt(6), is(ColorScheme.DARK.style(Category.SYMBOL)));
    // "1" is numeric
    assertThat(s.styleAt(8), is(ColorScheme.DARK.style(Category.NUMERIC)));
  }

  /** Strings, comments and constants get their categories' styles. */
  @Test
  void testHighlightTokens() {
    final ShellHighlighter h = highlighter("dark");
    assertThat(
        h.highlight(null, "\"abc\"").styleAt(0),
        is(ColorScheme.DARK.style(Category.STRING)));
    assertThat(
        h.highlight(null, "(* c *)").styleAt(0),
        is(ColorScheme.DARK.style(Category.COMMENT)));
    assertThat(
        h.highlight(null, "true").styleAt(0),
        is(ColorScheme.DARK.style(Category.CONSTANT)));
  }

  /**
   * An unterminated string ending in a backslash, the state the buffer passes
   * through when an escape sequence is typed interactively, must not crash the
   * highlighter.
   */
  @Test
  void testHighlightUnterminatedStringEscape() {
    final ShellHighlighter h = highlighter("dark");
    final AttributedStyle string = ColorScheme.DARK.style(Category.STRING);
    // '"' then '\': the escape skip must not run past the buffer.
    assertThat(h.highlight(null, "\"\\").styleAt(1), is(string));
    // Longer prefix of a typed escape, e.g. '"ab\'.
    assertThat(h.highlight(null, "\"ab\\").styleAt(3), is(string));
    // A complete escape in an unterminated string.
    assertThat(h.highlight(null, "\"a\\n").styleAt(3), is(string));
  }

  /** Word, real and scientific literals are styled as numeric. */
  @Test
  void testHighlightNumbers() {
    final ShellHighlighter h = highlighter("dark");
    final AttributedStyle numeric = ColorScheme.DARK.style(Category.NUMERIC);
    assertThat(h.highlight(null, "0w7").styleAt(0), is(numeric)); // word
    assertThat(h.highlight(null, "0wx1F").styleAt(2), is(numeric)); // word hex
    assertThat(h.highlight(null, "1.5").styleAt(0), is(numeric)); // real
    // the '.' is part of the real literal, not a separate symbol
    assertThat(h.highlight(null, "1.5").styleAt(1), is(numeric));
    assertThat(h.highlight(null, "1e~7").styleAt(3), is(numeric)); // scientific
  }

  /** The "none" scheme applies no styling. */
  @Test
  void testHighlightNone() {
    final AttributedString s =
        highlighter("none").highlight(null, "fun f = 1;");
    assertThat(s.styleAt(0), is(AttributedStyle.DEFAULT));
    assertThat(s.styleAt(8), is(AttributedStyle.DEFAULT));
  }

  /**
   * When {@code colorScheme} is unset, the scheme is deduced from the
   * environment; in a test (standard output is not a terminal) that is {@code
   * none}, so nothing is styled.
   */
  @Test
  void testHighlightUnsetDeduces() {
    final AttributedString s = highlighter(null).highlight(null, "fun f = 1;");
    assertThat(s.styleAt(0), is(AttributedStyle.DEFAULT));
  }

  /** Built-in schemes load and differ where expected. */
  @Test
  void testBuiltInSchemes() {
    assertThat(ColorScheme.builtIn("dark"), is(ColorScheme.DARK));
    assertThat(ColorScheme.builtIn("light"), is(ColorScheme.LIGHT));
    assertThat(ColorScheme.builtIn("none"), is(ColorScheme.NONE));
    assertThat(ColorScheme.builtIn("bogus"), nullValue());
    // dark keywords are bold cyan; light keywords are bold blue
    assertThat(
        ColorScheme.DARK.style(Category.KEYWORD),
        is(not(ColorScheme.LIGHT.style(Category.KEYWORD))));
    // none leaves every category default
    assertThat(
        ColorScheme.NONE.style(Category.KEYWORD), is(AttributedStyle.DEFAULT));
  }

  /** {@link ColorScheme#parse} handles names, indexes, rgb and attributes. */
  @Test
  void testColorSchemeParse() {
    final Map<String, String> props =
        ImmutableMap.of(
            "keyword", "bold red",
            "string", "green",
            "numeric", "245",
            "comment", "italic #5f87ff",
            "bogusKey", "green");
    final ColorScheme scheme = ColorScheme.parse("test", props);
    assertThat(
        scheme.style(Category.KEYWORD),
        is(AttributedStyle.DEFAULT.bold().foreground(AttributedStyle.RED)));
    assertThat(
        scheme.style(Category.STRING),
        is(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN)));
    // a bare number is an xterm-256 index
    assertThat(
        scheme.style(Category.NUMERIC),
        is(AttributedStyle.DEFAULT.foreground(245)));
    // #rrggbb is a 24-bit rgb color
    assertThat(
        scheme.style(Category.COMMENT),
        is(AttributedStyle.DEFAULT.italic().foregroundRgb(0x5f87ff)));
    // categories not mentioned are default; unknown keys are ignored
    assertThat(scheme.style(Category.IDENTIFIER), is(AttributedStyle.DEFAULT));
  }
}

// End ShellHighlighterTest.java
