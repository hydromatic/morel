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

import static net.hydromatic.morel.TestUtils.first;
import static net.hydromatic.morel.TestUtils.n2u;
import static net.hydromatic.morel.TestUtils.toCamelCase;
import static net.hydromatic.morel.TestUtils.urlToFile;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.io.PatternFilenameFilter;
import java.io.File;
import java.io.FilenameFilter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Test that runs files and checks the results. */
public class ScriptTest {
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

  @Test
  void testTypeInference() throws Exception {
    checkRun("script/type-inference.smli");
  }
}

// End ScriptTest.java
