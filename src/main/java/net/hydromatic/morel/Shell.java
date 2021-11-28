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
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.DataSet;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.parse.ParseException;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Runnables;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

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
          parse(ConfigImpl.DEFAULT,
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
    }

    return c.withValueMap(valueMapBuilder.build());
  }

  void usage() {
    String[] usageLines = {
        "Usage: java " + Shell.class.getName(),
    };
    printAll(Arrays.asList(usageLines));
  }

  void help() {
    String[] helpLines = {
        "List of available commands:",
        "    help   Print this help",
        "    quit   Quit shell",
    };
    printAll(Arrays.asList(helpLines));
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

  private void printAll(List<String> lines) {
    for (String line : lines) {
      terminal.writer().println(line);
    }
  }

  /** Generates a banner to be shown on startup. */
  private String banner() {
    return "morel version 0.2.0"
        + " (java version \"" + System.getProperty("java.version")
        + "\", JRE " + System.getProperty("java.vendor.version")
        + " (build " + System.getProperty("java.vm.version")
        + "), " + terminal.getName()
        + ", " + terminal.getType() + ")";
  }

  public void run() {
    if (config.help) {
      usage();
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
    LineReader reader = LineReaderBuilder.builder()
        .appName("morel")
        .terminal(terminal)
        .parser(parser)
        .variable(LineReader.SECONDARY_PROMPT_PATTERN, equalsPrompt)
        .build();

    pause();
    final TypeSystem typeSystem = new TypeSystem();
    Environment env = Environments.env(typeSystem, config.valueMap);
    final StringBuilder buf = new StringBuilder();
    final List<String> lines = new ArrayList<>();
    final List<Binding> bindings = new ArrayList<>();
    final Session session = new Session();
    while (true) {
      String line = "";
      try {
        final String prompt = buf.length() == 0 ? minusPrompt : equalsPrompt;
        final String rightPrompt = null;
        line = reader.readLine(prompt, rightPrompt, (MaskingCallback) null,
            null);
      } catch (UserInterruptException e) {
        // Ignore
      } catch (EndOfFileException e) {
        return;
      }

      // Ignore this line if it consists only of comments, spaces, and
      // optionally semicolon, and if we are not on a continuation line.
      final String trimmedLine = line
          .replaceAll("\\(\\*.*\\*\\)", "")
          .replaceAll("\\(\\*\\) .*$", "")
          .trim();
      if (buf.length() == 0
          && (trimmedLine.isEmpty() || trimmedLine.equals(";"))) {
        continue;
      }

      if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
        break;
      }
      final ParsedLine pl = reader.getParser().parse(line, 0);
      try {
        if ("help".equals(pl.word()) || "?".equals(pl.word())) {
          help();
        }
        buf.append(pl.line());
        if (pl.line().endsWith(";")) {
          final String code = buf.toString();
          buf.setLength(0);
          final MorelParserImpl smlParser =
              new MorelParserImpl(new StringReader(code));
          final AstNode statement;
          try {
            statement = smlParser.statementSemicolon();
            final CompiledStatement compiled =
                Compiles.prepareStatement(typeSystem, session, env, statement,
                    null);
            compiled.eval(session, env, lines, bindings);
            printAll(lines);
            terminal.writer().flush();
            lines.clear();
            env = env.bindAll(bindings);
            bindings.clear();
          } catch (ParseException | CompileException e) {
            terminal.writer().println(e.getMessage());
          }
          if (config.echo) {
            terminal.writer().println(code);
          }
        } else {
          buf.append("\n");
        }
      } catch (IllegalArgumentException e) {
        terminal.writer().println(e.getMessage());
      }
    }
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
            Runnables.doNothing());

    Config withBanner(boolean banner);
    Config withDumb(boolean dumb);
    Config withSystem(boolean system);
    Config withEcho(boolean echo);
    Config withHelp(boolean help);
    Config withValueMap(Map<String, ForeignValue> valueMap);
    Config withPauseFn(Runnable runnable);
  }

  /** Implementation of {@link Config}. */
  private static class ConfigImpl implements Config {
    private final boolean banner;
    private final boolean dumb;
    private final boolean echo;
    private final boolean help;
    private final boolean system;
    private final ImmutableMap<String, ForeignValue> valueMap;
    private final Runnable pauseFn;

    private ConfigImpl(boolean banner, boolean dumb, boolean system,
        boolean echo, boolean help, ImmutableMap<String, ForeignValue> valueMap,
        Runnable pauseFn) {
      this.banner = banner;
      this.dumb = dumb;
      this.system = system;
      this.echo = echo;
      this.help = help;
      this.valueMap = requireNonNull(valueMap, "valueMap");
      this.pauseFn = requireNonNull(pauseFn, "pauseFn");
    }

    @Override public ConfigImpl withBanner(boolean banner) {
      if (this.banner == banner) {
        return this;
      }
      return new ConfigImpl(banner, dumb, system, echo, help, valueMap,
          pauseFn);
    }

    @Override public ConfigImpl withDumb(boolean dumb) {
      if (this.dumb == dumb) {
        return this;
      }
      return new ConfigImpl(banner, dumb, system, echo, help, valueMap,
          pauseFn);
    }

    @Override public ConfigImpl withSystem(boolean system) {
      if (this.system == system) {
        return this;
      }
      return new ConfigImpl(banner, dumb, system, echo, help, valueMap,
          pauseFn);
    }

    @Override public ConfigImpl withEcho(boolean echo) {
      if (this.echo == echo) {
        return this;
      }
      return new ConfigImpl(banner, dumb, system, echo, help, valueMap,
          pauseFn);
    }

    @Override public ConfigImpl withHelp(boolean help) {
      if (this.help == help) {
        return this;
      }
      return new ConfigImpl(banner, dumb, system, echo, help, valueMap,
          pauseFn);
    }

    @Override public ConfigImpl withValueMap(
        Map<String, ForeignValue> valueMap) {
      if (this.valueMap.equals(valueMap)) {
        return this;
      }
      final ImmutableMap<String, ForeignValue> immutableValueMap =
          ImmutableMap.copyOf(valueMap);
      return new ConfigImpl(banner, dumb, system, echo, help, immutableValueMap,
          pauseFn);
    }

    @Override public Config withPauseFn(Runnable pauseFn) {
      if (this.pauseFn.equals(pauseFn)) {
        return this;
      }
      return new ConfigImpl(banner, dumb, system, echo, help, valueMap,
          pauseFn);
    }
  }
}

// End Shell.java
