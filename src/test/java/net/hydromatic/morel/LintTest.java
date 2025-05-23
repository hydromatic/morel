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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
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
import java.util.List;
import java.util.stream.Stream;
import net.hydromatic.morel.util.Generation;
import org.apache.calcite.util.Puffin;
import org.apache.calcite.util.Source;
import org.apache.calcite.util.Sources;
import org.junit.jupiter.api.Test;

/** Runs Lint-like checks on the source code. Also tests those checks. */
@SuppressWarnings("Convert2MethodRef") // JDK 8 requires lambdas
public class LintTest {
  private Puffin.Program<GlobalState> makeProgram() {
    Puffin.Builder<GlobalState, FileState> b =
        Puffin.builder(GlobalState::new, global -> new FileState(global));
    addProgram0(b);
    addProgram1(b);
    return b.build();
  }

  private static void addProgram0(Puffin.Builder<GlobalState, FileState> b) {
    b.add(
        line -> line.isLast(),
        line -> {
          String f = line.filename();
          final int slash = f.lastIndexOf('/');
          final String endMarker =
              "// End " + (slash < 0 ? f : f.substring(slash + 1));
          if (!line.line().equals(endMarker)
              && line.filename().endsWith(".java")) {
            line.state()
                .message("File must end with '" + endMarker + "'", line);
          }
        });
    b.add(line -> line.fnr() == 1, line -> line.globalState().fileCount++);

    // Trailing space
    b.add(
        line -> line.endsWith(" "),
        line -> line.state().message("Trailing space", line));

    // Tab
    b.add(
        line -> line.contains("\t"), line -> line.state().message("Tab", line));

    // Nullable
    b.add(
        line -> line.startsWith("import javax.annotation.Nullable;"),
        line ->
            line.state()
                .message(
                    "use org.checkerframework.checker.nullness.qual.Nullable",
                    line));

    // Nonnull
    b.add(
        line -> line.startsWith("import javax.annotation.Nonnull;"),
        line ->
            line.state()
                .message(
                    "use org.checkerframework.checker.nullness.qual.NonNull",
                    line));

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
                && !line.endsWith("// lint:skip")
                && !filenameIs(line, "LintTest.java")
                && !filenameIs(line, "UtilTest.java"),
        line -> line.state().message("should be static import", line));

    // In a test,
    //   assertThat(x.toString(), is(y));
    // should be
    //   assertThat(x, hasToString(y)));
    b.add(
        line ->
            line.contains(".toString(), is(")
                && line.filename().endsWith(".java")
                && !filenameIs(line, "LintTest.java"),
        line -> line.state().message("use 'Matchers.hasToString'", line));

    // Comment without space
    b.add(
        line ->
            line.matches(".* //[^ ].*")
                && !filenameIs(line, "LintTest.java")
                && !line.contains("//--")
                && !line.contains("//~")
                && !line.contains("//noinspection")
                && !line.contains("//CHECKSTYLE"),
        line -> line.state().message("'//' must be followed by ' '", line));

    // In 'for (int i : list)', colon must be surrounded by space.
    b.add(
        line ->
            line.matches("^ *for \\(.*:.*")
                && !line.matches(".*[^ ][ ][:][ ][^ ].*")
                && !line.matches(".*[^ ][ ][:]$")
                && isJava(line.filename()),
        line -> line.state().message("':' must be surrounded by ' '", line));
  }

  private void addProgram1(Puffin.Builder<GlobalState, FileState> b) {
    // Broken string, "latch" + "string", should be "latchstring".
    b.add(
        line ->
            line.matches("^[^\"]*[\"][^\"]*[\"] *\\+ *[\"].*$")
                && !line.contains("//")
                && isJava(line.filename()),
        line -> line.state().message("broken string", line));

    // Newline should be at end of string literal, not in the middle
    b.add(
        line ->
            line.matches("^.*\\\\n[^\"]+[\"][^\"]*$")
                && !line.contains("//")
                && !line.contains("\\\\n")
                && isJava(line.filename()),
        line ->
            line.state()
                .message("newline should be at end of string literal", line));

    // Javadoc does not require '</p>', so we do not allow '</p>'
    b.add(
        line -> line.state().inJavadoc() && line.contains("</p>"),
        line -> line.state().message("no '</p>'", line));

    // No "**/"
    b.add(
        line -> line.contains(" **/") && line.state().inJavadoc(),
        line -> line.state().message("no '**/'; use '*/'", line));

    // A Javadoc paragraph '<p>' must not be on its own line.
    b.add(
        line -> line.matches("^ *\\* <p>"),
        line -> line.state().message("<p> must not be on its own line", line));

    // A Javadoc paragraph '<p>' must be preceded by a blank Javadoc
    // line.
    b.add(
        line -> line.matches("^ *\\*"),
        line -> {
          final FileState f = line.state();
          if (f.starLine == line.fnr() - 1) {
            f.message("duplicate empty line in javadoc", line);
          }
          f.starLine = line.fnr();
        });

    b.add(
        line ->
            line.matches("^ *\\* <p>.*")
                && line.fnr() - 1 != line.state().starLine,
        line ->
            line.state().message("<p> must be preceded by blank line", line));

    // A non-blank line following a blank line must have a '<p>'
    b.add(
        line ->
            line.state().inJavadoc()
                && line.state().ulCount == 0
                && line.state().blockquoteCount == 0
                && line.contains("* ")
                && line.fnr() - 1 == line.state().starLine
                && line.matches("^ *\\* [^<@].*"),
        line -> line.state().message("missing '<p>'", line));

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
                .message("First @tag must be preceded by blank line", line);
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
                .message("<code> and </code> must be on same line", line);
          }
        });
  }

  private static boolean filenameIs(
      Puffin.Line<GlobalState, FileState> line, String anObject) {
    return line.source()
        .fileOpt()
        .filter(f -> f.getName().equals(anObject))
        .isPresent();
  }

  /** Returns whether we are in a file that contains Java code. */
  private static boolean isJava(String filename) {
    return filename.endsWith(".java")
        || filename.endsWith(".jj")
        || filename.endsWith(".fmpp")
        || filename.endsWith(".ftl")
        || filename.equals("GuavaCharSource{memory}"); // for testing
  }

  /** Returns the number of occurrences of a string in a string. */
  private int count(String s, String sub) {
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
            + "  String good = \"string with newline\\n\"\n"
            + "      \"at end of line\";\n"
            + "  // A comment with <code>on one line and\n"
            + "  // </code> on the next.\n"
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
            + "GuavaCharSource{memory}:32:"
            + "<code> and </code> must be on same line\n"
            + "GuavaCharSource{memory}:33:"
            + "<code> and </code> must be on same line\n";
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

    assertThat("Lint violations:\n" + b, g.messages, empty());
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
    int starLine;
    int atLine;
    int javadocStartLine;
    int javadocEndLine;
    int blockquoteCount;
    int ulCount;

    FileState(GlobalState global) {
      this.global = global;
    }

    void message(String message, Puffin.Line<GlobalState, FileState> line) {
      global.messages.add(new Message(line.source(), line.fnr(), message));
    }

    public boolean inJavadoc() {
      return javadocEndLine < javadocStartLine;
    }
  }
}

// End LintTest.java
