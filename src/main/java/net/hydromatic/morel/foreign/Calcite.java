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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Describer;
import net.hydromatic.morel.eval.EvalEnv;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.util.ThreadLocals;
import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.interpreter.Interpreter;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.plan.RelOptLattice;
import org.apache.calcite.plan.RelOptMaterialization;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.metadata.DefaultRelMetadataProvider;
import org.apache.calcite.rel.type.DelegatingTypeSystem;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql2rel.RelDecorrelator;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Program;
import org.apache.calcite.tools.Programs;
import org.apache.calcite.tools.RelBuilder;

/** Runtime context. */
public class Calcite {
  final RelBuilder relBuilder;
  final JavaTypeFactory typeFactory;
  public final SchemaPlus rootSchema;
  public final DataContext dataContext;

  protected Calcite() {
    rootSchema = CalciteSchema.createRootSchema(false).plus();
    relBuilder =
        RelBuilder.create(
            Frameworks.newConfigBuilder()
                .typeSystem(new RaggedTypeSystem())
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

  /**
   * Creates a {@code Code} that evaluates a Calcite relational expression,
   * converting it to Morel list type {@code type}.
   */
  public Code code(Environment env, RelNode rel, Type type) {
    // Transform the relational expression, converting sub-queries. For example,
    // RexSubQuery.IN becomes a Join.
    final Program program =
        Programs.sequence(
            Programs.subQuery(DefaultRelMetadataProvider.INSTANCE),
            new DecorrelateProgram());
    final RelOptPlanner planner = rel.getCluster().getPlanner();
    final RelTraitSet traitSet = rel.getCluster().traitSet();
    final RelNode rel2 =
        program.run(
            planner, rel, traitSet, ImmutableList.of(), ImmutableList.of());

    final Function<Enumerable<Object[]>, List<Object>> converter =
        Converters.fromEnumerable(rel, type);
    return new CalciteCode(dataContext, rel2, env, converter);
  }

  /** Copied from {@link Programs}. */
  private static class DecorrelateProgram implements Program {
    @Override
    public RelNode run(
        RelOptPlanner planner,
        RelNode rel,
        RelTraitSet requiredOutputTraits,
        List<RelOptMaterialization> materializations,
        List<RelOptLattice> lattices) {
      final CalciteConnectionConfig config =
          planner
              .getContext()
              .maybeUnwrap(CalciteConnectionConfig.class)
              .orElse(CalciteConnectionConfig.DEFAULT);
      if (config.forceDecorrelate()) {
        final RelBuilder relBuilder =
            RelFactories.LOGICAL_BUILDER.create(rel.getCluster(), null);
        return RelDecorrelator.decorrelateQuery(rel, relBuilder);
      }
      return rel;
    }
  }

  /**
   * Extension to Calcite context that remembers the foreign value for each
   * name.
   */
  private static class CalciteMap extends Calcite {
    final ImmutableMap<String, ForeignValue> valueMap;

    CalciteMap(Map<String, DataSet> dataSetMap) {
      final ImmutableMap.Builder<String, ForeignValue> b =
          ImmutableMap.builder();
      dataSetMap.forEach(
          (name, dataSet) -> b.put(name, dataSet.foreignValue(this)));
      this.valueMap = b.build();
    }

    @Override
    public Map<String, ForeignValue> foreignValues() {
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

  /**
   * Evaluates a Calcite relational expression, converting it to Morel list type
   * {@code type}.
   */
  private static class CalciteCode implements Code {
    final DataContext dataContext;
    final RelNode rel;
    final Environment env;
    final Function<Enumerable<Object[]>, List<Object>> converter;

    CalciteCode(
        DataContext dataContext,
        RelNode rel,
        Environment env,
        Function<Enumerable<Object[]>, List<Object>> converter) {
      this.dataContext = dataContext;
      this.rel = rel;
      this.env = env;
      this.converter = converter;
    }

    // to help with debugging
    @Override
    public String toString() {
      return Codes.describe(this);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "calcite", d -> d.arg("plan", RelOptUtil.toString(rel)));
    }

    @Override
    public Object eval(EvalEnv evalEnv) {
      return ThreadLocals.let(
          CalciteFunctions.THREAD_EVAL_ENV,
          evalEnv,
          () ->
              ThreadLocals.mutate(
                  CalciteFunctions.THREAD_ENV,
                  c -> c.withEnv(env),
                  () -> {
                    final Interpreter interpreter =
                        new Interpreter(dataContext, rel);
                    return converter.apply(interpreter);
                  }));
    }
  }

  /**
   * Type system whose {@link #shouldConvertRaggedUnionTypesToVarying()} returns
   * {@code true}.
   *
   * <p>Calcite requires it to have a public default constructor.
   */
  public static class RaggedTypeSystem extends DelegatingTypeSystem {
    public RaggedTypeSystem() {
      super(RelDataTypeSystem.DEFAULT);
    }

    public boolean shouldConvertRaggedUnionTypesToVarying() {
      return true;
    }
  }
}

// End Calcite.java
