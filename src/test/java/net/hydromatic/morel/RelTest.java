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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.hydromatic.morel.ast.AstWriter;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.JoinType;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.RelValidator;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.junit.jupiter.api.Test;

/**
 * Tests the relational tree, {@link Core.Rel}: the element type and kind that
 * {@link net.hydromatic.morel.ast.CoreBuilder} derives for each node, the
 * patterns a node binds, and the plan text that a tree prints.
 *
 * <p>The type derivations and the plan text are the contract that morel-rust
 * and morel-go implement, so a change here is a change to that contract.
 */
public class RelTest {
  /** Fixture with a type system and a few expressions to build trees from. */
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();

    {
      // Register 'bag' and the other built-in data types.
      BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    }

    final PrimitiveType intType = PrimitiveType.INT;

    final Core.Exp list12 = core.list(typeSystem, intLiteral(1), intLiteral(2));
    final Core.Exp list34 = core.list(typeSystem, intLiteral(3), intLiteral(4));
    final Core.Exp bag56 = core.bag(typeSystem, intLiteral(5), intLiteral(6));

    /** Ordinal of the next pattern; each node's patterns are distinct. */
    int nextOrdinal = 0;

    Core.Literal intLiteral(int i) {
      return core.literal(intType, i);
    }

    /** Mints the id, {@code $0}, for a node over an input. */
    Core.IdPat elementPat(Core.Exp input) {
      return core.idPat(input.type.elementType(), "$0", nextOrdinal++);
    }

    /** Mints the right id, {@code $1}, for a join over an input. */
    Core.IdPat rightElementPat(Core.Exp input) {
      return core.idPat(input.type.elementType(), "$1", nextOrdinal++);
    }

    /** Mints an ordinal pattern, {@code $ordinal}. */
    Core.IdPat ordinal() {
      return core.idPat(intType, "$ordinal", nextOrdinal++);
    }

    /** Builds a filter whose condition is given the id. */
    Core.Filter filter(Core.Exp input, Function<Core.Id, Core.Exp> condition) {
      final Core.IdPat id = elementPat(input);
      return core.filter(id, null, input, condition.apply(core.id(id)));
    }

    /** Builds a projection whose expression is given the id. */
    Core.Project project(Core.Exp input, Function<Core.Id, Core.Exp> exp) {
      final Core.IdPat id = elementPat(input);
      return core.project(typeSystem, id, null, input, exp.apply(core.id(id)));
    }

    /** Builds a sort whose key is given the id. */
    Core.Sort sort(Core.Exp input, Function<Core.Id, Core.Exp> exp) {
      final Core.IdPat id = elementPat(input);
      return core.sort(typeSystem, id, null, input, exp.apply(core.id(id)));
    }

    /** Builds a join whose condition is given the left and right ids. */
    Core.Join join(
        JoinType joinType,
        Core.Exp left,
        Core.Exp right,
        BiFunction<Core.Id, Core.Id, Core.Exp> condition) {
      final Core.IdPat leftId = elementPat(left);
      final Core.IdPat rightId = rightElementPat(right);
      return core.join(
          typeSystem,
          joinType,
          leftId,
          rightId,
          null,
          left,
          right,
          condition.apply(core.id(leftId), core.id(rightId)));
    }

    /**
     * Creates a record of the given expressions, labelled {@code a}, {@code b},
     * and so on.
     */
    Core.Exp record(Core.Exp... exps) {
      final PairList<String, Core.Exp> nameExps = PairList.of();
      for (int i = 0; i < exps.length; i++) {
        nameExps.add(String.valueOf((char) ('a' + i)), exps[i]);
      }
      return core.record(typeSystem, nameExps);
    }

    Core.Exp greaterThan(Core.Exp a0, Core.Exp a1) {
      return core.greaterThan(typeSystem, a0, a1);
    }

    /** Returns the violations that the validator finds in a tree. */
    List<String> violations(Core.Rel rel) {
      return RelValidator.violations(typeSystem, rel);
    }
  }

  /**
   * Tests a filter and a projection over a leaf, and that the element type of a
   * projection is simply the type of its expression.
   *
   * <p>A leaf is any collection-valued expression -- here the list {@code [1,
   * 2]} -- and prints as itself, with no operator of its own.
   */
  @Test
  void testFilterProject() {
    final Fixture f = new Fixture();
    final Core.Rel filter =
        f.filter(f.list12, r -> f.greaterThan(r, f.intLiteral(1)));
    final Core.Rel project =
        f.project(filter, r -> f.record(r, f.intLiteral(0)));

    assertThat(filter.type.moniker(), is("int list"));
    assertThat(project.type.moniker(), is("{a:int, b:int} list"));
    assertThat(
        project.describe(f.typeSystem),
        is(
            "project r [{a = r, b = 0}]\n" //
                + "  filter r [r > 1]\n"
                + "    [1, 2]\n"));
  }

  /**
   * Tests that {@code planEx}-style output prints the collection type of every
   * node, and of a leaf.
   */
  @Test
  void testDescribeWithTypes() {
    final Fixture f = new Fixture();
    final Core.Rel filter =
        f.filter(f.list12, r -> f.greaterThan(r, f.intLiteral(1)));
    assertThat(
        filter.describe(f.typeSystem, AstWriter.DEFAULT_WIDTH, true),
        is(
            "filter r [r > 1] : int list\n" //
                + "  [1, 2] : int list\n"));
  }

  /**
   * Tests that a join's element is its inputs' components, and that it is
   * ordered only if both inputs are ordered.
   */
  @Test
  void testJoin() {
    final Fixture f = new Fixture();
    final Core.Rel join =
        f.join(
            JoinType.INNER,
            f.list12,
            f.list34,
            (l, r) -> core.equal(f.typeSystem, l, r));
    // The element is the inputs' components, a tuple, not a record
    // of names -- there are no names to give it.
    assertThat(join.type.moniker(), is("(int * int) list"));
    assertThat(
        join.describe(f.typeSystem),
        is(
            "join r r_1 [r = r_1]\n" //
                + "  [1, 2]\n"
                + "  [3, 4]\n"));

    // A join with a bag input is a bag; a nested loop over a bag has no
    // order to preserve.
    final Core.Rel join2 =
        f.join(
            JoinType.INNER,
            f.list12,
            f.bag56,
            (l, r) -> core.boolLiteral(true));
    assertThat(join2.type.moniker(), is("(int * int) bag"));

    // A condition that is 'true' and an inner join kind print nothing.
    assertThat(
        join2.describe(f.typeSystem),
        is(
            "join r r_1\n" //
                + "  [1, 2]\n"
                + "  #fromList Bag ([5, 6])\n"));

    // An outer join prints its kind.
    final Core.Rel join3 =
        f.join(
            JoinType.LEFT,
            f.list12,
            f.list34,
            (l, r) -> core.boolLiteral(true));
    assertThat(
        join3.describe(f.typeSystem),
        is(
            "join r r_1 [left]\n" //
                + "  [1, 2]\n"
                + "  [3, 4]\n"));
  }

  /**
   * Tests a dependent join: its right input reads its left id, which the
   * printer names and shows as the join's argument; its element is its inputs'
   * components; and it is ordered only if both inputs are.
   */
  @Test
  void testDependentJoin() {
    final Fixture f = new Fixture();
    final Core.IdPat leftId = f.elementPat(f.list12);

    // The right input mentions the left id, so the join is dependent.
    final Core.Exp right =
        core.list(f.typeSystem, core.id(leftId), f.intLiteral(4));
    final Core.Join join =
        core.join(
            f.typeSystem,
            JoinType.INNER,
            leftId,
            f.rightElementPat(right),
            null,
            f.list12,
            right,
            core.boolLiteral(true));
    assertThat(join.isDependent(), is(true));

    // The element is the inputs' components, a tuple, not a record
    // of names -- there are no names to give it.
    assertThat(join.type.moniker(), is("(int * int) list"));
    assertThat(
        join.describe(f.typeSystem),
        is(
            "join r r_1\n" //
                + "  [1, 2]\n"
                + "  [r, 4]\n"));

    // A bag on either side makes the output a bag, as a dependent scan over a
    // bag does today.
    final Core.IdPat leftId2 = f.elementPat(f.list12);
    final Core.Join join2 =
        core.join(
            f.typeSystem,
            JoinType.INNER,
            leftId2,
            f.rightElementPat(f.bag56),
            null,
            f.list12,
            f.bag56,
            core.boolLiteral(true));
    assertThat(join2.isDependent(), is(false));
    assertThat(join2.type.moniker(), is("(int * int) bag"));
    assertThat(
        join2.describe(f.typeSystem),
        is(
            "join r r_1\n" //
                + "  [1, 2]\n"
                + "  #fromList Bag ([5, 6])\n"));
  }

  /**
   * Tests that {@code group} derives a record element type, whether it has one
   * label or many.
   */
  @Test
  void testGroup() {
    final Fixture f = new Fixture();

    final Core.IdPat id1 = f.elementPat(f.list12);
    final Core.Rel group1 =
        core.group(
            f.typeSystem,
            id1,
            null,
            f.list12,
            ImmutableSortedMap.of("j", (Core.Exp) core.id(id1)),
            ImmutableSortedMap.of());
    // A record, though there is only one key: collapsing it would make the
    // element's shape depend on how many labels there are.
    assertThat(group1.type.moniker(), is("{j:int} list"));
    assertThat(
        group1.describe(f.typeSystem),
        is(
            "group r [j = r]\n" //
                + "  [1, 2]\n"));

    final Core.IdPat id2 = f.elementPat(f.list12);
    final Core.Rel group2 =
        core.group(
            f.typeSystem,
            id2,
            null,
            f.list12,
            ImmutableSortedMap.<String, Core.Exp>orderedBy(RecordType.ORDERING)
                .put("i", core.id(id2))
                .put("j", core.id(id2))
                .build(),
            ImmutableSortedMap.of());
    assertThat(group2.type.moniker(), is("{i:int, j:int} list"));
  }

  @Test
  void testKinds() {
    final Fixture f = new Fixture();

    // sort : coll -> list; unorder : coll -> bag
    assertThat(f.sort(f.bag56, r -> r).type.moniker(), is("int list"));
    assertThat(
        core.unorder(f.typeSystem, f.list12).type.moniker(), is("int bag"));

    // skip and take preserve the kind, and print their counts
    final Core.Rel skip = core.skip(f.list12, f.intLiteral(1));
    assertThat(skip.type.moniker(), is("int list"));
    assertThat(
        core.take(skip, f.intLiteral(2)).describe(f.typeSystem),
        is(
            "take [2]\n" //
                + "  skip [1]\n"
                + "    [1, 2]\n"));

    // a set operator is a list only if every input is a list
    assertThat(
        core.union(f.typeSystem, true, Arrays.asList(f.list12, f.list34))
            .type
            .moniker(),
        is("int list"));
    final Core.Rel union =
        core.union(f.typeSystem, false, Arrays.asList(f.list12, f.bag56));
    assertThat(union.type.moniker(), is("int bag"));
    assertThat(
        union.describe(f.typeSystem),
        is(
            "union [all]\n" //
                + "  [1, 2]\n"
                + "  #fromList Bag ([5, 6])\n"));
  }

  /**
   * Tests the ordinal pattern: a node that reads its input's positions binds
   * {@code $ordinal}, which prints by name inside the node; and the input must
   * be a list, because a bag has no positions.
   */
  @Test
  void testOrdinal() {
    final Fixture f = new Fixture();
    final Core.IdPat id = f.elementPat(f.list12);
    final Core.IdPat ordinal = f.ordinal();
    final Core.Rel filter =
        core.filter(
            id,
            ordinal,
            f.list12,
            f.greaterThan(core.id(ordinal), f.intLiteral(0)));
    assertThat(
        filter.describe(f.typeSystem),
        is(
            "filter r $ordinal [$ordinal > 0]\n" //
                + "  [1, 2]\n"));
    assertThat(f.violations(filter), empty());

    final Core.IdPat id2 = f.elementPat(f.bag56);
    final Core.IdPat ordinal2 = f.ordinal();
    final Core.Rel overBag =
        core.filter(
            id2,
            ordinal2,
            f.bag56,
            f.greaterThan(core.id(ordinal2), f.intLiteral(0)));
    assertThat(
        f.violations(overBag),
        is(
            Arrays.asList(
                "filter binds an ordinal but its input is a bag: int bag")));
  }

  /**
   * Tests that the validator accepts trees the builder produces, and finds the
   * ways in which a hand-built tree can go wrong.
   */
  @Test
  void testValidator() {
    final Fixture f = new Fixture();

    // A tree built by the builder is valid.
    final Core.Rel filter =
        f.filter(f.list12, r -> f.greaterThan(r, f.intLiteral(1)));
    final Core.Rel project =
        f.project(filter, r -> f.record(r, f.intLiteral(0)));
    assertThat(f.violations(project), empty());

    final Core.Rel join =
        f.join(
            JoinType.INNER,
            f.list12,
            f.list34,
            (l, r) -> core.equal(f.typeSystem, l, r));
    assertThat(f.violations(join), empty());

    // A filter binds one id; a reference to another node's pattern is an
    // unbound name.
    final Core.IdPat stray = f.rightElementPat(f.list34);
    final Core.Rel badFilter =
        f.filter(f.list12, r -> core.equal(f.typeSystem, r, core.id(stray)));
    assertThat(
        f.violations(badFilter),
        is(Arrays.asList("filter condition cannot reference $1")));

    // 'skip' and 'take' counts are evaluated before the first element exists,
    // and the nodes bind nothing.
    final Core.IdPat id = f.elementPat(f.list12);
    assertThat(
        f.violations(core.take(f.list12, core.id(id))),
        is(Arrays.asList("take count cannot reference $0")));
    assertThat(
        f.violations(core.skip(f.list12, core.id(id))),
        is(Arrays.asList("skip count cannot reference $0")));

    // A leaf cannot see the id of the node above it.
    final Core.IdPat id2 = f.elementPat(f.list12);
    final Core.Rel badLeaf =
        core.filter(
            id2,
            null,
            core.list(f.typeSystem, core.id(id2)),
            f.greaterThan(core.id(id2), f.intLiteral(1)));
    assertThat(
        f.violations(badLeaf), is(Arrays.asList("leaf cannot reference $0")));

    // A nested node binds an id of its own.
    final Core.Rel nested =
        f.filter(
            f.filter(f.list12, r -> f.greaterThan(r, f.intLiteral(1))),
            r -> f.greaterThan(r, f.intLiteral(0)));
    assertThat(f.violations(nested), empty());

    // A dependent join's right input binds its own id, and may read the
    // join's left id.
    final Core.IdPat leftId = f.elementPat(f.list12);
    final Core.Exp right =
        f.project(
            core.list(f.typeSystem, core.id(leftId)),
            r -> f.record(core.id(leftId), r));
    final Core.Rel dependent =
        core.join(
            f.typeSystem,
            JoinType.INNER,
            leftId,
            f.rightElementPat(right),
            null,
            f.list12,
            right,
            core.boolLiteral(true));
    assertThat(f.violations(dependent), empty());

    // The right id is in scope in the condition only; the right input
    // cannot read it.
    final Core.IdPat leftId2 = f.elementPat(f.list12);
    final Core.IdPat rightId2 = f.rightElementPat(f.list34);
    final Core.Rel badRight =
        core.join(
            f.typeSystem,
            JoinType.INNER,
            leftId2,
            rightId2,
            null,
            f.list12,
            core.list(f.typeSystem, core.id(rightId2)),
            core.boolLiteral(true));
    assertThat(
        f.violations(badRight), is(Arrays.asList("leaf cannot reference $1")));
  }

  /**
   * Tests that a node's id is a variable bound by the node, so that a pass that
   * reasons about variables treats it as it treats a {@code fn}'s parameter:
   * two nodes' ids are two variables, neither is free, and a name the query
   * wrote still is.
   */
  @Test
  void testRowIsBound() {
    final Fixture f = new Fixture();

    // filter [#1 $0 = 1] over project [{...}] over a leaf: two nodes, each
    // reading its own id, and the two ids are of different types.
    final Core.Project project = f.project(f.list12, r -> f.record(r, r));
    final Core.Filter filter =
        f.filter(
            project,
            r ->
                core.equal(
                    f.typeSystem,
                    core.field(f.typeSystem, r, 0),
                    f.intLiteral(1)));
    assertThat(filter.id, not(is(project.id)));
    assertThat(filter.freePats(f.typeSystem), empty());
    assertThat(ids(filter), is(ImmutableList.of("$0", "$0", "$0")));

    // A name the query wrote is free.
    final Core.IdPat e = core.idPat(f.intType, "e", 0);
    final Core.Rel free =
        f.filter(
            f.list12, r -> core.equal(f.typeSystem, core.id(e), core.id(e)));
    assertThat(free.freePats(f.typeSystem), is(ImmutableSet.of(e)));
  }

  /**
   * A tree nested in a node's expression may read the node's id; on its own it
   * may not, unless the node's patterns are given as the outer scope. A name
   * the expression binds itself is its own to read, whatever it is called.
   */
  @Test
  void testValidatorScope() {
    final Fixture f = new Fixture();
    final Core.IdPat outerId = f.elementPat(f.list12);
    final Core.Rel nested =
        f.filter(f.list34, r -> core.equal(f.typeSystem, r, core.id(outerId)));
    final Core.Rel outer =
        core.filter(
            outerId,
            null,
            f.list12,
            core.nonEmpty(f.typeSystem, Pos.ZERO, nested));
    assertThat(f.violations(outer), empty());
    assertThat(
        f.violations(nested),
        is(Arrays.asList("filter condition cannot reference $0")));
    assertThat(
        RelValidator.violations(f.typeSystem, nested, outerId::equals),
        empty());

    // The resolver names a composed aggregate's parameter '$col'.
    final Core.IdPat col = core.idPat(PrimitiveType.BOOL, "$col", 0);
    final Core.Rel let =
        f.filter(
            f.list12,
            r ->
                core.let(
                    core.nonRecValDecl(
                        Pos.ZERO, col, null, core.boolLiteral(true)),
                    core.id(col)));
    assertThat(f.violations(let), empty());
  }

  /** Returns the names of every {@link Core.Id} in an expression. */
  private static List<String> ids(Core.Exp exp) {
    final List<String> names = new ArrayList<>();
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Id id) {
            names.add(id.idPat.name);
          }
        });
    return names;
  }
}

// End RelTest.java
