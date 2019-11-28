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
package net.hydromatic.sml;

import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.schema.SchemaPlus;

import com.google.common.base.Suppliers;

import net.hydromatic.foodmart.data.hsqldb.FoodmartHsqldb;
import net.hydromatic.scott.data.hsqldb.ScottHsqldb;
import net.hydromatic.sml.foreign.CalciteForeignValue;
import net.hydromatic.sml.foreign.ForeignValue;
import net.hydromatic.sml.util.Pair;

import java.util.AbstractMap;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;

/** Data sets for testing. */
enum DataSet {
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
    SchemaPlus schema() {
      return FOODMART_SCHEMA.get();
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
    SchemaPlus schema() {
      return SCOTT_SCHEMA.get();
    }
  };

  /** Returns the Calcite schema of this data set. */
  abstract SchemaPlus schema();

  /** Returns this data set as a foreign value. */
  ForeignValue foreignValue() {
    return new CalciteForeignValue(schema(), true);
  }

  /** Supplier for the root schema. */
  private static final Supplier<SchemaPlus> ROOT_SCHEMA =
      Suppliers.memoize(() -> CalciteSchema.createRootSchema(false).plus());

  /** Supplier for the Scott schema. */
  private static final Supplier<SchemaPlus> SCOTT_SCHEMA =
      Suppliers.memoize(() -> {
        final DataSource dataSource =
            JdbcSchema.dataSource(ScottHsqldb.URI, null, ScottHsqldb.USER,
                ScottHsqldb.PASSWORD);
        final String name = "scott";
        final SchemaPlus rootSchema = ROOT_SCHEMA.get();
        final JdbcSchema schema =
            JdbcSchema.create(rootSchema, name, dataSource, null, "SCOTT");
        return rootSchema.add(name, schema);
      });

  /** Supplier for the Foodmart schema. */
  private static final Supplier<SchemaPlus> FOODMART_SCHEMA =
      Suppliers.memoize(() -> {
        final DataSource dataSource =
            JdbcSchema.dataSource(FoodmartHsqldb.URI, null, FoodmartHsqldb.USER,
                FoodmartHsqldb.PASSWORD);
        final String name = "foodmart";
        final SchemaPlus rootSchema = ROOT_SCHEMA.get();
        final JdbcSchema schema =
            JdbcSchema.create(rootSchema, name, dataSource, null, "foodmart");
        return rootSchema.add(name, schema);
      });

  /** Returns a map of all known data sets.
   *
   * <p>Contains "foodmart" and "scott". */
  public static class Dictionary extends AbstractMap<String, ForeignValue> {
    public Set<Entry<String, ForeignValue>> entrySet() {
      return Stream.of(DataSet.values())
          .map(dataSet ->
              Pair.of(dataSet.name().toLowerCase(Locale.ROOT),
                  dataSet.foreignValue()))
          .collect(Collectors.toSet());
    }
  }
}

// End DataSet.java
