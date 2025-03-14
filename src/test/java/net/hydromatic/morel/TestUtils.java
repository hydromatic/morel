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

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.collect.ImmutableList;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.incava.diff.Diff;
import org.incava.diff.Difference;

/** Utility methods for testing. */
class TestUtils {
  private TestUtils() {}

  /**
   * Converts a path from Unix to native.
   *
   * <p>On Windows, converts forward-slashes to back-slashes; on Linux, does
   * nothing.
   */
  public static String u2n(String s) {
    return File.separatorChar == '\\' ? s.replace('/', '\\') : s;
  }

  /** Converts a path from native to Unix. */
  public static String n2u(String s) {
    return File.separatorChar == '\\' ? s.replace('\\', '/') : s;
  }

  public static <E> E first(E e0, E e1) {
    return e0 != null ? e0 : e1;
  }

  public static String toCamelCase(String name) {
    StringBuilder buf = new StringBuilder();
    int nextUpper = -1;

    for (int i = 0; i < name.length(); ++i) {
      char c = name.charAt(i);
      if (c == '_') {
        nextUpper = i + 1;
      } else {
        if (nextUpper == i) {
          c = Character.toUpperCase(c);
        } else {
          c = Character.toLowerCase(c);
        }

        buf.append(c);
      }
    }

    return buf.toString();
  }

  public static File urlToFile(URL url) {
    if (!"file".equals(url.getProtocol())) {
      return null;
    }
    URI uri;
    try {
      uri = url.toURI();
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(
          "Unable to convert URL " + url + " to URI", e);
    }
    if (uri.isOpaque()) {
      // It is like file:test%20file.c++
      // getSchemeSpecificPart would return "test file.c++"
      return new File(uri.getSchemeSpecificPart());
    }
    // See https://stackoverflow.com/a/17870390/1261287
    return Paths.get(uri).toFile();
  }

  /** Returns the root directory of test resources. */
  static File findDirectory() {
    final URL inUrl = MainTest.class.getResource("/");
    assertThat(inUrl, notNullValue());
    return urlToFile(inUrl);
  }

  @SuppressWarnings("unused")
  public static void discard(boolean value) {}

  /**
   * Creates a {@link PrintWriter} to a given output stream using UTF-8
   * character set.
   *
   * <p>Does not use the default character set.
   */
  public static PrintWriter printWriter(OutputStream out) {
    return new PrintWriter(
        new BufferedWriter(
            new OutputStreamWriter(out, StandardCharsets.UTF_8)));
  }

  /**
   * Creates a {@link PrintWriter} to a given file using UTF-8 character set.
   *
   * <p>Does not use the default character set.
   */
  public static PrintWriter printWriter(File file)
      throws FileNotFoundException {
    return printWriter(new FileOutputStream(file));
  }

  /**
   * Creates a {@link BufferedReader} to a given input stream using UTF-8
   * character set.
   *
   * <p>Does not use the default character set.
   */
  public static BufferedReader reader(InputStream in) {
    return new BufferedReader(
        new InputStreamReader(in, StandardCharsets.UTF_8));
  }

  /**
   * Creates a {@link BufferedReader} to read a given file using UTF-8 character
   * set.
   *
   * <p>Does not use the default character set.
   */
  public static BufferedReader reader(File file) throws FileNotFoundException {
    return reader(new FileInputStream(file));
  }

  /**
   * Returns a string containing the difference between the contents of two
   * files. The string has a similar format to the UNIX 'diff' utility.
   */
  public static String diff(File file1, File file2) {
    List<String> lines1 = fileLines(file1);
    List<String> lines2 = fileLines(file2);
    return diffLines(lines1, lines2);
  }

  /**
   * Returns a string containing the difference between the two sets of lines.
   */
  public static String diffLines(List<String> lines1, List<String> lines2) {
    final Diff<String> diff = new Diff<>(lines1, lines2);
    final List<Difference> differences = diff.execute();
    StringWriter sw = new StringWriter();
    int offset = 0;
    for (Difference d : differences) {
      final int as = d.getAddedStart() + 1;
      final int ae = d.getAddedEnd() + 1;
      final int ds = d.getDeletedStart() + 1;
      final int de = d.getDeletedEnd() + 1;
      if (ae == 0) {
        if (de == 0) {
          // no change
        } else {
          // a deletion: "<ds>,<de>d<as>"
          sw.append(String.valueOf(ds));
          if (de > ds) {
            sw.append(",").append(String.valueOf(de));
          }
          sw.append("d").append(String.valueOf(as - 1)).append('\n');
          for (int i = ds - 1; i < de; ++i) {
            sw.append("< ").append(lines1.get(i)).append('\n');
          }
        }
      } else {
        if (de == 0) {
          // an addition: "<ds>a<as,ae>"
          sw.append(String.valueOf(ds - 1))
              .append("a")
              .append(String.valueOf(as));
          if (ae > as) {
            sw.append(",").append(String.valueOf(ae));
          }
          sw.append('\n');
          for (int i = as - 1; i < ae; ++i) {
            sw.append("> ").append(lines2.get(i)).append('\n');
          }
        } else {
          // a change: "<ds>,<de>c<as>,<ae>
          sw.append(String.valueOf(ds));
          if (de > ds) {
            sw.append(",").append(String.valueOf(de));
          }
          sw.append("c").append(String.valueOf(as));
          if (ae > as) {
            sw.append(",").append(String.valueOf(ae));
          }
          sw.append('\n');
          for (int i = ds - 1; i < de; ++i) {
            sw.append("< ").append(lines1.get(i)).append('\n');
          }
          sw.append("---\n");
          for (int i = as - 1; i < ae; ++i) {
            sw.append("> ").append(lines2.get(i)).append('\n');
          }
          offset = offset + (ae - as) - (de - ds);
        }
      }
    }
    return sw.toString();
  }

  /**
   * Returns a list of the lines in a given file, or an empty list if the file
   * does not exist.
   *
   * @param file File
   * @return List of lines
   */
  private static List<String> fileLines(File file) {
    List<String> lines = new ArrayList<>();
    if (!file.exists()) {
      return lines;
    }
    try (LineNumberReader r = new LineNumberReader(reader(file))) {
      String line;
      while ((line = r.readLine()) != null) {
        lines.add(line);
      }
      return lines;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Returns a list plus one element. */
  public static <E> ImmutableList<E> plus(List<E> elements, E element) {
    return ImmutableList.<E>builder().addAll(elements).add(element).build();
  }
}

// End TestUtils.java
