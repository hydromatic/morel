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

import java.util.ArrayList;
import java.util.List;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;
import org.apache.calcite.util.Holder;

/**
 * Converts unbounded variables to bounded variables.
 *
 * <p>For example, converts
 *
 * <pre>{@code
 * from e
 *   where e elem #emps scott
 * }</pre>
 *
 * <p>to
 *
 * <pre>{@code
 * from e in #emps scott
 * }</pre>
 */
class SuchThatShuttle extends EnvShuttle {
  /** True if we're inside a recursive function definition. */
  private final boolean inRecursiveFunction;

  /**
   * True if the query being visited supplies only whether it has rows, not the
   * rows themselves, because it is the argument of {@code Relational.nonEmpty}
   * or {@code Relational.empty} - that is, it came from 'exists' or 'forall'.
   * Such a query may discard a variable that it cannot enumerate, as 'y' is
   * discarded in "exists x, y where x elem [1, 2, 3]".
   */
  private final boolean rowsUnused;

  SuchThatShuttle(TypeSystem typeSystem, Environment env) {
    this(typeSystem, env, false, false);
  }

  private SuchThatShuttle(
      TypeSystem typeSystem,
      Environment env,
      boolean inRecursiveFunction,
      boolean rowsUnused) {
    super(typeSystem, env);
    this.inRecursiveFunction = inRecursiveFunction;
    this.rowsUnused = rowsUnused;
  }

  @Override
  protected EnvShuttle push(Environment env) {
    return new SuchThatShuttle(
        typeSystem, env, inRecursiveFunction, rowsUnused);
  }

  @Override
  protected Core.Exp visit(Core.Apply apply) {
    if (apply.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)
        || apply.isCallTo(BuiltIn.RELATIONAL_EMPTY)) {
      final SuchThatShuttle inner =
          new SuchThatShuttle(typeSystem, env, inRecursiveFunction, true);
      return apply.copy(apply.fn.accept(inner), apply.arg.accept(inner));
    }
    return super.visit(apply);
  }

  @Override
  protected Core.RecValDecl visit(Core.RecValDecl recValDecl) {
    // When visiting recursive function definitions, mark that we're inside
    // so that From expressions won't be expanded (they're part of the
    // function definition, not queries to execute).
    final List<Binding> bindings = new ArrayList<>();
    Compiles.bindPattern(typeSystem, bindings, recValDecl);
    final SuchThatShuttle inner =
        new SuchThatShuttle(
            typeSystem, env.bindAll(bindings), true, rowsUnused);
    return recValDecl.copy(inner.visitList(recValDecl.list));
  }

  static boolean containsUnbounded(Core.Decl decl) {
    final Holder<Boolean> found = Holder.of(false);
    decl.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Scan scan) {
            super.visit(scan);
            if (Extents.isInfinite(scan.exp)
                || RangePushdown.isInfiniteRangeScan(scan)) {
              found.set(true);
            }
          }
        });
    return found.get();
  }

  @Override
  protected Core.Exp visit(Core.From from) {
    // Skip expansion for "from" expressions inside recursive function
    // definitions. These are part of the function's logic, not queries to
    // execute. The outer query will handle transitive closure detection.
    if (inRecursiveFunction) {
      return super.visit(from);
    }

    final Core.From from2 =
        Expander.expandFrom(typeSystem, env, from, !rowsUnused);

    // Expand subqueries.
    return super.visit(from2);
  }
}

// End SuchThatShuttle.java
