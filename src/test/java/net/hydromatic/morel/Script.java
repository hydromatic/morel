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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.TestUtils.n2u;
import static net.hydromatic.morel.TestUtils.u2n;
import static net.hydromatic.morel.TestUtils.urlToFile;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.ForeignValue;
import org.apache.commons.io.input.TeeReader;
import org.apache.commons.io.output.TeeWriter;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Runs an ".smli" file.
 *
 * <p>Powers {@link ScriptTest}, but may also be invoked via {@link
 * Script#main(String[])}.
 */
public class Script {
  private final File inFile;
  private final File outFile;
  private final boolean noInput;
  private final boolean echo;
  private final boolean idempotent;
  private final boolean loadDictionary;

  /** Internal constructor. */
  private Script(
      File inFile,
      File outFile,
      boolean noInput,
      boolean echo,
      boolean idempotent,
      boolean loadDictionary) {
    this.inFile = requireNonNull(inFile, "inFile");
    this.outFile = requireNonNull(outFile, "outFile");
    this.noInput = noInput;
    this.echo = echo;
    this.idempotent = idempotent;
    this.loadDictionary = loadDictionary;
  }

  /**
   * Creates a Script from just a path.
   *
   * <p>This is the entry point for {@link ScriptTest}.)
   */
  public static Script create(String path) throws IOException {
    return create(path, null, false);
  }

  /** Creates a Script. */
  public static Script create(
      String path, @Nullable File directory, boolean echo) throws IOException {
    final File inFile;
    final File outFile;
    final File f = new File(path);
    boolean noInput = path.equals("-");
    final boolean idempotent = noInput || path.endsWith(".smli");
    if (noInput) {
      inFile = File.createTempFile("morel-stdin-", ".smli");
      outFile = File.createTempFile("morel-stdin-", ".smli.out");
    } else if (f.isAbsolute()) {
      // e.g. path = "/tmp/foo.smli"
      inFile = f;
      outFile = new File(path + ".out");
    } else if (directory != null) {
      // e.g. path = "src/test/resources/script/dummy.smli"
      inFile = new File(directory, path);
      outFile = new File(directory, path + ".out");
    } else {
      // e.g. path = "sql/outer.sml"
      // inUrl = "file:/home/fred/morel/target/test-classes/script/outer.smli"
      final URL inUrl = MainTest.class.getResource("/" + n2u(path));
      checkArgument(inUrl != null, "path '%s' not found", path);
      inFile = urlToFile(inUrl);
      checkArgument(inFile != null, "file '%s' not found", inUrl);
      String outPath = idempotent ? path : path + ".out";
      outFile =
          new File(
              inFile.getAbsoluteFile().getParent(), u2n("surefire/") + outPath);
    }

    final boolean loadDictionary =
        noInput
            || inFile
                .getPath()
                .matches(
                    ".*/(blog|dummy|foreign|hybrid|logic|pretty"
                        + "|such-that)\\.(sml|smli)");

    return new Script(
        inFile, outFile, noInput, echo, idempotent, loadDictionary);
  }

  /**
   * Runs a test from the command line.
   *
   * <p>Arguments:
   *
   * <ul>
   *   <li>{@code --directory=<path>} - Set the base directory for resolving
   *       test files
   *   <li>{@code --echo} - Echo output to stdout as it's generated (for
   *       debugging hangs)
   *   <li>Test file paths (e.g., {@code script/table.sml})
   * </ul>
   *
   * <p>You can run via Java directly
   *
   * <pre>{@code
   * java Script script/table.sml
   * java Script --directory=/tmp script/table.sml
   * java Script --echo script/relational.smli
   * }</pre>
   *
   * <p>or via Maven
   *
   * <pre>{@code
   * ./mvnw -q test-compile exec:java \
   *   -Dexec.mainClass="net.hydromatic.morel.Script" \
   *   -Dexec.classpathScope=test \
   *   -Dexec.args="--echo script/wordle.smli"
   * }</pre>
   */
  public static void main(String[] args) throws Exception {
    File directory = null;
    boolean echo = false;
    String path = "-";
    for (String arg : args) {
      if (arg.startsWith("--directory=")) {
        directory = new File(arg.substring("--directory=".length()));
      } else if (arg.equals("--echo")) {
        echo = true;
      } else {
        path = arg;
      }
    }
    final Script t = create(path, directory, echo);
    t.run();
  }

  public void run() throws IOException {
    TestUtils.discard(outFile.getParentFile().mkdirs());
    final Map<Prop, Object> propMap = new LinkedHashMap<>();
    if (!noInput) {
      final File scriptDirectory = inFile.getParentFile();

      File directory = scriptDirectory;
      for (File d = scriptDirectory; d != null; d = d.getParentFile()) {
        if (d.getName().equals("script")) {
          directory = d.getParentFile();
          break;
        }
      }
      // For the "file.smli" test, move to a subdirectory; it's more predictable
      if (inFile.getPath().matches(".*/(file)\\.(sml|smli)")) {
        directory = new File(directory, "data");
      }
      Prop.SCRIPT_DIRECTORY.set(propMap, scriptDirectory);
      Prop.DIRECTORY.set(propMap, directory);
    }
    final Map<String, ForeignValue> dictionary =
        loadDictionary
            ? Calcite.withDataSets(BuiltInDataSet.DICTIONARY).foreignValues()
            : ImmutableMap.of();

    try (Reader reader =
            noInput
                ? new TeeReader(
                    new InputStreamReader(System.in),
                    new FileWriter(inFile),
                    true)
                : TestUtils.reader(inFile);
        Writer writer =
            echo
                ? new TeeWriter(
                    TestUtils.printWriter(outFile),
                    new PrintWriter(System.out, true))
                : TestUtils.printWriter(outFile)) {
      final List<String> argList = ImmutableList.of("--echo");
      new Main(argList, reader, writer, dictionary, propMap, idempotent).run();
    }

    final String inName =
        idempotent ? inFile.getName() : inFile.getName() + ".out";
    final File refFile = new File(inFile.getParentFile(), inName);
    if (!refFile.exists()) {
      System.out.println("Reference file not found: " + refFile);
    }
    final String diff = TestUtils.diff(refFile, outFile);
    if (!diff.isEmpty()) {
      fail(
          String.format(
              "Files differ: %s %s\n" //
                  + "%s",
              refFile, outFile, diff));
    }
  }
}

// End Script.java
