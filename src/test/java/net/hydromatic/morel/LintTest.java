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
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.not;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.parse.MorelParserImplConstants;
import net.hydromatic.morel.parse.Parsers;
import net.hydromatic.morel.util.Generation;
import net.hydromatic.morel.util.JavaVersion;
import net.hydromatic.morel.util.PairList;
import net.hydromatic.morel.util.WordComparator;
import org.apache.calcite.util.Puffin;
import org.apache.calcite.util.Source;
import org.apache.calcite.util.Sources;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

/** Runs Lint-like checks on the source code. Also tests those checks. */
@SuppressWarnings("Convert2MethodRef") // JDK 8 requires lambdas
public class LintTest {
  private static final ThreadLocal<@Nullable String> THREAD_FILE_NAME =
      new ThreadLocal<>();

  /**
   * Snapshot of the {@code lib/*.sig} model. Loaded once for the test class;
   * shared across all tests in {@link LintTest} so we parse each signature file
   * only once.
   */
  private static final Generation.Model MODEL;

  static {
    try {
      MODEL = Generation.loadModel();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Matches a class/enum/interface declaration. Captures the type name. */
  private static final Pattern CLASS_DECL_PAT =
      Pattern.compile("(?:^|\\W)(?:class|enum|interface)\\s+([A-Z][\\w$]*)\\b");

  private static final Pattern FULLY_QUALIFIED_PATTERN =
      Pattern.compile(
          "\\b(java|javax|com|net|org)\\.[a-z]+(\\.[a-z]+)*\\.[A-Z]");

  /** Matches "// lint:skip" or "// lint:skip N". */
  private static final Pattern LINT_SKIP_PATTERN =
      Pattern.compile("// lint:skip(?: (\\d+))?\\s*$");

  /** Maximum line length in Java files. */
  public static final int JAVA_WIDTH = 80;

  /** Maximum line length in Markdown files. */
  public static final int MD_WIDTH = 80;

  /** Maximum line length in Morel files. */
  public static final int MOREL_WIDTH = 70;

  private Puffin.Program<GlobalState> makeProgram() {
    Puffin.Builder<GlobalState, FileState> b =
        Puffin.builder(GlobalState::new, global -> new FileState(global));
    addProgram0(b);
    addProgram1(b);
    addProgram2(b);
    addProgram3(b);
    addProgram4(b);
    return b.build();
  }

  private static void addProgram0(Puffin.Builder<GlobalState, FileState> b) {
    // Set language
    b.add(
        line -> line.fnr() == 1,
        line -> {
          String filename = THREAD_FILE_NAME.get();
          if (filename == null) {
            filename = line.filename();
          }
          line.state().language = languageOf(filename);
        });

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
          final String comment = line.state().language.comment;
          if (comment != null) {
            final String endMarker =
                comment + " End " + (slash < 0 ? f : f.substring(slash + 1));
            if (!line.line().equals(endMarker)
                && !line.filename().equals("GuavaCharSource{memory}")) {
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
            line.state().language == Language.JAVA
                && line.matches("^ *for \\(.*:.*")
                && !line.matches(".*[^ ][ ][:][ ][^ ].*")
                && !line.matches(".*[^ ][ ][:]$"),
        line -> line.state().message(line, "':' must be surrounded by ' '"));
  }

  private static String skipLast(String s) {
    return s.substring(0, s.length() - 1);
  }

  private static void addProgram1(Puffin.Builder<GlobalState, FileState> b) {
    // Broken string, "latch" + "string", should be "latchstring".
    b.add(
        line ->
            line.state().language == Language.JAVA
                && line.matches("^[^\"]*[\"][^\"]*[\"] *\\+ *[\"].*$")
                && !line.contains("//"),
        line -> line.state().message(line, "broken string"));

    // Broken string, "yoyo\n" + "string", should be on separate lines.
    b.add(
        line ->
            line.state().language == Language.JAVA
                && line.matches(".*[^\\\\][\\\\]n[\"] *\\+ *[\"].*")
                && !line.contains("//"),
        line -> line.state().message(line, "broken string"));

    // Newline should be at end of string literal, not in the middle
    b.add(
        line ->
            line.state().language == Language.JAVA
                && line.matches("^.*\\\\n[^\"]+[\"][^\"]*$")
                && !line.contains("//")
                && !line.contains("\\\\n"),
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

    // In Markdown, <code> and </code> must be on same line
    b.add(
        line ->
            line.state().language == Language.MARKDOWN
                && line.contains("code>"),
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
            line.state().language == Language.JAVA
                && line.matches(
                    ".*\\b(java|javax|com|net|org)\\.[a-z]+(\\.[a-z]+)*\\.[A-Z].*")
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

  private static void addProgram3(Puffin.Builder<GlobalState, FileState> b) {
    // Rule: track Morel block comment depth and validate block comments.
    b.add(
        line -> line.state().language == Language.MOREL,
        line -> {
          final FileState f = line.state();
          final String s = line.line();

          // If the previous line was a block-open "(*", determine
          // whether this is a real block comment or commented-out code.
          if (f.pendingBlockOpenLine == line.fnr() - 1) {
            if (s.startsWith(" *")) {
              f.commentStartLine = f.pendingBlockOpenLine;
            }
            f.pendingBlockOpenLine = 0;
          }

          // Validate block comment intermediate lines.
          if (f.commentStartLine > 0
              && f.commentDepth == 1
              && f.pendingBlockOpenLine == 0) {
            if (!s.trim().equals("*)")) {
              if (!s.startsWith(" *")) {
                f.message(line, "block comment line must start with ' *'");
              }
              if (s.length() > MOREL_WIDTH
                  && !s.contains("http://")
                  && !s.contains("https://")) {
                f.message(
                    line,
                    "block comment line length %d > %d",
                    s.length(),
                    MOREL_WIDTH);
              }
            }
          }

          // Track comment depth.
          int i = 0;
          while (i < s.length() - 1) {
            if (s.charAt(i) == '(' && s.charAt(i + 1) == '*') {
              // Skip "(*)" — line comment, not block open
              if (i + 2 < s.length() && s.charAt(i + 2) == ')') {
                i += 3;
                continue;
              }
              f.commentDepth++;
              if (f.commentDepth == 1) {
                // Check if '(*' is the entire trimmed line
                if (s.trim().equals("(*")) {
                  f.pendingBlockOpenLine = line.fnr();
                }
              }
              i += 2;
            } else if (s.charAt(i) == '*' && s.charAt(i + 1) == ')') {
              f.commentDepth--;
              if (f.commentDepth == 0) {
                f.commentStartLine = 0;
                f.pendingBlockOpenLine = 0;
              }
              i += 2;
            } else {
              i++;
            }
          }
        });

    // Rule: decorative single-line headers with "---" must be MOREL_WIDTH (70)
    // chars.
    b.add(
        line ->
            line.state().language == Language.MOREL
                && line.line().startsWith("(*")
                && (line.line().endsWith("*)") || line.line().endsWith("--"))
                && line.line().contains("---"),
        line -> {
          if (line.line().length() != MOREL_WIDTH) {
            line.state()
                .message(
                    line,
                    "decorative comment length %d != %d",
                    line.line().length(),
                    MOREL_WIDTH);
          }
        });

    // Rule: "(*)" line with "---" is a decorative header that should
    // be a block comment "(* --- ... *)".
    b.add(
        line ->
            line.state().language == Language.MOREL
                && line.line().startsWith("(*)")
                && line.line().contains("---"),
        line ->
            line.state()
                .message(line, "decorative comment should be '(* --- ... *)'"));

    // Rule: decorative headers with "===" or "***" should use "---".
    b.add(
        line ->
            line.state().language == Language.MOREL
                && line.line().startsWith("(*")
                && (line.line().contains("===") || line.line().contains("***")),
        line ->
            line.state()
                .message(
                    line,
                    "decorative comment; use '---' not '%s'",
                    line.line().contains("***") ? "***" : "==="));
  }

  private static void addProgram4(Puffin.Builder<GlobalState, FileState> b) {
    // Track generated blocks in Markdown ([//]: # (start:...) / (end:...))
    b.add(
        line ->
            line.state().language == Language.MARKDOWN
                && line.line().startsWith("[//]: # (start:"),
        line -> line.state().inGeneratedBlock = true);
    b.add(
        line ->
            line.state().language == Language.MARKDOWN
                && line.line().startsWith("[//]: # (end:"),
        line -> line.state().inGeneratedBlock = false);

    // Markdown: line length check (MD_WIDTH chars, with exceptions)
    b.add(
        line ->
            line.state().language == Language.MARKDOWN
                && line.line().length() > MD_WIDTH
                && !line.state().inCodeBlock
                && !line.state().inPreBlock
                && !line.state().inComment
                && !line.state().inGeneratedBlock
                && !line.line().contains("http://")
                && !line.line().contains("https://")
                && !line.line().contains("src=\"") // HTML img tags
                && !line.line().contains("href=\"") // HTML link tags
                && !line.line().startsWith("|") // table row
                && !line.line().startsWith("```")
                && !line.line().startsWith("    ") // indented code
                && !line.line().startsWith("<i>") // syntax definition
                && !line.line().contains("<pre class=") // pre blocks w/ attrs
                && !line.line().contains("<div class=") // div blocks w/ attrs
                && !line.line().matches("^[-|:]+$") // table separator
                && !lintSkip(line),
        line ->
            line.state()
                .message(
                    line,
                    "line length %d > %d",
                    line.line().length(),
                    MD_WIDTH));

    // Track code blocks in Markdown (``` fences)
    b.add(
        line ->
            line.state().language == Language.MARKDOWN
                && line.line().startsWith("```"),
        line -> line.state().inCodeBlock = !line.state().inCodeBlock);

    // Track <pre> and <div class="code-"> blocks in Markdown
    b.add(
        line ->
            line.state().language == Language.MARKDOWN
                && (line.line().contains("<pre>")
                    || line.line().contains("<pre ")
                    || line.line().contains("<div class=\"code-")),
        line -> line.state().inPreBlock = true);
    b.add(
        line ->
            line.state().language == Language.MARKDOWN
                && (line.line().contains("</pre>")
                    || line.line().contains("</div>")),
        line -> line.state().inPreBlock = false);

    // Track Jekyll comment blocks
    b.add(
        line ->
            line.state().language == Language.MARKDOWN
                && line.line().contains("{% comment %}"),
        line -> line.state().inComment = true);
    b.add(
        line ->
            line.state().language == Language.MARKDOWN
                && line.line().contains("{% endcomment %}"),
        line -> line.state().inComment = false);

    // Markdown files in docs/ must have license header
    b.add(
        line ->
            line.state().language == Language.MARKDOWN
                && line.fnr() == 1
                && line.filename().contains("/docs/")
                && !line.line().equals("<!--"),
        line -> line.state().message(line, "missing license header"));

    // Markdown license header must start with "<!--"
    b.add(
        line ->
            line.state().language == Language.MARKDOWN
                && line.fnr() == 1
                && !line.filename().contains("/docs/")
                && !filenameIs(line, "HISTORY.md")
                && !filenameIs(line, "README.md")
                && !line.line().equals("<!--"),
        line -> line.state().message(line, "missing license header"));

    // Detect class/enum with one than one primary constructor.
    b.add(
        line -> line.state().language == Language.JAVA,
        line -> processPrimaryConstructorLine(line));
  }

  /** Single-line state-machine step for the primary-constructor rule. */
  private static void processPrimaryConstructorLine(
      Puffin.Line<GlobalState, FileState> line) {
    final FileState f = line.state();
    final String code = stripJavaLine(line.line(), f);

    // 1. Detect class/enum/interface declaration on this line.
    Matcher cm = CLASS_DECL_PAT.matcher(code);
    while (cm.find()) {
      f.pendingClassName = cm.group(1);
    }

    // 2. Detect a constructor signature for the innermost class. Only fires
    // when this line is at the immediate top of the class body, and we are
    // not already mid-way through processing a previous constructor.
    if (!f.classStack.isEmpty()
        && f.javaBraceDepth == f.classStack.peek().depth
        && f.pendingCtorSigLine == 0) {
      final ClassFrame top = f.classStack.element();
      final Pattern ctorPat =
          Pattern.compile(
              "^\\s*(?:public|private|protected|abstract|final|static"
                  + "|\\s)*\\b"
                  + Pattern.quote(top.name)
                  + "\\s*\\(");
      if (ctorPat.matcher(code).find()) {
        f.pendingCtorSigLine = line.fnr();
        f.pendingCtorSawBrace = false;
      }
    }

    // 3. If we are inside a pending constructor body, look for the body's
    // first non-trivial statement and classify primary vs delegating.
    if (f.pendingCtorSigLine > 0) {
      classifyPendingConstructor(line, code);
    }

    // 4. Update brace depth for this line.
    f.javaBraceDepth += countChar(code, '{') - countChar(code, '}');

    // 5. Push the pending class once its opening brace has appeared.
    if (f.pendingClassName != null && code.indexOf('{') >= 0) {
      f.classStack.push(new ClassFrame(f.pendingClassName, f.javaBraceDepth));
      f.pendingClassName = null;
    }

    // 6. Pop class frames whose body has been exited.
    while (!f.classStack.isEmpty()
        && f.javaBraceDepth < f.classStack.peek().depth) {
      f.classStack.pop();
    }
  }

  /**
   * Looks at {@code code} (the stripped current line) for the body of the
   * currently pending constructor. Reports a violation if this turns out to be
   * a second primary constructor.
   */
  private static void classifyPendingConstructor(
      Puffin.Line<GlobalState, FileState> line, String code) {
    final FileState f = line.state();
    final String firstStmt;
    if (!f.pendingCtorSawBrace) {
      // Body's opening '{' has not been seen yet.
      final int braceIdx = code.indexOf('{');
      if (braceIdx < 0) {
        return;
      }
      f.pendingCtorSawBrace = true;
      firstStmt = code.substring(braceIdx + 1).trim();
      if (firstStmt.isEmpty()) {
        return; // wait for next line
      }
    } else {
      firstStmt = code.trim();
      if (firstStmt.isEmpty()) {
        return;
      }
    }

    final ClassFrame top = f.classStack.element();
    final int sigLine = f.pendingCtorSigLine;
    f.pendingCtorSigLine = 0;
    f.pendingCtorSawBrace = false;

    if (firstStmt.matches("^this\\s*\\(.*")) {
      return; // delegating
    }
    if (top.firstPrimaryLine == 0) {
      top.firstPrimaryLine = sigLine;
      return;
    }
    if (sigLine < f.lintEnableLine) {
      return; // suppressed by '// lint:skip'
    }
    f.message(
        line,
        sigLine,
        "class '%s' already has a primary constructor (A primary "
            + "constructor sets fields directly rather than delegating to "
            + "another constructor. To fix, use 'this(...)' to call the "
            + "existing primary constructor at line %d, or change it to "
            + "call this constructor.)",
        top.name,
        top.firstPrimaryLine);
  }

  /**
   * Strips line and block comments, string literals, and char literals from a
   * Java line, replacing them with spaces (so column positions are preserved).
   * Block-comment state is carried across lines via {@link
   * FileState#inJavaBlockComment}.
   */
  private static String stripJavaLine(String line, FileState f) {
    final StringBuilder sb = new StringBuilder(line.length());
    int i = 0;
    while (i < line.length()) {
      final char c = line.charAt(i);
      if (f.inJavaBlockComment) {
        if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
          sb.append("  ");
          f.inJavaBlockComment = false;
          i += 2;
        } else {
          sb.append(' ');
          i++;
        }
      } else if (c == '/'
          && i + 1 < line.length()
          && line.charAt(i + 1) == '*') {
        sb.append("  ");
        f.inJavaBlockComment = true;
        i += 2;
      } else if (c == '/'
          && i + 1 < line.length()
          && line.charAt(i + 1) == '/') {
        while (i < line.length()) {
          sb.append(' ');
          i++;
        }
      } else if (c == '"' || c == '\'') {
        sb.append(c);
        i++;
        while (i < line.length() && line.charAt(i) != c) {
          if (line.charAt(i) == '\\' && i + 1 < line.length()) {
            sb.append("  ");
            i += 2;
          } else {
            sb.append(' ');
            i++;
          }
        }
        if (i < line.length()) {
          sb.append(c);
          i++;
        }
      } else {
        sb.append(c);
        i++;
      }
    }
    return sb.toString();
  }

  private static int countChar(String s, char c) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == c) {
        n++;
      }
    }
    return n;
  }

  /** A class/enum declaration encountered while scanning a Java file. */
  private static class ClassFrame {
    final String name;
    /** Brace depth INSIDE the class body. */
    final int depth;
    /** Line of the first primary constructor seen, or 0 if none yet. */
    int firstPrimaryLine;

    ClassFrame(String name, int depth) {
      this.name = name;
      this.depth = depth;
    }
  }

  /** Deduces the language of a file from its filename. */
  private static Language languageOf(String filename) {
    if (filename.endsWith(".java")
        || filename.endsWith(".jj")
        || filename.endsWith(".fmpp")
        || filename.endsWith(".ftl")
        || filename.equals("GuavaCharSource{memory}")) { // for testing
      return Language.JAVA;
    }
    if (filename.endsWith(".sml")
        || filename.endsWith(".smli")
        || filename.endsWith(".sig")) {
      return Language.MOREL;
    }
    if (filename.endsWith(".md")) {
      return Language.MARKDOWN;
    }
    return Language.UNKNOWN;
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

  private String programResult(String fileName, String code) {
    final Puffin.Program<GlobalState> program = makeProgram();
    final StringWriter sw = new StringWriter();
    final GlobalState g;
    try (PrintWriter pw = new PrintWriter(sw)) {
      THREAD_FILE_NAME.set(fileName);
      g = program.execute(Stream.of(Sources.of(code)), pw);
    } finally {
      THREAD_FILE_NAME.remove();
    }
    return g.messages.toString().replace(", ", "\n").replace(']', '\n');
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
            + "GuavaCharSource{memory}:35:"
            + "fully-qualified class name; use import\n"
            + "GuavaCharSource{memory}:40:"
            + "fully-qualified class name; use import\n";
    assertThat(programResult("Foo.java", code), is(expectedMessages));
  }

  @Test
  void testProgramWorksMorel() {
    final String code =
        "(* single-line comment *)\n"
            + "(*\n"
            + " * good block\n"
            + " * comment\n"
            + " *)\n"
            + "(*\n"
            + "commented out code\n"
            + "*)\n"
            + "(* Good header ----------------------------------------"
            + "------------ *)\n"
            + "(*\n"
            + " * good block line\n"
            + " bad block line\n"
            + " *)\n"
            + "(* short header -------- *)\n"
            + "(*** bad stars ***)\n"
            + "(*) End test.smli\n";
    final String expectedMessages =
        "["
            + "GuavaCharSource{memory}:12:"
            + "block comment line must start with ' *'\n"
            + "GuavaCharSource{memory}:14:"
            + "decorative comment length 27 != "
            + MOREL_WIDTH
            + "\n"
            + "GuavaCharSource{memory}:15:"
            + "decorative comment; use '---' not '***'\n";
    assertThat(programResult("foo.smli", code), is(expectedMessages));
  }

  @Test
  void testProgramWorksMarkdown() {
    final String code =
        "  // A comment with <code>on one line and\n"
            + "  // </code> on the next.\n";
    final String expectedMessages =
        "["
            + "GuavaCharSource{memory}:1:"
            + "<code> and </code> must be on same line\n"
            + "GuavaCharSource{memory}:1:"
            + "missing license header\n"
            + "GuavaCharSource{memory}:2:"
            + "<code> and </code> must be on same line\n";
    final String fileName = "foo.md";
    final String replace = programResult(fileName, code);
    assertThat(replace, is(expectedMessages));
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

  /**
   * Tests that {@link Parsers#RESERVED_WORDS} matches the alphabetic keyword
   * tokens generated from {@code MorelParser.jj}. If this fails, a keyword was
   * added or removed; update {@code RESERVED_WORDS} to match.
   */
  @Test
  void testReservedWords() {
    final TreeSet<String> fromGrammar = new TreeSet<>();
    for (String image : MorelParserImplConstants.tokenImage) {
      // A keyword token's image is the quoted literal, e.g. "\"left\"".
      if (image.length() >= 3
          && image.charAt(0) == '"'
          && image.charAt(image.length() - 1) == '"') {
        final String word = image.substring(1, image.length() - 1);
        if (word.matches("[a-z]+")) {
          fromGrammar.add(word);
        }
      }
    }
    assertThat(new TreeSet<>(Parsers.RESERVED_WORDS), is(fromGrammar));
  }

  /** Tests the primary-constructor rule against synthetic source code. */
  @Test
  void testPrimaryConstructorRule() {
    // Two primary constructors — should fail.
    final String bad =
        "class Foo {\n"
            + "  Foo(int i) {\n"
            + "    this.i = i;\n"
            + "  }\n"
            + "  Foo(String s) {\n"
            + "    this.s = s;\n"
            + "  }\n"
            + "}\n";
    String result = programResult("Foo.java", bad);
    assertThat(result, containsString("Foo"));
    assertThat(result, containsString("primary constructor"));

    // One primary, others delegate — should pass.
    final String good =
        "class Bar {\n"
            + "  private Bar(int i, String s) {\n"
            + "    this.i = i;\n"
            + "    this.s = s;\n"
            + "  }\n"
            + "  Bar(int i) {\n"
            + "    this(i, \"\");\n"
            + "  }\n"
            + "  Bar(String s) {\n"
            + "    this(0, s);\n"
            + "  }\n"
            + "}\n";
    final String goodResult = programResult("Bar.java", good);
    assertThat(goodResult, not(containsString("primary constructor")));

    // 'bad' with a `// lint:skip` before the second constructor — should pass.
    final String suppressed =
        "class Foo {\n"
            + "  Foo(int i) {\n"
            + "    this.i = i;\n"
            + "  }\n"
            + "  // lint:skip\n"
            + "  Foo(String s) {\n"
            + "    this.s = s;\n"
            + "  }\n"
            + "}\n";
    final String suppressedResult = programResult("Foo.java", suppressed);
    assertThat(suppressedResult, not(containsString("primary constructor")));
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
            + "Lines must be sorted; '  case b' should be before '  case c'"
            + " (move to line 5)");

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
            + "Lines must be sorted; '  case a' should be before '  case x'"
            + " (move to line 4)");

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
            + "Lines must be sorted; 'a' should be before 'c'"
            + " (move to line 3)");

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
            + "Lines must be sorted; 'a' should be before 'c'"
            + " (move to line 5)");
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

  /**
   * Checks all generated sections in all {@code .md} files under {@code docs/}.
   *
   * <p>Scans every {@code .md} file, finds {@code [//]: # (start:KEY)} markers,
   * calls {@link Generation#generateSection} with the key, and diffs the result
   * against the committed file.
   */
  @Test
  void testGeneratedSections() throws IOException {
    final File baseDir = TestUtils.getBaseDir(TestUtils.class);
    final File docsDir = new File(baseDir, "docs");
    final File targetDocsDir = new File(baseDir, "target/docs");
    final List<String> errors = new ArrayList<>();
    final List<File> mdFiles = new ArrayList<>();
    collectMdFiles(docsDir, mdFiles);
    mdFiles.sort(Comparator.comparing(File::getPath));
    for (File file : mdFiles) {
      final String relPath = docsDir.toURI().relativize(file.toURI()).getPath();
      final File genFile = new File(targetDocsDir, relPath);
      genFile.getParentFile().mkdirs();
      try (Reader r = new FileReader(file);
          BufferedReader br = new BufferedReader(r);
          Writer w = new FileWriter(genFile);
          PrintWriter pw = new PrintWriter(w)) {
        boolean emit = true;
        for (String line = br.readLine(); line != null; line = br.readLine()) {
          if (line.startsWith("[//]: # (end:")) {
            emit = true;
          }
          if (emit) {
            pw.println(line);
          }
          if (line.startsWith("[//]: # (start:")) {
            final String key = line.substring(15, line.length() - 1);
            emit = false;
            Generation.generateSection(MODEL, key, pw);
          }
        }
      }
      final String diff = TestUtils.diff(file, genFile);
      if (!diff.isEmpty()) {
        errors.add(
            "Files differ: "
                + file
                + " "
                + genFile
                + "\n" //
                + diff);
      }
    }
    if (!errors.isEmpty()) {
      fail(String.join("\n", errors));
    }
  }

  /** Collects all {@code .md} files recursively under {@code dir}. */
  private static void collectMdFiles(File dir, List<File> files) {
    final File @Nullable [] children = dir.listFiles();
    if (children == null) {
      return;
    }
    for (File f : children) {
      if (f.isDirectory()) {
        collectMdFiles(f, files);
      } else if (f.getName().endsWith(".md")) {
        files.add(f);
      }
    }
  }

  /**
   * Checks that every non-internal {@link BuiltIn} entry has a corresponding
   * {@code val} spec in {@code lib/*.sig}.
   *
   * <p>Entries in the internal {@code "$"} pseudo-structure are excluded.
   * Entries in the {@code "Test"} pseudo-structure (test-only built-ins) are
   * also excluded. Null-structure entries (top-level built-ins such as {@code
   * not}, {@code abs}) are also excluded.
   */
  @Test
  void testBuiltInsDocumented() {
    final Set<String> missing = new TreeSet<>();
    for (BuiltIn builtIn : BuiltIn.values()) {
      final String structure = builtIn.structure;
      if (structure.equals("Top")
          || structure.equals("$")
          || structure.equals("Test")) {
        continue;
      }
      // Datatype constructors that exist as a BuiltIn entry are documented
      // via the surrounding `datatype` declaration in the .sig, not as a
      // separate `val` spec. (See LIST_NIL: declared as a constructor of
      // `datatype 'a list = nil | ...` in list.sig.)
      if ("List".equals(structure)
          && ("nil".equals(builtIn.mlName) || "op ::".equals(builtIn.mlName))) {
        continue;
      }
      if (!MODEL.containsFunction(structure, builtIn.mlName)) {
        missing.add(structure + "." + builtIn.mlName);
      }
    }
    if (!missing.isEmpty()) {
      fail(
          format(
              "BuiltIn entries not documented in any lib/*.sig: %s\n"
                  + "Add a val/type/exception spec for each.",
              missing));
    }
  }

  /**
   * Checks that {@code [@@method]} in {@code lib/*.sig} is consistent with
   * {@link BuiltIn#method}.
   *
   * <p>Internal structures ({@code $}, {@code Test}) are excluded.
   */
  @Test
  void testMethodConsistent() {
    final List<String> errors = new ArrayList<>();
    for (BuiltIn builtIn : BuiltIn.values()) {
      final String structure = builtIn.structure;
      if (structure.equals("Top")
          || structure.equals("$")
          || structure.equals("Test")) {
        continue;
      }
      final boolean sigHasMethod =
          MODEL.containsMethod(structure, builtIn.mlName);
      final String key = structure + "." + builtIn.mlName;
      if (builtIn.method && !sigHasMethod) {
        errors.add(
            "BuiltIn "
                + key
                + " has method=true but .sig has no [@@method]"
                + " on the corresponding val spec");
      } else if (!builtIn.method && sigHasMethod) {
        errors.add(".sig has [@@method] for " + key + " but BuiltIn does not");
      }
    }
    if (!errors.isEmpty()) {
      fail(
          format(
              "%d method inconsistencies between BuiltIn and lib/*.sig:\n" //
                  + "%s",
              errors.size(), String.join("\n", errors)));
    }
  }

  /**
   * Checks that every non-internal {@link BuiltIn.Datatype} entry has a
   * corresponding type or datatype spec in {@code lib/*.sig}.
   */
  @Test
  void testDatatypesDocumented() {
    final List<String> missing = new ArrayList<>();
    for (BuiltIn.Datatype datatype : BuiltIn.Datatype.values()) {
      final String structure = datatype.structure;
      if (structure.equals("$")) {
        continue;
      }
      if (!MODEL.containsType(structure, datatype.mlName())) {
        missing.add(structure + "." + datatype.mlName());
      }
    }
    if (!missing.isEmpty()) {
      fail(
          format(
              "Datatype entries not declared in any lib/*.sig: %s\n"
                  + "Add a type/datatype spec for each.",
              missing));
    }
  }

  /**
   * Checks that every {@link BuiltIn.Constructor} entry whose datatype is
   * {@link BuiltIn.Datatype#EXN} is referenced by some {@link Codes.BuiltInExn}
   * entry.
   */
  @Test
  void testBuiltInExnsConsistent() {
    final EnumSet<BuiltIn.Constructor> missing =
        EnumSet.allOf(BuiltIn.Constructor.class);
    missing.removeIf(c -> c.datatype != BuiltIn.Datatype.EXN);
    for (Codes.BuiltInExn exn : EnumSet.allOf(Codes.BuiltInExn.class)) {
      missing.remove(exn.constructor);
    }
    if (!missing.isEmpty()) {
      fail(
          format(
              "EXN-datatype constructors with no matching "
                  + "Codes.BuiltInExn entry: %s\n"
                  + "Add a BuiltInExn variant referencing each.",
              missing));
    }
  }

  /**
   * Checks that every {@code lib/*.sig} structure has a corresponding {@code
   * docs/lib/{name}.md} file.
   */
  @Test
  void testStructureDocs() {
    final File baseDir = TestUtils.getBaseDir(TestUtils.class);
    final File libDir = new File(baseDir, "docs/lib");
    final List<String> missing = new ArrayList<>();
    for (String structureName : MODEL.structureNames()) {
      final String fileName = Generation.toKebab(structureName) + ".md";
      if (!new File(libDir, fileName).exists()) {
        missing.add(fileName);
      }
    }
    if (!missing.isEmpty()) {
      fail(
          format(
              "Missing docs/lib files: %s\n" //
                  + "Create a file for each in %s",
              missing, libDir.getAbsolutePath()));
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

    final File @Nullable [] files =
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
    Language language;
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
    int commentDepth;
    int commentStartLine;
    int pendingBlockOpenLine;
    boolean inCodeBlock;
    boolean inPreBlock;
    boolean inComment;
    boolean inGeneratedBlock;
    /** Java {@code /* ... *}{@code /} block-comment state across lines. */
    boolean inJavaBlockComment;
    /** Stack of class/enum declarations currently in scope. */
    final Deque<ClassFrame> classStack = new ArrayDeque<>();
    /** Brace depth in the Java file (with strings/comments stripped). */
    int javaBraceDepth;
    /**
     * Name from a {@code class} / {@code enum} declaration whose opening brace
     * has not been seen yet, or null.
     */
    @Nullable String pendingClassName;
    /**
     * Line number (1-based) of a constructor signature whose body's first
     * statement has not yet been classified, or 0 if none.
     */
    int pendingCtorSigLine;
    /** Whether the body's opening {@code &#123;} has been seen yet. */
    boolean pendingCtorSawBrace;

    FileState(GlobalState global) {
      this.global = global;
    }

    void message(
        Puffin.Line<GlobalState, FileState> line,
        String format,
        Object... args) {
      message(line, line.fnr(), format, args);
    }

    void message(
        Puffin.Line<GlobalState, FileState> line,
        int lineNumber,
        String format,
        Object... args) {
      String message = format(format, args);
      global.messages.add(new Message(line.source(), lineNumber, message));
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
    final PairList<String, Integer> lines = PairList.of();
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
        String prevLine = lines.left(lines.size() - 1);
        if (comparator.compare(prevLine, thisLine) > 0) {
          for (int i = 0; i < lines.size(); i++) {
            if (comparator.compare(lines.left(i), thisLine) > 0) {
              String format =
                  "Lines must be sorted; '%s' should be before '%s'"
                      + " (move to line %d)";
              line.state()
                  .message(
                      line, format, thisLine, lines.left(i), lines.right(i));
              break;
            }
          }
        }
      }
      lines.add(thisLine, line.fnr());
    }
  }

  enum Language {
    JAVA("//"),
    MARKDOWN(null),
    MOREL("(*)"),
    UNKNOWN(null);

    final @Nullable String comment;

    Language(@Nullable String comment) {
      this.comment = comment;
    }
  }
}

// End LintTest.java
