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

import net.hydromatic.sml.ast.AstNode;
import net.hydromatic.sml.compile.Compiler;
import net.hydromatic.sml.compile.Environment;
import net.hydromatic.sml.compile.Environments;
import net.hydromatic.sml.parse.ParseException;
import net.hydromatic.sml.parse.SmlParserImpl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Standard ML REPL. */
public class Main {
  private final String[] args;
  private final BufferedReader in;
  private final PrintWriter out;
  private final boolean echo;

  /** Command-line entry point.
   *
   * @param args Command-line arguments */
  public static void main(String[] args) {
    final Main main = new Main(args, System.in, System.out);
    try {
      main.run();
    } catch (Throwable e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  /** Creates a Main. */
  public Main(String[] args, InputStream in, PrintStream out) {
    this(args, new InputStreamReader(in), new OutputStreamWriter(out));
  }

  /** Creates a Main. */
  public Main(String[] args, Reader in, Writer out) {
    this.args = args;
    this.in = buffer(in);
    this.out = buffer(out);
    this.echo = Arrays.asList(args).contains("--echo");
  }

  private static PrintWriter buffer(Writer out) {
    if (out instanceof PrintWriter) {
      return (PrintWriter) out;
    } else {
      if (!(out instanceof BufferedWriter)) {
        out = new BufferedWriter(out);
      }
      return new PrintWriter(out);
    }
  }

  private static BufferedReader buffer(Reader in) {
    if (in instanceof BufferedReader) {
      return (BufferedReader) in;
    } else {
      return new BufferedReader(in);
    }
  }

  public void run() {
    final Reader in2;
    if (echo) {
      in2 = new BufferingReader(in);
    } else {
      in2 = in;
    }
    final SmlParserImpl parser = new SmlParserImpl(in2);
    Environment env = Environments.empty();
    final List<String> lines = new ArrayList<>();
    for (;;) {
      try {
        final AstNode statement = parser.statementSemicolon();
        if (in2 instanceof BufferingReader) {
          ((BufferingReader) in2).flush(out);
          out.write("\n");
        }
        final Compiler.CompiledStatement compiled =
            Compiler.prepareStatement(env, statement);
        env = compiled.eval(env, lines);
        for (String line : lines) {
          out.write(line);
          out.write("\n");
        }
        lines.clear();
      } catch (ParseException e) {
        final String message = e.getMessage();
        if (message.startsWith("Encountered \"<EOF>\" ")) {
          break;
        }
        e.printStackTrace(out);
      } catch (IOException e) {
        throw new RuntimeException(e);
      } finally {
        out.flush();
      }
    }
  }

  /** Reader that snoops which characters have been read and saves
   * them in a buffer until {@link #flush} is called. */
  static class BufferingReader extends FilterReader {
    final StringBuilder buf = new StringBuilder();

    protected BufferingReader(Reader in) {
      super(in);
    }

    @Override public int read() throws IOException {
      int c = super.read();
      buf.append(c);
      return c;
    }

    @Override public int read(char[] cbuf, int off, int len)
        throws IOException {
      int n = super.read(cbuf, off, 1);
      if (n > 0) {
        buf.append(cbuf, off, n);
      }
      return n;
    }

    public void flush(Writer out) throws IOException {
      String s = buf.toString();
      buf.setLength(0);
      out.write(s);
    }
  }
}

// End Main.java
