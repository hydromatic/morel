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

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Pair;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VariableCollector}.
 *
 * <p>Verifies that {@link VariableCollector} correctly identifies free
 * (captured) variables in function bodies.
 */
public class VariableCollectorTest {
  private final TypeSystem typeSystem = new TypeSystem();

  {
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());
  }

  /**
   * Runs VariableCollector on {@code exp} with {@code outerEnv} and returns the
   * names of the free variables found, joined by commas.
   */
  private String collectFree(Environment outerEnv, Core.Exp exp) {
    final Map<Core.NamedPat, VariableCollector.Scope> scopeMap =
        new LinkedHashMap<>();
    VariableCollector.create(
            typeSystem, outerEnv, (id, scope) -> scopeMap.put(id.idPat, scope))
        .accept(exp);
    return scopeMap.keySet().stream()
        .map(p -> p.name)
        .collect(Collectors.joining(", "));
  }

  /**
   * Tests that a variable referenced inside a {@code fn} but declared in the
   * outer environment is reported as free.
   *
   * <p>{@code fn x => y} — {@code y} is free, {@code x} is not.
   */
  @Test
  void testSimpleFreeVariable() {
    // Build outer env: {y: int}
    final Core.IdPat yPat = core.idPat(PrimitiveType.INT, "y", 0);
    final Environment outerEnv = Environments.empty().bind(Binding.of(yPat, 0));

    // Build: fn x => y
    final Core.IdPat xPat = core.idPat(PrimitiveType.INT, "x", 0);
    final FnType fnType =
        typeSystem.fnType(PrimitiveType.INT, PrimitiveType.INT);
    final Core.Fn fn = core.fn(fnType, xPat, core.id(yPat));

    assertThat(collectFree(outerEnv, fn), is("y"));
  }

  /**
   * Tests that the function argument is NOT reported as free.
   *
   * <p>{@code fn x => x} — no free variables.
   */
  @Test
  void testNoFreeVariables() {
    final Core.IdPat yPat = core.idPat(PrimitiveType.INT, "y", 0);
    final Environment outerEnv = Environments.empty().bind(Binding.of(yPat, 0));

    // fn x => x  (argument, no free vars)
    final Core.IdPat xPat = core.idPat(PrimitiveType.INT, "x", 0);
    final FnType fnType =
        typeSystem.fnType(PrimitiveType.INT, PrimitiveType.INT);
    final Core.Fn fn = core.fn(fnType, xPat, core.id(xPat));

    assertThat(collectFree(outerEnv, fn), is(""));
  }

  /**
   * Tests that only referenced free variables are reported, not all outer
   * variables.
   *
   * <p>{@code fn x => y} in env {y, z} — y is reported, z is not referenced.
   */
  @Test
  void testOnlyReferencedFreeVariables() {
    final Core.IdPat yPat = core.idPat(PrimitiveType.INT, "y", 0);
    final Core.IdPat zPat = core.idPat(PrimitiveType.INT, "z", 0);
    final Environment outerEnv =
        Environments.empty()
            .bind(Binding.of(yPat, 0))
            .bind(Binding.of(zPat, 0));

    // fn x => y  (only y is referenced)
    final Core.IdPat xPat = core.idPat(PrimitiveType.INT, "x", 0);
    final FnType fnType =
        typeSystem.fnType(PrimitiveType.INT, PrimitiveType.INT);
    final Core.Fn fn = core.fn(fnType, xPat, core.id(yPat));

    assertThat(collectFree(outerEnv, fn), is("y"));
  }

  /**
   * Tests that a direct reference (no lambda crossing) is NOT reported.
   *
   * <p>VariableCollector only reports variables crossed through a {@code fn}
   * boundary. A direct reference to {@code y} (not inside any {@code fn}) is
   * never reported, even if {@code y} is in the outer environment.
   */
  @Test
  void testNoBoundaryCrossing() {
    final Core.IdPat yPat = core.idPat(PrimitiveType.INT, "y", 0);
    final Environment outerEnv = Environments.empty().bind(Binding.of(yPat, 0));

    // Just: y  (no fn, no lambda boundary)
    final Core.Id yId = core.id(yPat);

    assertThat(collectFree(outerEnv, yId), is(""));
  }

  /**
   * Tests a nested lambda: only variables from the outermost outer scope are
   * reported, not intermediate-scope variables.
   *
   * <p>{@code fn x => fn z => y} in env {y: int} — {@code y} is free, {@code x}
   * and {@code z} are local.
   */
  @Test
  void testNestedFnFreeVariable() {
    final Core.IdPat yPat = core.idPat(PrimitiveType.INT, "y", 0);
    final Environment outerEnv = Environments.empty().bind(Binding.of(yPat, 0));

    // fn x => fn z => y
    final Core.IdPat xPat = core.idPat(PrimitiveType.INT, "x", 0);
    final Core.IdPat zPat = core.idPat(PrimitiveType.INT, "z", 0);
    final FnType innerType =
        typeSystem.fnType(PrimitiveType.INT, PrimitiveType.INT);
    final FnType outerType = typeSystem.fnType(PrimitiveType.INT, innerType);
    final Core.Fn inner = core.fn(innerType, zPat, core.id(yPat));
    final Core.Fn outer = core.fn(outerType, xPat, inner);

    assertThat(collectFree(outerEnv, outer), is("y"));
  }

  /**
   * Tests that a variable not present in the outer environment is not reported,
   * even when referenced inside a {@code fn}.
   */
  @Test
  void testVariableNotInOuterEnv() {
    // Outer env is empty (no user-defined variables)
    final Environment outerEnv = Environments.empty();

    // fn x => x  — x is the argument, not in outer env
    final Core.IdPat xPat = core.idPat(PrimitiveType.INT, "x", 0);
    final FnType fnType =
        typeSystem.fnType(PrimitiveType.INT, PrimitiveType.INT);
    final Core.Fn fn = core.fn(fnType, xPat, core.id(xPat));

    assertThat(collectFree(outerEnv, fn), is(""));
  }

  /** Tests that isAncestorOf correctly identifies ancestor relationships. */
  @Test
  void testIsAncestorOf() {
    final Core.IdPat aPat = core.idPat(PrimitiveType.INT, "a", 0);
    final Core.IdPat bPat = core.idPat(PrimitiveType.INT, "b", 0);
    final Core.IdPat cPat = core.idPat(PrimitiveType.INT, "c", 0);

    // Build a chain: empty → bind(a) → bind(b) → bind(c)
    final Environment e0 = Environments.empty();
    final Environment e1 = e0.bind(Binding.of(aPat, 1));
    final Environment e2 = e1.bind(Binding.of(bPat, 2));
    final Environment e3 = e2.bind(Binding.of(cPat, 3));

    // An environment is its own ancestor
    assertThat(e0.isAncestorOf(e0), is(true));
    assertThat(e1.isAncestorOf(e1), is(true));
    assertThat(e3.isAncestorOf(e3), is(true));

    // Older environments are ancestors of newer ones
    assertThat(e0.isAncestorOf(e1), is(true));
    assertThat(e0.isAncestorOf(e2), is(true));
    assertThat(e0.isAncestorOf(e3), is(true));
    assertThat(e1.isAncestorOf(e2), is(true));
    assertThat(e1.isAncestorOf(e3), is(true));
    assertThat(e2.isAncestorOf(e3), is(true));

    // Newer environments are NOT ancestors of older ones
    assertThat(e1.isAncestorOf(e0), is(false));
    assertThat(e2.isAncestorOf(e0), is(false));
    assertThat(e3.isAncestorOf(e1), is(false));
  }

  /** Tests that getOpt2 returns the correct declaring environment. */
  @Test
  void testGetOpt2ReturnsDeclaringEnv() {
    final Core.IdPat aPat = core.idPat(PrimitiveType.INT, "a", 0);
    final Core.IdPat bPat = core.idPat(PrimitiveType.INT, "b", 0);

    final Environment e0 = Environments.empty();
    final Environment e1 = e0.bind(Binding.of(aPat, 1));
    final Environment e2 = e1.bind(Binding.of(bPat, 2));

    // getOpt2 for "a" should return the environment that declared "a" (e1)
    final Pair<Binding, Environment> pairA = e2.getOpt2(aPat);
    assertThat(pairA, notNullValue());
    final Environment envA = requireNonNull(pairA).right;
    assertThat(envA.isAncestorOf(e2), is(true));
    // The declaring env for "a" has e0 as an ancestor
    assertThat(e0.isAncestorOf(envA), is(true));

    // getOpt2 for "b" should return the environment that declared "b" (e2)
    final Pair<Binding, Environment> pairB = e2.getOpt2(bPat);
    assertThat(pairB, notNullValue());
    assertThat(requireNonNull(pairB).right, is(e2));

    // getOpt2 for unknown variable returns null
    final Core.IdPat zPat = core.idPat(PrimitiveType.INT, "z", 0);
    assertThat(e2.getOpt2(zPat), is((Pair<Binding, Environment>) null));
  }
}

// End VariableCollectorTest.java
