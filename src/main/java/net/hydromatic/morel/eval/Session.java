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
package net.hydromatic.morel.eval;

import net.hydromatic.morel.compile.CompileException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/** Session environment.
 *
 * <p>Accessible from {@link EvalEnv#getOpt(String)} via the hidden "$session"
 * variable. */
public class Session {
  /** The plan of the previous command. */
  public Code code;
  /** The output lines of the previous command. */
  public List<String> out;
  /** Property values. */
  public final Map<Prop, Object> map = new LinkedHashMap<>();

  /** Implementation of "use". */
  private Shell shell = DefaultShell.INSTANCE;

  /** Calls some code with a new value of {@link Shell}. */
  public void withShell(Shell shell, Consumer<String> outLines,
      Consumer<Session> consumer) {
    final Shell prevShell = this.shell;
    try {
      this.shell = requireNonNull(shell, "shell");
      consumer.accept(this);
    } catch (RuntimeException e) {
      final StringBuilder buf = new StringBuilder();
      prevShell.handle(e, buf);
      outLines.accept(buf.toString());
    } finally {
      this.shell = prevShell;
    }
  }

  public void use(String fileName) {
    shell.use(fileName);
  }

  public void handle(Codes.MorelRuntimeException e, StringBuilder buf) {
    shell.handle(e, buf);
  }

  /** Callback to implement "use" command. */
  public interface Shell {
    void use(String fileName);

    /** Handles an exception. Particular implementations may re-throw the
     * exception, or may format the exception to a buffer that will be added to
     * the output. Typically the a root shell will handle the exception, and
     * sub-shells will re-throw. */
    void handle(RuntimeException e, StringBuilder buf);
  }

  /** Default implementation of {@link Shell}. */
  private enum DefaultShell implements Shell {
    INSTANCE;

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
}

// End Session.java
