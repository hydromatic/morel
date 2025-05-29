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

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.junit.jupiter.api.Test;

/** Test {@link net.hydromatic.morel.ast.FromBuilder}. */
public class FromBuilderTest {
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();
    final List<Binding> bindings = new ArrayList<>();

    {
      // Register 'bag'; keep only the 'DESC' binding.
      BuiltIn.dataTypes(typeSystem, bindings);
      bindings.removeIf(b -> !b.id.name.equals("DESC"));
    }

    final PrimitiveType intType = PrimitiveType.INT;
    final PrimitiveType unitType = PrimitiveType.UNIT;
    final Type intPairType = typeSystem.tupleType(intType, intType);
    final Core.IdPat aPat = core.idPat(intType, "a", 0);
    final Core.Id aId = core.id(aPat);
    final Core.IdPat bPat = core.idPat(intType, "b", 0);
    final Core.Id bId = core.id(bPat);
    final Core.IdPat dPat = core.idPat(intPairType, "d", 0);
    final Core.Id dId = core.id(dPat);
    final Core.IdPat iPat = core.idPat(intType, "i", 0);
    final Core.Id iId = core.id(iPat);
    final Core.IdPat jPat = core.idPat(intType, "j", 0);
    final Core.Id jId = core.id(jPat);
    final Core.IdPat uPat = core.idPat(unitType, "u", 0);
    final Core.Exp list12 = core.list(typeSystem, intLiteral(1), intLiteral(2));
    final Core.Exp list34 = core.list(typeSystem, intLiteral(3), intLiteral(4));
    final Core.Exp tuple12 =
        core.tuple(typeSystem, intLiteral(1), intLiteral(2));
    final Core.Exp tuple34 =
        core.tuple(typeSystem, intLiteral(3), intLiteral(4));

    Core.Literal intLiteral(int i) {
      return core.literal(intType, i);
    }

    Core.Exp record(Core.Id... ids) {
      final PairList<String, Core.Exp> nameExps = PairList.of();
      Arrays.asList(ids).forEach(id -> nameExps.add(id.idPat.name, id));
      return core.record(typeSystem, nameExps);
    }

    FromBuilder fromBuilder() {
      Environment env = Environments.empty().bindAll(bindings);
      return core.fromBuilder(typeSystem, env);
    }
  }

  @Test
  void testBasic() {
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

  @Test
  void testDistinct() {
    // from i in [1, 2] distinct
    final Fixture f = new Fixture();
    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder.scan(f.iPat, f.list12);
    fromBuilder.distinct();

    final Core.From from = fromBuilder.build();
    assertThat(from, hasToString("from i in [1, 2] group i = i"));

    // from i in [1, 2],
    //   j in [3, 4]
    //   distinct
    //   where i < j
    fromBuilder.clear();
    fromBuilder.scan(f.iPat, f.list12);
    fromBuilder.scan(f.jPat, f.list34);
    fromBuilder.distinct();
    fromBuilder.where(core.lessThan(f.typeSystem, f.iId, f.jId));

    final Core.From from2 = fromBuilder.build();
    assertThat(
        from2,
        hasToString(
            "from i in [1, 2] join j in [3, 4] "
                + "group i = i, j = j where i < j"));
  }

  @Test
  void testWhereOrder() {
    // from i in [1, 2] where i < 2 order i desc
    //  ==>
    // from i in [1, 2]
    final Fixture f = new Fixture();
    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder
        .scan(f.iPat, f.list12)
        .where(core.lessThan(f.typeSystem, f.iId, f.intLiteral(2)))
        .order(ImmutableList.of(core.orderItem(f.iId, Ast.Direction.DESC)));

    final Core.From from = fromBuilder.build();
    assertThat(from, hasToString("from i in [1, 2] where i < 2 order i desc"));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));

    // "where true" and "order {}" are ignored
    fromBuilder
        .where(core.boolLiteral(true))
        .order(ImmutableList.of())
        .where(core.greaterThan(f.typeSystem, f.iId, f.intLiteral(1)));
    final Core.From from2 = fromBuilder.build();
    assertThat(
        from2,
        hasToString("from i in [1, 2] where i < 2 order i desc where i > 1"));
    final Core.Exp e2 = fromBuilder.buildSimplify();
    assertThat(e2, is(from2));
  }

  @Test
  void testTrivialYield() {
    // from i in [1, 2] where i < 2 yield i
    final Fixture f = new Fixture();
    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder
        .scan(f.iPat, f.list12)
        .where(core.lessThan(f.typeSystem, f.iId, f.intLiteral(2)))
        .yield_(f.iId);

    final Core.From from = fromBuilder.build();
    assertThat(from, hasToString("from i in [1, 2] where i < 2"));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));
  }

  @Test
  void testTrivialYield2() {
    // from j in [1, 2], i in [3, 4] where i < 2 yield {i, j}
    //   ==>
    // from j in [1, 2], i in [3, 4] where i < 2
    final Fixture f = new Fixture();
    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder
        .scan(f.jPat, f.list12)
        .scan(f.iPat, f.list34)
        .where(core.lessThan(f.typeSystem, f.iId, f.intLiteral(2)))
        .yield_(f.record(f.iId, f.jId));

    final Core.From from = fromBuilder.build();
    final String expected = "from j in [1, 2] join i in [3, 4] where i < 2";
    assertThat(from, hasToString(expected));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));
  }

  @Test
  void testTrivialYield3() {
    // from j in [1, 2] yield {j} join i in [3, 4]
    //   ==>
    // from j in [1, 2] join i in [3, 4]
    final Fixture f = new Fixture();
    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder
        .scan(f.jPat, f.list12)
        .yield_(f.record(f.jId))
        .scan(f.iPat, f.list34);

    final Core.From from = fromBuilder.build();
    final String expected = "from j in [1, 2] join i in [3, 4]";
    assertThat(from, hasToString(expected));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));
  }

  @Test
  void testNested() {
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
    fromBuilder
        .scan(f.iPat, innerFrom)
        .where(core.greaterThan(f.typeSystem, f.iId, f.intLiteral(1)));

    final Core.From from = fromBuilder.build();
    final String expected =
        "from j in [1, 2] where j < 2 yield {i = j} where i > 1";
    assertThat(from, hasToString(expected));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));
  }

  @Test
  void testNested3() {
    // from i in (from j in [1, 2]) where i > 1
    //   ==>
    // from j in [1, 2] yield {i = j} where i > 1
    final Fixture f = new Fixture();
    final Core.From innerFrom = f.fromBuilder().scan(f.jPat, f.list12).build();

    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder
        .scan(f.iPat, innerFrom)
        .where(core.greaterThan(f.typeSystem, f.iId, f.intLiteral(1)));

    final Core.From from = fromBuilder.build();
    final String expected = "from j in [1, 2] yield {i = j} where i > 1";
    assertThat(from, hasToString(expected));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));

    // from j in (from j in [1, 2]) where j > 1
    //   ==>
    // from j in [1, 2] where j > 1
    final FromBuilder fromBuilder2 = f.fromBuilder();
    fromBuilder2
        .scan(f.jPat, innerFrom)
        .where(core.greaterThan(f.typeSystem, f.jId, f.intLiteral(1)));

    final Core.From from2 = fromBuilder2.build();
    final String expected2 = "from j in [1, 2] where j > 1";
    assertThat(from2, hasToString(expected2));
    final Core.Exp e2 = fromBuilder2.buildSimplify();
    assertThat(e2, is(from2));

    // from i in (from j in [1, 2])
    //   ==>
    // from j in [1, 2]
    //   ==> simplification
    // [1, 2]
    final FromBuilder fromBuilder3 = f.fromBuilder();
    fromBuilder3.scan(f.iPat, innerFrom);

    final Core.From from3 = fromBuilder3.build();
    final String expected3 = "from j in [1, 2]";
    assertThat(from3, hasToString(expected3));
    final Core.Exp e3 = fromBuilder3.buildSimplify();
    assertThat(e3, is(f.list12));
  }

  @Test
  void testNested4() {
    // from d in [(1, 2), (3, 4)]
    // join i in (from i in [#1 d])
    //   ==>
    // from d in [(1, 2), (3, 4)]
    // join i in [#1 d]
    final Fixture f = new Fixture();
    final Function<List<Binding>, Core.From> innerFrom =
        bindings ->
            core.fromBuilder(
                    f.typeSystem, Environments.empty().bindAll(bindings))
                .scan(
                    f.iPat,
                    core.list(f.typeSystem, core.field(f.typeSystem, f.dId, 0)))
                .build();
    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder
        .scan(f.dPat, core.list(f.typeSystem, f.tuple12, f.tuple34))
        .scan(f.iPat, innerFrom.apply(fromBuilder.stepEnv().bindings));

    final Core.From from = fromBuilder.build();
    final String expected = "from d in [(1, 2), (3, 4)] join i in [#1 d]";
    assertThat(from, hasToString(expected));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));

    // from d in [(1, 2), (3, 4)]
    // join j in (from i in [#1 d])
    // where j > #1 d
    //   ==>
    // from d in [(1, 2), (3, 4)]
    // join i in [#1 d]
    // yield {d, j = i}
    // where j > #1 d
    final FromBuilder fromBuilder2 = f.fromBuilder();
    fromBuilder2
        .scan(f.dPat, core.list(f.typeSystem, f.tuple12, f.tuple34))
        .scan(f.jPat, innerFrom.apply(fromBuilder.stepEnv().bindings))
        .where(
            core.greaterThan(
                f.typeSystem, f.jId, core.field(f.typeSystem, f.dId, 0)));

    final Core.From from2 = fromBuilder2.build();
    final String expected2 =
        "from d in [(1, 2), (3, 4)] "
            + "join i in [#1 d] "
            + "yield {d = d, j = i} "
            + "where j > #1 d";
    assertThat(from2, hasToString(expected2));
    final Core.Exp e2 = fromBuilder2.buildSimplify();
    assertThat(e2, is(from2));
  }

  /**
   * As {@link #testNested()} but inner and outer variables have the same name,
   * and therefore no yield is required.
   */
  @Test
  void testNestedSameName() {
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
    fromBuilder
        .scan(f.iPat, innerFrom)
        .where(core.greaterThan(f.typeSystem, f.iId, f.intLiteral(1)));

    final Core.From from = fromBuilder.build();
    final String expected = "from i in [1, 2] where i < 2 where i > 1";
    assertThat(from, hasToString(expected));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));
  }

  @Test
  void testNested0() {
    // from u in (from)
    //   ==>
    // from
    final Fixture f = new Fixture();
    final Core.From innerFrom = f.fromBuilder().build();

    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder.scan(f.uPat, innerFrom);

    final Core.From from = fromBuilder.build();
    assertThat(from, hasToString("from u in (from)"));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, hasToString("from"));
  }

  @Test
  void testNested2() {
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
    fromBuilder
        .scan(
            core.recordPat(
                f.typeSystem,
                ImmutableSet.of("i", "j"),
                ImmutableList.of(f.aPat, f.bPat)),
            innerFrom)
        .where(core.lessThan(f.typeSystem, f.iId, f.jId));

    final Core.From from = fromBuilder.build();
    final String expected =
        "from a in [1, 2] "
            + "join b in [3, 4] "
            + "where a < 2 "
            + "yield {i = a, j = b} "
            + "where i < j";
    assertThat(from, hasToString(expected));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));
  }

  @Test
  void testNestedFromTuple() {
    // from (a, b) in
    //   (from (a, b) in
    //     (from a in [1, 2] join b in [3, 4]))
    // where a > b andalso b = 10
    // yield b
    //   ==>
    // from a in [1, 2]
    // join b in [3, 4]
    // where a > b andalso a = 10
    // yield b
    final Fixture f = new Fixture();
    final Core.Pat abPat =
        core.tuplePat(f.typeSystem, Arrays.asList(f.aPat, f.bPat));

    final Core.From innermostFrom =
        f.fromBuilder().scan(f.aPat, f.list12).scan(f.bPat, f.list34).build();
    final Core.From innerFrom =
        f.fromBuilder().scan(abPat, innermostFrom).build();
    final FromBuilder fromBuilder = f.fromBuilder();
    fromBuilder
        .scan(abPat, innerFrom)
        .where(
            core.andAlso(
                f.typeSystem,
                core.greaterThan(f.typeSystem, f.aId, f.bId),
                core.equal(
                    f.typeSystem, f.aId, core.intLiteral(BigDecimal.TEN))))
        .yield_(f.bId);

    final Core.From from = fromBuilder.build();
    final String expected =
        "from a in [1, 2] "
            + "join b in [3, 4] "
            + "where a > b andalso a = 10 "
            + "yield b";
    assertThat(from, hasToString(expected));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));

    // Tuple where variables are not in alphabetical order. Requires
    // a 'yield' step to re-order variables.
    //
    // from (b, a) in
    //   (from a in [1, 2] join b in [3, 4])
    // where a > b andalso b = 10
    // yield b
    //   ==>
    // from a in [1, 2]
    // join b in [3, 4]
    // yield {a = b, b = a}
    // where a > b andalso a = 10
    // yield b
    final Core.Pat baPat =
        core.tuplePat(f.typeSystem, Arrays.asList(f.bPat, f.aPat));
    final FromBuilder fromBuilder2 = f.fromBuilder();
    fromBuilder2
        .scan(baPat, innermostFrom)
        .where(
            core.andAlso(
                f.typeSystem,
                core.greaterThan(f.typeSystem, f.aId, f.bId),
                core.equal(
                    f.typeSystem, f.aId, core.intLiteral(BigDecimal.TEN))))
        .yield_(f.bId);

    final Core.From from2 = fromBuilder2.build();
    final String expected2 =
        "from a in [1, 2] "
            + "join b in [3, 4] "
            + "yield {a = b, b = a} "
            + "where a > b andalso a = 10 "
            + "yield b";
    assertThat(from2, hasToString(expected2));
    final Core.Exp e2 = fromBuilder2.buildSimplify();
    assertThat(e2, is(from2));

    // from (i, j) in
    //   (from a in [1, 2] join b in [3, 4])
    // where i > j andalso j = 10
    // yield i
    //   ==>
    // from a in [1, 2]
    // join b in [3, 4]
    // yield {i = a, j = b}
    // where i > j andalso j = 10
    // yield i
    final Core.Pat ijPat =
        core.tuplePat(f.typeSystem, Arrays.asList(f.iPat, f.jPat));
    final FromBuilder fromBuilder3 = f.fromBuilder();
    fromBuilder3
        .scan(ijPat, innermostFrom)
        .where(
            core.andAlso(
                f.typeSystem,
                core.greaterThan(f.typeSystem, f.iId, f.jId),
                core.equal(
                    f.typeSystem, f.jId, core.intLiteral(BigDecimal.TEN))))
        .yield_(f.jId);

    final Core.From from3 = fromBuilder3.build();
    final String expected3 =
        "from a in [1, 2] "
            + "join b in [3, 4] "
            + "yield {i = a, j = b} "
            + "where i > j andalso j = 10 "
            + "yield j";
    assertThat(from3, hasToString(expected3));
    final Core.Exp e3 = fromBuilder3.buildSimplify();
    assertThat(e3, is(from3));
  }
}

// End FromBuilderTest.java
