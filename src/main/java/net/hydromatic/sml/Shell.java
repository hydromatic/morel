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

import com.google.common.collect.ImmutableList;

import net.hydromatic.sml.ast.AstNode;
import net.hydromatic.sml.compile.CompileException;
import net.hydromatic.sml.compile.CompiledStatement;
import net.hydromatic.sml.compile.Compiles;
import net.hydromatic.sml.compile.Environment;
import net.hydromatic.sml.compile.Environments;
import net.hydromatic.sml.parse.ParseException;
import net.hydromatic.sml.parse.SmlParserImpl;
import net.hydromatic.sml.type.Binding;
import net.hydromatic.sml.type.TypeSystem;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.MaskingCallback;
import org.jline.reader.ParsedLine;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Command shell for ML, powered by JLine3. */
public class Shell {
  private final List<String> argList;
  private final boolean echo;
  private final Terminal terminal;
  private final boolean banner;
  private final boolean system;
  private boolean help;

  /** Command-line entry point.
   *
   * @param args Command-line arguments */
  public static void main(String[] args) {
    try {
      final Shell main = new Shell(args, System.in, System.out);
      main.run();
    } catch (Throwable e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  /** Creates a Shell. */
  public Shell(String[] args, InputStream in, OutputStream out)
      throws IOException {
    this.argList = ImmutableList.copyOf(args);
    this.banner = !argList.contains("--banner=false");
    final boolean dumb = argList.contains("--terminal=dumb");
    this.echo = argList.contains("--echo");
    this.help = argList.contains("--help");
    this.system = !argList.contains("--system=false");

    final TerminalBuilder builder = TerminalBuilder.builder();
    builder.streams(in, out);
    builder.system(system);
    builder.dumb(dumb);
    if (dumb) {
      builder.type("dumb");
    }
    terminal = builder.build();
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

  private void printAll(List<String> lines) {
    for (String line : lines) {
      terminal.writer().println(line);
    }
  }

  /** Generates a banner to be shown on startup. */
  private String banner() {
    return "smlj version 0.1.0"
        + " (java version \"" + System.getProperty("java.version")
        + "\", JRE " + System.getProperty("java.vendor.version")
        + " (build " + System.getProperty("java.vm.version")
        + "), " + terminal.getName()
        + ", " + terminal.getType() + ")";
  }

  public void run() {
    if (help) {
      usage();
      return;
    }

    final DefaultParser parser = new DefaultParser();
    parser.setEofOnUnclosedQuote(true);
    parser.setEofOnUnclosedBracket(DefaultParser.Bracket.CURLY,
        DefaultParser.Bracket.ROUND, DefaultParser.Bracket.SQUARE);

    final String equalsPrompt = new AttributedStringBuilder()
        .style(AttributedStyle.DEFAULT.bold()).append("=")
        .style(AttributedStyle.DEFAULT).append(" ")
        .toAnsi(terminal);
    final String minusPrompt = new AttributedStringBuilder()
        .style(AttributedStyle.DEFAULT.bold()).append("-")
        .style(AttributedStyle.DEFAULT).append(" ")
        .toAnsi(terminal);

    if (banner) {
      terminal.writer().println(banner());
    }
    LineReader reader = LineReaderBuilder.builder()
        .terminal(terminal)
        .parser(parser)
        .variable(LineReader.SECONDARY_PROMPT_PATTERN, minusPrompt)
        .build();

    final TypeSystem typeSystem = new TypeSystem();
    Environment env = Environments.empty();
    final StringBuilder buf = new StringBuilder();
    final List<String> lines = new ArrayList<>();
    final List<Binding> bindings = new ArrayList<>();
    while (true) {
      String line = "";
      try {
        final String prompt = buf.length() == 0 ? equalsPrompt : minusPrompt;
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
      line = line.replaceAll("\\(\\*.*\\*\\)", "")
          .replaceAll("\\(\\*\\) .*$", "")
          .trim();
      if (buf.length() == 0 && (line.isEmpty() || line.equals(";"))) {
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
          final SmlParserImpl smlParser =
              new SmlParserImpl(new StringReader(code));
          final AstNode statement;
          try {
            statement = smlParser.statementSemicolon();
            final CompiledStatement compiled =
                Compiles.prepareStatement(typeSystem, env, statement);
            compiled.eval(env, lines, bindings);
            printAll(lines);
            terminal.writer().flush();
            lines.clear();
            env = env.bindAll(bindings);
            bindings.clear();
          } catch (ParseException | CompileException e) {
            terminal.writer().println(e.getMessage());
          }
          if (echo) {
            terminal.writer().println(code);
          }
        }
      } catch (IllegalArgumentException e) {
        terminal.writer().println(e.getMessage());
      }
    }
  }
}

// End Shell.java
