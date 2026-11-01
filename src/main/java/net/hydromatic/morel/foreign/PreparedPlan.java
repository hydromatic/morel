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
package net.hydromatic.morel.foreign;

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Pair.forEach;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.eval.Describer;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * A plan prepared on a connection: the value of Morel's {@code ('a, 'b) plan}.
 *
 * <p>{@code prepare} translates a function for a connection, and afterwards
 * both are needed: the plan to print or to run, and the connection to run it
 * on. So is the Morel type of the result, because that is the type the program
 * was typed against, and the rows have to come back as values of it.
 *
 * <p>A function with parameters is held as the query rather than as a plan, and
 * translated at each execution with the argument in place of them. Spark has
 * nowhere in a plan to keep a parameter -- its own parameters are for SQL text,
 * not for a tree of relations -- so the argument has to reach the plan before
 * it is sent. Translating again costs a tree walk, which is nothing beside the
 * round trip, and the plan that goes out is the plan a query written with that
 * argument would have produced.
 *
 * <p>It prints as {@code <plan>}. A plan's text is long, and asking for it is
 * what {@code Spark.toString} is for.
 */
public class PreparedPlan {
  private final SparkBackend.Connection connection;
  private final TypeSystem typeSystem;
  private final Core.Exp rel;
  private final ImmutableList<Core.IdPat> parameters;
  private final SparkBackend.@Nullable Plan plan;
  private final Type elementType;
  private final boolean collection;

  /**
   * Creates a prepared plan.
   *
   * @param connection Connection the plan was prepared on, and runs on
   * @param typeSystem Type system that typed the query
   * @param rel The query
   * @param parameters The function's parameters, in the order the argument
   *     holds them, or empty if it has none; a query with parameters is
   *     translated at each execution, because the argument is part of the plan
   * @param elementType Morel type of a row of the result
   * @param collection Whether the result is a collection of rows rather than
   *     one value; a query that computes a single value, such as a count, is a
   *     relation of one row and one column in Spark either way
   */
  public PreparedPlan(
      SparkBackend.Connection connection,
      TypeSystem typeSystem,
      Core.Exp rel,
      List<Core.IdPat> parameters,
      Type elementType,
      boolean collection) {
    this.connection = requireNonNull(connection, "connection");
    this.typeSystem = requireNonNull(typeSystem, "typeSystem");
    this.rel = requireNonNull(rel, "rel");
    this.parameters = ImmutableList.copyOf(parameters);
    // A query with no parameters is translated once, here, so that its plan is
    // the same plan however often it is used.
    this.plan =
        parameters.isEmpty() ? SparkBackend.translate(typeSystem, rel) : null;
    this.elementType = requireNonNull(elementType, "elementType");
    this.collection = collection;
  }

  @Override
  public String toString() {
    return "<plan>";
  }

  /**
   * Describes the plan where a plan description shows it, as {@code Sys.plan}
   * does.
   *
   * <p>A plan with a parameter has no text until it is executed, so what it can
   * say is which parameters it is waiting for.
   */
  public void describe(Describer.Detail detail) {
    if (plan != null) {
      detail.arg("plan", plan.text());
    } else {
      detail.args("parameters", Lists.transform(parameters, p -> p.name));
    }
  }

  /** Returns the plan as text. */
  public String text() {
    if (plan == null) {
      throw new SparkBackend.SparkException(
          "UNSUPPORTED",
          "A plan with a parameter has no text until it is given one;"
              + " execute it instead");
    }
    return plan.text();
  }

  /**
   * Executes the plan on an argument and returns its result: the rows, or,
   * where the query computes a single value, that value.
   */
  public Object execute(Object arg) {
    final SparkBackend.Plan plan =
        this.plan != null
            ? this.plan
            : SparkBackend.translate(typeSystem, substitute(arg));
    final List<Object> rows = connection.execute(plan, elementType);
    if (collection) {
      return rows;
    }
    if (rows.size() != 1) {
      throw new SparkBackend.SparkException(
          "CONNECTION",
          "Query computes one value, but Spark returned "
              + rows.size()
              + " rows");
    }
    return rows.get(0);
  }

  /**
   * Returns the query with an argument in place of its parameters.
   *
   * <p>One parameter takes the whole argument; several take its components, one
   * each, because an argument of several values is a tuple and a tuple is a
   * list of them.
   */
  private Core.Exp substitute(Object arg) {
    final Map<Core.IdPat, Object> values = new LinkedHashMap<>();
    if (parameters.size() == 1) {
      values.put(parameters.get(0), arg);
    } else {
      final List<?> args = (List<?>) arg;
      forEach(parameters, args, values::put);
    }
    return rel.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Id id) {
            final Object value = values.get(id.idPat);
            return value == null ? id : literal(id, value);
          }
        });
  }

  /** Returns a literal of the type of {@code id} whose value is {@code arg}. */
  private static Core.Exp literal(Core.Id id, Object arg) {
    if (id.type instanceof PrimitiveType) {
      return core.literal((PrimitiveType) id.type, arg);
    }
    // A decimal, say. A value literal holds the value as it is, and remembers
    // the expression it stands for, which is the parameter.
    return core.valueLiteral(id, arg);
  }
}

// End PreparedPlan.java
