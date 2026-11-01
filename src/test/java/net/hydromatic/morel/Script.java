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
import net.hydromatic.morel.compile.Tracer;
import net.hydromatic.morel.compile.Tracers;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.ForeignValue;
import org.apache.commons.io.input.TeeReader;
import org.apache.commons.io.output.TeeWriter;
import org.jspecify.annotations.Nullable;

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
  private final Map<String, ForeignValue> extraValues;

  /** Property overrides applied before running (e.g. {@code hybrid=true}). */
  private final Map<Prop, Object> propOverrides;

  /** Tracer installed on {@link Main} (e.g. to assert Calcite push-down). */
  private final Tracer tracer;

  /** Internal constructor. */
  private Script(
      File inFile,
      File outFile,
      boolean noInput,
      boolean echo,
      boolean idempotent,
      boolean loadDictionary,
      Map<String, ForeignValue> extraValues,
      Map<Prop, Object> propOverrides,
      Tracer tracer) {
    this.inFile = requireNonNull(inFile, "inFile");
    this.outFile = requireNonNull(outFile, "outFile");
    this.noInput = noInput;
    this.echo = echo;
    this.idempotent = idempotent;
    this.loadDictionary = loadDictionary;
    this.extraValues = ImmutableMap.copyOf(extraValues);
    this.propOverrides = ImmutableMap.copyOf(propOverrides);
    this.tracer = requireNonNull(tracer, "tracer");
  }

  /**
   * Returns a copy of this script that runs every query on Spark.
   *
   * <p>It sets the {@code sparkPushDown} property, which is what a session sets
   * to the same end, so that each query is wrapped in {@code Spark.remote} and
   * answers as it does locally, or fails. The connection comes from the value
   * bound as {@code spark}, so a script run this way needs {@link #withValues}
   * too -- and the connection the suite binds is the one carrying the data
   * set's nullability declaration, which is why the property is set here rather
   * than letting Morel open its own.
   */
  public Script withSparkPushDown() {
    final Map<Prop, Object> map = new LinkedHashMap<>(propOverrides);
    Prop.SPARK_PUSH_DOWN.set(map, true);
    // A query that stops being pushed down is a regression, so it fails here
    // rather than answering locally with a warning, which is what a session
    // would rather have.
    Prop.SPARK_FALLBACK.set(map, false);
    return new Script(
        inFile,
        // A mode of its own writes a file of its own: the suite runs the modes
        // of a script in parallel, and two that wrote one file would race.
        suffixed(outFile, "spark"),
        noInput,
        echo,
        idempotent,
        loadDictionary,
        extraValues,
        map,
        tracer);
  }

  /**
   * Creates a Script from just a path.
   *
   * <p>This is the entry point for {@link ScriptTest}.
   */
  public static Script create(String path) throws IOException {
    return create(path, null, false, ImmutableMap.of(), Tracers.empty());
  }

  /**
   * Returns a file beside {@code file} whose name carries a suffix, so that a
   * second way of running one script does not write the first way's output.
   */
  private static File suffixed(File file, String suffix) {
    return new File(file.getParentFile(), suffix + "-" + file.getName());
  }

  /** Returns a copy of this script with more foreign values bound. */
  public Script withValues(Map<String, ForeignValue> values) {
    final Map<String, ForeignValue> map = new LinkedHashMap<>(extraValues);
    map.putAll(values);
    return new Script(
        inFile,
        outFile,
        noInput,
        echo,
        idempotent,
        loadDictionary,
        map,
        propOverrides,
        tracer);
  }

  /** Creates a Script. */
  public static Script create(
      String path,
      @Nullable File directory,
      boolean echo,
      Map<Prop, Object> propOverrides,
      Tracer tracer)
      throws IOException {
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
      final File out =
          new File(
              inFile.getAbsoluteFile().getParent(), u2n("surefire/") + outPath);
      // As for Spark: a run in Calcite mode writes beside the local run's
      // output rather than over it, because the suite runs them in parallel.
      outFile = propOverrides.isEmpty() ? out : suffixed(out, "hybrid");
    }

    final boolean loadDictionary =
        noInput
            || inFile
                .getPath()
                .matches(
                    ".*/(blog|dual|dummy|dummy2|foreign|hybrid|logic|pretty"
                        + "|spark|spark-disabled|spark-divergence"
                        + "|spark-error|spark-exec|spark-live|spark-plan"
                        + "|spark-plan-generated|such-that|triple)\\.(sml|smli)");

    return new Script(
        inFile,
        outFile,
        noInput,
        echo,
        idempotent,
        loadDictionary,
        ImmutableMap.of(),
        propOverrides,
        tracer);
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
    final Map<Prop, Object> propOverrides = ImmutableMap.of();
    final Tracer tracer = Tracers.empty();
    final Script t = create(path, directory, echo, propOverrides, tracer);
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
    propMap.putAll(propOverrides);
    final Map<String, ForeignValue> dictionary = new LinkedHashMap<>();
    if (loadDictionary) {
      dictionary.putAll(
          Calcite.withDataSets(BuiltInDataSet.DICTIONARY).foreignValues());
    }
    dictionary.putAll(extraValues);

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
      final Main main =
          new Main(
              argList, reader, writer, dictionary, propMap, idempotent, tracer);
      main.run();
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
