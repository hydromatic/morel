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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.RelBuilder;
import net.hydromatic.morel.ast.Simplification;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.RelRule;
import net.hydromatic.morel.compile.RelRules;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Tests {@link RelRules}, the driver that applies rules to a tree. */
public class RelRulesTest {
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();

    {
      // Registers 'bag'.
      BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    }

    /** {@code [3, 1, 2]}. */
    final Core.Exp list =
        core.list(
            typeSystem,
            PrimitiveType.INT,
            ImmutableList.of(intLiteral(3), intLiteral(1), intLiteral(2)));

    Core.Exp intLiteral(int i) {
      return core.intLiteral(BigDecimal.valueOf(i));
    }

    /** {@code unorder (sort [$0] [3, 1, 2])}. */
    Core.Exp unorderSort() {
      final RelBuilder b = RelBuilder.create(typeSystem);
      b.push(list);
      b.sort(b.input(0));
      b.unorder();
      return b.build();
    }

    /** A builder that simplifies nothing, so that a test sees a rule fire. */
    RelBuilder builder() {
      return RelBuilder.create(
          typeSystem, EnumSet.noneOf(Simplification.class));
    }

    /** Rewrites a tree with the standard rules. */
    Core.Exp rewrite(Core.Exp tree) {
      return RelRules.rewrite(typeSystem, RelRules.STANDARD, tree);
    }
  }

  /** A rule that fires wrongly: it returns a node of another kind. */
  private static final RelRule BAD =
      new RelRule() {
        @Override
        public String name() {
          return "Bad";
        }

        @Override
        public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
          return rel instanceof Core.Unorder
              ? ((Core.Unorder) rel).input
              : null;
        }
      };

  /** Drops a sort under an unorder, which has nothing to keep it for. */
  private static final RelRule SORT_UNDER_UNORDER =
      new RelRule() {
        @Override
        public String name() {
          return "SortUnderUnorder";
        }

        @Override
        public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
          if (rel instanceof Core.Unorder
              && ((Core.Unorder) rel).input instanceof Core.Sort) {
            final Core.Sort sort = (Core.Sort) ((Core.Unorder) rel).input;
            return core.unorder(cx.typeSystem(), sort.input);
          }
          return null;
        }
      };

  /**
   * A whole-tree rule that fires once, at the root, and records what it was
   * given; and a node rule that fires at every node, likewise.
   */
  private static class Recorder implements RelRule {
    final boolean wholeTree;
    final List<String> seen = new ArrayList<>();

    Recorder(boolean wholeTree) {
      this.wholeTree = wholeTree;
    }

    @Override
    public String name() {
      return wholeTree ? "WholeTree" : "Node";
    }

    @Override
    public boolean wholeTree() {
      return wholeTree;
    }

    @Override
    public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
      seen.add(rel.op.lowerName + (cx.rowsUsed() ? "" : " (rows unused)"));
      return null;
    }
  }

  /** Two filters become one; three become one, the rule firing twice. */
  @Test
  void testFilterMerge() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push(f.list);
    b.filter(core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(0)));
    b.filter(core.lessThan(f.typeSystem, b.input(0), f.intLiteral(3)));
    final Core.Exp tree = b.build();
    assertThat(
        tree,
        hasToString(
            "filter r [r < 3]\n" //
                + "  filter r [r > 0]\n"
                + "    [3, 1, 2]\n"));
    assertThat(
        f.rewrite(tree),
        hasToString(
            "filter r [r > 0 andalso r < 3]\n" //
                + "  [3, 1, 2]\n"));

    b.push(tree);
    b.filter(core.notEqual(f.typeSystem, b.input(0), f.intLiteral(1)));
    assertThat(
        f.rewrite(b.build()),
        hasToString(
            "filter r [r > 0 andalso r < 3 andalso r <> 1]\n" //
                + "  [3, 1, 2]\n"));
  }

  /** A filter that reads the ordinal is not merged into the one below. */
  @Test
  void testFilterMergeOrdinal() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push(f.list);
    b.filter(core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(0)));
    b.filter(core.greaterThan(f.typeSystem, b.ordinal(), f.intLiteral(0)));
    final Core.Exp tree = b.build();
    assertThat(
        tree,
        hasToString(
            "filter r $ordinal [$ordinal > 0]\n" //
                + "  filter r [r > 0]\n"
                + "    [3, 1, 2]\n"));
    assertThat(f.rewrite(tree), sameInstance(tree));
  }

  /** A projection of the element goes. */
  @Test
  void testProjectIdentity() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push(f.list);
    b.project(b.input(0));
    final Core.Exp tree = b.build();
    assertThat(
        tree,
        hasToString(
            "project r [r]\n" //
                + "  [3, 1, 2]\n"));
    assertThat(f.rewrite(tree), hasToString("[3, 1, 2]"));
  }

  /**
   * Two projections become one by substitution; where the outer reads its
   * element twice, the inner's expression is bound once.
   */
  @Test
  void testProjectMerge() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push(f.list);
    b.project(core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(1)));
    b.project(core.notEqual(f.typeSystem, b.input(0), core.boolLiteral(true)));
    final Core.Exp tree = b.build();
    assertThat(
        tree,
        hasToString(
            "project r [r <> true]\n" //
                + "  project r [r > 1]\n"
                + "    [3, 1, 2]\n"));
    assertThat(
        f.rewrite(tree),
        hasToString(
            "project r [r > 1 <> true]\n" //
                + "  [3, 1, 2]\n"));

    final RelBuilder b2 = f.builder();
    b2.push(f.list);
    b2.project(core.greaterThan(f.typeSystem, b2.input(0), f.intLiteral(1)));
    b2.project(core.andAlso(f.typeSystem, b2.input(0), b2.input(0)));
    // The binder's number is whatever the generator was at; a plan renumbers.
    assertThat(
        f.rewrite(b2.build()).toString().replaceAll("v\\$[0-9]+", "v\\$"),
        is(
            "project r [let val v$ = r > 1 in v$ andalso v$ end]\n" //
                + "  [3, 1, 2]\n"));
  }

  /** A skip of nought goes. */
  @Test
  void testSkipZero() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push(f.list);
    b.skip(f.intLiteral(0));
    final Core.Exp tree = b.build();
    assertThat(
        tree,
        hasToString(
            "skip [0]\n" //
                + "  [3, 1, 2]\n"));
    assertThat(f.rewrite(tree), hasToString("[3, 1, 2]"));
  }

  /**
   * A whole-tree rule sees the root before the node rules have rewritten
   * anything below it; a node rule sees each node, inputs first.
   */
  @Test
  void testWholeTreeRuleSeesTheTreeFirst() {
    final Fixture f = new Fixture();
    final Core.Exp tree = f.unorderSort();
    final Recorder whole = new Recorder(true);
    final Recorder node = new Recorder(false);
    final Core.Exp tree2 =
        RelRules.rewrite(
            f.typeSystem,
            ImmutableList.of(node, whole, SORT_UNDER_UNORDER),
            tree);
    assertThat(
        tree2,
        hasToString(
            "unorder\n" //
                + "  [3, 1, 2]\n"));
    // The whole-tree rule was given the unorder over the sort; by the time
    // the node rule reached the root, the sort was gone.
    assertThat(whole.seen, hasToString("[unorder]"));
    assertThat(node.seen, hasToString("[sort, unorder, unorder]"));
  }

  /** With no rules, a tree is returned as it is. */
  @Test
  void testNoRules() {
    final Fixture f = new Fixture();
    final Core.Exp tree = f.unorderSort();
    final Core.Exp tree2 =
        RelRules.rewrite(f.typeSystem, ImmutableList.of(), tree);
    assertThat(tree2, sameInstance(tree));
  }

  /** A rule that changes the root's type is caught at the firing. */
  @Test
  void testRuleMustPreserveType() {
    final Fixture f = new Fixture();
    final Core.Exp tree = f.unorderSort();
    final AssertionError e =
        assertThrows(
            AssertionError.class,
            () -> RelRules.rewrite(f.typeSystem, ImmutableList.of(BAD), tree));
    assertThat(e.getMessage(), containsString("rule Bad changed the type"));
  }
}

// End RelRulesTest.java
