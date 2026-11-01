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
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasToString;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link RelExpander}, the front end that grounds the infinite-extent
 * leaves of a relational tree.
 *
 * <p>The engine it calls is {@link Generators}, so what these tests check is
 * the front end: that naming a leaf's element and rewriting the filters above
 * it in terms of that name yields the generator the constraints call for.
 *
 * <p>A generator appears wrapped in {@code group ... order ...} where its
 * duplicates would be observable: a collection may hold a value twice, and an
 * unbounded scan yields each assignment once.
 */
public class RelExpanderTest {
  /**
   * A query compiled as far as Core, with the type system and environment that
   * compiled it.
   */
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();
    final Environment env;
    final Core.Rel rel;

    Fixture(String ml) {
      final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
      final AstNode statement = parser.statementEofSafe();
      final Session session = null;
      env = Environments.env(typeSystem, session, ImmutableMap.of());
      final Ast.ValDecl valDecl = Compiles.toValDecl(statement);
      final Consumer<CompileException> ignoreWarnings = w -> {};
      final TypeResolver.Resolved resolved =
          TypeResolver.deduceType(env, valDecl, typeSystem, ignoreWarnings);
      final Resolver resolver = Resolver.of(resolved.typeMap, env, null);
      // Inline once, as Compiles does: until then a built-in such as 'elem'
      // is an Id, not the function literal that the engine matches on.
      final Core.ValDecl valDecl2 =
          (Core.ValDecl)
              resolver
                  .toCore((Ast.ValDecl) resolved.node)
                  .accept(Inliner.of(typeSystem, env, null));
      // The resolver returns the tree; take the outermost one.
      final Core.Rel[] rels = {null};
      valDecl2.accept(
          new Visitor() {
            @Override
            protected void visitRel(Core.Rel rel) {
              if (rels[0] == null) {
                rels[0] = rel;
              }
            }
          });
      rel = rels[0];
    }

    Core.Exp tree() {
      return requireNonNull(rel, "no query in this statement");
    }
  }

  /**
   * Returns the generator of the first infinite-extent leaf of a query's tree,
   * as a string.
   */
  private static String generator(String ml) {
    final Fixture f = new Fixture(ml);
    final Map<Core.Exp, Generator> generators =
        RelExpander.ground(f.typeSystem, f.env, f.tree());
    if (generators.isEmpty()) {
      return "no extent";
    }
    final Generator generator = generators.values().iterator().next();
    return generator == null
        ? "not grounded"
        : generator.exp + " : " + generator.cardinality;
  }

  /**
   * Returns the plan text of a query's tree, with its infinite-extent leaves
   * replaced by what bounds them.
   */
  private static String expanded(String ml) {
    final Fixture f = new Fixture(ml);
    final Core.Exp tree =
        RelExpander.expand(
            f.typeSystem, f.env, f.tree(), true, ImmutableList.of());
    return tree instanceof Core.Rel
        ? ((Core.Rel) tree).describe(f.typeSystem)
        : tree + "\n";
  }

  /**
   * The patterns a query was written with are read back off the projection that
   * names the element's components, or, where there is no such projection, off
   * the names the extents carry.
   */
  @Test
  void testLeafPats() {
    final Fixture f =
        new Fixture("from i, j where i elem [1] andalso j elem [2]");
    assertThat(RelExpander.leafPats(f.tree()), hasToString("[i, j]"));
    // A query with one binder has no projection, so the name is the one the
    // extent carries; a scan under a tuple of names carries one per component.
    final Fixture f2 = new Fixture("from i where i elem [1]");
    assertThat(RelExpander.leafPats(f2.tree()), hasToString("[i]"));
    final Fixture f3 = new Fixture("from (b: bool, i) where i elem [1]");
    assertThat(RelExpander.leafPats(f3.tree()), hasToString("[(b, i)]"));
    // A leaf that is not an extent carries no name.
    final Fixture f4 = new Fixture("from i in [1, 2] where i > 1");
    assertThat(RelExpander.leafPats(f4.tree()), empty());
  }

  /**
   * {@code ungrounded} names the first leaf that is still an infinite extent,
   * which after grounding is a leaf that nothing bounds.
   */
  @Test
  void testUngrounded() {
    final Fixture f = new Fixture("from i, j where j elem [2]");
    final List<Core.Pat> leafPats = RelExpander.leafPats(f.tree());
    assertThat(RelExpander.ungrounded(f.tree(), leafPats), hasToString("i"));
    final Fixture f1 = new Fixture("from j where j elem [1 ..]");
    final List<Core.Pat> leafPats1 = RelExpander.leafPats(f1.tree());
    assertThat(RelExpander.ungrounded(f1.tree(), leafPats1), hasToString("j"));
    final Fixture f2 =
        new Fixture("from i, j where i elem [1] andalso j elem [2]");
    final List<Core.Pat> leafPats2 = RelExpander.leafPats(f2.tree());
    assertThat(RelExpander.ungrounded(f2.tree(), leafPats2), hasToString("i"));
    final Core.Exp grounded =
        RelExpander.expand(f2.typeSystem, f2.env, f2.tree(), true, leafPats2);
    assertThat(RelExpander.ungrounded(grounded, leafPats2), nullValue());
  }

  /** Tests that a leaf constrained by 'elem' is grounded by the list. */
  @Test
  void testElem() {
    assertThat(
        generator("from x where x elem [1, 2, 3]"), is("[1, 2, 3] : FINITE"));
  }

  /**
   * Tests that a leaf with no constraint that bounds it comes back infinite.
   *
   * <p>That is how the engine reports "not grounded": it returns the extent
   * itself, and the caller decides that an infinite generator is an error.
   */
  @Test
  void testUnbounded() {
    assertThat(
        generator("from x where x > 1"), is("extent \"int\" : INFINITE"));
  }

  /** Tests that a query with no unbounded leaf has nothing to ground. */
  @Test
  void testBounded() {
    assertThat(generator("from x in [1, 2] where x > 1"), is("no extent"));
  }

  /**
   * Tests that the leaf is replaced by what bounds it, so that an unbounded
   * query becomes one that can run.
   *
   * <p>The filter goes too: the generator is sealed, so the collection that
   * replaced the leaf already enforces the condition, and the filter tested
   * nothing else.
   */
  @Test
  void testExpand() {
    assertThat(
        expanded("from x where x elem [1, 2, 3]"),
        is(
            "sort r [r]\n" //
                + "  project r [#`g$0` r]\n"
                + "    group g$0 [g$0 = g$0]\n"
                + "      [1, 2, 3]\n"
                + ""));
  }

  /** Tests that a condition a generator does not subsume is kept. */
  @Test
  void testExpandKeepsOtherConditions() {
    assertThat(
        expanded("from x where x elem [1, 2, 3] andalso x > 1"),
        is(
            "filter r [r > 1]\n" //
                + "  sort r [r]\n"
                + "    project r [#`g$0` r]\n"
                + "      group g$0 [g$0 = g$0]\n"
                + "        [1, 2, 3]\n"
                + ""));
  }

  /**
   * Tests a generator that reads another variable: the join becomes dependent,
   * its binder naming the left element that the generator needs.
   */
  @Test
  void testCorrelated() {
    assertThat(
        expanded("from x in [1, 2], y where y elem [x, x + 1]"),
        is(
            "project r [{x = #1 r, y = #2 r}]\n" //
                + "  join r r_1\n"
                + "    [1, 2]\n"
                + "    [r, r + 1]\n"));
  }

  /**
   * Tests that a leaf that is a list of numbers bounds its name, and that FBBT
   * carries the bound to another name: {@code z} in [1, 3] and {@code x + 1 =
   * z} put {@code x} in [0, 2].
   */
  @Test
  void testListBounds() {
    assertThat(
        expanded("from z in [1, 2, 3], x where x + 1 = z"),
        is(
            "project r [{x = #2 r, z = #1 r}]\n" //
                + "  filter r [#2 r + 1 = #1 r]\n"
                + "    join z x\n"
                + "      [1, 2, 3]\n"
                + "      #flatten Range ([CLOSED (0, 2)])\n"));
  }

  /**
   * Tests that a leaf correlated with an unbounded name is not taken as a bound
   * for it: {@code y in [x * 2]} cannot run until {@code x} has a generator, so
   * {@code x < y} is not what bounds {@code x}, and the constant bounds are.
   */
  @Test
  void testCorrelatedLeafIsNoBound() {
    assertThat(
        expanded(
            "from x, y in [x * 2] where x > 0 andalso x < 10 andalso x < y"),
        is(
            "project r [{x = #1 r, y = #2 r}]\n" //
                + "  filter r [#1 r < #2 r]\n"
                + "    join x y\n"
                + "      #flatten Range ([OPEN (0, 10)])\n"
                + "      [#`*` Int (x, 2)]\n"
                + ""));
  }

  /**
   * Tests that a condition reaches a leaf through a projection, which the step
   * list cannot do.
   *
   * <p>`from x yield {y = x} where y elem [2, 3]` errors today with "pattern
   * 'x' is not grounded"; a tree substitutes the projection into the condition
   * and grounds it.
   */
  @Test
  void testThroughProjection() {
    assertThat(
        generator("from x yield {y = x} where y elem [2, 3]"),
        is("[2, 3] : FINITE"));
  }

  /**
   * Tests that leaves which one generator binds together become one scan.
   *
   * <p>Replacing them separately would enumerate the collection once per leaf
   * and pair every value with every other; the user wrote one pattern, and a
   * tree has to notice.
   */
  @Test
  void testLeavesOneGeneratorBinds() {
    assertThat(
        expanded(
            "from i : int join j : string "
                + "where (i, j) elem [(1, \"a\"), (2, \"b\")]"),
        is(
            "project r [{i = #1 r, j = #2 r}]\n" //
                + "  project r [(#`g$0` r, #`g$1` r)]\n"
                + "    sort r [r]\n"
                + "      group r [g$0 = #1 r, g$1 = #2 r]\n"
                + "        [(1, \"a\"), (2, \"b\")]\n"));
  }

  /**
   * Tests that a leaf nothing constrains is dropped when the elements are only
   * counted.
   *
   * <p>{@code 'a} cannot be enumerated, so grounding `w` is impossible; but
   * inside an `exists` the elements do not matter, and neither does `w`.
   */
  @Test
  void testRowsNotUsed() {
    final Fixture f = new Fixture("from w : int join x : int where x = 3");
    final Core.Exp expanded =
        RelExpander.expand(
            f.typeSystem, f.env, f.tree(), false, ImmutableList.of());
    // `w` is gone, and so is the condition, which the generator enforces, and
    // so is the projection: nothing reads the elements, so what an element
    // *is* is not observable, and a projection written for two components
    // cannot stand over a collection that now has one.
    assertThat(
        expanded instanceof Core.Rel
            ? ((Core.Rel) expanded).describe(f.typeSystem)
            : expanded + "\n",
        is("[3]\n"));
  }

  /**
   * Tests that a filter between two joins constrains the leaves below it.
   *
   * <p>The tree is a join whose left input is a filter over another join, so
   * the walk has to see through the filter to reach the leaves, and gather its
   * conjuncts as it passes: {@code j} is bounded by a condition above the
   * filter and {@code i} by one below it.
   *
   * <p>The projection reads three components and not two, because a filter
   * passes its input's components through: it emits its input's elements
   * unchanged, so the element of the join above it is flat whether the filter
   * is there or not -- which is what lets expansion drop the filter, as it does
   * here, without re-associating what reads the element.
   */
  @Test
  void testFilterBetweenJoins() {
    assertThat(
        expanded(
            "from i : int join j : int where i elem [1, 2] "
                + "join k : int where j elem [3, 4] andalso k elem [5, 6]"),
        is(
            "project r [{i = #1 r, j = #2 r, k = #3 r}]\n" //
                + "  join r k\n"
                + "    join i j\n"
                + "      sort r [r]\n"
                + "        project r [#`g$0` r]\n"
                + "          group g$0 [g$0 = g$0]\n"
                + "            [1, 2]\n"
                + "      sort r [r]\n"
                + "        project r [#`g$1` r]\n"
                + "          group g$1 [g$1 = g$1]\n"
                + "            [3, 4]\n"
                + "    sort r_1 [r_1]\n"
                + "      project r_1 [#`g$2` r_1]\n"
                + "        group g$2 [g$2 = g$2]\n"
                + "          [5, 6]\n"
                + ""));
  }

  /**
   * Tests a generator that reads a name bound deeper in the left subtree.
   *
   * <p>{@code z} is correlated with {@code y}, which is not the join's left
   * input but a leaf inside it, so the binder names the left element and {@code
   * y} is read out of it by path.
   */
  @Test
  void testCorrelatedWithSubtree() {
    assertThat(
        expanded(
            "from x in [1, 2] join y where y elem [x] "
                + "join z where z elem [y, y + 1]"),
        is(
            "project r [{x = #1 r, y = #2 r, z = #3 r}]\n" //
                + "  join r r_1\n"
                + "    join r r_1\n"
                + "      [1, 2]\n"
                + "      [r]\n"
                + "    [#2 r, #2 r + 1]\n"));
  }

  /**
   * Tests a chain that grounds only if the join is reordered.
   *
   * <p>{@code dno} is unbounded until the condition {@code #deptno v = dno} is
   * seen, and by then {@code v} has not been declared; so {@code v} comes
   * first, and {@code dno} is read out of each of its elements.
   */
  @Test
  void testReordersToGroundAChain() {
    assertThat(
        expanded(
            "from dno : int join v : int list "
                + "where v elem [[1], [2]] andalso dno elem v"),
        is(
            "project r [{dno = #1 r, v = #2 r}]\n" //
                + "  project r [(#2 r, #1 r)]\n"
                + "    join r r_1\n"
                + "      sort r [r]\n"
                + "        project r [#`g$0` r]\n"
                + "          group g$0 [g$0 = g$0]\n"
                + "            [[1], [2]]\n"
                + "      sort r_1 [r_1]\n"
                + "        project r_1 [#`g$1` r_1]\n"
                + "          group g$1 [g$1 = g$1]\n"
                + "            r\n"
                + ""));
  }

  /** Tests that a query that cannot be bounded is an error. */
  @Test
  void testExpandUnbounded() {
    try {
      final String plan = expanded("from x where x > 1");
      fail("expected error, got " + plan);
    } catch (CompileException e) {
      assertThat(e.getMessage(), containsString("pattern is not grounded"));
    }
  }

  /**
   * Tests that a condition reaches the leaf through the nodes that do not
   * change the element: {@code from x take 3 where x elem [1, 2, 3]} bounds x
   * and then takes 3 of what remains.
   */
  @Test
  void testThroughOrderTakeSkip() {
    assertThat(
        generator("from x order x where x elem [1, 2, 3]"),
        is("[1, 2, 3] : FINITE"));
    assertThat(
        generator("from x take 3 where x elem [1, 2, 3]"),
        is("[1, 2, 3] : FINITE"));
    assertThat(
        generator("from x skip 1 where x elem [1, 2, 3]"),
        is("[1, 2, 3] : FINITE"));
  }
}

// End RelExpanderTest.java
