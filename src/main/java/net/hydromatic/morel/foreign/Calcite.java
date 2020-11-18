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

import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

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
