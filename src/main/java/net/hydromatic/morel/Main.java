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
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.compile.CompiledStatement;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.eval.Codes;
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
import java.io.File;
import java.io.FileReader;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/** Standard ML REPL. */
public class Main {
  private final BufferedReader in;
  private final PrintWriter out;
  private final boolean echo;
  private final Map<String, ForeignValue> valueMap;
  final TypeSystem typeSystem = new TypeSystem();
  final File directory;
  final Session session = new Session();

  /** Command-line entry point.
   *
   * @param args Command-line arguments */
  public static void main(String[] args) {
    final Main main =
        new Main(ImmutableList.copyOf(args), System.in, System.out,
            ImmutableMap.of(), new File(System.getProperty("user.dir")));
    try {
      main.run();
    } catch (Throwable e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  /** Creates a Main. */
  public Main(List<String> args, InputStream in, PrintStream out,
      Map<String, ForeignValue> valueMap, File directory) {
    this(args, new InputStreamReader(in), new OutputStreamWriter(out),
        valueMap, directory);
  }

  /** Creates a Main. */
  public Main(List<String> argList, Reader in, Writer out,
      Map<String, ForeignValue> valueMap, File directory) {
    this.in = buffer(in);
    this.out = buffer(out);
    this.echo = argList.contains("--echo");
    this.valueMap = ImmutableMap.copyOf(valueMap);
    this.directory = requireNonNull(directory, "directory");
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
    Environment env = Environments.env(typeSystem, valueMap);
    final Consumer<String> outLines = out::println;
    final Map<String, Binding> outBindings = new LinkedHashMap<>();
    final Shell shell = new Shell(this, env, outLines, outBindings);
    session.withShell(shell, outLines, session1 ->
        shell.run(session1, new BufferingReader(in)));
    out.flush();
  }

  /** Shell (or sub-shell created via
   * {@link use net.hydromatic.morel.compile.BuiltIn#INTERACT_USE}) that can
   * execute commands and handle errors. */
  static class Shell implements Session.Shell {
    protected final Main main;
    protected final Environment env0;
    protected final Consumer<String> outLines;
    protected final Map<String, Binding> bindingMap;

    Shell(Main main, Environment env0, Consumer<String> outLines,
        Map<String, Binding> bindingMap) {
      this.main = main;
      this.env0 = env0;
      this.outLines = outLines;
      this.bindingMap = bindingMap;
    }

    void run(Session session, BufferingReader in2) {
      final MorelParserImpl parser = new MorelParserImpl(in2);
      final SubShell subShell =
          new SubShell(main, outLines, bindingMap, env0);
      for (;;) {
        try {
          final AstNode statement = parser.statementSemicolon();
          String code = in2.flush();
          if (main.echo) {
            outLines.accept(code);
          }
          session.withShell(subShell, outLines, session1 ->
              subShell.command(statement, outLines));
        } catch (ParseException e) {
          final String message = e.getMessage();
          if (message.startsWith("Encountered \"<EOF>\" ")) {
            break;
          }
          outLines.accept(message);
        }
      }
    }

    @Override public void use(String fileName) {
      throw new UnsupportedOperationException();
    }

    @Override public void handle(RuntimeException e, StringBuilder buf) {
      if (e instanceof Codes.MorelRuntimeException) {
        ((Codes.MorelRuntimeException) e).describeTo(buf);
      } else if (e instanceof CompileException) {
        buf.append(e.getMessage());
      } else {
        buf.append(e);
      }
    }
  }

  /** Shell that is created via the
   * {@link use net.hydromatic.morel.compile.BuiltIn#INTERACT_USE}) command.
   * Like a top-level shell, it can execute commands and handle errors. But its
   * input is a file, and its output is to the same output as its parent
   * shell. */
  static class SubShell extends Shell {

    SubShell(Main main, Consumer<String> outLines,
        Map<String, Binding> outBindings, Environment env0) {
      super(main, env0, outLines, outBindings);
    }

    @Override public void use(String fileName) {
      outLines.accept("[opening " + fileName + "]");
      File file = new File(fileName);
      if (!file.isAbsolute()) {
        file = new File(main.directory, fileName);
      }
      if (!file.exists()) {
        outLines.accept("[use failed: Io: openIn failed on "
            + fileName
            + ", No such file or directory]");
        throw new Codes.MorelRuntimeException(Codes.BuiltInExn.ERROR);
      }
      try (FileReader fileReader = new FileReader(file);
           BufferedReader bufferedReader = new BufferedReader(fileReader)) {
        run(main.session, new BufferingReader(bufferedReader));
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    void command(AstNode statement, Consumer<String> outLines) {
      try {
        final Environment env = env0.bindAll(bindingMap.values());
        final CompiledStatement compiled =
            Compiles.prepareStatement(main.typeSystem, main.session, env,
                statement, null);
        final List<Binding> bindings = new ArrayList<>();
        compiled.eval(main.session, env, outLines, bindings::add);
        bindings.forEach(b -> this.bindingMap.put(b.id.name, b));
      } catch (Codes.MorelRuntimeException e) {
        final StringBuilder buf = new StringBuilder();
        main.session.handle(e, buf);
        outLines.accept(buf.toString());
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
