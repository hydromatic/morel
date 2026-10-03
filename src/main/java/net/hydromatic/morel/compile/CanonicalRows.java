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

import static net.hydromatic.morel.ast.CoreBuilder.core;

import java.util.SortedMap;
import java.util.TreeMap;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.JoinType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * Canonical patterns for a pass that writes a node's expressions before it has
 * the node.
 *
 * <p>Every node binds patterns of its own, distinct from every other node's. A
 * pass that reads expressions off several nodes and moves them between nodes is
 * simplest written over one id: an expression read off a node is rewritten over
 * the canonical patterns first, and an expression written into a node is
 * rewritten over patterns minted for that node.
 */
class CanonicalRows {
  /** Ordinal of the canonical patterns; no node's pattern has it. */
  static final int CANON = -1;

  static final Core.IdPat ID = core.idPat(PrimitiveType.UNIT, "$0", CANON);
  static final Core.IdPat RIGHT_ID =
      core.idPat(PrimitiveType.UNIT, "$1", CANON);
  static final Core.IdPat ORDINAL =
      core.idPat(PrimitiveType.INT, "$ordinal", CANON);

  private final TypeSystem typeSystem;

  CanonicalRows(TypeSystem typeSystem) {
    this.typeSystem = typeSystem;
  }

  /** Canonical id by which a node refers to its current element, {@code $0}. */
  Core.Id id0(Type elementType) {
    return core.id(core.idPat(elementType, "$0", CANON));
  }

  /**
   * Canonical id by which a join refers to its right input's current element,
   * {@code $1}.
   */
  Core.Id id1(Type elementType) {
    return core.id(core.idPat(elementType, "$1", CANON));
  }

  /**
   * Returns whether an expression is the canonical reference to input {@code
   * i}.
   */
  static boolean isId(Core.Exp exp, int i) {
    return exp instanceof Core.Id
        && ((Core.Id) exp).idPat.equals(i == 0 ? ID : RIGHT_ID);
  }

  /** Rewrites an expression of a node over the canonical patterns. */
  Core.Exp canonical(Core.Rel rel, Core.Exp exp) {
    Core.Exp e = exp;
    if (rel instanceof Core.Join) {
      final Core.Join join = (Core.Join) rel;
      e = core.substitute(typeSystem, e, join.leftId, id0(join.leftId.type));
      e = core.substitute(typeSystem, e, join.rightId, id1(join.rightId.type));
      if (join.ordinalId != null) {
        e = core.substitute(typeSystem, e, join.ordinalId, core.id(ORDINAL));
      }
    } else if (rel instanceof Core.RowRel) {
      final Core.RowRel rowRel = (Core.RowRel) rel;
      e = core.substitute(typeSystem, e, rowRel.id, id0(rowRel.id.type));
      if (rowRel.ordinalId != null) {
        e = core.substitute(typeSystem, e, rowRel.ordinalId, core.id(ORDINAL));
      }
    }
    return e;
  }

  /**
   * Rewrites an expression over the canonical patterns to be over a node's own.
   */
  Core.Exp real(
      Core.Exp exp,
      Core.IdPat id,
      Core.@Nullable IdPat rightId,
      Core.@Nullable IdPat ordinalId) {
    Core.Exp e = core.substitute(typeSystem, exp, ID, core.id(id));
    if (rightId != null) {
      e = core.substitute(typeSystem, e, RIGHT_ID, core.id(rightId));
    }
    if (ordinalId != null) {
      e = core.substitute(typeSystem, e, ORDINAL, core.id(ordinalId));
    }
    return e;
  }

  /** Mints the ordinal pattern for a node whose expressions read one. */
  Core.@Nullable IdPat ordinalFor(Core.Exp... exps) {
    for (Core.Exp exp : exps) {
      if (Core.mentions(exp, ORDINAL)) {
        return core.ordinalPat(typeSystem.nameGenerator::inc);
      }
    }
    return null;
  }

  Core.IdPat elementPat(Core.Exp input) {
    return core.elementPat(
        input.type.elementType(), typeSystem.nameGenerator::inc);
  }

  /** Builds a filter from a condition over the canonical id. */
  Core.Filter filter(Core.Exp input, Core.Exp condition) {
    final Core.IdPat id = elementPat(input);
    final Core.@Nullable IdPat ordinalId = ordinalFor(condition);
    return core.filter(
        id, ordinalId, input, real(condition, id, null, ordinalId));
  }

  /** Builds a projection from an expression over the canonical id. */
  Core.Project project(Core.Exp input, Core.Exp exp) {
    final Core.IdPat id = elementPat(input);
    final Core.@Nullable IdPat ordinalId = ordinalFor(exp);
    return core.project(
        typeSystem, id, ordinalId, input, real(exp, id, null, ordinalId));
  }

  /** Builds a sort from a key over the canonical id. */
  Core.Sort sort(Core.Exp input, Core.Exp exp) {
    final Core.IdPat id = elementPat(input);
    final Core.@Nullable IdPat ordinalId = ordinalFor(exp);
    return core.sort(
        typeSystem, id, ordinalId, input, real(exp, id, null, ordinalId));
  }

  /** Builds a group from keys and aggregates over the canonical id. */
  Core.Group group(
      Core.Exp input,
      SortedMap<String, Core.Exp> keys,
      SortedMap<String, Core.Aggregate> aggregates) {
    final Core.IdPat id = elementPat(input);
    final TreeMap<String, Core.Exp> keys2 = new TreeMap<>(keys.comparator());
    keys.forEach((name, key) -> keys2.put(name, real(key, id, null, null)));
    final TreeMap<String, Core.Aggregate> aggregates2 =
        new TreeMap<>(aggregates.comparator());
    aggregates.forEach(
        (name, agg) ->
            aggregates2.put(
                name,
                core.aggregate(
                    agg.pos(),
                    agg.type,
                    real(agg.aggregate, id, null, null),
                    agg.argument == null
                        ? null
                        : real(agg.argument, id, null, null))));
    return core.group(typeSystem, id, null, input, keys2, aggregates2);
  }

  /** Builds a join from a condition over the canonical ids. */
  Core.Join join(
      JoinType joinType, Core.Exp left, Core.Exp right, Core.Exp condition) {
    return dependentJoin(joinType, left, right, null, condition);
  }

  /**
   * Builds a join whose right input may read the left element through a name,
   * which becomes the join's left id.
   */
  Core.Join dependentJoin(
      JoinType joinType,
      Core.Exp left,
      Core.Exp right,
      Core.@Nullable IdPat param,
      Core.Exp condition) {
    final Core.IdPat leftId = elementPat(left);
    final Core.IdPat rightId =
        core.rightElementPat(
            right.type.elementType(), typeSystem.nameGenerator::inc);
    final Core.@Nullable IdPat ordinalId = ordinalFor(condition);
    final Core.Exp right2 =
        param == null
            ? right
            : core.substitute(typeSystem, right, param, core.id(leftId));
    return core.join(
        typeSystem,
        joinType,
        leftId,
        rightId,
        ordinalId,
        left,
        right2,
        real(condition, leftId, rightId, ordinalId));
  }
}

// End CanonicalRows.java
