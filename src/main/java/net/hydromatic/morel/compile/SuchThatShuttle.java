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
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import org.apache.calcite.util.Holder;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Static.skip;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

/**
 * Converts unbounded variables to bounded variables.
 *
 * <p>For example, converts
 *
 * <blockquote><pre>{@code
 * from e
 *   where e elem #dept scott
 * }</pre></blockquote>
 *
 * <p>to
 *
 * <blockquote><pre>{@code
 * from e in #dept scott
 * }</pre></blockquote>
 */
class SuchThatShuttle extends Shuttle {
  final @Nullable Environment env;

  SuchThatShuttle(TypeSystem typeSystem, @Nullable Environment env) {
    super(typeSystem);
    this.env = env;
  }

  static boolean containsUnbounded(Core.Decl decl) {
    final Holder<Boolean> found = Holder.of(false);
    decl.accept(new Visitor() {
      @Override protected void visit(Core.Scan scan) {
        super.visit(scan);
        if (Extents.isInfinite(scan.exp)) {
          found.set(true);
        }
      }
    });
    return found.get();
  }

  @Override protected Core.Exp visit(Core.From from) {
    final Core.From from2 = new FromVisitor(typeSystem, env).visit(from);
    return from2.equals(from) ? from : from2;
  }

  /** Workspace for converting unbounded variables in a particular
   * {@link Core.From} to bounded scans. */
  static class FromVisitor {
    final TypeSystem typeSystem;
    final FromBuilder fromBuilder;
    final List<Core.Exp> satisfiedFilters = new ArrayList<>();

    FromVisitor(TypeSystem typeSystem, @Nullable Environment env) {
      this.typeSystem = typeSystem;
      this.fromBuilder = core.fromBuilder(typeSystem, env);
    }

    Core.From visit(Core.From from) {
      final List<Core.FromStep> steps = from.steps;
      final DeferredStepList deferredScans =
          DeferredStepList.create(typeSystem, steps);

      Environment env = Environments.empty();
      final PairList<Core.IdPat, Core.Exp> idPats = PairList.of();
      for (int i = 0; i < steps.size(); i++) {
        final Core.FromStep step = steps.get(i);
        switch (step.op) {
        case SCAN:
          final Core.Scan scan = (Core.Scan) step;
          if (Extents.isInfinite(scan.exp)) {
            final int idPatCount = idPats.size();
            final Core.Exp rewritten = rewrite1(scan, skip(steps, i), idPats);

            // Create a scan for any new variables introduced by rewrite.
            idPats.forEachIndexed((j, pat, extent) -> {
              if (j >= idPatCount) {
                fromBuilder.scan(pat, extent);
              }
            });
            deferredScans.scan(env, scan.pat, rewritten, scan.condition);
          } else {
            deferredScans.scan(env, scan.pat, scan.exp);
          }
          break;

        case YIELD:
          final Core.Yield yield = (Core.Yield) step;
          killTemporaryScans(idPats);
          deferredScans.flush(fromBuilder);
          fromBuilder.yield_(false, yield.bindings, yield.exp);
          break;

        case WHERE:
          final Core.Where where = (Core.Where) step;
          Core.Exp condition =
              core.subTrue(typeSystem, where.exp, satisfiedFilters);
          deferredScans.where(env, condition);
          break;

        case GROUP:
          final Core.Group group = (Core.Group) step;
          killTemporaryScans(idPats);
          deferredScans.flush(fromBuilder);
          fromBuilder.group(group.groupExps, group.aggregates);
          break;

        case ORDER:
          final Core.Order order = (Core.Order) step;
          killTemporaryScans(idPats);
          deferredScans.flush(fromBuilder);
          fromBuilder.order(order.orderItems);
          break;

        default:
          throw new AssertionError(step.op);
        }
        env = Environments.empty().bindAll(step.bindings);
      }
      deferredScans.flush(fromBuilder);
      killTemporaryScans(idPats);
      return fromBuilder.build();
    }

    private void killTemporaryScans(PairList<Core.IdPat, Core.Exp> idPats) {
      if (idPats.isEmpty()) {
        return;
      }
      final PairList<String, Core.Id> nameExps = PairList.of();
      for (Binding b : fromBuilder.bindings()) {
        Core.IdPat id = (Core.IdPat) b.id;
        if (!idPats.leftList().contains(id)) {
          nameExps.add(id.name, core.id(id));
        }
      }
      if (nameExps.size() == 1) {
        fromBuilder.yield_(false, null, nameExps.get(0).getValue());
      } else {
        fromBuilder.yield_(false, null, core.record(typeSystem, nameExps));
      }
      idPats.clear();
    }

    /** Rewrites an unbounded scan to a {@code from} expression,
     * using predicates in later steps to determine the ranges of variables. */
    private Core.From rewrite1(Core.Scan scan,
        List<? extends Core.FromStep> laterSteps,
        PairList<Core.IdPat, Core.Exp> idPats) {
      final Extents.Analysis analysis =
          Extents.create(typeSystem, scan.pat, ImmutableSortedMap.of(),
              laterSteps, idPats);
      satisfiedFilters.addAll(analysis.satisfiedFilters);
      final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
      fromBuilder.scan(scan.pat, analysis.extentExp);
      return fromBuilder.build();
    }
  }

  /** Maintains a list of steps that have not been applied yet.
   *
   * <p>Holds the state necessary for a classic topological sort algorithm:
   * For each node, keep the list of unresolved forward references.
   * After each reference is resolved, remove it from each node's list.
   * Output each node as its unresolved list becomes empty.
   * The topological sort is stable.
   */
  static class DeferredStepList {
    final PairList<Set<Core.Pat>, Consumer<FromBuilder>> steps = PairList.of();
    final FreeFinder freeFinder;
    final List<Core.NamedPat> refs;

    DeferredStepList(FreeFinder freeFinder, List<Core.NamedPat> refs) {
      this.freeFinder = freeFinder;
      this.refs = refs;
    }

    static DeferredStepList create(TypeSystem typeSystem,
        List<Core.FromStep> steps) {
      final ImmutableSet<Core.Pat> forwardRefs =
          steps.stream()
              .filter(step -> step instanceof Core.Scan)
              .map(step -> ((Core.Scan) step).pat)
              .collect(toImmutableSet());
      final List<Core.NamedPat> refs = new ArrayList<>();
      final Consumer<Core.NamedPat> consumer = p -> {
        if (forwardRefs.contains(p)) {
          refs.add(p);
        }
      };
      final FreeFinder freeFinder =
          new FreeFinder(typeSystem, Environments.empty(),
              new ArrayDeque<>(), consumer);
      return new DeferredStepList(freeFinder, refs);
    }

    void scan(Environment env, Core.Pat pat, Core.Exp exp, Core.Exp condition) {
      final Set<Core.Pat> unresolvedRefs = unresolvedRefs(env, exp);
      steps.add(unresolvedRefs, fromBuilder -> {
        fromBuilder.scan(pat, exp, condition);
        resolve(pat);
      });
    }

    void scan(Environment env, Core.Pat pat, Core.Exp exp) {
      final Set<Core.Pat> unresolvedRefs = unresolvedRefs(env, exp);
      steps.add(unresolvedRefs, fromBuilder -> {
        fromBuilder.scan(pat, exp);
        resolve(pat);
      });
    }

    void where(Environment env, Core.Exp condition) {
      final Set<Core.Pat> unresolvedRefs = unresolvedRefs(env, condition);
      steps.add(unresolvedRefs,
          fromBuilder -> fromBuilder.where(condition));
    }

    private Set<Core.Pat> unresolvedRefs(Environment env, Core.Exp exp) {
      refs.clear();
      exp.accept(freeFinder.push(env));
      return new LinkedHashSet<>(refs);
    }

    /** Marks that a pattern has now been defined.
     *
     * <p>After this method, it is possible that some steps might have no
     * unresolved references. Those steps are now ready to add to the
     * builder. */
    void resolve(Core.Pat pat) {
      steps.forEach((unresolvedRefs, consumer) -> unresolvedRefs.remove(pat));
    }

    void flush(FromBuilder fromBuilder) {
      // Are there any scans that had forward references previously but
      // whose references are now all satisfied? Add them to the builder.
      for (;;) {
        int j = steps.firstMatch((unresolvedRefs, consumer) ->
            unresolvedRefs.isEmpty());
        if (j < 0) {
          break;
        }
        final Map.Entry<Set<Core.Pat>, Consumer<FromBuilder>> step =
            steps.remove(j);
        step.getValue().accept(fromBuilder);
      }
    }
  }

  /** Finds free variables in an expression. */
  private static class FreeFinder extends EnvVisitor {
    final Consumer<Core.NamedPat> consumer;

    FreeFinder(TypeSystem typeSystem, Environment env,
        Deque<FromContext> fromStack, Consumer<Core.NamedPat> consumer) {
      super(typeSystem, env, fromStack);
      this.consumer = consumer;
    }

    @Override protected EnvVisitor push(Environment env) {
      return new FreeFinder(typeSystem, env, fromStack, consumer);
    }

    @Override protected void visit(Core.Id id) {
      if (env.getOpt(id.idPat) == null) {
        consumer.accept(id.idPat);
      }
    }
  }
}

// End SuchThatShuttle.java
