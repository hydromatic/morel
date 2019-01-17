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
import net.hydromatic.sml.eval.Environment;
import net.hydromatic.sml.eval.Environments;
import net.hydromatic.sml.parse.ParseException;
import net.hydromatic.sml.parse.SmlParserImpl;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/** Standard ML REPL. */
public class Main {
  private final String[] args;
  private final InputStream in;
  private final PrintStream out;

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
    this.args = args;
    this.in = in;
    this.out = out;
  }

  public void run() {
    final SmlParserImpl parser = new SmlParserImpl(in);
    final Compiler compiler = new Compiler();
    Environment env = Environments.empty();
    final List<String> lines = new ArrayList<>();
    for (;;) {
      try {
        final AstNode statement = parser.statementSemicolon();
        final Compiler.CompiledStatement compiled =
            compiler.compileStatement(statement);
        env = compiled.eval(env, lines);
        for (String line : lines) {
          out.println(line);
        }
        lines.clear();
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
}

// End Main.java
