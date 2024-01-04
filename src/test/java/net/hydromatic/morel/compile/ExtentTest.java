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
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.compile.Extents.generator;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.core.Is.is;

/**
 * Tests for {@link Extents}.
 */
public class ExtentTest {
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();
    final PrimitiveType intType = PrimitiveType.INT;
    final Core.IdPat aPat = core.idPat(intType, "a", 0);
    final Core.Id aId = core.id(aPat);
    final Core.Exp list12 = core.list(typeSystem, intLiteral(1), intLiteral(2));

    Core.Literal intLiteral(int i) {
      return core.intLiteral(BigDecimal.valueOf(i));
    }
  }

  /** Tests whether an expression is constant.
   *
   * @see Core.Exp#isConstant() */
  @Test void testConstant() {
    final Fixture f = new Fixture();
    assertThat("1 is literal",
        core.intLiteral(BigDecimal.ONE).isConstant(), is(true));
    assertThat("false is literal",
        core.boolLiteral(false).isConstant(), is(true));
    assertThat("a is literal",
        core.charLiteral('a').isConstant(), is(true));
    assertThat("3.14 is literal",
        core.realLiteral(3.14f).isConstant(), is(true));
    assertThat("string is literal",
        core.stringLiteral("pi").isConstant(), is(true));
    assertThat("identifier is not literal",
        f.aId.isConstant(), is(false));
    assertThat("list of constants is constant",
        f.list12.isConstant(), is(true));

    assertThat("unit is constant",
        core.tuple(f.typeSystem, null, ImmutableList.of()).isConstant(),
        is(true));

    final Map<String, Core.Exp> map =
        ImmutableMap.of("a", f.intLiteral(1), "b",
            core.boolLiteral(true));
    assertThat("record of constants is constant",
        core.record(f.typeSystem, map).isConstant(), is(true));
    final List<Core.Exp> list = ImmutableList.copyOf(map.values());
    assertThat("tuple of constants is constant",
        core.tuple(f.typeSystem, null, list).isConstant(), is(true));

    final Map<String, Core.Exp> map2 =
        ImmutableMap.of("a", f.intLiteral(1), "b", f.aId);
    assertThat("record that contains an id is not constant",
        core.record(f.typeSystem, map2).isConstant(), is(false));
    final List<Core.Exp> list2 = ImmutableList.copyOf(map2.values());
    assertThat("tuple that contains an id is not constant",
        core.tuple(f.typeSystem, null, list2).isConstant(), is(false));

    // TODO: check that zero-arg constructor (e.g. NIL) is constant
    // TODO: check that one-arg constructor (e.g. CONS (1, [])) is constant
  }

  /** Tests a variable assigned a single value. */
  @Test void testEq() {
    final Fixture f = new Fixture();

    // pat = "x", exp = "x = 10", extent = "[10]"
    Core.IdPat xPat = core.idPat(PrimitiveType.INT, "x", 0);
    Core.Literal ten = f.intLiteral(10);
    Core.Exp exp = core.equal(f.typeSystem, core.id(xPat), ten);
    Core.Exp x = generator(f.typeSystem, xPat, exp);
    assertThat(x, hasToString("[10]"));

    // pat = "x", exp = "10 = x", extent = "[10]"
    Core.Exp exp2 = core.equal(f.typeSystem, ten, core.id(xPat));
    Core.Exp x2 = generator(f.typeSystem, xPat, exp2);
    assertThat(x2, hasToString("[10]"));
  }

  @Test void testBetween() {
    // pat = "x", exp = "x >= 3 andalso y = 20 andalso x < 10 andalso 5 <> x",
    // extent of x is "extent [[3..5), (5..10)]";
    // extent of y is "extent [[3..5), (5..10)]";
    final Fixture f = new Fixture();
    Core.IdPat xPat = core.idPat(PrimitiveType.INT, "x", 0);
    Core.IdPat yPat = core.idPat(PrimitiveType.INT, "y", 0);
    Core.Literal three = f.intLiteral(3);
    Core.Literal five = f.intLiteral(5);
    Core.Literal ten = f.intLiteral(10);
    Core.Literal twenty = f.intLiteral(20);
    Core.Exp exp0 = core.greaterThanOrEqualTo(f.typeSystem, core.id(xPat), three);
    Core.Exp exp1 = core.equal(f.typeSystem, core.id(yPat), twenty);
    Core.Exp exp2 = core.lessThan(f.typeSystem, core.id(xPat), ten);
    Core.Exp exp3 = core.notEqual(f.typeSystem, core.id(xPat), five);
    final Core.Exp exp =
        core.andAlso(f.typeSystem, exp0,
            core.andAlso(f.typeSystem, exp1,
                core.andAlso(f.typeSystem, exp2, exp3)));
    Core.Exp x = generator(f.typeSystem, xPat, exp);
    assertThat(x, instanceOf(Core.Apply.class));
    assertThat(((Core.Apply) x).fn, instanceOf(Core.Literal.class));
    assertThat(((Core.Literal) ((Core.Apply) x).fn).unwrap(BuiltIn.class),
        is(BuiltIn.Z_EXTENT));
    assertThat(x, hasToString("extent \"int [[3..5), (5..10)]\""));

    Core.Exp y = generator(f.typeSystem, yPat, exp);
    assertThat(y, instanceOf(Core.Apply.class));
    assertThat(((Core.Apply) y).fn, instanceOf(Core.Literal.class));
    assertThat(((Core.Literal) ((Core.Apply) y).fn).unwrap(BuiltIn.class),
        is(BuiltIn.Z_LIST));
    assertThat(y, hasToString("[20]"));
  }

}

// End ExtentTest.java
