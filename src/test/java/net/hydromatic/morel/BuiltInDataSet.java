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

import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.schema.SchemaPlus;

import net.hydromatic.foodmart.data.hsqldb.FoodmartHsqldb;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.CalciteForeignValue;
import net.hydromatic.morel.foreign.DataSet;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.scott.data.hsqldb.ScottHsqldb;

import java.util.AbstractMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.sql.DataSource;

/** Data sets for testing. */
enum BuiltInDataSet implements DataSet {
  /** Returns a value based on the Foodmart JDBC database.
   *
   * <p>It is a record with fields for the following tables:
   *
   * <ul>
   *   <li>{@code bonus} - Bonus table
   *   <li>{@code dept} - Departments table
   *   <li>{@code emp} - Employees table
   *   <li>{@code salgrade} - Salary grade table
   * </ul>
   */
  FOODMART {
    SchemaPlus schema(SchemaPlus rootSchema) {
      final DataSource dataSource =
          JdbcSchema.dataSource(FoodmartHsqldb.URI, null, FoodmartHsqldb.USER,
              FoodmartHsqldb.PASSWORD);
      final String name = "foodmart";
      final JdbcSchema schema =
          JdbcSchema.create(rootSchema, name, dataSource, null, "foodmart");
      return rootSchema.add(name, schema);
    }
  },

  /** Returns a value based on the Scott JDBC database.
   *
   * <p>It is a record with fields for the following tables:
   *
   * <ul>
   *   <li>{@code bonus} - Bonus table
   *   <li>{@code dept} - Departments table
   *   <li>{@code emp} - Employees table
   *   <li>{@code salgrade} - Salary grade table
   * </ul>
   */
  SCOTT {
    SchemaPlus schema(SchemaPlus rootSchema) {
      final DataSource dataSource =
          JdbcSchema.dataSource(ScottHsqldb.URI, null, ScottHsqldb.USER,
              ScottHsqldb.PASSWORD);
      final String name = "scott";
      final JdbcSchema schema =
          JdbcSchema.create(rootSchema, name, dataSource, null, "SCOTT");
      return rootSchema.add(name, schema);
    }
  };

  /** Map of all known data sets.
   *
   * <p>Contains "foodmart" and "scott". */
  static final Map<String, DataSet> DICTIONARY =
      Stream.of(BuiltInDataSet.values())
          .collect(
              Collectors.toMap(d -> d.name().toLowerCase(Locale.ROOT),
                  d -> d));

  /** Returns the Calcite schema of this data set. */
  abstract SchemaPlus schema(SchemaPlus rootSchema);

  @Override public ForeignValue foreignValue(Calcite calcite) {
    return new CalciteForeignValue(calcite, schema(calcite.rootSchema), true);
  }

  /** Map of built-in data sets.
   *
   * <p>Typically passed to {@link Shell} via the {@code --foreign} argument. */
  @SuppressWarnings("unused")
  public static class Dictionary extends AbstractMap<String, DataSet> {
    @Override @Nonnull public Set<Entry<String, DataSet>> entrySet() {
      return DICTIONARY.entrySet();
    }
  }
}

// End BuiltInDataSet.java
