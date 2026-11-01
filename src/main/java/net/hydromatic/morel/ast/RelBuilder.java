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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.type.RecordType.ORDERING;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * Builds a relational tree ({@link Core.Rel}).
 *
 * <p>It does three things, and deliberately no more.
 *
 * <p>It keeps a <b>stack</b> of relational expressions. A node takes its inputs
 * from the stack and leaves its result there, so an n-ary set operator or a
 * two-input join is ordinary rather than special.
 *
 * <p>It keeps a <b>name map</b> per stack entry, so that a caller can say
 * {@code e} for an input's element and {@code deptno} for a field of it,
 * without writing {@code $0} or a record selector itself. The names of a node
 * are the field names of its element, plus whatever a leaf was pushed under; so
 * the map is derived rather than threaded.
 *
 * <p>It <b>simplifies</b>, under a set of {@link Simplification}. Each
 * simplification is named and can be switched off. With {@link
 * Simplification#none()} the builder is a pure constructor, which is a test
 * oracle: build a query twice, once simplified and once not, and compare what
 * the two produce.
 */
public class RelBuilder {
  private final TypeSystem typeSystem;
  private final ImmutableSet<Simplification> simps;
  private final Deque<Frame> stack = new ArrayDeque<>();

  /**
   * Counter for generated binders. Per builder, not per type system, so that
   * plan text does not depend on what was compiled before it.
   */
  private int nextName = 0;

  /** Position given to the nodes built next; see {@link #at}. */
  private Pos pos = Pos.ZERO;

  private RelBuilder(TypeSystem typeSystem, Set<Simplification> simps) {
    this.typeSystem = requireNonNull(typeSystem);
    this.simps = ImmutableSet.copyOf(simps);
  }

  /** Creates a builder that applies every simplification. */
  public static RelBuilder create(TypeSystem typeSystem) {
    return new RelBuilder(typeSystem, Simplification.all());
  }

  /** Creates a builder that applies only the given simplifications. */
  public static RelBuilder create(
      TypeSystem typeSystem, Set<Simplification> simps) {
    return new RelBuilder(typeSystem, simps);
  }

  /**
   * Rebuilds a tree through a builder, node by node.
   *
   * <p>With {@link Simplification#none()} the result must equal the input: that
   * is the assertion that the builder can express every tree there is, which is
   * what a caller has to be able to assume before it depends on the builder.
   * With other sets it is what those simplifications make of the tree.
   */
  public static Core.Exp rebuild(
      TypeSystem typeSystem, Core.Exp exp, Set<Simplification> simps) {
    final RelBuilder b = create(typeSystem, simps);
    b.rebuild(exp);
    return b.build();
  }

  /** Pushes the rebuilt form of one node, having rebuilt its inputs. */
  private void rebuild(Core.Exp exp) {
    rebuild(exp, null);
  }

  /** Rebuilds a node, giving a leaf an id named as the node above names it. */
  private void rebuild(Core.Exp exp, Core.@Nullable IdPat above) {
    if (!(exp instanceof Core.Rel)) {
      // The leaf's own id is not in the tree -- a leaf is just an
      // expression -- so it takes a fresh id of the name the node above
      // used.
      push(exp, above == null ? null : elementPatLike(exp, above));
      return;
    }
    rebuildRel((Core.Rel) exp);
    at(exp.pos);
  }

  /** Rebuilds a node; the node's own position is set afterwards. */
  private void rebuildRel(Core.Rel exp) {
    if (exp instanceof Core.Filter) {
      final Core.Filter filter = (Core.Filter) exp;
      rebuild(filter.input, filter.id);
      filter(repattern(filter, filter.condition));
    } else if (exp instanceof Core.Project) {
      final Core.Project project = (Core.Project) exp;
      rebuild(project.input, project.id);
      project(repattern(project, project.exp));
    } else if (exp instanceof Core.Join) {
      final Core.Join join = (Core.Join) exp;
      rebuild(join.left, join.leftId);
      // The right input may read the left id; it reads the new one.
      final Core.IdPat leftId = frame(0).id;
      rebuild(
          core.substitute(typeSystem, join.right, join.leftId, core.id(leftId)),
          join.rightId);
      pair();
      final Core.IdPat rightId = rightId(frame(1));
      Core.Exp condition =
          core.substitute(
              typeSystem, join.condition, join.leftId, core.id(leftId));
      condition =
          core.substitute(
              typeSystem, condition, join.rightId, core.id(rightId));
      if (join.ordinalId != null) {
        condition =
            core.substitute(typeSystem, condition, join.ordinalId, ordinal());
      }
      join(join.joinType, condition);
    } else if (exp instanceof Core.Group) {
      final Core.Group group = (Core.Group) exp;
      rebuild(group.input);
      final SortedMap<String, Core.Exp> keys = new TreeMap<>(ORDERING);
      group.keys.forEach((name, key) -> keys.put(name, repattern(group, key)));
      final SortedMap<String, Core.Aggregate> aggregates =
          new TreeMap<>(ORDERING);
      group.aggregates.forEach(
          (name, agg) ->
              aggregates.put(
                  name,
                  core.aggregate(
                      agg.pos,
                      agg.type,
                      agg.aggregate,
                      agg.argument == null
                          ? null
                          : repattern(group, agg.argument))));
      group(keys, aggregates);
    } else if (exp instanceof Core.Sort) {
      final Core.Sort sort = (Core.Sort) exp;
      rebuild(sort.input);
      sort(repattern(sort, sort.exp));
    } else if (exp instanceof Core.Unorder) {
      rebuild(((Core.Unorder) exp).input);
      unorder();
    } else if (exp instanceof Core.Skip) {
      final Core.Skip skip = (Core.Skip) exp;
      rebuild(skip.input);
      skip(skip.count);
    } else if (exp instanceof Core.Take) {
      final Core.Take take = (Core.Take) exp;
      rebuild(take.input);
      take(take.count);
    } else if (exp instanceof Core.Union) {
      final Core.Union union = (Core.Union) exp;
      union.inputs.forEach(this::rebuild);
      union(union.inputs.size(), union.distinct);
    } else if (exp instanceof Core.Intersect) {
      final Core.Intersect intersect = (Core.Intersect) exp;
      intersect.inputs.forEach(this::rebuild);
      intersect(intersect.inputs.size(), intersect.distinct);
    } else if (exp instanceof Core.Except) {
      final Core.Except except = (Core.Except) exp;
      except.inputs.forEach(this::rebuild);
      except(except.inputs.size(), except.distinct);
    } else {
      throw new AssertionError("unknown node " + exp.op);
    }
  }

  /**
   * Rewrites an expression of a one-input node to be over the patterns of the
   * frame on top of the stack, which the node built next will bind.
   */
  private Core.Exp repattern(Core.RowRel rel, Core.Exp exp) {
    final Frame frame = frame(0);
    Core.Exp exp2 = core.substitute(typeSystem, exp, rel.id, core.id(frame.id));
    if (rel.ordinalId != null) {
      exp2 = core.substitute(typeSystem, exp2, rel.ordinalId, ordinal());
    }
    return exp2;
  }

  /** Returns whether a simplification is enabled. */
  private boolean on(Simplification simp) {
    return simps.contains(simp);
  }

  /**
   * Sets the position that the nodes built from now on carry: the position of
   * the expression the caller is converting.
   */
  public RelBuilder at(Pos pos) {
    this.pos = requireNonNull(pos);
    return this;
  }

  // Stack

  /** Pushes a relational expression, whose element has no name of its own. */
  public RelBuilder push(Core.Exp rel) {
    return push(rel, null);
  }

  /**
   * Pushes a relational expression, over a given id if there is one.
   *
   * <p>Rebuilding a tree uses this, so that a leaf's id keeps the name the node
   * above it gave it.
   */
  private RelBuilder push(Core.Exp rel, Core.@Nullable IdPat idPat) {
    final Core.IdPat id = idPat == null ? elementPat(rel) : idPat;
    stack.push(new Frame(rel, id, elementNames(rel, id, ImmutableMap.of())));
    return this;
  }

  /**
   * Pushes a relational expression and names its element, as {@code from e in
   * emps} names the element {@code e}.
   */
  public RelBuilder push(String name, Core.Exp rel) {
    final Core.IdPat id = elementPat(rel, name);
    stack.push(
        new Frame(
            rel,
            id,
            elementNames(rel, id, ImmutableMap.of(name, core.id(id)))));
    return this;
  }

  /**
   * Pushes a relational expression and names its element by a pattern, as
   * {@code from (a, b) in pairs} names the two components.
   *
   * <p>The pattern is erased: what survives is one name per binder, each mapped
   * to the path that reads it out of the element. That is what a tree has
   * instead of a pattern. A pattern that also filters -- a literal, or a tuple
   * that holds one -- becomes a filter above the collection, with the names
   * bound over the filter; a constructor pattern, whose test is a {@code case},
   * is refused. Use {@link #destructurable} and {@link #testable} to ask first.
   */
  public RelBuilder push(Core.Pat pat, Core.Exp rel) {
    final Map<String, Core.Exp> names = new LinkedHashMap<>();
    // A bare name lends the id its name; anything else is erased, and the
    // id has no name to borrow.
    final Core.IdPat id =
        pat instanceof Core.IdPat
            ? elementPat(rel, ((Core.IdPat) pat).name)
            : elementPat(rel);
    final Core.Exp element = core.id(id);
    if (!destructure(pat, element, names)) {
      throw new IllegalArgumentException(
          "pattern cannot be destructured, because it can fail to match: "
              + pat);
    }
    // A pattern that can fail to match filters as well as binds, and the two
    // halves are separate nodes: the filter here, the binding in the names.
    final Core.@Nullable Exp test = test(typeSystem, pat, element);
    if (test == null) {
      stack.push(new Frame(rel, id, elementNames(rel, id, names)));
      return this;
    }
    final Core.Exp rel2 = core.filter(pos, id, null, rel, test);
    // The filter binds the id the names were written over; the node above
    // the filter binds an id of its own, so the names move onto it.
    final Core.IdPat id2 = elementPat(rel2);
    final Map<String, Core.Exp> names2 = new LinkedHashMap<>();
    names.forEach(
        (name, exp) ->
            names2.put(
                name, core.substitute(typeSystem, exp, id, core.id(id2))));
    stack.push(new Frame(rel2, id2, elementNames(rel2, id2, names2)));
    return this;
  }

  /** Returns whether a pattern binds names without also filtering. */
  public static boolean destructurable(Core.Pat pat) {
    switch (pat.op) {
      case ID_PAT:
      case WILDCARD_PAT:
        return true;
      case AS_PAT:
        return destructurable(((Core.AsPat) pat).pat);
      case TUPLE_PAT:
      case RECORD_PAT:
        for (Core.Pat arg : args(pat)) {
          if (!destructurable(arg)) {
            return false;
          }
        }
        return true;
      default:
        return false;
    }
  }

  /**
   * Returns whether {@link #test} can express a pattern's condition, and {@link
   * #destructure} its bindings, as expressions over the element.
   */
  public static boolean testable(Core.Pat pat) {
    switch (pat.op) {
      case AS_PAT:
        // The name matches whatever the pattern it wraps matches.
        return testable(((Core.AsPat) pat).pat);
      case ID_PAT:
      case WILDCARD_PAT:
      case BOOL_LITERAL_PAT:
      case CHAR_LITERAL_PAT:
      case INT_LITERAL_PAT:
      case REAL_LITERAL_PAT:
      case STRING_LITERAL_PAT:
      case WORD_LITERAL_PAT:
        return true;
      case TUPLE_PAT:
        return ((Core.TuplePat) pat)
            .args.stream().allMatch(RelBuilder::testable);
      case RECORD_PAT:
        return ((Core.RecordPat) pat)
            .args.stream().allMatch(RelBuilder::testable);
      case CONS_PAT:
        // `::` is a constructor, but the list datatype has total accessors --
        // `null`, `hd`, `tl` -- where a user datatype has none.
        return ((Core.ConPat) pat).pat.op == Op.TUPLE_PAT
            && testable(((Core.ConPat) pat).pat);
      case LIST_PAT:
        return ((Core.ListPat) pat)
            .args.stream().allMatch(RelBuilder::testable);
      default:
        return false;
    }
  }

  /**
   * Returns the condition under which a pattern matches an element, or null if
   * it always matches.
   *
   * <p>A pattern that can fail to match filters as well as binds, and the two
   * halves separate: this is the filter, and {@link #destructure} is the
   * binding. A scan is then a leaf with a filter above it and, where the
   * bindings do not describe the element, a projection above that -- three
   * ordinary nodes, each of which a rule can see through, rather than one node
   * holding a {@code case} that yields a collection.
   *
   * <p>Callers must first ask {@link #testable}. A constructor pattern is not
   * testable here, because extracting what it binds needs a {@code case} of its
   * own: a datatype has no total accessor for a constructor's argument, and no
   * value to give the branch that does not match.
   */
  public static Core.@Nullable Exp test(
      TypeSystem typeSystem, Core.Pat pat, Core.Exp element) {
    switch (pat.op) {
      case ID_PAT:
      case WILDCARD_PAT:
        return null;

      case AS_PAT:
        return test(typeSystem, ((Core.AsPat) pat).pat, element);

      case BOOL_LITERAL_PAT:
      case CHAR_LITERAL_PAT:
      case INT_LITERAL_PAT:
      case REAL_LITERAL_PAT:
      case STRING_LITERAL_PAT:
      case WORD_LITERAL_PAT:
        final Core.LiteralPat literalPat = (Core.LiteralPat) pat;
        return core.equal(
            typeSystem,
            element,
            core.literal((PrimitiveType) pat.type, literalPat.value));

      case TUPLE_PAT:
      case RECORD_PAT:
        final List<Core.Pat> args =
            pat.op == Op.TUPLE_PAT
                ? ((Core.TuplePat) pat).args
                : ((Core.RecordPat) pat).args;
        final List<Core.Exp> tests = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
          final Core.@Nullable Exp test =
              test(typeSystem, args.get(i), core.field(typeSystem, element, i));
          if (test != null) {
            tests.add(test);
          }
        }
        return tests.isEmpty() ? null : core.andAlso(typeSystem, tests);

      case CONS_PAT:
        // A non-empty list, whose head and tail must match in turn.
        final Core.TuplePat consPat = (Core.TuplePat) ((Core.ConPat) pat).pat;
        final List<Core.Exp> consTests = new ArrayList<>();
        consTests.add(core.not(typeSystem, isNull(typeSystem, element)));
        addTest(
            typeSystem,
            consTests,
            consPat.args.get(0),
            hd(typeSystem, element));
        addTest(
            typeSystem,
            consTests,
            consPat.args.get(1),
            tl(typeSystem, element));
        return core.andAlso(typeSystem, consTests);

      case LIST_PAT:
        // A list of exactly this length, whose items must match in turn.
        final List<Core.Pat> listItems = ((Core.ListPat) pat).args;
        if (listItems.isEmpty()) {
          return isNull(typeSystem, element);
        }
        final List<Core.Exp> listTests = new ArrayList<>();
        listTests.add(
            core.equal(
                typeSystem,
                length(typeSystem, element),
                core.literal(PrimitiveType.INT, listItems.size())));
        for (int i = 0; i < listItems.size(); i++) {
          addTest(
              typeSystem,
              listTests,
              listItems.get(i),
              nth(typeSystem, element, i));
        }
        return core.andAlso(typeSystem, listTests);

      default:
        throw new AssertionError("not testable: " + pat);
    }
  }

  /** Adds a pattern's test to a list, if it has one. */
  private static void addTest(
      TypeSystem typeSystem,
      List<Core.Exp> tests,
      Core.Pat pat,
      Core.Exp element) {
    final Core.@Nullable Exp test = test(typeSystem, pat, element);
    if (test != null) {
      tests.add(test);
    }
  }

  /** Applies a one-argument list built-in to a list. */
  private static Core.Exp listCall(
      TypeSystem typeSystem, BuiltIn builtIn, Core.Exp list, Type resultType) {
    return core.apply(
        Pos.ZERO,
        resultType,
        core.functionLiteral(typeSystem.fnType(list.type, resultType), builtIn),
        list);
  }

  private static Core.Exp isNull(TypeSystem typeSystem, Core.Exp list) {
    return listCall(typeSystem, BuiltIn.LIST_NULL, list, PrimitiveType.BOOL);
  }

  private static Core.Exp length(TypeSystem typeSystem, Core.Exp list) {
    return listCall(typeSystem, BuiltIn.LIST_LENGTH, list, PrimitiveType.INT);
  }

  private static Core.Exp hd(TypeSystem typeSystem, Core.Exp list) {
    return listCall(typeSystem, BuiltIn.LIST_HD, list, list.type.elementType());
  }

  private static Core.Exp tl(TypeSystem typeSystem, Core.Exp list) {
    return listCall(typeSystem, BuiltIn.LIST_TL, list, list.type);
  }

  private static Core.Exp nth(TypeSystem typeSystem, Core.Exp list, int i) {
    final Type elementType = list.type.elementType();
    final Type fnType =
        typeSystem.fnType(
            typeSystem.tupleType(list.type, PrimitiveType.INT), elementType);
    return core.apply(
        Pos.ZERO,
        elementType,
        core.functionLiteral(fnType, BuiltIn.LIST_NTH),
        core.tuple(
            typeSystem,
            null,
            ImmutableList.of(list, core.literal(PrimitiveType.INT, i))));
  }

  /** Returns the components of a tuple or record pattern. */
  private static List<Core.Pat> args(Core.Pat pat) {
    return pat.op == Op.TUPLE_PAT
        ? ((Core.TuplePat) pat).args
        : ((Core.RecordPat) pat).args;
  }

  private boolean destructure(
      Core.Pat pat, Core.Exp element, Map<String, Core.Exp> names) {
    switch (pat.op) {
      case ID_PAT:
        names.put(((Core.IdPat) pat).name, element);
        return true;
      case WILDCARD_PAT:
        return true;
      case AS_PAT:
        // `p as (a, b)` names the whole value and its parts, and in a tree
        // both are paths to the same element -- so, unlike a pattern that
        // reaches Core as a value to be taken apart, there is nothing here
        // that makes the two dependent.
        final Core.AsPat asPat = (Core.AsPat) pat;
        names.put(asPat.name, element);
        return destructure(asPat.pat, element, names);
      case TUPLE_PAT:
      case RECORD_PAT:
        final List<Core.Pat> args = args(pat);
        for (int i = 0; i < args.size(); i++) {
          if (!destructure(
              args.get(i), core.field(typeSystem, element, i), names)) {
            return false;
          }
        }
        return true;

      case BOOL_LITERAL_PAT:
      case CHAR_LITERAL_PAT:
      case INT_LITERAL_PAT:
      case REAL_LITERAL_PAT:
      case STRING_LITERAL_PAT:
      case WORD_LITERAL_PAT:
        // A literal binds nothing; it filters, and `test` says how.
        return true;

      case CONS_PAT:
        // `h :: t` binds the head and the tail, which `hd` and `tl` reach.
        final Core.TuplePat headTail = (Core.TuplePat) ((Core.ConPat) pat).pat;
        return destructure(headTail.args.get(0), hd(typeSystem, element), names)
            && destructure(
                headTail.args.get(1), tl(typeSystem, element), names);

      case LIST_PAT:
        final List<Core.Pat> items = ((Core.ListPat) pat).args;
        for (int i = 0; i < items.size(); i++) {
          if (!destructure(items.get(i), nth(typeSystem, element, i), names)) {
            return false;
          }
        }
        return true;

      default:
        // A user datatype's constructor also filters, but extracting what it
        // binds has no total expression -- see `test`.
        return false;
    }
  }

  /** Returns how many expressions are on the stack. */
  public int size() {
    return stack.size();
  }

  /** Returns the expression on top of the stack, without popping it. */
  public Core.Exp peek() {
    return frame(0).rel;
  }

  /**
   * Returns the finished expression, which must be the only one on the stack.
   */
  public Core.Exp build() {
    checkArgument(stack.size() == 1, "expected one expression, got %s", stack);
    return stack.pop().rel;
  }

  // Names

  /**
   * Returns the expression that a name denotes, over the element of the {@code
   * i}th input, counting from the deepest of the inputs a two-input node will
   * take. For a one-input node {@code i} is 0 and the expression is over {@code
   * $0}; for a join, input 0 is the left and input 1 the right.
   */
  public Core.Exp name(int i, String name) {
    final Core.Exp exp = frame(i).names.get(name);
    if (exp == null) {
      throw new IllegalArgumentException(
          "no name '" + name + "' among " + frame(i).names.keySet());
    }
    return rebase(exp, i);
  }

  /** Returns the expression that a name denotes in the top input. */
  public Core.Exp name(String name) {
    return name(0, name);
  }

  /** Returns a field of the {@code i}th input's element, {@code #f $i}. */
  private Core.Exp field(int i, String fieldName) {
    return field(input(i), fieldName);
  }

  /** Returns a field of the top input's element, {@code #f $0}. */
  public Core.Exp field(String fieldName) {
    return field(0, fieldName);
  }

  /** Returns a field of what a name denotes, as {@code e.deptno} does. */
  public Core.Exp field(int i, String name, String fieldName) {
    return field(name(i, name), fieldName);
  }

  /** Returns a reference to the element of the {@code i}th input. */
  public Core.Exp input(int i) {
    final Frame frame = frame(i);
    return core.id(i == 0 ? frame.id : rightId(frame));
  }

  /**
   * Returns a reference to the ordinal of the top input's element, which the
   * node built next will bind.
   */
  public Core.Exp ordinal() {
    final Frame frame = frame(0);
    if (frame.ordinalId == null) {
      frame.ordinalId = core.ordinalPat(typeSystem.nameGenerator::inc);
    }
    return core.id(frame.ordinalId);
  }

  /** Returns a field of an expression, by name. */
  public Core.Exp field(Core.Exp exp, String fieldName) {
    final RecordLikeType recordType = (RecordLikeType) exp.type;
    final int slot =
        new ArrayList<>(recordType.argNameTypes().keySet()).indexOf(fieldName);
    checkArgument(
        slot >= 0, "no field '%s' in %s", fieldName, exp.type.moniker());
    return core.field(typeSystem, exp, slot);
  }

  /**
   * Rewrites an expression over {@code $0} to be over {@code $i}, which is what
   * a join's right input needs.
   */
  private Core.Exp rebase(Core.Exp exp, int i) {
    if (i == 0) {
      return exp;
    }
    final Frame frame = frame(i);
    return core.substitute(typeSystem, exp, frame.id, core.id(rightId(frame)));
  }

  /**
   * Returns the id by which a join refers to the current element of a frame as
   * its right input, named as the frame's id is: the right id of {@code from e
   * in emps, d in depts} is {@code d}, as the left id is {@code e}.
   */
  private Core.IdPat rightId(Frame frame) {
    if (frame.rightId == null) {
      final @Nullable String name = Core.NamedPat.idName(frame.id.name);
      frame.rightId =
          name == null
              ? core.rightElementPat(
                  frame.rel.type.elementType(), typeSystem.nameGenerator::inc)
              : elementPat(frame.rel, name);
    }
    return frame.rightId;
  }

  /**
   * Returns the frame's ordinal pattern if any of the expressions reads it,
   * otherwise null: a node binds an ordinal exactly where its expressions read
   * one, so that two trees that mean the same thing print the same.
   */
  private static Core.@Nullable IdPat ordinalIfRead(
      Frame frame, Core.Exp... exps) {
    if (frame.ordinalId != null) {
      for (Core.Exp exp : exps) {
        if (Core.mentions(exp, frame.ordinalId)) {
          return frame.ordinalId;
        }
      }
    }
    return null;
  }

  /** Mints the pattern that names an expression's element. */
  private Core.IdPat elementPat(Core.Exp rel) {
    return core.elementPat(
        rel.type.elementType(), typeSystem.nameGenerator::inc);
  }

  /**
   * Returns the id by which a node refers to a collection's current element,
   * called after the name the query gave it.
   *
   * <p>An id that prints as {@code e} rather than {@code $0} makes a plan
   * easier to read, and gives a translator to another system a name to use:
   * Spark calls it a subquery alias, and {@code $0} is not a name Spark would
   * accept.
   */
  private Core.IdPat elementPat(Core.Exp rel, String name) {
    return core.elementPat(
        rel.type.elementType(), name, typeSystem.nameGenerator::inc);
  }

  /** Returns a fresh id for a collection's element, named as another id is. */
  private Core.IdPat elementPatLike(Core.Exp rel, Core.IdPat id) {
    final @Nullable String name = Core.NamedPat.idName(id.name);
    return name == null ? elementPat(rel) : elementPat(rel, name);
  }

  /**
   * Returns the frame for a node built on another frame whose element is the
   * same, so that it offers the same names, over an id of its own.
   */
  private Frame above(Frame frame, Core.Exp rel) {
    // The element is the input's, so the id keeps the input's name; a node
    // above a collection called `e` still calls its id `e`.
    final Core.IdPat id = elementPatLike(rel, frame.id);
    final ImmutableMap.Builder<String, Core.Exp> b = ImmutableMap.builder();
    frame.names.forEach(
        (name, exp) ->
            b.put(
                name, core.substitute(typeSystem, exp, frame.id, core.id(id))));
    return new Frame(rel, id, b.build());
  }

  /**
   * Returns the names an expression's element offers: one per field if it is a
   * record, plus any the caller supplied for the element itself.
   */
  private ImmutableMap<String, Core.Exp> elementNames(
      Core.Exp rel, Core.IdPat id, Map<String, Core.Exp> extra) {
    final ImmutableMap.Builder<String, Core.Exp> b = ImmutableMap.builder();
    b.putAll(extra);
    final Type elementType = rel.type.elementType();
    if (elementType instanceof RecordLikeType) {
      final RecordLikeType recordType = (RecordLikeType) elementType;
      final Core.Exp element = core.id(id);
      int slot = 0;
      for (String fieldName : recordType.argNameTypes().keySet()) {
        if (!extra.containsKey(fieldName)) {
          b.put(fieldName, core.field(typeSystem, element, slot));
        }
        ++slot;
      }
    }
    return b.build();
  }

  /**
   * Returns the {@code i}th input of the node being built, counting from the
   * deepest; {@code frame(0)} is the top of the stack for a one-input node, and
   * for a join {@code frame(0)} is the left because it was pushed first.
   */
  private Frame frame(int i) {
    checkArgument(i >= 0 && i < stack.size(), "no input %s", i);
    // A two-input node takes the top two, left deeper than right, so counting
    // from the deepest means counting down from the top.
    int j = arity() - 1 - i;
    for (Frame frame : stack) {
      if (j-- == 0) {
        return frame;
      }
    }
    throw new AssertionError();
  }

  /**
   * How many of the stack's entries the names currently address. Two while a
   * join's expressions are being written, one otherwise.
   */
  private int arity = 1;

  private int arity() {
    return arity;
  }

  /**
   * Declares that the next expressions are written over two inputs, so that
   * {@link #name(int, String)} counts {@code 0} as the left and {@code 1} as
   * the right. Used while building a join's condition.
   */
  public RelBuilder pair() {
    checkArgument(stack.size() >= 2, "need two inputs");
    arity = 2;
    return this;
  }

  // Nodes

  /** Filters the top of the stack. */
  public RelBuilder filter(Core.Exp condition) {
    final Frame frame = pop();
    if (on(Simplification.FILTER_TRUE) && condition.isBoolLiteral(true)) {
      return push(frame);
    }
    final Core.@Nullable IdPat ordinal = ordinalIfRead(frame, condition);
    if (on(Simplification.FILTER_MERGE)
        && frame.rel instanceof Core.Filter
        && ordinal == null) {
      // A condition that reads the ordinal counts the elements the inner filter
      // let through, which the merged filter does not have, so it stays a
      // filter of its own.
      final Core.Filter filter = (Core.Filter) frame.rel;
      final Core.Exp condition2 =
          core.substitute(typeSystem, condition, frame.id, core.id(filter.id));
      return push(
          above(
              frame,
              core.filter(
                  pos,
                  filter.id,
                  filter.ordinalId,
                  filter.input,
                  core.andAlso(typeSystem, filter.condition, condition2))));
    }
    return push(
        above(
            frame, core.filter(pos, frame.id, ordinal, frame.rel, condition)));
  }

  /** Projects the top of the stack; {@code exp} is over {@code $0}. */
  public RelBuilder project(Core.Exp exp) {
    final Frame frame = pop();
    final Core.@Nullable IdPat frameOrdinal = ordinalIfRead(frame, exp);
    if (on(Simplification.PROJECT_IDENTITY)
        && isId(exp, frame.id)
        && frameOrdinal == null) {
      return push(frame);
    }
    if (on(Simplification.PROJECT_MERGE) && frame.rel instanceof Core.Project) {
      final Core.Project project = (Core.Project) frame.rel;
      // A projection keeps its input's positions, so an ordinal the outer
      // expression reads is the inner projection's input's.
      Core.@Nullable IdPat ordinal = project.ordinalId;
      Core.Exp exp2 = exp;
      if (frameOrdinal != null) {
        if (ordinal == null) {
          ordinal = frameOrdinal;
        } else {
          exp2 =
              core.substitute(typeSystem, exp2, frameOrdinal, core.id(ordinal));
        }
      }
      return push(
          core.project(
              pos,
              typeSystem,
              project.id,
              ordinal,
              project.input,
              merge(exp2, frame.id, project.exp)));
    }
    return push(
        core.project(pos, typeSystem, frame.id, frameOrdinal, frame.rel, exp));
  }

  /** Sorts the top of the stack; the result is a list. */
  public RelBuilder sort(Core.Exp exp) {
    final Frame frame = pop();
    return push(
        above(
            frame,
            core.sort(
                pos,
                typeSystem,
                frame.id,
                ordinalIfRead(frame, exp),
                frame.rel,
                exp)));
  }

  /** Discards the ordering of the top of the stack; the result is a bag. */
  public RelBuilder unorder() {
    final Frame frame = pop();
    if (on(Simplification.UNORDER_UNORDERED) && !isOrdered(frame.rel)) {
      return push(frame);
    }
    return push(above(frame, core.unorder(pos, typeSystem, frame.rel)));
  }

  /** Skips elements of the top of the stack. */
  public RelBuilder skip(Core.Exp count) {
    final Frame frame = pop();
    if (on(Simplification.SKIP_ZERO) && isIntLiteral(count, 0)) {
      return push(frame);
    }
    return push(above(frame, core.skip(pos, frame.rel, count)));
  }

  /** Takes elements of the top of the stack. */
  public RelBuilder take(Core.Exp count) {
    final Frame frame = pop();
    return push(above(frame, core.take(pos, frame.rel, count)));
  }

  /** Groups the top of the stack. */
  public RelBuilder group(
      SortedMap<String, Core.Exp> keys,
      SortedMap<String, Core.Aggregate> aggregates) {
    final Frame frame = pop();
    final List<Core.Exp> exps = new ArrayList<>(keys.values());
    aggregates
        .values()
        .forEach(
            agg -> {
              exps.add(agg.aggregate);
              if (agg.argument != null) {
                exps.add(agg.argument);
              }
            });
    final Core.Exp rel =
        core.group(
            pos,
            typeSystem,
            frame.id,
            ordinalIfRead(frame, exps.toArray(new Core.Exp[0])),
            frame.rel,
            keys,
            aggregates);
    // A group's element is a record of its labels, whether there is one label
    // or many, so the names come off its fields like any other node's.
    return push(rel);
  }

  /**
   * Projects the top of the stack, naming the element.
   *
   * <p>An atomizing yield -- {@code yield e.deptno} -- makes the element a bare
   * value, which has no fields to take names from, but the query still refers
   * to it by the name the yield gave it.
   */
  public RelBuilder project(String name, Core.Exp exp) {
    project(exp);
    final Frame frame = pop();
    return push(
        new Frame(
            frame.rel,
            frame.id,
            elementNames(
                frame.rel,
                frame.id,
                ImmutableMap.of(name, core.id(frame.id)))));
  }

  /**
   * Joins the top two of the stack, the deeper being the left. The condition is
   * over {@code $0} and {@code $1}; the element is the inputs' components
   * concatenated.
   */
  public RelBuilder join(JoinType joinType, Core.Exp condition) {
    final Frame right = pop();
    final Frame left = pop();
    arity = 1;
    final Core.Exp rel =
        core.join(
            pos,
            typeSystem,
            joinType,
            left.id,
            rightId(right),
            ordinalIfRead(left, condition, right.rel),
            left.rel,
            right.rel,
            condition);
    final Core.IdPat id = elementPat(rel);
    return push(new Frame(rel, id, joinNames(rel, id, left, right)));
  }

  /**
   * Returns the names of a join, by rebasing its inputs' onto the element it
   * concatenates them into.
   *
   * <p>This is what lets a caller keep saying {@code e} after two joins: the
   * element of {@code join(join(emps, depts), salgrades)} is three components,
   * and the map says which of them each name is. Without it the names would be
   * the element's field names, which for a tuple are {@code 1}, {@code 2} and
   * {@code 3} -- true, and useless to anything that started from a query.
   */
  private ImmutableMap<String, Core.Exp> joinNames(
      Core.Exp rel, Core.IdPat id, Frame left, Frame right) {
    final JoinType joinType = ((Core.Join) rel).joinType;
    final Core.Exp element = core.id(id);
    final Map<String, Core.Exp> names = new LinkedHashMap<>();
    rebaseInto(names, left, 0, element, joinType.leftIsOption());
    rebaseInto(
        names,
        right,
        core.componentCount(left.rel),
        element,
        joinType.rightIsOption());
    return ImmutableMap.copyOf(names);
  }

  /**
   * Rebases one input's names onto the join's element: an input with one
   * component is the whole of a position, and one that is itself a flattened
   * join has several, whose indexes shift by the offset.
   */
  private void rebaseInto(
      Map<String, Core.Exp> names,
      Frame frame,
      int offset,
      Core.Exp element,
      boolean absent) {
    final int n = core.componentCount(frame.rel);
    frame.names.forEach(
        (name, a) -> {
          if (n != 1) {
            names.put(name, shift(a, frame.id, offset, element));
            return;
          }
          final Core.Exp component = core.field(typeSystem, element, offset);
          names.put(
              name,
              absent
                  ? optionize(a, frame.id, component)
                  : core.substitute(typeSystem, a, frame.id, component));
        });
  }

  /**
   * Re-expresses a name's path into a component as a path into an option of
   * that component, for the side an outer join can leave absent.
   *
   * <p>Where the name is the whole component, the option-typed component is the
   * path. Otherwise the path maps through the option, because Morel makes each
   * *binder* of the absent side an option and not the side as a whole: {@code
   * left join (j, k) in pairs} binds {@code j : int option} and {@code k : int
   * option}, not {@code (int * int) option}.
   */
  private Core.Exp optionize(
      Core.Exp access, Core.IdPat id, Core.Exp component) {
    if (isId(access, id)) {
      return component;
    }
    final Core.IdPat param = core.idPat(id.type, typeSystem.nameGenerator::get);
    final Core.Exp body =
        core.substitute(typeSystem, access, id, core.id(param));
    final Core.Fn fn =
        core.fn(typeSystem.fnType(id.type, body.type), param, body);
    final Type optionType = typeSystem.option(body.type);
    return core.apply(
        Pos.ZERO,
        optionType,
        core.apply(
            Pos.ZERO,
            typeSystem.fnType(component.type, optionType),
            core.functionLiteral(typeSystem, BuiltIn.OPTION_MAP),
            fn),
        component);
  }

  /** Rewrites {@code #j $0} to {@code #(offset + j) $0}. */
  private Core.Exp shift(
      Core.Exp exp, Core.IdPat id, int offset, Core.Exp element) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Apply apply) {
            if (apply.fn instanceof Core.RecordSelector
                && isId(apply.arg, id)) {
              return core.field(
                  typeSystem,
                  element,
                  offset + ((Core.RecordSelector) apply.fn).slot);
            }
            return super.visit(apply);
          }
        });
  }

  /**
   * Returns a binder that names the top input's element, for the right input of
   * a dependent join. The right input is built after this, and reads the binder
   * where it needs the left element.
   */
  public Core.IdPat binder() {
    return frame(0).id;
  }

  /** Combines the top {@code n} of the stack with a set operator. */
  public RelBuilder union(int n, boolean distinct) {
    return setRel(n, inputs -> core.union(pos, typeSystem, distinct, inputs));
  }

  /** Intersects the top {@code n} of the stack. */
  public RelBuilder intersect(int n, boolean distinct) {
    return setRel(
        n, inputs -> core.intersect(pos, typeSystem, distinct, inputs));
  }

  /** Subtracts the top {@code n - 1} of the stack from the one below them. */
  public RelBuilder except(int n, boolean distinct) {
    return setRel(n, inputs -> core.except(pos, typeSystem, distinct, inputs));
  }

  private RelBuilder setRel(int n, Function<List<Core.Exp>, Core.Exp> f) {
    checkArgument(n >= 2, "a set operator needs at least two inputs");
    final List<Core.Exp> inputs = new ArrayList<>();
    Frame first = null;
    for (int i = 0; i < n; i++) {
      final Frame frame = pop();
      inputs.add(0, frame.rel);
      first = frame;
    }
    return push(above(requireNonNull(first), f.apply(inputs)));
  }

  // Helpers

  private Frame pop() {
    checkArgument(!stack.isEmpty(), "stack is empty");
    arity = 1;
    return stack.pop();
  }

  private RelBuilder push(Frame frame) {
    stack.push(frame);
    return this;
  }

  private boolean isOrdered(Core.Exp rel) {
    return rel.type instanceof ListType;
  }

  private static boolean isId(Core.Exp exp, Core.IdPat id) {
    return exp instanceof Core.Id && ((Core.Id) exp).idPat.equals(id);
  }

  private static boolean isIntLiteral(Core.Exp exp, int value) {
    return exp.op == Op.INT_LITERAL
        && ((Core.Literal) exp)
                .unwrap(BigDecimal.class)
                .compareTo(BigDecimal.valueOf(value))
            == 0;
  }

  /**
   * Substitutes the inner projection's expression into the outer's, binding it
   * to a variable first if the outer reads it more than once.
   *
   * <p>Merging two projections is substitution, and substitution duplicates:
   * {@code project [$0 + $0]} over {@code project [f $0]} would call {@code f}
   * twice per element where the two nodes called it once. A {@code let} keeps
   * the one evaluation that the two nodes had, so the merge is a simplification
   * of the plan and never a pessimization of it.
   */
  private Core.Exp merge(Core.Exp outer, Core.IdPat id, Core.Exp inner) {
    if (count(outer, id) <= 1) {
      return core.substitute(typeSystem, outer, id, inner);
    }
    final Core.IdPat pat =
        core.idPat(inner.type, typeSystem.nameGenerator.getPrefixed("v"), 0);
    return core.let(
        core.nonRecValDecl(inner.pos, pat, null, inner),
        core.substitute(typeSystem, outer, id, core.id(pat)));
  }

  /** Returns how many times an expression reads {@code $0}. */
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

  /** An expression on the stack, and the names its element offers. */
  private static class Frame {
    final Core.Exp rel;
    /** Pattern that the node built on this frame binds for its element. */
    final Core.IdPat id;

    final ImmutableMap<String, Core.Exp> names;
    /** Pattern for the element as a join's right input; minted when asked. */
    Core.@Nullable IdPat rightId;
    /** Pattern for the element's ordinal; minted when asked. */
    Core.@Nullable IdPat ordinalId;

    Frame(Core.Exp rel, Core.IdPat id, ImmutableMap<String, Core.Exp> names) {
      this.rel = requireNonNull(rel);
      this.id = requireNonNull(id);
      this.names = requireNonNull(names);
    }

    @Override
    public String toString() {
      return rel.toString();
    }
  }
}

// End RelBuilder.java
