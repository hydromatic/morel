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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.util.Puffin;
import org.apache.calcite.util.Source;
import org.apache.calcite.util.Sources;
import org.junit.jupiter.api.Test;

/** Runs Lint-like checks on the source code. Also tests those checks. */
public class LintTest {
  @SuppressWarnings("Convert2MethodRef") // JDK 8 requires lambdas
  private Puffin.Program<GlobalState> makeProgram() {
    return Puffin.builder(GlobalState::new, global -> new FileState(global))
        .add(
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
            })
        .add(line -> line.fnr() == 1, line -> line.globalState().fileCount++)

        // Trailing space
        .add(
            line -> line.endsWith(" "),
            line -> line.state().message("Trailing space", line))

        // Tab
        .add(
            line -> line.contains("\t"),
            line -> line.state().message("Tab", line))

        // Nullable
        .add(
            line -> line.startsWith("import javax.annotation.Nullable;"),
            line ->
                line.state()
                    .message(
                        "use org.checkerframework.checker.nullness.qual.Nullable",
                        line))

        // Nonnull
        .add(
            line -> line.startsWith("import javax.annotation.Nonnull;"),
            line ->
                line.state()
                    .message(
                        "use org.checkerframework.checker.nullness.qual.NonNull",
                        line))

        // Use of 'Static.' other than in an import.
        .add(
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
                    && !line.source()
                        .fileOpt()
                        .filter(f -> f.getName().equals("LintTest.java"))
                        .isPresent()
                    && !line.source()
                        .fileOpt()
                        .filter(f -> f.getName().equals("UtilTest.java"))
                        .isPresent(),
            line -> line.state().message("should be static import", line))

        // In a test,
        //   assertThat(x.toString(), is(y));
        // should be
        //   assertThat(x, hasToString(y)));
        .add(
            line ->
                line.contains(".toString(), is(")
                    && line.filename().endsWith(".java")
                    && !line.source()
                        .fileOpt()
                        .filter(f -> f.getName().equals("LintTest.java"))
                        .isPresent(),
            line -> line.state().message("use 'Matchers.hasToString'", line))

        // Comment without space
        .add(
            line ->
                line.matches(".* //[^ ].*")
                    && !line.source()
                        .fileOpt()
                        .filter(f -> f.getName().equals("LintTest.java"))
                        .isPresent()
                    && !line.contains("//--")
                    && !line.contains("//~")
                    && !line.contains("//noinspection")
                    && !line.contains("//CHECKSTYLE"),
            line -> line.state().message("'//' must be followed by ' '", line))

        // Javadoc does not require '</p>', so we do not allow '</p>'
        .add(
            line -> line.state().inJavadoc() && line.contains("</p>"),
            line -> line.state().message("no '</p>'", line))

        // No "**/"
        .add(
            line -> line.contains(" **/") && line.state().inJavadoc(),
            line -> line.state().message("no '**/'; use '*/'", line))

        // A Javadoc paragraph '<p>' must not be on its own line.
        .add(
            line -> line.matches("^ *\\* <p>"),
            line ->
                line.state().message("<p> must not be on its own line", line))

        // A Javadoc paragraph '<p>' must be preceded by a blank Javadoc
        // line.
        .add(
            line -> line.matches("^ *\\*"),
            line -> {
              final FileState f = line.state();
              if (f.starLine == line.fnr() - 1) {
                f.message("duplicate empty line in javadoc", line);
              }
              f.starLine = line.fnr();
            })
        .add(
            line ->
                line.matches("^ *\\* <p>.*")
                    && line.fnr() - 1 != line.state().starLine,
            line ->
                line.state()
                    .message("<p> must be preceded by blank line", line))

        // A non-blank line following a blank line must have a '<p>'
        .add(
            line ->
                line.state().inJavadoc()
                    && line.state().ulCount == 0
                    && line.state().blockquoteCount == 0
                    && line.contains("* ")
                    && line.fnr() - 1 == line.state().starLine
                    && line.matches("^ *\\* [^<@].*"),
            line -> line.state().message("missing '<p>'", line))

        // The first "@param" of a javadoc block must be preceded by a blank
        // line.
        .add(
            line -> line.matches("^ */\\*\\*.*"),
            line -> {
              final FileState f = line.state();
              f.javadocStartLine = line.fnr();
              f.blockquoteCount = 0;
              f.ulCount = 0;
            })
        .add(
            line -> line.matches(".*\\*/"),
            line -> line.state().javadocEndLine = line.fnr())
        .add(
            line -> line.matches("^ *\\* @.*"),
            line -> {
              if (line.state().inJavadoc()
                  && line.state().atLine < line.state().javadocStartLine
                  && line.fnr() - 1 != line.state().starLine) {
                line.state()
                    .message("First @tag must be preceded by blank line", line);
              }
              line.state().atLine = line.fnr();
            })
        .add(
            line -> line.contains("<blockquote>"),
            line -> line.state().blockquoteCount++)
        .add(
            line -> line.contains("</blockquote>"),
            line -> line.state().blockquoteCount--)
        .add(line -> line.contains("<ul>"), line -> line.state().ulCount++)
        .add(line -> line.contains("</ul>"), line -> line.state().ulCount--)
        .build();
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
