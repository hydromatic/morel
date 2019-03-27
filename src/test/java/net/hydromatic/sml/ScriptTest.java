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
package net.hydromatic.sml;

import com.google.common.collect.Lists;
import com.google.common.io.PatternFilenameFilter;

import org.incava.diff.Diff;
import org.incava.diff.Difference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.fail;

/**
 * Test that runs files and checks the results.
 */
@RunWith(Parameterized.class)
public class ScriptTest {
  protected final String path;
  protected final Method method;

  /** Creates a ScriptTest. Public per {@link Parameterized}. */
  @SuppressWarnings("WeakerAccess")
  public ScriptTest(String path) {
    this.path = path;
    this.method = findMethod(path);
  }

  /** Runs a test from the command line.
   *
   * <p>For example:
   *
   * <blockquote>
   *   <code>java ScriptTest script/table.sml</code>
   * </blockquote> */
  public static void main(String[] args) throws Exception {
    for (String arg : args) {
      new ScriptTest(arg).test();
    }
  }

  /** For {@link Parameterized} runner. */
  @Parameterized.Parameters(name = "{index}: script({0})")
  public static Collection<Object[]> data() {
    // Start with a test file we know exists, then find the directory and list
    // its files.
    final String first = "script/simple.sml";
    return data(first);
  }

  @Test
  public void test() throws Exception {
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

  private Method findMethod(String path) {
    // E.g. path "script/simple.sml" gives method "testScriptSimple"
    String methodName = Utils.toCamelCase("test_"
        + path.replace(File.separatorChar, '_').replaceAll("\\.sml$", ""));
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
    if (f.isAbsolute()) {
      // e.g. path = "/tmp/foo.sml"
      inFile = f;
      outFile = new File(path + ".out");
    } else {
      // e.g. path = "sql/outer.sml"
      // inUrl = "file:/home/fred/smlj/target/test-classes/script/outer.sml"
      final URL inUrl = MainTest.class.getResource("/" + Utils.n2u(path));
      inFile = Utils.urlToFile(inUrl);
      outFile = new File(inFile.getAbsoluteFile().getParent(),
          Utils.u2n("surefire/") + path + ".out");
    }
    Utils.discard(outFile.getParentFile().mkdirs());
    final String[] args = {"--echo"};
    try (Reader reader = Utils.reader(inFile);
         Writer writer = Utils.printWriter(outFile)) {
      new Main(args, reader, writer).run();
    }
    final File refFile =
        new File(inFile.getParentFile(), inFile.getName() + ".out");
    if (refFile.exists()) {
      final String diff = Utils.diff(refFile, outFile);
      if (!diff.isEmpty()) {
        fail("Files differ: " + refFile + " " + outFile + "\n"
            + diff);
      }
    } else {
      fail("Reference file not found: " + refFile + "\n"
          + "Out file is: " + outFile + "\n");
    }
  }

  protected static Collection<Object[]> data(String first) {
    // inUrl = "file:/home/fred/smlj/target/test-classes/script/agg.sml"
    final URL inUrl = MainTest.class.getResource("/" + Utils.n2u(first));
    final File firstFile = Utils.urlToFile(inUrl);
    final int commonPrefixLength =
        firstFile.getAbsolutePath().length() - first.length();
    final File dir = firstFile.getParentFile();
    final List<String> paths = new ArrayList<>();
    final FilenameFilter filter = new PatternFilenameFilter(".*\\.sml$");
    File[] files = dir.listFiles(filter);
    for (File f : Utils.first(files, new File[0])) {
      paths.add(f.getAbsolutePath().substring(commonPrefixLength));
    }
    return Lists.transform(paths, path -> new Object[] {path});
  }

  /** Utility methods. */
  static class Utils {
    /** Converts a path from Unix to native. On Windows, converts
     * forward-slashes to back-slashes; on Linux, does nothing. */
    private static String u2n(String s) {
      return File.separatorChar == '\\'
          ? s.replace('/', '\\')
          : s;
    }

    /** Converts a path from native to Unix. */
    private static String n2u(String s) {
      return File.separatorChar == '\\' ? s.replace('\\', '/') : s;
    }

    private static <E> E first(E e0, E e1) {
      return e0 != null ? e0 : e1;
    }

    private static String toCamelCase(String name) {
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

    private static File urlToFile(URL url) {
      if (!"file".equals(url.getProtocol())) {
        return null;
      }
      URI uri;
      try {
        uri = url.toURI();
      } catch (URISyntaxException e) {
        throw new IllegalArgumentException("Unable to convert URL " + url
            + " to URI", e);
      }
      if (uri.isOpaque()) {
        // It is like file:test%20file.c++
        // getSchemeSpecificPart would return "test file.c++"
        return new File(uri.getSchemeSpecificPart());
      }
      // See https://stackoverflow.com/a/17870390/1261287
      return Paths.get(uri).toFile();
    }

    @SuppressWarnings("unused")
    public static void discard(boolean value) {
    }

    /** Creates a {@link PrintWriter} to a given output stream using UTF-8
     * character set.
     *
     * <p>Does not use the default character set. */
    public static PrintWriter printWriter(OutputStream out) {
      return new PrintWriter(
          new BufferedWriter(
              new OutputStreamWriter(out, StandardCharsets.UTF_8)));
    }

    /** Creates a {@link PrintWriter} to a given file using UTF-8
     * character set.
     *
     * <p>Does not use the default character set. */
    public static PrintWriter printWriter(File file)
        throws FileNotFoundException {
      return printWriter(new FileOutputStream(file));
    }

    /** Creates a {@link BufferedReader} to a given input stream using UTF-8
     * character set.
     *
     * <p>Does not use the default character set. */
    public static BufferedReader reader(InputStream in) {
      return new BufferedReader(
          new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** Creates a {@link BufferedReader} to read a given file using UTF-8
     * character set.
     *
     * <p>Does not use the default character set. */
    public static BufferedReader reader(File file)
        throws FileNotFoundException {
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
      final Diff<String> differencer = new Diff<>(lines1, lines2);
      final List<Difference> differences = differencer.execute();
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
            sw.append(String.valueOf(ds - 1)).append("a").append(
                String.valueOf(as));
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
     * Returns a list of the lines in a given file.
     *
     * @param file File
     * @return List of lines
     */
    private static List<String> fileLines(File file) {
      List<String> lines = new ArrayList<>();
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
  }
}

// End ScriptTest.java
