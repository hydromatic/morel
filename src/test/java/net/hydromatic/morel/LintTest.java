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

import static com.google.common.base.Strings.repeat;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.util.Static.last;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.hydromatic.morel.util.Generation;
import net.hydromatic.morel.util.JavaVersion;
import net.hydromatic.morel.util.WordComparator;
import org.apache.calcite.util.Puffin;
import org.apache.calcite.util.Source;
import org.apache.calcite.util.Sources;
import org.apache.calcite.util.Util;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

/** Runs Lint-like checks on the source code. Also tests those checks. */
@SuppressWarnings("Convert2MethodRef") // JDK 8 requires lambdas
public class LintTest {
  private Puffin.Program<GlobalState> makeProgram() {
    Puffin.Builder<GlobalState, FileState> b =
        Puffin.builder(GlobalState::new, global -> new FileState(global));
    addProgram0(b);
    addProgram1(b);
    addProgram2(b);
    return b.build();
  }

  private static void addProgram0(Puffin.Builder<GlobalState, FileState> b) {
    // Track "// lint:skip" and "// lint:skip N" comments.
    b.add(
        line -> true,
        line -> {
          // lint:skip 1
          java.util.regex.Matcher m = LINT_SKIP_PATTERN.matcher(line.line());
          if (m.find()) {
            int n = m.group(1) != null ? Integer.parseInt(m.group(1)) : 1;
            line.state().lintEnableLine = line.fnr() + n + 1;
          }
        });
    b.add(
        line -> line.isLast(),
        line -> {
          String f = line.filename();
          final int slash = f.lastIndexOf('/');
          final String comment = getCommentStyleForFile(f);
          if (comment != null) {
            final String endMarker =
                comment + " End " + (slash < 0 ? f : f.substring(slash + 1));
            if (!line.line().equals(endMarker)) {
              line.state().message(line, "File must end with '%s'", endMarker);
            }
          }
        });
    b.add(line -> line.fnr() == 1, line -> line.globalState().fileCount++);

    // Trailing space
    b.add(
        line -> line.endsWith(" "),
        line -> line.state().message(line, "Trailing space"));

    // Tab
    b.add(
        line -> line.contains("\t"), line -> line.state().message(line, "Tab"));

    // Smart quotes
    //noinspection UnnecessaryUnicodeEscape
    b.add(
        line ->
            line.contains("\u00B4") // acute accent
                || line.contains("\u2018") // left single quote
                || line.contains("\u2019") // right single quote
                || line.contains("\u201C") // left double quote
                || line.contains("\u201D"), // right double quote
        line -> line.state().message(line, "Smart quote"));

    // Nullable
    b.add(
        line -> line.startsWith("import javax.annotation.Nullable;"),
        line ->
            line.state()
                .message(
                    line,
                    "use org.checkerframework.checker.nullness.qual.Nullable"));

    // Nonnull
    b.add(
        line -> line.startsWith("import javax.annotation.Nonnull;"),
        line ->
            line.state()
                .message(
                    line,
                    "use org.checkerframework.checker.nullness.qual.NonNull"));

    // Use of 'Static.' other than in an import.
    b.add(
        line ->
            (line.contains("Assertions.")
                    || line.contains("CoreMatchers.")
                    || line.contains("MatcherAssert.assertThat")
                    || line.contains("Objects.requireNonNull")
                    || line.contains("Ord.forEachIndexed")
                    || line.contains("Pair.forEach")
                    || line.contains("Preconditions.")
                    || line.contains("Static."))
                && line.filename().endsWith(".java")
                && !line.startsWith("import static")
                && !line.matches("^ *// .*$")
                && !lintSkip(line)
                && !filenameIs(line, "LintTest.java")
                && !filenameIs(line, "UtilTest.java"),
        line -> line.state().message(line, "should be static import"));

    // In a test,
    //   assertThat(x.toString(), is(y));
    // should be
    //   assertThat(x, hasToString(y)));
    b.add(
        line ->
            line.contains(".toString(), is(")
                && line.filename().endsWith(".java")
                && !filenameIs(line, "LintTest.java"),
        line -> line.state().message(line, "use 'Matchers.hasToString'"));

    // Comment without space
    b.add(
        line ->
            line.matches(".* //[^ ].*")
                && !filenameIs(line, "LintTest.java")
                && !line.contains("//--")
                && !line.contains("//~")
                && !line.contains("//noinspection")
                && !line.contains("//CHECKSTYLE"),
        line -> line.state().message(line, "'//' must be followed by ' '"));

    // Add line to sorting
    b.add(
        line -> line.state().sortConsumer != null,
        line -> requireNonNull(line.state().sortConsumer).accept(line));

    // Start sorting if line has "lint: sort until ..."
    b.add(
        line ->
            line.contains("// lint: sort")
                && !filenameIs(line, "LintTest.java"),
        line -> {
          line.state().sortConsumer = null;
          boolean continued = line.line().endsWith("\\");
          if (continued) {
            line.state().partialSort = "";
          } else {
            line.state().partialSort = null;
            Sort sort = Sort.parse(line.line());
            if (sort != null) {
              line.state().sortConsumer = new SortConsumer(sort);
            }
          }
        });

    // Start sorting if previous line had "lint: sort until ... \"
    b.add(
        line -> line.state().partialSort != null,
        line -> {
          String thisLine = line.line();
          boolean continued = line.line().endsWith("\\");
          if (continued) {
            thisLine = skipLast(thisLine);
          }
          String nextLine;
          if (requireNonNull(line.state().partialSort).isEmpty()) {
            nextLine = thisLine;
          } else {
            thisLine = thisLine.replaceAll("^ *(// )?", "");
            nextLine = line.state().partialSort + thisLine;
          }
          if (continued) {
            line.state().partialSort = nextLine;
          } else {
            line.state().partialSort = null;
            Sort sort = Sort.parse(nextLine);
            if (sort != null) {
              line.state().sortConsumer = new SortConsumer(sort);
            }
          }
        });

    // In 'for (int i : list)', colon must be surrounded by space.
    b.add(
        line ->
            line.matches("^ *for \\(.*:.*")
                && !line.matches(".*[^ ][ ][:][ ][^ ].*")
                && !line.matches(".*[^ ][ ][:]$")
                && isJava(line.filename()),
        line -> line.state().message(line, "':' must be surrounded by ' '"));
  }

  private static String skipLast(String s) {
    return s.substring(0, s.length() - 1);
  }

  private static void addProgram1(Puffin.Builder<GlobalState, FileState> b) {
    // Broken string, "latch" + "string", should be "latchstring".
    b.add(
        line ->
            line.matches("^[^\"]*[\"][^\"]*[\"] *\\+ *[\"].*$")
                && !line.contains("//")
                && isJava(line.filename()),
        line -> line.state().message(line, "broken string"));

    // Broken string, "yoyo\n" + "string", should be on separate lines.
    b.add(
        line ->
            line.matches(".*[^\\\\][\\\\]n[\"] *\\+ *[\"].*")
                && !line.contains("//")
                && isJava(line.filename()),
        line -> line.state().message(line, "broken string"));

    // Newline should be at end of string literal, not in the middle
    b.add(
        line ->
            line.matches("^.*\\\\n[^\"]+[\"][^\"]*$")
                && !line.contains("//")
                && !line.contains("\\\\n")
                && isJava(line.filename()),
        line ->
            line.state()
                .message(line, "newline should be at end of string literal"));

    // Javadoc does not require '</p>', so we do not allow '</p>'
    b.add(
        line -> line.state().inJavadoc() && line.contains("</p>"),
        line -> line.state().message(line, "no '</p>'"));

    // No "**/"
    b.add(
        line -> line.contains(" **/") && line.state().inJavadoc(),
        line -> line.state().message(line, "no '**/'; use '*/'"));

    // A Javadoc paragraph '<p>' must not be on its own line.
    b.add(
        line -> line.matches("^ *\\* <p>"),
        line -> line.state().message(line, "<p> must not be on its own line"));

    // A Javadoc paragraph '<p>' must be preceded by a blank Javadoc
    // line.
    b.add(
        line -> line.matches("^ *\\*"),
        line -> {
          final FileState f = line.state();
          if (f.starLine == line.fnr() - 1) {
            f.message(line, "duplicate empty line in javadoc");
          }
          f.starLine = line.fnr();
        });

    b.add(
        line ->
            line.matches("^ *\\* <p>.*")
                && line.fnr() - 1 != line.state().starLine,
        line ->
            line.state().message(line, "<p> must be preceded by blank line"));

    // A non-blank line following a blank line must have a '<p>'
    b.add(
        line ->
            line.state().inJavadoc()
                && line.state().ulCount == 0
                && line.state().blockquoteCount == 0
                && line.contains("* ")
                && line.fnr() - 1 == line.state().starLine
                && line.matches("^ *\\* [^<@].*"),
        line -> line.state().message(line, "missing '<p>'"));

    // The first "@param" of a javadoc block must be preceded by a blank
    // line.
    b.add(
        line -> line.matches("^ */\\*\\*.*"),
        line -> {
          final FileState f = line.state();
          f.javadocStartLine = line.fnr();
          f.blockquoteCount = 0;
          f.ulCount = 0;
        });

    b.add(
        line -> line.matches(".*\\*/"),
        line -> line.state().javadocEndLine = line.fnr());
    b.add(
        line -> line.matches("^ *\\* @.*"),
        line -> {
          if (line.state().inJavadoc()
              && line.state().atLine < line.state().javadocStartLine
              && line.fnr() - 1 != line.state().starLine) {
            line.state()
                .message(line, "First @tag must be preceded by blank line");
          }
          line.state().atLine = line.fnr();
        });
    b.add(
        line -> line.contains("<blockquote>"),
        line -> line.state().blockquoteCount++);
    b.add(
        line -> line.contains("</blockquote>"),
        line -> line.state().blockquoteCount--);
    b.add(line -> line.contains("<ul>"), line -> line.state().ulCount++);
    b.add(line -> line.contains("</ul>"), line -> line.state().ulCount--);

    // In markdown, <code> and </code> must be on same line
    b.add(
        line ->
            line.contains("code>")
                && !line.source()
                    .fileOpt()
                    .filter(f -> f.getName().equals("LintTest.java"))
                    .isPresent(),
        line -> {
          int openCount = count(line.line(), "<code>");
          int closeCount = count(line.line(), "</code>");
          if (openCount != closeCount) {
            line.state()
                .message(line, "<code> and </code> must be on same line");
          }
        });

    // README.md must have a line "morel-java version x.y.z (java version ...)"
    final String versionString = JavaVersion.MOREL_VERSION.toString();
    b.add(
        line ->
            filenameIs(line, "README.md")
                && line.startsWith("morel-java version "),
        line -> {
          line.state().versionCount++;
          final String version = line.line().split(" ")[2];
          if (!version.equals(versionString)) {
            line.state()
                .message(
                    line,
                    "Version '%s' should match '%s'",
                    version,
                    JavaVersion.MOREL_VERSION);
          }
        });

    // README.md must have a line "<version>x.y.z</version>"
    final String versionLine =
        "<version>" + JavaVersion.MOREL_VERSION + "</version>";
    b.add(
        line -> filenameIs(line, "README.md") && line.matches("  <version>.*"),
        line -> {
          line.state().versionCount++;
          final String version = line.line().split(" ")[2];
          if (!line.line().contains(versionLine)) {
            line.state()
                .message(
                    line,
                    "Version '%s' should match '%s'",
                    version,
                    JavaVersion.MOREL_VERSION);
          }
        });

    // README must have a line "Morel release x.y.z"
    b.add(
        line ->
            filenameIs(line, "README")
                && line.startsWith("Morel Java release "),
        line -> {
          line.state().versionCount++;
          final String version = line.line().split(" ")[3];
          if (!version.equals(versionString)) {
            line.state()
                .message(
                    line,
                    "Version '%s' should match '%s'",
                    version,
                    JavaVersion.MOREL_VERSION);
          }
        });
    b.add(
        line -> line.isLast(),
        line -> {
          int expectedVersionCount =
              filenameIs(line, "README.md")
                  ? 2
                  : filenameIs(line, "README") ? 1 : 0;
          if (expectedVersionCount != line.state().versionCount) {
            line.state()
                .message(
                    line,
                    "Version should appear %d times but appears %d times",
                    expectedVersionCount,
                    line.state().versionCount);
          }
        });
  }

  private static void addProgram2(Puffin.Builder<GlobalState, FileState> b) {
    // Fully-qualified class name (e.g. "java.util.List") should use import.
    // Allowed in: imports, package declarations, javadoc @link/@see, comments,
    // string literals, and lines with "// lint:skip".
    b.add(
        line ->
            line.matches(
                    ".*\\b(java|javax|com|net|org)\\.[a-z]+(\\.[a-z]+)*\\.[A-Z].*")
                && isJava(line.filename())
                && !line.startsWith("import ")
                && !line.startsWith("import static ")
                && !line.startsWith("package ")
                && !line.matches("^ */\\*.*")
                && !line.matches("^ *\\* .*")
                && !line.matches("^ *\\*\\*.*")
                && !line.matches("^ *//.*")
                && !line.contains("{@link ")
                && !line.contains("{@code ")
                && !lintSkip(line)
                && !isInStringLiteral(line.line()),
        line ->
            line.state()
                .message(line, "fully-qualified class name; use import"));
  }

  /**
   * Returns whether lint is disabled for this line by a "// lint:skip" or "//
   * lint:skip N" comment on this or a preceding line.
   */
  private static boolean lintSkip(Puffin.Line<GlobalState, FileState> line) {
    return line.fnr() < line.state().lintEnableLine;
  }

  private static boolean filenameIs(
      Puffin.Line<GlobalState, FileState> line, String anObject) {
    return line.source()
        .fileOpt()
        .filter(f -> f.getName().equals(anObject))
        .isPresent();
  }

  /**
   * Returns the comment style for a file based on its extension, or null if the
   * file type doesn't require an end marker.
   */
  private static String getCommentStyleForFile(String filename) {
    // Find the file extension
    final int dot = filename.lastIndexOf('.');
    if (dot < 0) {
      return null;
    }
    final String extension = filename.substring(dot);

    switch (extension) {
      case ".java":
        return "//";
      case ".smli":
      case ".sig":
        return "(*)";
      default:
        return null;
    }
  }

  /** Returns whether we are in a file that contains Java code. */
  private static boolean isJava(String filename) {
    return filename.endsWith(".java")
        || filename.endsWith(".jj")
        || filename.endsWith(".fmpp")
        || filename.endsWith(".ftl")
        || filename.equals("GuavaCharSource{memory}"); // for testing
  }

  /**
   * Returns whether a fully-qualified class name pattern on this line occurs
   * only inside a string literal. A simple heuristic: if the pattern match
   * occurs after an odd number of unescaped double-quotes, it is inside a
   * string.
   */
  private static boolean isInStringLiteral(String line) {
    // Find each match of a fully-qualified name and check whether it occurs
    // after an odd number of unescaped double-quotes (i.e. inside a string).
    Matcher m = FULLY_QUALIFIED_PATTERN.matcher(line);
    while (m.find()) {
      int quotes = 0;
      for (int i = 0; i < m.start(); i++) {
        if (line.charAt(i) == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
          quotes++;
        }
      }
      if (quotes % 2 == 0) {
        return false; // this match is outside a string literal
      }
    }
    return true; // all matches are inside string literals
  }

  private static final Pattern FULLY_QUALIFIED_PATTERN =
      Pattern.compile(
          "\\b(java|javax|com|net|org)\\.[a-z]+(\\.[a-z]+)*\\.[A-Z]");

  /** Matches "// lint:skip" or "// lint:skip N". */
  private static final Pattern LINT_SKIP_PATTERN =
      Pattern.compile("// lint:skip(?: (\\d+))?\\s*$");

  /** Returns the number of occurrences of a string in a string. */
  private static int count(String s, String sub) {
    int count = 0;
    for (int i = 0; i < s.length(); ) {
      i = s.indexOf(sub, i);
      if (i < 0) {
        break;
      }
      count++;
      i += sub.length();
    }
    return count;
  }

  @Test
  void testProgramWorks() {
    final String code =
        "class MyClass {\n"
            + "  /** Paragraph.\n"
            + "   *\n"
            + "   * Missing p.\n"
            + "   *\n"
            + "   * <p>\n"
            + "   * <p>A paragraph (p must be preceded by blank line).\n"
            + "   *\n"
            + "   *\n"
            + "   * <p>no p</p>\n"
            + "   * @see java.lang.String (should be preceded by blank line)\n"
            + "   **/\n"
            + "  String x = \"ok because it's not in javadoc:</p>\";\n"
            + "  for (Map.Entry<String, Integer> e: entries) {\n"
            + "    //comment without space\n"
            + "  }\n"
            + "  for (int i :tooFewSpacesAfter) {\n"
            + "  }\n"
            + "  for (int i  : tooManySpacesBefore) {\n"
            + "  }\n"
            + "  for (int i :   tooManySpacesAfter) {\n"
            + "  }\n"
            + "  for (int i : justRight) {\n"
            + "  }\n"
            + "  for (int i :\n"
            + "     alsoFine) {\n"
            + "  }\n"
            + "  String bad = \"broken\" + \"string\";\n"
            + "  String bad2 = \"string with\\nembedded newline\";\n"
            + "  String bad3 = \"string with\\n"
            + " embedded newline\";\n"
            + "  String good = \"string with newline\\n\"\n"
            + "      \"at end of line\";\n"
            + "  // A comment with <code>on one line and\n"
            + "  // </code> on the next.\n"
            + "  java.util.List list = null;\n"
            + "  /** See {@link java.util.List}. */\n"
            + "  String s = \"java.util.List is ok in a string\";\n"
            + "  // java.util.List is ok in a comment\n"
            + "  Ast.Literal ok = null;\n"
            + "  Object o = new org.foo.Bar();\n"
            + "}\n";
    final String expectedMessages =
        "["
            + "GuavaCharSource{memory}:4:"
            + "missing '<p>'\n"
            + "GuavaCharSource{memory}:6:"
            + "<p> must not be on its own line\n"
            + "GuavaCharSource{memory}:7:"
            + "<p> must be preceded by blank line\n"
            + "GuavaCharSource{memory}:9:"
            + "duplicate empty line in javadoc\n"
            + "GuavaCharSource{memory}:10:"
            + "no '</p>'\n"
            + "GuavaCharSource{memory}:11:"
            + "First @tag must be preceded by blank line\n"
            + "GuavaCharSource{memory}:12:"
            + "no '**/'; use '*/'\n"
            + "GuavaCharSource{memory}:14:"
            + "':' must be surrounded by ' '\n"
            + "GuavaCharSource{memory}:15:"
            + "'//' must be followed by ' '\n"
            + "GuavaCharSource{memory}:17:"
            + "':' must be surrounded by ' '\n"
            + "GuavaCharSource{memory}:19:"
            + "':' must be surrounded by ' '\n"
            + "GuavaCharSource{memory}:21:"
            + "':' must be surrounded by ' '\n"
            + "GuavaCharSource{memory}:28:"
            + "broken string\n"
            + "GuavaCharSource{memory}:29:"
            + "newline should be at end of string literal\n"
            + "GuavaCharSource{memory}:30:"
            + "newline should be at end of string literal\n"
            + "GuavaCharSource{memory}:33:"
            + "<code> and </code> must be on same line\n"
            + "GuavaCharSource{memory}:34:"
            + "<code> and </code> must be on same line\n"
            + "GuavaCharSource{memory}:35:"
            + "fully-qualified class name; use import\n"
            + "GuavaCharSource{memory}:40:"
            + "fully-qualified class name; use import\n";
    final Puffin.Program<GlobalState> program = makeProgram();
    final StringWriter sw = new StringWriter();
    final GlobalState g;
    try (PrintWriter pw = new PrintWriter(sw)) {
      g = program.execute(Stream.of(Sources.of(code)), pw);
    }
    assertThat(
        g.messages.toString().replace(", ", "\n").replace(']', '\n'),
        is(expectedMessages));
  }

  /** Tests that source code has no flaws. */
  @Test
  void testLint() {
    assumeTrue(TestUnsafe.haveGit(), "Invalid git environment");

    final Puffin.Program<GlobalState> program = makeProgram();
    final List<File> javaFiles = TestUnsafe.getTextFiles();

    final GlobalState g;
    StringWriter b = new StringWriter();
    try (PrintWriter pw = new PrintWriter(b)) {
      g = program.execute(javaFiles.parallelStream().map(Sources::of), pw);
    }

    for (Message message : g.messages) {
      System.out.println(message);
    }
    assertThat("Lint violations:\n" + b, g.messages, empty());
  }

  /** Tests the Sort specification syntax. */
  @Test
  void testSort() {
    // With "until" and "where"
    checkSortSpec(
        "class Test {\n"
            + "  switch (x) {\n"
            + "  // lint: sort until '#}' where '##case '\n"
            + "  case a\n"
            + "  case c\n"
            + "  case d\n"
            + "  case b\n"
            + "  case e\n"
            + "  }\n"
            + "}\n",
        "GuavaCharSource{memory}:7:"
            + "Lines must be sorted; '  case b' should be before '  case c'");

    // With "until" and "where"; cases after "until" should be ignored.
    checkSortSpec(
        "class Test {\n"
            + "  switch (x) {\n"
            + "  // lint: sort until '#}' where '##case '\n"
            + "  case x\n"
            + "  case y\n"
            + "  case z\n"
            + "  }\n"
            + "  switch (y) {\n"
            + "  case a\n"
            + "  }\n"
            + "}\n",
        "GuavaCharSource{memory}:9:"
            + "Lines must be sorted; '  case a' should be before '  case x'");

    // Change '##}' to '#}' to make the test pass.
    checkSortSpec(
        "class Test {\n"
            + "  switch (x) {\n"
            + "  // lint: sort until '##}' where '##case '\n"
            + "  case x\n"
            + "  case y\n"
            + "  case z\n"
            + "  }\n"
            + "  switch (y) {\n"
            + "  case a\n"
            + "  }\n"
            + "}\n");

    // Specification has "until", "where" and "erase" clauses.
    checkSortSpec(
        "class Test {\n"
            + "  // lint: sort until '#}' where '##A::' erase '^ .*::'\n"
            + "  A::c\n"
            + "  A::a\n"
            + "  A::b\n"
            + "  }\n"
            + "}\n",
        "GuavaCharSource{memory}:4:"
            + "Lines must be sorted; 'a' should be before 'c'");

    // Specification is spread over multiple lines.
    checkSortSpec(
        "class Test {\n"
            + "  // lint: sort until '#}'\\\n"
            + "  // where '##A::'\\\n"
            + "  // erase '^ .*::'\n"
            + "  A::c\n"
            + "  A::a\n"
            + "  A::b\n"
            + "  }\n"
            + "}\n",
        "GuavaCharSource{memory}:6:"
            + "Lines must be sorted; 'a' should be before 'c'");
  }

  private void checkSortSpec(String code, String... expectedMessages) {
    final Puffin.Program<GlobalState> program = makeProgram();
    final StringWriter sw = new StringWriter();
    final GlobalState g;
    try (PrintWriter pw = new PrintWriter(sw)) {
      g = program.execute(Stream.of(Sources.of(code)), pw);
    }
    assertThat(g.messages, hasToString(Arrays.toString(expectedMessages)));
  }

  /** Parses the "reference.md" file. */
  @Test
  void testFunctionTable() throws IOException {
    File baseDir = TestUtils.getBaseDir(TestUtils.class);
    final File file = new File(baseDir, "docs/reference.md");
    final File genFile = new File(baseDir, "target/reference.md");
    try (Reader r = new FileReader(file);
        BufferedReader br = new BufferedReader(r);
        Writer w = new FileWriter(genFile);
        PrintWriter pw = new PrintWriter(w)) {
      boolean emit = true;
      for (; ; ) {
        String line = br.readLine();
        if (line == null) {
          break;
        }
        if (line.equals("{% comment %}END TABLE{% endcomment %}")) {
          emit = true;
        }
        if (emit) {
          pw.println(line);
        }
        if (line.equals("{% comment %}START TABLE{% endcomment %}")) {
          emit = false;
          Generation.generateFunctionTable(pw);
        }
      }
    }

    final String diff = TestUtils.diff(file, genFile);
    if (!diff.isEmpty()) {
      fail(
          "Files differ: "
              + file
              + " "
              + genFile
              + "\n" //
              + diff);
    }
  }

  /**
   * Validates that signature files in the lib directory are well-formed and
   * that their value and exception declarations match the corresponding entries
   * in the {@link net.hydromatic.morel.compile.BuiltIn} and {@link
   * net.hydromatic.morel.eval.Codes.BuiltInExn} enums.
   */
  @Test
  void testSignatures() throws Exception {
    final File libDir = new File("lib");
    assertThat(libDir.exists(), is(true));
    assertThat(libDir.isDirectory(), is(true));

    final File[] files =
        libDir.listFiles(
            (dir, name) -> name.endsWith(".sig") || name.endsWith(".sml"));
    assertThat("no files to test in lib directory", files, notNullValue());

    final SignatureChecker checker = new SignatureChecker();
    for (File file : files) {
      checker.checkSignatureFile(file);
    }
  }

  /** Warning that code is not as it should be. */
  private static class Message {
    final Source source;
    final int line;
    final String message;

    Message(Source source, int line, String message) {
      this.source = source;
      this.line = line;
      this.message = message;
    }

    @Override
    public String toString() {
      return source + ":" + line + ":" + message;
    }
  }

  /** Internal state of the lint rules. */
  private static class GlobalState {
    int fileCount = 0;
    final List<Message> messages = new ArrayList<>();
  }

  /** Internal state of the lint rules, per file. */
  private static class FileState {
    final GlobalState global;
    @Nullable String partialSort;
    @Nullable Consumer<Puffin.Line<GlobalState, FileState>> sortConsumer;
    int versionCount;
    int starLine;
    int atLine;
    int javadocStartLine;
    int javadocEndLine;
    int blockquoteCount;
    int ulCount;
    int lintEnableLine;

    FileState(GlobalState global) {
      this.global = global;
    }

    void message(
        Puffin.Line<GlobalState, FileState> line,
        String format,
        Object... args) {
      String message = format(format, args);
      global.messages.add(new Message(line.source(), line.fnr(), message));
    }

    public boolean inJavadoc() {
      return javadocEndLine < javadocStartLine;
    }
  }

  /**
   * Specification for a sort directive, parsed from comments like {@code //
   * lint: sort until 'pattern' where 'filter' erase 'prefix'}.
   *
   * <p>Supports indentation placeholders:
   *
   * <ul>
   *   <li>{@code ##} - current line's indentation
   *   <li>{@code #} - one level up (2 fewer spaces)
   * </ul>
   */
  private static class Sort {
    final Pattern until;
    final @Nullable Pattern where;
    final @Nullable Pattern erase;
    final int indent;

    Sort(
        Pattern until,
        @Nullable Pattern where,
        @Nullable Pattern erase,
        int indent) {
      this.until = until;
      this.where = where;
      this.erase = erase;
      this.indent = indent;
    }

    /**
     * Parses a sort directive from a line like {@code // lint: sort until 'X'
     * where 'Y' erase 'Z'}.
     *
     * @param line the line containing the directive
     * @return parsed Sort specification, or null if parsing fails
     */
    static @Nullable Sort parse(String line) {
      // Extract everything after "lint: sort"
      int sortIndex = line.indexOf("lint: sort");
      if (sortIndex < 0) {
        return null;
      }

      String spec = line.substring(sortIndex + "lint: sort".length()).trim();

      // Count leading spaces for indentation
      int indent = 0;
      while (indent < line.length() && line.charAt(indent) == ' ') {
        indent++;
      }

      // Parse until clause (required)
      Pattern until = extractPattern(spec, "until", indent);
      if (until == null) {
        return null;
      }

      // Parse optional where clause
      Pattern where = extractPattern(spec, "where", indent);

      // Parse optional erase clause
      Pattern erase = extractPattern(spec, "erase", indent);

      return new Sort(until, where, erase, indent);
    }

    /**
     * Extracts a pattern from a clause like {@code until 'pattern'} or {@code
     * where 'pattern'}.
     */
    private static @Nullable Pattern extractPattern(
        String spec, String keyword, int indent) {
      int keywordIndex = spec.indexOf(keyword);
      if (keywordIndex < 0) {
        return null;
      }

      String rest = spec.substring(keywordIndex + keyword.length()).trim();
      if (rest.isEmpty() || rest.charAt(0) != '\'') {
        return null;
      }

      int endQuote = rest.indexOf('\'', 1);
      if (endQuote < 0) {
        return null;
      }

      String pattern = rest.substring(1, endQuote);
      // Replace indentation placeholders
      pattern = pattern.replace("##", "^" + repeat(" ", indent));
      pattern =
          pattern.replace("#", "^" + repeat(" ", Math.max(0, indent - 2)));

      try {
        return Pattern.compile(pattern);
      } catch (Exception e) {
        return null;
      }
    }
  }

  /** Consumer that checks that are sorted. */
  private static class SortConsumer
      implements Consumer<Puffin.Line<GlobalState, FileState>> {
    final Sort sort;
    final Comparator<String> comparator = WordComparator.INSTANCE;
    final List<String> lines = new ArrayList<>();
    boolean done = false;

    SortConsumer(Sort sort) {
      this.sort = sort;
    }

    @Override
    public void accept(Puffin.Line<GlobalState, FileState> line) {
      if (done) {
        return;
      }

      String thisLine = line.line();

      // Check if we've reached the end marker
      if (sort.until.matcher(thisLine).find()) {
        done = true;
        // Clear the consumer from state to stop processing
        line.state().sortConsumer = null;
        return;
      }

      // If where clause exists, only process matching lines.
      if (sort.where != null && !sort.where.matcher(thisLine).find()) {
        return;
      }

      // Apply the erase pattern, if present.
      String compareLine = thisLine;
      if (sort.erase != null) {
        compareLine = sort.erase.matcher(thisLine).replaceAll("");
      }

      addLine(line, compareLine);
    }

    protected void addLine(
        Puffin.Line<GlobalState, FileState> line, String thisLine) {
      if (!lines.isEmpty()) {
        String prevLine = last(lines);
        if (comparator.compare(prevLine, thisLine) > 0) {
          String earlierLine =
              Util.filter(lines, s -> comparator.compare(s, thisLine) > 0)
                  .iterator()
                  .next();
          String format = "Lines must be sorted; '%s' should be before '%s'";
          line.state().message(line, format, thisLine, earlierLine);
        }
      }
      lines.add(thisLine);
    }
  }
}

// End LintTest.java
