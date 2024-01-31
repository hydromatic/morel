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
package net.hydromatic.morel.util;

import java.io.OutputStream;
import java.io.PrintWriter;

import static net.hydromatic.morel.util.Static.str;

import static java.util.Objects.requireNonNull;

/** Implementations of {@link net.hydromatic.morel.util.Unifier.Tracer}. */
public class Tracers {

  private static final NullTracer NULL_TRACER = new NullTracer();

  private Tracers() {}

  /** Returns a tracer that does nothing. */
  public static Unifier.Tracer nullTracer() {
    return NULL_TRACER;
  }

  /** Returns a tracer that writes debugging messages to a writer. */
  public static Unifier.Tracer printTracer(PrintWriter w) {
    return new PrintTracer(w);
  }

  /** Returns a tracer that writes debugging messages to a stream. */
  public static Unifier.Tracer printTracer(OutputStream stream) {
    return printTracer(new PrintWriter(stream));
  }

  /** Implementation of {@link Unifier.Tracer} that does nothing. */
  private static class NullTracer implements Unifier.Tracer {
    public void onDelete(Unifier.Term left, Unifier.Term right) { }
    public void onConflict(Unifier.Sequence left, Unifier.Sequence right) { }
    public void onSequence(Unifier.Sequence left, Unifier.Sequence right) { }
    public void onSwap(Unifier.Term left, Unifier.Term right) { }
    public void onCycle(Unifier.Variable variable, Unifier.Term term) { }
    public void onVariable(Unifier.Variable variable, Unifier.Term term) { }
    public void onSubstitute(Unifier.Term left, Unifier.Term right,
        Unifier.Term left2, Unifier.Term right2) { }
  }

  /** Implementation of {@link Unifier.Tracer} that writes to a given
   * {@link PrintWriter}. */
  private static class PrintTracer implements Unifier.Tracer {
    private final StringBuilder b = new StringBuilder();
    private final PrintWriter w;

    PrintTracer(PrintWriter w) {
      this.w = requireNonNull(w);
    }

    private void flush() {
      w.println(str(b));
      w.flush();
    }

    public void onDelete(Unifier.Term left, Unifier.Term right) {
      b.append("delete ").append(left).append(' ').append(right);
      flush();
    }

    public void onConflict(Unifier.Sequence left, Unifier.Sequence right) {
      b.append("conflict ").append(left).append(' ').append(right);
      flush();
    }

    public void onSequence(Unifier.Sequence left, Unifier.Sequence right) {
      b.append("sequence ").append(left).append(' ').append(right);
      flush();
    }

    public void onSwap(Unifier.Term left, Unifier.Term right) {
      b.append("swap ").append(left).append(' ').append(right);
      flush();
    }

    public void onCycle(Unifier.Variable variable, Unifier.Term term) {
      b.append("cycle ").append(variable).append(' ').append(term);
      flush();
    }

    public void onVariable(Unifier.Variable variable, Unifier.Term term) {
      b.append("variable ").append(variable).append(' ').append(term);
      flush();
    }

    public void onSubstitute(Unifier.Term left, Unifier.Term right,
        Unifier.Term left2, Unifier.Term right2) {
      b.append("substitute ").append(left).append(' ').append(right);
      if (left2 != left) {
        b.append("; ").append(left).append(" -> ").append(left2);
      }
      if (right2 != right) {
        b.append("; ").append(right).append(" -> ").append(right2);
      }
      flush();
    }
  }
}

// End Tracers.java
