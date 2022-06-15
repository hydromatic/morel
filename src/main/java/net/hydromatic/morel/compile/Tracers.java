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
package net.hydromatic.morel.compile;

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.eval.Code;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.function.Consumer;

/** Utilities for {@link Tracer}. */
public abstract class Tracers {

  /** Returns a tracer that does nothing. */
  public static Tracer empty() {
    return EmptyTracer.INSTANCE;
  }

  /** Returns a tracer that performs the given action on a declaration,
   * then calls the underlying tracer. */
  public static Tracer withOnCore(Tracer tracer, int pass,
      Consumer<Core.Decl> consumer) {
    final int expectedPass = pass;
    return new DelegatingTracer(tracer) {
      @Override public void onCore(int pass, Core.Decl e) {
        if (pass == expectedPass) {
          consumer.accept(e);
        }
        super.onCore(pass, e);
      }
    };
  }

  /** Returns a tracer that performs the given action on code,
   * then calls the underlying tracer. */
  public static Tracer withOnPlan(Tracer tracer, Consumer<Code> consumer) {
    return new DelegatingTracer(tracer) {
      @Override public void onPlan(Code code) {
        consumer.accept(code);
        super.onPlan(code);
      }
    };
  }

  /** Returns a tracer that performs the given action on the result of an
   * evaluation, then calls the underlying tracer. */
  public static Tracer withOnResult(Tracer tracer, Consumer<Object> consumer) {
    return new DelegatingTracer(tracer) {
      @Override public void onResult(Object o) {
        consumer.accept(o);
        super.onResult(o);
      }
    };
  }

  public static Tracer withOnWarnings(Tracer tracer,
      Consumer<List<Throwable>> consumer) {
    return new DelegatingTracer(tracer) {
      @Override public void onWarnings(List<Throwable> warningList) {
        consumer.accept(warningList);
        super.onWarnings(warningList);
      }
    };
  }

  public static Tracer withOnException(Tracer tracer,
      Consumer<@Nullable Throwable> consumer) {
    return new DelegatingTracer(tracer) {
      @Override public boolean onException(@Nullable Throwable e) {
        consumer.accept(e);
        super.onException(e);
        return true;
      }
    };
  }

  public static Tracer withOnCompileException(Tracer tracer,
      Consumer<CompileException> consumer) {
    return new DelegatingTracer(tracer) {
      @Override public boolean handleCompileException(
          @Nullable CompileException e) {
        consumer.accept(e);
        super.handleCompileException(e);
        return true;
      }
    };
  }

  /** Tracer that does nothing. */
  private static class EmptyTracer implements Tracer {
    static final Tracer INSTANCE = new EmptyTracer();

    @Override public void onCore(int pass, Core.Decl e) {
    }

    @Override public void onPlan(Code code) {
    }

    @Override public void onResult(Object o) {
    }

    @Override public void onWarnings(List<Throwable> warningList) {
    }

    @Override public boolean onException(@Nullable Throwable e) {
      return false;
    }

    @Override public boolean handleCompileException(
        @Nullable CompileException e) {
      return false;
    }
  }

  /** Tracer that delegates to an underlying tracer. */
  private static class DelegatingTracer implements Tracer {
    final Tracer tracer;

    DelegatingTracer(Tracer tracer) {
      this.tracer = tracer;
    }

    @Override public void onCore(int pass, Core.Decl e) {
      tracer.onCore(pass, e);
    }

    @Override public void onPlan(Code code) {
      tracer.onPlan(code);
    }

    @Override public void onResult(Object o) {
      tracer.onResult(o);
    }

    @Override public void onWarnings(List<Throwable> warningList) {
      tracer.onWarnings(warningList);
    }

    @Override public boolean onException(@Nullable Throwable e) {
      return tracer.onException(e);
    }

    @Override public boolean handleCompileException(
        @Nullable CompileException e) {
      return tracer.handleCompileException(e);
    }
  }
}

// End Tracers.java
