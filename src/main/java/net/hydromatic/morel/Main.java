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
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.CompiledStatement;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.compile.Tracer;
import net.hydromatic.morel.compile.Tracers;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.parse.ParseException;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.MorelException;

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
import java.io.StringReader;
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
  final boolean idempotent;
  final Session session = new Session();

  /** Command-line entry point.
   *
   * @param args Command-line arguments */
  public static void main(String[] args) {
    final Main main =
        new Main(ImmutableList.copyOf(args), System.in, System.out,
            ImmutableMap.of(), new File(System.getProperty("user.dir")), false);
    try {
      main.run();
    } catch (Throwable e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  /** Creates a Main. */
  public Main(List<String> args, InputStream in, PrintStream out,
      Map<String, ForeignValue> valueMap, File directory, boolean idempotent) {
    this(args, new InputStreamReader(in), new OutputStreamWriter(out),
        valueMap, directory, idempotent);
  }

  /** Creates a Main. */
  public Main(List<String> argList, Reader in, Writer out,
      Map<String, ForeignValue> valueMap, File directory, boolean idempotent) {
    this.in = buffer(idempotent ? stripOutLines(in) : in);
    this.out = buffer(out);
    this.echo = argList.contains("--echo");
    this.valueMap = ImmutableMap.copyOf(valueMap);
    this.directory = requireNonNull(directory, "directory");
    this.idempotent = idempotent;
  }

  private static void readerToString(Reader r, StringBuilder b) {
    final char[] chars = new char[1024];
    try {
      for (;;) {
        final int read = r.read(chars);
        if (read < 0) {
          return;
        }
        b.append(chars, 0, read);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static Reader stripOutLines(Reader in) {
    final StringBuilder b = new StringBuilder();
    readerToString(in, b);
    final String s = b.toString();
    b.setLength(0);
    for (int i = 0, n = s.length();;) {
      int j0 = i == 0 && s.startsWith("> ") ? 0 : -1;
      int j1 = s.indexOf("\n> ", i);
      int j2 = s.indexOf("(*)", i);
      int j3 = s.indexOf("(*", i);
      int j = min(j0, j1, j2, j3);
      if (j < 0) {
        b.append(s, i, n);
        break;
      }
      if (j == j0 || j == j1) {
        // Skip line beginning "> "
        b.append(s, i, j);
        int k = s.indexOf("\n", j + 2);
        if (k < 0) {
          k = n;
        }
        i = k;
      } else if (j == j2) {
        // If a line contains "(*)", next search begins at the start of the
        // next line.
        int k = s.indexOf("\n", j + "(*)".length());
        if (k < 0) {
          k = n;
        }
        b.append(s, i, k);
        i = k;
      } else if (j == j3) {
        // If a line contains "(*", next search begins at the next "*)".
        int k = s.indexOf("*)", j + "(*".length());
        if (k < 0) {
          k = n;
        }
        b.append(s, i, k);
        i = k;
      }
    }
    return new StringReader(b.toString());
  }

  /** Returns the minimum non-negative value of the list, or -1 if all are
   * negative. */
  private static int min(int... ints) {
    int count = 0;
    int min = Integer.MAX_VALUE;
    for (int i : ints) {
      if (i >= 0) {
        ++count;
        if (i < min) {
          min = i;
        }
      }
    }
    return count == 0 ? -1 : min;
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
    final Consumer<String> echoLines = out::println;
    final Consumer<String> outLines =
        idempotent
            ? x -> out.println("> " + x.replace("\n", "\n> "))
            : echoLines;
    final Map<String, Binding> outBindings = new LinkedHashMap<>();
    final Shell shell = new Shell(this, env, echoLines, outLines, outBindings);
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
    protected final Consumer<String> echoLines;
    protected final Consumer<String> outLines;
    protected final Map<String, Binding> bindingMap;

    Shell(Main main, Environment env0, Consumer<String> echoLines,
        Consumer<String> outLines, Map<String, Binding> bindingMap) {
      this.main = main;
      this.env0 = env0;
      this.echoLines = echoLines;
      this.outLines = outLines;
      this.bindingMap = bindingMap;
    }

    void run(Session session, BufferingReader in2) {
      final MorelParserImpl parser = new MorelParserImpl(in2);
      final SubShell subShell =
          new SubShell(main, echoLines, outLines, bindingMap, env0);
      for (;;) {
        try {
          parser.zero("stdIn");
          final AstNode statement = parser.statementSemicolonOrEof();
          String code = in2.flush();
          if (main.idempotent) {
            if (code.startsWith("\n")) {
              code = code.substring(1);
            }
          }
          if (statement == null && code.endsWith("\n")) {
            code = code.substring(0, code.length() - 1);
          }
          if (main.echo) {
            echoLines.accept(code);
          }
          if (statement == null) {
            break;
          }
          session.withShell(subShell, outLines, session1 ->
              subShell.command(statement, outLines));
        } catch (ParseException e) {
          final String message = e.getMessage();
          if (message.startsWith("Encountered \"<EOF>\" ")) {
            break;
          }
          String code = in2.flush();
          if (main.echo) {
            outLines.accept(code);
          }
          outLines.accept(message);
          if (code.length() == 0) {
            // If we consumed no input, we're not making progress, so we'll
            // never finish. Abort.
            break;
          }
        }
      }
    }

    @Override public void use(String fileName, Pos pos) {
      throw new UnsupportedOperationException();
    }

    @Override public void handle(RuntimeException e, StringBuilder buf) {
      if (e instanceof MorelException) {
        final MorelException me = (MorelException) e;
        me.describeTo(buf)
            .append("\n")
            .append("  raised at: ");
        me.pos().describeTo(buf);
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

    SubShell(Main main, Consumer<String> echoLines,
        Consumer<String> outLines,
        Map<String, Binding> outBindings, Environment env0) {
      super(main, env0, echoLines, outLines, outBindings);
    }

    @Override public void use(String fileName, Pos pos) {
      outLines.accept("[opening " + fileName + "]");
      File file = new File(fileName);
      if (!file.isAbsolute()) {
        file = new File(main.directory, fileName);
      }
      if (!file.exists()) {
        outLines.accept("[use failed: Io: openIn failed on "
            + fileName
            + ", No such file or directory]");
        throw new Codes.MorelRuntimeException(Codes.BuiltInExn.ERROR, pos);
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
        final Tracer tracer = Tracers.empty();
        final CompiledStatement compiled =
            Compiles.prepareStatement(main.typeSystem, main.session, env,
                statement, null, e -> appendToOutput(e, outLines), tracer);
        final List<Binding> bindings = new ArrayList<>();
        compiled.eval(main.session, env, outLines, bindings::add);
        bindings.forEach(b -> this.bindingMap.put(b.id.name, b));
      } catch (Codes.MorelRuntimeException e) {
        appendToOutput(e, outLines);
      }
    }

    private void appendToOutput(MorelException e, Consumer<String> outLines) {
      final StringBuilder buf = new StringBuilder();
      main.session.handle(e, buf);
      outLines.accept(buf.toString());
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
