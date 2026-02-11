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

import static net.hydromatic.morel.util.Static.str;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.compile.CompiledStatement;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.compile.OutputMatcher;
import net.hydromatic.morel.compile.Tracer;
import net.hydromatic.morel.compile.Tracers;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.parse.MorelParseException;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.MorelException;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Standard ML REPL. */
public class Main {
  private final BufferingReader in;
  private final PrintWriter out;
  private final boolean echo;
  private final Map<String, ForeignValue> valueMap;
  final TypeSystem typeSystem = new TypeSystem();
  final boolean idempotent;
  final Session session;

  /**
   * Command-line entry point.
   *
   * @param args Command-line arguments
   */
  public static void main(String[] args) {
    final List<String> argList = ImmutableList.copyOf(args);
    final Map<String, ForeignValue> valueMap = ImmutableMap.of();
    final Map<Prop, Object> propMap = new LinkedHashMap<>();
    Prop.DIRECTORY.set(propMap, new File(System.getProperty("user.dir")));
    final Main main =
        new Main(argList, System.in, System.out, valueMap, propMap, false);
    try {
      main.run();
    } catch (Throwable e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  /** Creates a Main. */
  public Main(
      List<String> args,
      InputStream in,
      PrintStream out,
      Map<String, ForeignValue> valueMap,
      Map<Prop, Object> propMap,
      boolean idempotent) {
    this(
        args,
        new InputStreamReader(in),
        new OutputStreamWriter(out),
        valueMap,
        propMap,
        idempotent);
  }

  /** Creates a Main. */
  public Main(
      List<String> argList,
      Reader in,
      Writer out,
      Map<String, ForeignValue> valueMap,
      Map<Prop, Object> propMap,
      boolean idempotent) {
    this.out = buffer(out);
    this.echo = argList.contains("--echo");
    this.valueMap = ImmutableMap.copyOf(valueMap);
    this.session = new Session(propMap, typeSystem);
    this.idempotent = idempotent;
    if (idempotent) {
      StripResult result = stripAndCaptureOutLines(in);
      this.in =
          new BufferingReader(
              new StringReader(result.code), result.expectedOutputByOffset);
    } else {
      this.in = new BufferingReader(buffer(in));
    }
  }

  private static void readerToString(Reader r, StringBuilder b) {
    final char[] chars = new char[1024];
    try {
      for (; ; ) {
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

  /** Strips output lines and captures expected output by offset. */
  private static StripResult stripAndCaptureOutLines(Reader in) {
    final StringBuilder b = new StringBuilder();
    readerToString(in, b);
    final String s = str(b);
    final NavigableMap<Integer, String> expectedMap = new TreeMap<>();

    for (int i = 0, n = s.length(); ; ) {
      int j0 = i == 0 && (s.startsWith("> ") || s.startsWith(">\n")) ? 0 : -1;
      int j1 = s.indexOf("\n> ", i); // lint:skip
      int j2 = s.indexOf("\n>\n", i); // lint:skip
      int j3 = s.indexOf(":t", i);
      int j4 = s.indexOf("(*)", i);
      int j5 = s.indexOf("(*", i);
      int j = min(j0, j1, j2, j3, j4, j5);
      if (j < 0) {
        b.append(s, i, n);
        break;
      }
      if (j == j0 || j == j1 || j == j2) {
        // Skip lines beginning ">"
        b.append(s, i, j);
        final int offsetInStripped = b.length();
        // Collect all consecutive ">" lines as expected output
        final StringBuilder expectedBuf = new StringBuilder();
        int k = j;
        while (k < n) {
          // If k == j and j == j0, we're at position 0
          // Otherwise we need a newline before ">"
          if (k == j && j == j0) {
            // At start of string: line begins with ">" at position 0
          } else if (k < n && s.charAt(k) == '\n') {
            k++; // skip the newline before ">"
          } else {
            break;
          }
          if (k >= n || s.charAt(k) != '>') {
            break;
          }
          // We have a ">" line: find end of line
          int lineEnd = s.indexOf("\n", k + 1);
          if (lineEnd < 0) {
            lineEnd = n;
          }
          String line = s.substring(k, lineEnd);
          // Remove "> " prefix (or just ">")
          if (line.startsWith("> ")) {
            line = line.substring(2);
          } else if (line.equals(">")) {
            line = "";
          } else {
            break;
          }
          if (expectedBuf.length() > 0) {
            expectedBuf.append('\n');
          }
          expectedBuf.append(line);
          k = lineEnd;
          // Check if next line also starts with ">"
          if (k < n && s.charAt(k) == '\n') {
            int peek = k + 1;
            if (peek < n && s.charAt(peek) == '>') {
              // Continue collecting
              continue;
            }
          }
          break;
        }
        if (expectedBuf.length() > 0) {
          expectedMap.put(offsetInStripped, expectedBuf.toString());
        }
        i = k;
      } else if (j == j3) {
        // Found ":t" - check if followed by space or newline
        boolean atLineStart = j == 0 || s.charAt(j - 1) == '\n';
        boolean followedBySpaceOrNewline =
            j + 2 < n && (s.charAt(j + 2) == ' ' || s.charAt(j + 2) == '\n');
        if (atLineStart && followedBySpaceOrNewline) {
          b.append(s, i, j);
          b.append("(*TYPE_ONLY*)");
          i =
              j
                  + (s.charAt(j + 2) == ' '
                      ? 3
                      : 2); // Skip ":t " or ":t" (not newline)
        } else {
          b.append(s, i, j + 1);
          i = j + 1;
        }
      } else if (j == j4) {
        // If a line contains "(*)", next search begins at the start of the
        // next line.
        int k = s.indexOf("\n", j + "(*)".length());
        if (k < 0) {
          k = n;
        }
        b.append(s, i, k);
        i = k;
      } else if (j == j5) {
        // If a line contains "(*", skip the block comment (accounting for
        // nesting) and continue searching after it.
        int k = skipBlockComment(s, j + "(*".length(), n);
        b.append(s, i, k);
        i = k;
      }
    }
    return new StripResult(b.toString(), expectedMap);
  }

  /** Strips output lines without capturing expected output (for sub-shells). */
  private static Reader stripOutLines(Reader in) {
    return new StringReader(stripAndCaptureOutLines(in).code);
  }

  /**
   * Skips a block comment in the input string, accounting for nested comments.
   *
   * @param s Input string
   * @param pos Position after the opening "(*"
   * @param n Length of the string
   * @return Position after the closing "*)" or end of string if no closing
   *     found
   */
  private static int skipBlockComment(String s, int pos, int n) {
    while (pos < n) {
      // Check for nested "(*" (but not "(*)").
      int nextOpen = s.indexOf("(*", pos);
      int nextClose = s.indexOf("*)", pos);

      if (nextClose < 0) {
        // No closing "*)" found.
        return n;
      }

      if (nextOpen >= 0 && nextOpen < nextClose) {
        // Found a nested "(*" before the closing "*)".
        // Check if it's actually "(*)" which is not a nested comment.
        if (nextOpen + 2 < n && s.charAt(nextOpen + 2) == ')') {
          // It's "(*)", skip it and continue searching.
          pos = nextOpen + 3;
        } else {
          // It's a nested comment, recursively skip it.
          pos = skipBlockComment(s, nextOpen + 2, n);
        }
      } else {
        // Found the closing "*)".
        return nextClose + 2;
      }
    }
    return n;
  }

  /**
   * Returns the minimum non-negative value of the list, or -1 if all are
   * negative.
   */
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
    Environment env = Environments.env(typeSystem, session, valueMap);
    final Consumer<String> echoLines = out::println;
    final Consumer<String> outLines =
        idempotent ? x -> out.println(prefixLines(x)) : echoLines;
    final Multimap<String, Binding> outBindings =
        ArrayListMultimap.create(100, 2);
    final Shell shell = new Shell(this, env, echoLines, outLines, outBindings);
    session.withShell(
        shell,
        outLines,
        session1 -> shell.run(session1, in, echoLines, outLines));
    out.flush();
  }

  /**
   * Precedes every line in 'x' with a caret. The caret is followed by a space
   * except if the line is empty.
   */
  static String prefixLines(String s) {
    final int capacity = s.length() + 16; // expansion room for 8 lines
    final StringBuilder b = new StringBuilder(capacity);
    b.append('>');
    int lineStart = b.length();
    for (int i = 0; i < s.length(); ++i) {
      final char c = s.charAt(i);
      if (c == '\n') {
        b.append('\n');
        b.append('>');
        lineStart = b.length();
      } else {
        if (b.length() == lineStart) {
          // Convert ">" to "> " now we know the line is not empty.
          b.append(' ');
        }
        b.append(c);
      }
    }
    return b.toString();
  }

  /**
   * Shell (or sub-shell created via {@link use
   * net.hydromatic.morel.compile.BuiltIn#INTERACT_USE}) that can execute
   * commands and handle errors.
   */
  static class Shell implements Session.Shell {
    protected final Main main;
    protected Environment env0;
    protected final Consumer<String> echoLines;
    protected final Consumer<String> outLines;
    /**
     * Contains the environment created by previous commands in this shell. It
     * is a multimap so that overloads with the same name can all be stored.
     */
    protected final Multimap<String, Binding> bindingMap;

    Shell(
        Main main,
        Environment env0,
        Consumer<String> echoLines,
        Consumer<String> outLines,
        Multimap<String, Binding> bindingMap) {
      this.main = main;
      this.env0 = env0;
      this.echoLines = echoLines;
      this.outLines = outLines;
      this.bindingMap = bindingMap;
    }

    void run(
        Session session,
        BufferingReader in2,
        Consumer<String> echoLines,
        Consumer<String> outLines) {
      final MorelParserImpl parser = new MorelParserImpl(in2);
      final LineConsumer lineConsumer =
          main.idempotent
              ? new BufferingLineConsumer(outLines)
              : new DirectLineConsumer(outLines);
      final SubShell subShell =
          new SubShell(main, echoLines, lineConsumer, bindingMap, env0);
      for (; ; ) {
        try {
          Pos pos = parser.nextTokenPos();
          parser.zero("stdIn");
          final AstNode statement = parser.statementSemicolonOrEofSafe();
          String code = in2.flush();
          final @Nullable String expectedOutput = in2.expectedOutput();
          if (main.idempotent) {
            if (code.startsWith("\n")) {
              code = code.substring(1);
            }
          }
          if (statement == null && code.endsWith("\n")) {
            code = code.substring(0, code.length() - 1);
          }
          // Check if code contains (*TYPE_ONLY*) marker (type-only mode)
          final boolean typeOnly =
              main.idempotent && code.contains("(*TYPE_ONLY*)");
          if (typeOnly) {
            // Insert ":t " or ":t\n" where the marker was
            code =
                code.replace("(*TYPE_ONLY*)\n", ":t\n")
                    .replace("(*TYPE_ONLY*)", ":t ");
          }
          if (main.echo) {
            echoLines.accept(code);
          }
          if (statement == null) {
            break;
          }
          session.withShell(
              subShell,
              outLines,
              session1 ->
                  subShell.command(
                      statement, lineConsumer, typeOnly, expectedOutput));
        } catch (MorelParseException | CompileException e) {
          final String message = e.getMessage();
          if (message.startsWith("Encountered \"<EOF>\" ")) {
            break;
          }
          String code = in2.flush();
          if (main.echo) {
            outLines.accept(code);
          }
          outLines.accept(message);
          if (code.isEmpty()) {
            // If we consumed no input, we're not making progress, so we'll
            // never finish. Abort.
            break;
          }
        }
      }
    }

    @Override
    public void use(String fileName, boolean silent, Pos pos) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void handle(RuntimeException e, StringBuilder buf) {
      if (e instanceof MorelException) {
        final MorelException me = (MorelException) e;
        me.describeTo(buf).append("\n").append("  raised at: ");
        me.pos().describeTo(buf);
      } else {
        buf.append(e);
      }
    }

    @Override
    public void clearEnv() {
      bindingMap.clear();
      env0 = Environments.env(main.typeSystem, main.session, main.valueMap);
    }

    /**
     * Implementation of {@link LineConsumer} that appends a copy of each line
     * received to an internal list.
     */
    private static class BufferingLineConsumer implements LineConsumer {
      private final List<String> lines = new ArrayList<>();
      private final Consumer<String> consumer;

      BufferingLineConsumer(Consumer<String> consumer) {
        this.consumer = consumer;
      }

      @Override
      public Consumer<String> consumer() {
        return consumer;
      }

      @Override
      public void start() {
        lines.clear();
      }

      @Override
      public List<String> bufferedLines() {
        return ImmutableList.copyOf(lines);
      }

      @Override
      public void accept(String line) {
        lines.add(line);
      }
    }

    /**
     * Implementation of {@link LineConsumer} that does not buffer, writing
     * lines to a downstream consumer.
     */
    private static class DirectLineConsumer implements LineConsumer {
      private final Consumer<String> outLines;

      DirectLineConsumer(Consumer<String> outLines) {
        this.outLines = outLines;
      }

      @Override
      public Consumer<String> consumer() {
        throw new UnsupportedOperationException();
      }

      @Override
      public void start() {
        throw new UnsupportedOperationException();
      }

      @Override
      public List<String> bufferedLines() {
        throw new UnsupportedOperationException();
      }

      @Override
      public void accept(String line) {
        outLines.accept(line);
      }
    }
  }

  /**
   * Shell that is created via the {@link use
   * net.hydromatic.morel.compile.BuiltIn#INTERACT_USE}) command. Like a
   * top-level shell, it can execute commands and handle errors. But its input
   * is a file, and its output is to the same output as its parent shell.
   */
  static class SubShell extends Shell {

    SubShell(
        Main main,
        Consumer<String> echoLines,
        Consumer<String> outLines,
        Multimap<String, Binding> outBindings,
        Environment env0) {
      super(main, env0, echoLines, outLines, outBindings);
    }

    @Override
    public void use(String fileName, boolean silent, Pos pos) {
      // In idempotent mode, route through commandOutLines so that
      // emitOutput can compare actual vs expected output correctly.
      outLines.accept("[opening " + fileName + "]");
      File file = new File(fileName);
      if (!file.isAbsolute()) {
        final File directory =
            Prop.SCRIPT_DIRECTORY.fileValue(main.session.map);
        file = new File(directory, fileName);
      }
      if (!file.exists()) {
        outLines.accept(
            "[use failed: Io: openIn failed on "
                + fileName
                + ", No such file or directory]");
        throw new Codes.MorelRuntimeException(Codes.BuiltInExn.ERROR, pos);
      }
      final Consumer<String> echoLines2 = silent ? line -> {} : echoLines;
      final Consumer<String> outLines2 = silent ? line -> {} : outLines;
      try (FileReader in = new FileReader(file);
          Reader bufferedReader =
              buffer(main.idempotent ? stripOutLines(in) : in)) {
        run(
            main.session,
            new BufferingReader(bufferedReader),
            echoLines2,
            outLines2);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    void command(
        AstNode statement,
        LineConsumer outLines,
        boolean typeOnly,
        @Nullable String expectedOutput) {
      if (expectedOutput != null) {
        outLines.start();
      }

      try {
        final Environment env = env0.bindAll(bindingMap.values());
        final Tracer tracer = Tracers.empty();
        final CompiledStatement compiled =
            Compiles.prepareStatement(
                main.typeSystem,
                main.session,
                env,
                statement,
                null,
                e -> appendToOutput(e, outLines),
                tracer);
        final List<Binding> bindings = new ArrayList<>();
        if (typeOnly) {
          // For type-only mode, get bindings without evaluation
          Consumer<Binding> typeOnlyConsumer =
              binding -> {
                bindings.add(binding);
                outLines.accept(
                    "val " + binding.id.name + " : " + binding.id.type);
              };
          compiled.getBindings(typeOnlyConsumer);
        } else {
          compiled.eval(main.session, env, outLines, bindings::add);
        }

        if (expectedOutput != null) {
          // Expected output is provided. If the expected and actual output are
          // same modulo whitespace, line endings and re-ordered elements of
          // bags, emit the expected output.
          final List<String> actualLines = outLines.bufferedLines();
          final String actualOutput = String.join("\n", actualLines);
          if (actualOutput.equals(expectedOutput)
              || new OutputMatcher(main.typeSystem)
                  .equivalent(
                      compiled.getType(), actualOutput, expectedOutput)) {
            // Expected and actual are equivalent; emit expected verbatim
            Arrays.stream(expectedOutput.split("\n", -1))
                .forEach(outLines.consumer());
          } else {
            actualLines.forEach(outLines.consumer());
          }
        }

        // Add the new bindings to the map. Overloaded bindings (INST) add to
        // previous bindings; ordinary bindings (VAL) replace previous bindings
        // of the same name.
        for (Binding binding : bindings) {
          if (binding.overloadId == null
              && bindingMap.containsKey(binding.id.name)) {
            // This is not an instance of an overload, so if there was a
            // previous value we must overwrite it, not append to it.
            bindingMap.replaceValues(
                binding.id.name, ImmutableList.of(binding));
          } else {
            bindingMap.put(binding.id.name, binding);
          }
        }
      } catch (Codes.MorelRuntimeException e) {
        appendToOutput(e, outLines);
      }
    }

    private void appendToOutput(MorelException e, LineConsumer outLines) {
      final StringBuilder buf = new StringBuilder();
      main.session.handle(e, buf);
      outLines.accept(buf.toString());
    }
  }

  /**
   * Reader that snoops which characters have been read and saves them in a
   * buffer until {@link #flush} is called.
   */
  static class BufferingReader extends FilterReader {
    final StringBuilder buf = new StringBuilder();
    private final @Nullable NavigableMap<Integer, String> expectedMap;
    private int offset = 0;
    private int flushStart = 0;

    protected BufferingReader(Reader in) {
      this(in, null);
    }

    BufferingReader(
        Reader in, @Nullable NavigableMap<Integer, String> expectedMap) {
      super(in);
      this.expectedMap = expectedMap;
    }

    @Override
    public int read() throws IOException {
      int c = super.read();
      if (c >= 0) {
        buf.append((char) c);
        offset++;
      }
      return c;
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
      int n = super.read(cbuf, off, 1);
      if (n > 0) {
        buf.append(cbuf, off, n);
        offset += n;
      }
      return n;
    }

    public String flush() {
      flushStart = offset;
      return str(buf);
    }

    /**
     * Returns expected output for the most recently flushed chunk, or null.
     * Uses position-based lookup to stay in sync.
     */
    public @Nullable String expectedOutput() {
      if (expectedMap == null) {
        return null;
      }
      Map.Entry<Integer, String> entry = expectedMap.floorEntry(flushStart);
      return entry != null ? entry.getValue() : null;
    }
  }

  /** Result of stripping output lines from an idempotent script. */
  static class StripResult {
    final String code;
    final NavigableMap<Integer, String> expectedOutputByOffset;

    StripResult(
        String code, NavigableMap<Integer, String> expectedOutputByOffset) {
      this.code = code;
      this.expectedOutputByOffset = expectedOutputByOffset;
    }
  }

  /** Can consume output lines. */
  interface LineConsumer extends Consumer<String> {
    /** Starts recording output lines. */
    void start();

    /**
     * Returns a list of lines that were output since {@link #start} was called.
     */
    List<String> bufferedLines();

    /** Returns the downstream consumer. */
    Consumer<String> consumer();
  }
}

// End Main.java
