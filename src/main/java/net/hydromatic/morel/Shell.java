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
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.compile.CompiledStatement;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.compile.Tracer;
import net.hydromatic.morel.compile.Tracers;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.DataSet;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.parse.ParseException;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.MorelException;
import net.hydromatic.morel.util.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Runnables;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.MaskingCallback;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

import static net.hydromatic.morel.util.Static.str;

import static java.util.Objects.requireNonNull;

/** Command shell for ML, powered by JLine3. */
public class Shell {
  private final ConfigImpl config;
  private final Terminal terminal;

  /** Command-line entry point.
   *
   * @param args Command-line arguments */
  public static void main(String[] args) {
    try {
      final Config config =
          parse(ConfigImpl.DEFAULT
              .withDirectory(new File(System.getProperty("user.dir"))),
              ImmutableList.copyOf(args));
      final Shell main = create(config, System.in, System.out);
      main.run();
    } catch (Throwable e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  /** Creates a Shell. */
  public static Shell create(List<String> args, InputStream in,
      OutputStream out) throws IOException {
    final Config config = parse(ConfigImpl.DEFAULT, args);
    return create(config, in, out);
  }

  /** Creates a Shell. */
  public static Shell create(Config config, InputStream in,
      OutputStream out) throws IOException {
    final TerminalBuilder builder = TerminalBuilder.builder();
    builder.streams(in, out);
    final ConfigImpl configImpl = (ConfigImpl) config;
    builder.system(configImpl.system);
    builder.dumb(configImpl.dumb);
    if (configImpl.dumb) {
      builder.type("dumb");
    }
    final Terminal terminal = builder.build();
    return new Shell(config, terminal);
  }

  /** Creates a Shell. */
  public Shell(Config config, Terminal terminal) {
    this.config = (ConfigImpl) config;
    this.terminal = terminal;
  }

  /** Parses an argument list to an equivalent Config. */
  public static Config parse(Config config, List<String> argList) {
    ConfigImpl c = (ConfigImpl) config;
    final ImmutableMap.Builder<String, ForeignValue> valueMapBuilder =
        ImmutableMap.builder();
    for (String arg : argList) {
      if (arg.equals("--banner=false")) {
        c = c.withBanner(false);
      }
      if (arg.equals("--terminal=dumb")) {
        c = c.withDumb(true);
      }
      if (arg.equals("--echo")) {
        c = c.withEcho(true);
      }
      if (arg.equals("--help")) {
        c = c.withHelp(true);
      }
      if (arg.equals("--system=false")) {
        c = c.withSystem(false);
      }
      if (arg.startsWith("--foreign=")) {
        final String className = arg.substring("--foreign=".length());
        @SuppressWarnings("unchecked") final Map<String, DataSet> map =
            instantiate(className, Map.class);
        valueMapBuilder.putAll(Calcite.withDataSets(map).foreignValues());
      }
      if (arg.startsWith("--directory=")) {
        final String directoryPath = arg.substring("--directory=".length());
        c = c.withDirectory(new File(directoryPath));
      }
      if (arg.startsWith("--maxUseDepth=")) {
        int maxUseDepth =
            Integer.parseInt(arg.substring("--maxUseDepth=".length()));
        c = c.withMaxUseDepth(maxUseDepth);
      }
    }

    return c.withValueMap(valueMapBuilder.build());
  }

  static void usage(Consumer<String> outLines) {
    String[] usageLines = {
        "Usage: java " + Shell.class.getName(),
    };
    Arrays.asList(usageLines).forEach(outLines);
  }

  static void help(Consumer<String> outLines) {
    String[] helpLines = {
        "List of available commands:",
        "    help   Print this help",
        "    quit   Quit shell",
    };
    Arrays.asList(helpLines).forEach(outLines);
  }

  /** Pauses after creating the terminal.
   *
   * <p>Calls the value set by {@link Config#withPauseFn(Runnable)} which,
   * for the default config, does nothing;
   * the instance used in testing pauses for a few milliseconds,
   * which gives classes time to load and makes test deterministic. */
  protected final void pause() {
    config.pauseFn.run();
  }

  /** Returns whether we can ignore a line. We can ignore a line if it consists
   * only of comments, spaces, and optionally semicolon, and if we are not on a
   * continuation line. */
  private static boolean canIgnoreLine(StringBuilder buf, String line) {
    final String trimmedLine = line
        .replaceAll("\\(\\*.*\\*\\)", "")
        .replaceAll("\\(\\*\\) .*$", "")
        .trim();
    return buf.length() == 0
        && (trimmedLine.isEmpty() || trimmedLine.equals(";"));
  }

  /** Generates a banner to be shown on startup. */
  private String banner() {
    return "morel version 0.3.0"
        + " (java version \"" + System.getProperty("java.version")
        + "\", JRE " + System.getProperty("java.vendor.version")
        + " (build " + System.getProperty("java.vm.version")
        + "), " + terminal.getName()
        + ", " + terminal.getType() + ")";
  }

  public void run() {
    if (config.help) {
      usage(terminal.writer()::println);
      return;
    }

    final Parser parser = new DefaultParser() {
      {
        setEofOnUnclosedQuote(true);
        setEofOnUnclosedBracket(DefaultParser.Bracket.CURLY,
            DefaultParser.Bracket.ROUND, DefaultParser.Bracket.SQUARE);
      }

      @Override public ParsedLine parse(String line, int cursor,
          ParseContext context) {
        // Remove from "(*)" to end of line, if present
        if (line.matches(".*\\(\\*\\).*")) {
          line = line.replaceAll("\\(\\*\\).*$", "");
        }
        return super.parse(line, cursor, context);
      }
    };

    final String equalsPrompt = new AttributedStringBuilder()
        .style(AttributedStyle.DEFAULT.bold()).append("=")
        .style(AttributedStyle.DEFAULT).append(" ")
        .toAnsi(terminal);
    final String minusPrompt = new AttributedStringBuilder()
        .style(AttributedStyle.DEFAULT.bold()).append("-")
        .style(AttributedStyle.DEFAULT).append(" ")
        .toAnsi(terminal);

    if (config.banner) {
      terminal.writer().println(banner());
    }
    LineReader lineReader = LineReaderBuilder.builder()
        .appName("morel")
        .terminal(terminal)
        .parser(parser)
        .variable(LineReader.SECONDARY_PROMPT_PATTERN, equalsPrompt)
        .build();

    pause();
    final TypeSystem typeSystem = new TypeSystem();
    Environment env = Environments.env(typeSystem, config.valueMap);
    final Session session = new Session();
    final LineFn lineFn =
        new TerminalLineFn(minusPrompt, equalsPrompt, lineReader);
    final SubShell subShell =
        new SubShell(1, config.maxUseDepth, lineFn, config.echo, typeSystem,
            env, terminal.writer()::println, session, config.directory);
    final Map<String, Binding> bindings = new LinkedHashMap<>();
    subShell.extracted(bindings);
  }

  /** Instantiates a class.
   *
   * <p>Assumes that the class has a public no-arguments constructor. */
  @Nonnull private static <T> T instantiate(String className,
      @SuppressWarnings("SameParameterValue") Class<T> clazz) {
    try {
      final Class<?> aClass = Class.forName(className);
      return clazz.cast(aClass.getConstructor().newInstance());
    } catch (ClassNotFoundException | NoSuchMethodException
        | InstantiationException | InvocationTargetException
        | IllegalAccessException e) {
      throw new RuntimeException("Cannot load class: " + className, e);
    }
  }

  /** Shell configuration. */
  @SuppressWarnings("unused")
  public interface Config {
    @SuppressWarnings("UnstableApiUsage")
    Config DEFAULT =
        new ConfigImpl(true, false, true, false, false, ImmutableMap.of(),
            new File(""), Runnables.doNothing(), -1);

    Config withBanner(boolean banner);
    Config withDumb(boolean dumb);
    Config withSystem(boolean system);
    Config withEcho(boolean echo);
    Config withHelp(boolean help);
    Config withValueMap(Map<String, ForeignValue> valueMap);
    Config withDirectory(File directory);
    Config withPauseFn(Runnable runnable);
    Config withMaxUseDepth(int maxUseDepth);
  }

  /** Implementation of {@link Config}. */
  private static class ConfigImpl implements Config {
    private final boolean banner;
    private final boolean dumb;
    private final boolean echo;
    private final boolean help;
    private final boolean system;
    private final ImmutableMap<String, ForeignValue> valueMap;
    private final File directory;
    private final Runnable pauseFn;
    private final int maxUseDepth;

    private ConfigImpl(boolean banner, boolean dumb, boolean system,
        boolean echo, boolean help, ImmutableMap<String, ForeignValue> valueMap,
        File directory, Runnable pauseFn, int maxUseDepth) {
      this.banner = banner;
      this.dumb = dumb;
      this.system = system;
      this.echo = echo;
      this.help = help;
      this.valueMap = requireNonNull(valueMap, "valueMap");
      this.directory = requireNonNull(directory, "directory");
      this.pauseFn = requireNonNull(pauseFn, "pauseFn");
      this.maxUseDepth = maxUseDepth;
    }

    @Override public ConfigImpl withBanner(boolean banner) {
      if (this.banner == banner) {
        return this;
      }
      return new ConfigImpl(banner, dumb, system, echo, help, valueMap,
          directory, pauseFn, maxUseDepth);
    }

    @Override public ConfigImpl withDumb(boolean dumb) {
      if (this.dumb == dumb) {
        return this;
      }
      return new ConfigImpl(banner, dumb, system, echo, help, valueMap,
          directory, pauseFn, maxUseDepth);
    }

    @Override public ConfigImpl withSystem(boolean system) {
      if (this.system == system) {
        return this;
      }
      return new ConfigImpl(banner, dumb, system, echo, help, valueMap,
          directory, pauseFn, maxUseDepth);
    }

    @Override public ConfigImpl withEcho(boolean echo) {
      if (this.echo == echo) {
        return this;
      }
      return new ConfigImpl(banner, dumb, system, echo, help, valueMap,
          directory, pauseFn, maxUseDepth);
    }

    @Override public ConfigImpl withHelp(boolean help) {
      if (this.help == help) {
        return this;
      }
      return new ConfigImpl(banner, dumb, system, echo, help, valueMap,
          directory, pauseFn, maxUseDepth);
    }

    @Override public ConfigImpl withValueMap(
        Map<String, ForeignValue> valueMap) {
      if (this.valueMap.equals(valueMap)) {
        return this;
      }
      final ImmutableMap<String, ForeignValue> immutableValueMap =
          ImmutableMap.copyOf(valueMap);
      return new ConfigImpl(banner, dumb, system, echo, help, immutableValueMap,
          directory, pauseFn, maxUseDepth);
    }

    @Override public ConfigImpl withDirectory(File directory) {
      if (this.directory.equals(directory)) {
        return this;
      }
      return new ConfigImpl(banner, dumb, system, echo, help, valueMap,
          directory, pauseFn, maxUseDepth);
    }

    @Override public Config withPauseFn(Runnable pauseFn) {
      if (this.pauseFn.equals(pauseFn)) {
        return this;
      }
      return new ConfigImpl(banner, dumb, system, echo, help, valueMap,
          directory, pauseFn, maxUseDepth);
    }

    @Override public ConfigImpl withMaxUseDepth(int maxUseDepth) {
      if (this.maxUseDepth == maxUseDepth) {
        return this;
      }
      return new ConfigImpl(banner, dumb, system, echo, help, valueMap,
          directory, pauseFn, maxUseDepth);
    }
  }

  /** Abstraction of a terminal's line reader. Can read lines from an input
   * (terminal or file) and categorize the lines. */
  interface LineFn {
    Pair<LineType, String> read(StringBuilder buf);
  }

  /** Type of line from {@link LineFn}. */
  enum LineType {
    QUIT,
    EOF,
    INTERRUPT,
    IGNORE,
    HELP,
    REGULAR
  }

  /** Simplified shell that works in both interactive mode (where input and
   * output is a terminal) and batch mode (where input is a file, and output
   * is to an array of lines). */
  static class SubShell {
    private final int depth;
    private final int maxDepth;
    private final LineFn lineFn;
    private final boolean echo;
    private final TypeSystem typeSystem;
    private final Environment env;
    private final Consumer<String> outLines;
    private final Session session;
    private final File directory;

    SubShell(int depth, int maxDepth, LineFn lineFn,
        boolean echo, TypeSystem typeSystem, Environment env,
        Consumer<String> outLines, Session session, File directory) {
      this.depth = depth;
      this.maxDepth = maxDepth;
      this.lineFn = lineFn;
      this.echo = echo;
      this.typeSystem = typeSystem;
      this.env = env;
      this.outLines = outLines;
      this.session = session;
      this.directory = directory;
    }

    void extracted(@Nullable Map<String, Binding> outBindings) {
      final StringBuilder buf = new StringBuilder();
      final Map<String, Binding> bindingMap = new LinkedHashMap<>();
      final List<Binding> bindings = new ArrayList<>();
      Environment env1 = env;
      for (;;) {
        final Pair<LineType, String> line = lineFn.read(buf);
        switch (line.left) {
        case EOF:
        case QUIT:
          return;

        case IGNORE:
          continue;

        case HELP:
          help(outLines);
          buf.append(line.right).append("\n");
          break;

        case REGULAR:
          try {
            buf.append(line.right);
            if (line.right.endsWith(";")) {
              final String code = str(buf);
              final MorelParserImpl smlParser =
                  new MorelParserImpl(new StringReader(code));
              final AstNode statement;
              try {
                smlParser.zero("stdIn");
                statement = smlParser.statementSemicolon();
                final Environment env0 = env1;
                final List<CompileException> warningList = new ArrayList<>();
                final Tracer tracer = Tracers.empty();
                final CompiledStatement compiled =
                    Compiles.prepareStatement(typeSystem, session, env0,
                        statement, null, warningList::add, tracer);
                final Use shell = new Use(env0, bindingMap);
                session.withShell(shell, outLines, session1 ->
                    compiled.eval(session1, env0, outLines, bindings::add));
                bindings.forEach(b -> bindingMap.put(b.id.name, b));
                env1 = env0.bindAll(bindingMap.values());
                if (outBindings != null) {
                  outBindings.putAll(bindingMap);
                }
                bindingMap.clear();
                bindings.clear();
              } catch (ParseException | CompileException e) {
                outLines.accept(e.getMessage());
              }
              if (echo) {
                outLines.accept(code);
              }
            } else {
              buf.append("\n");
            }
          } catch (IllegalArgumentException e) {
            outLines.accept(e.getMessage());
          }
        }
      }
    }

    /** Implementation of the "use" function. */
    private class Use implements Session.Shell {
      private final Environment env;
      private final Map<String, Binding> bindings;

      Use(Environment env, Map<String, Binding> bindings) {
        this.env = env;
        this.bindings = bindings;
      }

      @Override public void use(String fileName, Pos pos) {
        outLines.accept("[opening " + fileName + "]");
        File file = new File(fileName);
        if (!file.isAbsolute()) {
          file = new File(directory, fileName);
        }
        if (!file.exists()) {
          outLines.accept("[use failed: Io: openIn failed on "
              + fileName
              + ", No such file or directory]");
          throw new Codes.MorelRuntimeException(Codes.BuiltInExn.ERROR, pos);
        }
        if (depth > maxDepth && maxDepth >= 0) {
          outLines.accept("[use failed: Io: openIn failed on "
              + fileName
              + ", Too many open files]");
          throw new Codes.MorelRuntimeException(Codes.BuiltInExn.ERROR, pos);
        }
        try (FileReader fileReader = new FileReader(file);
             BufferedReader bufferedReader = new BufferedReader(fileReader)) {
          final SubShell subShell =
              new SubShell(depth + 1, maxDepth, new ReaderLineFn(bufferedReader),
                  false, typeSystem, env, outLines, session, directory);
          subShell.extracted(bindings);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }

      @Override public void handle(RuntimeException e,
          StringBuilder buf) {
        if (depth != 1) {
          throw e;
        }
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
  }

  /** Implementation of {@link LineFn} that reads from a reader. */
  static class ReaderLineFn implements LineFn {
    private final BufferedReader reader;

    ReaderLineFn(BufferedReader reader) {
      this.reader = reader;
    }

    @Override public Pair<LineType, String> read(StringBuilder buf) {
      try {
        final String line = reader.readLine();
        if (line == null) {
          return Pair.of(LineType.EOF, "");
        }
        if (canIgnoreLine(buf, line)) {
          return Pair.of(LineType.IGNORE, "");
        }
        return Pair.of(LineType.REGULAR, line);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /** Implementation of {@link LineFn} that reads from JLine's terminal.
   * It is used for interactive sessions. */
  private static class TerminalLineFn implements LineFn {
    private final String minusPrompt;
    private final String equalsPrompt;
    private final LineReader lineReader;

    TerminalLineFn(String minusPrompt, String equalsPrompt,
        LineReader lineReader) {
      this.minusPrompt = minusPrompt;
      this.equalsPrompt = equalsPrompt;
      this.lineReader = lineReader;
    }

    @Override public Pair<LineType, String> read(StringBuilder buf) {
      final String line;
      try {
        final String prompt = buf.length() == 0 ? minusPrompt : equalsPrompt;
        final String rightPrompt = null;
        line = lineReader.readLine(prompt, rightPrompt, (MaskingCallback) null,
            null);
      } catch (UserInterruptException e) {
        return Pair.of(LineType.INTERRUPT, "");
      } catch (EndOfFileException e) {
        return Pair.of(LineType.EOF, "");
      }

      if (canIgnoreLine(buf, line)) {
        return Pair.of(LineType.IGNORE, "");
      }

      if (line.equalsIgnoreCase("quit")
          || line.equalsIgnoreCase("exit")) {
        return Pair.of(LineType.QUIT, "");
      }

      final ParsedLine pl = lineReader.getParser().parse(line, 0);
      if ("help".equals(pl.word()) || "?".equals(pl.word())) {
        return Pair.of(LineType.HELP, "");
      }
      return Pair.of(LineType.REGULAR, pl.line());
    }
  }
}

// End Shell.java
