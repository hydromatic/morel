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
import static org.apache.calcite.linq4j.tree.Expressions.list;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.core.Is.is;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.ImmutablePairList;
import net.hydromatic.morel.util.PairList;
import org.junit.jupiter.api.Test;

/** Tests for {@link Extents}. */
public class ExtentTest {
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();

    {
      // Register 'bag'
      BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    }

    final PrimitiveType intType = PrimitiveType.INT;
    final Core.IdPat aPat = core.idPat(intType, "a", 0);
    final Core.Id aId = core.id(aPat);
    final Core.IdPat bPat = core.idPat(intType, "b", 0);
    final Core.Id bId = core.id(bPat);
    final Core.IdPat cPat = core.idPat(intType, "c", 0);
    final Core.Id cId = core.id(cPat);
    final Core.IdPat dPat = core.idPat(intType, "d", 0);
    final Core.Id dId = core.id(dPat);
    final Core.Exp list12 = core.list(typeSystem, intLiteral(1), intLiteral(2));
    final RecordLikeType deptType =
        typeSystem.recordType(
            RecordType.map(
                "deptno",
                PrimitiveType.INT,
                "dname",
                PrimitiveType.STRING,
                "loc",
                PrimitiveType.STRING));
    final Type deptBagType = typeSystem.bagType(deptType);
    final Core.IdPat depts = core.idPat(deptBagType, "depts", 0);

    Core.Literal intLiteral(int i) {
      return core.intLiteral(BigDecimal.valueOf(i));
    }

    void checkFlatten(Core.Exp exp, String s, String s2) {
      final List<Core.Exp> andExps = core.decomposeAnd(exp);
      assertThat(andExps, hasToString(s));
      assertThat(
          core.andAlso(typeSystem, andExps), hasToString(exp.toString()));

      final List<Core.Exp> orExps = core.decomposeOr(exp);
      assertThat(orExps, hasToString(s2));
      assertThat(core.orElse(typeSystem, orExps), hasToString(exp.toString()));
    }

    void checkSubTrue(Core.Exp exp, List<Core.Exp> exps, String s) {
      final Core.Exp exp2 = core.subTrue(typeSystem, exp, exps);
      assertThat(exp2, hasToString(s));
    }

    Core.Exp extent(Core.Pat pat, Core.Exp filterExp) {
      final Extents.Analysis analysis =
          Extents.create(
              typeSystem,
              pat,
              ImmutableSortedMap.of(),
              ImmutableList.of(core.where(Core.StepEnv.EMPTY, filterExp)),
              ImmutablePairList.of());
      return analysis.extentExp;
    }
  }

  /**
   * Tests whether an expression is constant.
   *
   * @see Core.Exp#isConstant()
   */
  @Test
  void testConstant() {
    final Fixture f = new Fixture();
    assertThat(
        "1 is literal", core.intLiteral(BigDecimal.ONE).isConstant(), is(true));
    assertThat(
        "false is literal", core.boolLiteral(false).isConstant(), is(true));
    assertThat("a is literal", core.charLiteral('a').isConstant(), is(true));
    assertThat(
        "3.14 is literal", core.realLiteral(3.14f).isConstant(), is(true));
    assertThat(
        "string is literal", core.stringLiteral("pi").isConstant(), is(true));
    assertThat("identifier is not literal", f.aId.isConstant(), is(false));
    assertThat(
        "list of constants is constant", f.list12.isConstant(), is(true));

    assertThat(
        "unit is constant",
        core.tuple(f.typeSystem, null, ImmutableList.of()).isConstant(),
        is(true));

    final PairList<String, Core.Exp> map =
        PairList.copyOf("a", f.intLiteral(1), "b", core.boolLiteral(true));
    assertThat(
        "record of constants is constant",
        core.record(f.typeSystem, map).isConstant(),
        is(true));
    final List<Core.Exp> list = map.rightList();
    assertThat(
        "tuple of constants is constant",
        core.tuple(f.typeSystem, null, list).isConstant(),
        is(true));

    final PairList<String, Core.Exp> map2 =
        PairList.copyOf("a", f.intLiteral(1), "b", f.aId);
    assertThat(
        "record that contains an id is not constant",
        core.record(f.typeSystem, map2).isConstant(),
        is(false));
    final List<Core.Exp> list2 = map2.rightList();
    assertThat(
        "tuple that contains an id is not constant",
        core.tuple(f.typeSystem, null, list2).isConstant(),
        is(false));

    // TODO: check that zero-arg constructor (e.g. NIL) is constant
    // TODO: check that one-arg constructor (e.g. CONS (1, [])) is constant
  }

  /** Tests a variable assigned a single value. */
  @Test
  void testEq() {
    final Fixture f = new Fixture();

    // pat = "x", exp = "x = 10", extent = "[10]"
    Core.IdPat xPat = core.idPat(PrimitiveType.INT, "x", 0);
    Core.Literal ten = f.intLiteral(10);
    Core.Exp exp = core.equal(f.typeSystem, core.id(xPat), ten);
    Core.Exp x = f.extent(xPat, exp);
    assertThat(x, hasToString("[10]"));

    // pat = "x", exp = "10 = x", extent = "[10]"
    Core.Exp exp2 = core.equal(f.typeSystem, ten, core.id(xPat));
    Core.Exp x2 = f.extent(xPat, exp2);
    assertThat(x2, hasToString("[10]"));
  }

  @Test
  void testBetween() {
    // pat = "x", exp = "x >= 3 andalso y = 20 andalso x < 10 andalso 5 <> x",
    // extent of x is "extent [[3..5), (5..10)]";
    // extent of y is "extent [20, 20]";
    final Fixture f = new Fixture();
    Core.IdPat xPat = core.idPat(PrimitiveType.INT, "x", 0);
    Core.IdPat yPat = core.idPat(PrimitiveType.INT, "y", 0);
    Core.Literal three = f.intLiteral(3);
    Core.Literal five = f.intLiteral(5);
    Core.Literal ten = f.intLiteral(10);
    Core.Literal twenty = f.intLiteral(20);
    Core.Exp exp0 =
        core.greaterThanOrEqualTo(f.typeSystem, core.id(xPat), three);
    Core.Exp exp1 = core.equal(f.typeSystem, core.id(yPat), twenty);
    Core.Exp exp2 = core.lessThan(f.typeSystem, core.id(xPat), ten);
    Core.Exp exp3 = core.notEqual(f.typeSystem, core.id(xPat), five);
    final Core.Exp exp =
        core.andAlso(
            f.typeSystem,
            exp0,
            core.andAlso(
                f.typeSystem, exp1, core.andAlso(f.typeSystem, exp2, exp3)));
    Core.Exp x = f.extent(xPat, exp);
    assertThat(x, instanceOf(Core.Apply.class));
    assertThat(((Core.Apply) x).fn, instanceOf(Core.Literal.class));
    assertThat(
        ((Core.Literal) ((Core.Apply) x).fn).unwrap(BuiltIn.class),
        is(BuiltIn.Z_EXTENT));
    assertThat(x, hasToString("extent \"int {/=[[3..5), (5..10)]}\""));

    Core.Exp y = f.extent(yPat, exp);
    assertThat(y, instanceOf(Core.Apply.class));
    assertThat(((Core.Apply) y).fn, instanceOf(Core.Literal.class));
    assertThat(
        ((Core.Literal) ((Core.Apply) y).fn).unwrap(BuiltIn.class),
        is(BuiltIn.Z_LIST));
    assertThat(y, hasToString("[20]"));
  }

  @Test
  void testSubTrue() {
    final Fixture f = new Fixture();
    final Core.Exp exp1 =
        core.andAlso(
            f.typeSystem,
            f.aId,
            core.andAlso(
                f.typeSystem,
                f.bId,
                core.orElse(f.typeSystem, f.cId, f.intLiteral(1))));
    final String expected1a = "a andalso (c orelse 1)";
    final String expected1b = "a andalso (b andalso (c orelse 1))";
    final String expected1c = "b andalso (c orelse 1)";
    f.checkSubTrue(exp1, list(f.bId), expected1a);
    f.checkSubTrue(exp1, list(f.cId), expected1b);
    f.checkSubTrue(exp1, list(f.cId, f.dId, f.list12), expected1b);
    f.checkSubTrue(exp1, list(f.aId), expected1c);
    f.checkSubTrue(exp1, list(f.dId), expected1b);
    f.checkSubTrue(exp1, list(), expected1b);

    final Core.Exp exp2 =
        core.orElse(
            f.typeSystem,
            f.aId,
            core.orElse(
                f.typeSystem, f.bId, core.andAlso(f.typeSystem, f.cId, f.dId)));
    final String expected2a = "a orelse (b orelse c andalso d)";
    f.checkSubTrue(exp2, list(f.bId), expected2a); // TODO "a"
    f.checkSubTrue(
        exp2, list(f.cId), expected2a); // TODO "a orelse (b orelse d)"
    f.checkSubTrue(exp2, list(f.bId, f.cId), expected2a); // TODO "a"
    f.checkSubTrue(exp2, list(f.aId), expected2a); // TODO "true"
    f.checkSubTrue(exp2, list(), expected2a);
  }

  @Test
  void testFlatten() {
    final Fixture f = new Fixture();
    f.checkFlatten(f.aId, "[a]", "[a]");
    f.checkFlatten(core.boolLiteral(true), "[]", "[true]");
    f.checkFlatten(core.boolLiteral(false), "[false]", "[]");
    f.checkFlatten(
        core.andAlso(
            f.typeSystem,
            f.aId,
            core.andAlso(
                f.typeSystem,
                f.bId,
                core.orElse(f.typeSystem, f.cId, f.intLiteral(1)))),
        "[a, b, c orelse 1]",
        "[a andalso (b andalso (c orelse 1))]");
    f.checkFlatten(
        core.orElse(
            f.typeSystem,
            f.aId,
            core.orElse(
                f.typeSystem,
                f.bId,
                core.andAlso(f.typeSystem, f.cId, f.intLiteral(1)))),
        "[a orelse (b orelse c andalso 1)]",
        "[a, b, c andalso 1]");
  }

  @Test
  void testAnalysis2c() {
    final Fixture f = new Fixture();
    final Core.IdPat loc = core.idPat(PrimitiveType.STRING, "loc", 0);
    final Core.IdPat deptno = core.idPat(PrimitiveType.INT, "deptno", 0);
    final Core.IdPat name = core.idPat(PrimitiveType.STRING, "name", 0);
    final Core.TuplePat pat =
        core.tuplePat(f.typeSystem, list(loc, deptno, name));

    final Core.Exp condition0 =
        core.elem(
            f.typeSystem,
            core.record(
                f.typeSystem,
                PairList.copyOf(
                    "deptno",
                    core.id(deptno),
                    "dname",
                    core.id(name),
                    "loc",
                    core.id(loc))),
            core.id(f.depts));
    final Core.Exp condition1 =
        core.greaterThan(
            f.typeSystem,
            core.id(deptno),
            core.intLiteral(BigDecimal.valueOf(20)));

    final BiConsumer<String, Consumer<FromBuilder>> fn =
        (v, action) -> {
          final FromBuilder fromBuilder = core.fromBuilder(f.typeSystem);
          fromBuilder.scan(pat);
          // Apply one of the variants of 'where' clause
          action.accept(fromBuilder);

          final PairList<Core.IdPat, Core.Exp> idPats = PairList.of();
          Extents.Analysis analysis =
              Extents.create(
                  f.typeSystem,
                  pat,
                  ImmutableSortedMap.of(),
                  fromBuilder.build().steps,
                  idPats);
          assertThat(analysis, notNullValue());
          if ("".isEmpty()) {
            assertThat(
                analysis.extentExp,
                hasToString(
                    ("from loc in [#loc v$0] "
                            + "join deptno in [#deptno v$0] "
                            + "join name in [#dname v$0]")
                        .replace("v$0", v)));
            assertThat(analysis.satisfiedFilters, hasSize(3));
            assertThat(
                analysis.satisfiedFilters.get(0),
                hasToString("loc = #loc v$0".replace("v$0", v)));
          } else {
            assertThat(analysis.extentExp, hasToString("depts"));
            assertThat(analysis.satisfiedFilters, hasSize(1));
            assertThat(analysis.satisfiedFilters.get(0), is(condition0));
          }
          assertThat(analysis.remainingFilters, empty());
          assertThat(analysis.boundPats, anEmptyMap());
          assertThat(analysis.goalPats, is(ImmutableSet.of(loc, deptno, name)));
          assertThat(idPats, hasSize(1));
          assertThat(idPats.leftList().get(0), hasToString(v));
        };

    // from (loc, deptno, name)
    // where op elem ({deptno = deptno, dname = name, loc = loc}, depts)
    fn.accept("v$0", fromBuilder -> fromBuilder.where(condition0));

    // from (loc, deptno, name)
    // where op elem ({deptno = deptno, dname = name, loc = loc}, depts)
    // where deptno > 20
    fn.accept(
        "v$1", fromBuilder -> fromBuilder.where(condition0).where(condition1));

    // from (loc, deptno, name)
    // where op elem ({deptno = deptno, dname = name, loc = loc}, depts)
    //    andalso deptno > 20
    fn.accept(
        "v$2",
        fromBuilder ->
            fromBuilder.where(
                core.andAlso(f.typeSystem, condition0, condition1)));
  }

  @Test
  void testAnalysis2d() {
    final Fixture f = new Fixture();
    final Core.IdPat dno = core.idPat(PrimitiveType.INT, "dno", 0);
    final Core.IdPat name = core.idPat(PrimitiveType.STRING, "name", 0);
    final Core.TuplePat pat = core.tuplePat(f.typeSystem, list(dno, name));

    // from (dno, name)
    // where op elem ({deptno = dno, dname = name, loc = "CHICAGO"}, depts)
    //   andalso dno > 20
    final FromBuilder fromBuilder = core.fromBuilder(f.typeSystem);
    fromBuilder.scan(pat);
    final Core.Exp condition0 =
        core.elem(
            f.typeSystem,
            core.record(
                f.typeSystem,
                PairList.copyOf(
                    "deptno",
                    core.id(dno),
                    "dname",
                    core.id(name),
                    "loc",
                    core.stringLiteral("CHICAGO"))),
            core.id(f.depts));
    final Core.Exp condition1 =
        core.greaterThan(
            f.typeSystem,
            core.id(dno),
            core.intLiteral(BigDecimal.valueOf(20)));
    fromBuilder.where(core.andAlso(f.typeSystem, condition0, condition1));

    final PairList<Core.IdPat, Core.Exp> idPats = PairList.of();
    Extents.Analysis analysis =
        Extents.create(
            f.typeSystem,
            pat,
            ImmutableSortedMap.of(),
            fromBuilder.build().steps,
            idPats);
    assertThat(analysis, notNullValue());
    assertThat(
        analysis.extentExp,
        hasToString("from dno in [#deptno v$0] join name in [#dname v$0]"));
    assertThat(idPats, hasSize(1));
    assertThat(idPats.leftList().get(0), hasToString("v$0"));
  }
}

// End ExtentTest.java
