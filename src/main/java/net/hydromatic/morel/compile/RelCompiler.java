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
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.JoinType;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.compile.Compiler.Context;
import net.hydromatic.morel.compile.Compiler.RowSinkFactory;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Comparators;
import net.hydromatic.morel.eval.RowSink;
import net.hydromatic.morel.eval.RowSinks;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.ImmutablePairList;
import org.jspecify.annotations.Nullable;

/**
 * Compiles a relational tree to a chain of {@link RowSink}s.
 *
 * <p>A node's element travels to the node above it bound to the pattern by
 * which that node reads it, in one of two ways. A scan pushes each element onto
 * the stack, and so does a node that binds an ordinal (the count, beside its
 * element) and a group (the record of its outputs). Every other node defers its
 * element: it hands up an expression over the slots below -- a filter its id, a
 * projection its expression, a join the tuple of its inputs' components -- and
 * the node above substitutes that expression for the pattern before it compiles
 * its own. So a filter above a join reads the joined slots directly, and a
 * projection below a filter costs nothing until something reads a field. A node
 * that needs its element as a value -- a set operator, an outer join that wraps
 * each component in {@code option} -- materializes it into slots with a {@link
 * RowSinks#yield(List, int, int[], RowSink) yield} that pops what it replaces.
 *
 * <p>The expressions of a node are compiled against a {@link Context} whose
 * layout maps the node's patterns to those slots, so a reference to a pattern
 * is a stack read. A node's inputs other than the one it streams -- a join's
 * right input, a set operator's later inputs -- are compiled as expressions
 * that evaluate to collections, which a nested tree does via {@link #compile}.
 */
class RelCompiler {
  private static final Code TRUE = Codes.constant(true);

  private final Compiler compiler;
  private final TypeSystem typeSystem;

  RelCompiler(Compiler compiler, TypeSystem typeSystem) {
    this.compiler = compiler;
    this.typeSystem = typeSystem;
  }

  /** Compiles a tree to code that evaluates it to a collection. */
  Code compile(Context cx, Core.Rel rel) {
    final Core.IdPat target = freshPat(rel.type.elementType());
    return RowSinks.from(stream(cx, rel, target, new Collect(target)));
  }

  /** Downstream of a node: builds the sink that consumes the node's element. */
  private interface Next {
    RowSinkFactory sink(Row row);
  }

  /** Downstream of a root: collects the elements. */
  private static class Collect implements Next {
    final Core.IdPat target;

    Collect(Core.IdPat target) {
      this.target = target;
    }

    @Override
    public RowSinkFactory sink(Row row) {
      final Code code = row.code(core.id(target));
      return () -> RowSinks.collect(code, null);
    }
  }

  /**
   * Compiles a node so that its element arrives above {@code base}, bound to
   * {@code target}, and hands that row to {@code next}.
   */
  private RowSinkFactory stream(
      Context base, Core.Exp node, Core.IdPat target, Next next) {
    if (!(node instanceof Core.Rel)) {
      return scan(base, node, target, next);
    }
    switch (node.op) {
      case FILTER:
        return filter(base, (Core.Filter) node, target, next);
      case PROJECT:
        return project(base, (Core.Project) node, target, next);
      case SORT:
        return sort(base, (Core.Sort) node, target, next);
      case GROUP:
        return group(base, (Core.Group) node, target, next);
      case JOIN:
        return join(base, (Core.Join) node, target, next);
      case SKIP:
        final Core.Skip skip = (Core.Skip) node;
        final Code skipCode = compiler.compile(base, skip.count);
        return stream(
            base,
            skip.input,
            target,
            row -> {
              final RowSinkFactory nf = next.sink(row);
              return () -> RowSinks.skip(skipCode, nf.get());
            });
      case TAKE:
        final Core.Take take = (Core.Take) node;
        final Code takeCode = compiler.compile(base, take.count);
        return stream(
            base,
            take.input,
            target,
            row -> {
              final RowSinkFactory nf = next.sink(row);
              return () -> RowSinks.take(takeCode, nf.get());
            });
      case UNORDER:
        // A change of type only; ordered and unordered streams have the same
        // representation.
        return stream(base, ((Core.Unorder) node).input, target, next);
      case UNION:
      case INTERSECT:
      case EXCEPT:
        return setOp(base, (Core.SetRel) node, target, next);
      default:
        throw new AssertionError("cannot compile " + node.op);
    }
  }

  /** Scans a collection-valued expression, one slot per element. */
  private RowSinkFactory scan(
      Context base, Core.Exp collection, Core.IdPat target, Next next) {
    final Code code = compiler.compile(base, collection);
    final RowSinkFactory nf = next.sink(rowOf(base).push(target));
    return () ->
        RowSinks.scan(Op.SCAN, target, 1, false, code, TRUE, null, nf.get());
  }

  private RowSinkFactory filter(
      Context base, Core.Filter filter, Core.IdPat target, Next next) {
    return stream(
        base,
        filter.input,
        filter.id,
        row0 ->
            withOrdinal(
                row0,
                filter.ordinalId,
                row -> {
                  final Code condition = row.code(filter.condition);
                  final RowSinkFactory nf =
                      next.sink(row.defer(target, core.id(filter.id)));
                  return () -> RowSinks.where(condition, nf.get());
                }));
  }

  private RowSinkFactory project(
      Context base, Core.Project project, Core.IdPat target, Next next) {
    return stream(
        base,
        project.input,
        project.id,
        row0 ->
            withOrdinal(
                row0,
                project.ordinalId,
                row -> next.sink(row.defer(target, project.exp))));
  }

  private RowSinkFactory sort(
      Context base, Core.Sort sort, Core.IdPat target, Next next) {
    return stream(
        base,
        sort.input,
        sort.id,
        row0 ->
            withOrdinal(
                row0,
                sort.ordinalId,
                row -> {
                  final Code key = row.code(sort.exp);
                  final Comparator comparator =
                      Comparators.comparatorFor(
                          typeSystem, sort.exp.type, sort.exp.pos());
                  // The sink pushes every slot back before it emits an
                  // element, so the downstream sees the layout it sees here.
                  final ImmutablePairList<String, Code> inSlots = row.inSlots();
                  final RowSinkFactory nf =
                      next.sink(row.defer(target, core.id(sort.id)));
                  return () ->
                      RowSinks.order(key, comparator, inSlots, nf.get());
                }));
  }

  private RowSinkFactory group(
      Context base, Core.Group group, Core.IdPat target, Next next) {
    return stream(
        base,
        group.input,
        group.id,
        row0 ->
            withOrdinal(
                row0,
                group.ordinalId,
                row -> group2(group, row, target, next)));
  }

  private RowSinkFactory group2(
      Core.Group group, Row row, Core.IdPat target, Next next) {
    final List<Code> keyCodes = transformEager(group.keys.values(), row::code);
    final Code keyCode = Codes.tuple(keyCodes);
    // The sink captures the row's slots, and pushes them back for each
    // element when it evaluates an aggregate's argument.
    final ImmutablePairList<String, Code> inSlots = row.inSlots();
    final int scanDepth = inSlots.size();
    final ImmutableList.Builder<Applicable> aggregateCodes =
        ImmutableList.builder();
    for (Core.Aggregate aggregate : group.aggregates.values()) {
      final @Nullable Code argumentCode =
          aggregate.argument == null ? null : row.code(aggregate.argument);
      final FnType fnType = FnType.of(aggregate.aggregate.type);
      final Core.Exp aggregateExp = row.resolve(aggregate.aggregate);
      final @Nullable Applicable applicable =
          compiler.compileApplicable(
              row.cx, aggregateExp, fnType.paramType, aggregate.pos());
      // An aggregate function runs when the elements have been read and the
      // stack is back at the base, so it is compiled there.
      final Code aggregateCode =
          applicable == null
              ? compiler.compile(row.base, aggregateExp)
              : applicable.asCode();
      aggregateCodes.add(
          Codes.aggregate(
              row.cx.env,
              aggregateCode,
              inSlots.leftList(),
              argumentCode,
              scanDepth));
    }
    final ImmutableList<String> keyNames =
        ImmutableList.copyOf(group.keys.keySet());
    final ImmutableList<String> outNames =
        ImmutableList.<String>builder()
            .addAll(keyNames)
            .addAll(group.aggregates.keySet())
            .build();
    // The sink leaves each output in the environment under its label, with the
    // stack at the base. The element is the record of them, which a yield
    // builds into the target slot: deferring it would leave a sort or a group
    // above to capture environment values that the sink restores when it is
    // done.
    final Type elementType = group.type.elementType();
    final Code record;
    if (elementType instanceof RecordLikeType
        && !((RecordLikeType) elementType).argNames().isEmpty()) {
      record =
          Codes.tuple(
              transformEager(
                  ((RecordLikeType) elementType).argNames(), Codes::get));
    } else {
      record = Codes.constant(Unit.INSTANCE);
    }
    final RowSinkFactory nf = emit(rowOf(row.base), record, target, next);
    return () ->
        RowSinks.group(
            keyCode,
            aggregateCodes.build(),
            inSlots,
            scanDepth,
            keyNames,
            outNames,
            nf.get());
  }

  private RowSinkFactory setOp(
      Context base, Core.SetRel setRel, Core.IdPat target, Next next) {
    return stream(
        base,
        setRel.inputs.get(0),
        target,
        row0 ->
            materialize(
                row0, target, row -> setOp2(setRel, row, target, next)));
  }

  /** Compiles a set operator whose first input's element is the one slot. */
  private RowSinkFactory setOp2(
      Core.SetRel setRel, Row row, Core.IdPat target, Next next) {
    final List<Core.Exp> others =
        setRel.inputs.subList(1, setRel.inputs.size());
    // A non-distinct 'except' or 'intersect' reads its other inputs when the
    // first element arrives, with that element on the stack; the rest read them
    // when the elements have all arrived, with the stack at the base.
    final boolean eager =
        !setRel.distinct
            && (setRel.op == Op.EXCEPT || setRel.op == Op.INTERSECT);
    final Context cxArg = eager ? row.cx : row.base;
    final ImmutableList<Code> codes =
        transformEager(others, e -> compiler.compile(cxArg, row.resolve(e)));
    // The element is the whole row, whatever its type, so the sink keys on
    // the one slot, as an atom.
    final ImmutableList<String> names = ImmutableList.of(target.name);
    final ImmutablePairList<String, Code> inSlots = row.inSlots();
    final RowSinkFactory nf = next.sink(row);
    switch (setRel.op) {
      case UNION:
        return () ->
            RowSinks.union(
                setRel.distinct, codes, names, true, inSlots, nf.get());
      case INTERSECT:
        return () ->
            RowSinks.intersect(
                setRel.distinct, codes, names, true, inSlots, nf.get());
      default:
        return () ->
            RowSinks.except(
                setRel.distinct, codes, names, true, inSlots, nf.get());
    }
  }

  private RowSinkFactory join(
      Context base, Core.Join join, Core.IdPat target, Next next) {
    final boolean build = join.joinType.leftIsOption();
    // The right input runs once per left element, and sees that element's
    // position. A 'right' or 'full' join reads its right input once, before
    // any left element, so it cannot depend on one.
    final boolean rightReadsOrdinal =
        join.ordinalId != null && Core.mentions(join.right, join.ordinalId);
    checkArgument(
        !build || !(join.isDependent() || rightReadsOrdinal),
        "right input of a %s join cannot read the left id",
        join.joinType);
    return stream(
        base,
        join.left,
        join.leftId,
        row0 ->
            withOrdinal(
                row0,
                rightReadsOrdinal ? join.ordinalId : null,
                row1 -> join2(join, rightReadsOrdinal, row1, target, next)));
  }

  private RowSinkFactory join2(
      Core.Join join,
      boolean rightReadsOrdinal,
      Row row1,
      Core.IdPat target,
      Next next) {
    final boolean build = join.joinType.leftIsOption();
    final List<Core.Exp> leftExps =
        core.components(typeSystem, join.left, core.id(join.leftId));
    if (build && !(row1.slots.size() == 1 && row1.isSlot(join.leftId))) {
      // The sink wraps the slots above the base in 'option', one each, and the
      // element has one option per component; so lay the components out as
      // slots, and read the left element as the tuple of them.
      final List<Core.NamedPat> pats =
          transformEager(leftExps, e -> freshPat(e.type));
      final List<Core.Exp> ids = transformEager(pats, core::id);
      final Core.Exp leftElement =
          ids.size() == 1 ? ids.get(0) : core.tuple(typeSystem, null, ids);
      final Core.Exp condition =
          core.substitute(typeSystem, join.condition, join.leftId, leftElement);
      return relayout(
          row1,
          transformEager(leftExps, row1::code),
          pats,
          row ->
              join3(
                  join, rightReadsOrdinal, row, condition, ids, target, next));
    }
    return join3(
        join, rightReadsOrdinal, row1, join.condition, leftExps, target, next);
  }

  private RowSinkFactory join3(
      Core.Join join,
      boolean rightReadsOrdinal,
      Row row,
      Core.Exp condition,
      List<Core.Exp> leftComponents,
      Core.IdPat target,
      Next next) {
    final boolean build = join.joinType.leftIsOption();
    final List<Core.Exp> rightExps =
        core.components(typeSystem, join.right, core.id(join.rightId));
    final List<Core.NamedPat> rightPats;
    final Core.Pat rightPat;
    final List<Core.Exp> rightComponents;
    if (join.joinType.rightIsOption() && rightExps.size() > 1) {
      // As for the left side: the sink wraps each slot it pushes, so push one
      // per component.
      rightPats = transformEager(rightExps, e -> freshPat(e.type));
      rightComponents = transformEager(rightPats, core::id);
      rightPat = core.tuplePat(typeSystem, rightPats);
      condition =
          core.substitute(
              typeSystem,
              condition,
              join.rightId,
              core.tuple(typeSystem, null, rightComponents));
    } else {
      rightPats = ImmutableList.of(join.rightId);
      rightPat = join.rightId;
      rightComponents = rightExps;
    }
    // A right input that reads the left id is evaluated once per left element,
    // with that element on the stack; one that does not is evaluated once,
    // before any left element, with the stack at the base -- as a build
    // join's is.
    final boolean dependent = join.isDependent() || rightReadsOrdinal;
    final Code rightCode =
        compiler.compile(
            build || !dependent ? row.base : row.cx, row.resolve(join.right));
    Row rowScan = row;
    for (Core.NamedPat pat : rightPats) {
      rowScan = rowScan.push(pat);
    }
    // The condition runs once per candidate pair, and sees the pair's
    // position, which the scan counts.
    final int[] pairSlots = {0};
    Context cxCondition = rowScan.cx;
    if (join.ordinalId != null && Core.mentions(condition, join.ordinalId)) {
      pairSlots[0] = 1;
      final Core.IdPat pairPat = freshPat(join.ordinalId.type);
      condition =
          core.substitute(
              typeSystem, condition, join.ordinalId, core.id(pairPat));
      cxCondition =
          cxCondition.bindAll(
              ImmutableList.of(
                  Binding.of(pairPat, (Object) Codes.ordinalGet(pairSlots))));
    }
    Code conditionCode =
        compiler.compile(cxCondition, rowScan.resolve(condition));
    final int @Nullable [] liveSlots;
    if (pairSlots[0] == 0) {
      liveSlots = null;
    } else {
      liveSlots = pairSlots;
      conditionCode = Codes.ordinalInc(pairSlots, conditionCode);
    }
    // The element is the inputs' components in order.
    final List<Core.Exp> exps = new ArrayList<>(leftComponents);
    exps.addAll(rightComponents);
    final RowSinkFactory nf =
        next.sink(rowScan.defer(target, core.tuple(typeSystem, null, exps)));
    final Code conditionCode2 = conditionCode;
    if (build) {
      final int leftSlotCount = row.slots.size();
      return () ->
          RowSinks.buildJoin(
              op(join.joinType),
              rightPat,
              rightPats.size(),
              leftSlotCount,
              rightCode,
              conditionCode2,
              liveSlots,
              nf.get());
    }
    return () ->
        RowSinks.scan(
            op(join.joinType),
            rightPat,
            rightPats.size(),
            dependent,
            rightCode,
            conditionCode2,
            liveSlots,
            nf.get());
  }

  private static Op op(JoinType joinType) {
    switch (joinType) {
      case LEFT:
        return Op.LEFT_JOIN;
      case RIGHT:
        return Op.RIGHT_JOIN;
      case FULL:
        return Op.FULL_JOIN;
      default:
        return Op.SCAN;
    }
  }

  /**
   * Pushes a node's ordinal beside its row, if the node binds one, and hands
   * the row to {@code next}.
   */
  private RowSinkFactory withOrdinal(
      Row row, Core.@Nullable IdPat ordinal, Next next) {
    if (ordinal == null) {
      return next.sink(row);
    }
    final int[] slots = {0};
    final Code code = Codes.ordinalInc(slots, Codes.ordinalGet(slots));
    final RowSinkFactory nf = next.sink(row.push(ordinal));
    return () -> RowSinks.yield(ImmutableList.of(code), 0, slots, nf.get());
  }

  /**
   * Makes {@code target} the one slot above the base, if it is not that
   * already, and hands the row to {@code next}.
   */
  private RowSinkFactory materialize(Row row, Core.IdPat target, Next next) {
    if (row.slots.size() == 1 && row.isSlot(target)) {
      return next.sink(row);
    }
    return emit(row, row.code(core.id(target)), target, next);
  }

  /**
   * Emits a value as the one slot above the base, bound to {@code target},
   * popping the slots the node pushed.
   */
  private RowSinkFactory emit(
      Row row, Code code, Core.IdPat target, Next next) {
    if (next instanceof Collect) {
      // The root collects the value; no need to put it in a slot first.
      return () -> RowSinks.collect(code, null);
    }
    return relayout(
        row, ImmutableList.of(code), ImmutableList.of(target), next);
  }

  /**
   * Replaces the slots above the base with the values of {@code codes}, bound
   * to {@code pats}.
   */
  private RowSinkFactory relayout(
      Row row,
      List<Code> codes,
      List<? extends Core.NamedPat> pats,
      Next next) {
    Row row2 = rowOf(row.base);
    for (Core.NamedPat pat : pats) {
      row2 = row2.push(pat);
    }
    final RowSinkFactory nf = next.sink(row2);
    final int popCount = row.slots.size();
    return () -> RowSinks.yield(codes, popCount, null, nf.get());
  }

  /** Creates the row at a query's base: no slots. */
  private Row rowOf(Context base) {
    return new Row(base, base, ImmutableList.of(), ImmutableMap.of());
  }

  private Core.IdPat freshPat(Type type) {
    return core.elementPat(type, typeSystem.nameGenerator::inc);
  }

  /**
   * Reads a field out of a tuple that is being constructed here: {@code #2 (x,
   * y)} becomes {@code y}. Only where the selector was made for this tuple's
   * type; a substitution can put a tuple of another arity under it.
   */
  static Core.Exp readField(Core.Exp exp) {
    if (exp instanceof Core.Apply) {
      final Core.Apply apply = (Core.Apply) exp;
      if (apply.fn instanceof Core.RecordSelector
          && apply.arg instanceof Core.Tuple
          && ((FnType) apply.fn.type).paramType.equals(apply.arg.type)) {
        final int slot = ((Core.RecordSelector) apply.fn).slot;
        return ((Core.Tuple) apply.arg).args.get(slot);
      }
    }
    return exp;
  }

  /**
   * The stack above a query's base: the pattern in each slot, the context that
   * reads them, and the elements that are deferred as expressions over them.
   */
  private final class Row {
    final Context base;
    final Context cx;
    final ImmutableList<Core.NamedPat> slots;
    final ImmutableMap<Core.NamedPat, Core.Exp> deferred;

    Row(
        Context base,
        Context cx,
        ImmutableList<Core.NamedPat> slots,
        ImmutableMap<Core.NamedPat, Core.Exp> deferred) {
      this.base = base;
      this.cx = cx;
      this.slots = slots;
      this.deferred = deferred;
    }

    /** Pushes a slot. */
    Row push(Core.NamedPat pat) {
      final Context cx2 =
          new Context(
              cx.env.bindAll(ImmutableList.of(Binding.of(pat))),
              cx.layout.with(pat, cx.localDepth),
              cx.localDepth + 1,
              cx.globalSlotMap,
              cx.recPeers);
      return new Row(
          base,
          cx2,
          ImmutableList.<Core.NamedPat>builder().addAll(slots).add(pat).build(),
          deferred);
    }

    /** Defers a pattern to an expression over the slots. */
    Row defer(Core.NamedPat pat, Core.Exp exp) {
      return new Row(
          base,
          cx,
          slots,
          ImmutableMap.<Core.NamedPat, Core.Exp>builder()
              .putAll(deferred)
              .put(pat, resolve(exp))
              .build());
    }

    /** Returns whether a pattern is in a slot (rather than deferred). */
    boolean isSlot(Core.NamedPat pat) {
      return slots.contains(pat) && !deferred.containsKey(pat);
    }

    /**
     * Replaces each deferred pattern in an expression with its expression, and
     * reads a field of a tuple built there off the tuple.
     */
    Core.Exp resolve(Core.Exp exp) {
      if (deferred.isEmpty()) {
        return exp;
      }
      return exp.accept(
          new Shuttle(typeSystem) {
            @Override
            protected Core.Exp visit(Core.Id id) {
              final Core.@Nullable Exp e = deferred.get(id.idPat);
              return e != null ? e : id;
            }

            @Override
            protected Core.Exp visit(Core.Apply apply) {
              return readField(super.visit(apply));
            }
          });
    }

    /** Compiles an expression over this row. */
    Code code(Core.Exp exp) {
      return compiler.compile(cx, resolve(exp));
    }

    /** Returns a (name, code) pair that reads each slot. */
    ImmutablePairList<String, Code> inSlots() {
      return ImmutablePairList.fromTransformed(
          slots, (pat, add) -> add.accept(pat.name, cx.fieldCode(pat)));
    }
  }
}

// End RelCompiler.java
