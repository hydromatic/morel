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
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Range;
import org.apache.calcite.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Pair.allMatch;

import static org.apache.calcite.util.Util.minus;

/** Generates an expression for the set of values that a variable can take in
 * a program.
 *
 * <p>If {@code i} is a variable of type {@code int} then one approximation is
 * the set of all 2<sup>32</sup> values of the {@code int} data type. (Every
 * data type, primitive data types and those built using sum ({@code datatype})
 * or product (record and tuple), has a finite set of values, but the set is
 * usually too large to iterate over.)
 *
 * <p>There is often a better approximation that can be deduced from the uses
 * of the variable. For example,
 *
 * <blockquote><pre>{@code
 *   let
 *     fun isOdd i = i % 2 = 0
 *   in
 *     from e in emps,
 *         i suchthat isOdd i andalso i < 100
 *       where i = e.deptno
 *   end
 * }</pre></blockquote>
 *
 * <p>we can deduce a better extent for {@code i}, namely
 *
 * <blockquote><pre>{@code
 *    from e in emps
 *      yield e.deptno
 *      where deptno % 2 = 0 andalso deptno < 100
 * }</pre></blockquote>
 */
public class Extents {
  private Extents() {}

  /** Returns an expression that generates the extent of a pattern.
   *
   * <p>For example, given the program
   *
   * <blockquote><pre>{@code
   *   let
   *     fun f i = i elem [1, 2, 4]
   *   in
   *     from x suchthat f x
   *   end
   * }</pre></blockquote>
   *
   * <p>we can deduce that the extent of "x" is "[1, 2, 4]".
   *
   * <p>We can also compute the extent of tuples. For the program
   *
   * <blockquote><pre>{@code
   *   let
   *     val edges = [(1, 2), (2, 3), (1, 4), (4, 2), (4, 3)]
  *      fun edge (i, j) = (i, j) elem edges
   *   in
   *     from (x, y, z) suchthat edge (x, y) andalso edge (y, z) andalso x <> z
   *   end
   * }</pre></blockquote>
   *
   * we could deduce that "x" has extent "from e in edges group e.i",
   * "y" has extent "from e in edges group e.j"
   * ("from e in edges group e.i" is also valid),
   * "z" has extent "from e in edges group e.j",
   * and therefore "(x, y, z)" has extent
   *
   * <blockquote><pre>{@code
   * from x in (from e in edges group e.i),
   *   y in (from e in edges group e.j),
   *   z in (from e in edges group e.j)
   * }</pre></blockquote>
   *
   * <p>but we can do better by computing the extent of (x, y) simultaneously:
   *
   * <blockquote><pre>{@code
   * from (x, y) in (from e in edges),
   *   z in (from e in edges group e.j)
   * }</pre></blockquote>
   */
  public static Core.Exp generator(TypeSystem typeSystem, Core.Pat pat,
      Core.Exp exp) {
    return create(typeSystem, pat, ImmutableSortedMap.of(), exp).extentExp;
  }

  public static Analysis create(TypeSystem typeSystem, Core.Pat pat,
      SortedMap<Core.NamedPat, Core.Exp> boundPats, Core.Exp exp) {
    final Extent extent = new Extent(typeSystem, pat, boundPats);

    final ListMultimap<Core.Pat, Core.Exp> map = LinkedListMultimap.create();
    extent.g3(map, exp);
    final List<Core.Exp> exps = map.get(pat);
    if (exps.isEmpty()) {
      throw new AssertionError();
    }
    final Pair<Core.Exp, List<Core.Exp>> pair =
        core.mergeExtents(typeSystem, exps, true);
    return new Analysis(boundPats, extent.goalPats, pair.left, pair.right);
  }

  /** Converts a singleton id pattern "x" or tuple pattern "(x, y)"
   * to a list of id patterns. */
  private static List<Core.IdPat> flatten(Core.Pat pat) {
    switch (pat.op) {
    case ID_PAT:
      return ImmutableList.of((Core.IdPat) pat);

    case TUPLE_PAT:
      final Core.TuplePat tuplePat = (Core.TuplePat) pat;
      for (Core.Pat arg : tuplePat.args) {
        if (arg.op != Op.ID_PAT) {
          throw new CompileException("must be id", false, arg.pos);
        }
      }
      //noinspection unchecked,rawtypes
      return (List) tuplePat.args;

    default:
      throw new CompileException("must be id", false, pat.pos);
    }
  }

  public static class Analysis {
    final SortedMap<Core.NamedPat, Core.Exp> boundPats;
    final Set<Core.NamedPat> goalPats;
    final Core.Exp extentExp;
    final List<Core.Exp> remainingFilters;

    Analysis(SortedMap<Core.NamedPat, Core.Exp> boundPats,
        Set<Core.NamedPat> goalPats, Core.Exp extentExp,
        List<Core.Exp> remainingFilters) {
      this.boundPats = boundPats;
      this.goalPats = goalPats;
      this.extentExp = extentExp;
      this.remainingFilters = remainingFilters;
    }

    Set<Core.NamedPat> unboundPats() {
      return minus(goalPats, boundPats.keySet());
    }
  }

  private static class Extent {
    private final TypeSystem typeSystem;
    final Set<Core.NamedPat> goalPats;
    final SortedMap<Core.NamedPat, Core.Exp> boundPats;

    Extent(TypeSystem typeSystem, Core.Pat pat,
        SortedMap<Core.NamedPat, Core.Exp> boundPats) {
      this.typeSystem = typeSystem;
      this.goalPats = ImmutableSet.copyOf(flatten(pat));
      this.boundPats = ImmutableSortedMap.copyOf(boundPats);
    }

    @SuppressWarnings("SwitchStatementWithTooFewBranches")
    void g3(Multimap<Core.Pat, Core.Exp> map, Core.Exp exp) {
      final Core.Apply apply;
      switch (exp.op) {
      case APPLY:
        apply = (Core.Apply) exp;
        switch (apply.fn.op) {
        case FN_LITERAL:
          BuiltIn builtIn = (BuiltIn) ((Core.Literal) apply.fn).value;
          switch (builtIn) {
          case Z_ANDALSO:
            // Expression is 'andalso'. Visit each pattern, and union the
            // constraints (intersect the generators).
            apply.arg.forEachArg((arg, i) -> g3(map, arg));
            break;

          case Z_ORELSE:
            // Expression is 'orelse'. Visit each pattern, and intersect the
            // constraints (union the generators).
            final Multimap<Core.Pat, Core.Exp> map2 =
                LinkedListMultimap.create();
            apply.arg.forEachArg((arg, i) -> {
              final Multimap<Core.Pat, Core.Exp> map3 =
                  LinkedListMultimap.create();
              g3(map3, arg);
              map3.asMap().forEach((k, vs) ->
                  map2.put(k, core.intersect(typeSystem, vs)));
            });
            map2.asMap().forEach((k, vs) ->
                map.put(k, core.union(typeSystem, vs)));
            break;

          case OP_EQ:
          case OP_NE:
          case OP_GE:
          case OP_GT:
          case OP_LT:
          case OP_LE:
            g4(map, builtIn, apply.arg(0), apply.arg(1));
            break;
          }
        }
        break;

      default:
        break;
      }
    }

    @SuppressWarnings("SwitchStatementWithTooFewBranches")
    private void g4(Multimap<Core.Pat, Core.Exp> map, BuiltIn builtIn,
        Core.Exp arg0, Core.Exp arg1) {
      switch (builtIn) {
      case OP_EQ:
      case OP_NE:
      case OP_GE:
      case OP_GT:
      case OP_LE:
      case OP_LT:
        switch (arg0.op) {
        case ID:
          final Core.Id id = (Core.Id) arg0;
          if (arg1.isConstant()) {
            // If exp is "id = literal", add extent "id: [literal]";
            // if exp is "id > literal", add extent "id: (literal, inf)", etc.
            map.put(id.idPat, baz(builtIn, arg1));
          }
          break;
        default:
          if (arg0.isConstant() && arg1.op == Op.ID) {
            // Try switched, "literal = id".
            g4(map, builtIn.reverse(), arg1, arg0);
          }
        }
        break;

      default:
        throw new AssertionError("unexpected: " + builtIn);
      }
    }

    @SuppressWarnings("UnstableApiUsage")
    private Core.Exp baz(BuiltIn builtIn, Core.Exp arg) {
      switch (builtIn) {
      case OP_EQ:
        return core.list(typeSystem, arg);
      case OP_NE:
        return core.extent(typeSystem, arg.type,
            ImmutableRangeSet.of(Range.singleton(((Core.Literal) arg).value))
                .complement());
      case OP_GE:
        return core.extent(typeSystem, arg.type,
            ImmutableRangeSet.of(Range.atLeast(((Core.Literal) arg).value)));
      case OP_GT:
        return core.extent(typeSystem, arg.type,
            ImmutableRangeSet.of(Range.greaterThan(((Core.Literal) arg).value)));
      case OP_LE:
        return core.extent(typeSystem, arg.type,
            ImmutableRangeSet.of(Range.atMost(((Core.Literal) arg).value)));
      case OP_LT:
        return core.extent(typeSystem, arg.type,
            ImmutableRangeSet.of(Range.lessThan(((Core.Literal) arg).value)));
      default:
        throw new AssertionError("unexpected: " + builtIn);
      }
    }

    @SuppressWarnings("UnstableApiUsage")
    ExtentFilter extent(Core.Scan scan) {
      final List<Core.Exp> extents = new ArrayList<>();
      final List<Core.Exp> filters = new ArrayList<>();
      extent(scan.pat, scan.exp, extents, filters);
      final Core.Exp extent;
      if (extents.isEmpty()) {
        extent = core.extent(typeSystem, scan.pat.type,
            ImmutableRangeSet.of(Range.all()));
      } else {
        extent = extents.get(0);
        filters.addAll(Util.skip(extents));
      }
      return new ExtentFilter(extent, ImmutableList.copyOf(filters));
    }

    private void extent(Core.Pat pat, Core.Exp exp, List<Core.Exp> extents,
        List<Core.Exp> filters) {
      switch (exp.op) {
      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        switch (apply.fn.op) {
        case FN_LITERAL:
          switch ((BuiltIn) ((Core.Literal) apply.fn).value) {
          case OP_ELEM:
            final List<Core.Exp> args = ((Core.Tuple) apply.arg).args;
            if (matches(args.get(0), pat)) {
              extents.add(args.get(1));
            }
            break;
          case Z_ANDALSO:
            for (Core.Exp e : ((Core.Tuple) apply.arg).args) {
              extent(pat, e, extents, filters);
              return;
            }
          }
        }
      }
      filters.add(exp);
    }

    /** Returns whether an expression corresponds exactly to a pattern.
     * For example "x" matches the pattern "x",
     * and "(z, y)" matches the pattern "(x, y)". */
    private static boolean matches(Core.Exp exp, Core.Pat pat) {
      if (exp.op == Op.ID && pat.op == Op.ID_PAT) {
        return ((Core.Id) exp).idPat.equals(pat);
      }
      if (exp.op == Op.TUPLE && pat.op == Op.TUPLE_PAT) {
        final Core.Tuple tuple = (Core.Tuple) exp;
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        if (tuple.args.size() == tuplePat.args.size()) {
          return allMatch(tuple.args, tuplePat.args, Extent::matches);
        }
      }
      return false;
    }
  }

  /** A "suchthat" expression split into an extent and filters. */
  static class ExtentFilter {
    final Core.Exp extent;
    final ImmutableList<Core.Exp> filters;

    ExtentFilter(Core.Exp extent, ImmutableList<Core.Exp> filters) {
      this.extent = extent;
      this.filters = filters;
    }
  }
}
