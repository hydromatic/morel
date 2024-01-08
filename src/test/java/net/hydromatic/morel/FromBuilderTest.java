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
package net.hydromatic.morel;

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static net.hydromatic.morel.ast.CoreBuilder.core;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;

/**
 * Test {@link net.hydromatic.morel.ast.FromBuilder}.
 */
public class FromBuilderTest {
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();
    final PrimitiveType intType = PrimitiveType.INT;
    final Core.IdPat aPat = core.idPat(intType, "a", 0);
    final Core.Id aId = core.id(aPat);
    final Core.IdPat bPat = core.idPat(intType, "b", 0);
    final Core.IdPat iPat = core.idPat(intType, "i", 0);
    final Core.Id iId = core.id(iPat);
    final Core.IdPat jPat = core.idPat(intType, "j", 0);
    final Core.Id jId = core.id(jPat);
    final Core.Exp list12 = core.list(typeSystem, intLiteral(1), intLiteral(2));
    final Core.Exp list34 = core.list(typeSystem, intLiteral(3), intLiteral(4));

    Core.Literal intLiteral(int i) {
      return core.literal(intType, i);
    }

    Core.Exp record(Core.Id... ids) {
      final PairList<String, Core.Exp> nameExps = PairList.of();
      Arrays.asList(ids).forEach(id -> nameExps.add(id.idPat.name, id));
      return core.record(typeSystem, nameExps);
    }

    FromBuilder fromBuilder() {
      return core.fromBuilder(typeSystem, Environments.empty());
    }
  }

  @Test void testBasic() {
    // from i in [1, 2]
    final Fixture f = new Fixture();
    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder.scan(f.iPat, f.list12);

    final Core.From from = fromBuilder.build();
    assertThat(from, hasToString("from i in [1, 2]"));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, hasToString("[1, 2]"));

    // "from i in [1, 2] yield i" --> "[1, 2]"
    fromBuilder.yield_(f.iId);
    final Core.From from2 = fromBuilder.build();
    assertThat(from2, is(from));
    final Core.Exp e2 = fromBuilder.buildSimplify();
    assertThat(e2, is(e));
  }

  @Test void testWhereOrder() {
    // from i in [1, 2] where i < 2 order i desc
    //  ==>
    // from i in [1, 2]
    final Fixture f = new Fixture();
    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder.scan(f.iPat, f.list12)
        .where(core.lessThan(f.typeSystem, f.iId, f.intLiteral(2)))
        .order(ImmutableList.of(core.orderItem(f.iId, Ast.Direction.DESC)));

    final Core.From from = fromBuilder.build();
    assertThat(from.toString(),
        is("from i in [1, 2] where i < 2 order i desc"));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));

    // "where true" and "order {}" are ignored
    fromBuilder.where(core.boolLiteral(true))
        .order(ImmutableList.of())
        .where(core.greaterThan(f.typeSystem, f.iId, f.intLiteral(1)));
    final Core.From from2 = fromBuilder.build();
    assertThat(from2.toString(),
        is("from i in [1, 2] where i < 2 order i desc where i > 1"));
    final Core.Exp e2 = fromBuilder.buildSimplify();
    assertThat(e2, is(from2));
  }

  @Test void testTrivialYield() {
    // from i in [1, 2] where i < 2 yield i
    final Fixture f = new Fixture();
    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder.scan(f.iPat, f.list12)
        .where(core.lessThan(f.typeSystem, f.iId, f.intLiteral(2)))
        .yield_(f.iId);

    final Core.From from = fromBuilder.build();
    assertThat(from, hasToString("from i in [1, 2] where i < 2"));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));
  }

  @Test void testTrivialYield2() {
    // from j in [1, 2], i in [3, 4] where i < 2 yield {i, j}
    //   ==>
    // from j in [1, 2], i in [3, 4] where i < 2
    final Fixture f = new Fixture();
    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder.scan(f.jPat, f.list12)
        .scan(f.iPat, f.list34)
        .where(core.lessThan(f.typeSystem, f.iId, f.intLiteral(2)))
        .yield_(f.record(f.iId, f.jId));

    final Core.From from = fromBuilder.build();
    final String expected = "from j in [1, 2] "
        + "join i in [3, 4] "
        + "where i < 2";
    assertThat(from, hasToString(expected));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));
  }

  @Test void testTrivialYield3() {
    // from j in [1, 2] yield {j} join i in [3, 4]
    //   ==>
    // from j in [1, 2] join i in [3, 4]
    final Fixture f = new Fixture();
    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder.scan(f.jPat, f.list12)
        .yield_(f.record(f.jId))
        .scan(f.iPat, f.list34);

    final Core.From from = fromBuilder.build();
    final String expected = "from j in [1, 2] "
        + "join i in [3, 4]";
    assertThat(from, hasToString(expected));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));
  }

  @Test void testNested() {
    // from i in (from j in [1, 2] where j < 2) where i > 1
    //   ==>
    // from j in [1, 2] where j < 2 yield {i = j} where i > 1
    final Fixture f = new Fixture();
    final Core.From innerFrom =
        f.fromBuilder()
            .scan(f.jPat, f.list12)
            .where(core.lessThan(f.typeSystem, f.jId, f.intLiteral(2)))
            .build();

    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder.scan(f.iPat, innerFrom)
        .where(core.greaterThan(f.typeSystem, f.iId, f.intLiteral(1)));

    final Core.From from = fromBuilder.build();
    final String expected = "from j in [1, 2] "
        + "where j < 2 "
        + "yield {i = j} "
        + "where i > 1";
    assertThat(from, hasToString(expected));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));
  }

  /** As {@link #testNested()} but inner and outer variables have the same
   * name, and therefore no yield is required. */
  @Test void testNestedSameName() {
    // from i in (from i in [1, 2] where i < 2) where i > 1
    //   ==>
    // from i in [1, 2] where i < 2 where i > 1
    final Fixture f = new Fixture();
    final Core.From innerFrom =
        f.fromBuilder()
            .scan(f.iPat, f.list12)
            .where(core.lessThan(f.typeSystem, f.iId, f.intLiteral(2)))
            .build();

    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder.scan(f.iPat, innerFrom)
        .where(core.greaterThan(f.typeSystem, f.iId, f.intLiteral(1)));

    final Core.From from = fromBuilder.build();
    final String expected = "from i in [1, 2] "
        + "where i < 2 "
        + "where i > 1";
    assertThat(from, hasToString(expected));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));
  }

  @Test void testNested0() {
    // from i in (from)
    //   ==>
    // from
    final Fixture f = new Fixture();
    final Core.From innerFrom = f.fromBuilder().build();

    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder.scan(f.iPat, innerFrom);

    final Core.From from = fromBuilder.build();
    assertThat(from, hasToString("from i in (from)"));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, hasToString("from"));
  }

  @Test void testNested2() {
    // from {i = a, j = b} in (from a in [1, 2], b in [3, 4] where a < 2)
    //   where i < j
    //   ==>
    // from a in [1, 2], b in [3, 4] where a < 2 yield {i = a, j = b}
    //   where i < j
    final Fixture f = new Fixture();
    final Core.From innerFrom =
        f.fromBuilder()
            .scan(f.aPat, f.list12)
            .scan(f.bPat, f.list34)
            .where(core.lessThan(f.typeSystem, f.aId, f.intLiteral(2)))
            .build();

    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder.scan(
        core.recordPat(f.typeSystem, ImmutableSet.of("i", "j"),
            ImmutableList.of(f.aPat, f.bPat)),
            innerFrom)
        .where(core.lessThan(f.typeSystem, f.iId, f.jId));

    final Core.From from = fromBuilder.build();
    final String expected = "from a in [1, 2] "
        + "join b in [3, 4] "
        + "where a < 2 "
        + "yield {i = a, j = b} "
        + "where i < j";
    assertThat(from, hasToString(expected));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));
  }
}

// End FromBuilderTest.java
