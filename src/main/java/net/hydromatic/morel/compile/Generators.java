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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getLast;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.compile.FreeFinder.freePats;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.CoreBuilder;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RangeExtent;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Implementations of {@link Generator}, and supporting methods. */
class Generators {
  private Generators() {}

  static boolean maybeGenerator(
      Cache cache, Core.Pat pat, boolean ordered, List<Core.Exp> constraints) {
    if (maybeElem(cache, pat, ordered, constraints)) {
      return true;
    }

    if (maybePoint(cache, pat, ordered, constraints)) {
      return true;
    }

    if (maybeRange(cache, pat, ordered, constraints)) {
      return true;
    }

    if (maybeStringPrefix(cache, pat, ordered, constraints)) {
      return true;
    }

    if (maybeExists(cache, pat, constraints)) {
      return true;
    }

    if (maybeFunction(cache, pat, ordered, constraints)) {
      return true;
    }

    return maybeUnion(cache, pat, ordered, constraints);
  }

  /**
   * For each predicate "exists ... where pat ...", adds a generator for "pat".
   *
   * <p>Because we're in core, {@code exists} has been translated to a call to
   * {@link BuiltIn#RELATIONAL_NON_EMPTY}. The above query will look like
   * "Relational.nonEmpty (from ... where pat ...)".
   *
   * <p>Pattern can occur in other places than a {@code where} clause, but it
   * must be in a location that is <b>monotonic</b>. That is, where adding a
   * value to the generator can never cause the query to emit fewer rows.
   */
  private static boolean maybeExists(
      Cache cache, Core.Pat pat, List<Core.Exp> constraints) {
    constraint_loop:
    for (int j = 0; j < constraints.size(); j++) {
      final Core.Exp constraint = constraints.get(j);
      if (constraint.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)) {
        final Core.Apply apply = (Core.Apply) constraint;
        if (apply.arg instanceof Core.From) {
          final Core.From from = (Core.From) apply.arg;

          // Create a copy of constraints with this constraint removed.
          // When we encounter a "where" step, we will add more constraints.
          final List<Core.Exp> constraints2 = new ArrayList<>(constraints);
          //noinspection SuspiciousListRemoveInLoop
          constraints2.remove(j);

          // Collect inner scan patterns - these are available for generators
          // within the exists scope
          final List<Core.Scan> innerScans = new ArrayList<>();
          for (Core.FromStep step : from.steps) {
            if (step.op == Op.SCAN) {
              innerScans.add((Core.Scan) step);
            }
          }

          for (Core.FromStep step : from.steps) {
            switch (step.op) {
              case SCAN:
                break;
              case WHERE:
                // Decompose "andalso" to allow each conjunct to be processed
                // separately. E.g., "edge(x,y) andalso x = y" becomes
                // ["edge(x,y)", "x = y"], allowing maybeFunction to process
                // the edge function call.
                core.flattenAnd(((Core.Where) step).exp, constraints2::add);
                // First try to create a generator without inner dependencies
                if (maybeGenerator(cache, pat, false, constraints2)) {
                  // Check if the created generator depends on inner scans
                  final Generator gen =
                      cache.bestGenerator((Core.NamedPat) pat);
                  if (gen != null) {
                    // Check if any freePat is from inner scans OR if the
                    // generator's pattern contains inner scan variables
                    boolean dependsOnInnerScan = false;

                    // Check free variables in the generator expression
                    for (Core.NamedPat freePat : gen.freePats) {
                      for (Core.Scan innerScan : innerScans) {
                        if (innerScan.pat.expand().contains(freePat)) {
                          dependsOnInnerScan = true;
                          break;
                        }
                      }
                    }

                    // Also check if the generator's pattern contains inner
                    // scan variables (e.g., for elem patterns like
                    // "(v0, x) elem list" where v0 is from the exists clause)
                    if (!dependsOnInnerScan) {
                      for (Core.NamedPat patVar : gen.pat.expand()) {
                        for (Core.Scan innerScan : innerScans) {
                          if (innerScan.pat.expand().contains(patVar)) {
                            dependsOnInnerScan = true;
                            break;
                          }
                        }
                      }
                    }

                    if (dependsOnInnerScan) {
                      // Replace the generator with one that includes inner
                      // scans
                      createJoinedGenerator(cache, pat, gen, innerScans);
                    }
                  }
                  return true;
                }
                break;
              default:
                continue constraint_loop;
            }
          }
        }
      }
    }
    return false;
  }

  /**
   * Creates a generator that joins inner scans with a dependent generator.
   *
   * <p>For example, if we have:
   *
   * <pre>{@code
   * from p where exists (from s in list where String.isPrefix p s)
   * }</pre>
   *
   * <p>And the generator for {@code p} is {@code List.tabulate(String.size s +
   * 1, ...)} which depends on {@code s}, this method creates:
   *
   * <pre>{@code
   * from s in list, p in List.tabulate(String.size s + 1, ...)
   * }</pre>
   */
  private static void createJoinedGenerator(
      Cache cache,
      Core.Pat pat,
      Generator dependentGen,
      List<Core.Scan> innerScans) {
    final TypeSystem typeSystem = cache.typeSystem;
    final FromBuilder fromBuilder = core.fromBuilder(typeSystem);

    // Collect which inner scan variables are already covered by the
    // dependent generator's pattern
    final Set<Core.NamedPat> coveredByGenerator =
        new HashSet<>(dependentGen.pat.expand());

    // Add inner scans first (only if not already covered by the generator)
    for (Core.Scan scan : innerScans) {
      boolean scanCovered = true;
      for (Core.NamedPat scanPat : scan.pat.expand()) {
        if (!coveredByGenerator.contains(scanPat)) {
          scanCovered = false;
          break;
        }
      }
      if (!scanCovered) {
        fromBuilder.scan(scan.pat, scan.exp);
      }
    }

    // Add the dependent generator as a join
    fromBuilder.scan(dependentGen.pat, dependentGen.exp);

    // Yield the FULL scan pattern (including inner scan variables).
    // This allows the Expander to join subsequent generators on shared
    // inner variables. For example, for:
    //   exists v0 where parent (v0, x) andalso parent (v0, y)
    // The first generator yields (v0, x), and the second can join on v0.
    // IMPORTANT: Use recordOrAtom (not tuple) so that distinct() sees
    // individual bindings for each component, not a single tuple binding.
    final List<Core.NamedPat> yieldPats = dependentGen.pat.expand();
    fromBuilder.yield_(core.recordOrAtom(typeSystem, yieldPats));
    fromBuilder.distinct();

    final Core.From joinedFrom = fromBuilder.build();
    final Set<Core.NamedPat> freePats2 = freePats(typeSystem, joinedFrom);

    // Remove the old generator and add the new joined one.
    // Use the FULL pattern so inner scan variables are included in the
    // generator's pattern. The Expander will add these to 'done' and can
    // create join conditions for subsequent generators.
    // IMPORTANT: Create a record pattern to match the record we're yielding.
    final Core.Pat joinedPat = core.recordOrAtomPat(typeSystem, yieldPats);
    cache.remove((Core.NamedPat) pat);
    cache.add(new ExistsJoinGenerator(joinedPat, joinedFrom, freePats2));
  }

  /** Generator created from an exists pattern that joins inner scans. */
  static class ExistsJoinGenerator extends Generator {
    ExistsJoinGenerator(
        Core.Pat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats) {
      super(exp, freePats, pat, Cardinality.FINITE, true);
    }

    @Override
    Core.Exp simplify(TypeSystem typeSystem, Core.Pat pat, Core.Exp exp) {
      // The exists constraint is satisfied by this generator
      return exp;
    }
  }

  /**
   * Finds the index of goalPat within a tuple expression.
   *
   * <p>For example, if fnArg is (x, y) and goalPat is x, returns 0. If fnArg is
   * (x, y) and goalPat is y, returns 1. Returns -1 if fnArg is not a tuple or
   * goalPat is not found.
   */
  private static int findTupleComponent(Core.Exp fnArg, Core.Pat goalPat) {
    if (fnArg.op != Op.TUPLE) {
      return -1;
    }
    final Core.Tuple tuple = (Core.Tuple) fnArg;
    for (int i = 0; i < tuple.args.size(); i++) {
      Core.Exp arg = tuple.args.get(i);
      if (arg.op == Op.ID) {
        Core.Id id = (Core.Id) arg;
        if (goalPat instanceof Core.IdPat
            && id.idPat.name.equals(((Core.IdPat) goalPat).name)) {
          return i;
        }
      }
    }
    return -1;
  }

  /**
   * Creates a tuple pattern from a tuple expression.
   *
   * <p>For example, if fnArg is (x, y) where x and y are Ids referencing
   * IdPats, creates a TuplePat containing those IdPats.
   *
   * <p>Returns null if fnArg is not a tuple or contains non-ID elements (e.g.,
   * expressions like {@code n - 1}).
   */
  private static Core.@Nullable Pat createTuplePatFromArg(
      TypeSystem ts, Core.Exp fnArg) {
    if (fnArg.op != Op.TUPLE) {
      return null;
    }
    final Core.Tuple tuple = (Core.Tuple) fnArg;
    final List<Core.Pat> pats = new ArrayList<>();
    for (Core.Exp arg : tuple.args) {
      if (arg.op == Op.ID) {
        pats.add(((Core.Id) arg).idPat);
      } else {
        return null;
      }
    }
    return core.tuplePat(ts, pats);
  }

  /**
   * Attempts to invert a user-defined recursive boolean function call into a
   * generator.
   *
   * <p>Handles functions following the bounded recursive pattern:
   *
   * <pre>{@code
   * fun path (x, y, n) =
   *   n > 0 andalso
   *   (edge (x, y) orelse
   *    exists z where edge (x, z) andalso path (z, y, n - 1))
   * }</pre>
   *
   * <p>A function is invertible if:
   *
   * <ul>
   *   <li>The constraint is a function call {@code f(arg)} where {@code f} is a
   *       user-defined function (not a built-in)
   *   <li>The function body has the form {@code n > 0 andalso (base orelse
   *       recursive)}
   *   <li>The bound parameter {@code n} has a guard {@code n > 0} and is
   *       decremented in the recursive call
   *   <li>The bound parameter is supplied as a constant at the call site
   *   <li>The base case is directly invertible (e.g., {@code {x, y} elem
   *       edges})
   *   <li>The recursive case contains a call to the same function
   * </ul>
   *
   * <p>When these conditions are met, generates a bounded iteration that
   * computes paths up to the specified depth, returning the union of all
   * intermediate results.
   *
   * @param cache The generator cache (requires environment for function lookup)
   * @param goalPat The pattern to generate values for
   * @param ordered Whether to generate a list (true) or bag (false)
   * @param constraints Boolean expressions that must be satisfied
   * @return true if a generator was created, false otherwise
   */
  static boolean maybeFunction(
      Cache cache,
      Core.Pat goalPat,
      boolean ordered,
      List<Core.Exp> constraints) {
    for (Core.Exp constraint : constraints) {
      // Step 0: Handle CASE expression (from tuple pattern matching)
      // This occurs when a tuple-parameter function like f(x, y) is compiled
      // to: case (arg1, arg2) of (x, y) => body
      if (constraint.op == Op.CASE) {
        final Core.Case caseExp = (Core.Case) constraint;
        if (caseExp.matchList.size() == 1) {
          final Core.Match match = caseExp.matchList.get(0);
          final Core.Exp inlinedBody =
              substituteArgs(
                  cache.typeSystem,
                  cache.env,
                  match.pat,
                  caseExp.exp,
                  match.exp);
          // Decompose "andalso" into individual conjuncts for range detection.
          if (maybeGenerator(
              cache, goalPat, ordered, core.decomposeAnd(inlinedBody))) {
            return true;
          }
        }
        continue;
      }

      // Step 1: Find function calls (Apply where fn is an Id, not a built-in)
      if (constraint.op != Op.APPLY) {
        continue;
      }
      final Core.Apply apply = (Core.Apply) constraint;

      // Step 1a: Handle lambda application (fn ... => body) arg
      // This occurs when a function has been inlined to a lambda.
      // Note: FN_LITERAL is for built-in functions (Core.Literal), not Core.Fn.
      if (apply.fn.op == Op.FN) {
        final Core.Fn fn = (Core.Fn) apply.fn;
        final Core.Exp inlinedBody =
            inlineFunctionBody(cache.typeSystem, cache.env, fn, apply.arg);
        // Decompose "andalso" into individual conjuncts for range detection.
        if (maybeGenerator(
            cache, goalPat, ordered, core.decomposeAnd(inlinedBody))) {
          return true;
        }
        continue;
      }

      if (apply.fn.op != Op.ID) {
        continue;
      }
      final Core.Id fnId = (Core.Id) apply.fn;
      final String fnName = fnId.idPat.name;

      // Step 2: Look up the function definition in the environment.
      // Use getTop(name) instead of getOpt(idPat) because the idPat in the
      // call expression may have a different 'i' than the one in the
      // definition.
      final Binding binding = cache.env.getTop(fnName);
      if (binding == null || binding.exp == null || binding.exp.op != Op.FN) {
        continue;
      }
      final Core.Fn fn = (Core.Fn) binding.exp;

      // Step 3a: Try to analyze as a bounded recursive pattern (with depth
      // limit)
      final @Nullable BoundedRecursivePattern boundedPattern =
          analyzeBoundedRecursive(cache, fn, apply.arg, fnName);
      if (boundedPattern != null) {
        // Step 4a: Generate bounded iterate expression
        final Core.Exp iterateExp =
            generateBoundedIterate(cache, boundedPattern, goalPat, ordered);
        if (iterateExp != null) {
          // Step 5a: Register the generator
          final Set<Core.NamedPat> freePats =
              freePats(cache.typeSystem, iterateExp);
          cache.add(
              new BoundedIterateGenerator(
                  (Core.NamedPat) goalPat,
                  iterateExp,
                  freePats,
                  boundedPattern.depthBound));
          return true;
        }
      }

      // Step 3b: Try to analyze as unbounded transitive closure pattern.
      // Handle two cases:
      // 1. Full application: path p where p : (int * int)
      // 2. Tuple application: path (x, y) where x, y : int
      final Core.Exp fnArg = apply.arg;
      final boolean isFullApplication =
          fnArg.op == Op.ID && fnArg.type.equals(goalPat.type);
      final int tupleComponentIndex = findTupleComponent(fnArg, goalPat);
      final boolean isTupleApplication = tupleComponentIndex >= 0;
      if (isFullApplication) {
        if (tryTransitiveClosure(cache, fn, apply, goalPat, ordered)) {
          return true;
        }
      }
      if (isTupleApplication) {
        if (tryTupleTransitiveClosure(
            cache, fn, apply, fnArg, goalPat, ordered)) {
          return true;
        }
      }

      // Step 3c: Try simple function inversion.
      // Inline the function body by substituting actual args for formal params,
      // then recursively try to find a generator for the substituted body.
      // For recursive functions, inline only the non-recursive branches of
      // any top-level orelse to avoid infinite expansion.
      final Core.Exp inlinedBody =
          inlineFunctionBody(cache.typeSystem, cache.env, fn, apply.arg);
      final Core.Exp safeBody =
          containsSelfCall(fn.exp, fnName)
              ? removeRecursiveBranches(cache.typeSystem, inlinedBody, fnName)
              : inlinedBody;
      if (safeBody != null) {
        // Decompose "andalso" into individual conjuncts for range detection.
        if (maybeGenerator(
            cache, goalPat, ordered, core.decomposeAnd(safeBody))) {
          return true;
        }
      }
    }
    return false;
  }

  /** Tries to create a transitive closure generator for a full application. */
  private static boolean tryTransitiveClosure(
      Cache cache,
      Core.Fn fn,
      Core.Apply apply,
      Core.Pat goalPat,
      boolean ordered) {
    final String fnName = ((Core.Id) apply.fn).idPat.name;
    final @Nullable TransitiveClosurePattern tcPattern =
        analyzeTransitiveClosure(cache, fn, apply.arg, fnName);
    if (tcPattern != null) {
      final Core.Exp iterateExp =
          generateTransitiveClosure(cache, tcPattern, goalPat, ordered);
      if (iterateExp != null) {
        final Set<Core.NamedPat> freePats =
            freePats(cache.typeSystem, iterateExp);
        cache.add(
            new TransitiveClosureGenerator(
                goalPat, iterateExp, freePats, apply));
        return true;
      }
    }
    return false;
  }

  /** Tries to create a transitive closure generator for a tuple application. */
  private static boolean tryTupleTransitiveClosure(
      Cache cache,
      Core.Fn fn,
      Core.Apply apply,
      Core.Exp fnArg,
      Core.Pat goalPat,
      boolean ordered) {
    // Check if a TransitiveClosureGenerator already exists for goalPat from
    // the same function call.
    final Generator existingGen = cache.bestGenerator((Core.NamedPat) goalPat);
    if (existingGen instanceof TransitiveClosureGenerator) {
      final TransitiveClosureGenerator tcGen =
          (TransitiveClosureGenerator) existingGen;
      if (tcGen.constraint.equals(apply)) {
        return true;
      }
    }

    final Core.Pat tuplePat = createTuplePatFromArg(cache.typeSystem, fnArg);
    if (tuplePat == null) {
      // Some args are not IDs (e.g., constants). Try with fresh variables.
      if (fnArg.op == Op.TUPLE) {
        return tryTupleTransitiveClosureWithConstants(
            cache, fn, apply, fnArg, goalPat, ordered);
      }
      return false;
    }

    // Check for duplicate variables in the tuple (e.g., odd_path(v0, v0)).
    // If duplicates exist, create fresh variables for the TC and filter
    // for equality afterward.
    if (hasDuplicateVars(tuplePat)) {
      return tryTupleTransitiveClosureWithDuplicates(
          cache, fn, apply, fnArg, goalPat, ordered);
    }

    final String fnName = ((Core.Id) apply.fn).idPat.name;
    final @Nullable TransitiveClosurePattern tcPattern =
        analyzeTransitiveClosure(cache, fn, apply.arg, fnName);
    if (tcPattern != null) {
      final Core.Exp iterateExp =
          generateTransitiveClosure(cache, tcPattern, tuplePat, ordered);
      if (iterateExp != null) {
        final Set<Core.NamedPat> freePats =
            freePats(cache.typeSystem, iterateExp);
        cache.add(
            new TransitiveClosureGenerator(
                tuplePat, iterateExp, freePats, apply));
        return true;
      }
    }
    return false;
  }

  /** Returns whether a pattern has duplicate named variables. */
  private static boolean hasDuplicateVars(Core.Pat pat) {
    final List<? extends Core.NamedPat> expanded = pat.expand();
    final Set<String> names = new HashSet<>();
    for (Core.NamedPat np : expanded) {
      if (!names.add(np.name)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Handles transitive closure when the call has duplicate variables (e.g.,
   * {@code odd_path(v0, v0)}).
   *
   * <p>Creates fresh variables for the TC generator, then wraps the result to
   * filter for equality among the duplicate positions.
   */
  private static boolean tryTupleTransitiveClosureWithDuplicates(
      Cache cache,
      Core.Fn fn,
      Core.Apply apply,
      Core.Exp fnArg,
      Core.Pat goalPat,
      boolean ordered) {
    final TypeSystem ts = cache.typeSystem;
    final Core.Tuple tuple = (Core.Tuple) fnArg;

    // Create fresh variables for each position in the tuple
    final List<Core.IdPat> freshPats = new ArrayList<>();
    final List<Core.Exp> freshArgs = new ArrayList<>();
    for (int i = 0; i < tuple.args.size(); i++) {
      final Core.Exp arg = tuple.args.get(i);
      final Core.IdPat freshPat = core.idPat(arg.type, "_tc_" + i, 0);
      freshPats.add(freshPat);
      freshArgs.add(core.id(freshPat));
    }
    final Core.Pat freshTuplePat = core.tuplePat(ts, freshPats);
    final Core.Exp freshCallArgs =
        core.tuple(
            (RecordLikeType) fnArg.type, freshArgs.toArray(new Core.Exp[0]));

    // Analyze the transitive closure using fresh callArgs
    final String fnName = ((Core.Id) apply.fn).idPat.name;
    final @Nullable TransitiveClosurePattern tcPattern =
        analyzeTransitiveClosure(cache, fn, freshCallArgs, fnName);
    if (tcPattern == null) {
      return false;
    }

    // Generate TC expression using the fresh tuplePat
    final Core.Exp iterateExp =
        generateTransitiveClosure(cache, tcPattern, freshTuplePat, ordered);
    if (iterateExp == null) {
      return false;
    }

    // Build a wrapper: from (tc_0, tc_1) in iterateExp
    //   where tc_0 = tc_1 yield tc_0
    // This filters the TC result to only pairs where duplicated positions
    // are equal, then yields the original variable's value.
    final FromBuilder fb = core.fromBuilder(ts);
    fb.scan(freshTuplePat, iterateExp);

    // Add equality constraints for duplicate positions.
    // Build a map from original variable to first fresh variable.
    final Map<String, Core.IdPat> firstFresh = new LinkedHashMap<>();
    for (int i = 0; i < tuple.args.size(); i++) {
      final Core.Exp arg = tuple.args.get(i);
      if (arg.op == Op.ID) {
        final String name = ((Core.Id) arg).idPat.name;
        if (firstFresh.containsKey(name)) {
          // Duplicate: add equality constraint
          fb.where(
              core.equal(
                  ts,
                  core.id(firstFresh.get(name)),
                  core.id(freshPats.get(i))));
        } else {
          firstFresh.put(name, freshPats.get(i));
        }
      }
    }

    // Yield the first fresh variable for each original variable
    if (goalPat instanceof Core.IdPat) {
      // goalPat is a scalar (e.g., v0) - yield just the first fresh var
      final Core.IdPat firstVar = firstFresh.values().iterator().next();
      fb.yield_(core.id(firstVar));
    } else {
      // goalPat is a tuple - yield matching fresh vars
      final List<Core.Exp> yieldArgs = new ArrayList<>();
      for (Core.IdPat fp : firstFresh.values()) {
        yieldArgs.add(core.id(fp));
      }
      fb.yield_(
          core.tuple(
              (RecordLikeType) goalPat.type,
              yieldArgs.toArray(new Core.Exp[0])));
    }
    final Core.Exp wrappedExp = fb.build();

    final Set<Core.NamedPat> freePats = freePats(ts, wrappedExp);
    cache.add(
        new TransitiveClosureGenerator(goalPat, wrappedExp, freePats, apply));
    return true;
  }

  /**
   * Handles transitive closure when some call arguments are constants (e.g.,
   * {@code isAncestor(a, "arwen")}).
   *
   * <p>Creates fresh variables for all positions, runs the full transitive
   * closure, then registers a generator with a tuple pattern containing both
   * variable and literal patterns. The literal patterns act as filters during
   * scan, same as how {@link CollectionGenerator} handles partial patterns.
   */
  private static boolean tryTupleTransitiveClosureWithConstants(
      Cache cache,
      Core.Fn fn,
      Core.Apply apply,
      Core.Exp fnArg,
      Core.Pat goalPat,
      boolean ordered) {
    final TypeSystem ts = cache.typeSystem;
    final Core.Tuple tuple = (Core.Tuple) fnArg;

    // Create fresh variables for each position in the tuple
    final List<Core.IdPat> freshPats = new ArrayList<>();
    final List<Core.Exp> freshArgs = new ArrayList<>();
    for (int i = 0; i < tuple.args.size(); i++) {
      final Core.Exp arg = tuple.args.get(i);
      final Core.IdPat freshPat = core.idPat(arg.type, "_tc_" + i, 0);
      freshPats.add(freshPat);
      freshArgs.add(core.id(freshPat));
    }
    final Core.Pat freshTuplePat = core.tuplePat(ts, freshPats);
    final Core.Exp freshCallArgs =
        core.tuple(
            (RecordLikeType) fnArg.type, freshArgs.toArray(new Core.Exp[0]));

    // Analyze the transitive closure using fresh callArgs
    final String fnName = ((Core.Id) apply.fn).idPat.name;
    final @Nullable TransitiveClosurePattern tcPattern =
        analyzeTransitiveClosure(cache, fn, freshCallArgs, fnName);
    if (tcPattern == null) {
      return false;
    }

    // Generate TC expression using the fresh tuplePat
    final Core.Exp iterateExp =
        generateTransitiveClosure(cache, tcPattern, freshTuplePat, ordered);
    if (iterateExp == null) {
      return false;
    }

    // Build a scan pattern that mirrors the call arguments: variable positions
    // use the goalPat's IdPat (or fresh pats), constant positions use literal
    // patterns. E.g., for isAncestor(a, "arwen") we create pattern (a, "arwen")
    // so the scan destructures tuples and filters by the literal.
    final Core.Pat scanPat = requireNonNull(wholePat(fnArg));

    final Set<Core.NamedPat> freePats = freePats(ts, iterateExp);
    cache.add(
        new TransitiveClosureGenerator(scanPat, iterateExp, freePats, apply));
    return true;
  }

  /**
   * Inlines a function body by substituting actual arguments for formal
   * parameters.
   *
   * <p>For example, if the function is {@code fn (x, y) => {x, y} elem edges}
   * and actual args are {@code (#1 p, #2 p)}, returns {@code {#1 p, #2 p} elem
   * edges}.
   */
  private static Core.Exp inlineFunctionBody(
      TypeSystem ts, Environment env, Core.Fn fn, Core.Exp actualArgs) {
    // Unwrap CASE expression if present (from tuple pattern matching)
    Core.Exp body = fn.exp;
    Core.Pat formalParams = fn.idPat;
    if (body.op == Op.CASE) {
      final Core.Case caseExp = (Core.Case) body;
      if (caseExp.matchList.size() == 1) {
        final Core.Match match = caseExp.matchList.get(0);
        body = match.exp;
        formalParams = match.pat;
      }
    }
    return substituteArgs(ts, env, formalParams, actualArgs, body);
  }

  /**
   * Attempts to recognize a bounded recursive pattern.
   *
   * <p>Expects a structure like:
   *
   * <pre>{@code
   * fun f(x, y, n) =
   *   n > 0 andalso
   *   (baseCase orelse
   *    exists z where stepPredicate andalso f(z, y, n - 1))
   * }</pre>
   *
   * @param cache The generator cache
   * @param fn The function definition
   * @param callArgs The actual arguments at call site
   * @param fnName The function name
   * @return The pattern, or null if not recognized
   */
  private static @Nullable BoundedRecursivePattern analyzeBoundedRecursive(
      Cache cache, Core.Fn fn, Core.Exp callArgs, String fnName) {

    // Step 1: Check for "n > 0 andalso body" structure
    if (!fn.exp.isCallTo(BuiltIn.Z_ANDALSO)) {
      return null;
    }

    // Find the guard "n > 0" and identify the bound parameter
    Core.NamedPat boundParam = null;
    int boundParamIndex = -1;
    final List<Core.Exp> bodyParts = new ArrayList<>();

    for (Core.Exp conjunct : core.decomposeAnd(fn.exp)) {
      if (conjunct.isCallTo(BuiltIn.OP_GT)) {
        final Core.Apply gt = (Core.Apply) conjunct;
        if (gt.arg(0).op == Op.ID && isZeroLiteral(gt.arg(1))) {
          // Found "n > 0" pattern
          boundParam = ((Core.Id) gt.arg(0)).idPat;
          boundParamIndex = findParamIndex(fn.idPat, boundParam);
          continue;
        }
      }
      bodyParts.add(conjunct);
    }

    if (boundParam == null || bodyParts.isEmpty()) {
      return null;
    }

    // Reconstruct the body without the guard
    final Core.Exp remainingBody = core.andAlso(cache.typeSystem, bodyParts);

    // Step 2: Check that body is "baseCase orelse recursiveCase"
    if (!remainingBody.isCallTo(BuiltIn.Z_ORELSE)) {
      return null;
    }
    final List<Core.Exp> disjuncts = core.decomposeOr(remainingBody);
    if (disjuncts.size() != 2) {
      return null;
    }

    final Core.Exp baseCase = disjuncts.get(0);
    final Core.Exp recursiveCase = disjuncts.get(1);

    // Step 3: Check that recursiveCase contains a self-reference
    if (!containsSelfCall(recursiveCase, fnName)) {
      return null;
    }

    // Step 4: Analyze recursive case for
    // "exists z where step andalso f(z, n-1)"
    if (!recursiveCase.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)) {
      return null;
    }
    final Core.Apply nonEmpty = (Core.Apply) recursiveCase;
    if (nonEmpty.arg.op != Op.FROM) {
      return null;
    }
    final Core.From existsFrom = (Core.From) nonEmpty.arg;

    // Extract intermediate var and where clause
    Core.Pat intermediateVar = null;
    Core.Exp whereClause = null;
    for (Core.FromStep step : existsFrom.steps) {
      if (step.op == Op.SCAN) {
        intermediateVar = ((Core.Scan) step).pat;
      } else if (step.op == Op.WHERE) {
        whereClause = ((Core.Where) step).exp;
      }
    }

    if (intermediateVar == null || whereClause == null) {
      return null;
    }

    // Step 5: Decompose where clause into step predicate and recursive call
    Core.Apply recursiveCall = null;
    final List<Core.Exp> stepPredicates = new ArrayList<>();

    for (Core.Exp conj : core.decomposeAnd(whereClause)) {
      if (conj.op == Op.APPLY) {
        final Core.Apply applyConj = (Core.Apply) conj;
        if (applyConj.fn.op == Op.ID
            && ((Core.Id) applyConj.fn).idPat.name.equals(fnName)) {
          recursiveCall = applyConj;
          continue;
        }
      }
      stepPredicates.add(conj);
    }

    if (recursiveCall == null || stepPredicates.isEmpty()) {
      return null;
    }

    // Step 6: Verify recursive call has "n - 1" for bound parameter
    final List<Core.Exp> recursiveArgs = flattenTupleExp(recursiveCall.arg);
    if (boundParamIndex >= recursiveArgs.size()) {
      return null;
    }
    final Core.Exp boundArg = recursiveArgs.get(boundParamIndex);
    if (!isDecrementOf(boundArg, boundParam)) {
      return null;
    }

    // Step 7: Extract constant bound from call site
    final List<Core.Exp> actualArgs = flattenTupleExp(callArgs);
    if (boundParamIndex >= actualArgs.size()) {
      return null;
    }
    final Core.Exp boundValue = actualArgs.get(boundParamIndex);
    if (boundValue.op != Op.INT_LITERAL) {
      return null; // Bound must be a constant
    }
    final int depthBound =
        ((BigDecimal) ((Core.Literal) boundValue).value).intValue();
    if (depthBound <= 0) {
      return null;
    }

    // Step 8: Extract output parameters (all except bound param)
    final List<Core.NamedPat> outputParams = new ArrayList<>();
    final List<Core.Pat> allParams = flattenTuplePat(fn.idPat);
    for (int i = 0; i < allParams.size(); i++) {
      if (i != boundParamIndex && allParams.get(i) instanceof Core.NamedPat) {
        outputParams.add((Core.NamedPat) allParams.get(i));
      }
    }

    // Build the step predicate
    final Core.Exp stepPredicate =
        core.andAlso(cache.typeSystem, stepPredicates);

    return new BoundedRecursivePattern(
        fnName,
        boundParamIndex,
        boundParam,
        outputParams,
        fn.idPat,
        baseCase,
        intermediateVar,
        stepPredicate,
        recursiveCall,
        depthBound,
        callArgs);
  }

  /**
   * Attempts to recognize an unbounded transitive closure pattern.
   *
   * <p>Expects a structure like:
   *
   * <pre>{@code
   * fun path(x, y) =
   *   edge(x, y) orelse
   *   (exists z where path(x, z) andalso edge(z, y))
   * }</pre>
   *
   * <p>Or equivalently:
   *
   * <pre>{@code
   * fun path(x, y) =
   *   edge(x, y) orelse
   *   (exists z where edge(x, z) andalso path(z, y))
   * }</pre>
   *
   * @param cache The generator cache
   * @param fn The function definition
   * @param callArgs The actual arguments at call site
   * @param fnName The function name
   * @return The pattern, or null if not recognized
   */
  private static @Nullable TransitiveClosurePattern analyzeTransitiveClosure(
      Cache cache, Core.Fn fn, Core.Exp callArgs, String fnName) {
    // Unwrap CASE expression from tuple pattern matching
    // fun path (x, y) = ... becomes fn v => case v of (x, y) => body
    // We need to extract the actual pattern (x, y) for proper substitution
    Core.Exp body = fn.exp;
    Core.Pat formalParams = fn.idPat; // Default to lambda param
    if (body.op == Op.CASE) {
      final Core.Case caseExp = (Core.Case) body;
      if (caseExp.matchList.size() == 1) {
        final Core.Match match = caseExp.matchList.get(0);
        body = match.exp;
        formalParams =
            match.pat; // Use the CASE pattern (x, y) not lambda param v
      }
    }

    // Check that body is "baseCase orelse recursiveCase" (no guard)
    if (!body.isCallTo(BuiltIn.Z_ORELSE)) {
      return null;
    }
    final List<Core.Exp> disjuncts = core.decomposeOr(body);
    if (disjuncts.size() != 2) {
      return null;
    }

    final Core.Exp baseCase = disjuncts.get(0);
    final Core.Exp recursiveCase = disjuncts.get(1);

    // Check that recursiveCase contains a self-reference
    if (!containsSelfCall(recursiveCase, fnName)) {
      return null;
    }

    // Analyze recursive case for "exists z where step andalso f(z, y)"
    if (!recursiveCase.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)) {
      return null;
    }
    final Core.Apply nonEmpty = (Core.Apply) recursiveCase;
    if (nonEmpty.arg.op != Op.FROM) {
      return null;
    }
    final Core.From existsFrom = (Core.From) nonEmpty.arg;

    // Extract intermediate var and where clause
    Core.Pat intermediateVar = null;
    Core.Exp whereClause = null;
    for (Core.FromStep step : existsFrom.steps) {
      if (step.op == Op.SCAN) {
        intermediateVar = ((Core.Scan) step).pat;
      } else if (step.op == Op.WHERE) {
        whereClause = ((Core.Where) step).exp;
      }
    }

    if (intermediateVar == null || whereClause == null) {
      return null;
    }

    // Decompose where clause into step predicate and recursive call
    Core.Apply recursiveCall = null;
    final List<Core.Exp> stepPredicates = new ArrayList<>();

    for (Core.Exp conj : core.decomposeAnd(whereClause)) {
      if (conj.op == Op.APPLY) {
        final Core.Apply applyConj = (Core.Apply) conj;
        if (applyConj.fn.op == Op.ID
            && ((Core.Id) applyConj.fn).idPat.name.equals(fnName)) {
          recursiveCall = applyConj;
          continue;
        }
      }
      stepPredicates.add(conj);
    }

    if (recursiveCall == null || stepPredicates.isEmpty()) {
      return null;
    }

    // Build the step predicate
    final Core.Exp stepPredicate =
        core.andAlso(cache.typeSystem, stepPredicates);

    return new TransitiveClosurePattern(
        fnName,
        formalParams,
        baseCase,
        intermediateVar,
        stepPredicate,
        recursiveCall,
        callArgs);
  }

  /**
   * Generates an {@link BuiltIn#RELATIONAL_ITERATE iterate} expression for
   * transitive closure.
   *
   * <p>For {@code from p where path p}, generates:
   *
   * <pre>{@code
   * Relational.iterate edges
   *   (fn (allPaths, newPaths) =>
   *     from (x, z) in edges, (z2, y) in newPaths
   *       where z = z2
   *       yield (x, y))
   * }</pre>
   */
  private static Core.@Nullable Exp generateTransitiveClosure(
      Cache cache,
      TransitiveClosurePattern pattern,
      Core.Pat goalPat,
      boolean ordered) {
    // Substitute actual args into base case and try to invert to get
    // the base generator (e.g., edges)
    final Core.Exp substitutedBase =
        substituteArgs(
            cache.typeSystem,
            cache.env,
            pattern.formalParams,
            pattern.callArgs,
            pattern.baseCase);

    // Try to create a generator for the base case using a fresh cache,
    // so that extent generators from outer scope don't interfere.
    final Cache baseCache = new Cache(cache.typeSystem, cache.env);
    final List<Core.Exp> baseConstraints = ImmutableList.of(substitutedBase);
    if (!maybeGenerator(baseCache, goalPat, ordered, baseConstraints)) {
      return null;
    }
    final Generator baseGenerator =
        requireNonNull(baseCache.bestGeneratorForPat(goalPat));

    // Note: We don't need to create a step generator. The step function in
    // Relational.iterate just scans the base edges and joins with newPaths.
    // The base generator provides the edges collection which is used for both
    // the initial seed and the step computation.

    // Build the Relational.iterate expression
    return buildRelationalIterate(cache, baseGenerator, goalPat, ordered);
  }

  /**
   * Builds an {@code iterate} expression for fixed-point computation.
   *
   * <pre>{@code
   * Relational.iterate base
   *   (fn (all, new) =>
   *     from step in base, prev in new
   *       where step = prev.joinField
   *       yield {outputFields})
   * }</pre>
   */
  private static Core.Exp buildRelationalIterate(
      Cache cache, Generator baseGenerator, Core.Pat goalPat, boolean ordered) {
    final TypeSystem ts = cache.typeSystem;
    final Type baseElementType = baseGenerator.exp.type.elementType();

    // Get the original collection type by unwrapping any #fromList Bag wrapper
    // that was added by core.withOrdered when ordered=false
    final Type originalCollectionType;
    if (baseGenerator.exp.isCallTo(BuiltIn.BAG_FROM_LIST)) {
      // baseGenerator.exp is "#fromList Bag edges" - get edges.type
      // Access the arg field directly since arg(i) assumes tuple arg
      originalCollectionType = ((Core.Apply) baseGenerator.exp).arg.type;
    } else {
      originalCollectionType = baseGenerator.exp.type;
    }

    // The result type should match goalPat, which may differ from base element
    // type (e.g., edges are records {x,y} but goalPat wants tuples (x,y))
    final Type resultElementType = goalPat.type;
    // Preserve list vs bag from original collection
    final Type resultCollectionType =
        originalCollectionType instanceof ListType
            ? ts.listType(resultElementType)
            : ts.bagType(resultElementType);

    // Create a tuple type for the step function argument: (allPaths, newPaths)
    final Core.IdPat allPaths = core.idPat(resultCollectionType, "allPaths", 0);
    final Core.IdPat newPaths = core.idPat(resultCollectionType, "newPaths", 0);

    // Build the step body: from step in baseGen, prev in newPaths
    //   where joinCondition yield outputTuple
    final Environment env =
        cache.env.bindAll(
            ImmutableList.of(Binding.of(allPaths), Binding.of(newPaths)));
    final FromBuilder fb = core.fromBuilder(ts, env);

    // Scan base generator (e.g., edges) - the same collection used as the seed
    final Core.IdPat stepPat = core.idPat(baseElementType, "step", 0);
    fb.scan(stepPat, baseGenerator.exp);

    // Scan newPaths (second component of the tuple argument) - uses result type
    final Core.IdPat prevPat = core.idPat(resultElementType, "prev", 0);
    fb.scan(prevPat, core.id(newPaths));

    // Build join condition: step.#1 = prev.#2
    // The first field of edge (start node) equals the second field of path
    // (end node). For path (x, z2) and edge (z, y), we join on z = z2.
    final Core.Exp stepId = core.id(stepPat);
    final Core.Exp prevId = core.id(prevPat);
    final Core.Exp stepFirstField = core.field(ts, stepId, 0); // z
    final Core.Exp prevSecondField = core.field(ts, prevId, 1); // z2
    fb.where(core.equal(ts, stepFirstField, prevSecondField));

    // Yield: (prev.#1, step.#2) = (x, y)
    fb.yield_(
        CoreBuilder.core.tuple(
            (RecordLikeType) resultElementType,
            core.field(ts, prevId, 0),
            core.field(ts, stepId, 1)));
    final Core.From stepBody = fb.build();

    // Create the step function: fn (all, new) => stepBody
    final Core.TuplePat stepArgPat =
        core.tuplePat(ts, ImmutableList.of(allPaths, newPaths));
    final Core.Fn stepFn =
        core.fn(
            Pos.ZERO,
            ts.fnType(stepArgPat.type, resultCollectionType),
            ImmutableList.of(core.match(Pos.ZERO, stepArgPat, stepBody)),
            value -> 0);

    // Convert base generator to result type if needed
    // e.g., from {x,y} in edges yield (x, y)
    final Core.Exp seedExp;
    if (baseElementType.equals(resultElementType)) {
      seedExp = baseGenerator.exp;
    } else {
      final FromBuilder seedFb = core.fromBuilder(ts);
      final Core.IdPat seedPat = core.idPat(baseElementType, "e", 0);
      seedFb.scan(seedPat, baseGenerator.exp);
      final Core.Exp seedId = core.id(seedPat);
      seedFb.yield_(
          core.tuple(
              (RecordLikeType) resultElementType,
              core.field(ts, seedId, 0),
              core.field(ts, seedId, 1)));
      seedExp = seedFb.build();
    }

    // Build: Relational.iterate seedExp stepFn
    final Core.Exp iterateFn =
        core.functionLiteral(ts, BuiltIn.RELATIONAL_ITERATE);
    final Core.Exp iterateWithBase =
        core.apply(
            Pos.ZERO,
            ts.fnType(stepFn.type, resultCollectionType),
            iterateFn,
            seedExp);
    return core.withOrdered(
        ordered,
        core.apply(Pos.ZERO, resultCollectionType, iterateWithBase, stepFn),
        ts);
  }

  /**
   * Generates a bounded iteration expression.
   *
   * <p>For {@code path(x, y, 3)}, generates:
   *
   * <pre>{@code
   * let
   *   val iter0 = from (x, y) in edges yield {x, y}
   *   val iter1 = from (x, z) in edges, e in iter0
   *                 where z = e.x yield {x, y = e.y}
   *   val iter2 = from (x, z) in edges, e in iter1
   *                 where z = e.x yield {x, y = e.y}
   * in
   *   List.concat [iter0, iter1, iter2]
   * end
   * }</pre>
   */
  private static Core.@Nullable Exp generateBoundedIterate(
      Cache cache,
      BoundedRecursivePattern pattern,
      Core.Pat goalPat,
      boolean ordered) {
    // 1. Substitute actual args into base case and try to invert
    final Core.Exp substitutedBase =
        substituteArgs(
            cache.typeSystem,
            cache.env,
            pattern.formalParams,
            pattern.callArgs,
            pattern.baseCase);

    // Try to create a generator for the base case
    final Cache baseCache = new Cache(cache.typeSystem, cache.env);
    final List<Core.Exp> baseConstraints = ImmutableList.of(substitutedBase);
    if (!maybeGenerator(baseCache, goalPat, ordered, baseConstraints)) {
      return null;
    }
    final Generator baseGenerator =
        getLast(baseCache.generators.get((Core.NamedPat) goalPat));

    // 2. Similarly, substitute and invert the step predicate
    final Core.Exp substitutedStep =
        substituteArgs(
            cache.typeSystem,
            cache.env,
            pattern.formalParams,
            pattern.callArgs,
            pattern.stepPredicate);

    // For step, we need a pattern that captures the "joining" variable
    final Core.Pat stepGoalPat = deriveStepGoalPat(pattern);
    if (stepGoalPat == null) {
      return null;
    }

    final Cache stepCache = new Cache(cache.typeSystem, cache.env);
    final List<Core.Exp> stepConstraints = ImmutableList.of(substitutedStep);
    if (!maybeGenerator(stepCache, stepGoalPat, ordered, stepConstraints)) {
      return null;
    }
    final Generator stepGenerator =
        getLast(stepCache.generators.get((Core.NamedPat) stepGoalPat));

    // 3. Build the unrolled iteration
    return unrollBoundedIterate(
        cache, baseGenerator, stepGenerator, pattern, ordered);
  }

  /**
   * Derives the goal pattern for the step generator.
   *
   * <p>For a pattern like {@code edge(x, z)}, we need to capture both x and z
   * so we can join z with the previous iteration's output.
   */
  private static Core.@Nullable Pat deriveStepGoalPat(
      BoundedRecursivePattern pattern) {
    // For simplicity, use the intermediate variable as the step goal.
    // This works for the common case where step is like "edge(x, z)".
    if (pattern.intermediateVar instanceof Core.NamedPat) {
      return pattern.intermediateVar;
    }
    return null;
  }

  /** Unrolls bounded iteration into a let expression with union. */
  private static Core.Exp unrollBoundedIterate(
      Cache cache,
      Generator baseGenerator,
      Generator stepGenerator,
      BoundedRecursivePattern pattern,
      boolean ordered) {

    final Type collectionType = baseGenerator.exp.type;
    final Type elementType = collectionType.elementType();

    final List<Core.IdPat> iterPats = new ArrayList<>();
    final List<Core.Exp> iterExps = new ArrayList<>();

    // iter0 = base generator
    final Core.IdPat iter0Pat = core.idPat(collectionType, "iter", 0);
    iterPats.add(iter0Pat);
    iterExps.add(baseGenerator.exp);

    // For each subsequent iteration, build a join
    Core.IdPat prevIterPat = iter0Pat;
    for (int i = 1; i < pattern.depthBound; i++) {
      final Core.IdPat iterIPat = core.idPat(collectionType, "iter", i);

      // Build: from stepVar in stepGenerator, prevVar in prevIter
      //          where joinCondition yield outputTuple
      final FromBuilder fb = core.fromBuilder(cache.typeSystem, cache.env);

      // Scan step generator (e.g., edges for edge(x, z))
      final Core.IdPat stepPat =
          core.idPat(stepGenerator.exp.type.elementType(), "step", 0);
      fb.scan(stepPat, stepGenerator.exp);

      // Scan previous iteration
      final Core.IdPat prevPat = core.idPat(elementType, "prev", 0);
      fb.scan(prevPat, core.id(prevIterPat));

      // Build join condition: the intermediate var from step equals
      // the first field of prev. For path(x, y, n), step produces (x, z) and
      // prev has (z', y). Join on z = z'.
      final Core.Exp joinCondition =
          buildJoinCondition(cache.typeSystem, stepPat, prevPat);
      fb.where(joinCondition);

      // Yield the combined output: (x from step, y from prev)
      final Core.Exp yieldExp =
          buildYieldExp(cache.typeSystem, stepPat, prevPat);
      fb.yield_(yieldExp);

      iterPats.add(iterIPat);
      iterExps.add(fb.build());
      prevIterPat = iterIPat;
    }

    // Build: List.concat [iter0, iter1, ..., iterN-1]
    final List<Core.Exp> iterRefs = transformEager(iterPats, core::id);
    final Core.Exp listOfIters =
        core.list(cache.typeSystem, collectionType, iterRefs);
    final BuiltIn builtIn = ordered ? BuiltIn.LIST_CONCAT : BuiltIn.BAG_CONCAT;
    final Core.Literal fn = core.functionLiteral(cache.typeSystem, builtIn);
    Core.Exp e = core.apply(Pos.ZERO, collectionType, fn, listOfIters);

    // Wrap in nested let expressions: let val iter0 = ... in let val iter1 =
    // ... in ... end end
    for (int i = iterPats.size() - 1; i >= 0; i--) {
      final Core.NonRecValDecl decl =
          core.nonRecValDecl(Pos.ZERO, iterPats.get(i), null, iterExps.get(i));
      e = core.let(decl, e);
    }
    return e;
  }

  /**
   * Builds the join condition for connecting step output to previous iteration.
   *
   * <p>For path traversal where step produces (x, z) and prev has (z', y), the
   * join condition is z = z'.x (or the appropriate field).
   */
  private static Core.Exp buildJoinCondition(
      TypeSystem ts, Core.IdPat stepPat, Core.IdPat prevPat) {
    // The intermediate variable from the step should equal the first
    // component of the previous tuple.
    // For edge(x, z), z is the intermediate var.
    // For prev tuple {x, y}, we join on z = prev.x

    // Get the "joining" field from step (the intermediate variable)
    // This is typically the second field of the step output
    final Core.Exp stepId = core.id(stepPat);
    final Core.Exp prevId = core.id(prevPat);

    // For records with x, y fields, join on step's element = prev.x
    if (stepPat.type instanceof RecordLikeType
        && prevPat.type instanceof RecordLikeType) {
      // step element should equal prev.x (first output param)
      final Core.Exp prevField = core.field(ts, prevId, 0);
      return core.equal(ts, stepId, prevField);
    }

    // Simple case: direct equality
    return core.equal(ts, stepId, core.field(ts, prevId, 0));
  }

  /**
   * Builds the yield expression for the iteration step.
   *
   * <p>Combines the first field from step with remaining fields from prev.
   */
  private static Core.Exp buildYieldExp(
      TypeSystem ts, Core.IdPat stepPat, Core.IdPat prevPat) {
    // For path(x, y), yield {x = step.x, y = prev.y}
    // where step comes from edge(x, z) giving us x
    // and prev comes from previous paths giving us y

    final Core.Exp stepId = core.id(stepPat);
    final Core.Exp prevId = core.id(prevPat);

    if (prevPat.type instanceof RecordType) {
      final RecordType recordType = (RecordType) prevPat.type;
      final List<Core.Exp> fields = new ArrayList<>();

      // First field from step generator (the "x" in edge(x, z))
      // For now, we use the step directly if it's a simple type
      if (stepPat.type instanceof RecordType) {
        fields.add(core.field(ts, stepId, 0));
      } else {
        fields.add(stepId);
      }

      // Remaining fields from prev (skip the first which was the join key)
      for (int i = 1; i < recordType.argNameTypes.size(); i++) {
        fields.add(core.field(ts, prevId, i));
      }

      return core.tuple(ts, null, fields);
    }

    // Simple tuple case
    return core.tuple(ts, null, ImmutableList.of(stepId, prevId));
  }

  /**
   * Removes recursive branches from an inlined orelse expression.
   *
   * <p>For a recursive function body like {@code (edge(x,y) andalso n=1) orelse
   * (exists v0 where path(...))}, after inlining, this returns only the
   * non-recursive branches (e.g., {@code edge(x,y) andalso n=1}). If there are
   * no non-recursive branches, returns null.
   *
   * <p>If the expression is not an orelse, returns null (cannot safely inline a
   * recursive expression that isn't a union of branches).
   */
  private static Core.@Nullable Exp removeRecursiveBranches(
      TypeSystem typeSystem, Core.Exp exp, String fnName) {
    if (!exp.isCallTo(BuiltIn.Z_ORELSE)) {
      // Not an orelse - can't extract non-recursive parts
      return null;
    }
    final List<Core.Exp> nonRecursiveBranches = new ArrayList<>();
    for (Core.Exp branch : core.decomposeOr(exp)) {
      if (!containsSelfCall(branch, fnName)) {
        nonRecursiveBranches.add(branch);
      }
    }
    if (nonRecursiveBranches.isEmpty()) {
      return null;
    }
    return core.orElse(typeSystem, nonRecursiveBranches);
  }

  /** Returns whether the expression contains a call to the named function. */
  private static boolean containsSelfCall(Core.Exp exp, String fnName) {
    final AtomicBoolean found = new AtomicBoolean(false);
    exp.accept(
        new Visitor() {
          @Override
          public void visit(Core.Apply apply) {
            if (apply.fn.op == Op.ID) {
              final Core.Id id = (Core.Id) apply.fn;
              if (id.idPat.name.equals(fnName)) {
                found.set(true);
              }
            }
            super.visit(apply);
          }
        });
    return found.get();
  }

  /** Returns whether exp is a literal zero. */
  private static boolean isZeroLiteral(Core.Exp exp) {
    return exp.op == Op.INT_LITERAL
        && BigDecimal.ZERO.equals(((Core.Literal) exp).value);
  }

  /** Returns whether exp is a literal one. */
  private static boolean isOneLiteral(Core.Exp exp) {
    return exp.op == Op.INT_LITERAL
        && BigDecimal.ONE.equals(((Core.Literal) exp).value);
  }

  /** Returns whether exp is "param - 1". */
  private static boolean isDecrementOf(Core.Exp exp, Core.NamedPat param) {
    if (!exp.isCallTo(BuiltIn.OP_MINUS) && !exp.isCallTo(BuiltIn.Z_MINUS_INT)) {
      return false;
    }
    final Core.Apply minus = (Core.Apply) exp;
    return minus.arg(0).op == Op.ID
        && ((Core.Id) minus.arg(0)).idPat.equals(param)
        && isOneLiteral(minus.arg(1));
  }

  /** Finds the index of a parameter in a (possibly tuple) pattern. */
  private static int findParamIndex(Core.Pat params, Core.NamedPat target) {
    final List<Core.Pat> flatParams = flattenTuplePat(params);
    for (int i = 0; i < flatParams.size(); i++) {
      if (flatParams.get(i).equals(target)) {
        return i;
      }
    }
    return -1;
  }

  /** Flattens a tuple pattern into a list of patterns. */
  private static List<Core.Pat> flattenTuplePat(Core.Pat pat) {
    if (pat.op == Op.TUPLE_PAT) {
      return ((Core.TuplePat) pat).args;
    } else if (pat.op == Op.RECORD_PAT) {
      return ((Core.RecordPat) pat).args;
    } else {
      return ImmutableList.of(pat);
    }
  }

  /** Flattens a tuple expression into a list of expressions. */
  private static List<Core.Exp> flattenTupleExp(Core.Exp exp) {
    if (exp.op == Op.TUPLE) {
      return ((Core.Tuple) exp).args;
    } else {
      return ImmutableList.of(exp);
    }
  }

  /**
   * Substitutes actual arguments for formal parameters in an expression.
   *
   * <p>Handles the case where formals are a tuple pattern like {@code (x, y)}
   * but actual is a single expression like {@code p}. In this case, substitutes
   * {@code x -> #1 p} and {@code y -> #2 p}.
   */
  private static Core.Exp substituteArgs(
      TypeSystem ts,
      Environment env,
      Core.Pat formalParams,
      Core.Exp actualArgs,
      Core.Exp body) {
    final List<Core.Pat> formals = flattenTuplePat(formalParams);
    final List<Core.Exp> actuals = flattenTupleExp(actualArgs);

    // Build substitution map
    final Map<Core.NamedPat, Core.Exp> substitutions = new LinkedHashMap<>();

    if (formals.size() == actuals.size()) {
      // Sizes match - direct substitution
      for (int i = 0; i < formals.size(); i++) {
        if (formals.get(i) instanceof Core.NamedPat) {
          substitutions.put((Core.NamedPat) formals.get(i), actuals.get(i));
        }
      }
    } else if (actuals.size() == 1 && formals.size() > 1) {
      // Single actual (e.g., record p) with multiple formals (e.g., (x, y))
      // Substitute x -> #1 p, y -> #2 p (field access by index)
      final Core.Exp single = actuals.get(0);
      if (single.type instanceof RecordLikeType) {
        for (int i = 0; i < formals.size(); i++) {
          if (formals.get(i) instanceof Core.NamedPat) {
            // Create field access: #i single
            final Core.Exp fieldAccess = core.field(ts, single, i);
            substitutions.put((Core.NamedPat) formals.get(i), fieldAccess);
          }
        }
      }
    } else if (formals.size() == 1 && actuals.size() > 1) {
      // Single formal (e.g., p) with multiple actuals (e.g., (x, y))
      // Substitute p -> (x, y) (construct a tuple)
      final Core.Pat single = formals.get(0);
      if (single instanceof Core.NamedPat) {
        final RecordLikeType tupleType = (RecordLikeType) actualArgs.type;
        final Core.Exp tupleExp = core.tuple(tupleType, actuals);
        substitutions.put((Core.NamedPat) single, tupleExp);
      }
    }

    final Core.Exp substituted =
        Replacer.substitute(ts, env, substitutions, body);
    return core.simplify(ts, substituted);
  }

  /**
   * Pattern for bounded recursive functions.
   *
   * <p>Recognizes:
   *
   * <pre>{@code
   * fun f(x, y, n) =
   *   n > 0 andalso
   *   (baseCase orelse
   *    exists z where stepPredicate andalso f(z, y, n - 1))
   * }</pre>
   */
  static class BoundedRecursivePattern {
    /** The function name. */
    final String fnName;
    /** Position of the depth-bound parameter (e.g., 2 for third param). */
    final int boundParamIndex;
    /** The depth-bound parameter pattern. */
    final Core.NamedPat boundParam;
    /** The output parameters (x, y). */
    final List<Core.NamedPat> outputParams;
    /** The formal parameters pattern. */
    final Core.Pat formalParams;
    /** The base case predicate (e.g., edge(x, y)). */
    final Core.Exp baseCase;
    /** The intermediate variable from exists (z). */
    final Core.Pat intermediateVar;
    /** The step predicate (e.g., edge(x, z)). */
    final Core.Exp stepPredicate;
    /** The recursive call (e.g., path(z, y, n - 1)). */
    final Core.Apply recursiveCall;
    /** The constant bound from the call site (e.g., 3). */
    final int depthBound;
    /** The actual arguments at the call site. */
    final Core.Exp callArgs;

    BoundedRecursivePattern(
        String fnName,
        int boundParamIndex,
        Core.NamedPat boundParam,
        List<Core.NamedPat> outputParams,
        Core.Pat formalParams,
        Core.Exp baseCase,
        Core.Pat intermediateVar,
        Core.Exp stepPredicate,
        Core.Apply recursiveCall,
        int depthBound,
        Core.Exp callArgs) {
      this.fnName = requireNonNull(fnName);
      this.boundParamIndex = boundParamIndex;
      this.boundParam = requireNonNull(boundParam);
      this.outputParams = ImmutableList.copyOf(outputParams);
      this.formalParams = requireNonNull(formalParams);
      this.baseCase = requireNonNull(baseCase);
      this.intermediateVar = requireNonNull(intermediateVar);
      this.stepPredicate = requireNonNull(stepPredicate);
      this.recursiveCall = requireNonNull(recursiveCall);
      this.depthBound = depthBound;
      this.callArgs = requireNonNull(callArgs);
    }
  }

  /**
   * Generator that uses bounded iteration for depth-limited recursive queries.
   */
  static class BoundedIterateGenerator extends Generator {
    private final int depthBound;

    BoundedIterateGenerator(
        Core.NamedPat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        int depthBound) {
      super(exp, freePats, pat, Cardinality.FINITE, true);
      this.depthBound = depthBound;
    }

    @Override
    Core.Exp simplify(TypeSystem typeSystem, Core.Pat pat, Core.Exp exp) {
      // No simplification for bounded iterate expressions
      return exp;
    }
  }

  /**
   * Pattern for unbounded transitive closure functions.
   *
   * <p>Recognizes:
   *
   * <pre>{@code
   * fun path(x, y) =
   *   edge(x, y) orelse
   *   (exists z where edge(x, z) andalso path(z, y))
   * }</pre>
   */
  static class TransitiveClosurePattern {
    /** The function name. */
    final String fnName;
    /** The formal parameters pattern. */
    final Core.Pat formalParams;
    /** The base case predicate (e.g., edge(x, y)). */
    final Core.Exp baseCase;
    /** The intermediate variable from exists (z). */
    final Core.Pat intermediateVar;
    /** The step predicate (e.g., edge(x, z)). */
    final Core.Exp stepPredicate;
    /** The recursive call (e.g., path(z, y)). */
    final Core.Apply recursiveCall;
    /** The actual arguments at the call site. */
    final Core.Exp callArgs;

    TransitiveClosurePattern(
        String fnName,
        Core.Pat formalParams,
        Core.Exp baseCase,
        Core.Pat intermediateVar,
        Core.Exp stepPredicate,
        Core.Apply recursiveCall,
        Core.Exp callArgs) {
      this.fnName = requireNonNull(fnName);
      this.formalParams = requireNonNull(formalParams);
      this.baseCase = requireNonNull(baseCase);
      this.intermediateVar = requireNonNull(intermediateVar);
      this.stepPredicate = requireNonNull(stepPredicate);
      this.recursiveCall = requireNonNull(recursiveCall);
      this.callArgs = requireNonNull(callArgs);
    }
  }

  /** Generator that uses Relational.iterate for transitive closure queries. */
  static class TransitiveClosureGenerator extends Generator {
    /**
     * The original constraint (function call) that this generator satisfies.
     */
    private final Core.Apply constraint;

    TransitiveClosureGenerator(
        Core.Pat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        Core.Apply constraint) {
      super(exp, freePats, pat, Cardinality.FINITE, true);
      this.constraint = constraint;
    }

    @Override
    Core.Exp simplify(TypeSystem typeSystem, Core.Pat pat, Core.Exp exp) {
      // If the expression is the original constraint (the path function call),
      // simplify it to true since the generator produces exactly the values
      // that satisfy the constraint.
      if (exp.equals(constraint)) {
        return core.boolLiteral(true);
      }
      // Also check if it's a call to the same function with the goal pattern.
      // Use containsRef to handle both IdPat (path p) and TuplePat (path (x,
      // y)).
      if (exp.op == Op.APPLY) {
        Core.Apply apply = (Core.Apply) exp;
        if (apply.fn.equals(constraint.fn) && containsRef(apply.arg, pat)) {
          return core.boolLiteral(true);
        }
      }
      return exp;
    }
  }

  /**
   * For each constraint "pat elem collection", adds a generator based on
   * "collection".
   */
  static boolean maybeElem(
      Cache cache,
      Core.Pat goalPat,
      boolean ordered,
      List<Core.Exp> constraints) {
    for (Core.Exp predicate : constraints) {
      if (predicate.isCallTo(BuiltIn.OP_ELEM)) {
        if (containsRef(predicate.arg(0), goalPat)) {
          // If predicate is "(p, q) elem links", first create a generator
          // for "p2 elem links", where "p2 as (p, q)".
          final Core.Exp collection = predicate.arg(1);
          final Core.Pat pat = requireNonNull(wholePat(predicate.arg(0)));
          CollectionGenerator.create(cache, ordered, pat, collection);
          return true;
        }
      }
    }
    return false;
  }

  static boolean maybePoint(
      Cache cache, Core.Pat pat, boolean ordered, List<Core.Exp> constraints) {
    final Core.@Nullable Exp value = point(pat, constraints);
    if (value != null) {
      PointGenerator.create(cache, pat, ordered, value);
      return true;
    }
    return false;
  }

  /**
   * Creates an expression that generates values from several generators.
   *
   * <p>The resulting generator has {@code unique = false} because different
   * branches of an {@code orelse} may produce overlapping values. The caller
   * should add a {@code distinct} operation if uniqueness is required.
   *
   * @param ordered If true, generate a `list`, otherwise a `bag`
   * @param generators Generators
   */
  @SuppressWarnings("UnusedReturnValue")
  static Generator generateUnion(
      Cache cache, boolean ordered, List<Generator> generators) {
    final Core.Exp fn =
        core.functionLiteral(
            cache.typeSystem,
            ordered ? BuiltIn.LIST_CONCAT : BuiltIn.BAG_CONCAT);
    final Type collectionType = generators.get(0).exp.type;
    Core.Exp arg =
        core.list(
            cache.typeSystem,
            collectionType.elementType(),
            transformEager(generators, g -> g.exp));
    final Core.Exp exp = core.apply(Pos.ZERO, collectionType, fn, arg);
    final Set<Core.NamedPat> freePats = freePats(cache.typeSystem, exp);
    return cache.add(new UnionGenerator(exp, freePats, generators));
  }

  static boolean maybeUnion(
      Cache cache, Core.Pat pat, boolean ordered, List<Core.Exp> constraints) {
    next_constraint:
    for (Core.Exp constraint : constraints) {
      if (constraint.isCallTo(BuiltIn.Z_ORELSE)) {
        final List<Generator> generators = new ArrayList<>();

        // Save generator count before trying branches.
        // If any branch fails, we need to clean up generators from successful
        // branches so they don't leak into the cache.
        final int initialCount =
            cache.generators.get((Core.NamedPat) pat).size();

        for (Core.Exp exp : core.decomposeOr(constraint)) {
          if (!maybeGenerator(cache, pat, ordered, core.decomposeAnd(exp))) {
            // Clean up generators added by successful branches before this one.
            // Remove generators until we're back to the initial count.
            while (cache.generators.get((Core.NamedPat) pat).size()
                > initialCount) {
              final List<Generator> genList =
                  (List<Generator>) cache.generators.get((Core.NamedPat) pat);
              genList.remove(genList.size() - 1);
            }
            continue next_constraint;
          }
          generators.add(getLast(cache.generators.get((Core.NamedPat) pat)));
        }
        generateUnion(cache, ordered, generators);
        return true;
      }
    }
    return false;
  }

  static boolean maybeRange(
      Cache cache, Core.Pat pat, boolean ordered, List<Core.Exp> constraints) {
    if (pat.type != PrimitiveType.INT) {
      return false;
    }
    final @Nullable Pair<Core.Exp, Boolean> lower =
        lowerBound(cache.typeSystem, pat, constraints);
    if (lower == null) {
      return false;
    }
    final @Nullable Pair<Core.Exp, Boolean> upper =
        upperBound(cache.typeSystem, pat, constraints);
    if (upper == null) {
      return false;
    }
    Generators.generateRange(
        cache,
        ordered,
        (Core.NamedPat) pat,
        lower.left,
        lower.right,
        upper.left,
        upper.right);
    return true;
  }

  /**
   * Checks for {@code String.isPrefix p s} pattern where p is the goal pattern
   * and s is a string expression. If found, generates all prefixes of s.
   *
   * <p>For example, {@code from p where String.isPrefix p "abcd"} generates
   * {@code List.tabulate(String.size "abcd" + 1, fn i =>
   * String.substring("abcd", 0, i))}.
   *
   * <p>String.isPrefix is curried, so the structure is: {@code
   * APPLY(APPLY(FN_LITERAL(STRING_IS_PREFIX), p), s)}
   */
  static boolean maybeStringPrefix(
      Cache cache, Core.Pat pat, boolean ordered, List<Core.Exp> constraints) {
    if (pat.type != PrimitiveType.STRING) {
      return false;
    }
    for (Core.Exp constraint : constraints) {
      // Check for curried call: APPLY(APPLY(FN_LITERAL(STRING_IS_PREFIX), p),
      // s)
      if (constraint.op != Op.APPLY) {
        continue;
      }
      final Core.Apply outerApply = (Core.Apply) constraint;
      if (outerApply.fn.op != Op.APPLY) {
        continue;
      }
      final Core.Apply innerApply = (Core.Apply) outerApply.fn;
      if (!innerApply.isCallTo(BuiltIn.STRING_IS_PREFIX)) {
        continue;
      }
      // innerApply.arg is p (the prefix pattern)
      // outerApply.arg is s (the string to check prefixes of)
      if (references(innerApply.arg, pat)) {
        StringPrefixGenerator.create(cache, pat, ordered, outerApply.arg);
        return true;
      }
    }
    return false;
  }

  /**
   * Creates an expression that generates a range of integer values.
   *
   * <p>For example, {@code generateRange(3, true, 8, false)} generates a range
   * {@code 3 < x <= 8}, which yields the values {@code [4, 5, 6, 7, 8]}.
   *
   * @param ordered If true, generate a `list`, otherwise a `bag`
   * @param pat Pattern
   * @param lower Lower bound
   * @param lowerStrict Whether the lower bound is strict (exclusive): true for
   *     {@code x > lower}, false for {@code x >= lower}
   * @param upper Upper bound
   * @param upperStrict Whether the upper bound is strict (exclusive): true for
   *     {@code x < upper}, false for {@code x <= upper}
   */
  static Generator generateRange(
      Cache cache,
      boolean ordered,
      Core.NamedPat pat,
      Core.Exp lower,
      boolean lowerStrict,
      Core.Exp upper,
      boolean upperStrict) {
    // For x > lower, we want x >= lower + 1
    final TypeSystem typeSystem = cache.typeSystem;
    final Core.Exp lower2 =
        lowerStrict
            ? core.call(
                typeSystem,
                BuiltIn.Z_PLUS_INT,
                lower,
                core.intLiteral(BigDecimal.ONE))
            : lower;
    // For x < upper, we want x <= upper - 1
    final Core.Exp upper2 =
        upperStrict
            ? core.call(
                typeSystem,
                BuiltIn.Z_MINUS_INT,
                upper,
                core.intLiteral(BigDecimal.ONE))
            : upper;

    // List.tabulate(upper - lower + 1, fn k => lower + k)
    final Type type = PrimitiveType.INT;
    final Core.IdPat kPat = core.idPat(type, "k", 0);
    final Core.Exp upperMinusLower =
        core.call(typeSystem, BuiltIn.OP_MINUS, type, Pos.ZERO, upper2, lower2);
    final Core.Exp count =
        core.call(
            typeSystem,
            BuiltIn.OP_PLUS,
            type,
            Pos.ZERO,
            upperMinusLower,
            core.intLiteral(BigDecimal.ONE));
    final Core.Exp lowerPlusK =
        core.call(typeSystem, BuiltIn.Z_PLUS_INT, lower2, core.id(kPat));
    final Core.Fn fn = core.fn(typeSystem.fnType(type, type), kPat, lowerPlusK);
    BuiltIn tabulate = ordered ? BuiltIn.LIST_TABULATE : BuiltIn.BAG_TABULATE;
    Core.Apply exp = core.call(typeSystem, tabulate, type, Pos.ZERO, count, fn);
    final Core.Exp simplified = Simplifier.simplify(typeSystem, exp);
    final Set<Core.NamedPat> freePats = freePats(typeSystem, simplified);
    return cache.add(
        new RangeGenerator(
            pat, simplified, freePats, lower, lowerStrict, upper, upperStrict));
  }

  /** Returns an extent generator, or null if expression is not an extent. */
  @SuppressWarnings("UnusedReturnValue")
  public static boolean maybeExtent(Cache cache, Core.Pat pat, Core.Exp exp) {
    if (exp.isExtent()) {
      ExtentGenerator.create(cache, pat, exp);
      return true;
    }
    return false;
  }

  /** If there is a predicate "pat = exp" or "exp = pat", returns "exp". */
  static Core.@Nullable Exp point(Core.Pat pat, List<Core.Exp> constraints) {
    for (Core.Exp constraint : constraints) {
      if (constraint.isCallTo(BuiltIn.OP_EQ)) {
        if (references(constraint.arg(0), pat)) {
          return constraint.arg(1);
        }
        if (references(constraint.arg(1), pat)) {
          return constraint.arg(0);
        }
      }
    }
    return null;
  }

  /**
   * If there is a predicate "pat &gt; exp" or "pat &ge; exp", returns "exp" and
   * whether the comparison is strict.
   *
   * <p>Also handles constraints like "e &lt; pat + k" which gives a lower bound
   * of "e - k" for pat.
   *
   * <p>We do not attempt to find the strongest such constraint. Clearly "p &gt;
   * 10" is stronger than "p &ge; 0". But is "p &gt; x" stronger than "p &ge;
   * y"? If the goal is to convert an infinite generator to a finite generator,
   * any constraint is good enough.
   */
  static @Nullable Pair<Core.Exp, Boolean> lowerBound(
      TypeSystem typeSystem, Core.Pat pat, List<Core.Exp> constraints) {
    for (Core.Exp constraint : constraints) {
      switch (constraint.builtIn()) {
        case OP_GT:
        case OP_GE:
          if (references(constraint.arg(0), pat)) {
            // "p > e" -> (strict, e); "p >= e" -> (non-strict, e).
            final boolean strict = constraint.builtIn() == BuiltIn.OP_GT;
            return Pair.of(constraint.arg(1), strict);
          }
          // Check for "e > p + k" -> "p < e - k" (this is an upper bound, skip)
          break;
        case OP_LT:
        case OP_LE:
          if (references(constraint.arg(1), pat)) {
            // "e < p" -> (strict, e); "e <= p" -> (non-strict, e).
            final boolean strict = constraint.builtIn() == BuiltIn.OP_LT;
            return Pair.of(constraint.arg(0), strict);
          }
          // Check for "e < p + k" -> "p > e - k" -> lower bound = e - k
          final BigDecimal offset = extractOffset(constraint.arg(1), pat);
          if (offset != null) {
            // "e < p + k" -> "p > e - k"
            final boolean strict = constraint.builtIn() == BuiltIn.OP_LT;
            final Core.Exp bound =
                adjustBound(constraint.arg(0), offset, typeSystem);
            return Pair.of(bound, strict);
          }
          break;
      }
    }
    return null;
  }

  /**
   * If there is a constraint "pat &lt; exp" or "pat &le; exp", returns "exp"
   * and whether the comparison is strict.
   *
   * <p>Also handles constraints like "e &gt; pat + k" which gives an upper
   * bound of "e - k" for pat.
   *
   * <p>Analogous to {@link #lowerBound(TypeSystem, Core.Pat, List)}.
   */
  static @Nullable Pair<Core.Exp, Boolean> upperBound(
      TypeSystem typeSystem, Core.Pat pat, List<Core.Exp> constraints) {
    for (Core.Exp constraint : constraints) {
      switch (constraint.builtIn()) {
        case OP_LT:
        case OP_LE:
          if (references(constraint.arg(0), pat)) {
            // "p < e" -> (strict, e); "p <= e" -> (non-strict, e).
            final boolean strict = constraint.builtIn() == BuiltIn.OP_LT;
            return Pair.of(constraint.arg(1), strict);
          }
          // Check for "e < p + k" -> "p > e - k" (this is a lower bound, skip)
          break;
        case OP_GT:
        case OP_GE:
          if (references(constraint.arg(1), pat)) {
            // "e > p" -> (strict, e); "e >= p" -> (non-strict, e).
            final boolean strict = constraint.builtIn() == BuiltIn.OP_GT;
            return Pair.of(constraint.arg(0), strict);
          }
          // Check for "e > p + k" -> "p < e - k" -> upper bound = e - k
          final BigDecimal offset = extractOffset(constraint.arg(1), pat);
          if (offset != null) {
            // "e > p + k" -> "p < e - k"
            final boolean strict = constraint.builtIn() == BuiltIn.OP_GT;
            final Core.Exp bound =
                adjustBound(constraint.arg(0), offset, typeSystem);
            return Pair.of(bound, strict);
          }
          break;
      }
    }
    return null;
  }

  /**
   * Returns whether an expression is a reference ({@link Core.Id}) to a
   * pattern.
   */
  private static boolean references(Core.Exp arg, Core.Pat pat) {
    return arg.op == Op.ID && ((Core.Id) arg).idPat.equals(pat);
  }

  /**
   * Extracts the offset from an expression relative to a pattern.
   *
   * <p>Returns the integer offset if exp is pat, pat + k, or pat - k where k is
   * an integer literal. Returns null otherwise.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code extractOffset(y, y)} returns 0
   *   <li>{@code extractOffset(y + 5, y)} returns 5
   *   <li>{@code extractOffset(y - 3, y)} returns -3
   *   <li>{@code extractOffset(x + 5, y)} returns null
   * </ul>
   */
  private static @Nullable BigDecimal extractOffset(
      Core.Exp exp, Core.Pat pat) {
    if (references(exp, pat)) {
      return BigDecimal.ZERO;
    }
    if (exp instanceof Core.Apply) {
      final Core.Apply apply = (Core.Apply) exp;
      switch (apply.builtIn()) {
        case Z_PLUS_INT:
        case OP_PLUS:
          // pat + k
          if (references(apply.arg(0), pat) && apply.arg(1).isConstant()) {
            final Object value = ((Core.Literal) apply.arg(1)).value;
            if (value instanceof BigDecimal) {
              return (BigDecimal) value;
            }
          }
          // k + pat
          if (references(apply.arg(1), pat) && apply.arg(0).isConstant()) {
            final Object value = ((Core.Literal) apply.arg(0)).value;
            if (value instanceof BigDecimal) {
              return (BigDecimal) value;
            }
          }
          break;
        case Z_MINUS_INT:
        case OP_MINUS:
          // pat - k
          if (references(apply.arg(0), pat) && apply.arg(1).isConstant()) {
            final Object value = ((Core.Literal) apply.arg(1)).value;
            if (value instanceof BigDecimal) {
              return ((BigDecimal) value).negate();
            }
          }
          break;
      }
    }
    return null;
  }

  /**
   * Adjusts an expression by subtracting an offset.
   *
   * <p>For example, {@code adjustBound(x, 5, ts)} returns {@code x - 5}.
   */
  private static Core.Exp adjustBound(
      Core.Exp exp, BigDecimal offset, TypeSystem typeSystem) {
    if (offset.equals(BigDecimal.ZERO)) {
      return exp;
    }
    return core.call(
        typeSystem, BuiltIn.Z_MINUS_INT, exp, core.intLiteral(offset));
  }

  /**
   * Returns whether an expression contains a reference to a pattern. If the
   * pattern is {@link Core.IdPat} {@code p}, returns true for {@link Core.Id}
   * {@code p} and tuple {@code (p, q)}.
   *
   * <p>When {@code pat} is a TuplePat, returns true if {@code exp} is a tuple
   * expression where each component matches the corresponding component of
   * {@code pat}.
   */
  private static boolean containsRef(Core.Exp exp, Core.Pat pat) {
    // Special case: if pat is a TuplePat, check if exp is a matching tuple
    if (pat instanceof Core.TuplePat && exp.op == Op.TUPLE) {
      final Core.TuplePat tuplePat = (Core.TuplePat) pat;
      final Core.Tuple tuple = (Core.Tuple) exp;
      if (tuplePat.args.size() == tuple.args.size()) {
        // Check if each component of the tuple expression matches
        // the corresponding component pattern
        for (int i = 0; i < tuplePat.args.size(); i++) {
          if (!containsRef(tuple.args.get(i), tuplePat.args.get(i))) {
            return false;
          }
        }
        return true;
      }
    }

    switch (exp.op) {
      case ID:
        return ((Core.Id) exp).idPat.equals(pat);

      case TUPLE:
        for (Core.Exp arg : ((Core.Tuple) exp).args) {
          if (containsRef(arg, pat)) {
            return true;
          }
        }
        return false;

        // Note: Records in Core are represented as Tuple with RecordType.
        // The TUPLE case above handles them since Tuple.args contains the
        // values.

      case APPLY:
        // Handle field access like #1 p or #x p
        final Core.Apply apply = (Core.Apply) exp;
        if (apply.fn.op == Op.RECORD_SELECTOR) {
          return containsRef(apply.arg, pat);
        }
        // Also recursively check function and arg for other applies
        return containsRef(apply.fn, pat) || containsRef(apply.arg, pat);

      default:
        return false;
    }
  }

  /**
   * Creates a pattern that encompasses a whole expression.
   *
   * <p>Returns null if {@code exp} does not contain a pattern reference; you
   * should have called {@link #containsRef(Core.Exp, Core.Pat)} first.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code wholePat(id(p))} returns {@code idPat(p)}
   *   <li>{@code wholePat(tuple(id(p), id(q)))} returns {@code tuplePat(p, q)}
   *   <li>{@code wholePat(tuple(id(p), literal("x")))} returns {@code
   *       tuplePat(p, literalPat("x"))}
   *   <li>{@code wholePat(#1 p)} returns {@code idPat(p)}
   * </ul>
   */
  private static Core.@Nullable Pat wholePat(Core.Exp exp) {
    switch (exp.op) {
      case ID:
        return ((Core.Id) exp).idPat;

      case TUPLE:
        final Core.Tuple tuple = (Core.Tuple) exp;
        int slot = -1;
        final List<Core.Pat> patList = new ArrayList<>();
        for (Ord<Core.Exp> arg : Ord.zip(tuple.args)) {
          if (wholePat(arg.e) != null) {
            slot = arg.i;
          }
          patList.add(core.toPat(arg.e));
        }
        if (slot < 0) {
          return null;
        }
        final RecordLikeType type = tuple.type();
        return type instanceof RecordType
            ? core.recordPat((RecordType) type, patList)
            : core.tuplePat(type, patList);

      case APPLY:
        // Handle field access like #1 p or #x p
        final Core.Apply apply = (Core.Apply) exp;
        if (apply.fn.op == Op.RECORD_SELECTOR && apply.arg.op == Op.ID) {
          // Field access on an ID - the underlying variable is what we want
          return ((Core.Id) apply.arg).idPat;
        }
        return null;

      default:
        return null;
    }
  }

  /**
   * Generator that generates a range of integers from {@code lower} to {@code
   * upper}.
   */
  static class RangeGenerator extends Generator {
    private final Core.Exp lower;
    private final Core.Exp upper;

    RangeGenerator(
        Core.NamedPat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        Core.Exp lower,
        boolean ignoreLowerStrict,
        Core.Exp upper,
        boolean ignoreUpperStrict) {
      super(exp, freePats, pat, Cardinality.FINITE, true);
      this.lower = lower;
      this.upper = upper;
    }

    @Override
    Core.Exp simplify(TypeSystem typeSystem, Core.Pat pat, Core.Exp exp) {
      // Simplify "p > lower && p < upper && other" to "other"
      // (or to "true" if there are no other conjuncts).
      final List<Core.Exp> ands = core.decomposeAnd(exp);
      final Pair<Core.Exp, Boolean> lowerBound =
          lowerBound(typeSystem, pat, ands);
      final Pair<Core.Exp, Boolean> upperBound =
          upperBound(typeSystem, pat, ands);
      if (lowerBound == null || upperBound == null) {
        return exp;
      }
      requireNonNull(lowerBound.left);
      requireNonNull(lowerBound.right);
      requireNonNull(upperBound.left);
      requireNonNull(upperBound.right);
      final boolean boundsMatch =
          lowerBound.left.equals(this.lower)
              && upperBound.left.equals(this.upper);
      final boolean boundsImplied =
          lowerBound.left.isConstant()
              && upperBound.left.isConstant()
              && this.lower.isConstant()
              && this.upper.isConstant()
              && gt(lowerBound.left, this.lower, lowerBound.right)
              && gt(this.upper, upperBound.left, upperBound.right);
      if (boundsMatch || boundsImplied) {
        // Remove the range constraints and keep the rest.
        final Core.Exp lowerConstraint = findLowerConstraint(pat, ands);
        final Core.Exp upperConstraint = findUpperConstraint(pat, ands);
        final List<Core.Exp> remaining = new ArrayList<>(ands);
        remaining.remove(lowerConstraint);
        remaining.remove(upperConstraint);
        if (remaining.isEmpty()) {
          return core.boolLiteral(true);
        }
        if (remaining.size() == 1) {
          return remaining.get(0);
        }
        // Multiple remaining constraints - use core.andAlso to
        // reconstruct.
        return core.andAlso(typeSystem, remaining);
      }
      return exp;
    }

    /** Finds the constraint that provides the lower bound. */
    private static Core.@Nullable Exp findLowerConstraint(
        Core.Pat pat, List<Core.Exp> constraints) {
      for (Core.Exp constraint : constraints) {
        switch (constraint.builtIn()) {
          case OP_GT:
          case OP_GE:
            if (references(constraint.arg(0), pat)) {
              return constraint;
            }
            break;
          case OP_LT:
          case OP_LE:
            if (references(constraint.arg(1), pat)) {
              return constraint;
            }
            break;
        }
      }
      return null;
    }

    /** Finds the constraint that provides the upper bound. */
    private static Core.@Nullable Exp findUpperConstraint(
        Core.Pat pat, List<Core.Exp> constraints) {
      for (Core.Exp constraint : constraints) {
        switch (constraint.builtIn()) {
          case OP_LT:
          case OP_LE:
            if (references(constraint.arg(0), pat)) {
              return constraint;
            }
            break;
          case OP_GT:
          case OP_GE:
            if (references(constraint.arg(1), pat)) {
              return constraint;
            }
            break;
        }
      }
      return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static boolean gt(Core.Exp left, Core.Exp right, boolean strict) {
      final Comparable leftVal = ((Core.Literal) left).value;
      final Comparable rightVal = ((Core.Literal) right).value;
      int c = leftVal.compareTo(rightVal);
      return strict ? c > 0 : c >= 0;
    }
  }

  /** Generator that generates a single value. */
  static class PointGenerator extends Generator {
    private final Core.Exp point;

    PointGenerator(
        Core.Pat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        Core.Exp point) {
      super(exp, freePats, pat, Cardinality.SINGLE, true);
      this.point = point;
    }

    /**
     * Creates an expression that generates a single value.
     *
     * @param ordered If true, generate a `list`, otherwise a `bag`
     * @param lower Lower bound
     */
    @SuppressWarnings("UnusedReturnValue")
    static Generator create(
        Cache cache, Core.Pat pat, boolean ordered, Core.Exp lower) {
      final Core.Exp exp =
          ordered
              ? core.list(cache.typeSystem, lower)
              : core.bag(cache.typeSystem, lower);
      final Set<Core.NamedPat> freePats = freePats(cache.typeSystem, exp);
      return cache.add(new PointGenerator(pat, exp, freePats, lower));
    }

    @Override
    Core.Exp simplify(TypeSystem typeSystem, Core.Pat pat, Core.Exp exp) {
      // Simplify "p = point" to true.
      if (exp.isCallTo(BuiltIn.OP_EQ)) {
        final Core.@Nullable Exp point = point(pat, ImmutableList.of(exp));
        if (point != null) {
          if (point.equals(this.point)) {
            return core.boolLiteral(true);
          }
          if (point.isConstant() && this.point.isConstant()) {
            return core.boolLiteral(false);
          }
        }
      }
      return exp;
    }
  }

  /** Generator that generates all prefixes of a string expression. */
  static class StringPrefixGenerator extends Generator {
    private final Core.Exp strExp;

    StringPrefixGenerator(
        Core.Pat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        Core.Exp strExp) {
      super(exp, freePats, pat, Cardinality.FINITE, true);
      this.strExp = strExp;
    }

    /**
     * Creates an expression that generates all prefixes of a string.
     *
     * <p>Generates: {@code List.tabulate(String.size s + 1, fn i =>
     * String.substring(s, 0, i))}
     *
     * @param strExp The string expression to generate prefixes of
     */
    @SuppressWarnings("UnusedReturnValue")
    static Generator create(
        Cache cache, Core.Pat pat, boolean ordered, Core.Exp strExp) {
      final TypeSystem ts = cache.typeSystem;

      // Build: String.size s + 1
      final Core.Exp sizeExp = core.call(ts, BuiltIn.STRING_SIZE, strExp);
      final Core.Literal one = core.intLiteral(BigDecimal.ONE);
      final Core.Exp countExp = core.call(ts, BuiltIn.Z_PLUS_INT, sizeExp, one);

      // Build: fn i => String.substring(s, 0, i)
      final Core.IdPat iPat = core.idPat(PrimitiveType.INT, "i", 0);
      Core.Literal zero = core.intLiteral(BigDecimal.ZERO);
      final Core.Exp substringExp =
          core.call(
              ts,
              BuiltIn.STRING_SUBSTRING,
              core.tuple(ts, strExp, zero, core.id(iPat)));
      final Core.Fn fn =
          core.fn(
              ts.fnType(PrimitiveType.INT, PrimitiveType.STRING),
              iPat,
              substringExp);

      // Build: List.tabulate(count, fn) or Bag.tabulate(count, fn)
      final BuiltIn tabulate =
          ordered ? BuiltIn.LIST_TABULATE : BuiltIn.BAG_TABULATE;
      final Core.Apply exp =
          core.call(ts, tabulate, PrimitiveType.STRING, Pos.ZERO, countExp, fn);

      final Set<Core.NamedPat> freePats = freePats(ts, exp);
      return cache.add(new StringPrefixGenerator(pat, exp, freePats, strExp));
    }

    @Override
    Core.Exp simplify(TypeSystem typeSystem, Core.Pat pat, Core.Exp exp) {
      // Simplify "String.isPrefix p strExp" to true when p is in this generator
      // Structure: APPLY(APPLY(FN_LITERAL(STRING_IS_PREFIX), p), s)
      if (exp.op != Op.APPLY) {
        return exp;
      }
      final Core.Apply outerApply = (Core.Apply) exp;
      if (outerApply.fn.op != Op.APPLY) {
        return exp;
      }
      final Core.Apply innerApply = (Core.Apply) outerApply.fn;
      if (!innerApply.isCallTo(BuiltIn.STRING_IS_PREFIX)) {
        return exp;
      }
      if (references(innerApply.arg, pat)) {
        if (outerApply.arg.equals(this.strExp)) {
          return core.boolLiteral(true);
        }
      }
      return exp;
    }
  }

  /** Generator that generates a union of several underlying generators. */
  static class UnionGenerator extends Generator {
    private final List<Generator> generators;

    UnionGenerator(
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        List<Generator> generators) {
      super(
          exp,
          freePats,
          firstGenerator(generators).pat,
          Cardinality.FINITE,
          // not unique because branches of union may overlap
          false);
      this.generators = ImmutableList.copyOf(generators);
    }

    private static Generator firstGenerator(List<Generator> generators) {
      checkArgument(generators.size() >= 2);
      return generators.get(0);
    }

    @Override
    Core.Exp simplify(TypeSystem typeSystem, Core.Pat pat, Core.Exp exp) {
      for (Generator generator : generators) {
        exp = generator.simplify(typeSystem, pat, exp);
      }
      return exp;
    }
  }

  /**
   * Generator that generates all values of a type. For most types it is
   * infinite.
   */
  static class ExtentGenerator extends Generator {
    private ExtentGenerator(
        Core.Pat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        Cardinality cardinality) {
      super(exp, freePats, pat, cardinality, true);
    }

    /** Creates an extent generator. */
    @SuppressWarnings("UnusedReturnValue")
    static Generator create(Cache cache, Core.Pat pat, Core.Exp exp) {
      final RangeExtent rangeExtent = exp.getRangeExtent();
      final Cardinality cardinality =
          rangeExtent.iterable == null
              ? Cardinality.INFINITE
              : Cardinality.FINITE;
      final Set<Core.NamedPat> freePats = freePats(cache.typeSystem, exp);
      return cache.add(new ExtentGenerator(pat, exp, freePats, cardinality));
    }

    @Override
    Core.Exp simplify(TypeSystem typeSystem, Core.Pat pat, Core.Exp exp) {
      return exp;
    }
  }

  /**
   * Generator that returns the contents of a collection.
   *
   * <p>The inverse of {@code p elem collection} is {@code collection}.
   */
  static class CollectionGenerator extends Generator {
    final Core.Exp collection;

    private CollectionGenerator(
        Core.Pat pat,
        Core.Exp collection,
        Iterable<? extends Core.NamedPat> freePats) {
      super(collection, freePats, pat, Cardinality.FINITE, true);
      this.collection = collection;
      checkArgument(collection.type.isCollection());
    }

    @SuppressWarnings("UnusedReturnValue")
    static CollectionGenerator create(
        Cache cache, boolean ordered, Core.Pat pat, Core.Exp collection) {
      // Convert the collection to a list or bag, per "ordered".
      final TypeSystem typeSystem = cache.typeSystem;
      final Core.Exp collection2 =
          core.withOrdered(ordered, collection, typeSystem);
      final Set<Core.NamedPat> freePats =
          freePats(cache.typeSystem, collection2);
      return cache.add(new CollectionGenerator(pat, collection2, freePats));
    }

    @Override
    Core.Exp simplify(TypeSystem typeSystem, Core.Pat pat, Core.Exp exp) {
      if (exp.isCallTo(BuiltIn.OP_ELEM)
          && references(exp.arg(0), pat)
          && exp.arg(1).equals(this.collection)) {
        // "p elem collection" simplifies to "true"
        return core.boolLiteral(true);
      }
      return exp;
    }
  }

  /** Generators that have been created to date. */
  static class Cache {
    final TypeSystem typeSystem;
    final Environment env;
    final Multimap<Core.NamedPat, Generator> generators =
        MultimapBuilder.hashKeys().arrayListValues().build();

    Cache(TypeSystem typeSystem, Environment env) {
      this.typeSystem = requireNonNull(typeSystem);
      this.env = requireNonNull(env);
    }

    @Nullable
    Generator bestGenerator(Core.NamedPat namedPat) {
      Generator bestGenerator = null;
      for (Generator generator : generators.get(namedPat)) {
        bestGenerator = generator;
      }
      return bestGenerator;
    }

    /**
     * Gets the best generator for a pattern, which may be a TuplePat.
     *
     * <p>For TuplePat, looks for a generator that is indexed under all
     * component patterns. Returns the first such generator found.
     */
    @Nullable
    Generator bestGeneratorForPat(Core.Pat pat) {
      if (pat instanceof Core.NamedPat) {
        return bestGenerator((Core.NamedPat) pat);
      }
      if (pat instanceof Core.TuplePat) {
        // For TuplePat, find a generator common to all component patterns
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        if (tuplePat.args.isEmpty()) {
          return null;
        }
        // Get generators for the first component
        Set<Generator> candidates = null;
        for (Core.Pat component : tuplePat.args) {
          if (component instanceof Core.NamedPat) {
            final Set<Generator> componentGens =
                new HashSet<>(generators.get((Core.NamedPat) component));
            if (candidates == null) {
              candidates = componentGens;
            } else {
              candidates.retainAll(componentGens);
            }
          }
        }
        if (candidates != null && !candidates.isEmpty()) {
          return candidates.iterator().next();
        }
      }
      return null;
    }

    /**
     * Registers a generator, adding it to the index of each constituent
     * pattern.
     *
     * <p>For example, if {@code g} is {@code CollectionGenerator((s, t) elem
     * links)}, then {@code add(g)} will add {@code (s, g), (t, g)} to the
     * generators index.
     */
    public <G extends Generator> G add(G generator) {
      for (Core.NamedPat namedPat : generator.pat.expand()) {
        generators.put(namedPat, generator);
      }
      return generator;
    }

    /** Removes all generators for a given pattern. */
    public void remove(Core.NamedPat namedPat) {
      generators.removeAll(namedPat);
    }
  }
}

// End Generators.java
