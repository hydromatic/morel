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

import static com.google.common.collect.Iterables.getLast;
import static java.lang.String.format;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.compile.Generators.maybeGenerator;
import static net.hydromatic.morel.util.Static.append;
import static net.hydromatic.morel.util.Static.forEachInIntersection;
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;

/** Expands generators. */
public class Expander {
  private final Generators.Cache cache;
  private final List<Core.Exp> constraints;

  private Expander(Generators.Cache cache, List<Core.Exp> constraints) {
    this.cache = cache;
    this.constraints = constraints;
  }

  /**
   * Converts all unbounded variables in a query to bounded, introducing
   * generators by inverting predicates.
   *
   * <p>Returns {@code from} unchanged if no expansion is required.
   */
  public static Core.From expandFrom(
      TypeSystem typeSystem, Environment env, Core.From from) {
    final Generators.Cache cache = new Generators.Cache(typeSystem, env);
    final Expander expander = new Expander(cache, ImmutableList.of());

    // First, deduce generators.
    expandSteps(from.steps, expander);

    // Second, check that we found a generator for each pattern (infinite
    // extent).
    final StepVarSet stepVars = StepVarSet.create(from, typeSystem);
    for (Core.FromStep step : from.steps) {
      if (step.op == Op.SCAN) {
        final Core.Scan scan = (Core.Scan) step;
        if (scan.exp.isExtent()) {
          final List<Core.NamedPat> namedPats = scan.pat.expand();
          for (Core.NamedPat namedPat : namedPats) {
            if (!stepVars.usedPats.contains(namedPat)) {
              // Ignore patterns that are not used. For example, "y" is unused
              // in "exists x, y where x elem [1,2,3]".
              continue;
            }
            final Generator generator = cache.bestGenerator(namedPat);
            if (generator == null
                || generator.cardinality == Generator.Cardinality.INFINITE) {
              throw new IllegalArgumentException(
                  format("pattern '%s' is not grounded", namedPat.name));
            }
          }
        }
      }
    }

    // Third, substitute generators.
    Core.From from2 = expandFrom2(cache, env, stepVars);
    return from2.equals(from) ? from : from2;
  }

  /** Processing state for a pattern during generator expansion. */
  enum PatternState {
    /** Pattern is currently being processed; used to detect cycles. */
    IN_PROGRESS,
    /** Pattern has been fully processed and has a scan. */
    DONE
  }

  private static Core.From expandFrom2(
      Generators.Cache cache, Environment env, StepVarSet stepVarSet) {
    // Build the set of patterns that are assigned in some scan.
    final TypeSystem typeSystem = cache.typeSystem;
    // Tracks processing state for each pattern.
    final Map<Core.NamedPat, PatternState> patternState = new HashMap<>();
    final Set<Core.NamedPat> allPats = new HashSet<>();
    // All patterns defined in any scan step (extent or not). Used to
    // distinguish local scan patterns from outer-scope variables.
    final Set<Core.NamedPat> allScanPats = new HashSet<>();
    final Map<Core.NamedPat, Generator> generatorMap = new HashMap<>();
    stepVarSet.stepVars.forEach(
        (step, vars) -> {
          if (step.op == Op.SCAN) {
            final Core.Scan scan = (Core.Scan) step;
            final List<Core.NamedPat> namedPats = scan.pat.expand();
            allScanPats.addAll(namedPats);
            if (scan.exp.isExtent()) {
              for (Core.NamedPat namedPat : namedPats) {
                if (!stepVarSet.usedPats.contains(namedPat)) {
                  // Ignore patterns that are not used. For example, "y" is
                  // unused in "exists x, y where x elem [1,2,3]".
                  continue;
                }
                final Generator generator = cache.bestGenerator(namedPat);
                if (generator != null) {
                  generatorMap.put(namedPat, generator);
                }
              }
              allPats.addAll(namedPats);
            }
          }
        });

    // Track original patterns before adding shared ones for joining.
    // We'll need to project away shared patterns at the end.
    final Set<Core.NamedPat> originalPats = new HashSet<>(allPats);

    // Find shared patterns across generators. If a pattern appears in
    // multiple generators, add it to allPats so it can be used for joining.
    // For example, for "exists v0 where parent(v0, x) andalso parent(v0, y)",
    // both the generator for x and the generator for y have v0 in their
    // patterns. We need v0 in allPats so the second generator can join on it.
    // Use a Set to deduplicate generators (the same generator may be indexed
    // under multiple patterns in generatorMap).
    final Map<Core.NamedPat, Integer> patternCounts = new HashMap<>();
    final Set<Generator> uniqueGenerators =
        new HashSet<>(generatorMap.values());
    for (Generator generator : uniqueGenerators) {
      for (Core.NamedPat p : generator.pat.expand()) {
        patternCounts.merge(p, 1, Integer::sum);
      }
    }
    final Set<Core.NamedPat> sharedPats = new HashSet<>();
    patternCounts.forEach(
        (p, count) -> {
          if (count > 1 && allPats.add(p)) {
            sharedPats.add(p);
          }
        });

    final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
    final Map<Core.NamedPat, Core.Exp> substitution = new HashMap<>();
    stepVarSet.stepVars.forEach(
        (step, freePats) -> {
          // Pull forward any generators.
          for (Core.NamedPat freePat : freePats) {
            addGeneratorScan(
                typeSystem,
                patternState,
                freePat,
                generatorMap,
                allPats,
                allScanPats,
                fromBuilder);
          }

          if (step instanceof Core.Scan) {
            final Core.Scan scan = (Core.Scan) step;
            if (scan.exp.isExtent()) {
              for (Core.NamedPat p : scan.pat.expand()) {
                // Skip scan variables that are not used, e.g. "y" in
                // "exists x, y where x elem [1, 2]".
                if (stepVarSet.usedPats.contains(p)) {
                  addGeneratorScan(
                      typeSystem,
                      patternState,
                      p,
                      generatorMap,
                      allPats,
                      allScanPats,
                      fromBuilder);
                }
              }
              if (scan.env.atom
                  && fromBuilder.stepEnv().bindings.size() == 1
                  && !fromBuilder.stepEnv().atom) {
                final Binding binding =
                    Iterables.getOnlyElement(fromBuilder.stepEnv().bindings);
                fromBuilder.yield_(core.id(binding.id));
              }
              return;
            }
            // The pattern(s) defined in the scan are now available to
            // subsequent steps.
            for (Core.NamedPat p : scan.pat.expand()) {
              patternState.put(p, PatternState.DONE);
            }
          }

          // The step is not a scan over an extent. Add it now.
          step = Replacer.substitute(typeSystem, env, substitution, step);

          // For "where" steps, simplify the condition using generators that
          // can reliably simplify predicates they satisfy.
          // This removes predicates that are satisfied by the generator.
          if (step instanceof Core.Where) {
            final AtomicReference<Core.Exp> conditionRef =
                new AtomicReference<>(((Core.Where) step).exp);
            generatorMap.forEach(
                (p, generator) ->
                    conditionRef.set(
                        generator.simplify(typeSystem, p, conditionRef.get())));
            // Note: satisfiedConstraints simplification is intentionally
            // not applied here. The generator-based simplification above
            // handles the cases that matter.
            fromBuilder.where(conditionRef.get());
            return;
          }

          fromBuilder.addAll(ImmutableList.of(step));
        });

    // If we added shared patterns for joining, project them away at the end.
    // The final result should only contain the original query patterns.
    if (!sharedPats.isEmpty()) {
      // Check if any shared patterns are in the current step environment
      final List<Core.NamedPat> toProject = new ArrayList<>();
      for (Binding binding : fromBuilder.stepEnv().bindings) {
        if (originalPats.contains(binding.id)) {
          toProject.add(binding.id);
        }
      }
      if (toProject.size() < fromBuilder.stepEnv().bindings.size()) {
        // Some shared patterns need to be projected away.
        // We also need distinct because projecting away variables that were
        // used for joining (like y in "exists y where edge(x,y) andalso
        // edge(y,z)") can cause duplicates. For example, (1, 3) would appear
        // twice if there are two different y values connecting x=1 to z=3.
        fromBuilder.yield_(core.recordOrAtom(typeSystem, toProject));
        fromBuilder.distinct();
      }
    }

    return fromBuilder.build();
  }

  /**
   * Adds a scan that generates {@code freePat}.
   *
   * <p>Does nothing if {@code freePat} is not an unbounded variable, or if it
   * already has a scan.
   *
   * <p>If the generator expression depends on other unbounded variables, adds
   * those variables' generators first.
   */
  private static void addGeneratorScan(
      TypeSystem typeSystem,
      Map<Core.NamedPat, PatternState> patternState,
      Core.NamedPat freePat,
      Map<Core.NamedPat, Generator> generatorMap,
      Set<Core.NamedPat> allPats,
      Set<Core.NamedPat> allScanPats,
      FromBuilder fromBuilder) {
    if (patternState.containsKey(freePat) || !allPats.contains(freePat)) {
      return;
    }

    // Find a generator, and find which patterns it depends on.
    final Generator generator = generatorMap.get(freePat);
    if (generator == null) {
      return;
    }

    // Mark this pattern as "in progress" to prevent infinite recursion.
    // If a dependency cycles back to this pattern, we'll detect it via
    // patternState.containsKey() and stop recursing.
    patternState.put(freePat, PatternState.IN_PROGRESS);

    // Make sure all dependencies have a scan.
    for (Core.NamedPat p : generator.freePats) {
      addGeneratorScan(
          typeSystem,
          patternState,
          p,
          generatorMap,
          allPats,
          allScanPats,
          fromBuilder);
    }

    // Check that all dependencies are now satisfied.
    // If a dependency is from a scan in this from expression that hasn't been
    // processed yet, we cannot add this generator now - it will be added later
    // when the dependency is DONE (e.g., when processing the WHERE clause).
    // Free variables from outer scopes (e.g., a variable defined in an
    // enclosing let) are already bound and don't need to be DONE.
    for (Core.NamedPat p : generator.freePats) {
      if (allScanPats.contains(p) && patternState.get(p) != PatternState.DONE) {
        patternState.remove(freePat);
        return;
      }
    }

    // The patterns we need (requiredPats) are those provided by the generator,
    // which are used in later steps (allPats),
    // and are not already DONE.
    final List<Core.NamedPat> expandedPats = generator.pat.expand();
    final List<Core.NamedPat> requiredPats =
        new ArrayList<>(expandedPats.size());
    for (Core.NamedPat p : expandedPats) {
      if (allPats.contains(p) && patternState.get(p) != PatternState.DONE) {
        requiredPats.add(p);
      }
    }
    // Now all dependencies are DONE, add a scan for the generator.
    if (expandedPats.equals(requiredPats)) {
      if (generator.unique) {
        // Add "join (x, y, z) in collection".
        fromBuilder.scan(generator.pat, generator.exp);
      } else {
        // Generator may produce duplicates (e.g., union of overlapping ranges).
        // Wrap with distinct: "from pat in collection group pat"
        final FromBuilder fromBuilder2 = core.fromBuilder(typeSystem);
        fromBuilder2.scan(generator.pat, generator.exp);
        fromBuilder2.distinct();
        fromBuilder.scan(generator.pat, fromBuilder2.build());
      }
    } else {
      // Some patterns are already bound. Create a filtered projection.
      // For example, for "(y, z) in edges" where y is already bound:
      // Add "join z in (from (y', z) in edges where y' = y yield z)".

      // Identify patterns that are already bound.
      final Map<Core.NamedPat, Core.IdPat> renameMap = new HashMap<>();
      final List<Core.Exp> joinConditions = new ArrayList<>();
      for (Core.NamedPat p : expandedPats) {
        if (patternState.get(p) == PatternState.DONE
            && !requiredPats.contains(p)) {
          // Create a fresh pattern variable for the subquery's binding.
          final Core.IdPat freshPat = core.idPat(p.type, p.name + "'", 0);
          renameMap.put(p, freshPat);
          // Add condition: freshPat = p (subquery's value equals outer value)
          joinConditions.add(
              core.equal(typeSystem, core.id(freshPat), core.id(p)));
        }
      }

      // Build subquery: from (y', z) in collection where y' = y yield z
      final FromBuilder fromBuilder2 = core.fromBuilder(typeSystem);
      final Core.Pat scanPat =
          renamePatterns(typeSystem, generator.pat, renameMap);
      fromBuilder2.scan(
          scanPat, generator.exp, core.andAlso(typeSystem, joinConditions));

      // Yield only the required patterns.
      fromBuilder2.yield_(core.recordOrAtom(typeSystem, requiredPats));

      // Add distinct if:
      // 1. The generator may produce duplicates (!generator.unique), or
      // 2. We're projecting away inner variables (not outer-bound).
      //
      // If patterns are projected away because they're DONE (already bound
      // from outer scans), we don't need distinct - the outer context provides
      // uniqueness via the join condition.
      // If patterns are projected away because they're not in `allPats` (inner
      // variables like y in "exists y"), we need distinct to avoid duplicates.
      boolean needsDistinct = !generator.unique;
      if (!needsDistinct) {
        for (Core.NamedPat p : expandedPats) {
          if (!requiredPats.contains(p)
              && patternState.get(p) != PatternState.DONE) {
            needsDistinct = true;
            break;
          }
        }
      }
      if (needsDistinct) {
        fromBuilder2.distinct();
      }

      // Add scan from the filtered subquery.
      final Core.Pat scanPat2 = core.recordOrAtomPat(typeSystem, requiredPats);
      final Core.From subquery = fromBuilder2.build();
      fromBuilder.scan(scanPat2, subquery);
    }
    for (Core.NamedPat p : requiredPats) {
      patternState.put(p, PatternState.DONE);
    }
  }

  /**
   * Renames patterns in a pattern tree according to the given map.
   *
   * <p>For example, if the map is {y -> y$}, then the pattern (x, y, z) becomes
   * (x, y$, z).
   */
  private static Core.Pat renamePatterns(
      TypeSystem typeSystem,
      Core.Pat pat,
      Map<Core.NamedPat, Core.IdPat> renameMap) {
    if (pat instanceof Core.IdPat) {
      final Core.IdPat replacement = renameMap.get(pat);
      return replacement != null ? replacement : pat;
    } else if (pat instanceof Core.TuplePat) {
      final Core.TuplePat tuplePat = (Core.TuplePat) pat;
      final List<Core.Pat> args = new ArrayList<>(tuplePat.args.size());
      boolean changed = false;
      for (Core.Pat arg : tuplePat.args) {
        final Core.Pat newArg = renamePatterns(typeSystem, arg, renameMap);
        args.add(newArg);
        if (newArg != arg) {
          changed = true;
        }
      }
      return changed ? tuplePat.copy(typeSystem, args) : pat;
    } else if (pat instanceof Core.RecordPat) {
      final Core.RecordPat recordPat = (Core.RecordPat) pat;
      final List<Core.Pat> args = new ArrayList<>(recordPat.args.size());
      boolean changed = false;
      for (Core.Pat arg : recordPat.args) {
        final Core.Pat newArg = renamePatterns(typeSystem, arg, renameMap);
        args.add(newArg);
        if (newArg != arg) {
          changed = true;
        }
      }
      return changed ? core.recordPat(recordPat.type(), args) : pat;
    } else {
      return pat;
    }
  }

  static void expandSteps(List<Core.FromStep> steps, Expander expander) {
    if (steps.isEmpty()) {
      return;
    }
    final Generators.Cache cache = expander.cache;
    final Core.FromStep step0 = steps.get(0);
    switch (step0.op) {
      case SCAN:
        final Core.Scan scan = (Core.Scan) step0;
        // The first attempt at a generator is the extent of the type.
        // Usually finite, but finite for types like 'bool option'.
        Generators.maybeExtent(cache, scan.pat, scan.exp);
        break;

      case WHERE:
        final Core.Where where = (Core.Where) step0;
        final List<Core.Exp> conditions = core.decomposeAnd(where.exp);
        for (Core.Exp condition : conditions) {
          expander = expander.plusConstraint(condition);
          expander.improveGenerators(cache.generators);
        }
        break;
    }
    expandSteps(skip(steps), expander);
  }

  /**
   * Tries to improve the existing generators.
   *
   * <p>This means replacing each generator with one of lower cardinality - an
   * infinite generator with a finite generator, or a finite generator with one
   * that is a single value or is empty.
   */
  private void improveGenerators(
      Multimap<Core.NamedPat, Generator> generators) {
    // Create a snapshot of the generators map, to avoid concurrent
    // modification.
    final PairList<Core.NamedPat, Generator> infiniteGenerators = PairList.of();
    generators.forEach(
        (pat, generator) -> {
          if (generator.cardinality == Generator.Cardinality.INFINITE) {
            infiniteGenerators.add(pat, generator);
          }
        });

    infiniteGenerators.forEach(
        (pat, generator) -> {
          final boolean ordered = generator.exp.type instanceof ListType;
          if (maybeGenerator(cache, pat, ordered, constraints)) {
            Generator g = getLast(cache.generators.get(pat));
            g.pat.expand().forEach(p2 -> generators.put(p2, g));
          }
        });
  }

  private Expander plusConstraint(Core.Exp constraint) {
    return withConstraints(append(this.constraints, constraint));
  }

  private Expander withConstraints(List<Core.Exp> constraints) {
    return new Expander(cache, constraints);
  }

  /**
   * Finds free variables in an expression.
   *
   * <p>It works similarly to {@link FreeFinder}.
   */
  static class StepAnalyzer extends EnvVisitor {
    final List<Core.NamedPat> freePats;
    final BiConsumer<Core.FromStep, Set<Core.NamedPat>> consumer;

    private StepAnalyzer(
        TypeSystem typeSystem,
        Environment env,
        Deque<FromContext> fromStack,
        List<Core.NamedPat> freePats,
        BiConsumer<Core.FromStep, Set<Core.NamedPat>> consumer) {
      super(typeSystem, env, fromStack);
      this.freePats = freePats;
      this.consumer = consumer;
    }

    /**
     * Given a query, computes a list of steps and the free variables in each
     * step.
     */
    private static PairList<Core.FromStep, Set<Core.NamedPat>> getEntries(
        Core.From from, TypeSystem typeSystem) {
      final PairList<Core.FromStep, Set<Core.NamedPat>> list = PairList.of();
      final Environment env = Environments.empty();
      from.accept(
          new StepAnalyzer(
              typeSystem,
              env,
              new ArrayDeque<>(),
              new ArrayList<>(),
              list::add));
      return list;
    }

    @Override
    protected EnvVisitor push(Environment env) {
      return new StepAnalyzer(typeSystem, env, fromStack, freePats, consumer);
    }

    @Override
    protected void visit(Core.Id id) {
      freePats.add(id.idPat);
    }

    @Override
    public void visitStep(Core.FromStep step, Core.StepEnv stepEnv) {
      if (!fromStack.isEmpty()) {
        super.visitStep(step, stepEnv);
        return;
      }
      freePats.clear();
      super.visitStep(step, stepEnv);
      final ImmutableSet.Builder<Core.NamedPat> namedPats =
          ImmutableSet.builder();
      forEachInIntersection(
          freePats,
          transformEager(stepEnv.bindings, b -> b.id),
          namedPats::add);
      consumer.accept(step, namedPats.build());
    }
  }

  /** Analysis of the variables used in each step of a query. */
  static class StepVarSet {
    private final PairList<Core.FromStep, Set<Core.NamedPat>> stepVars;
    private final Set<Core.NamedPat> usedPats;

    StepVarSet(PairList<Core.FromStep, Set<Core.NamedPat>> stepVars) {
      this.stepVars = stepVars.immutable();
      this.usedPats =
          stepVars.rightList().stream()
              .flatMap(Collection::stream)
              .collect(ImmutableSet.toImmutableSet());
    }

    /**
     * Given a query, returns a list of the steps and the variables from the
     * step environment used by each step.
     */
    static StepVarSet create(Core.From from, TypeSystem typeSystem) {
      return new StepVarSet(StepAnalyzer.getEntries(from, typeSystem));
    }
  }
}

// End Expander.java
