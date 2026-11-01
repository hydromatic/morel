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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.BuiltInDataSet;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link MockSparkConnection}, the offline Spark connection, and {@link
 * SparkCatalog}, the catalog as a progressively typed record, over the scott
 * data set.
 */
public class SparkCatalogTest {
  private static final String EMPS_TYPE =
      "{comm:decimal option, deptno:int, empno:int, ename:string,"
          + " hiredate:string, job:string, mgr:int option, sal:decimal}"
          + " bag";

  private static TypeSystem typeSystem() {
    final TypeSystem typeSystem = new TypeSystem();
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    return typeSystem;
  }

  private static Map<String, ForeignValue> scott() {
    return Calcite.withDataSets(ImmutableMap.of("scott", BuiltInDataSet.SCOTT))
        .foreignValues();
  }

  @Test
  void testMockConnection() {
    final TypeSystem ts = typeSystem();
    try (SparkBackend.Connection c = SparkBackend.open(ts, scott(), "mock:")) {
      assertThat(c.uri(), is("mock:"));
      assertThat(c.databases(), is(ImmutableList.of("scott")));
      assertThat(
          c.tables("scott"),
          is(ImmutableList.of("bonuses", "depts", "emps", "salgrades")));
      assertThat(c.tableType("scott", "emps").moniker(), is(EMPS_TYPE));
      assertThat(
          c.tableType("scott", "depts").moniker(),
          is("{deptno:int, dname:string, loc:string} bag"));
      assertThat(c.rows("scott", "depts").size(), is(4));
      assertThat(
          c.rows("scott", "depts").get(0),
          is(ImmutableList.of(10, "ACCOUNTING", "NEW YORK")));
      assertThat(c.rows("scott", "emps").size(), is(14));

      final SparkBackend.SparkException e =
          assertThrows(
              SparkBackend.SparkException.class, () -> c.tables("nope"));
      assertThat(e.errorClass, is("SCHEMA_NOT_FOUND"));
      final SparkBackend.SparkException e2 =
          assertThrows(
              SparkBackend.SparkException.class,
              () -> c.tableType("scott", "nope"));
      assertThat(e2.errorClass, is("TABLE_OR_VIEW_NOT_FOUND"));
      final SparkBackend.SparkException e3 =
          assertThrows(
              SparkBackend.SparkException.class, () -> c.sql("SELECT 1"));
      assertThat(e3.errorClass, is("MOCK"));
    }
  }

  @Test
  void testClosed() {
    final TypeSystem ts = typeSystem();
    final SparkBackend.Connection c = SparkBackend.open(ts, scott(), "mock:");
    c.close();
    final SparkBackend.SparkException e =
        assertThrows(SparkBackend.SparkException.class, c::databases);
    assertThat(e.errorClass, is("CLOSED"));
  }

  /** The catalog discovers databases, tables and rows as they are asked for. */
  @Test
  void testCatalog() {
    final TypeSystem ts = typeSystem();
    try (SparkBackend.Connection c = SparkBackend.open(ts, scott(), "mock:")) {
      final SparkCatalog catalog = new SparkCatalog(c);
      // The root's type names the databases; a database's type is unknown
      // until it is discovered.
      assertThat(
          catalog.typeKey().toType(ts).moniker(), is("{scott:{...}, ...}"));

      final int count0 = ts.expandCount.get();
      catalog.discoverField(ts, "scott");
      assertThat(ts.expandCount.get(), is(count0 + 1));
      assertThat(
          catalog.typeKey().toType(ts).moniker(),
          is(
              "{scott:{bonuses:{...} bag, depts:{...} bag, emps:{...} bag,"
                  + " salgrades:{...} bag, ...}, ...}"));
      // Discovering again fetches nothing more.
      catalog.discoverField(ts, "scott");
      assertThat(ts.expandCount.get(), is(count0 + 1));

      final TypedValue scott = catalog.fieldValueAs("scott", TypedValue.class);
      scott.discoverField(ts, "emps");
      assertThat(ts.expandCount.get(), is(count0 + 2));
      final TypedValue emps = scott.fieldValueAs("emps", TypedValue.class);
      assertThat(emps.typeKey().toType(ts).moniker(), is(EMPS_TYPE));
      final List<?> rows = emps.valueAs(List.class);
      assertThat(rows.size(), is(14));
      assertThat(
          catalog.typeKey().toType(ts).moniker(),
          is(
              "{scott:{bonuses:{...} bag, depts:{...} bag, emps:"
                  + EMPS_TYPE
                  + ", salgrades:{...} bag, ...}, ...}"));

      // Unknown names are ignored, as in the file system.
      catalog.discoverField(ts, "nope");
      assertThat(ts.expandCount.get(), is(count0 + 2));
    }
  }
}

// End SparkCatalogTest.java
