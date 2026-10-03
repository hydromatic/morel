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
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * Applies {@link RelRule}s to the relational trees in an expression, and holds
 * the standard rules.
 *
 * <p>The driver is deterministic. At a tree's root it first applies the
 * whole-tree rules, in the order given, until none fires. Then it rewrites the
 * tree bottom-up: a node's inputs first, and the trees nested in its
 * expressions, then the node, where it tries the node rules in the order given
 * and applies the first that fires. A firing may put new nodes under the
 * replacement, so the replacement is rewritten in turn, and the rules are tried
 * again at it until none fires. The order of the rules and of the walk being
 * fixed, the result depends on the tree, the rules and the context -- the
 * environment, and whether the elements are read -- and nothing else.
 *
 * <p>Every firing is checked: the replacement has the type of the node it
 * replaces, always, and, with assertions on, is a well-formed tree by {@link
 * RelValidator}. Those are the two properties a rule must preserve and the
 * first place to look when one is wrong.
 */
public class RelRules {
  /** Firings beyond which a rule set is taken to be looping. */
  private static final int LIMIT = 10_000;

  /** Drops a {@code filter} whose condition is {@code true}. */
  public static final RelRule FILTER_TRUE =
      new RelRule() {
        @Override
        public String name() {
          return "FilterTrue";
        }

        @Override
        public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
          if (rel instanceof Core.Filter
              && ((Core.Filter) rel).condition.isBoolLiteral(true)) {
            return ((Core.Filter) rel).input;
          }
          return null;
        }
      };

  /**
   * Merges a {@code filter} over a {@code filter} into one whose condition is
   * the conjunction of the two, the inner's first.
   *
   * <p>The resolver leaves the {@code where} steps as the user wrote them, so
   * that a plan reads as the query does; it is here that they become one
   * condition. A condition that reads the ordinal counts the elements the inner
   * filter let through, which the merged filter would not have, so such a
   * filter stays one of its own.
   */
  public static final RelRule FILTER_MERGE =
      new RelRule() {
        @Override
        public String name() {
          return "FilterMerge";
        }

        @Override
        public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
          final TypeSystem typeSystem = cx.typeSystem();
          if (!(rel instanceof Core.Filter)) {
            return null;
          }
          final Core.Filter outer = (Core.Filter) rel;
          if (outer.ordinalId != null
              || !(outer.input instanceof Core.Filter)) {
            return null;
          }
          final Core.Filter inner = (Core.Filter) outer.input;
          final Core.Exp condition =
              core.substitute(
                  typeSystem, outer.condition, outer.id, core.id(inner.id));
          return core.filter(
              outer.pos(),
              inner.id,
              inner.ordinalId,
              inner.input,
              core.andAlso(typeSystem, inner.condition, condition));
        }
      };

  /**
   * Drops a {@code project} whose expression is its input's element and that
   * binds no ordinal.
   */
  public static final RelRule PROJECT_IDENTITY =
      new RelRule() {
        @Override
        public String name() {
          return "ProjectIdentity";
        }

        @Override
        public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
          if (rel instanceof Core.Project) {
            final Core.Project project = (Core.Project) rel;
            if (project.ordinalId == null && isId(project.exp, project.id)) {
              return project.input;
            }
          }
          return null;
        }
      };

  /**
   * Merges a {@code project} over a {@code project} into one, by substituting
   * the inner's expression into the outer's.
   *
   * <p>Substitution duplicates: {@code project [$0 + $0]} over {@code project
   * [f $0]} would call {@code f} twice per element where the two nodes called
   * it once. Where the outer reads its element more than once, the inner's
   * expression is bound by a {@code let} first, which keeps the one evaluation
   * the two nodes had. An expression that only reads -- a variable, a literal,
   * a field, a record of those -- costs nothing to duplicate, and is
   * substituted however often it is read, so that the fold below can reach it.
   *
   * <p>A projection keeps its input's positions, so an ordinal the outer reads
   * is the inner's input's, and the merged node binds it.
   *
   * <p>Where the inner builds a record and the outer reads a field of it, the
   * substitution leaves {@code #id {id = #id $0}}, and that is folded to {@code
   * #id $0}.
   */
  public static final RelRule PROJECT_MERGE =
      new RelRule() {
        @Override
        public String name() {
          return "ProjectMerge";
        }

        @Override
        public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
          final TypeSystem typeSystem = cx.typeSystem();
          if (!(rel instanceof Core.Project)) {
            return null;
          }
          final Core.Project outer = (Core.Project) rel;
          if (!(outer.input instanceof Core.Project)) {
            return null;
          }
          final Core.Project inner = (Core.Project) outer.input;
          Core.@Nullable IdPat ordinal = inner.ordinalId;
          Core.Exp exp = outer.exp;
          if (outer.ordinalId != null) {
            if (ordinal == null) {
              ordinal = outer.ordinalId;
            } else {
              exp =
                  core.substitute(
                      typeSystem, exp, outer.ordinalId, core.id(ordinal));
            }
          }
          final Core.Exp exp2;
          if (count(exp, outer.id) <= 1 || isCheap(inner.exp)) {
            exp2 =
                foldSelectors(
                    typeSystem,
                    core.substitute(typeSystem, exp, outer.id, inner.exp));
          } else {
            final Core.IdPat pat =
                core.idPat(
                    inner.exp.type,
                    "v$" + typeSystem.nameGenerator.inc("v$"),
                    0);
            exp2 =
                core.let(
                    core.nonRecValDecl(inner.exp.pos(), pat, null, inner.exp),
                    core.substitute(typeSystem, exp, outer.id, core.id(pat)));
          }
          return core.project(
              outer.pos(), typeSystem, inner.id, ordinal, inner.input, exp2);
        }
      };

  /** Drops a {@code skip} whose count is {@code 0}. */
  public static final RelRule SKIP_ZERO =
      new RelRule() {
        @Override
        public String name() {
          return "SkipZero";
        }

        @Override
        public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
          if (rel instanceof Core.Skip) {
            final Core.Skip skip = (Core.Skip) rel;
            if (skip.count.op == Op.INT_LITERAL
                && ((Core.Literal) skip.count).value.equals(BigDecimal.ZERO)) {
              return skip.input;
            }
          }
          return null;
        }
      };

  /**
   * Grounds a tree: replaces the extents among its leaves -- "every value of
   * the type" -- with generators derived from the tree's constraints.
   *
   * <p>A whole-tree rule: the constraints are wherever the query put them, and
   * it reads the tree as it was built. Under {@code exists} or {@code empty}
   * the elements are only counted, and the grounding may then simplify what an
   * element is. A tree in a recursive function's body is left alone: it is
   * expanded when the function is called, or recognized as a transitive
   * closure.
   *
   * <p>A generator's collection may be a query of its own over an extent that
   * its own conditions bound, so the leaves that are trees are grounded in turn
   * before the result is judged. A result with an unbounded leaf, or one that
   * reads a field its element no longer has, is not a grounding, and the rule
   * declines; the tree stays as it was, and the diagnostic is the caller's,
   * which asks for it after every rule has had its chance.
   */
  public static final RelRule GROUND =
      new RelRule() {
        @Override
        public String name() {
          return "Ground";
        }

        @Override
        public boolean wholeTree() {
          return true;
        }

        @Override
        public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
          if (cx.inRecursiveFunction() || !RelExpander.containsUnbounded(rel)) {
            return null;
          }
          final Core.Exp expanded0 =
              RelExpander.expand(
                  cx.typeSystem(),
                  cx.env(),
                  rel,
                  cx.rowsUsed(),
                  RelExpander.leafPats(rel));
          final Core.Exp expanded = groundLeaves(cx, expanded0);
          if (RelExpander.containsUnbounded(expanded)
              || Expander.misaddressed(expanded)) {
            return null;
          }
          return expanded;
        }

        /** Grounds the leaves of a tree that are trees over extents. */
        private Core.Exp groundLeaves(RelRule.Context cx, Core.Exp exp) {
          if (!(exp instanceof Core.Rel)) {
            return exp;
          }
          return copy(
              cx.typeSystem(),
              (Core.Rel) exp,
              input -> {
                if (input instanceof Core.Rel
                    && RelExpander.containsUnbounded(input)) {
                  final Core.@Nullable Exp grounded =
                      apply(cx, (Core.Rel) input);
                  return grounded != null ? grounded : input;
                }
                return groundLeaves(cx, input);
              },
              Function.identity());
        }
      };

  /**
   * The rules that every query is rewritten with, in order.
   *
   * <p>Grounding comes first, and is the one whole-tree rule: it sees each tree
   * before the node rules reshape it.
   *
   * <p>The builder applies most of these as it builds -- what is cheaper not to
   * build than to build and remove -- and they are rules as well for the trees
   * that a pass leaves: a filter under a filter after inlining, say. The
   * resolver builds without the filter merge on purpose, so that the plan of a
   * query reads as the query does.
   */
  public static final ImmutableList<RelRule> STANDARD =
      ImmutableList.of(
          GROUND,
          FILTER_TRUE,
          FILTER_MERGE,
          PROJECT_IDENTITY,
          PROJECT_MERGE,
          SKIP_ZERO);

  private RelRules() {}

  /**
   * Returns whether an expression only reads: a variable, a literal, a field of
   * such, or a record of such. Duplicating one evaluates nothing twice.
   */
  private static boolean isCheap(Core.Exp exp) {
    switch (exp.op) {
      case ID:
      case BOOL_LITERAL:
      case CHAR_LITERAL:
      case INT_LITERAL:
      case REAL_LITERAL:
      case STRING_LITERAL:
      case UNIT_LITERAL:
        return true;
      case TUPLE:
        return ((Core.Tuple) exp).args.stream().allMatch(RelRules::isCheap);
      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        return apply.fn.op == Op.RECORD_SELECTOR && isCheap(apply.arg);
      default:
        return false;
    }
  }

  private static boolean isId(Core.Exp exp, Core.IdPat id) {
    return exp instanceof Core.Id && ((Core.Id) exp).idPat.equals(id);
  }

  /** Returns how many times an expression reads a pattern. */
  private static int count(Core.Exp exp, Core.IdPat pat) {
    final int[] n = {0};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Id id) {
            if (id.idPat.equals(pat)) {
              ++n[0];
            }
          }
        });
    return n[0];
  }

  /**
   * Folds a field access applied to a record that is built right there.
   *
   * <p>Substitution produces {@code #y {x = a, y = b}}: a projection merged
   * into the one above it, or a join's yield put into a condition. The engine
   * looks for a reference to a variable, not for a record it could have taken
   * apart, so folding it to {@code b} is what lets the engine see the
   * constraint.
   */
  static Core.Exp foldSelectors(TypeSystem typeSystem, Core.Exp exp) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Apply apply) {
            final Core.Exp exp2 = super.visit(apply);
            if (exp2 instanceof Core.Apply) {
              final Core.Apply apply2 = (Core.Apply) exp2;
              if (apply2.fn.op == Op.RECORD_SELECTOR
                  && apply2.arg.op == Op.TUPLE
                  // The selector reads a slot of the record it was made for,
                  // and a substitution can put a tuple under a selector built
                  // for another, of a different arity, where the slot is not
                  // even in range. Fold only what is; folding is what lets the
                  // engine see a constraint, so decline no more than this.
                  && ((Core.RecordSelector) apply2.fn).slot
                      < ((Core.Tuple) apply2.arg).args.size()) {
                return ((Core.Tuple) apply2.arg)
                    .args.get(((Core.RecordSelector) apply2.fn).slot);
              }
            }
            return exp2;
          }
        });
  }

  /** Rewrites every tree in a declaration. */
  public static Core.Decl rewrite(
      TypeSystem typeSystem,
      Environment env,
      List<RelRule> rules,
      Core.Decl decl) {
    return decl.accept(new Driver(typeSystem, rules).walker(env));
  }

  /** Rewrites every tree in an expression. */
  public static Core.Exp rewrite(
      TypeSystem typeSystem,
      Environment env,
      List<RelRule> rules,
      Core.Exp exp) {
    return new Driver(typeSystem, rules).walker(env).rewrite(exp);
  }

  /** Rewrites every tree in an expression that has no free names. */
  public static Core.Exp rewrite(
      TypeSystem typeSystem, List<RelRule> rules, Core.Exp exp) {
    return rewrite(typeSystem, Environments.empty(), rules, exp);
  }

  /**
   * Copies a node with its inputs and its expressions passed through the given
   * functions; returns the node itself if nothing changed.
   */
  private static Core.Exp copy(
      TypeSystem typeSystem,
      Core.Rel rel,
      Function<Core.Exp, Core.Exp> inputFn,
      Function<Core.Exp, Core.Exp> expFn) {
    if (rel instanceof Core.Filter) {
      final Core.Filter filter = (Core.Filter) rel;
      return filter.copy(
          inputFn.apply(filter.input), expFn.apply(filter.condition));
    }
    if (rel instanceof Core.Project) {
      final Core.Project project = (Core.Project) rel;
      return project.copy(
          typeSystem, inputFn.apply(project.input), expFn.apply(project.exp));
    }
    if (rel instanceof Core.Join) {
      final Core.Join join = (Core.Join) rel;
      return join.copy(
          typeSystem,
          join.joinType,
          inputFn.apply(join.left),
          inputFn.apply(join.right),
          expFn.apply(join.condition));
    }
    if (rel instanceof Core.Group) {
      final Core.Group group = (Core.Group) rel;
      return group.copy(
          typeSystem,
          inputFn.apply(group.input),
          transformValues(group.keys, expFn),
          transformValues(
              group.aggregates,
              a ->
                  a.copy(
                      a.type,
                      expFn.apply(a.aggregate),
                      a.argument == null ? null : expFn.apply(a.argument))));
    }
    if (rel instanceof Core.Sort) {
      final Core.Sort sort = (Core.Sort) rel;
      return sort.copy(
          typeSystem, inputFn.apply(sort.input), expFn.apply(sort.exp));
    }
    if (rel instanceof Core.Unorder) {
      final Core.Unorder unorder = (Core.Unorder) rel;
      return unorder.copy(typeSystem, inputFn.apply(unorder.input));
    }
    if (rel instanceof Core.Skip) {
      final Core.Skip skip = (Core.Skip) rel;
      return skip.copy(inputFn.apply(skip.input), expFn.apply(skip.count));
    }
    if (rel instanceof Core.Take) {
      final Core.Take take = (Core.Take) rel;
      return take.copy(inputFn.apply(take.input), expFn.apply(take.count));
    }
    if (rel instanceof Core.SetRel) {
      final Core.SetRel setRel = (Core.SetRel) rel;
      return setRel.copy(
          typeSystem,
          setRel.distinct,
          transformEager(setRel.inputs, inputFn::apply));
    }
    throw new AssertionError("unknown node " + rel.op);
  }

  private static <V> SortedMap<String, V> transformValues(
      SortedMap<String, V> map, Function<V, V> fn) {
    final SortedMap<String, V> map2 = new TreeMap<>(map.comparator());
    map.forEach((k, v) -> map2.put(k, fn.apply(v)));
    return map2;
  }

  /** One rewrite of one expression: the rules, and the count of firings. */
  private static class Driver {
    final TypeSystem typeSystem;
    final List<RelRule> wholeTreeRules;
    final List<RelRule> nodeRules;
    int firings;

    Driver(TypeSystem typeSystem, List<RelRule> rules) {
      this.typeSystem = typeSystem;
      final ImmutableList.Builder<RelRule> whole = ImmutableList.builder();
      final ImmutableList.Builder<RelRule> node = ImmutableList.builder();
      rules.forEach(rule -> (rule.wholeTree() ? whole : node).add(rule));
      this.wholeTreeRules = whole.build();
      this.nodeRules = node.build();
    }

    Walker walker(Environment env) {
      return new Walker(env, true, false, ImmutableSet.of());
    }

    /**
     * Applies rules at a node until none fires, and returns the result.
     *
     * <p>{@code below} is how a firing's replacement is completed: a node rule
     * may put new nodes under its replacement, which are rewritten in turn; a
     * whole-tree rule's replacement is rewritten below afterwards, as a whole.
     */
    private Core.Exp fire(
        List<RelRule> rules,
        RelRule.Context cx,
        Set<Core.IdPat> patterns,
        Core.Exp node,
        Function<Core.Exp, Core.Exp> below) {
      while (node instanceof Core.Rel) {
        final Core.Rel rel = (Core.Rel) node;
        Core.@Nullable Exp next = null;
        for (RelRule rule : rules) {
          final Core.@Nullable Exp result = rule.apply(cx, rel);
          if (result != null && result != rel) {
            check(rule, cx, rel, result, patterns);
            next = result;
            break;
          }
        }
        if (next == null) {
          return node;
        }
        if (++firings > LIMIT) {
          throw new IllegalStateException(
              "rules did not converge after " + LIMIT + " firings");
        }
        node = below.apply(next);
      }
      return node;
    }

    /** Checks the two properties every rule must preserve. */
    private void check(
        RelRule rule,
        RelRule.Context cx,
        Core.Rel before,
        Core.Exp after,
        Set<Core.IdPat> patterns) {
      final boolean ok =
          cx.rowsUsed()
              ? after.type.equals(before.type)
              // Nothing reads the elements, so the element may change; the kind
              // may not.
              : after.type.isCollection()
                  && after.type.getClass() == before.type.getClass();
      if (!ok) {
        throw new AssertionError(
            String.format(
                "rule %s changed the type of %s from %s to %s",
                rule.name(),
                before.op.lowerName,
                before.type.moniker(),
                after.type.moniker()));
      }
      assert valid(rule, after, patterns);
    }

    private boolean valid(
        RelRule rule, Core.Exp after, Set<Core.IdPat> patterns) {
      if (after instanceof Core.Rel) {
        final List<String> violations =
            RelValidator.violations(
                typeSystem, (Core.Rel) after, patterns::contains);
        if (!violations.isEmpty()) {
          throw new AssertionError(
              "rule "
                  + rule.name()
                  + " built an invalid tree: "
                  + violations
                  + "\n"
                  + after);
        }
      }
      return true;
    }

    /**
     * Walks an expression in its context, and rewrites each tree it meets; a
     * tree's own walk is {@link #rewrite}, so the shuttle does not descend into
     * one.
     *
     * <p>The context is the environment, whether the elements are read, and
     * whether the walk is in a recursive function's body, and each is what it
     * was where the tree stands. The walker also carries the patterns of the
     * enclosing nodes, which a tree nested in a node's expression may read;
     * they are for the validator, which would otherwise see such a read as
     * unbound.
     */
    private class Walker extends EnvShuttle implements RelRule.Context {
      private final boolean rowsUsed;
      private final boolean inRecursiveFunction;
      private final Set<Core.IdPat> patterns;

      Walker(
          Environment env,
          boolean rowsUsed,
          boolean inRecursiveFunction,
          Set<Core.IdPat> patterns) {
        super(Driver.this.typeSystem, env);
        this.rowsUsed = rowsUsed;
        this.inRecursiveFunction = inRecursiveFunction;
        this.patterns = patterns;
      }

      @Override
      protected EnvShuttle push(Environment env) {
        return new Walker(env, rowsUsed, inRecursiveFunction, patterns);
      }

      /** Returns a walker for what is under a node, which binds patterns. */
      private Walker under(Iterable<Core.IdPat> pats) {
        return new Walker(
            env,
            rowsUsed,
            inRecursiveFunction,
            ImmutableSet.<Core.IdPat>builder()
                .addAll(patterns)
                .addAll(pats)
                .build());
      }

      @Override
      public TypeSystem typeSystem() {
        return typeSystem;
      }

      @Override
      public Environment env() {
        return env;
      }

      @Override
      public boolean rowsUsed() {
        return rowsUsed;
      }

      @Override
      public boolean inRecursiveFunction() {
        return inRecursiveFunction;
      }

      @Override
      protected Core.Exp visit(Core.Apply apply) {
        if (rowsUsed
            && (apply.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)
                || apply.isCallTo(BuiltIn.RELATIONAL_EMPTY))) {
          // The argument's elements are counted, not read.
          final Walker inner =
              new Walker(env, false, inRecursiveFunction, patterns);
          return apply.copy(apply.fn.accept(inner), apply.arg.accept(inner));
        }
        return super.visit(apply);
      }

      @Override
      protected Core.RecValDecl visit(Core.RecValDecl recValDecl) {
        if (inRecursiveFunction) {
          return super.visit(recValDecl);
        }
        return new Walker(env, rowsUsed, true, patterns)
            .visitRecursive(recValDecl);
      }

      private Core.RecValDecl visitRecursive(Core.RecValDecl recValDecl) {
        return super.visit(recValDecl);
      }

      @Override
      protected Core.@Nullable Exp visitRel(Core.Rel rel) {
        return rewrite(rel);
      }

      /**
       * Rewrites an expression: a tree from its root, anything else by walk.
       */
      Core.Exp rewrite(Core.Exp exp) {
        if (!(exp instanceof Core.Rel)) {
          return exp.accept(this);
        }
        // The whole-tree rules first, at the root, on the tree as it was
        // built; then the tree below, and the node rules.
        final Core.Exp tree =
            fire(wholeTreeRules, this, patterns, exp, Function.identity());
        return tree instanceof Core.Rel
            ? rewriteNodes((Core.Rel) tree)
            : tree.accept(this);
      }

      /**
       * Rewrites a tree bottom-up: its inputs, and the trees nested in its
       * expressions, then the node rules at the node.
       */
      private Core.Exp rewriteNodes(Core.Rel rel) {
        // The node's expressions may read its patterns; a join's right input
        // may read its left id, and its ordinal.
        final Walker inner = under(rel.patterns());
        final Core.Exp node =
            copy(typeSystem, rel, inputFn(rel), inner::rewrite);
        // A firing may put new nodes below its replacement; rewrite those,
        // but not the expressions, which the walk has been through already.
        return fire(
            nodeRules,
            this,
            patterns,
            node,
            next ->
                next instanceof Core.Rel
                    ? copy(
                        typeSystem,
                        (Core.Rel) next,
                        inputFn((Core.Rel) next),
                        Function.identity())
                    : next);
      }

      /**
       * Returns the function that rewrites a node's inputs: a join's right
       * input under a walker that binds the join's left id, and its ordinal if
       * it has one, since the right input may read them; any other input under
       * this walker.
       */
      private Function<Core.Exp, Core.Exp> inputFn(Core.Rel rel) {
        final Walker right =
            rel instanceof Core.Join
                ? under(
                    ((Core.Join) rel).ordinalId == null
                        ? ImmutableList.of(((Core.Join) rel).leftId)
                        : ImmutableList.of(
                            ((Core.Join) rel).leftId,
                            ((Core.Join) rel).ordinalId))
                : this;
        return input ->
            (rel instanceof Core.Join && input == ((Core.Join) rel).right
                    ? right
                    : this)
                .rewriteInput(input);
      }

      /** Rewrites an input: a node by the node rules, a leaf by walk. */
      private Core.Exp rewriteInput(Core.Exp input) {
        return input instanceof Core.Rel
            ? rewriteNodes((Core.Rel) input)
            : input.accept(this);
      }
    }
  }
}

// End RelRules.java
