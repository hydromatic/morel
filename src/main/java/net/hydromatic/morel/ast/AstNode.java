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
package net.hydromatic.morel.ast;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Comparators;

/** Abstract syntax tree node. */
public abstract class AstNode {
  /**
   * This node's position in the source.
   *
   * <p>Not final, because parentheses are not known until after the node inside
   * them has been created, and the node's position must then cover them; {@link
   * #withPos} is how the parser says so. Protected, so that code elsewhere
   * reads it through {@link #pos()} and nothing but the parser is in a position
   * to write it.
   */
  protected Pos pos;

  public final Op op;

  public AstNode(Pos pos, Op op) {
    this.pos = requireNonNull(pos);
    this.op = requireNonNull(op);
  }

  /** Returns this node's position. */
  public Pos pos() {
    return pos;
  }

  /**
   * Sets this node's position, and returns this node.
   *
   * <p>Intended to be called only by the parser, right after the node is
   * created, on finding that parentheses enclose it.
   *
   * <p>It mutates rather than copying because copying needed one override per
   * node class, each calling that class's own constructor, and a class whose
   * override was missing threw rather than reparenthesizing. Fifteen were
   * missing. A node's position is the one part of it that is not settled when
   * it is built, so this is the part that gives.
   */
  public AstNode withPos(Pos pos) {
    this.pos = requireNonNull(pos);
    return this;
  }

  /**
   * Converts this node into an ML string.
   *
   * <p>The purpose of this string is debugging. If you want to generate an
   * expression, use {@link #unparse}, which will insert parentheses as
   * necessary for operator precedence.
   *
   * <p>Derived classes <em>may</em> override, but they must produce the same
   * result; so the only reason to override is if they can do it more
   * efficiently.
   */
  @Override
  public final String toString() {
    // Marked final because you should override unparse, not toString
    return unparse(new AstWriter());
  }

  /**
   * Converts this node into a string with fewer id ordinals; if {@code
   * withTypes}, a relational node prints the collection type of every line, as
   * {@code Sys.planEx} prints it.
   */
  public final String unparseRenumbered(
      TypeSystem typeSystem, int width, boolean withTypes) {
    final AstWriter w = renumberingWriter(typeSystem, this, width, withTypes);
    if (this instanceof Core.Rel) {
      // The root prints in place; `unparse` would break it out.
      w.describe((Core.Rel) this, 0);
    } else {
      unparse(w);
    }
    return finish(w);
  }

  /**
   * Completes a plan: the relations that were broken out, then the type legend.
   *
   * <p>The writer collects both, and whoever created the writer prints them: a
   * nested tree writes onto the caller's writer, so one definitions region and
   * one legend cover the whole text, however deep it nests.
   */
  static String finish(AstWriter w) {
    // A plan whose last line is a node ends with a newline; one that ends in
    // the middle of an expression does not. Terminate it either way, so that
    // the blank line before the first block is a blank line.
    if ((w.relDefCount() > 0 || !w.typeLegend().isEmpty())
        && !w.atLineStart()) {
      w.append("\n");
    }
    // The list grows while it is walked, because a block may itself hold a
    // relation that has to be broken out. Index, do not iterate.
    for (int i = 0; i < w.relDefCount(); i++) {
      final Core.Rel rel = w.relDef(i);
      w.append("\n").append(w.relHeader(i)).append(" =\n");
      w.describe(rel, 2);
    }
    return w + w.typeLegend();
  }

  /**
   * Returns a writer that renumbers the binders it prints.
   *
   * <p>One writer per plan, because the numbering is by first occurrence over
   * the whole text: two of them number the same binder differently, and a
   * binder printed by one and read by the other reads as two.
   */
  public static AstWriter renumberingWriter(
      TypeSystem typeSystem, AstNode root, int width, boolean withTypes) {
    return new RenumberingAstWriter(
        typeSystem, boundInPlan(root), width, withTypes);
  }

  /**
   * Returns the variables the plan itself binds.
   *
   * <p>A fragment declares what it reads from outside itself, but only what a
   * reader of the plan could not otherwise resolve: {@code scott} and {@code
   * pairs} are bound outside the whole plan, and every fragment would name
   * them.
   */
  private static Set<Core.NamedPat> boundInPlan(AstNode root) {
    final Set<Core.NamedPat> pats = new LinkedHashSet<>();
    root.accept(
        new Visitor() {
          @Override
          protected void visit(Core.IdPat idPat) {
            pats.add(idPat);
          }

          @Override
          protected void visitRel(Core.Rel rel) {
            // A node's patterns are bound in the plan too, and a fragment
            // that reads one names it in its header.
            pats.addAll(rel.patterns());
          }
        });
    return pats;
  }

  /** Converts this node into an ML string, with a given writer. */
  public final String unparse(AstWriter w) {
    return unparse(w, 0, 0).toString();
  }

  abstract AstWriter unparse(AstWriter w, int left, int right);

  /**
   * Accepts a shuttle, calling the {@link
   * net.hydromatic.morel.ast.Shuttle#visit} method appropriate to the type of
   * this node, and returning the result.
   */
  public abstract AstNode accept(Shuttle shuttle);

  /**
   * Accepts a visitor, calling the {@link
   * net.hydromatic.morel.ast.Shuttle#visit} method appropriate to the type of
   * this node, and returning the result.
   */
  public abstract void accept(Visitor visitor);

  /**
   * Implementation of {@link net.hydromatic.morel.ast.AstWriter} that remembers
   * occurrences of {@link Core.NamedPat}.
   */
  private static class RenumberingAstWriter extends AstWriter {
    final Map<String, List<Integer>> nameIds = new HashMap<>();
    final Map<String, String> names = new LinkedHashMap<>();

    /**
     * The types a plan line was too narrow to name, in the order they were
     * first encountered. A {@link LinkedHashMap} because that order is the
     * contract: the reference {@code t$0} means the first long type in the
     * text, whatever else the plan contains.
     */
    final Map<String, Integer> typeRefs = new LinkedHashMap<>();

    /**
     * The relations broken out of the expressions they appeared in, in the
     * order they were first referred to. A list compared by identity rather
     * than a set: two structurally equal subqueries are two subqueries, and
     * giving them one reference would say they are one.
     */
    final List<Core.Rel> relDefs = new ArrayList<>();

    /**
     * What each broken-out relation reads from outside itself, worked out when
     * the relation is first referred to.
     */
    final Map<Core.Rel, List<Core.NamedPat>> relParams =
        new IdentityHashMap<>();

    final TypeSystem typeSystem;

    /** What the plan binds; see {@code AstNode#boundInPlan(AstNode)}. */
    final Set<Core.NamedPat> boundInPlan;

    final int width;
    final boolean withTypes;

    RenumberingAstWriter(
        TypeSystem typeSystem,
        Set<Core.NamedPat> boundInPlan,
        int width,
        boolean withTypes) {
      this.typeSystem = requireNonNull(typeSystem);
      this.boundInPlan = ImmutableSet.copyOf(boundInPlan);
      this.width = width;
      this.withTypes = withTypes;
    }

    @Override
    protected int width() {
      return width;
    }

    @Override
    public boolean withTypes() {
      return withTypes;
    }

    @Override
    public boolean treeMode() {
      return true;
    }

    @Override
    public String relRef(Core.Rel rel) {
      for (int i = 0; i < relDefs.size(); i++) {
        if (relDefs.get(i) == rel) {
          return relHeader(i);
        }
      }
      relDefs.add(rel);
      return relHeader(relDefs.size() - 1);
    }

    @Override
    public String relHeader(int i) {
      final List<Core.NamedPat> params = params(relDefs.get(i));
      if (params.isEmpty()) {
        return "r$" + i;
      }
      // Renaming here is safe, and not merely convenient: a parameter is bound
      // by a `let` that encloses the reference, so it has been printed -- and
      // therefore numbered -- before this runs.
      final List<String> paramNames = new ArrayList<>();
      params.forEach(
          p ->
              paramNames.add(
                  p.name.charAt(0) == '$'
                      ? idName((Core.IdPat) p)
                      : rename(p.name)));
      paramNames.sort(Comparators::compareNumberedNames);
      return "r$" + i + "[" + String.join(", ", paramNames) + "]";
    }

    /**
     * Returns what a relation reads from outside itself.
     *
     * <p>Asked when the relation is first referred to, which is the only moment
     * it needs to be known and the only moment the writer knows the relation is
     * being broken out at all. Transitive without trying: a fragment nested
     * inside this one is part of its subtree, however far away it is printed.
     */
    private List<Core.NamedPat> params(Core.Rel rel) {
      return relParams.computeIfAbsent(
          rel,
          r -> {
            final Set<Core.NamedPat> free =
                new LinkedHashSet<>(r.freePats(typeSystem));
            free.retainAll(boundInPlan);
            return ImmutableList.copyOf(free);
          });
    }

    @Override
    public int relDefCount() {
      return relDefs.size();
    }

    @Override
    public Core.Rel relDef(int i) {
      return relDefs.get(i);
    }

    private int register(String name, int i) {
      final List<Integer> list =
          nameIds.computeIfAbsent(name, id_ -> new ArrayList<>());
      int j = list.indexOf(i);
      if (j < 0) {
        j = list.size();
        list.add(i);
      }
      return j;
    }

    /**
     * Renumbers a generated binder by first occurrence, so that a plan's text
     * does not depend on what was compiled before it.
     *
     * <p>Allocation stays free -- a binder takes the next number from whatever
     * counter its maker uses, and uniqueness is all that is asked of it -- and
     * determinism is a property of the text, so it belongs to the printer. This
     * is what {@link Core.Rel#describe} does for a tree's plan.
     */
    private String rename(String name) {
      if (!Core.NamedPat.isGenerated(name)) {
        return name;
      }
      final String existing = names.get(name);
      if (existing != null) {
        return existing;
      }
      // The prefix distinguishes what one maker generated from another's;
      // each is numbered in its own sequence.
      final String prefix = name.substring(0, name.indexOf('$') + 1);
      int n = 0;
      for (String s : names.values()) {
        if (s.startsWith(prefix)) {
          ++n;
        }
      }
      final String replacement = prefix + n;
      names.put(name, replacement);
      return replacement;
    }

    @Override
    protected String renameGenerated(String name) {
      return rename(name);
    }

    @Override
    public String typeRef(String moniker) {
      final String renamed = renameWithin(moniker);
      if (renamed.length() <= MAX_TYPE_LENGTH) {
        return renamed;
      }
      final Integer i = typeRefs.computeIfAbsent(renamed, m -> typeRefs.size());
      return "t$" + i;
    }

    /**
     * Renumbers the generated binders inside a type's text.
     *
     * <p>A type reaches the writer as text, so a binder in it never passes
     * through {@link #rename} on its own: a tree's projection names a component
     * after the binder it came from, and the component becomes a record field,
     * so the field's name is a binder's. It is renumbered here like the id that
     * reads it, so that the tree and the legend say the same name. A record
     * label, which is also printed as text, gets the same treatment in {@link
     * #append(String)}.
     */
    private String renameWithin(String moniker) {
      final Matcher matcher = Core.NamedPat.GENERATED.matcher(moniker);
      if (!matcher.find()) {
        return moniker;
      }
      // A StringBuffer, not a StringBuilder: the JDK 8 Matcher takes only
      // the former.
      final StringBuffer b = new StringBuffer();
      do {
        matcher.appendReplacement(
            b, Matcher.quoteReplacement(rename(matcher.group())));
      } while (matcher.find());
      matcher.appendTail(b);
      return b.toString();
    }

    @Override
    public String typeLegend() {
      if (typeRefs.isEmpty()) {
        return "";
      }
      final StringBuilder b = new StringBuilder("\n");
      typeRefs.forEach(
          (moniker, i) ->
              b.append("t$")
                  .append(i)
                  .append(' ')
                  .append(moniker)
                  .append('\n'));
      return b.toString();
    }

    @Override
    public AstWriter id(String name) {
      return super.id(rename(name));
    }

    @Override
    public AstWriter id(String name, int i) {
      return super.id(rename(name), register(name, i));
    }

    @Override
    public AstWriter idQuoted(String name) {
      return super.idQuoted(rename(name));
    }

    @Override
    public AstWriter selector(String label) {
      return super.selector(rename(label));
    }

    /**
     * Renumbers a record label, which a record writes as plain text rather than
     * as an identifier.
     *
     * <p>A tree's projection names its element's components after the binders
     * they came from, so a label is often a generated binder's name, and it
     * must be renumbered like the id that reads it. Otherwise the two disagree
     * and the plan reads as if the query bound one name and used another. Only
     * a string that is entirely a generated binder is renamed, and no
     * punctuation or keyword is.
     */
    @Override
    public AstWriter append(String s) {
      return super.append(rename(s));
    }

    @Override
    public AstWriter idQuoted(String name, int i) {
      return super.idQuoted(rename(name), register(name, i));
    }
  }
}

// End AstNode.java
