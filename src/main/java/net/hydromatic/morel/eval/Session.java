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

import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.util.MorelException;

import com.google.common.base.Suppliers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
  public final Map<Prop, Object> map;

  /** File system.
   *
   * <p>Wrapped in a Supplier to avoid the cost of initializing it (scanning
   * a directory) for every session. */
  public final Supplier<File> file;

  /** Implementation of "use". */
  private Shell shell = Shells.INSTANCE;

  /** Creates a Session.
   *
   * <p>The {@code map} parameter, that becomes the property map, is used as is,
   * not copied. It may be immutable if the session is for a narrow, internal
   * use. Otherwise, it should probably be a {@link LinkedHashMap} to provide
   * deterministic iteration order.
   *
   * @param map Map that contains property values */
  public Session(Map<Prop, Object> map) {
    this.map = map;
    this.file =
        Suppliers.memoize(() ->
            Files.create(Prop.DIRECTORY.fileValue(this.map)));
  }

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

  /** Calls some code with a {@link Shell} that does not handle errors. */
  public void withoutHandlingExceptions(Consumer<Session> consumer) {
    final Shell prevShell = this.shell;
    try {
      this.shell = Shells.BARF;
      consumer.accept(this);
    } finally {
      this.shell = prevShell;
    }
  }

  public void use(String fileName, Pos pos) {
    shell.use(fileName, pos);
  }

  public void handle(MorelException e, StringBuilder buf) {
    shell.handle((RuntimeException) e, buf);
  }

  /** Callback to implement "use" command. */
  public interface Shell {
    void use(String fileName, Pos pos);

    /** Handles an exception. Particular implementations may re-throw the
     * exception, or may format the exception to a buffer that will be added to
     * the output. Typically, a root shell will handle the exception, and
     * sub-shells will re-throw. */
    void handle(RuntimeException e, StringBuilder buf);
  }

  /** Various implementations of {@link Shell}. */
  private enum Shells implements Shell {
    /** Default instance of Shell. */
    INSTANCE {
      @Override public void handle(RuntimeException e, StringBuilder buf) {
        if (e instanceof Codes.MorelRuntimeException) {
          ((Codes.MorelRuntimeException) e).describeTo(buf);
        } else if (e instanceof CompileException) {
          buf.append(e.getMessage());
        } else {
          buf.append(e);
        }
      }
    },

    /** Instance of Shell that does not handle exceptions. */
    BARF {
      @Override public void handle(RuntimeException e, StringBuilder buf) {
        throw e;
      }
    };

    @Override public void use(String fileName, Pos pos) {
      throw new UnsupportedOperationException();
    }
  }
}

// End Session.java
