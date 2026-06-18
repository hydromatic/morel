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
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Range;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
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
      Cache cache, Core.Pat pat, boolean ordered, Context context) {
    // Phase A: Classify leaf constraints (single loop)
    Core.Exp elemMatch = null;
    Core.Exp pointMatch = null;
    Core.Exp pointValue = null;
    boolean hasBounds = false;
    Core.Exp prefixMatch = null;
    Core.Exp prefixString = null;
    Core.Exp rangeContainsMatch = null;
    Core.Exp rangeContainsValue = null;
    Core.Exp rangeContainsRange = null;

    for (Core.Exp c : context.constraints) {
      if (elemMatch == null
          && c.isCallTo(BuiltIn.OP_ELEM)
          && containsRef(c.arg(0), pat)) {
        elemMatch = c;
      }
      // 'pat elem [a..b]' is rewritten to 'Range.contains (a..b) pat'. If the
      // range is finite and 'pat' is discrete, it is a finite generator for
      // 'pat', equivalent to 'pat in Range.flatten [a..b]'.
      if (rangeContainsMatch == null
          && c.op == Op.APPLY
          && ((Core.Apply) c).fn.isCallTo(BuiltIn.RANGE_CONTAINS)
          && references(((Core.Apply) c).arg, pat)
          && pat.type.isDiscrete(cache.typeSystem)) {
        final Core.Exp range = ((Core.Apply) ((Core.Apply) c).fn).arg;
        if (isFiniteRange(range)) {
          rangeContainsMatch = c;
          rangeContainsValue = ((Core.Apply) c).arg;
          rangeContainsRange = range;
        }
      }
      if (pointMatch == null && c.isCallTo(BuiltIn.OP_EQ)) {
        if (references(c.arg(0), pat)) {
          pointMatch = c;
          pointValue = c.arg(1);
        } else if (references(c.arg(1), pat)) {
          pointMatch = c;
          pointValue = c.arg(0);
        }
      }
      if (!hasBounds && isBoundConstraint(c, pat)) {
        hasBounds = true;
      }
      if (prefixMatch == null && pat.type == PrimitiveType.STRING) {
        if (c.op == Op.APPLY) {
          final Core.Apply outer = (Core.Apply) c;
          if (outer.fn.op == Op.APPLY) {
            final Core.Apply inner = (Core.Apply) outer.fn;
            if (inner.isCallTo(BuiltIn.STRING_IS_PREFIX)
                && references(inner.arg, pat)) {
              prefixMatch = c;
              prefixString = outer.arg;
            }
          }
        }
      }
    }

    // Phase B: Synthesize leaf generators (priority order)
    if (elemMatch != null) {
      final Core.Exp collection = elemMatch.arg(1);
      final Core.Pat elemPat = cache.patForExp(elemMatch.arg(0));
      CollectionGenerator.create(
          cache, ordered, elemPat, collection, ImmutableSet.of(elemMatch));
      cache.deriveFieldGenerators(ordered);
      return true;
    }
    if (rangeContainsMatch != null) {
      final TypeSystem typeSystem = cache.typeSystem;
      final Type elementType = pat.type;
      final Core.Exp rangeListExp =
          core.list(
              typeSystem,
              typeSystem.range(elementType),
              ImmutableList.of(rangeContainsRange));
      final Core.Exp collection =
          core.call(
              typeSystem,
              BuiltIn.RANGE_FLATTEN,
              elementType,
              Pos.ZERO,
              rangeListExp);
      final Core.Pat elemPat = cache.patForExp(rangeContainsValue);
      CollectionGenerator.create(
          cache,
          ordered,
          elemPat,
          collection,
          ImmutableSet.of(rangeContainsMatch));
      cache.deriveFieldGenerators(ordered);
      return true;
    }
    if (pointMatch != null) {
      PointGenerator.create(
          cache, pat, ordered, pointValue, ImmutableSet.of(pointMatch));
      return true;
    }
    if (hasBounds && pat.type.isDiscrete(cache.typeSystem)) {
      final @Nullable Bound lower =
          lowerBound(cache.typeSystem, pat, context.constraints);
      final @Nullable Bound upper =
          upperBound(cache.typeSystem, pat, context.constraints);
      if (lower != null && upper != null) {
        generateRange(cache, ordered, (Core.NamedPat) pat, lower, upper);
        return true;
      }
    }
    if (prefixMatch != null) {
      StringPrefixGenerator.create(
          cache, pat, ordered, prefixString, ImmutableSet.of(prefixMatch));
      return true;
    }

    // Phase C: Complex strategies (unchanged)
    if (maybeExists(cache, pat, context)) {
      return true;
    }

    if (maybeFunction(cache, pat, ordered, context)) {
      return true;
    }

    if (maybeCase(cache, pat, ordered, context)) {
      return true;
    }

    return maybeUnion(cache, pat, ordered, context);
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
      Cache cache, Core.Pat pat, Context context) {
    constraint_loop:
    for (int j = 0; j < context.constraints.size(); j++) {
      final Core.Exp constraint = context.constraints.get(j);
      if (constraint.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)) {
        final Core.Apply apply = (Core.Apply) constraint;
        if (apply.arg instanceof Core.From) {
          final Core.From from = (Core.From) apply.arg;

          // Create a copy of constraints with this constraint removed.
          // When we encounter a "where" step, we will add more constraints.
          final List<Core.Exp> constraints2 =
              new ArrayList<>(context.constraints);
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
              case YIELD:
              case GROUP:
                // Skip these steps - they don't add constraints
                break;
              case WHERE:
                // Decompose "andalso" to allow each conjunct to be processed
                // separately. E.g., "edge(x,y) andalso x = y" becomes
                // ["edge(x,y)", "x = y"], allowing maybeFunction to process
                // the edge function call.
                core.flattenAnd(((Core.Where) step).exp, constraints2::add);
                // First try to create a generator without inner dependencies
                final Context innerContext = new Context(constraints2);
                if (maybeGenerator(cache, pat, false, innerContext)) {
                  // Check if the created generator depends on inner scans
                  final Generator gen =
                      cache.bestGenerator((Core.NamedPat) pat);
                  if (gen != null) {
                    // Check if any free variable of the generator expression
                    // comes from inner scans. If so, we need to join with those
                    // scans to bind those variables before using the generator.
                    boolean dependsOnInnerScan = false;
                    for (Core.NamedPat freePat : gen.freePats) {
                      for (Core.Scan innerScan : innerScans) {
                        if (innerScan.pat.expand().contains(freePat)) {
                          dependsOnInnerScan = true;
                          break;
                        }
                      }
                    }

                    if (dependsOnInnerScan) {
                      // Replace the generator with one that includes inner
                      // scans
                      createJoinedGenerator(cache, pat, gen, innerScans);
                    } else {
                      // Check if remaining constraints only reference inner
                      // scans and bound patterns (by name, not identity). If
                      // they reference other extent patterns, skip
                      // createFilteredGenerator to avoid circular dependencies.
                      final Set<String> boundNames = new HashSet<>();
                      for (Core.NamedPat p : gen.pat.expand()) {
                        boundNames.add(p.name);
                      }
                      final Set<String> innerNames = new HashSet<>();
                      for (Core.Scan innerScan : innerScans) {
                        for (Core.NamedPat p : innerScan.pat.expand()) {
                          innerNames.add(p.name);
                        }
                      }
                      boolean referencesOtherExtent = false;
                      for (Core.Exp c : constraints2) {
                        for (Core.NamedPat fp : freePats(cache.typeSystem, c)) {
                          // Skip patterns bound in environment (functions, etc)
                          if (cache.env.getOpt(fp) != null) {
                            continue;
                          }
                          if (!boundNames.contains(fp.name)
                              && !innerNames.contains(fp.name)) {
                            referencesOtherExtent = true;
                            break;
                          }
                        }
                        if (referencesOtherExtent) {
                          break;
                        }
                      }
                      if (!referencesOtherExtent) {
                        createFilteredGenerator(
                            cache,
                            pat,
                            gen,
                            constraints2,
                            innerScans,
                            innerContext);
                      }
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

    // Add inner scans first (only if not already covered by the generator).
    // For multi-pattern scans (e.g., {bar, beer, price} in extent), we must
    // decompose and add separate extent scans only for uncovered patterns,
    // to avoid duplicating patterns that are already covered.
    for (Core.Scan scan : innerScans) {
      final List<Core.NamedPat> scanPats = scan.pat.expand();
      if (scanPats.size() == 1) {
        // Single-pattern scan: add if not covered
        if (!coveredByGenerator.contains(scanPats.get(0))) {
          fromBuilder.scan(scan.pat, scan.exp);
        }
      } else {
        // Multi-pattern scan: add separate extent scans for uncovered patterns
        for (Core.NamedPat scanPat : scanPats) {
          if (!coveredByGenerator.contains(scanPat)) {
            final Core.Exp extent =
                core.extent(
                    Pos.ZERO,
                    typeSystem,
                    scanPat.type,
                    ImmutableRangeSet.of(Range.all()));
            fromBuilder.scan(scanPat, extent);
          }
        }
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

    // Add the new joined generator. Use the FULL pattern so inner scan
    // variables are included in the generator's pattern. The Expander will
    // add these to 'done' and can create join conditions for subsequent
    // generators. IMPORTANT: Create a record pattern to match the record
    // we're yielding.
    final Core.Pat joinedPat = core.recordOrAtomPat(typeSystem, yieldPats);
    cache.add(new ExistsJoinGenerator(joinedPat, joinedFrom, freePats2));
  }

  /** Generator created from an exists pattern that joins inner scans. */
  static class ExistsJoinGenerator extends Generator {
    ExistsJoinGenerator(
        Core.Pat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats) {
      super(exp, freePats, pat, Cardinality.FINITE, true, false);
    }

    @Override
    Core.Exp simplify(TypeSystem typeSystem, Core.Pat pat, Core.Exp exp) {
      // The exists constraint is satisfied by this generator
      return exp;
    }
  }

  /**
   * Creates a generator that applies remaining constraints as a WHERE filter.
   *
   * <p>For example, if we have:
   *
   * <pre>{@code
   * from b where cheap b
   * }</pre>
   *
   * <p>Where {@code cheap} is defined as:
   *
   * <pre>{@code
   * fun cheap beer = exists bar1, price1, bar2, price2
   *   where sells(bar1, beer, price1) andalso sells(bar2, beer, price2)
   *     andalso price1 < 3 andalso price2 < 3 andalso bar1 <> bar2
   * }</pre>
   *
   * <p>The generator from {@code sells(bar1, beer, price1)} produces {@code
   * {bar1, beer, price1}} tuples. This method creates a filtered generator that
   * applies the remaining constraints:
   *
   * <pre>{@code
   * from {bar1, beer, price1} in barBeers
   *   where price1 < 3
   *     andalso exists {bar2, price2} in barBeers
   *       where beer = beer andalso price2 < 3 andalso bar1 <> bar2
   *   yield beer
   *   distinct
   * }</pre>
   */
  private static void createFilteredGenerator(
      Cache cache,
      Core.Pat pat,
      Generator sourceGen,
      List<Core.Exp> remainingConstraints,
      List<Core.Scan> innerScans,
      Context context) {
    final TypeSystem typeSystem = cache.typeSystem;
    final FromBuilder fromBuilder = core.fromBuilder(typeSystem);

    // Add the source generator scan
    fromBuilder.scan(sourceGen.pat, sourceGen.exp);

    // Filter out constraints that have been consumed by generator derivation
    // (e.g., function calls like par(x, y) that were inlined to create
    // generators). These are already encoded in the source generator's
    // expression and don't need to be re-checked.
    final List<Core.Exp> effectiveConstraints = new ArrayList<>();
    for (Core.Exp constraint : remainingConstraints) {
      if (!context.consumed.contains(constraint)) {
        effectiveConstraints.add(constraint);
      }
    }

    // Identify which constraints can be applied directly (those that only
    // reference variables from the source generator) vs those that need
    // to remain as exists subqueries (those that reference inner scan
    // variables not in the source generator).
    final Set<Core.NamedPat> boundPats = new HashSet<>(sourceGen.pat.expand());
    final List<Core.Exp> directConstraints = new ArrayList<>();
    final List<Core.Exp> existsConstraints = new ArrayList<>();

    for (Core.Exp constraint : effectiveConstraints) {
      final Set<Core.NamedPat> freeInConstraint =
          freePats(typeSystem, constraint);
      boolean needsExists = false;
      for (Core.NamedPat freePat : freeInConstraint) {
        if (!boundPats.contains(freePat)) {
          // This constraint references a variable not bound by the generator
          for (Core.Scan innerScan : innerScans) {
            if (innerScan.pat.expand().contains(freePat)) {
              needsExists = true;
              break;
            }
          }
        }
      }
      if (needsExists) {
        existsConstraints.add(constraint);
      } else {
        directConstraints.add(constraint);
      }
    }

    // Apply direct constraints as WHERE
    if (!directConstraints.isEmpty()) {
      fromBuilder.where(core.andAlso(typeSystem, directConstraints));
    }

    // If there are exists constraints, wrap them in an exists subquery.
    // Use the inner scans directly (they have the proper source collections).
    if (!existsConstraints.isEmpty()) {
      // Find which inner scans are needed for the exists constraints
      final Set<Core.NamedPat> neededPats = new HashSet<>();
      for (Core.Exp constraint : existsConstraints) {
        neededPats.addAll(freePats(typeSystem, constraint));
      }
      neededPats.removeAll(boundPats);

      // Build the exists subquery using inner scans that provide needed
      // patterns
      final FromBuilder existsBuilder = core.fromBuilder(typeSystem);
      for (Core.Scan innerScan : innerScans) {
        boolean scanNeeded = false;
        for (Core.NamedPat scanPat : innerScan.pat.expand()) {
          if (neededPats.contains(scanPat)) {
            scanNeeded = true;
            break;
          }
        }
        if (scanNeeded) {
          existsBuilder.scan(innerScan.pat, innerScan.exp);
        }
      }
      existsBuilder.where(core.andAlso(typeSystem, existsConstraints));
      final Core.From existsFrom = existsBuilder.build();

      // Add the exists check as a WHERE condition
      final Core.Exp existsCheck =
          core.apply(
              Pos.ZERO,
              PrimitiveType.BOOL,
              core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_NON_EMPTY),
              existsFrom);
      fromBuilder.where(existsCheck);
    }

    // Yield just the target pattern. We need to find the pattern within
    // sourceGen.pat that corresponds to pat (they may be different objects).
    Core.NamedPat yieldPat = null;
    final String patName = ((Core.NamedPat) pat).name;
    for (Core.NamedPat p : sourceGen.pat.expand()) {
      if (p.name.equals(patName)) {
        yieldPat = p;
        break;
      }
    }
    if (yieldPat == null) {
      // pat is not in sourceGen.pat - this shouldn't happen
      return;
    }
    fromBuilder.yield_(core.id(yieldPat));
    fromBuilder.distinct();

    final Core.From filteredFrom = fromBuilder.build();
    final Set<Core.NamedPat> freePats2 = freePats(typeSystem, filteredFrom);

    // Add the filtered generator. bestGenerator returns the last entry,
    // so this naturally supersedes any earlier generator for pat.
    cache.add(
        new ExistsFilterGenerator(
            (Core.NamedPat) pat, filteredFrom, freePats2));
  }

  /** Generator created from an exists pattern with remaining constraints. */
  static class ExistsFilterGenerator extends Generator {
    ExistsFilterGenerator(
        Core.NamedPat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats) {
      super(exp, freePats, pat, Cardinality.FINITE, true, false);
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
   * @param context Context containing boolean constraints that must be
   *     satisfied
   * @return true if a generator was created, false otherwise
   */
  static boolean maybeFunction(
      Cache cache, Core.Pat goalPat, boolean ordered, Context context) {
    boolean anySuccess = false;
    for (Core.Exp constraint : context.constraints) {
      // Step 0: Handle CASE expression (from tuple pattern matching)
      // This occurs when a tuple-parameter function like f(x, y) is compiled
      // to: case (arg1, arg2) of (x, y) => body
      if (constraint.op == Op.CASE) {
        final Core.Case caseExp = (Core.Case) constraint;
        if (caseExp.matchList.size() == 1) {
          final Core.Match match = caseExp.matchList.get(0);

          // Step 0a: Handle constructor patterns (CON_PAT and CON0_PAT)
          // For "case e of INL n => body", generate values for n, wrap with INL
          if (match.pat.op == Op.CON_PAT) {
            if (maybeConPat(cache, goalPat, ordered, match)) {
              return true;
            }
            continue;
          }
          if (match.pat.op == Op.CON0_PAT) {
            if (maybeCon0Pat(cache, goalPat, ordered, match)) {
              return true;
            }
            continue;
          }

          final Core.Exp inlinedBody =
              substituteArgs(
                  cache.typeSystem,
                  cache.env,
                  match.pat,
                  caseExp.exp,
                  match.exp);
          // Decompose "andalso" into individual conjuncts for range detection.
          if (maybeGenerator(
              cache,
              goalPat,
              ordered,
              new Context(core.decomposeAnd(inlinedBody)))) {
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
            cache,
            goalPat,
            ordered,
            new Context(core.decomposeAnd(inlinedBody)))) {
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
      //
      // Do not return early here: the inlined body may produce generators
      // for sub-patterns of goalPat (e.g., field components of a tuple).
      // Continue processing remaining constraints so that all components
      // can be covered, allowing deriveFieldGenerators to reconstruct the
      // full tuple.
      final Core.Exp inlinedBody =
          inlineFunctionBody(cache.typeSystem, cache.env, fn, apply.arg);
      final Core.Exp safeBody =
          containsSelfCall(fn.exp, fnName)
              ? removeRecursiveBranches(cache.typeSystem, inlinedBody, fnName)
              : inlinedBody;
      if (safeBody != null) {
        // Decompose "andalso" into individual conjuncts for range detection.
        final List<Core.Exp> innerConstraints = core.decomposeAnd(safeBody);
        final Context innerContext = new Context(innerConstraints);
        if (maybeGenerator(cache, goalPat, ordered, innerContext)) {
          anySuccess = true;
          context.consumed.add(constraint);
          // If goalPat now has a finite generator, stop. Processing
          // further constraints might create conflicting generators that
          // replace the current best without preserving the constraints
          // already marked as satisfied. Continue only when goalPat
          // is a NamedPat that still has no finite generator (e.g.,
          // because only field sub-patterns have been found so far,
          // and deriveFieldGenerators needs more fields).
          final Generator bestGen = cache.bestGeneratorForPat(goalPat);
          if (bestGen != null
              && bestGen.cardinality != Generator.Cardinality.INFINITE) {
            // If the generator fully encodes the function body,
            // add the function-call constraint to its provenance.
            // This allows expandFrom2 to remove the redundant
            // function-call conjunct from WHERE.
            //
            // Only safe when the generator's pattern matches goalPat
            // exactly (no extra patterns). If the generator has extra
            // patterns (e.g., pat = (p, bar) but goalPat = p), those
            // extra patterns may be projected away when the generator
            // is used in a nested expansion, making the provenance
            // entry invalid.
            final Set<Core.NamedPat> goalPats = new HashSet<>(goalPat.expand());
            if (bestGen.sealed
                && goalPats.containsAll(bestGen.pat.expand())
                && coversAll(
                    innerConstraints,
                    bestGen.provenance,
                    innerContext.consumed)) {
              bestGen.provenance.add(constraint);
            }
            return true;
          }
        }
      }
    }
    return anySuccess;
  }

  /** Whether every constraint is in {@code provenance} or {@code consumed}. */
  private static boolean coversAll(
      List<Core.Exp> constraints,
      Set<Core.Exp> provenance,
      Set<Core.Exp> consumed) {
    for (Core.Exp c : constraints) {
      if (!provenance.contains(c) && !consumed.contains(c)) {
        return false;
      }
    }
    return true;
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

    // Extract intermediate var and where clause.
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

    // Extract all intermediate vars (existential SCAN patterns) and the
    // single WHERE clause from the existential's from-expression.
    final List<Core.Pat> intermediateVars = new ArrayList<>();
    Core.Exp whereClause = null;
    for (Core.FromStep step : existsFrom.steps) {
      if (step.op == Op.SCAN) {
        intermediateVars.add(((Core.Scan) step).pat);
      } else if (step.op == Op.WHERE) {
        whereClause = ((Core.Where) step).exp;
      }
    }

    if (intermediateVars.isEmpty() || whereClause == null) {
      return null;
    }

    // Decompose where clause into step predicates and recursive call
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

    return new TransitiveClosurePattern(
        fnName,
        formalParams,
        baseCase,
        intermediateVars,
        stepPredicates,
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
    final Context baseContext = new Context(ImmutableList.of(substitutedBase));
    if (!maybeGenerator(baseCache, goalPat, ordered, baseContext)) {
      return null;
    }
    final Generator baseGenerator =
        requireNonNull(baseCache.bestGeneratorForPat(goalPat));

    // For path-like patterns the recursive call carries at least one formal
    // parameter through (e.g. path(z, y) keeps y; odd_path(x, v0) keeps x), so
    // we generate a path-extension iterate. For cousin-like patterns where all
    // recursive-call args are existentials (e.g. cousin(xp, yp)), we generate
    // a multi-step iterate that walks the relation in parallel along each
    // formal's chain.
    if (carriesFormalThrough(pattern)) {
      return buildRelationalIterate(cache, baseGenerator, goalPat, ordered);
    }
    return buildBidirectionalIterate(
        cache, pattern, baseGenerator, goalPat, ordered);
  }

  /**
   * Returns whether the recursive call has at least one argument that directly
   * references one of the formal parameters (i.e. a formal "carries through"
   * the recursion). The reference can be direct, e.g. {@code path(z, y)} uses
   * formal {@code y}; or indirect via a record selector or other
   * sub-expression, e.g. {@code path(#1 p, z)} references formal {@code p}. A
   * purely existential call like {@code cousin(xp, yp)} returns false.
   */
  private static boolean carriesFormalThrough(
      TransitiveClosurePattern pattern) {
    final Set<String> formalNames = new HashSet<>();
    for (Core.NamedPat np : pattern.formalParams.expand()) {
      formalNames.add(np.name);
    }
    final AtomicBoolean found = new AtomicBoolean(false);
    final Visitor visitor =
        new Visitor() {
          @Override
          protected void visit(Core.Id id) {
            if (formalNames.contains(id.idPat.name)) {
              found.set(true);
            }
          }
        };
    for (Core.Exp arg : flattenTupleExp(pattern.recursiveCall.arg)) {
      arg.accept(visitor);
    }
    return found.get();
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
   * Builds an {@code iterate} expression for fixed-point computation when the
   * recursive call uses no formal parameters directly (i.e. every formal is
   * "rebound" through its own step predicate).
   *
   * <p>For example, given
   *
   * <pre>{@code
   * fun cousin (x, y) =
   *   sib (x, y)
   *   orelse exists xp, yp where par(x, xp)
   *                       andalso par(y, yp)
   *                       andalso cousin(xp, yp);
   * }</pre>
   *
   * <p>generates the equivalent of
   *
   * <pre>{@code
   * Relational.iterate sib
   *   (fn (allCousins, newCousins) =>
   *     from prev in newCousins,
   *          x in <par inverted on column 2 = #1 prev>,
   *          y in <par inverted on column 2 = #2 prev>
   *       yield (x, y))
   * }</pre>
   */
  private static Core.@Nullable Exp buildBidirectionalIterate(
      Cache cache,
      TransitiveClosurePattern pattern,
      Generator baseGenerator,
      Core.Pat goalPat,
      boolean ordered) {
    final TypeSystem ts = cache.typeSystem;
    final Type baseElementType = baseGenerator.exp.type.elementType();

    // Get the original collection type by unwrapping any #fromList Bag wrapper
    // that was added by core.withOrdered when ordered=false
    final Type originalCollectionType;
    if (baseGenerator.exp.isCallTo(BuiltIn.BAG_FROM_LIST)) {
      originalCollectionType = ((Core.Apply) baseGenerator.exp).arg.type;
    } else {
      originalCollectionType = baseGenerator.exp.type;
    }

    final Type resultElementType = goalPat.type;
    final Type resultCollectionType =
        originalCollectionType instanceof ListType
            ? ts.listType(resultElementType)
            : ts.bagType(resultElementType);

    final Core.IdPat allPaths = core.idPat(resultCollectionType, "allPaths", 0);
    final Core.IdPat newPaths = core.idPat(resultCollectionType, "newPaths", 0);

    final Environment env =
        cache.env.bindAll(
            ImmutableList.of(Binding.of(allPaths), Binding.of(newPaths)));
    final FromBuilder fb = core.fromBuilder(ts, env);

    // Scan prev in newPaths
    final Core.IdPat prevPat = core.idPat(resultElementType, "prev", 0);
    fb.scan(prevPat, core.id(newPaths));
    final Core.Exp prevId = core.id(prevPat);

    // Build substitution map: each recursive-call arg a_i is mapped to #i prev.
    // Carry-through formals (a_i = formal x_j) and existentials are both mapped
    // through prev fields.
    final List<Core.Exp> recArgs = flattenTupleExp(pattern.recursiveCall.arg);
    final Map<Core.NamedPat, Core.Exp> subs = new LinkedHashMap<>();
    for (int i = 0; i < recArgs.size(); i++) {
      final Core.Exp a = recArgs.get(i);
      if (a.op == Op.ID) {
        subs.put(((Core.Id) a).idPat, core.field(ts, prevId, i));
      }
    }

    // For each formal, decide where its value comes from and add scans for
    // rebound formals.
    final Environment stepEnv = env.bind(Binding.of(prevPat));
    final List<? extends Core.NamedPat> formals = pattern.formalParams.expand();
    final Map<String, Core.Exp> formalValue = new LinkedHashMap<>();

    for (Core.NamedPat formal : formals) {
      final Core.Exp sub = subs.get(formal);
      if (sub != null) {
        // Carry-through: value is the substituted prev field
        formalValue.put(formal.name, sub);
        continue;
      }

      // Rebound: find a step predicate that mentions this formal, and invert
      // it manually by inlining the function and projecting from its
      // collection.
      Core.Exp matchingPred = null;
      for (Core.Exp pred : pattern.stepPredicates) {
        if (containsRef(pred, formal)) {
          matchingPred = pred;
          break;
        }
      }
      if (matchingPred == null) {
        return null;
      }

      final Core.Exp substitutedPred =
          Replacer.substitute(ts, stepEnv, subs, matchingPred);

      if (substitutedPred.op != Op.APPLY) {
        return null;
      }
      final Core.Apply applyPred = (Core.Apply) substitutedPred;
      if (applyPred.fn.op != Op.ID) {
        return null;
      }
      final String fnName = ((Core.Id) applyPred.fn).idPat.name;
      final Binding binding = stepEnv.getTop(fnName);
      if (binding == null || binding.exp == null || binding.exp.op != Op.FN) {
        return null;
      }
      final Core.Fn predFn = (Core.Fn) binding.exp;
      final Core.Exp inlined =
          inlineFunctionBody(ts, stepEnv, predFn, applyPred.arg);

      if (!inlined.isCallTo(BuiltIn.OP_ELEM)) {
        return null;
      }
      final Core.Apply elemCall = (Core.Apply) inlined;
      final Core.Exp tupleExpr = elemCall.arg(0);
      final Core.Exp collection = elemCall.arg(1);
      final List<Core.Exp> tupleArgs = flattenTupleExp(tupleExpr);

      // For each component of the inlined tuple, classify as bound (filter)
      // or as the rebound formal (yield).
      final List<Core.Pat> scanPats = new ArrayList<>();
      final List<Core.Exp> filterEqs = new ArrayList<>();
      Core.NamedPat formalScanPat = null;
      for (int i = 0; i < tupleArgs.size(); i++) {
        final Core.Exp comp = tupleArgs.get(i);
        if (comp.op == Op.ID
            && ((Core.Id) comp).idPat.name.equals(formal.name)) {
          scanPats.add(formal);
          formalScanPat = formal;
        } else {
          final Core.IdPat freshPat =
              core.idPat(comp.type, formal.name + "$f" + i, 0);
          scanPats.add(freshPat);
          filterEqs.add(core.equal(ts, core.id(freshPat), comp));
        }
      }
      if (formalScanPat == null) {
        return null;
      }
      final Core.Pat scanTuplePat;
      if (scanPats.size() == 1) {
        scanTuplePat = scanPats.get(0);
      } else {
        scanTuplePat = core.tuplePat(ts, ImmutableList.copyOf(scanPats));
      }

      // Wrap the inversion in its own from-expression so the surrounding
      // builder sees a single yield of the formal's value.
      final FromBuilder invFb = core.fromBuilder(ts, stepEnv);
      invFb.scan(scanTuplePat, collection);
      for (Core.Exp eq : filterEqs) {
        invFb.where(eq);
      }
      invFb.yield_(core.id(formalScanPat));
      final Core.Exp formalGen = invFb.build();

      fb.scan(formal, formalGen);
      formalValue.put(formal.name, core.id(formal));
    }

    // Yield: tuple of formal values in declaration order
    final List<Core.Exp> yieldExps = new ArrayList<>();
    for (Core.NamedPat formal : formals) {
      yieldExps.add(formalValue.get(formal.name));
    }
    fb.yield_(
        CoreBuilder.core.tuple((RecordLikeType) resultElementType, yieldExps));
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

    // Convert base generator to result type if needed (same as path-extension)
    final Core.Exp seedExp;
    if (baseElementType.equals(resultElementType)) {
      seedExp = baseGenerator.exp;
    } else {
      final FromBuilder seedFb = core.fromBuilder(ts);
      final Core.IdPat seedPat = core.idPat(baseElementType, "e", 0);
      seedFb.scan(seedPat, baseGenerator.exp);
      final Core.Exp seedId = core.id(seedPat);
      final List<Core.Exp> seedFields = new ArrayList<>();
      for (int i = 0; i < formals.size(); i++) {
        seedFields.add(core.field(ts, seedId, i));
      }
      seedFb.yield_(core.tuple((RecordLikeType) resultElementType, seedFields));
      seedExp = seedFb.build();
    }

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
    final Context baseContext = new Context(ImmutableList.of(substitutedBase));
    if (!maybeGenerator(baseCache, goalPat, ordered, baseContext)) {
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
    final Context stepContext = new Context(ImmutableList.of(substitutedStep));
    if (!maybeGenerator(stepCache, stepGoalPat, ordered, stepContext)) {
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
    if (!exp.isCallTo(BuiltIn.OP_MINUS)
        && !exp.isCallTo(BuiltIn.INT_OP_MINUS)) {
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
      super(exp, freePats, pat, Cardinality.FINITE, true, false);
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
    /**
     * The intermediate variables from exists (z, or xp/yp). For the simple
     * path-extension pattern there is one; for patterns like cousin there can
     * be several.
     */
    final List<Core.Pat> intermediateVars;
    /**
     * The step predicates (e.g., edge(x, z) or par(x, xp) and par(y, yp)).
     * Their conjunction together with the recursive call forms the body of the
     * existential.
     */
    final List<Core.Exp> stepPredicates;
    /** The recursive call (e.g., path(z, y) or cousin(xp, yp)). */
    final Core.Apply recursiveCall;
    /** The actual arguments at the call site. */
    final Core.Exp callArgs;

    TransitiveClosurePattern(
        String fnName,
        Core.Pat formalParams,
        Core.Exp baseCase,
        List<Core.Pat> intermediateVars,
        List<Core.Exp> stepPredicates,
        Core.Apply recursiveCall,
        Core.Exp callArgs) {
      this.fnName = requireNonNull(fnName);
      this.formalParams = requireNonNull(formalParams);
      this.baseCase = requireNonNull(baseCase);
      this.intermediateVars = ImmutableList.copyOf(intermediateVars);
      this.stepPredicates = ImmutableList.copyOf(stepPredicates);
      this.recursiveCall = requireNonNull(recursiveCall);
      this.callArgs = requireNonNull(callArgs);
    }

    /** Convenience accessor — the first intermediate variable. */
    Core.Pat intermediateVar() {
      return intermediateVars.get(0);
    }

    /** Convenience accessor — the conjunction of all step predicates. */
    Core.Exp stepPredicate(TypeSystem ts) {
      return CoreBuilder.core.andAlso(ts, stepPredicates);
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
      super(exp, freePats, pat, Cardinality.FINITE, true, false);
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
      Cache cache,
      boolean ordered,
      List<Generator> generators,
      Core.@Nullable Exp constraint) {
    // If every branch is a RangeGenerator or PointGenerator, merge their
    // range constructors into one generator. When the ctors are provably
    // disjoint (over int literal endpoints), emit the flatten-based shape
    // that matches generateRange and user-written [k..n] syntax. When we
    // can't prove disjointness (overlapping ranges, tuple endpoints,
    // variable endpoints), fall back to Range.discreteSetOf — which has
    // set semantics and so handles deduplication.
    if (generators.stream()
        .allMatch(
            g -> g instanceof RangeGenerator || g instanceof PointGenerator)) {
      final Generator firstGen = generators.get(0);
      final TypeSystem typeSystem = cache.typeSystem;
      final Type type = firstGen.pat.type;
      final Type rangeType = typeSystem.range(type);
      final List<Core.Apply> rangeExps =
          generators.stream()
              .flatMap(g -> g.rangeExp(typeSystem).stream())
              .collect(ImmutableList.toImmutableList());
      final Core.Exp rangeListExp = core.list(typeSystem, rangeType, rangeExps);
      final boolean disjoint = rangesAreDisjointNumericLiterals(rangeExps);
      final Core.Exp mergedExp;
      if (disjoint) {
        final Core.Apply flattenExp =
            core.call(
                typeSystem,
                BuiltIn.RANGE_FLATTEN,
                type,
                Pos.ZERO,
                rangeListExp);
        mergedExp =
            ordered
                ? flattenExp
                : core.call(
                    typeSystem,
                    BuiltIn.BAG_FROM_LIST,
                    type,
                    Pos.ZERO,
                    flattenExp);
      } else {
        final Core.Apply discreteSetExp =
            core.call(
                typeSystem,
                BuiltIn.RANGE_DISCRETE_SET_OF,
                type,
                Pos.ZERO,
                rangeListExp);
        final BuiltIn toListOrBag =
            ordered
                ? BuiltIn.RANGE_DISCRETE_SET_TO_LIST
                : BuiltIn.RANGE_DISCRETE_SET_TO_BAG;
        mergedExp =
            core.call(typeSystem, toListOrBag, type, Pos.ZERO, discreteSetExp);
      }
      final Core.Exp simplified = Simplifier.simplify(typeSystem, mergedExp);
      final Set<Core.NamedPat> freePats = freePats(typeSystem, simplified);
      final ImmutableSet<Core.Exp> mergedProvenance =
          constraint != null ? ImmutableSet.of(constraint) : ImmutableSet.of();
      return cache.add(
          new RangeGenerator(
              (Core.NamedPat) firstGen.pat,
              simplified,
              freePats,
              rangeExps,
              mergedProvenance));
    }
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

  /**
   * Attempts to invert a case expression with multiple arms into a generator.
   *
   * <p>Transforms a case expression like:
   *
   * <pre>{@code
   * case x of p1 => e1 | p2 => e2 | ... | _ => false
   * }</pre>
   *
   * <p>into an orelse of constraints, one per arm. Each arm's constraint is:
   *
   * <ul>
   *   <li>For literal patterns: {@code (x = literal) andalso body}
   *   <li>For constructor patterns: a single-arm case for maybeFunction
   *   <li>For id patterns: the body with subject substituted for the variable
   * </ul>
   *
   * <p>Exclusion constraints are added for earlier arms that returned false.
   */
  static boolean maybeCase(
      Cache cache, Core.Pat pat, boolean ordered, Context context) {
    for (Core.Exp constraint : context.constraints) {
      if (constraint.op != Op.CASE) {
        continue;
      }
      final Core.Case caseExp = (Core.Case) constraint;

      // Only handle case expressions with boolean result type
      if (caseExp.type != PrimitiveType.BOOL) {
        continue;
      }

      // Need at least 2 arms to be interesting
      if (caseExp.matchList.size() < 2) {
        continue;
      }

      // Collect literal values from arms that return false for exclusion
      final List<Core.Exp> excludeValues = new ArrayList<>();

      // Build an orelse of constraints for each arm
      final List<Core.Exp> branches = new ArrayList<>();
      for (Core.Match match : caseExp.matchList) {
        // Skip wildcard and id patterns at top level
        if (match.pat.op == Op.WILDCARD_PAT) {
          continue;
        }
        if (match.pat.op == Op.ID_PAT) {
          if (!match.exp.isBoolLiteral(false)) {
            Core.Exp substituted =
                substituteArgs(
                    cache.typeSystem,
                    cache.env,
                    match.pat,
                    caseExp.exp,
                    match.exp);
            // Apply exclusion constraints for earlier false-returning patterns
            // Use "not (x = v)" rather than "x <> v" for set-minus semantics
            for (Core.Exp excludeValue : excludeValues) {
              final Core.Exp eq =
                  core.equal(cache.typeSystem, caseExp.exp, excludeValue);
              final Core.Exp notEq =
                  core.call(cache.typeSystem, BuiltIn.BOOL_NOT, eq);
              substituted = core.andAlso(cache.typeSystem, substituted, notEq);
            }
            branches.add(substituted);
          }
          continue;
        }

        // If this arm returns false, collect literal value for exclusion
        if (match.exp.isBoolLiteral(false)) {
          final Core.Exp literalValue = patternToLiteral(match.pat);
          if (literalValue != null) {
            excludeValues.add(literalValue);
          }
          continue;
        }

        // Create constraint for this arm
        final Core.Exp armConstraint =
            createArmConstraint(
                cache.typeSystem, cache.env, caseExp.exp, match, caseExp.pos);
        if (armConstraint == null) {
          continue;
        }

        // Apply exclusion constraints for earlier false-returning patterns
        // Use "not (x = v)" rather than "x <> v" for set-minus semantics
        Core.Exp branchExp = armConstraint;
        for (Core.Exp excludeValue : excludeValues) {
          final Core.Exp eq =
              core.equal(cache.typeSystem, caseExp.exp, excludeValue);
          final Core.Exp notEq =
              core.call(cache.typeSystem, BuiltIn.BOOL_NOT, eq);
          branchExp = core.andAlso(cache.typeSystem, branchExp, notEq);
        }

        branches.add(branchExp);

        // Add literal value to exclusions if this is a constant pattern
        final Core.Exp literalValue = patternToLiteral(match.pat);
        if (literalValue != null) {
          excludeValues.add(literalValue);
        }
      }

      if (branches.isEmpty()) {
        continue;
      }

      // Combine branches with orelse and delegate to maybeGenerator
      final Core.Exp orElseExp = core.orElse(cache.typeSystem, branches);
      final List<Core.Exp> newConstraints =
          ImmutableList.<Core.Exp>builder()
              .add(orElseExp)
              .addAll(
                  context.constraints.stream()
                      .filter(c -> c != constraint)
                      .collect(ImmutableList.toImmutableList()))
              .build();
      return maybeGenerator(cache, pat, ordered, new Context(newConstraints));
    }
    return false;
  }

  /** Converts a literal pattern to its corresponding literal expression. */
  private static Core.@Nullable Exp patternToLiteral(Core.Pat pat) {
    if (!(pat instanceof Core.LiteralPat)) {
      return null;
    }
    final Core.LiteralPat literalPat = (Core.LiteralPat) pat;
    if (!(literalPat.type instanceof PrimitiveType)) {
      return null;
    }
    return core.literal((PrimitiveType) literalPat.type, literalPat.value);
  }

  /**
   * Converts a pattern to an expression that references the pattern's
   * variables.
   *
   * <p>For example, converts pattern {@code (x, y)} to expression {@code (x,
   * y)}.
   */
  private static Core.Exp patToExp(TypeSystem ts, Core.Pat pat) {
    if (pat instanceof Core.IdPat) {
      return core.id((Core.IdPat) pat);
    }
    if (pat instanceof Core.TuplePat) {
      final Core.TuplePat tuplePat = (Core.TuplePat) pat;
      final List<Core.Exp> args = new ArrayList<>();
      for (Core.Pat arg : tuplePat.args) {
        args.add(patToExp(ts, arg));
      }
      return core.tuple(tuplePat.type(), args);
    }
    if (pat instanceof Core.RecordPat) {
      final Core.RecordPat recordPat = (Core.RecordPat) pat;
      final Map<String, Core.Exp> expArgs = new LinkedHashMap<>();
      final List<String> names =
          new ArrayList<>(recordPat.type().argNameTypes.keySet());
      for (int i = 0; i < recordPat.args.size(); i++) {
        expArgs.put(names.get(i), patToExp(ts, recordPat.args.get(i)));
      }
      return core.record(ts, expArgs.entrySet());
    }
    throw new AssertionError("unexpected pattern type: " + pat.op);
  }

  /** Creates a constraint expression for a case arm. */
  private static Core.@Nullable Exp createArmConstraint(
      TypeSystem typeSystem,
      Environment env,
      Core.Exp subject,
      Core.Match match,
      Pos pos) {
    final Core.Pat pat = match.pat;
    final Core.Exp body = match.exp;

    switch (pat.op) {
      case BOOL_LITERAL_PAT:
      case CHAR_LITERAL_PAT:
      case INT_LITERAL_PAT:
      case REAL_LITERAL_PAT:
      case STRING_LITERAL_PAT:
        // Literal pattern: (subject = literal) andalso body
        final Core.Exp literal = patternToLiteral(pat);
        if (literal == null) {
          return null;
        }
        final Core.Exp eq = core.equal(typeSystem, subject, literal);
        if (body.isBoolLiteral(true)) {
          return eq;
        }
        return core.andAlso(typeSystem, eq, body);

      case CON0_PAT:
      case CON_PAT:
        // Constructor pattern: create single-arm case for maybeFunction
        return core.caseOf(
            pos, PrimitiveType.BOOL, subject, ImmutableList.of(match));

      case ID_PAT:
        // Variable pattern: substitute subject for variable in body
        return substituteArgs(typeSystem, env, pat, subject, body);

      default:
        return null;
    }
  }

  /**
   * Handles a constructor pattern with a payload (CON_PAT).
   *
   * <p>For a pattern like {@code INL n => n >= 5 andalso n <= 8}, this method:
   *
   * <ol>
   *   <li>Recursively generates values for the inner pattern {@code n}
   *   <li>Wraps each generated value with the constructor {@code INL}
   * </ol>
   *
   * @param cache The generator cache
   * @param goalPat The pattern we're generating values for (e.g., {@code e})
   * @param ordered Whether to generate a list (true) or bag (false)
   * @param match The case arm with a CON_PAT pattern
   * @return true if a generator was created, false otherwise
   */
  private static boolean maybeConPat(
      Cache cache, Core.Pat goalPat, boolean ordered, Core.Match match) {
    final TypeSystem ts = cache.typeSystem;
    final Core.ConPat conPat = (Core.ConPat) match.pat;
    final Core.Pat innerPat = conPat.pat;
    final Core.Exp body = match.exp;

    // Build the constructor application: CON(value)
    // The constructor type is: innerType -> dataType
    final DataType dataType = (DataType) conPat.type;
    final Type innerType = innerPat.type;
    final FnType conFnType = ts.fnType(innerType, dataType);
    final Core.IdPat conIdPat = core.idPat(conFnType, conPat.tyCon, 0);
    final Core.Id conId = core.id(conIdPat);

    // Handle literal patterns: INL 0 => true
    // For literals, generate a single value: CON(literal)
    if (innerPat instanceof Core.LiteralPat) {
      final Core.LiteralPat literalPat = (Core.LiteralPat) innerPat;
      // Body must be true for a literal pattern to generate a value
      if (!body.isBoolLiteral(true)) {
        return false;
      }
      // Convert the literal pattern to a literal expression
      final Core.Exp literalExp = patternToLiteral(literalPat);
      if (literalExp == null) {
        return false;
      }
      // Create CON(literal)
      final Core.Exp conLiteralExp =
          core.apply(Pos.ZERO, dataType, conId, literalExp);
      // Create a point generator for this single value
      PointGenerator.create(
          cache, goalPat, ordered, conLiteralExp, ImmutableSet.of());
      return true;
    }

    // Handle IdPat or TuplePat: INL n => constraint, INR (b, i) => constraint
    // Build wrapper: from innerPat in extent where body yield CON(innerPat)
    // The body (constraint) filters the extent values, then wraps with the
    // constructor. The Expander will use maybeGenerator to convert infinite
    // extents to finite generators based on the constraints.
    final FromBuilder fb = core.fromBuilder(ts);

    // Add scans for all component patterns
    for (Core.NamedPat p : innerPat.expand()) {
      final Core.Exp extentExp =
          core.extent(Pos.ZERO, ts, p.type, ImmutableRangeSet.of(Range.all()));
      fb.scan(p, extentExp);
    }

    if (!body.isBoolLiteral(true)) {
      fb.where(body);
    }

    // Create the yield expression: CON(innerPat)
    final Core.Exp innerPatRef = patToExp(ts, innerPat);
    final Core.Exp yieldExp =
        core.apply(Pos.ZERO, dataType, conId, innerPatRef);
    fb.yield_(yieldExp);

    final Core.Exp wrapperExp = fb.build();

    // Create a generator for goalPat
    CollectionGenerator.create(
        cache, ordered, goalPat, wrapperExp, ImmutableSet.of());
    return true;
  }

  /**
   * Handles a zero-argument constructor pattern (CON0_PAT).
   *
   * <p>For a pattern like {@code NONE => true}, this generates a single value
   * (the constructor constant) for the goal pattern.
   *
   * @param cache The generator cache
   * @param goalPat The pattern we're generating values for
   * @param ordered Whether to generate a list (true) or bag (false)
   * @param match The case arm with a CON0_PAT pattern
   * @return true if a generator was created, false otherwise
   */
  private static boolean maybeCon0Pat(
      Cache cache, Core.Pat goalPat, boolean ordered, Core.Match match) {
    final TypeSystem ts = cache.typeSystem;
    final Core.Con0Pat con0Pat = (Core.Con0Pat) match.pat;
    final Core.Exp body = match.exp;

    // Only handle if body is true (unconditional)
    if (!body.isBoolLiteral(true)) {
      return false;
    }

    // Create the constructor constant value
    // For Con0Pat, the value is just the constructor name wrapped in a list
    final DataType dataType = (DataType) con0Pat.type;
    final Core.IdPat conIdPat = core.idPat(dataType, con0Pat.tyCon, 0);
    final Core.Id conValue = core.id(conIdPat);

    // Create a point generator for this single value
    PointGenerator.create(cache, goalPat, ordered, conValue, ImmutableSet.of());
    return true;
  }

  static boolean maybeUnion(
      Cache cache, Core.Pat pat, boolean ordered, Context context) {
    next_constraint:
    for (Core.Exp constraint : context.constraints) {
      if (constraint.isCallTo(BuiltIn.Z_ORELSE)) {
        final List<Generator> generators = new ArrayList<>();

        // Save generator count before trying branches.
        // If any branch fails, we need to clean up generators from successful
        // branches so they don't leak into the cache.
        final int initialCount =
            cache.generators.get((Core.NamedPat) pat).size();

        for (Core.Exp exp : core.decomposeOr(constraint)) {
          if (!maybeGenerator(
              cache, pat, ordered, new Context(core.decomposeAnd(exp)))) {
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
        generateUnion(cache, ordered, generators, constraint);
        return true;
      }
    }
    return false;
  }

  /**
   * The bound value and strictness derived from a single source constraint.
   * {@code source} is the constraint that yielded this bound; it is added to
   * the generator's provenance so it can be dropped from the surrounding {@code
   * where} clause.
   */
  static class Bound {
    final Core.Exp value;
    final boolean strict;
    final Core.Exp source;

    Bound(Core.Exp value, boolean strict, Core.Exp source) {
      this.value = value;
      this.strict = strict;
      this.source = source;
    }
  }

  /**
   * If {@code constraint} is {@code Range.contains range pat}, returns the
   * range constructor application (e.g. {@code AT_LEAST a} or {@code CLOSED (a,
   * b)}); otherwise returns null.
   */
  private static Core.@Nullable Apply rangeContainsArg(
      Core.Exp constraint, Core.Pat pat) {
    if (constraint.op != Op.APPLY) {
      return null;
    }
    final Core.Apply apply = (Core.Apply) constraint;
    if (!apply.fn.isCallTo(BuiltIn.RANGE_CONTAINS)
        || !references(apply.arg, pat)) {
      return null;
    }
    final Core.Exp range = ((Core.Apply) apply.fn).arg;
    return range instanceof Core.Apply
            && ((Core.Apply) range).fn instanceof Core.Id
        ? (Core.Apply) range
        : null;
  }

  /** Returns the range constructor of a range application, or null. */
  private static BuiltIn.@Nullable Constructor rangeConstructor(
      Core.Apply range) {
    return BuiltIn.Constructor.forName(((Core.Id) range.fn).idPat.name);
  }

  /**
   * Returns whether {@code range} is a range constructor application bounded on
   * both ends (e.g. {@code CLOSED (a, b)}), and therefore finite (and
   * enumerable, for a discrete element type). Infinite constructors such as
   * {@code AT_LEAST a} and {@code ALL} return false.
   */
  private static boolean isFiniteRange(Core.Exp range) {
    if (!(range instanceof Core.Apply)) {
      return false;
    }
    final Core.Apply ctor = (Core.Apply) range;
    if (!(ctor.fn instanceof Core.Id)) {
      return false;
    }
    final BuiltIn.Constructor c =
        BuiltIn.Constructor.forName(((Core.Id) ctor.fn).idPat.name);
    if (c == null) {
      return false;
    }
    switch (c) {
      case RANGE_CLOSED:
      case RANGE_CLOSED_OPEN:
      case RANGE_OPEN:
      case RANGE_OPEN_CLOSED:
        return true;
      default:
        return false;
    }
  }

  /** Returns whether a constraint is a comparison bound on {@code pat}. */
  private static boolean isBoundConstraint(Core.Exp constraint, Core.Pat pat) {
    switch (constraint.builtIn()) {
      case OP_GT:
      case OP_GE:
      case OP_LT:
      case OP_LE:
        // Check direct reference (e.g., "x > y") and offset
        // expressions (e.g., "x < y + 5" where y is the pattern).
        return references(constraint.arg(0), pat)
            || references(constraint.arg(1), pat)
            || extractOffset(constraint.arg(0), pat) != null
            || extractOffset(constraint.arg(1), pat) != null;
      case CHAR_OP_GT:
      case CHAR_OP_GE:
      case CHAR_OP_LT:
      case CHAR_OP_LE:
        return references(constraint.arg(0), pat)
            || references(constraint.arg(1), pat);
      default:
        // 'Range.contains (AT_LEAST a) pat' (and the other one-sided ranges)
        // is a comparison bound; a two-sided range is handled as a generator,
        // not a bound.
        final Core.Apply range = rangeContainsArg(constraint, pat);
        if (range != null) {
          final BuiltIn.Constructor c = rangeConstructor(range);
          return c == BuiltIn.Constructor.RANGE_AT_LEAST
              || c == BuiltIn.Constructor.RANGE_AT_MOST
              || c == BuiltIn.Constructor.RANGE_GREATER_THAN
              || c == BuiltIn.Constructor.RANGE_LESS_THAN;
        }
        return false;
    }
  }

  /**
   * Returns whether the given list of range constructor applications (each one
   * a {@code CTOR(args)} Apply expression) is pairwise disjoint, given that
   * every endpoint is an int or real literal. Returns false conservatively if
   * any endpoint is not a numeric literal (e.g. tuples, variables, char).
   *
   * <p>Adjacent-but-touching ranges (e.g. {@code CLOSED (1, 5)} and {@code
   * CLOSED (5, 10)}) are NOT considered disjoint, because the point 5 belongs
   * to both. {@code CLOSED (1, 5)} and {@code OPEN (5, 10)} are disjoint (5
   * belongs only to the first).
   */
  private static boolean rangesAreDisjointNumericLiterals(
      List<Core.Apply> rangeExps) {
    final List<RangeEndpoints> endpoints = new ArrayList<>(rangeExps.size());
    for (Core.Apply rangeExp : rangeExps) {
      final RangeEndpoints e = extractNumericLiteralEndpoints(rangeExp);
      if (e == null) {
        return false;
      }
      endpoints.add(e);
    }
    // Sort by low endpoint (-inf first; ties broken by closed-before-open).
    endpoints.sort(RangeEndpoints::compareByLow);
    // Pairwise check.
    for (int i = 0; i + 1 < endpoints.size(); i++) {
      final RangeEndpoints a = endpoints.get(i);
      final RangeEndpoints b = endpoints.get(i + 1);
      if (!a.isStrictlyBelow(b)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Extracts (low, lowOpen, high, highOpen) from a range constructor Apply.
   * Endpoints must be int or real literals; returns null otherwise (or for
   * ctors we don't recognize).
   */
  private static @Nullable RangeEndpoints extractNumericLiteralEndpoints(
      Core.Apply rangeExp) {
    if (!(rangeExp.fn instanceof Core.Id)) {
      return null;
    }
    final BuiltIn.Constructor ctor =
        BuiltIn.Constructor.forName(((Core.Id) rangeExp.fn).idPat.name);
    if (ctor == null) {
      return null;
    }
    final Core.Exp arg = rangeExp.arg;
    switch (ctor) {
      case RANGE_ALL:
        return new RangeEndpoints(null, false, null, false);
      case RANGE_AT_LEAST:
        {
          Core.@Nullable Literal vLit = Bounds.numericLiteral(arg);
          BigDecimal v = vLit == null ? null : vLit.unwrap(BigDecimal.class);
          return v == null ? null : new RangeEndpoints(v, false, null, false);
        }
      case RANGE_AT_MOST:
        {
          Core.@Nullable Literal vLit = Bounds.numericLiteral(arg);
          BigDecimal v = vLit == null ? null : vLit.unwrap(BigDecimal.class);
          return v == null ? null : new RangeEndpoints(null, false, v, false);
        }
      case RANGE_GREATER_THAN:
        {
          Core.@Nullable Literal vLit = Bounds.numericLiteral(arg);
          BigDecimal v = vLit == null ? null : vLit.unwrap(BigDecimal.class);
          return v == null ? null : new RangeEndpoints(v, true, null, false);
        }
      case RANGE_LESS_THAN:
        {
          Core.@Nullable Literal vLit = Bounds.numericLiteral(arg);
          BigDecimal v = vLit == null ? null : vLit.unwrap(BigDecimal.class);
          return v == null ? null : new RangeEndpoints(null, false, v, true);
        }
      case RANGE_POINT:
        {
          Core.@Nullable Literal vLit = Bounds.numericLiteral(arg);
          BigDecimal v = vLit == null ? null : vLit.unwrap(BigDecimal.class);
          return v == null ? null : new RangeEndpoints(v, false, v, false);
        }
      case RANGE_CLOSED:
      case RANGE_CLOSED_OPEN:
      case RANGE_OPEN_CLOSED:
      case RANGE_OPEN:
        {
          if (arg.op != Op.TUPLE) {
            return null;
          }
          final Core.Tuple tuple = (Core.Tuple) arg;
          if (tuple.args.size() != 2) {
            return null;
          }
          final Core.@Nullable Literal loLit =
              Bounds.numericLiteral(tuple.args.get(0));
          final Core.@Nullable Literal hiLit =
              Bounds.numericLiteral(tuple.args.get(1));
          final BigDecimal lo =
              loLit == null ? null : loLit.unwrap(BigDecimal.class);
          final BigDecimal hi =
              hiLit == null ? null : hiLit.unwrap(BigDecimal.class);
          if (lo == null || hi == null) {
            return null;
          }
          final boolean lowOpen =
              ctor == BuiltIn.Constructor.RANGE_OPEN
                  || ctor == BuiltIn.Constructor.RANGE_OPEN_CLOSED;
          final boolean highOpen =
              ctor == BuiltIn.Constructor.RANGE_OPEN
                  || ctor == BuiltIn.Constructor.RANGE_CLOSED_OPEN;
          return new RangeEndpoints(lo, lowOpen, hi, highOpen);
        }
      default:
        return null;
    }
  }

  /**
   * A real-number interval with optional open/closed endpoints. {@link #low} ==
   * null means -infinity; {@link #high} == null means +infinity.
   */
  private static final class RangeEndpoints {
    final @Nullable BigDecimal low;
    final boolean lowOpen;
    final @Nullable BigDecimal high;
    final boolean highOpen;

    RangeEndpoints(
        @Nullable BigDecimal low,
        boolean lowOpen,
        @Nullable BigDecimal high,
        boolean highOpen) {
      this.low = low;
      this.lowOpen = lowOpen;
      this.high = high;
      this.highOpen = highOpen;
    }

    /**
     * Sort order: -inf first; then by value; closed-low before open-low at the
     * same value.
     */
    int compareByLow(RangeEndpoints other) {
      if (this.low == null && other.low == null) {
        return 0;
      }
      if (this.low == null) {
        return -1;
      }
      if (other.low == null) {
        return 1;
      }
      final int cmp = this.low.compareTo(other.low);
      if (cmp != 0) {
        return cmp;
      }
      return Boolean.compare(this.lowOpen, other.lowOpen);
    }

    /**
     * Returns whether this range lies strictly below {@code other} on the real
     * number line (no shared point).
     */
    boolean isStrictlyBelow(RangeEndpoints other) {
      if (this.high == null) {
        return false;
      }
      if (other.low == null) {
        return false;
      }
      final int cmp = this.high.compareTo(other.low);
      if (cmp < 0) {
        return true;
      }
      if (cmp > 0) {
        return false;
      }
      // Same value at the boundary: disjoint iff at least one side is open.
      return this.highOpen || other.lowOpen;
    }
  }

  /**
   * Creates an expression that generates a range of integer values.
   *
   * <p>For example, a {@code lower} of {@code (3, true)} and an {@code upper}
   * of {@code (8, false)} generate the range {@code 3 < x <= 8}, yielding
   * {@code [4, 5, 6, 7, 8]}.
   *
   * @param ordered If true, generate a {@code list}; otherwise a {@code bag}
   * @param pat Pattern
   * @param lower Lower bound (value, strictness, source constraint)
   * @param upper Upper bound (value, strictness, source constraint)
   */
  static Generator generateRange(
      Cache cache,
      boolean ordered,
      Core.NamedPat pat,
      Bound lower,
      Bound upper) {
    final Core.Exp lowerValue = lower.value;
    final boolean lowerStrict = lower.strict;
    final Core.Exp upperValue = upper.value;
    final boolean upperStrict = upper.strict;
    // Provenance is exactly the two source constraints from which the
    // chosen lower and upper bounds were extracted. The wider Expander
    // pipeline uses this to drop subsumed conjuncts from the surrounding
    // where clause. Other bound-shaped constraints (e.g. "x < y") may
    // remain as filters, so we must not add them to provenance.
    final ImmutableSet.Builder<Core.Exp> provenance = ImmutableSet.builder();
    provenance.add(lower.source);
    provenance.add(upper.source);
    // Emit: Range.flatten [CTOR (lower, upper)]                 (list)
    //   or: Bag.fromList (Range.flatten [CTOR (lower, upper)])  (bag)
    // where CTOR is CLOSED, CLOSED_OPEN, OPEN_CLOSED, or OPEN.
    // (A single ctor means there's no overlap to deduplicate, so we don't
    // need Range.discreteSetOf; the shape matches user-written [k..n] /
    // bag [k..n] syntax.)
    final TypeSystem typeSystem = cache.typeSystem;
    final BuiltIn.Constructor rangeCtor =
        getConstructor(lowerStrict, upperStrict);
    final Type type = pat.type;
    final Type rangeType = typeSystem.range(type);
    final FnType conFnType =
        typeSystem.fnType(typeSystem.tupleType(type, type), rangeType);
    final Core.IdPat conIdPat = core.idPat(conFnType, rangeCtor.constructor, 0);
    final Core.Apply rangeExp =
        core.apply(
            Pos.ZERO,
            rangeType,
            core.id(conIdPat),
            core.tuple(typeSystem, lowerValue, upperValue));
    final Core.Exp rangeListExp =
        core.list(typeSystem, rangeType, ImmutableList.of(rangeExp));
    final Core.Apply flattenExp =
        core.call(
            typeSystem, BuiltIn.RANGE_FLATTEN, type, Pos.ZERO, rangeListExp);
    final Core.Exp exp =
        ordered
            ? flattenExp
            : core.call(
                typeSystem, BuiltIn.BAG_FROM_LIST, type, Pos.ZERO, flattenExp);
    final Core.Exp simplified = Simplifier.simplify(typeSystem, exp);
    final Set<Core.NamedPat> freePats = freePats(typeSystem, simplified);
    return cache.add(
        new RangeGenerator(
            pat,
            simplified,
            freePats,
            ImmutableList.of(rangeExp),
            provenance.build()));
  }

  private static BuiltIn.Constructor getConstructor(
      boolean lowerStrict, boolean upperStrict) {
    if (lowerStrict) {
      if (upperStrict) {
        return BuiltIn.Constructor.RANGE_OPEN;
      } else {
        return BuiltIn.Constructor.RANGE_OPEN_CLOSED;
      }
    } else {
      if (upperStrict) {
        return BuiltIn.Constructor.RANGE_CLOSED_OPEN;
      } else {
        return BuiltIn.Constructor.RANGE_CLOSED;
      }
    }
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
  static @Nullable Bound lowerBound(
      TypeSystem typeSystem, Core.Pat pat, List<Core.Exp> constraints) {
    // Two passes: first prefer a bound whose value is a constant
    // expression. A constant bound makes the generator independent of other
    // variables; a variable bound creates a generator-scheduling dependency
    // that can fail to break a cycle even when the system is finite. If no
    // constant bound exists, fall back to the first bound of any shape.
    final Bound constant = lowerBound1(typeSystem, pat, constraints, true);
    if (constant != null) {
      return constant;
    }
    return lowerBound1(typeSystem, pat, constraints, false);
  }

  /**
   * Helper for {@link #lowerBound}. When {@code requireConstant} is true, skips
   * any candidate whose bound expression is not a constant.
   */
  private static @Nullable Bound lowerBound1(
      TypeSystem typeSystem,
      Core.Pat pat,
      List<Core.Exp> constraints,
      boolean requireConstant) {
    for (Core.Exp constraint : constraints) {
      switch (constraint.builtIn()) {
        case OP_GT:
        case OP_GE:
          if (references(constraint.arg(0), pat)) {
            // "p > e" -> (strict, e); "p >= e" -> (non-strict, e).
            final Core.Exp bound = constraint.arg(1);
            if (requireConstant && !bound.isConstant()) {
              continue;
            }
            final boolean strict = constraint.builtIn() == BuiltIn.OP_GT;
            return new Bound(bound, strict, constraint);
          }
          // Check for "e > p + k" -> "p < e - k" (this is an upper bound, skip)
          break;
        case CHAR_OP_GT:
        case CHAR_OP_GE:
          if (references(constraint.arg(0), pat)) {
            final Core.Exp bound = constraint.arg(1);
            if (requireConstant && !bound.isConstant()) {
              continue;
            }
            return new Bound(
                bound, constraint.builtIn() == BuiltIn.CHAR_OP_GT, constraint);
          }
          break;
        case OP_LT:
        case OP_LE:
          if (references(constraint.arg(1), pat)) {
            // "e < p" -> (strict, e); "e <= p" -> (non-strict, e).
            final Core.Exp bound = constraint.arg(0);
            if (requireConstant && !bound.isConstant()) {
              continue;
            }
            final boolean strict = constraint.builtIn() == BuiltIn.OP_LT;
            return new Bound(bound, strict, constraint);
          }
          // Check for "e < p + k" -> "p > e - k" -> lower bound = e - k
          final BigDecimal offset = extractOffset(constraint.arg(1), pat);
          if (offset != null) {
            // "e < p + k" -> "p > e - k"
            if (requireConstant && !constraint.arg(0).isConstant()) {
              continue;
            }
            final boolean strict = constraint.builtIn() == BuiltIn.OP_LT;
            final Core.Exp bound =
                adjustBound(constraint.arg(0), offset, typeSystem);
            return new Bound(bound, strict, constraint);
          }
          break;
        case CHAR_OP_LT:
        case CHAR_OP_LE:
          if (references(constraint.arg(1), pat)) {
            final Core.Exp bound = constraint.arg(0);
            if (requireConstant && !bound.isConstant()) {
              continue;
            }
            return new Bound(
                bound, constraint.builtIn() == BuiltIn.CHAR_OP_LT, constraint);
          }
          break;
        default:
          // 'Range.contains (AT_LEAST a) pat' -> "pat >= a";
          // 'Range.contains (GREATER_THAN a) pat' -> "pat > a".
          final Core.Apply range = rangeContainsArg(constraint, pat);
          if (range != null) {
            final BuiltIn.Constructor c = rangeConstructor(range);
            if (c == BuiltIn.Constructor.RANGE_AT_LEAST
                || c == BuiltIn.Constructor.RANGE_GREATER_THAN) {
              final Core.Exp bound = range.arg;
              if (requireConstant && !bound.isConstant()) {
                continue;
              }
              return new Bound(
                  bound,
                  c == BuiltIn.Constructor.RANGE_GREATER_THAN,
                  constraint);
            }
          }
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
  static @Nullable Bound upperBound(
      TypeSystem typeSystem, Core.Pat pat, List<Core.Exp> constraints) {
    // See {@link #lowerBound}: prefer a constant bound to avoid a cyclic
    // generator dependency.
    final Bound constant = upperBound1(typeSystem, pat, constraints, true);
    if (constant != null) {
      return constant;
    }
    return upperBound1(typeSystem, pat, constraints, false);
  }

  private static @Nullable Bound upperBound1(
      TypeSystem typeSystem,
      Core.Pat pat,
      List<Core.Exp> constraints,
      boolean requireConstant) {
    for (Core.Exp constraint : constraints) {
      switch (constraint.builtIn()) {
        case OP_LT:
        case OP_LE:
          if (references(constraint.arg(0), pat)) {
            // "p < e" -> (strict, e); "p <= e" -> (non-strict, e).
            final Core.Exp bound = constraint.arg(1);
            if (requireConstant && !bound.isConstant()) {
              continue;
            }
            final boolean strict = constraint.builtIn() == BuiltIn.OP_LT;
            return new Bound(bound, strict, constraint);
          }
          // Check for "e < p + k" -> "p > e - k" (this is a lower bound, skip)
          break;
        case CHAR_OP_LT:
        case CHAR_OP_LE:
          if (references(constraint.arg(0), pat)) {
            final Core.Exp bound = constraint.arg(1);
            if (requireConstant && !bound.isConstant()) {
              continue;
            }
            return new Bound(
                bound, constraint.builtIn() == BuiltIn.CHAR_OP_LT, constraint);
          }
          break;
        case OP_GT:
        case OP_GE:
          if (references(constraint.arg(1), pat)) {
            // "e > p" -> (strict, e); "e >= p" -> (non-strict, e).
            final Core.Exp bound = constraint.arg(0);
            if (requireConstant && !bound.isConstant()) {
              continue;
            }
            final boolean strict = constraint.builtIn() == BuiltIn.OP_GT;
            return new Bound(bound, strict, constraint);
          }
          // Check for "e > p + k" -> "p < e - k" -> upper bound = e - k
          final BigDecimal offset = extractOffset(constraint.arg(1), pat);
          if (offset != null) {
            // "e > p + k" -> "p < e - k"
            if (requireConstant && !constraint.arg(0).isConstant()) {
              continue;
            }
            final boolean strict = constraint.builtIn() == BuiltIn.OP_GT;
            final Core.Exp bound =
                adjustBound(constraint.arg(0), offset, typeSystem);
            return new Bound(bound, strict, constraint);
          }
          break;
        case CHAR_OP_GT:
        case CHAR_OP_GE:
          if (references(constraint.arg(1), pat)) {
            final Core.Exp bound = constraint.arg(0);
            if (requireConstant && !bound.isConstant()) {
              continue;
            }
            return new Bound(
                bound, constraint.builtIn() == BuiltIn.CHAR_OP_GT, constraint);
          }
          break;
        default:
          // 'Range.contains (AT_MOST b) pat' -> "pat <= b";
          // 'Range.contains (LESS_THAN b) pat' -> "pat < b".
          final Core.Apply range = rangeContainsArg(constraint, pat);
          if (range != null) {
            final BuiltIn.Constructor c = rangeConstructor(range);
            if (c == BuiltIn.Constructor.RANGE_AT_MOST
                || c == BuiltIn.Constructor.RANGE_LESS_THAN) {
              final Core.Exp bound = range.arg;
              if (requireConstant && !bound.isConstant()) {
                continue;
              }
              return new Bound(
                  bound, c == BuiltIn.Constructor.RANGE_LESS_THAN, constraint);
            }
          }
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
        case INT_OP_PLUS:
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
        case INT_OP_MINUS:
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
        typeSystem, BuiltIn.INT_OP_MINUS, exp, core.intLiteral(offset));
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
    /** The single {@code CTOR(lo, hi)} range expression, before wrapping. */
    private final List<Core.Apply> rangeExps;

    RangeGenerator(
        Core.NamedPat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        List<Core.Apply> rangeExps,
        Set<Core.Exp> provenance) {
      super(exp, freePats, pat, Cardinality.FINITE, true, true, provenance);
      this.rangeExps = ImmutableList.copyOf(rangeExps);
    }

    @Override
    Core.Exp simplify(TypeSystem typeSystem, Core.Pat pat, Core.Exp exp) {
      return exp;
    }

    @Override
    protected List<Core.Apply> rangeExp(TypeSystem typeSystem) {
      return rangeExps;
    }
  }

  /** Generator that generates a single value. */
  static class PointGenerator extends Generator {
    private final Core.Exp point;

    PointGenerator(
        Core.Pat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        Core.Exp point,
        Set<Core.Exp> provenance) {
      super(exp, freePats, pat, Cardinality.SINGLE, true, true, provenance);
      this.point = point;
    }

    /**
     * Creates an expression that generates a single value.
     *
     * @param ordered If true, generate a `list`, otherwise a `bag`
     * @param lower Lower bound
     * @param provenance The constraints this generator subsumes
     */
    @SuppressWarnings("UnusedReturnValue")
    static Generator create(
        Cache cache,
        Core.Pat pat,
        boolean ordered,
        Core.Exp lower,
        Set<Core.Exp> provenance) {
      final Core.Exp exp =
          ordered
              ? core.list(cache.typeSystem, lower)
              : core.bag(cache.typeSystem, lower);
      final Set<Core.NamedPat> freePats = freePats(cache.typeSystem, exp);
      return cache.add(
          new PointGenerator(pat, exp, freePats, lower, provenance));
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

    @Override
    protected List<Core.Apply> rangeExp(TypeSystem typeSystem) {
      // Return the constructor call: "POINT point".
      final Type rangeType = typeSystem.range(point.type);
      final FnType conFnType = typeSystem.fnType(point.type, rangeType);
      final Core.IdPat conIdPat =
          core.idPat(conFnType, BuiltIn.Constructor.RANGE_POINT.constructor, 0);
      final Core.Apply apply =
          core.apply(Pos.ZERO, rangeType, core.id(conIdPat), point);
      return ImmutableList.of(apply);
    }
  }

  /** Generator that generates all prefixes of a string expression. */
  static class StringPrefixGenerator extends Generator {
    private final Core.Exp strExp;

    StringPrefixGenerator(
        Core.Pat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        Core.Exp strExp,
        Set<Core.Exp> provenance) {
      super(exp, freePats, pat, Cardinality.FINITE, true, true, provenance);
      this.strExp = strExp;
    }

    /**
     * Creates an expression that generates all prefixes of a string.
     *
     * <p>Generates: {@code List.tabulate(String.size s + 1, fn i =>
     * String.substring(s, 0, i))}
     *
     * @param strExp The string expression to generate prefixes of
     * @param provenance The constraints this generator subsumes
     */
    @SuppressWarnings("UnusedReturnValue")
    static Generator create(
        Cache cache,
        Core.Pat pat,
        boolean ordered,
        Core.Exp strExp,
        Set<Core.Exp> provenance) {
      final TypeSystem ts = cache.typeSystem;

      // Build: String.size s + 1
      final Core.Exp sizeExp = core.call(ts, BuiltIn.STRING_SIZE, strExp);
      final Core.Literal one = core.intLiteral(BigDecimal.ONE);
      final Core.Exp countExp =
          core.call(ts, BuiltIn.INT_OP_PLUS, sizeExp, one);

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
      return cache.add(
          new StringPrefixGenerator(pat, exp, freePats, strExp, provenance));
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
          false,
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
      super(exp, freePats, pat, cardinality, true, true);
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
        Iterable<? extends Core.NamedPat> freePats,
        Set<Core.Exp> provenance) {
      super(
          collection,
          freePats,
          pat,
          Cardinality.FINITE,
          true,
          true,
          provenance);
      this.collection = collection;
      checkArgument(collection.type.isCollection());
    }

    @SuppressWarnings("UnusedReturnValue")
    static CollectionGenerator create(
        Cache cache,
        boolean ordered,
        Core.Pat pat,
        Core.Exp collection,
        Set<Core.Exp> provenance) {
      // Convert the collection to a list or bag, per "ordered".
      final TypeSystem typeSystem = cache.typeSystem;
      final Core.Exp collection2 =
          core.withOrdered(ordered, collection, typeSystem);
      final Set<Core.NamedPat> freePats =
          freePats(cache.typeSystem, collection2);
      return cache.add(
          new CollectionGenerator(pat, collection2, freePats, provenance));
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

  /**
   * The set of constraints known to be true at a point in the generator
   * derivation tree.
   *
   * <p>A context accumulates facts as the derivation descends into the
   * expression tree. It grows monotonically: child contexts contain all facts
   * of their parent, plus new facts from the current scope.
   */
  static class Context {
    final ImmutableList<Core.Exp> constraints;

    /** Constraints consumed by generator derivation (append-only). */
    final Set<Core.Exp> consumed = new LinkedHashSet<>();

    Context(Iterable<? extends Core.Exp> constraints) {
      this.constraints = ImmutableList.copyOf(constraints);
    }
  }

  /**
   * Monotonic cache of generators and derived facts.
   *
   * <p>Serves as a memoized deductive store during generator expansion. Facts
   * (generators, field mappings) are only added, never removed. Each fact is
   * true forever — fresh pattern variables ensure that new facts have their own
   * identity and don't conflict with existing ones.
   *
   * <p>The cache supports dynamic programming: when asked for a generator for a
   * pattern, it first checks whether one has already been computed. If not, it
   * may derive one from accumulated facts (e.g., composing field generators
   * into a tuple reconstruction).
   *
   * <p>The monotonicity invariant means {@code bestGenerator} can safely return
   * the most recently added generator for a pattern, since later additions are
   * refinements (e.g., an ExistsJoinGenerator that subsumes a raw
   * CollectionGenerator).
   */
  static class Cache {
    final TypeSystem typeSystem;
    final Environment env;
    final Multimap<Core.NamedPat, Generator> generators =
        MultimapBuilder.hashKeys().arrayListValues().build();

    /**
     * Maps (variable, fieldIndex) to the fresh pattern created for {@code #i
     * variable} by {@link #patForExp}.
     */
    final Map<Pair<Core.NamedPat, Integer>, Core.IdPat> fieldPats =
        new LinkedHashMap<>();

    Cache(TypeSystem typeSystem, Environment env) {
      this.typeSystem = requireNonNull(typeSystem);
      this.env = requireNonNull(env);
    }

    /**
     * Creates a pattern for an expression, recording field-access
     * relationships.
     *
     * <p>Unlike {@code wholePat} + {@code toPat}, this method remembers which
     * fresh patterns correspond to which field accesses, enabling {@link
     * #deriveFieldGenerators} to reconstruct tuple generators.
     *
     * <ul>
     *   <li>{@code patForExp(id(x))} returns {@code idPat(x)}
     *   <li>{@code patForExp(tuple(id(x), id(y)))} returns {@code tuplePat(x,
     *       y)}
     *   <li>{@code patForExp(#1 p)} creates fresh {@code f1}, records {@code
     *       (p, 0) -> f1}, returns {@code f1}
     * </ul>
     */
    Core.Pat patForExp(Core.Exp exp) {
      switch (exp.op) {
        case ID:
          return ((Core.Id) exp).idPat;

        case TUPLE:
          final Core.Tuple tuple = (Core.Tuple) exp;
          final List<Core.Pat> patList = new ArrayList<>();
          for (Core.Exp arg : tuple.args) {
            patList.add(patForExp(arg));
          }
          final RecordLikeType type = tuple.type();
          return type instanceof RecordType
              ? core.recordPat((RecordType) type, patList)
              : core.tuplePat(type, patList);

        case APPLY:
          final Core.Apply apply = (Core.Apply) exp;
          if (apply.fn.op == Op.RECORD_SELECTOR && apply.arg.op == Op.ID) {
            final Core.RecordSelector selector = (Core.RecordSelector) apply.fn;
            final Core.NamedPat basePat = ((Core.Id) apply.arg).idPat;
            final int slot = selector.slot;
            final Pair<Core.NamedPat, Integer> key = Pair.of(basePat, slot);
            return fieldPats.computeIfAbsent(
                key,
                k -> {
                  final String fieldName = selector.fieldName();
                  final String varName =
                      Character.isDigit(fieldName.charAt(0))
                          ? "f" + fieldName
                          : fieldName;
                  return core.idPat(exp.type, varName, 0);
                });
          }
          // For other applies, create a fresh pattern (same as toPat)
          return core.idPat(exp.type, "v", 0);

        case BOOL_LITERAL:
        case CHAR_LITERAL:
        case INT_LITERAL:
        case REAL_LITERAL:
        case STRING_LITERAL:
        case UNIT_LITERAL:
          return core.toPat(exp);

        default:
          return core.toPat(exp);
      }
    }

    /**
     * Checks whether all fields of some tuple-typed variable have fresh
     * patterns in {@link #fieldPats}, and if so creates a self-contained {@link
     * CollectionGenerator} that reconstructs the tuple by joining the
     * underlying collection scans.
     *
     * <p>For example, if {@code fieldPats} contains {@code (p, 0) -> f1} and
     * {@code (p, 1) -> f2}, with generators {@code (p1, f1) in edges} and
     * {@code (p2, f2) in edges}, creates:
     *
     * <pre>{@code
     * from (p1, f1) in edges, (p2, f2) in edges
     *   where p1 = p2
     *   yield (f1, f2)
     *   distinct
     * }</pre>
     */
    void deriveFieldGenerators(boolean ordered) {
      // Collect all base patterns that have field entries
      final Map<Core.NamedPat, Map<Integer, Core.IdPat>> byBase =
          new LinkedHashMap<>();
      for (Map.Entry<Pair<Core.NamedPat, Integer>, Core.IdPat> entry :
          fieldPats.entrySet()) {
        byBase
            .computeIfAbsent(entry.getKey().left, k -> new LinkedHashMap<>())
            .put(entry.getKey().right, entry.getValue());
      }
      for (Map.Entry<Core.NamedPat, Map<Integer, Core.IdPat>> entry :
          byBase.entrySet()) {
        final Core.NamedPat basePat = entry.getKey();
        final Map<Integer, Core.IdPat> fields = entry.getValue();
        // Check if basePat is tuple-typed with all fields covered
        if (!(basePat.type instanceof RecordLikeType)) {
          continue;
        }
        final RecordLikeType recordType = (RecordLikeType) basePat.type;
        final int arity = recordType.argNameTypes().size();
        final Generator existing = bestGenerator(basePat);
        final boolean hasFiniteGenerator =
            existing != null
                && existing.cardinality != Generator.Cardinality.INFINITE;
        if (fields.size() != arity || hasFiniteGenerator) {
          continue;
        }
        // All fields covered and no finite generator — derive one.
        // Verify all field patterns exist.
        final List<Core.IdPat> fieldPatList = new ArrayList<>();
        boolean complete = true;
        for (int i = 0; i < arity; i++) {
          final Core.IdPat fieldPat = fields.get(i);
          if (fieldPat == null) {
            complete = false;
            break;
          }
          fieldPatList.add(fieldPat);
        }
        if (!complete) {
          continue;
        }

        // Find the generator for each field pattern and build a FROM
        // expression that joins them.
        final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
        final List<Core.NamedPat> sharedPats = new ArrayList<>();
        boolean first = true;
        for (Core.IdPat fieldPat : fieldPatList) {
          final Generator fieldGen = bestGenerator(fieldPat);
          if (fieldGen == null) {
            complete = false;
            break;
          }
          if (first) {
            // First scan: use the generator's pattern and expression as-is
            fromBuilder.scan(fieldGen.pat, fieldGen.exp);
            // Track non-field patterns for joining
            for (Core.NamedPat p : fieldGen.pat.expand()) {
              if (!fieldPatList.contains(p)) {
                sharedPats.add(p);
              }
            }
            first = false;
          } else {
            // Subsequent scans: need to create fresh patterns for
            // non-field components and add equality constraints for
            // shared variables (like p' that appears in both generators).
            final List<Core.NamedPat> genPats = fieldGen.pat.expand();
            final List<Core.Pat> freshPats = new ArrayList<>();
            final List<Core.Exp> eqConstraints = new ArrayList<>();
            for (Core.NamedPat gp : genPats) {
              if (fieldPatList.contains(gp)) {
                freshPats.add(gp);
              } else {
                // Create a fresh pattern for this shared/auxiliary variable
                final Core.IdPat freshPat =
                    core.idPat(gp.type, gp.name + "$", 0);
                freshPats.add(freshPat);
                // Find matching shared pattern from earlier scan
                for (Core.NamedPat sp : sharedPats) {
                  if (sp.name.equals(gp.name)) {
                    eqConstraints.add(
                        core.equal(typeSystem, core.id(sp), core.id(freshPat)));
                    break;
                  }
                }
              }
            }
            final Core.Pat scanPat = core.tuplePat(typeSystem, freshPats);
            fromBuilder.scan(scanPat, fieldGen.exp);
            for (Core.Exp eq : eqConstraints) {
              fromBuilder.where(eq);
            }
          }
        }
        if (!complete) {
          continue;
        }

        // Group by the reconstructed tuple to get distinct values.
        // Use group with atom=true and a single key, rather than
        // yield + distinct, to avoid a pre-existing bug where
        // yield(tuple) + distinct produces null for tuple-typed atoms.
        final List<Core.Exp> fieldExps = new ArrayList<>();
        for (Core.IdPat fp : fieldPatList) {
          fieldExps.add(core.id(fp));
        }
        final Core.Exp tupleExp =
            core.tuple(typeSystem, fieldExps.toArray(new Core.Exp[0]));
        final Core.IdPat resultPat = core.idPat(basePat.type, basePat.name, 0);
        final ImmutableSortedMap<Core.IdPat, Core.Exp> groupExps =
            ImmutableSortedMap.of(resultPat, tupleExp);
        fromBuilder.group(true, groupExps, ImmutableSortedMap.of());

        final Core.From derivedFrom = fromBuilder.build();
        // Convert the derived FROM to a list to prevent FromBuilder.scan
        // from inlining it (which would break variable scoping).
        final Core.Exp asList = core.withOrdered(true, derivedFrom, typeSystem);
        CollectionGenerator.create(
            this, ordered, basePat, asList, ImmutableSet.of());
      }
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
  }
}

// End Generators.java
