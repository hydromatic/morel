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
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.net.URL;
import org.apache.calcite.util.Sources;

/** Static utilities for JUnit tests. */
public abstract class TestUtil {
  /** Returns the root directory of the source tree. */
  public static File getBaseDir(Class<?> klass) {
    // Algorithm:
    // 1) Find location of TestUtil.class
    // 2) Climb via getParentFile() until we detect pom.xml
    // 3) It means we've got BASE/testkit/pom.xml, and we need to get BASE
    final URL resource = klass.getResource(klass.getSimpleName() + ".class");
    final File classFile =
        Sources.of(requireNonNull(resource, "resource")).file();

    File file = classFile.getAbsoluteFile();
    for (int i = 0; i < 42; i++) {
      if (isProjectDir(file)) {
        // Ok, file == BASE/testkit/
        break;
      }
      file = file.getParentFile();
    }
    if (!isProjectDir(file)) {
      fail(
          "Could not find pom.xml, build.gradle.kts or gradle.properties. "
              + "Started with "
              + classFile.getAbsolutePath()
              + ", the current path is "
              + file.getAbsolutePath());
    }
    return file;
  }

  private static boolean isProjectDir(File dir) {
    return new File(dir, "pom.xml").isFile()
        || new File(dir, "build.gradle.kts").isFile()
        || new File(dir, "gradle.properties").isFile();
  }
}

// End TestUtil.java
