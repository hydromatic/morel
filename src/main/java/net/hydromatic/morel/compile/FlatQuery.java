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

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.JoinType;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * A relational tree read as a flat query: the collections it scans, each under
 * a variable, the conditions over those variables, and the element it yields.
 *
 * <p>This is the shape that generator analysis reads -- "{@code exists z where
 * step andalso f (z, n - 1)}" is a scan of {@code z} and a condition -- and a
 * tree of joins and filters flattens to it, and a projection at the root is the
 * query's yield. A node of any other kind is opaque, and is scanned whole: a
 * projection below the root too, because its element is a value of its own, and
 * a reading that substituted it into the conditions above would give the
 * analysis a field of a scan variable where it expects a variable.
 */
class FlatQuery {
  /** A collection scanned under a variable. */
  static class Scan {
    final Core.IdPat pat;
    final Core.Exp exp;

    Scan(Core.IdPat pat, Core.Exp exp) {
      this.pat = pat;
      this.exp = exp;
    }
  }

  final ImmutableList<Scan> scans;
  final ImmutableList<Core.Exp> conditions;

  /** The element, over the scans' variables. */
  final Core.Exp element;

  private FlatQuery(
      List<Scan> scans, List<Core.Exp> conditions, Core.Exp element) {
    this.scans = ImmutableList.copyOf(scans);
    this.conditions = ImmutableList.copyOf(conditions);
    this.element = element;
  }

  /** Flattens a collection-valued expression. */
  static FlatQuery of(TypeSystem typeSystem, Core.Exp exp) {
    final Flattener flattener = new Flattener(typeSystem);
    final Core.Exp element = flattener.element(exp, true);
    return new FlatQuery(flattener.scans, flattener.conditions, element);
  }

  /** Returns the conditions as one expression, or null if there are none. */
  Core.@Nullable Exp condition(TypeSystem typeSystem) {
    return conditions.isEmpty() ? null : core.andAlso(typeSystem, conditions);
  }

  /** Walks a tree, collecting scans and conditions. */
  private static class Flattener {
    final TypeSystem typeSystem;
    final List<Scan> scans = new ArrayList<>();
    final List<Core.Exp> conditions = new ArrayList<>();

    Flattener(TypeSystem typeSystem) {
      this.typeSystem = typeSystem;
    }

    /**
     * Adds a node's scans and conditions, and returns the expression, over the
     * scans' variables, that denotes its element.
     */
    Core.Exp element(Core.Exp node, boolean root) {
      if (node instanceof Core.Filter) {
        final Core.Filter filter = (Core.Filter) node;
        if (filter.ordinalId == null) {
          final Core.Exp e = element(filter.input, false);
          conditions.add(subst(filter.condition, filter.id, e));
          return e;
        }
      }
      if (root && node instanceof Core.Project) {
        // The query's own yield: what it yields is the element, over the
        // scans below. A projection anywhere else is a value of its own, and
        // is scanned whole.
        final Core.Project project = (Core.Project) node;
        if (project.ordinalId == null) {
          final Core.Exp e = element(project.input, false);
          return subst(project.exp, project.id, e);
        }
      }
      if (node instanceof Core.Join) {
        final Core.Join join = (Core.Join) node;
        if (join.joinType == JoinType.INNER && join.ordinalId == null) {
          final Core.Exp left = element(join.left, false);
          // A dependent right input reads the left element by its binder.
          final Core.Exp right =
              element(subst(join.right, join.leftId, left), false);
          conditions.add(
              subst(
                  subst(join.condition, join.leftId, left),
                  join.rightId,
                  right));
          final List<Core.Exp> exps =
              new ArrayList<>(core.components(typeSystem, join.left, left));
          exps.addAll(core.components(typeSystem, join.right, right));
          return core.tuple(typeSystem, null, exps);
        }
      }
      // A leaf, or a node this reading does not open.
      final Type elementType = node.type.elementType();
      final Core.IdPat pat =
          core.idPat(elementType, typeSystem.nameGenerator.getPrefixed("w"), 0);
      scans.add(new Scan(pat, node));
      return core.id(pat);
    }

    /**
     * Replaces a pattern with an expression, reading a field of a tuple built
     * there off the tuple.
     */
    Core.Exp subst(Core.Exp exp, Core.IdPat pat, Core.Exp replacement) {
      return exp.accept(
          new Shuttle(typeSystem) {
            @Override
            protected Core.Exp visit(Core.Id id) {
              return id.idPat.equals(pat) ? core.at(replacement, id.pos()) : id;
            }

            @Override
            protected Core.Exp visit(Core.Apply apply) {
              return RelCompiler.readField(super.visit(apply));
            }
          });
    }
  }
}

// End FlatQuery.java
