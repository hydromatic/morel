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

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.TestUtils.first;
import static net.hydromatic.morel.TestUtils.n2u;
import static net.hydromatic.morel.TestUtils.toCamelCase;
import static net.hydromatic.morel.TestUtils.urlToFile;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Tracer;
import net.hydromatic.morel.compile.Tracers;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Prop;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Test that runs files and checks the results. */
public class ScriptTest {
  /** The slowest tests in the system and how many seconds they take to run. */
  private static final Map<String, Integer> TEST_TIMINGS =
      ImmutableMap.<String, Integer>builder()
          .put("script/wordle.smli", 44)
          .put("script/built-in.smli", 5)
          .put("script/blog.smli", 20)
          .put("script/pretty.smli", 14)
          .put("script/such-that.smli", 16)
          .put("script/hybrid.smli", 18)
          .put("script/foreign.smli", 13)
          .put("script/logic.smli", 15)
          .build();

  public ScriptTest() {}

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
  @Timeout(60)
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
    Script.create(path).run();
  }

  @SuppressWarnings("SameParameterValue")
  protected static Stream<Arguments> data_(String first) {
    // inUrl = "file:/home/fred/morel/target/test-classes/script/agg.sml"
    final URL inUrl = MainTest.class.getResource("/" + n2u(first));
    assertThat(inUrl, notNullValue());
    final File firstFile = urlToFile(inUrl);
    assertThat(firstFile, notNullValue());
    requireNonNull(firstFile);
    final int commonPrefixLength =
        firstFile.getAbsolutePath().length() - first.length();
    final Path dir = firstFile.getParentFile().toPath();
    final Stream<Path> walk;
    try {
      walk = Files.walk(dir);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    // Skip the "surefire" subdirectory; it contains output files written by
    // previous test runs (which have the same .smli suffix).
    final File[] files =
        walk.filter(Files::isRegularFile)
            .filter(p -> p.getFileName().toString().matches(".*\\.(sml|smli)$"))
            .filter(p -> !p.toString().contains(File.separator + "surefire"))
            .map(Path::toFile)
            .toArray(File[]::new);
    return Stream.of(first(files, new File[0]))
        .map(f -> f.getAbsolutePath().substring(commonPrefixLength))
        .sorted(
            Comparator.comparingInt(ScriptTest::slowPriority)
                .thenComparing(Comparator.naturalOrder()))
        .map(Arguments::of);
  }

  /**
   * Returns a large negative integer if {@code testPath} is a slow test, a
   * small negative integer if it is faster, and zero if its running time is
   * negligible. Sorting by these integers puts the slow tests first, which
   * minimizes the time for the whole suite when run in parallel.
   */
  private static int slowPriority(String testPath) {
    return -TEST_TIMINGS.getOrDefault(testPath, 0);
  }

  @Test
  void testScript() throws Exception {
    checkRun("script.sml");
  }

  @Test
  void testTypeInference() throws Exception {
    checkRun("script/type-inference.smli");
  }

  /**
   * Runs {@code dual.smli} a second time, in Calcite ("hybrid") mode, asserting
   * that each query is pushed down to Calcite.
   *
   * <p>The ordinary {@link #test} run (via {@link #checkRun}) already exercises
   * {@code dual.smli} in local mode; both runs check the output against the
   * same golden file, since the results are identical (the script runner
   * tolerates reordered bag elements). This run additionally installs a tracer
   * that fails if a query's plan contains no Calcite node.
   */
  @Test
  void testScriptDual() throws Exception {
    Script.create(
            "script/dual.smli",
            null,
            false,
            ImmutableMap.of(Prop.HYBRID, true),
            requireCalciteTracer())
        .run();
  }

  /**
   * Returns a tracer that fails if a statement's plan is not (even partly)
   * pushed down to Calcite. Statements that cannot be pushed down -- a call to
   * the {@code Sys} structure such as {@code Sys.set} -- are exempt (the "deny
   * list").
   */
  private static Tracer requireCalciteTracer() {
    // Capture the fully-inlined core (pass -1) of each statement, then check
    // its plan when code is generated.
    final Core.Decl[] lastCore = {null};
    return Tracers.withOnPlan(
        Tracers.withOnCore(Tracers.empty(), -1, d -> lastCore[0] = d),
        code -> assertPushedDown(lastCore[0], code));
  }

  private static void assertPushedDown(Core.@Nullable Decl decl, Code code) {
    if (!(decl instanceof Core.NonRecValDecl)) {
      return; // not an expression statement; nothing to push down
    }
    final Core.Exp exp = ((Core.NonRecValDecl) decl).exp;
    final BuiltIn builtIn = exp.builtIn();
    if (builtIn != null && "Sys".equals(builtIn.structure)) {
      return; // e.g. Sys.set; cannot be pushed down
    }
    // A pushed-down plan contains a "calcite(...)" node (possibly nested inside
    // a "globalMarshal(...)" wrapper).
    final String plan = Codes.describe(code);
    if (!plan.contains("calcite(")) {
      throw new AssertionError(
          "query was not pushed down to Calcite: "
              + exp
              + "\n" //
              + "  plan: "
              + plan);
    }
  }
}

// End ScriptTest.java
