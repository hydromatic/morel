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
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import org.apache.calcite.util.Holder;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.append;
import static net.hydromatic.morel.util.Static.plus;
import static net.hydromatic.morel.util.Static.skip;

import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.getOnlyElement;

/**
 * Converts {@code suchThat} to {@code in} wherever possible.
 */
class SuchThatShuttle extends Shuttle {
  final Deque<FromState> fromStates = new ArrayDeque<>();
  final Environment env;

  SuchThatShuttle(TypeSystem typeSystem, Environment env) {
    super(typeSystem);
    this.env = env;
  }

  static boolean containsSuchThat(Core.Decl decl) {
    final Holder<Boolean> found = Holder.of(false);
    decl.accept(new Visitor() {
      @Override protected void visit(Core.Scan scan) {
        super.visit(scan);
        if (scan.op == Op.SUCH_THAT) {
          found.set(true);
        }
      }
    });
    return found.get();
  }

  @Override protected Core.Exp visit(Core.From from) {
    try {
      final FromState fromState = new FromState(from);
      fromStates.push(fromState);
      for (Core.FromStep node : from.steps) {
        fromState.steps.add(node.accept(this));
      }
      return from.copy(typeSystem, env, fromState.steps);
    } finally {
      fromStates.pop();
    }
  }

  @Override protected Core.Scan visit(Core.Scan scan) {
    if (scan.op != Op.SUCH_THAT) {
      return super.visit(scan);
    }
    final ImmutableSortedMap.Builder<Core.NamedPat, Core.Exp> boundPatBuilder =
        ImmutableSortedMap.orderedBy(Core.NamedPat.ORDERING);
    if (!fromStates.element().steps.isEmpty()) {
      getLast(fromStates.element().steps).bindings.forEach(b ->
          boundPatBuilder.put(b.id, core.id(b.id)));
    }
    final SortedMap<Core.NamedPat, Core.Exp> boundPats = boundPatBuilder.build();
    final Core.Exp rewritten = rewrite0(boundPats, scan.pat, scan.exp);
    return core.scan(Op.INNER_JOIN, scan.bindings,
        scan.pat, rewritten, scan.condition);
  }

  private Core.Exp rewrite0(SortedMap<Core.NamedPat, Core.Exp> boundPats,
      Core.Pat pat, Core.Exp exp) {
    try {
      final Map<Core.IdPat, Core.Exp> scans = ImmutableMap.of();
      final List<Core.Exp> filters = ImmutableList.of();
      final UnaryOperator<Core.NamedPat> originalPats = UnaryOperator.identity();
      return rewrite(originalPats, boundPats, scans, filters,
          conjunctions(exp));
    } catch (RewriteFailedException e) {
      // We could not rewrite.
      // Try a different approach.
      // Generate an iterator over all values of all variables,
      // then filter.
      final Core.Exp generator = Extents.generator(typeSystem, pat, exp);
      final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
      return fromBuilder.scan(pat, generator)
          .where(exp)
          .build();
    }
  }

  /** Rewrites a "from vars suchthat condition" expression to a
   * "from vars in list" expression; returns null if no rewrite is possible.
   *
   * <p>The "filters" argument contains a list of conditions to be applied
   * after generating rows. For example,
   * "from x suchthat x % 2 = 1 and x elem list"
   * becomes "from x in list where x % 2 = 1" with the filter "x % 2 = 1".
   *
   * <p>The "scans" argument contains scans to be added. For example,
   * "from x suchthat x elem list" adds the scan "(x, list)".
   *
   * <p>The "boundPats" argument contains expressions that are bound to
   * variables. For example, "from (x, y) suchthat (x, y) elem list"
   * will add the scan "(e, list)" and boundPats [(x, #1 e), (y, #2 e)].
   *
   * @param mapper Renames variables
   * @param boundPats Variables that have been bound to a list
   * @param scans Scans (joins) to be appended to the resulting "from"
   * @param filters Filters to be appended as "where" in the resulting "from"
   * @param exps The condition, decomposed into conjunctions
   * @return Rewritten expression
   */
  private Core.Exp rewrite(UnaryOperator<Core.NamedPat> mapper,
      SortedMap<Core.NamedPat, Core.Exp> boundPats,
      Map<Core.IdPat, Core.Exp> scans, List<Core.Exp> filters,
      List<Core.Exp> exps) {
    if (exps.isEmpty()) {
      final ImmutableSortedMap.Builder<Core.NamedPat, Core.Exp> b =
          ImmutableSortedMap.naturalOrder();
      boundPats.forEach((p, e) -> b.put(mapper.apply(p), e));
      final SortedMap<Core.NamedPat, Core.Exp> boundPats2 = b.build();

      final SortedMap<String, Core.Exp> nameExps = RecordType.mutableMap();
      if (scans.isEmpty()) {
        final Core.Scan scan = (Core.Scan) fromStates.element().currentStep();
        final Extents.Analysis extent =
            Extents.create(typeSystem, scan.pat, boundPats2, scan.exp);
        final Set<Core.NamedPat> unboundPats = extent.unboundPats();
        if (!unboundPats.isEmpty()) {
          throw new RewriteFailedException("Cannot implement 'suchthat'; "
              + "variables " + unboundPats + " are not grounded" + "]");
        }
        boundPats2.forEach((p, e) -> {
          if (extent.goalPats.contains(p)) {
            nameExps.put(p.name, e);
          }
        });
      } else {
        boundPats2.forEach((p, e) -> nameExps.put(p.name, e));
      }

      final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
      scans.forEach(fromBuilder::scan);
      filters.forEach(fromBuilder::where);
      fromBuilder.yield_(nameExps.size() == 1
          ? getOnlyElement(nameExps.values())
          : core.record(typeSystem, nameExps));
      return fromBuilder.build();
    }
    final Core.Exp exp = exps.get(0);
    final List<Core.Exp> exps2 = skip(exps);
    switch (exp.op) {
    case APPLY:
      final Core.Apply apply = (Core.Apply) exp;
      if (exp.isCallTo(BuiltIn.OP_ELEM)) {
        Core.Exp a0 = apply.args().get(0);
        Core.Exp a1 = apply.args().get(1);
        Core.@Nullable Exp e =
            rewriteElem(typeSystem, mapper, boundPats, scans, filters, a0, a1,
                exps2);
        if (e != null) {
          return e;
        }
        throw new AssertionError(exp);
      }
      if (exp.isCallTo(BuiltIn.OP_EQ)) {
        Core.Exp a0 = apply.args().get(0);
        Core.Exp a1 = apply.args().get(1);
        if (a1.op == Op.ID && a0.op != Op.ID) {
          final Core.Exp tmp = a0;
          a0 = a1;
          a1 = tmp;
        }
        Core.Exp a1List = core.list(typeSystem, a1);
        Core.@Nullable Exp e =
            rewriteElem(typeSystem, mapper, boundPats, scans,
                filters, a0, a1List, exps2);
        if (e != null) {
          return e;
        }
      }
      final List<Core.Exp> filters2 = append(filters, exp);
      return rewrite(mapper, boundPats, scans, filters2,
          exps2);

    case CASE:
      final Core.Case case_ = (Core.Case) exp;
      if (case_.matchList.size() == 1) {
        // A simple renaming case, e.g. "case (e, j) of (a, b) => #job a = b",
        // boundPats is "{e}" translate as if the expression were "#job e = j",
        // boundPats = "{a}".
        final Core.Match match = case_.matchList.get(0);
        final SortedMap<Core.NamedPat, Core.Exp> boundPats2 =
            new TreeMap<>(boundPats.comparator());
        boundPats.forEach((p, e) -> boundPats2.put(mapper.apply(p), e));
        final PatMap patMap = PatMap.of(match.pat, case_.exp);
        return rewrite(mapper.andThen(patMap::apply)::apply, boundPats2,
            scans, filters, plus(match.exp, exps2));
      }
      break;
    }

    throw new RewriteFailedException("not implemented: suchthat " + exp.op
        + " [" + exp + "]");
  }

  private Core.@Nullable Exp rewriteElem(TypeSystem typeSystem,
      UnaryOperator<Core.NamedPat> mapper,
      SortedMap<Core.NamedPat, Core.Exp> boundPats,
      Map<Core.IdPat, Core.Exp> scans,
      List<Core.Exp> filters, Core.Exp a0,
      Core.Exp a1, List<Core.Exp> exps2) {
    if (a0.op == Op.ID) {
      // from ... v suchthat (v elem list)
      final Core.IdPat idPat = (Core.IdPat) ((Core.Id) a0).idPat;
      if (!boundPats.containsKey(idPat)) {
        // "from a, b, c suchthat (b in list-valued-expression)"
        //  --> remove b from unbound
        //     add (b, scans.size) to bound
        //     add list to scans
        // from ... v in list
        final SortedMap<Core.NamedPat, Core.Exp> boundPats2 =
            plus(boundPats, idPat, core.id(idPat));
        final Map<Core.IdPat, Core.Exp> scans2 = plus(scans, idPat, a1);
        return rewrite(mapper, boundPats2, scans2, filters, exps2);
      } else {
        final Core.Exp e = boundPats.get(idPat);
        final List<Core.Exp> filters2 =
            append(filters, core.elem(typeSystem, e, a1));
        return rewrite(mapper, boundPats, scans, filters2, exps2);
      }
    } else if (a0.op == Op.TUPLE) {
      // from v, w, x suchthat ((v, w, x) elem list)
      //  -->
      //  from e suchthat (e elem list)
      //    yield (e.v, e.w, e.x)
      final Core.Tuple tuple = (Core.Tuple) a0;
      final Core.IdPat idPat =
          core.idPat(((ListType) a1.type).elementType,
              typeSystem.nameGenerator);
      final Core.Id id = core.id(idPat);
      SortedMap<Core.NamedPat, Core.Exp> boundPats2 = boundPats;
      List<Core.Exp> filters2 = filters;
      for (Ord<Core.Exp> arg : Ord.zip(tuple.args)) {
        final Core.Exp e = core.field(typeSystem, id, arg.i);
        if (arg.e instanceof Core.Id) {
          final Core.NamedPat idPat2 = ((Core.Id) arg.e).idPat;
          if (!boundPats2.containsKey(idPat2)) {
            // This variable was not previously bound; bind it.
            boundPats2 = plus(boundPats2, idPat2, e);
          } else {
            // This variable is already bound; now add a filter.
            filters2 =
                append(filters, core.equal(typeSystem, e, arg.e));
          }
        } else {
          filters2 =
              append(filters, core.equal(typeSystem, e, arg.e));
        }
      }
      final Map<Core.IdPat, Core.Exp> scans2 = plus(scans, idPat, a1);
      return rewrite(mapper, boundPats2, scans2, filters2, exps2);
    } else {
      return null;
    }
  }

  /**
   * Returns an expression as a list of conjunctions.
   *
   * <p>For example
   * {@code conjunctions(a andalso b)}
   * returns [{@code a}, {@code b}] (two elements);
   * {@code conjunctions(a andalso b andalso c)}
   * returns [{@code a}, {@code b}, {@code c}] (three elements);
   * {@code conjunctions(a orelse b)}
   * returns [{@code a orelse b}] (one element);
   * {@code conjunctions(true)}
   * returns [] (no elements);
   * {@code conjunctions(false)}
   * returns [{@code false}] (one element).
   */
  static List<Core.Exp> conjunctions(Core.Exp e) {
    final ImmutableList.Builder<Core.Exp> b = ImmutableList.builder();
    addConjunctions(b, e);
    return b.build();
  }

  private static void addConjunctions(ImmutableList.Builder<Core.Exp> b,
      Core.Exp e) {
    if (e.op == Op.APPLY
        && ((Core.Apply) e).fn.op == Op.FN_LITERAL
        && ((Core.Literal) ((Core.Apply) e).fn).value == BuiltIn.Z_ANDALSO) {
      ((Core.Apply) e).args().forEach(a -> addConjunctions(b, a));
    } else if (e.op != Op.BOOL_LITERAL
        || !((boolean) ((Core.Literal) e).value)) {
      // skip true
      b.add(e);
    }
  }

  /** Workspace for converting a particular {@link Core.From} from "suchthat"
   * to "in" form. */
  static class FromState {
    final Core.From from;
    final List<Core.FromStep> steps = new ArrayList<>();

    FromState(Core.From from) {
      this.from = from;
    }

    Core.FromStep currentStep() {
      // We assume that from.steps are translated 1:1 into steps that get added
      // to this.steps. If steps.size() is N, we are currently working on
      // from.steps.get(N).
      return from.steps.get(steps.size());
    }
  }

  /** Maps patterns from their name in the "from" to their name after a sequence
   * of renames.
   *
   * <p>For example, in "case (x, y) of (a, b) => a + b", "x" is renamed to "a"
   * and "y" is renamed to "b". */
  private static class PatMap {
    private final ImmutableMap<Core.NamedPat, Core.NamedPat> map;

    PatMap(ImmutableMap<Core.NamedPat, Core.NamedPat> map) {
      this.map = map;
    }

    static PatMap of(Core.Pat pat, Core.Exp exp) {
      final ImmutableMap.Builder<Core.NamedPat, Core.NamedPat> builder =
          ImmutableMap.builder();
      populate(pat, exp, builder);
      return new PatMap(builder.build());
    }

    private static void populate(Core.Pat pat, Core.Exp exp,
        ImmutableMap.Builder<Core.NamedPat, Core.NamedPat> nameBuilder) {
      switch (pat.op) {
      case ID_PAT:
        nameBuilder.put(Pair.of(((Core.Id) exp).idPat, (Core.IdPat) pat));
        break;
      case TUPLE_PAT:
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        final Core.Tuple tuple = (Core.Tuple) exp;
        forEach(tuplePat.args, tuple.args,
            (pat2, exp2) -> populate(pat2, exp2, nameBuilder));
        break;
      }
    }

    Core.NamedPat apply(Core.NamedPat p) {
      for (Map.Entry<Core.NamedPat, Core.NamedPat> pair : map.entrySet()) {
        if (pair.getValue().equals(p)) {
          p = pair.getKey();
        }
      }
      return p;
    }
  }

  /** Signals that we could not rewrite. */
  private static class RewriteFailedException extends RuntimeException {
    RewriteFailedException(String message) {
      super(message);
    }
  }
}

// End SuchThatShuttle.java
