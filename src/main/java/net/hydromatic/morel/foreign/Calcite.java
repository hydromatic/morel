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

import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Describer;
import net.hydromatic.morel.eval.EvalEnv;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.util.ThreadLocals;

import com.google.common.collect.ImmutableMap;
import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.interpreter.Interpreter;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Runtime context. */
public class Calcite {
  final RelBuilder relBuilder;
  final JavaTypeFactory typeFactory;
  public final SchemaPlus rootSchema;
  public final DataContext dataContext;

  protected Calcite() {
    rootSchema = CalciteSchema.createRootSchema(false).plus();
    relBuilder = RelBuilder.create(Frameworks.newConfigBuilder()
        .defaultSchema(rootSchema)
        .build());
    typeFactory = (JavaTypeFactory) relBuilder.getTypeFactory();
    dataContext = new EmptyDataContext(typeFactory, rootSchema);
  }

  /** Returns foreign values. */
  public Map<String, ForeignValue> foreignValues() {
    return ImmutableMap.of();
  }

  /** Creates a runtime context with the given data sets. */
  public static Calcite withDataSets(Map<String, DataSet> dataSetMap) {
    return new CalciteMap(dataSetMap);
  }

  /** Creates an empty RelBuilder. */
  public RelBuilder relBuilder() {
    return relBuilder.transform(c -> c);
  }

  /** Creates a {@code Code} that evaluates a Calcite relational expression,
   * converting it to Morel list type {@code type}. */
  public Code code(Environment env, RelNode rel, Type type) {
    final Function<Enumerable<Object[]>, List<Object>> converter =
        Converters.fromEnumerable(rel, type);
    return new Code() {
      @Override public Describer describe(Describer describer) {
        return describer.start("calcite", d ->
            d.arg("plan", RelOptUtil.toString(rel)));
      }

      @Override public Object eval(EvalEnv evalEnv) {
        return ThreadLocals.let(CalciteFunctions.THREAD_EVAL_ENV,
            evalEnv, () ->
                ThreadLocals.mutate(CalciteFunctions.THREAD_ENV,
                    c -> c.withEnv(env),
                    () -> {
                      final Interpreter interpreter =
                          new Interpreter(dataContext, rel);
                      return converter.apply(interpreter);
                    }));
      }
    };
  }

  /** Extension to Calcite context that remembers the foreign value
   * for each name. */
  private static class CalciteMap extends Calcite {
    final ImmutableMap<String, ForeignValue> valueMap;

    CalciteMap(Map<String, DataSet> dataSetMap) {
      final ImmutableMap.Builder<String, ForeignValue> b =
          ImmutableMap.builder();
      dataSetMap.forEach((name, dataSet) ->
          b.put(name, dataSet.foreignValue(this)));
      this.valueMap = b.build();
    }

    @Override public Map<String, ForeignValue> foreignValues() {
      return valueMap;
    }
  }

  /** Data context that has no variables. */
  private static class EmptyDataContext implements DataContext {
    private final JavaTypeFactory typeFactory;
    private final SchemaPlus rootSchema;

    EmptyDataContext(JavaTypeFactory typeFactory, SchemaPlus rootSchema) {
      this.typeFactory = typeFactory;
      this.rootSchema = rootSchema;
    }

    public SchemaPlus getRootSchema() {
      return rootSchema;
    }

    public JavaTypeFactory getTypeFactory() {
      return typeFactory;
    }

    public QueryProvider getQueryProvider() {
      throw new UnsupportedOperationException();
    }

    public Object get(String name) {
      return null;
    }
  }
}

// End Calcite.java
