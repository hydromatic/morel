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

import static java.util.Objects.requireNonNull;

import java.util.AbstractMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import net.hydromatic.foodmart.data.hsqldb.FoodmartHsqldb;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.CalciteForeignValue;
import net.hydromatic.morel.foreign.CalciteForeignValue.NameConverter;
import net.hydromatic.morel.foreign.DataSet;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.scott.data.hsqldb.ScottHsqldb;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.commons.dbcp2.BasicDataSource;

/** Data sets for testing. */
enum BuiltInDataSet implements DataSet {
  /**
   * Returns a value based on the Foodmart JDBC database.
   *
   * <p>It is a record with fields for the following tables (and a few others):
   *
   * <ul>
   *   <li>{@code days} - Table with days of the week (7 rows)
   *   <li>{@code customer} - Customer dimension table (10,281 rows)
   *   <li>{@code product} - Product dimension table (1,560 rows)
   *   <li>{@code product_class} - Product snowflake dimension table (110 rows)
   *   <li>{@code promotion} - Promotion dimension table (1,864 rows)
   *   <li>{@code time_by_day} - Date dimension table (730 rows, 2 years)
   *   <li>{@code inventory_fact_1997} - Inventory fact table, 1997 (4,070 rows)
   *   <li>{@code sales_fact_1997} - Sales fact table, 1997 (86,837 rows)
   *   <li>{@code sales_fact_1998} - Sales fact table, 1998 (164,558 rows)
   *   <li>{@code sales_fact_dec_1998} - Sales fact table, Dec 1998 (18,325
   *       rows)
   *   <li>{@code warehouse} - Warehouse dimension table (24 rows)
   * </ul>
   */
  FOODMART("foodmart", NameConverter.TO_LOWER) {
    SchemaPlus schema(SchemaPlus rootSchema) {
      final DataSource dataSource =
          createDataSource(
              FoodmartHsqldb.URI, FoodmartHsqldb.USER, FoodmartHsqldb.PASSWORD);
      String hsqldbSchema = "foodmart";
      final JdbcSchema schema =
          JdbcSchema.create(
              rootSchema, schemaName, dataSource, null, hsqldbSchema);
      return rootSchema.add(schemaName, schema);
    }
  },

  /**
   * Returns a value based on the Scott JDBC database.
   *
   * <p>It is a record with fields for the following tables:
   *
   * <ul>
   *   <li>{@code bonuses} - Bonus table
   *   <li>{@code depts} - Departments table
   *   <li>{@code emps} - Employees table
   *   <li>{@code salgrades} - Salary grade table
   * </ul>
   *
   * <p>The underlying tables are {@code BONUS}, {@code DEPT}, {@code EMP},
   * {@code SALGRADE}.
   */
  SCOTT("scott", BuiltInDataSet::scottNameConverter) {
    SchemaPlus schema(SchemaPlus rootSchema) {
      final DataSource dataSource =
          createDataSource(
              ScottHsqldb.URI, ScottHsqldb.USER, ScottHsqldb.PASSWORD);
      final String hsqldbSchema = "SCOTT";
      final JdbcSchema schema =
          JdbcSchema.create(
              rootSchema, schemaName, dataSource, null, hsqldbSchema);
      return rootSchema.add(schemaName, schema);
    }
  };

  /**
   * Map of all known data sets.
   *
   * <p>Contains "foodmart" and "scott".
   */
  static final Map<String, DataSet> DICTIONARY =
      Stream.of(BuiltInDataSet.values())
          .collect(
              Collectors.toMap(d -> d.name().toLowerCase(Locale.ROOT), d -> d));

  /**
   * Creates a DataSource with connection pool settings suitable for parallel
   * test execution.
   *
   * <p>Uses a larger pool size (50) to handle concurrent test threads, and a
   * borrow timeout (5 seconds) so tests fail fast instead of hanging forever if
   * the pool is exhausted.
   */
  private static DataSource createDataSource(
      String url, String user, String password) {
    final BasicDataSource dataSource = new BasicDataSource();
    dataSource.setUrl(url);
    dataSource.setUsername(user);
    dataSource.setPassword(password);
    dataSource.setMaxTotal(50);
    dataSource.setMaxWaitMillis(5000);
    return dataSource;
  }

  final String schemaName;
  final NameConverter nameConverter;

  BuiltInDataSet(String schemaName, NameConverter nameConverter) {
    this.schemaName = requireNonNull(schemaName);
    this.nameConverter = requireNonNull(nameConverter);
  }

  /** Returns the Calcite schema of this data set. */
  abstract SchemaPlus schema(SchemaPlus rootSchema);

  @Override
  public ForeignValue foreignValue(Calcite calcite) {
    SchemaPlus schema = schema(calcite.rootSchema);
    return new CalciteForeignValue(calcite, schema, nameConverter);
  }

  /** Creates a {@link NameConverter} for the "scott" database. */
  private static String scottNameConverter(List<String> path, String name) {
    switch (name) {
      case "EMP":
        return "emps";
      case "DEPT":
        return "depts";
      case "BONUS":
        return "bonuses";
      case "SALGRADE":
        return "salgrades";
      default:
        return name.toLowerCase(Locale.ROOT);
    }
  }

  /**
   * Map of built-in data sets.
   *
   * <p>Typically passed to {@link Shell} via the {@code --foreign} argument.
   */
  @SuppressWarnings("unused")
  public static class Dictionary extends AbstractMap<String, DataSet> {
    @Override
    public Set<Entry<String, DataSet>> entrySet() {
      return DICTIONARY.entrySet();
    }
  }
}

// End BuiltInDataSet.java
