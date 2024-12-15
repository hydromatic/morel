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

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.util.Static.str;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.function.BiConsumer;
import net.hydromatic.morel.util.Unifier.Sequence;
import net.hydromatic.morel.util.Unifier.Term;
import net.hydromatic.morel.util.Unifier.Variable;

/** Implementations of {@link net.hydromatic.morel.util.Unifier.Tracer}. */
public class Tracers {

  private Tracers() {}

  /** Returns a tracer that does nothing. */
  public static ConfigurableTracer nullTracer() {
    return ConfigurableTracerImpl.INITIAL;
  }

  /** Returns a tracer that writes debugging messages to a writer. */
  public static ConfigurableTracer printTracer(PrintWriter w) {
    final PrintTracer p = new PrintTracer(w);
    return ConfigurableTracerImpl.INITIAL
        .withConflictHandler(p::onConflict)
        .withCycleHandler(p::onCycle)
        .withDeleteHandler(p::onDelete)
        .withSequenceHandler(p::onSequence)
        .withVariableHandler(p::onVariable)
        .withSubstituteHandler(p::onSubstitute)
        .withSwapHandler(p::onSwap);
  }

  /** Returns a tracer that writes debugging messages to a stream. */
  public static ConfigurableTracer printTracer(OutputStream stream) {
    return printTracer(new PrintWriter(stream));
  }

  /** Implementation of {@link Unifier.Tracer} that does nothing. */
  private static class NullTracer implements Unifier.Tracer {
    public void onDelete(Unifier.Term left, Unifier.Term right) {}

    public void onConflict(Unifier.Sequence left, Unifier.Sequence right) {}

    public void onSequence(Unifier.Sequence left, Unifier.Sequence right) {}

    public void onSwap(Unifier.Term left, Unifier.Term right) {}

    public void onCycle(Unifier.Variable variable, Unifier.Term term) {}

    public void onVariable(Unifier.Variable variable, Unifier.Term term) {}

    public void onSubstitute(
        Unifier.Term left,
        Unifier.Term right,
        Unifier.Term left2,
        Unifier.Term right2) {}
  }

  /**
   * Implementation of {@link Unifier.Tracer} that writes to a given {@link
   * PrintWriter}.
   */
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

    public void onDelete(Term left, Term right) {
      b.append("delete ").append(left).append(' ').append(right);
      flush();
    }

    public void onConflict(Sequence left, Sequence right) {
      b.append("conflict ").append(left).append(' ').append(right);
      flush();
    }

    public void onSequence(Sequence left, Sequence right) {
      b.append("sequence ").append(left).append(' ').append(right);
      flush();
    }

    public void onSwap(Term left, Term right) {
      b.append("swap ").append(left).append(' ').append(right);
      flush();
    }

    public void onCycle(Variable variable, Term term) {
      b.append("cycle ").append(variable).append(' ').append(term);
      flush();
    }

    public void onVariable(Variable variable, Term term) {
      b.append("variable ").append(variable).append(' ').append(term);
      flush();
    }

    public void onSubstitute(Term left, Term right, Term left2, Term right2) {
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

  /** Tracer that allows each of its methods to be modified using a handler. */
  public interface ConfigurableTracer extends Unifier.Tracer {
    /** Sets handler for {@link #onDelete(Term, Term)}. */
    ConfigurableTracer withDeleteHandler(BiConsumer<Term, Term> handler);
    /** Sets handler for {@link #onConflict(Sequence, Sequence)}. */
    ConfigurableTracer withConflictHandler(
        BiConsumer<Sequence, Sequence> handler);
    /** Sets handler for {@link #onSequence(Sequence, Sequence)}. */
    ConfigurableTracer withSequenceHandler(
        BiConsumer<Sequence, Sequence> handler);
    /** Sets handler for {@link #onSwap(Term, Term)}. */
    ConfigurableTracer withSwapHandler(BiConsumer<Term, Term> handler);
    /** Sets handler for {@link #onCycle(Variable, Term)}. */
    ConfigurableTracer withCycleHandler(BiConsumer<Variable, Term> handler);
    /** Sets handler for {@link #onVariable(Variable, Term)}. */
    ConfigurableTracer withVariableHandler(BiConsumer<Variable, Term> handler);
    /** Sets handler for {@link #onSubstitute(Term, Term, Term, Term)}. */
    ConfigurableTracer withSubstituteHandler(
        QuadConsumer<Term, Term, Term, Term> handler);
  }

  /**
   * Consumer that accepts four arguments.
   *
   * @param <T> First argument type
   * @param <U> Second argument type
   * @param <V> Third argument type
   * @param <W> Fourth argument type
   */
  @FunctionalInterface
  public interface QuadConsumer<T, U, V, W> {
    void accept(T t, U u, V v, W w);
  }

  /**
   * Implementation of {@link ConfigurableTracer} that has a field for each
   * handler.
   */
  private static class ConfigurableTracerImpl implements ConfigurableTracer {
    static final ConfigurableTracerImpl INITIAL =
        new ConfigurableTracerImpl(
            (left, right) -> {},
            (left, right) -> {},
            (left, right) -> {},
            (left, right) -> {},
            (left, right) -> {},
            (left, right) -> {},
            (left, right, left2, right2) -> {});

    private final BiConsumer<Term, Term> deleteHandler;
    private final BiConsumer<Sequence, Sequence> conflictHandler;
    private final BiConsumer<Sequence, Sequence> sequenceHandler;
    private final BiConsumer<Term, Term> swapHandler;
    private final BiConsumer<Variable, Term> cycleHandler;
    private final BiConsumer<Variable, Term> variableHandler;
    private final QuadConsumer<Term, Term, Term, Term> substituteHandler;

    private ConfigurableTracerImpl(
        BiConsumer<Term, Term> deleteHandler,
        BiConsumer<Sequence, Sequence> conflictHandler,
        BiConsumer<Sequence, Sequence> sequenceHandler,
        BiConsumer<Term, Term> swapHandler,
        BiConsumer<Variable, Term> cycleHandler,
        BiConsumer<Variable, Term> variableHandler,
        QuadConsumer<Term, Term, Term, Term> substituteHandler) {
      this.deleteHandler = requireNonNull(deleteHandler);
      this.conflictHandler = requireNonNull(conflictHandler);
      this.sequenceHandler = requireNonNull(sequenceHandler);
      this.swapHandler = requireNonNull(swapHandler);
      this.cycleHandler = requireNonNull(cycleHandler);
      this.variableHandler = requireNonNull(variableHandler);
      this.substituteHandler = requireNonNull(substituteHandler);
    }

    @Override
    public ConfigurableTracer withDeleteHandler(
        BiConsumer<Term, Term> deleteHandler) {
      return new ConfigurableTracerImpl(
          deleteHandler,
          conflictHandler,
          sequenceHandler,
          swapHandler,
          cycleHandler,
          variableHandler,
          substituteHandler);
    }

    @Override
    public ConfigurableTracer withConflictHandler(
        BiConsumer<Sequence, Sequence> conflictHandler) {
      return new ConfigurableTracerImpl(
          deleteHandler,
          conflictHandler,
          sequenceHandler,
          swapHandler,
          cycleHandler,
          variableHandler,
          substituteHandler);
    }

    @Override
    public ConfigurableTracer withSequenceHandler(
        BiConsumer<Sequence, Sequence> sequenceHandler) {
      return new ConfigurableTracerImpl(
          deleteHandler,
          conflictHandler,
          sequenceHandler,
          swapHandler,
          cycleHandler,
          variableHandler,
          substituteHandler);
    }

    @Override
    public ConfigurableTracer withSwapHandler(
        BiConsumer<Term, Term> swapHandler) {
      return new ConfigurableTracerImpl(
          deleteHandler,
          conflictHandler,
          sequenceHandler,
          swapHandler,
          cycleHandler,
          variableHandler,
          substituteHandler);
    }

    @Override
    public ConfigurableTracer withCycleHandler(
        BiConsumer<Variable, Term> cycleHandler) {
      return new ConfigurableTracerImpl(
          deleteHandler,
          conflictHandler,
          sequenceHandler,
          swapHandler,
          cycleHandler,
          variableHandler,
          substituteHandler);
    }

    @Override
    public ConfigurableTracer withVariableHandler(
        BiConsumer<Variable, Term> variableHandler) {
      return new ConfigurableTracerImpl(
          deleteHandler,
          conflictHandler,
          sequenceHandler,
          swapHandler,
          cycleHandler,
          variableHandler,
          substituteHandler);
    }

    @Override
    public ConfigurableTracer withSubstituteHandler(
        QuadConsumer<Term, Term, Term, Term> substituteHandler) {
      return new ConfigurableTracerImpl(
          deleteHandler,
          conflictHandler,
          sequenceHandler,
          swapHandler,
          cycleHandler,
          variableHandler,
          substituteHandler);
    }

    @Override
    public void onDelete(Term left, Term right) {
      deleteHandler.accept(left, right);
    }

    @Override
    public void onConflict(Sequence left, Sequence right) {
      conflictHandler.accept(left, right);
    }

    @Override
    public void onSequence(Sequence left, Sequence right) {
      sequenceHandler.accept(left, right);
    }

    @Override
    public void onSwap(Term left, Term right) {
      swapHandler.accept(left, right);
    }

    @Override
    public void onCycle(Variable variable, Term term) {
      cycleHandler.accept(variable, term);
    }

    @Override
    public void onVariable(Variable variable, Term term) {
      variableHandler.accept(variable, term);
    }

    @Override
    public void onSubstitute(Term left, Term right, Term left2, Term right2) {
      substituteHandler.accept(left, right, left2, right2);
    }
  }
}

// End Tracers.java
