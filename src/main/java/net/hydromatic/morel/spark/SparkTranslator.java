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
package net.hydromatic.morel.spark;

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;

import com.google.common.collect.ImmutableList;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.JoinType;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.foreign.SparkCatalog;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.apache.spark.connect.proto.Aggregate;
import org.apache.spark.connect.proto.DataType;
import org.apache.spark.connect.proto.Deduplicate;
import org.apache.spark.connect.proto.Expression;
import org.apache.spark.connect.proto.Filter;
import org.apache.spark.connect.proto.Join;
import org.apache.spark.connect.proto.Limit;
import org.apache.spark.connect.proto.LocalRelation;
import org.apache.spark.connect.proto.Offset;
import org.apache.spark.connect.proto.Plan;
import org.apache.spark.connect.proto.Project;
import org.apache.spark.connect.proto.Read;
import org.apache.spark.connect.proto.Relation;
import org.apache.spark.connect.proto.RelationCommon;
import org.apache.spark.connect.proto.SetOperation;
import org.apache.spark.connect.proto.Sort;
import org.apache.spark.connect.proto.SubqueryAlias;
import org.apache.spark.connect.proto.SubqueryExpression;
import org.apache.spark.connect.proto.WithRelations;
import org.jspecify.annotations.Nullable;

/**
 * Translates a Morel query, as the core tree holds it, to a Spark plan.
 *
 * <p>Connect's {@code Relation} is a conventional operator tree, and so is the
 * core tree since #449, so the translation is close to one node for one node.
 * The hinge is the row variable: a Morel query binds one (the {@code r} of
 * {@code from r in ...}) and refers to a column as a field of it, which is what
 * Spark calls a subquery alias and a qualified attribute. So the alias goes on
 * the scanned collection, and {@code #b r} becomes {@code r.b}.
 *
 * <p>Plan ids are assigned in the order the text prints them -- the outermost
 * node is 0 -- so that a plan's text depends on the query and nothing else.
 */
public class SparkTranslator {
  private final TypeSystem typeSystem;
  private final SparkFunctionTable functions = SparkFunctionTable.instance();
  private final List<Integer> localRowCounts = new ArrayList<>();

  /**
   * For a row that an outer join may not find, the column that says whether it
   * was found, by the name the row goes by.
   */
  private final Map<String, String> witnesses = new HashMap<>();

  /**
   * The relations that expressions in the query refer to, which Spark carries
   * beside the root rather than inside it. A correlated subquery is one.
   */
  private final List<Relation> references = new ArrayList<>();

  private int nextPlanId;

  private SparkTranslator(TypeSystem typeSystem) {
    this.typeSystem = typeSystem;
  }

  /**
   * Returns a query with the bindings that correlate a subquery substituted
   * away.
   *
   * <p>A subquery that reads the enclosing row captures it in a {@code let},
   * and names the binding where it means the row: {@code let val v = d in
   * nonEmpty (... v ...) end}. Spark needs no binding, because a subquery
   * resolves a name against the enclosing relation, and a {@code let} is in the
   * way of every path that reads an expression -- a filter's condition, a
   * projection's record -- so they come off before anything else looks.
   */
  private Core.Exp unlet(Core.Exp query) {
    return query.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Let let) {
            final Core.Exp body = let.exp.accept(this);
            if (let.decl instanceof Core.NonRecValDecl) {
              final Core.NonRecValDecl decl = (Core.NonRecValDecl) let.decl;
              if (decl.exp.op == Op.ID) {
                return substitute(body, decl.pat, decl.exp);
              }
            }
            // Not a correlation; the translator will say what it is.
            return core.let(let.decl, body);
          }
        });
  }

  /** Translates a query to a plan. */
  public static SparkPlan translate(TypeSystem typeSystem, Core.Exp query) {
    final SparkTranslator translator = new SparkTranslator(typeSystem);
    final Relation root = translator.rel(translator.unlet(query));
    return new SparkPlan(
        Plan.newBuilder().setRoot(root).build(), translator.localRowCounts);
  }

  /**
   * Translates a relational node, wrapping it where its own expressions refer
   * to relations: Spark keeps those beside the node, not inside it, and the
   * wrapper is what holds them. The wrapper takes the plan id above the node's,
   * which is the order the text prints them.
   */
  private Relation rel(Core.Exp exp) {
    if (!(exp instanceof Core.Rel)) {
      // The query is the collection itself -- `from e in emps`, with no step
      // but the scan -- so the plan is the read, and there is nothing above it
      // to need a name for its rows.
      return leaf(exp);
    }
    if (isWholeOfInput(exp)) {
      // Morel distinguishes a bag of one-field records from a bag of values,
      // and a projection turns the one into the other. Spark does not: a
      // relation always has named columns, so the projection is nothing to
      // do, and takes no plan id.
      return rel(((Core.Project) exp).input);
    }
    if (!refersToRelation(exp)) {
      return relNode(exp, nextPlanId++);
    }
    final int wrapperId = nextPlanId++;
    final List<Relation> enclosing = new ArrayList<>(references);
    references.clear();
    final Relation node = relNode(exp, nextPlanId++);
    final List<Relation> own = new ArrayList<>(references);
    references.clear();
    references.addAll(enclosing);
    return Relation.newBuilder()
        .setCommon(RelationCommon.newBuilder().setPlanId(wrapperId))
        .setWithRelations(
            WithRelations.newBuilder().setRoot(node).addAllReferences(own))
        .build();
  }

  /**
   * Returns whether a node is a projection that reads the whole of its input:
   * the one field of a one-field row, which leaves the relation as it was.
   */
  private static boolean isWholeOfInput(Core.Exp exp) {
    if (!(exp instanceof Core.Project)) {
      return false;
    }
    final Core.Project project = (Core.Project) exp;
    if (project.exp.op != Op.APPLY) {
      return false;
    }
    final Core.Apply apply = (Core.Apply) project.exp;
    if (apply.fn.op != Op.RECORD_SELECTOR || apply.arg.op != Op.ID) {
      return false;
    }
    final Type elementType = project.input.type.arg(0);
    return elementType instanceof RecordLikeType
        && ((RecordLikeType) elementType).argNameTypes().size() == 1
        && ((RecordLikeType) elementType)
            .argNameTypes()
            .containsKey(selectedName(apply));
  }

  /** Returns whether a node's own expressions ask a question of a query. */
  private boolean refersToRelation(Core.Exp exp) {
    if (exp instanceof Core.Filter) {
      return containsSubquery(((Core.Filter) exp).condition);
    }
    if (exp instanceof Core.Project) {
      return containsSubquery(((Core.Project) exp).exp);
    }
    return false;
  }

  /**
   * Returns whether an expression asks a question of a query. The function
   * table says which built-ins do.
   */
  private boolean containsSubquery(Core.Exp exp) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Apply apply) {
            if (apply.fn.op == Op.FN_LITERAL) {
              final SparkFunctionTable.Entry entry =
                  functions.get((BuiltIn) ((Core.Literal) apply.fn).value);
              if (entry != null
                  && entry.kind == SparkFunctionTable.Kind.SUBQUERY) {
                found[0] = true;
              }
            }
            super.visit(apply);
          }
        });
    return found[0];
  }

  /** Translates one relational node, under a given plan id. */
  private Relation relNode(Core.Exp exp, int planId) {
    final Relation.Builder b = Relation.newBuilder();
    switch (exp.op) {
      case FILTER:
        final Core.Filter filter = (Core.Filter) exp;
        b.setFilter(
            Filter.newBuilder()
                .setInput(input(filter.input, filter.id))
                .setCondition(exp(filter.condition, parts(filter.input))));
        break;

      case PROJECT:
        final Core.Project project = (Core.Project) exp;
        b.setProject(
            Project.newBuilder()
                .setInput(input(project.input, project.id))
                .addAllExpressions(
                    projections(
                        project.exp, parts(project.input), project.type)));
        break;

      case GROUP:
        final Core.Group group = (Core.Group) exp;
        if (group.aggregates.isEmpty()) {
          // Grouping without aggregating asks only for distinct rows, which
          // Spark does by projecting the keys and deduplicating them. One
          // node becomes two, so the inner one takes the next plan id.
          final Project.Builder keys = Project.newBuilder();
          final int keyId = nextPlanId++;
          final Relation keyInput = input(group.input, group.id);
          final List<String> keyParts = parts(group.input);
          group.keys.forEach(
              (name, key) ->
                  keys.addExpressions(named(exp(key, keyParts), name)));
          b.setDeduplicate(
              Deduplicate.newBuilder()
                  .setInput(
                      Relation.newBuilder()
                          .setCommon(
                              RelationCommon.newBuilder().setPlanId(keyId))
                          .setProject(keys.setInput(keyInput)))
                  .setAllColumnsAsKeys(true)
                  .setWithinWatermark(false));
          break;
        }
        final Aggregate.Builder aggregate =
            Aggregate.newBuilder()
                .setInput(input(group.input, group.id))
                .setGroupType(Aggregate.GroupType.GROUP_TYPE_GROUPBY);
        final List<String> groupParts = parts(group.input);
        group.keys.forEach(
            (name, key) ->
                aggregate.addGroupingExpressions(
                    named(exp(key, groupParts), name)));
        group.aggregates.forEach(
            (name, agg) ->
                aggregate.addAggregateExpressions(
                    named(aggregate(agg, groupParts), name)));
        b.setAggregate(aggregate);
        break;

      case SORT:
        final Core.Sort sort = (Core.Sort) exp;
        final Sort.Builder sortBuilder =
            Sort.newBuilder().setInput(input(sort.input, sort.id));
        sortOrder(sort.exp, false, parts(sort.input), sortBuilder::addOrder);
        // A query orders its whole result, not each partition of it.
        b.setSort(sortBuilder.setIsGlobal(true));
        break;

      case TAKE:
        final Core.Take take = (Core.Take) exp;
        b.setLimit(
            Limit.newBuilder()
                .setInput(rel(take.input))
                .setLimit(count(take.count, "take")));
        break;

      case SKIP:
        final Core.Skip skip = (Core.Skip) exp;
        b.setOffset(
            Offset.newBuilder()
                .setInput(rel(skip.input))
                .setOffset(count(skip.count, "skip")));
        break;

      case UNION:
      case INTERSECT:
      case EXCEPT:
        final Core.SetRel setRel = (Core.SetRel) exp;
        if (setRel.inputs.size() != 2) {
          // Spark's set operation takes two inputs; Morel's takes any
          // number, and nothing yet folds the one into the other.
          throw unsupported(exp.op + " of " + setRel.inputs.size() + " inputs");
        }
        b.setSetOp(
            SetOperation.newBuilder()
                .setLeftInput(rel(setRel.inputs.get(0)))
                .setRightInput(rel(setRel.inputs.get(1)))
                .setSetOpType(setOpType(exp.op))
                // Morel's set operators are over bags and keep every row,
                // unless the query asked for distinct.
                .setIsAll(!setRel.distinct)
                .setByName(false)
                .setAllowMissingColumns(false));
        break;

      case JOIN:
        final Core.Join join = (Core.Join) exp;
        if (join.joinType == JoinType.LEFT) {
          final String key = keyColumn(join.condition, join.rightId);
          if (key != null) {
            witnesses.put(elementName(join.rightId), key);
          }
        }
        b.setJoin(
            Join.newBuilder()
                .setLeft(input(join.left, join.leftId))
                .setRight(input(join.right, join.rightId))
                .setJoinCondition(exp(join.condition, ImmutableList.of()))
                .setJoinType(joinType(join.joinType)));
        break;

      default:
        throw unsupported("relational operator " + exp.op);
    }
    return b.setCommon(RelationCommon.newBuilder().setPlanId(planId)).build();
  }

  /**
   * Translates a node's input. A relational input translates as itself;
   * anything else is the collection the query scans, and takes the row
   * variable's name as its alias.
   */
  private Relation input(Core.Exp exp, Core.IdPat row) {
    if (exp instanceof Core.Rel) {
      return rel(exp);
    }
    final int planId = nextPlanId++;
    return Relation.newBuilder()
        .setCommon(RelationCommon.newBuilder().setPlanId(planId))
        .setSubqueryAlias(
            SubqueryAlias.newBuilder()
                .setInput(leaf(exp))
                .setAlias(elementName(row)))
        .build();
  }

  /** Translates the collection a query scans. */
  private Relation leaf(Core.Exp exp) {
    final int planId = nextPlanId++;
    if (exp.isCallTo(BuiltIn.Z_LIST)) {
      final List<Object> values = new ArrayList<>();
      rows(exp).forEach(row -> values.add(value(row)));
      return localRelation(planId, values, exp.type);
    }
    final String table = catalogTable(exp);
    if (table != null) {
      return Relation.newBuilder()
          .setCommon(RelationCommon.newBuilder().setPlanId(planId))
          .setRead(
              Read.newBuilder()
                  .setNamedTable(
                      Read.NamedTable.newBuilder()
                          .setUnparsedIdentifier(table)))
          .build();
    }
    if (exp.op == Op.VALUE_LITERAL) {
      // A collection the query reached through a name rather than writing out,
      // and not a table of a catalog: its rows cross as a local relation, as
      // an inline collection's do.
      final Object value = ((Core.Literal) exp).valueObject();
      if (value instanceof List) {
        return localRelation(planId, (List<Object>) value, exp.type);
      }
    }
    throw unsupported("collection " + exp.op);
  }

  /**
   * Returns what Spark calls the table a collection reads, or null if the
   * collection is not a table of a connection's catalog.
   *
   * <p>The name is no guide, so the value decides: {@code scott.emps} may be a
   * table of the server, a data set of this session with the same name, or a
   * record whose field holds a collection, and only the first is a scan.
   *
   * <p>A table arrives as a value, because inlining replaced the expression
   * that read it with what it holds, and what a catalog holds is the table
   * itself -- which knows the name Spark has for it.
   */
  private static @Nullable String catalogTable(Core.Exp exp) {
    if (exp.op == Op.VALUE_LITERAL) {
      final Object value = ((Core.Literal) exp).valueObject();
      if (value instanceof SparkCatalog.Table) {
        return ((SparkCatalog.Table) value).qualifiedName();
      }
    }
    return null;
  }

  /** Translates a set operator. */
  private static SetOperation.SetOpType setOpType(Op op) {
    switch (op) {
      case UNION:
        return SetOperation.SetOpType.SET_OP_TYPE_UNION;
      case INTERSECT:
        return SetOperation.SetOpType.SET_OP_TYPE_INTERSECT;
      case EXCEPT:
        return SetOperation.SetOpType.SET_OP_TYPE_EXCEPT;
      default:
        throw unsupported("set operator " + op);
    }
  }

  /** Translates a join's kind. */
  private static Join.JoinType joinType(JoinType joinType) {
    switch (joinType) {
      case INNER:
        return Join.JoinType.JOIN_TYPE_INNER;
      case LEFT:
        return Join.JoinType.JOIN_TYPE_LEFT_OUTER;
      case RIGHT:
        return Join.JoinType.JOIN_TYPE_RIGHT_OUTER;
      case FULL:
        return Join.JoinType.JOIN_TYPE_FULL_OUTER;
      default:
        throw unsupported("join kind " + joinType);
    }
  }

  /**
   * Returns how many rows a `take` or a `skip` counts. Spark wants a number, so
   * a count that is not a literal cannot be pushed down.
   */
  private static int count(Core.Exp exp, String what) {
    if (exp.op != Op.INT_LITERAL) {
      throw unsupported(what + " of " + exp.op);
    }
    return ((Number) ((Core.Literal) exp).value).intValue();
  }

  /**
   * Returns the rows of an inline collection. A list is a call to the internal
   * list constructor over a tuple of its elements.
   */
  private static List<Core.Exp> rows(Core.Exp exp) {
    final Core.Exp arg = ((Core.Apply) exp).arg;
    if (arg.op == Op.TUPLE) {
      return ((Core.Tuple) arg).args;
    }
    throw unsupported("inline collection whose rows are " + arg.op);
  }

  /**
   * Translates a collection of rows as a local relation: the rows as an Arrow
   * batch, and the schema that says what the bytes mean.
   */
  private Relation localRelation(
      int planId, List<Object> rows, Type collectionType) {
    final Type elementType = elementType(collectionType);
    localRowCounts.add(rows.size());
    return Relation.newBuilder()
        .setCommon(RelationCommon.newBuilder().setPlanId(planId))
        .setLocalRelation(
            LocalRelation.newBuilder()
                .setData(ArrowEncoder.encode(rows, elementType))
                .setSchema(SparkSchema.schemaJson(elementType)))
        .build();
  }

  /**
   * Returns the Morel value of a constant expression.
   *
   * <p>The rows of an inline collection are written out in the query, and Spark
   * carries them as data rather than as expressions, so what it wants is their
   * values.
   */
  private static Object value(Core.Exp exp) {
    switch (exp.op) {
      case BOOL_LITERAL:
        return ((Core.Literal) exp).unwrap(Boolean.class);
      case INT_LITERAL:
        return ((Core.Literal) exp).unwrap(Integer.class);
      case REAL_LITERAL:
        return ((Core.Literal) exp).unwrap(Float.class);
      case STRING_LITERAL:
        return ((Core.Literal) exp).unwrap(String.class);
      case TUPLE:
        final ImmutableList.Builder<Object> fields = ImmutableList.builder();
        ((Core.Tuple) exp).args.forEach(arg -> fields.add(value(arg)));
        return fields.build();
      case VALUE_LITERAL:
        // A row the compiler folded into a value. A decimal is written as a
        // call -- "decimal \"1.5\"" -- which folds, and a record of such
        // calls folds with it, so what is left is the value itself, in the
        // form the encoder wants.
        return requireNonNull(((Core.Literal) exp).valueObject(), "row value");
      default:
        throw unsupported("a row that is " + exp.op);
    }
  }

  /**
   * Returns the name a built-in goes by: the table's name for it, which is the
   * name a program writes, rather than the name of the constant that holds it.
   */
  private String name(BuiltIn builtIn) {
    final SparkFunctionTable.Entry entry = functions.get(builtIn);
    return entry != null ? entry.morel : builtIn.mlName;
  }

  /** Returns the element type of a collection type. */
  private Type elementType(Type type) {
    return type.arg(0);
  }

  /**
   * Translates the expression a {@code yield} computes into one Spark
   * expression per output field. A field whose value is the input field of the
   * same name needs no alias; anything else is aliased to the output name.
   */
  private List<Expression> projections(
      Core.Exp exp, List<String> parts, Type type) {
    if (exp.op != Op.TUPLE) {
      // A query can yield one unnamed column -- `yield d.dname` -- and then
      // there is no record of names to draw an alias from.
      return ImmutableList.of(exp(exp, parts));
    }
    final Core.Tuple tuple = (Core.Tuple) exp;
    final List<String> names =
        new ArrayList<>(((RecordLikeType) type.arg(0)).argNameTypes().keySet());
    final List<Expression> expressions = new ArrayList<>();
    for (int i = 0; i < tuple.args.size(); i++) {
      final Core.Exp arg = tuple.args.get(i);
      final String name = names.get(i);
      final Expression expression = exp(arg, parts);
      if (name.equals(fieldName(arg))) {
        expressions.add(expression);
      } else {
        expressions.add(
            Expression.newBuilder()
                .setAlias(
                    Expression.Alias.newBuilder()
                        .setExpr(expression)
                        .addName(name))
                .build());
      }
    }
    return expressions;
  }

  /**
   * Returns the name of the input field that {@code exp} reads, or null if it
   * reads none: that is, if it is anything but a field of the row.
   */
  private static @Nullable String fieldName(Core.Exp exp) {
    if (exp.op == Op.APPLY) {
      final Core.Apply apply = (Core.Apply) exp;
      if (apply.fn.op == Op.RECORD_SELECTOR) {
        return selectedName(apply);
      }
    }
    return null;
  }

  /** Translates an expression. */
  private Expression exp(Core.Exp exp, List<String> parts) {
    switch (exp.op) {
      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        if (apply.fn.op == Op.RECORD_SELECTOR
            && apply.arg.op == Op.ID
            && !parts.isEmpty()
            && ((Core.RecordSelector) apply.fn).slot < parts.size()) {
          // A whole input of a join, not a column of one: `yield {d.dname, e}`
          // asks for all of `e`.
          return component(
              parts.get(((Core.RecordSelector) apply.fn).slot), apply.type);
        }
        if (apply.fn.op == Op.RECORD_SELECTOR) {
          final String qualifier = qualifier(apply.arg, parts);
          if (qualifier != null) {
            return attribute(
                qualifier.isEmpty()
                    ? selectedName(apply)
                    : qualifier + "." + selectedName(apply));
          }
        }
        final List<Core.Exp> args = new ArrayList<>();
        final Core.Exp fn = flatten(apply, args);
        if (fn.op == Op.FN_LITERAL) {
          return function((BuiltIn) ((Core.Literal) fn).value, args, parts);
        }
        throw unsupported("call to " + apply.fn.op);

      case BOOL_LITERAL:
        return literal(
            Expression.Literal.newBuilder()
                .setBoolean((Boolean) ((Core.Literal) exp).value));

      case INT_LITERAL:
        return literal(
            Expression.Literal.newBuilder()
                .setInteger(((Number) ((Core.Literal) exp).value).intValue()));

      case REAL_LITERAL:
        return literal(
            Expression.Literal.newBuilder()
                .setDouble(
                    ((Number) ((Core.Literal) exp).value).doubleValue()));

      case STRING_LITERAL:
        return literal(
            Expression.Literal.newBuilder()
                .setString((String) ((Core.Literal) exp).value));

      case VALUE_LITERAL:
        // A `decimal` literal, which `decimal "2"` folded to. Morel's decimal
        // is arbitrary-precision, and Spark's is not, so the literal carries
        // the narrowest decimal type that holds the value -- the type Spark's
        // own parser gives the same literal written as SQL.
        final BigDecimal d = decimalValue(exp);
        return literal(
            Expression.Literal.newBuilder()
                .setDecimal(
                    Expression.Literal.Decimal.newBuilder()
                        .setValue(d.toPlainString())
                        .setPrecision(Math.max(d.precision(), d.scale() + 1))
                        .setScale(d.scale())));

      case ID:
        // A name that is not a column of the query: a plan's parameter, say,
        // which phase 1 does not pass, or a value the resolver could not
        // replace with what it holds. Naming it says which.
        throw unsupported("the variable " + ((Core.Id) exp).idPat.name);

      default:
        throw unsupported("expression " + exp.op);
    }
  }

  /**
   * Returns the arguments of a call: a tuple's fields, or the one argument of a
   * one-argument call.
   */
  private static List<Core.Exp> arguments(Core.Exp arg) {
    return arg.op == Op.TUPLE ? ((Core.Tuple) arg).args : ImmutableList.of(arg);
  }

  /**
   * Peels a call down to the function it calls, adding the arguments it meets
   * to {@code args} in the order they were written.
   *
   * <p>A built-in takes its arguments one way or the other: {@code
   * String.substring (s, 0, 2)} is one application of a tuple, and {@code
   * String.isPrefix "S" s} is two applications, one per argument. Both arrive
   * here as the same list, which is what the function table's templates number.
   */
  private static Core.Exp flatten(Core.Exp exp, List<Core.Exp> args) {
    if (exp.op != Op.APPLY) {
      return exp;
    }
    final Core.Apply apply = (Core.Apply) exp;
    final Core.Exp fn = flatten(apply.fn, args);
    args.addAll(arguments(apply.arg));
    return fn;
  }

  /** Translates a call to a built-in. */
  private Expression function(
      BuiltIn builtIn, List<Core.Exp> args, List<String> parts) {
    final SparkFunctionTable.Entry entry = functions.get(builtIn);
    if (entry == null) {
      throw unsupported(
          "built-in " + builtIn.mlName + ", which the table omits");
    }
    switch (entry.kind) {
      case REWRITTEN:
        // The template is SQL, so its arguments must be SQL too, and so must
        // anything inside them. Spark parses what comes out.
        return Expression.newBuilder()
            .setExpressionString(
                Expression.ExpressionString.newBuilder()
                    .setExpression(
                        requireNonNull(entry.template)
                            .expand(i -> sql(args.get(i), parts))))
            .build();

      case SUBQUERY:
        return subquery(builtIn, args, parts);

      case DIRECT:
        final Expression.UnresolvedFunction.Builder b =
            Expression.UnresolvedFunction.newBuilder()
                .setFunctionName(entry.spark);
        for (Core.Exp arg : args) {
          b.addArguments(exp(arg, parts));
        }
        return Expression.newBuilder().setUnresolvedFunction(b).build();
      default:
        throw unsupported(
            name(builtIn)
                + ", which the table calls "
                + entry.kind.name().toLowerCase(Locale.ROOT));
    }
  }

  /**
   * Translates an aggregate function over a group: the function the table
   * names, applied to what the query aggregates. An aggregate with no argument
   * counts rows, and Spark counts them by counting a constant.
   */
  private Expression aggregate(Core.Aggregate agg, List<String> parts) {
    if (agg.aggregate.op != Op.FN_LITERAL) {
      throw unsupported("aggregate " + agg.aggregate.op);
    }
    final BuiltIn builtIn = (BuiltIn) ((Core.Literal) agg.aggregate).value;
    final SparkFunctionTable.Entry entry = functions.get(builtIn);
    if (entry == null) {
      throw unsupported(
          "aggregate " + builtIn.mlName + ", which the table omits");
    }
    if (entry.kind != SparkFunctionTable.Kind.AGGREGATE) {
      throw unsupported(
          name(builtIn)
              + " over a group, which the table calls "
              + entry.kind.name().toLowerCase(Locale.ROOT));
    }
    final Expression.UnresolvedFunction.Builder b =
        Expression.UnresolvedFunction.newBuilder().setFunctionName(entry.spark);
    b.addArguments(
        agg.argument == null || agg.argument.op == Op.UNIT_LITERAL
            ? literal(Expression.Literal.newBuilder().setInteger(1))
            : exp(agg.argument, parts));
    return Expression.newBuilder().setUnresolvedFunction(b).build();
  }

  /**
   * Names an expression, unless it already reads the field of that name: a key
   * called `deptno` that reads `deptno` needs no alias, and an aggregate always
   * does.
   */
  private static Expression named(Expression expression, String name) {
    if (expression.hasUnresolvedAttribute()) {
      final String id =
          expression.getUnresolvedAttribute().getUnparsedIdentifier();
      if (id.endsWith("." + name) || id.equals(name)) {
        return expression;
      }
    }
    return Expression.newBuilder()
        .setAlias(
            Expression.Alias.newBuilder().setExpr(expression).addName(name))
        .build();
  }

  /**
   * Translates the keys a query orders by, which are a tuple when there is more
   * than one, and each of which may be wrapped in `DESC`.
   *
   * <p>Spark puts nulls where that direction puts them: last when descending,
   * first when ascending.
   */
  private void sortOrder(
      Core.Exp exp,
      boolean descending,
      List<String> parts,
      Consumer<Expression.SortOrder> consumer) {
    if (exp.isCallTo(BuiltIn.Constructor.DESCENDING_DESC)) {
      sortOrder(((Core.Apply) exp).arg, !descending, parts, consumer);
      return;
    }
    if (exp.op == Op.TUPLE) {
      for (Core.Exp arg : ((Core.Tuple) exp).args) {
        sortOrder(arg, descending, parts, consumer);
      }
      return;
    }
    consumer.accept(
        Expression.SortOrder.newBuilder()
            .setChild(exp(exp, parts))
            .setDirection(
                descending
                    ? Expression.SortOrder.SortDirection
                        .SORT_DIRECTION_DESCENDING
                    : Expression.SortOrder.SortDirection
                        .SORT_DIRECTION_ASCENDING)
            .setNullOrdering(
                descending
                    ? Expression.SortOrder.NullOrdering.SORT_NULLS_LAST
                    : Expression.SortOrder.NullOrdering.SORT_NULLS_FIRST)
            .build());
  }

  /**
   * Translates a call that asks a question of a query: whether it has a row,
   * whether it holds a value, or what its one value is. The query goes beside
   * the root, under a plan id of its own, and the expression quotes that id.
   *
   * <p>The query may read the outer row, which is what makes it correlated;
   * nothing special is needed for that, because the outer row prints under the
   * name it has, and Spark resolves it against the enclosing relation.
   */
  private Expression subquery(
      BuiltIn builtIn, List<Core.Exp> args, List<String> parts) {
    final SubqueryExpression.SubqueryType type;
    final Relation relation;
    final boolean negated;
    switch (builtIn) {
      case OP_ELEM:
      case OP_NOT_ELEM:
        relation = elemRelation(args.get(1), args.get(0), nextPlanId++);
        type = SubqueryExpression.SubqueryType.SUBQUERY_TYPE_EXISTS;
        negated = builtIn == BuiltIn.OP_NOT_ELEM;
        break;

      case RELATIONAL_ONLY:
        relation = relation(args.get(0));
        type = SubqueryExpression.SubqueryType.SUBQUERY_TYPE_SCALAR;
        negated = false;
        break;

      case RELATIONAL_NON_EMPTY:
      case RELATIONAL_EMPTY:
      case LIST_NULL:
        relation = relation(args.get(0));
        type = SubqueryExpression.SubqueryType.SUBQUERY_TYPE_EXISTS;
        // Spark has no NOT EXISTS of its own; `empty` is `exists` denied.
        negated = builtIn != BuiltIn.RELATIONAL_NON_EMPTY;
        break;

      default:
        throw unsupported(name(builtIn) + ", which the table calls a subquery");
    }
    // The expression quotes the plan id of the relation it asks about, which
    // the relation itself knows; a node the translation leaves out, such as a
    // projection that reads the whole of its input, never had one.
    final int planId = (int) relation.getCommon().getPlanId();
    references.add(relation);
    final Expression expression =
        Expression.newBuilder()
            .setSubqueryExpression(
                SubqueryExpression.newBuilder()
                    .setPlanId(planId)
                    .setSubqueryType(type))
            .build();
    return negated ? call("not", expression) : expression;
  }

  /** Translates the query a subquery expression asks about. */
  private Relation relation(Core.Exp exp) {
    if (!(exp instanceof Core.Rel)) {
      throw unsupported("a subquery over " + exp.op);
    }
    return rel(exp);
  }

  /**
   * Translates the collection of an {@code elem} as the relation of an EXISTS
   * subquery: not the values the query yields, but the rows that hold the value
   * asked about.
   *
   * <p>Spark Connect 4.0 has no IN subquery, so {@code x elem q} becomes {@code
   * exists q where v = x}, where v is what q yields. The projection comes off
   * -- EXISTS does not look at it -- and the expression it yielded goes into
   * the condition, which is also the only place it can go, since nothing
   * outside a subquery can name a column of it. Where q filters already, the
   * equality joins that condition rather than standing in a second filter above
   * it.
   */
  private Relation elemRelation(
      Core.Exp collection, Core.Exp value, int planId) {
    if (collection.op != Op.PROJECT) {
      throw unsupported("elem over " + collection.op);
    }
    final Core.Project project = (Core.Project) collection;
    final List<String> parts = parts(project.input);
    final Expression equality =
        call("==", exp(project.exp, parts), exp(value, parts));
    final Filter.Builder filterBuilder = Filter.newBuilder();
    if (project.input.op == Op.FILTER) {
      final Core.Filter filter = (Core.Filter) project.input;
      filterBuilder
          .setInput(input(filter.input, filter.id))
          .setCondition(call("and", exp(filter.condition, parts), equality));
    } else {
      filterBuilder
          .setInput(input(project.input, project.id))
          .setCondition(equality);
    }
    return Relation.newBuilder()
        .setCommon(RelationCommon.newBuilder().setPlanId(planId))
        .setFilter(filterBuilder)
        .build();
  }

  /** Builds a call to a Spark function. */
  private static Expression call(String name, Expression... args) {
    final Expression.UnresolvedFunction.Builder b =
        Expression.UnresolvedFunction.newBuilder().setFunctionName(name);
    for (Expression arg : args) {
      b.addArguments(arg);
    }
    return Expression.newBuilder().setUnresolvedFunction(b).build();
  }

  /**
   * Translates a reference to a whole input of a join: a struct of its columns,
   * and, where the input may be absent, null instead of the struct when it was.
   *
   * <p>Spark gives an absent side a row of nulls rather than an absent row, so
   * the only way to tell is to ask whether a column that cannot be null is
   * null. A row all of whose columns are optional has no such column, and the
   * two cases are then the same bytes, so such a join is refused; `plan.md`
   * records what it would take to support it.
   */
  private Expression component(String alias, Type type) {
    final Type valueType = SparkSchema.optionValueType(type);
    final Type rowType = valueType != null ? valueType : type;
    if (!(rowType instanceof RecordLikeType)) {
      throw unsupported("a whole input of type " + rowType);
    }
    final Map<String, Type> fields = ((RecordLikeType) rowType).argNameTypes();
    final Expression.UnresolvedFunction.Builder struct =
        Expression.UnresolvedFunction.newBuilder().setFunctionName("struct");
    fields.forEach(
        (name, fieldType) ->
            struct.addArguments(
                Expression.newBuilder()
                    .setAlias(
                        Expression.Alias.newBuilder()
                            .setExpr(attribute(alias + "." + name))
                            .addName(name))
                    .build()));
    final Expression structExp =
        Expression.newBuilder().setUnresolvedFunction(struct).build();
    if (valueType == null) {
      return structExp;
    }
    final String witness = witnesses.get(alias);
    if (witness == null) {
      throw unsupported(
          "a row of this join that may be absent, because nothing in the"
              + " join's condition says whether a row was found");
    }
    return Expression.newBuilder()
        .setUnresolvedFunction(
            Expression.UnresolvedFunction.newBuilder()
                .setFunctionName("when")
                .addArguments(
                    Expression.newBuilder()
                        .setUnresolvedFunction(
                            Expression.UnresolvedFunction.newBuilder()
                                .setFunctionName("isNull")
                                .addArguments(attribute(alias + "." + witness)))
                        .build())
                .addArguments(
                    literal(
                        Expression.Literal.newBuilder()
                            .setNull(
                                DataType.newBuilder()
                                    .setNull(
                                        DataType.NULL.getDefaultInstance()))))
                .addArguments(structExp))
        .build();
  }

  /**
   * Returns a column of the right row's join key, or null if the condition has
   * none.
   *
   * <p>This is the column that says whether a left join found the row: a key
   * cannot be null in a row that matched, because a null key matches nothing,
   * so asking whether it is null asks exactly the right question.
   *
   * <p>The types cannot answer it. A catalog column is a plain type whether or
   * not it holds nulls, so a column that looks as though it could not be null
   * may be null in a row that was found -- and a join that asked that column
   * would report the row as not found, quietly. The commission of Morel's own
   * `scott` is such a column.
   */
  private static @Nullable String keyColumn(
      Core.Exp condition, Core.IdPat rightRow) {
    if (condition.op != Op.APPLY) {
      return null;
    }
    final Core.Apply apply = (Core.Apply) condition;
    if (apply.fn.op != Op.FN_LITERAL) {
      return null;
    }
    final List<Core.Exp> args = arguments(apply.arg);
    switch ((BuiltIn) ((Core.Literal) apply.fn).value) {
      case Z_ANDALSO:
      case BOOL_ANDALSO:
        // Any conjunct may hold the key.
        for (Core.Exp arg : args) {
          final String column = keyColumn(arg, rightRow);
          if (column != null) {
            return column;
          }
        }
        return null;

      case OP_EQ:
        for (Core.Exp arg : args) {
          final String column = columnOf(arg, rightRow);
          if (column != null) {
            return column;
          }
        }
        return null;

      default:
        return null;
    }
  }

  /** Returns the column that an expression reads of a row, or null. */
  private static @Nullable String columnOf(Core.Exp exp, Core.IdPat row) {
    if (exp.op == Op.APPLY) {
      final Core.Apply apply = (Core.Apply) exp;
      if (apply.fn.op == Op.RECORD_SELECTOR
          && apply.arg.op == Op.ID
          && ((Core.Id) apply.arg).idPat.equals(row)) {
        return selectedName(apply);
      }
    }
    return null;
  }

  /**
   * Renders an expression as SQL, for a template to put its arguments into.
   *
   * <p>Every compound expression is parenthesized, whatever its operator: the
   * text goes to Spark's parser, and parenthesizing unconditionally is both
   * shorter to write and safer to read than a table of precedences.
   */
  private String sql(Core.Exp exp, List<String> parts) {
    switch (exp.op) {
      case BOOL_LITERAL:
      case INT_LITERAL:
        return ((Core.Literal) exp).value.toString();

      case REAL_LITERAL:
        return Float.toString(
            ((Number) ((Core.Literal) exp).value).floatValue());

      case VALUE_LITERAL:
        // `BD` is how SQL spells a decimal literal; without it, Spark would
        // read `2` as an int and `2.5` as a double.
        return decimalValue(exp).toPlainString() + "BD";

      case STRING_LITERAL:
        return "'"
            + ((String) ((Core.Literal) exp).value).replace("'", "''")
            + "'";

      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        if (apply.fn.op == Op.RECORD_SELECTOR) {
          final String qualifier = qualifier(apply.arg, parts);
          if (qualifier != null) {
            return qualifier.isEmpty()
                ? selectedName(apply)
                : qualifier + "." + selectedName(apply);
          }
        }
        final List<Core.Exp> args = new ArrayList<>();
        final Core.Exp fn = flatten(apply, args);
        if (fn.op == Op.FN_LITERAL) {
          return sqlCall((BuiltIn) ((Core.Literal) fn).value, args, parts);
        }
        break;

      default:
        break;
    }
    throw unsupported("expression " + exp.op + " inside a SQL template");
  }

  /**
   * Returns the value of a decimal literal, with a scale that Spark allows
   * (Spark has no negative scale, so {@code 2E+3} becomes {@code 2000}).
   */
  private static BigDecimal decimalValue(Core.Exp exp) {
    // A value literal keeps its value wrapped, so that a literal compares by
    // the expression it stands for.
    final BigDecimal value = ((Core.Literal) exp).unwrap(BigDecimal.class);
    return value.scale() < 0 ? value.setScale(0) : value;
  }

  /** Renders a call to a built-in as SQL. */
  private String sqlCall(
      BuiltIn builtIn, List<Core.Exp> args, List<String> parts) {
    final SparkFunctionTable.Entry entry = functions.get(builtIn);
    if (entry == null) {
      throw unsupported(
          "built-in "
              + builtIn
              + " inside a SQL template, which the table"
              + " omits");
    }
    switch (entry.kind) {
      case REWRITTEN:
        return requireNonNull(entry.template)
            .expand(i -> sql(args.get(i), parts));

      case DIRECT:
        if (args.size() == 2 && !isWord(entry.spark)) {
          return "("
              + sql(args.get(0), parts)
              + " "
              + entry.spark
              + " "
              + sql(args.get(1), parts)
              + ")";
        }
        final StringBuilder b = new StringBuilder(entry.spark).append('(');
        for (int i = 0; i < args.size(); i++) {
          if (i > 0) {
            b.append(", ");
          }
          b.append(sql(args.get(i), parts));
        }
        return b.append(')').toString();

      default:
        throw unsupported(
            builtIn
                + " inside a SQL template, which the table calls "
                + entry.kind);
    }
  }

  /**
   * Returns whether a Spark name is a word, and so written as a call, rather
   * than a symbol written between its arguments.
   */
  private static boolean isWord(String name) {
    return Character.isLetter(name.charAt(0));
  }

  private static Expression attribute(String identifier) {
    return Expression.newBuilder()
        .setUnresolvedAttribute(
            Expression.UnresolvedAttribute.newBuilder()
                .setUnparsedIdentifier(identifier)
                .setIsMetadataColumn(false))
        .build();
  }

  private static Expression literal(Expression.Literal.Builder literal) {
    return Expression.newBuilder().setLiteral(literal).build();
  }

  /**
   * Returns the alias that qualifies a field read out of {@code exp}, or null
   * if {@code exp} is not something a field can be read from.
   *
   * <p>A field of the row is qualified by the row's own name. A field of a
   * component of the row -- what a projection over a join reads -- is qualified
   * by the name of the input that contributed it, which {@code parts} lists in
   * order.
   */
  private static @Nullable String qualifier(Core.Exp exp, List<String> parts) {
    if (exp.op == Op.ID) {
      final String name = elementName(((Core.Id) exp).idPat);
      // A row the compiler named, as a projection's output is, has no alias
      // in the plan, so a column of it is unqualified.
      return name;
    }
    if (exp.op == Op.APPLY) {
      final Core.Apply apply = (Core.Apply) exp;
      if (apply.fn.op == Op.RECORD_SELECTOR && apply.arg.op == Op.ID) {
        final int slot = ((Core.RecordSelector) apply.fn).slot;
        if (slot < parts.size()) {
          return parts.get(slot);
        }
      }
    }
    return null;
  }

  /**
   * Returns the names of a node's input's components: a join's two sides, or
   * nothing at all, since only a join concatenates rows.
   */
  private static List<String> parts(Core.Exp input) {
    if (input instanceof Core.Join) {
      final Core.Join join = (Core.Join) input;
      return ImmutableList.of(
          elementName(join.leftId), elementName(join.rightId));
    }
    if (input instanceof Core.Filter
        || input instanceof Core.Sort
        || input instanceof Core.Skip
        || input instanceof Core.Take
        || input instanceof Core.Unorder) {
      // These pass their element through, so a node above one still reads
      // the components of whatever join is below it.
      return parts(((Core.SingleRel) input).input);
    }
    return ImmutableList.of();
  }

  /**
   * Returns {@code exp} with every use of {@code pat} replaced by {@code v}.
   */
  private Core.Exp substitute(Core.Exp exp, Core.NamedPat pat, Core.Exp v) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Id id) {
            return id.idPat.equals(pat) ? v : id;
          }
        });
  }

  /**
   * Returns the name a query gave an element, or "" if the compiler named it.
   *
   * <p>The compiler writes a query's own name with a {@code $} in front of it,
   * so that it cannot collide with a field; {@code $0} and {@code $ordinal} are
   * its own and have no name a plan can use.
   */
  private static String elementName(Core.NamedPat pat) {
    final @Nullable String name = Core.NamedPat.idName(pat.name);
    if (name != null) {
      return name;
    }
    return pat.name.startsWith("$") ? "" : pat.name;
  }

  /** Returns the field name that a record selector selects. */
  private static String selectedName(Core.Apply apply) {
    final int slot = ((Core.RecordSelector) apply.fn).slot;
    final RecordLikeType recordType = (RecordLikeType) apply.arg.type;
    int i = 0;
    for (Map.Entry<String, Type> field : recordType.argNameTypes().entrySet()) {
      if (i++ == slot) {
        return field.getKey();
      }
    }
    throw new IllegalArgumentException(
        "no field " + slot + " in " + recordType);
  }

  private static SparkBackend.SparkException unsupported(String what) {
    return new SparkBackend.SparkException(
        "UNSUPPORTED", "Spark cannot do " + what);
  }
}

// End SparkTranslator.java
