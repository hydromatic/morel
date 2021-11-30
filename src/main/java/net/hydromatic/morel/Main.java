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

import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.compile.CompiledStatement;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.parse.ParseException;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

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
import java.util.List;
import java.util.Map;

/** Standard ML REPL. */
public class Main {
  private final BufferedReader in;
  private final PrintWriter out;
  private final boolean echo;
  private final Map<String, ForeignValue> valueMap;

  /** Command-line entry point.
   *
   * @param args Command-line arguments */
  public static void main(String[] args) {
    final Main main =
        new Main(ImmutableList.copyOf(args), System.in, System.out,
            ImmutableMap.of());
    try {
      main.run();
    } catch (Throwable e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  /** Creates a Main. */
  public Main(List<String> args, InputStream in, PrintStream out,
      Map<String, ForeignValue> valueMap) {
    this(args, new InputStreamReader(in), new OutputStreamWriter(out),
        valueMap);
  }

  /** Creates a Main. */
  public Main(List<String> argList, Reader in, Writer out,
      Map<String, ForeignValue> valueMap) {
    this.in = buffer(in);
    this.out = buffer(out);
    this.echo = argList.contains("--echo");
    this.valueMap = ImmutableMap.copyOf(valueMap);
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
    final TypeSystem typeSystem = new TypeSystem();
    final BufferingReader in2 = new BufferingReader(in);
    final MorelParserImpl parser = new MorelParserImpl(in2);
    Environment env = Environments.env(typeSystem, valueMap);
    final List<String> lines = new ArrayList<>();
    final List<Binding> bindings = new ArrayList<>();
    final Session session = new Session();
    for (;;) {
      String code = "";
      try {
        final AstNode statement = parser.statementSemicolon();
        code = in2.flush();
        if (echo) {
          out.write(code);
          out.write("\n");
        }
        final CompiledStatement compiled =
            Compiles.prepareStatement(typeSystem, session, env, statement,
                null);
        compiled.eval(session, env, lines::add, bindings::add);
        for (String line : lines) {
          out.write(line);
          out.write("\n");
        }
        lines.clear();
        env = env.bindAll(bindings);
        bindings.clear();
      } catch (RuntimeException e) {
        out.println("Error while executing statement:");
        out.println(code);
        e.printStackTrace(out);
      } catch (Error e) {
        out.println("Error while executing statement:");
        out.println(code);
        e.printStackTrace(out);
        throw e;
      } catch (ParseException e) {
        final String message = e.getMessage();
        if (message.startsWith("Encountered \"<EOF>\" ")) {
          break;
        }
        e.printStackTrace(out);
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

    public String flush() {
      final String s = buf.toString();
      buf.setLength(0);
      return s;
    }
  }
}

// End Main.java
