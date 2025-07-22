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
import static net.hydromatic.morel.TestUtils.first;
import static net.hydromatic.morel.TestUtils.n2u;
import static net.hydromatic.morel.TestUtils.toCamelCase;
import static net.hydromatic.morel.TestUtils.u2n;
import static net.hydromatic.morel.TestUtils.urlToFile;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.PatternFilenameFilter;
import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.ForeignValue;
import org.apache.commons.io.input.TeeReader;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Test that runs files and checks the results. */
public class ScriptTest {
  public ScriptTest() {}

  protected @Nullable File directory() {
    return null;
  }

  /**
   * Runs a test from the command line.
   *
   * <p>For example:
   *
   * <pre>{@code java ScriptTest script/table.sml}</pre>
   */
  public static void main(String[] args) throws Exception {
    File directory = null;
    for (String arg : args) {
      if (arg.startsWith("--directory=")) {
        directory = new File(arg.substring("--directory=".length()));
      } else {
        final ScriptTest t;
        if (directory == null) {
          t = new ScriptTest();
        } else {
          File finalDirectory = directory;
          t =
              new ScriptTest() {
                @Override
                protected File directory() {
                  return finalDirectory;
                }
              };
        }
        t.test(arg);
      }
    }
  }

  /** For {@link ParameterizedTest} runner. */
  @SuppressWarnings("unused")
  static Stream<Arguments> data() {
    // Start with a test file we know exists, then find the directory and list
    // its files.
    final String first = "script/simple.smli";
    return data_(first);
  }

  @ParameterizedTest
  @MethodSource("data")
  void test(String path) throws Exception {
    Method method = findMethod(path);
    if (method != null) {
      try {
        method.invoke(this);
      } catch (InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception) {
          throw (Exception) cause;
        }
        if (cause instanceof Error) {
          throw (Error) cause;
        }
        throw e;
      }
    } else {
      checkRun(path);
    }
  }

  private @Nullable Method findMethod(String path) {
    // E.g. path "script/simple.sml" gives method "testScriptSimple"
    String methodName =
        toCamelCase(
            "test_"
                + path.replace(File.separatorChar, '_')
                    .replaceAll("\\.sml$", ""));
    Method m;
    try {
      m = getClass().getMethod(methodName);
    } catch (NoSuchMethodException e) {
      m = null;
    }
    return m;
  }

  protected void checkRun(String path) throws Exception {
    final File inFile;
    final File outFile;
    final File f = new File(path);
    final boolean idempotent = path.equals("-") || path.endsWith(".smli");
    if (path.equals("-")) {
      inFile = File.createTempFile("morel-stdin-", ".smli");
      outFile = File.createTempFile("morel-stdin-", ".smli.out");
    } else if (f.isAbsolute()) {
      // e.g. path = "/tmp/foo.smli"
      inFile = f;
      outFile = new File(path + ".out");
    } else if (directory() != null) {
      // e.g. path = "src/test/resources/script/dummy.smli"
      inFile = new File(directory(), path);
      outFile = new File(directory(), path + ".out");
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
    TestUtils.discard(outFile.getParentFile().mkdirs());
    final List<String> argList = ImmutableList.of("--echo");
    final boolean loadDictionary;
    final Map<Prop, Object> propMap = new LinkedHashMap<>();
    if (path.equals("-")) {
      loadDictionary = true;
    } else {
      final File scriptDirectory = inFile.getParentFile();
      loadDictionary =
          inFile
              .getPath()
              .matches(
                  ".*/(blog|dummy|foreign|hybrid|logic|pretty"
                      + "|suchThat)\\.(sml|smli)");

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
            path.equals("-")
                ? new TeeReader(
                    new InputStreamReader(System.in),
                    new FileWriter(inFile),
                    true)
                : TestUtils.reader(inFile);
        Writer writer = TestUtils.printWriter(outFile)) {
      Main main =
          new Main(argList, reader, writer, dictionary, propMap, idempotent);
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
          "Files differ: "
              + refFile
              + " "
              + outFile
              + "\n" //
              + diff);
    }
  }

  @SuppressWarnings("SameParameterValue")
  protected static Stream<Arguments> data_(String first) {
    // inUrl = "file:/home/fred/morel/target/test-classes/script/agg.sml"
    final URL inUrl = MainTest.class.getResource("/" + n2u(first));
    assertThat(inUrl, notNullValue());
    final File firstFile = urlToFile(inUrl);
    assertThat(firstFile, notNullValue());
    final int commonPrefixLength =
        firstFile.getAbsolutePath().length() - first.length();
    final File dir = firstFile.getParentFile();
    @SuppressWarnings("UnstableApiUsage")
    final FilenameFilter filter = new PatternFilenameFilter(".*\\.(sml|smli)$");
    File[] files = dir.listFiles(filter);
    return Stream.of(first(files, new File[0]))
        .map(
            f ->
                Arguments.of(
                    f.getAbsolutePath().substring(commonPrefixLength)));
  }

  @Test
  void testScript() throws Exception {
    checkRun("script.sml");
  }
}

// End ScriptTest.java
