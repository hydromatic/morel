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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.JoinType;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.RelBuilder;
import net.hydromatic.morel.ast.Simplification;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.RelValidator;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link RelBuilder}, which does three things: keeps a stack of
 * relational expressions, maps names onto paths into an input, and simplifies
 * under a switchable {@link Simplification} set.
 *
 * <p>Every simplification is tested twice -- once with the set empty, which is
 * what the builder was told to build, and once with it enabled. That pairing is
 * the point of the {@code EnumSet}: it makes "what did simplification change?"
 * a question the tests can ask directly.
 */
public class RelBuilderTest {
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();

    {
      BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    }

    /** A collection of records, as {@code scott.emps} is. */
    final Core.Exp emps =
        core.list(
            typeSystem,
            record("deptno", core.literal(PrimitiveType.INT, 10)),
            record("deptno", core.literal(PrimitiveType.INT, 20)));

    final Core.Exp list12 =
        core.list(
            typeSystem,
            core.literal(PrimitiveType.INT, 1),
            core.literal(PrimitiveType.INT, 2));

    Core.Exp record(String name, Core.Exp exp) {
      final PairList<String, Core.Exp> nameExps = PairList.of();
      nameExps.add(name, exp);
      return core.record(typeSystem, nameExps);
    }

    Core.Literal intLiteral(int i) {
      return core.literal(PrimitiveType.INT, i);
    }

    /**
     * Creates a builder with the given simplifications enabled; with none
     * named, a builder that constructs exactly what it is told.
     */
    RelBuilder builder(Simplification... simps) {
      return RelBuilder.create(
          typeSystem,
          simps.length == 0
              ? Simplification.none()
              : ImmutableSet.copyOf(EnumSet.copyOf(Arrays.asList(simps))));
    }

    /** Builds, checks the validator is happy, and returns the plan text. */
    String plan(Core.Exp rel) {
      if (rel instanceof Core.Rel) {
        assertThat(
            RelValidator.violations(typeSystem, (Core.Rel) rel), empty());
        return ((Core.Rel) rel).describe(typeSystem);
      }
      return rel + "\n";
    }
  }

  /** Tests that a leaf and a filter make the tree they say. */
  @Test
  void testFilter() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    final Core.Exp rel =
        b.push(f.list12)
            .filter(core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(1)))
            .build();
    assertThat(
        f.plan(rel),
        is(
            "filter r [r > 1]\n" //
                + "  [1, 2]\n"));
  }

  /**
   * Tests the name map: a leaf can name its element, and a field of that name
   * becomes a selector over {@code $0}, so that the caller never writes {@code
   * $0} or a slot number.
   */
  @Test
  void testNameAndField() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    final Core.Exp rel =
        b.push("e", f.emps)
            .filter(
                core.equal(
                    f.typeSystem, b.field(0, "e", "deptno"), f.intLiteral(10)))
            .build();
    assertThat(
        f.plan(rel),
        is(
            "filter e [#deptno e = 10]\n" //
                + "  [{deptno = 10}, {deptno = 20}]\n"
                + ""));
  }

  /**
   * Tests that a record element's field names are available without the leaf
   * being named at all, because the names of a node are the field names of its
   * element.
   */
  @Test
  void testFieldWithoutName() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    final Core.Exp rel = b.push(f.emps).project(b.name("deptno")).build();
    assertThat(
        f.plan(rel),
        is(
            "project r [#deptno r]\n" //
                + "  [{deptno = 10}, {deptno = 20}]\n"));
  }

  /**
   * Tests the stack with two inputs: the deeper is the left and is {@code $0},
   * the top is the right and is {@code $1}.
   */
  @Test
  void testJoin() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    final PairList<String, Core.Exp> nameExps = PairList.of();
    b.push("i", f.list12).push("j", f.list12).pair();
    nameExps.add("i", b.name(0, "i"));
    nameExps.add("j", b.name(1, "j"));
    // Before the join, each name reads its own input's id.
    assertThat(nameExps, hasToString("[<i, i>, <j, j>]"));
    final Core.Exp rel = b.join(JoinType.INNER, core.boolLiteral(true)).build();
    assertThat(
        f.plan(rel),
        is(
            "join i j\n" //
                + "  [1, 2]\n"
                + "  [1, 2]\n"));
  }

  /**
   * Tests a dependent join: the right input reads the left id, which the
   * printer names, because the right input is a tree of its own whose {@code
   * $0} is its own id.
   */
  @Test
  void testDependentJoin() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push("e", f.emps);
    final Core.IdPat leftId = b.binder();
    // The right input reads the left element through the left id.
    b.push(core.list(f.typeSystem, b.field(core.id(leftId), "deptno")));
    b.pair();
    final Core.Exp rel = b.join(JoinType.INNER, core.boolLiteral(true)).build();
    assertThat(((Core.Join) rel).isDependent(), is(true));
    assertThat(
        f.plan(rel),
        is(
            "join e r\n" //
                + "  [{deptno = 10}, {deptno = 20}]\n"
                + "  [#deptno e]\n"
                + ""));
  }

  /**
   * Tests that a join whose right input does not read the left id is
   * independent, and prints without an argument for it, whatever the caller
   * asked the builder for.
   */
  @Test
  void testJoinIndependent() {
    final Fixture f = new Fixture();
    assertThat(
        f.plan(offeredBinder(f)),
        is(
            "join i r [i = r]\n" //
                + "  [1, 2]\n"
                + "  [1, 2]\n"
                + ""));
    assertThat(
        f.plan(offeredBinder(f, Simplification.values())),
        is(
            "join i r [i = r]\n" //
                + "  [1, 2]\n"
                + "  [1, 2]\n"
                + ""));
  }

  private static Core.Exp offeredBinder(Fixture f, Simplification... simps) {
    final RelBuilder b = f.builder(simps);
    b.push("i", f.list12);
    // The left id is offered, and the right input reads nothing of it.
    final Core.IdPat unused = b.binder();
    assertThat(unused, notNullValue());
    b.push(f.list12).pair();
    final Core.Exp rel =
        b.join(JoinType.INNER, core.equal(f.typeSystem, b.input(0), b.input(1)))
            .build();
    assertThat(((Core.Join) rel).isDependent(), is(false));
    return rel;
  }

  /**
   * Tests that the left id may be read from the condition, where it is {@code
   * $0}, as well as from the right input.
   */
  @Test
  void testLeftRowInCondition() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push("e", f.emps);
    final Core.IdPat leftId = b.binder();
    b.push(core.list(f.typeSystem, b.field(core.id(leftId), "deptno")));
    b.pair();
    final Core.Exp rel =
        b.join(
                JoinType.INNER,
                core.equal(
                    f.typeSystem,
                    core.field(f.typeSystem, core.id(leftId), 0),
                    f.intLiteral(10)))
            .build();
    assertThat(
        f.plan(rel),
        is(
            "join e r [#deptno e = 10]\n" //
                + "  [{deptno = 10}, {deptno = 20}]\n"
                + "  [#deptno e]\n"
                + ""));
  }

  /**
   * Tests that names survive two joins, which is what a three-way query needs.
   *
   * <p>{@code from e in emps, d in depts, g in salgrades} builds {@code
   * join(join(emps, depts), salgrades)}, whose element is three components
   * because an inner join flattens. Each name is one of them, and the builder
   * says which -- where reading the names off the element's fields would say
   * {@code 1}, {@code 2} and {@code 3}, which is true and useless to a caller
   * that started from a query.
   */
  @Test
  void testNamesAcrossTwoJoins() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push("e", f.emps).push("d", f.emps).pair();
    b.join(JoinType.INNER, core.boolLiteral(true));
    b.push("g", f.emps).pair();
    b.join(JoinType.INNER, core.boolLiteral(true));

    assertThat(
        b.peek().type.moniker(),
        is("({deptno:int} * {deptno:int} * {deptno:int}) list"));
    assertThat(b.name("e"), hasToString("#1 r"));
    assertThat(b.name("d"), hasToString("#2 r"));
    assertThat(b.name("g"), hasToString("#3 r"));

    // And a field of a name is the field of that component, which is the
    // two-step path a three-way join's condition is made of.
    assertThat(b.field(b.name("e"), "deptno"), hasToString("#deptno (#1 r)"));
  }

  /**
   * Tests that an outer join concatenates like any other, so a name from inside
   * it keeps a position of its own.
   */
  @Test
  void testNamesAcrossAnOuterJoin() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push("e", f.emps).push("d", f.emps).pair();
    b.join(JoinType.LEFT, core.boolLiteral(true));
    b.push("g", f.emps).pair();
    b.join(JoinType.INNER, core.boolLiteral(true));

    // The left join flattened, so its two components keep their places and
    // 'g' is the third. Each component of the absent side is option-wrapped
    // on its own, which is what Morel binds: 'e' and 'd' are options here,
    // not one option over the pair.
    assertThat(b.name("g"), hasToString("#3 r"));
    // The left join leaves its right side absent, so 'd' is an option and
    // 'e' is not -- one option per component, and not one over the pair.
    assertThat(b.name("e").type, hasToString("{deptno:int}"));
    assertThat(b.name("d").type, hasToString("{deptno:int} option"));
  }

  /** Tests that a set operator takes as many inputs as it is given. */
  @Test
  void testUnionOfThree() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    final Core.Exp rel =
        b.push(f.list12).push(f.list12).push(f.list12).union(3, false).build();
    assertThat(
        f.plan(rel),
        is(
            "union [all]\n" //
                + "  [1, 2]\n"
                + "  [1, 2]\n"
                + "  [1, 2]\n"));
  }

  /** Tests {@link Simplification#FILTER_TRUE}, off and on. */
  @Test
  void testFilterTrue() {
    final Fixture f = new Fixture();
    assertThat(
        f.plan(
            f.builder().push(f.list12).filter(core.boolLiteral(true)).build()),
        is(
            "filter r [true]\n" //
                + "  [1, 2]\n"));
    assertThat(
        f.plan(
            f.builder(Simplification.values())
                .push(f.list12)
                .filter(core.boolLiteral(true))
                .build()),
        is("[1, 2]\n"));
  }

  /** Tests {@link Simplification#FILTER_MERGE}, off and on. */
  @Test
  void testFilterMerge() {
    final Fixture f = new Fixture();
    assertThat(
        f.plan(twoFilters(f)),
        is(
            "filter r [r < 2]\n" //
                + "  filter r [r > 0]\n"
                + "    [1, 2]\n"));
    assertThat(
        f.plan(twoFilters(f, Simplification.values())),
        is(
            "filter r [r > 0 andalso r < 2]\n" //
                + "  [1, 2]\n"));
  }

  private static Core.Exp twoFilters(Fixture f, Simplification... simps) {
    final RelBuilder b = f.builder(simps);
    return b.push(f.list12)
        .filter(core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(0)))
        .filter(core.lessThan(f.typeSystem, b.input(0), f.intLiteral(2)))
        .build();
  }

  /** Tests {@link Simplification#PROJECT_IDENTITY}, off and on. */
  @Test
  void testProjectIdentity() {
    final Fixture f = new Fixture();
    final RelBuilder b0 = f.builder();
    assertThat(
        f.plan(b0.push(f.list12).project(b0.input(0)).build()),
        is(
            "project r [r]\n" //
                + "  [1, 2]\n"));
    final RelBuilder b1 = f.builder(Simplification.values());
    assertThat(
        f.plan(b1.push(f.list12).project(b1.input(0)).build()), is("[1, 2]\n"));
  }

  /**
   * Tests {@link Simplification#PROJECT_MERGE}, off and on. Merging is
   * substitution: the outer projection's {@code $0} is the inner projection's
   * expression, so {@code $0 > 1} over {@code #deptno $0} becomes {@code
   * #deptno $0 > 1}.
   */
  @Test
  void testProjectMerge() {
    final Fixture f = new Fixture();
    assertThat(
        f.plan(twoProjects(f)),
        is(
            "project r [r > 1]\n" //
                + "  project r [#deptno r]\n"
                + "    [{deptno = 10}, {deptno = 20}]\n"));
    assertThat(
        f.plan(twoProjects(f, Simplification.values())),
        is(
            "project r [#deptno r > 1]\n" //
                + "  [{deptno = 10}, {deptno = 20}]\n"));
  }

  private static Core.Exp twoProjects(Fixture f, Simplification... simps) {
    final RelBuilder b = f.builder(simps);
    b.push(f.emps).project(b.field("deptno"));
    return b.project(
            core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(1)))
        .build();
  }

  /** Tests {@link Simplification#SKIP_ZERO}, off and on. */
  @Test
  void testSkipZero() {
    final Fixture f = new Fixture();
    assertThat(
        f.plan(f.builder().push(f.list12).skip(f.intLiteral(0)).build()),
        is(
            "skip [0]\n" //
                + "  [1, 2]\n"));
    assertThat(
        f.plan(
            f.builder(Simplification.values())
                .push(f.list12)
                .skip(f.intLiteral(0))
                .build()),
        is("[1, 2]\n"));
    // A non-zero skip survives either way.
    assertThat(
        f.plan(
            f.builder(Simplification.values())
                .push(f.list12)
                .skip(f.intLiteral(1))
                .build()),
        is(
            "skip [1]\n" //
                + "  [1, 2]\n"));
  }

  /**
   * Tests that the simplifications are independent: enabling one does not
   * enable another.
   */
  @Test
  void testOneSimplificationAtATime() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(Simplification.FILTER_TRUE);
    final Core.Exp rel =
        b.push(f.list12)
            .filter(core.boolLiteral(true))
            .skip(f.intLiteral(0))
            .build();
    // The filter went; the skip stayed, because SKIP_ZERO is off.
    assertThat(
        f.plan(rel),
        is(
            "skip [0]\n" //
                + "  [1, 2]\n"));
  }

  /**
   * Tests the oracle that the {@code EnumSet} buys: the same query built twice,
   * once as told and once simplified, and the two compared.
   */
  @Test
  void testSimplifiedDiffersFromLiteral() {
    final Fixture f = new Fixture();
    final String literal = f.plan(twoFilters(f));
    final String simplified = f.plan(twoFilters(f, Simplification.values()));
    assertThat(literal, not(is(simplified)));
    // Both are valid trees of the same type: simplification does not change
    // what the query returns, only how many nodes say it.
    assertThat(
        twoFilters(f).type, is(twoFilters(f, Simplification.values()).type));
  }

  /**
   * Tests that a scan's pattern is erased into one name per binder, each mapped
   * to the path that reads it out of the element.
   */
  @Test
  void testPushPattern() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    final Core.Pat pat =
        core.tuplePat(
            f.typeSystem,
            ImmutableList.of(
                core.idPat(PrimitiveType.INT, "a", 0),
                core.idPat(PrimitiveType.INT, "b", 0)));
    final Core.Exp pairs =
        core.list(
            f.typeSystem,
            core.tuple(f.typeSystem, f.intLiteral(1), f.intLiteral(2)));
    final Core.Exp rel =
        b.push(pat, pairs)
            .filter(core.greaterThan(f.typeSystem, b.name("b"), b.name("a")))
            .build();
    assertThat(
        f.plan(rel),
        is(
            "filter r [#2 r > #1 r]\n" //
                + "  [(1, 2)]\n"));
  }

  /**
   * Tests that a pattern which can fail to match is refused, because such a
   * pattern also filters and so cannot simply be erased.
   */
  @Test
  void testFailablePatternRefused() {
    final Core.Pat idPat = core.idPat(PrimitiveType.INT, "a", 0);
    final Core.Pat literalPat =
        core.literalPat(Op.INT_LITERAL_PAT, PrimitiveType.INT, BigDecimal.ONE);
    assertThat(RelBuilder.destructurable(idPat), is(true));
    assertThat(RelBuilder.destructurable(literalPat), is(false));
    assertThat(RelBuilder.testable(literalPat), is(true));
  }

  /**
   * A literal pattern binds nothing and filters: {@code push} puts a filter
   * above the collection.
   */
  @Test
  void testLiteralPatternFilters() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    final Core.Pat literalPat =
        core.literalPat(Op.INT_LITERAL_PAT, PrimitiveType.INT, BigDecimal.ONE);
    b.push(literalPat, f.list12);
    assertThat(
        b.build(),
        hasToString(
            "filter r [r = 1]\n" //
                + "  [1, 2]\n"));
  }

  /**
   * Rebuilding a tree through a builder that simplifies nothing gives the tree
   * back; through one that simplifies, it gives what the simplifications make
   * of it.
   */
  @Test
  void testRebuild() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push("i", f.list12);
    b.filter(core.greaterThan(f.typeSystem, b.name("i"), f.intLiteral(0)));
    b.filter(core.lessThan(f.typeSystem, b.name("i"), f.intLiteral(3)));
    final Core.Exp tree = b.build();
    final String text = tree.toString();
    assertThat(
        RelBuilder.rebuild(f.typeSystem, tree, Simplification.none()),
        hasToString(text));
    assertThat(
        RelBuilder.rebuild(
            f.typeSystem, tree, EnumSet.of(Simplification.FILTER_MERGE)),
        hasToString(
            "filter i [i > 0 andalso i < 3]\n" //
                + "  [1, 2]\n"));
  }

  /**
   * Tests rebuilding a projection whose element is of a different type than its
   * input's, under a node that is not a {@code RowRel}. The rebuild once handed
   * the projection's id, an {@code int}, to the {@code skip} above, whose
   * element is a {@code bool}.
   */
  @Test
  void testRebuildProjectUnderSkip() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push("i", f.list12);
    b.project(core.greaterThan(f.typeSystem, b.name("i"), f.intLiteral(0)));
    b.skip(f.intLiteral(1));
    b.filter(b.input(0));
    final Core.Exp tree = b.build();
    final String text = tree.toString();
    assertThat(
        text,
        is(
            "filter r [r]\n" //
                + "  skip [1]\n"
                + "    project i [i > 0]\n"
                + "      [1, 2]\n"));
    assertThat(
        RelBuilder.rebuild(f.typeSystem, tree, Simplification.none()),
        hasToString(text));
  }

  /** {@code checkValid} throws with the violations, and is silent without. */
  @Test
  void testCheckValid() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push("i", f.list12);
    b.filter(core.greaterThan(f.typeSystem, b.name("i"), f.intLiteral(0)));
    final Core.Rel tree = (Core.Rel) b.build();
    RelValidator.checkValid(f.typeSystem, tree);

    // A filter whose condition reads a pattern that nothing binds.
    final Core.IdPat stray = core.idPat(PrimitiveType.INT, "$1", 99);
    final Core.Rel bad =
        core.filter(
            core.idPat(PrimitiveType.INT, "$0", 98),
            null,
            f.list12,
            core.greaterThan(f.typeSystem, core.id(stray), f.intLiteral(0)));
    final IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> RelValidator.checkValid(f.typeSystem, bad));
    assertThat(e.getMessage(), containsString("invalid relational tree"));
    assertThat(e.getMessage(), containsString("$1"));
  }

  /**
   * Tests that a group's labels are names, whether there is one of them or
   * several, because a group's element is a record either way.
   *
   * <p>This is what the resolver will ask of the name map: after {@code group j
   * = i}, the query says {@code j}, and the map has to answer whether or not
   * the element happens to be a record.
   */
  @Test
  void testNamesAfterGroup() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push(f.list12);
    b.group(ImmutableSortedMap.of("j", b.input(0)), ImmutableSortedMap.of());
    // A record, though there is one key, so 'j' is a field of it.
    assertThat(b.peek().type.moniker(), is("{j:int} list"));
    assertThat(b.name("j"), hasToString("#j r"));

    final RelBuilder b2 = f.builder();
    b2.push(f.list12);
    b2.group(
        ImmutableSortedMap.of("j", b2.input(0), "k", b2.input(0)),
        ImmutableSortedMap.of());
    // Two keys, so the element is a record and each label is a field.
    assertThat(b2.name("j"), hasToString("#j r"));
    assertThat(b2.name("k"), hasToString("#k r"));
  }

  /**
   * Tests that a projection to a bare value keeps the name it was pushed under,
   * as an atomizing yield does.
   */
  @Test
  void testNamesAfterAtomizingProject() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push("e", f.emps);
    b.project("d", b.field("deptno"));
    assertThat(b.peek().type.moniker(), is("int list"));
    assertThat(b.name("d"), hasToString("r"));
  }

  /** Tests {@code group}, whose element is a record of keys and aggregates. */
  @Test
  void testGroup() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push(f.list12);
    final Core.Exp rel =
        b.group(ImmutableSortedMap.of("k", b.input(0)), ImmutableSortedMap.of())
            .build();
    assertThat(
        f.plan(rel),
        is(
            "group r [k = r]\n" //
                + "  [1, 2]\n"));
  }
}

// End RelBuilderTest.java
