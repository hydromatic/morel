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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.apache.spark.connect.proto.DataType;
import org.junit.jupiter.api.Test;

/** Tests {@link SparkTypes}, the mapping from Spark types to Morel types. */
public class SparkTypesTest {
  private static final DataType INT =
      DataType.newBuilder()
          .setInteger(DataType.Integer.getDefaultInstance())
          .build();
  private static final DataType LONG =
      DataType.newBuilder().setLong(DataType.Long.getDefaultInstance()).build();
  private static final DataType STRING =
      DataType.newBuilder()
          .setString(DataType.String.getDefaultInstance())
          .build();
  private static final DataType BOOL =
      DataType.newBuilder()
          .setBoolean(DataType.Boolean.getDefaultInstance())
          .build();
  private static final DataType DOUBLE =
      DataType.newBuilder()
          .setDouble(DataType.Double.getDefaultInstance())
          .build();
  private static final DataType DECIMAL =
      DataType.newBuilder()
          .setDecimal(
              DataType.Decimal.newBuilder().setPrecision(10).setScale(2))
          .build();
  private static final DataType BINARY =
      DataType.newBuilder()
          .setBinary(DataType.Binary.getDefaultInstance())
          .build();
  private static final DataType DATE =
      DataType.newBuilder().setDate(DataType.Date.getDefaultInstance()).build();
  private static final DataType TIMESTAMP =
      DataType.newBuilder()
          .setTimestamp(DataType.Timestamp.getDefaultInstance())
          .build();
  private static final DataType TIMESTAMP_NTZ =
      DataType.newBuilder()
          .setTimestampNtz(DataType.TimestampNTZ.getDefaultInstance())
          .build();
  private static final DataType MAP =
      DataType.newBuilder()
          .setMap(
              DataType.Map.newBuilder().setKeyType(STRING).setValueType(INT))
          .build();

  private static DataType array(DataType element, boolean containsNull) {
    return DataType.newBuilder()
        .setArray(
            DataType.Array.newBuilder()
                .setElementType(element)
                .setContainsNull(containsNull))
        .build();
  }

  private static DataType.StructField field(
      String name, DataType type, boolean nullable) {
    return DataType.StructField.newBuilder()
        .setName(name)
        .setDataType(type)
        .setNullable(nullable)
        .build();
  }

  private static DataType struct(DataType.StructField... fields) {
    final DataType.Struct.Builder b = DataType.Struct.newBuilder();
    for (DataType.StructField field : fields) {
      b.addFields(field);
    }
    return DataType.newBuilder().setStruct(b).build();
  }

  private static TypeSystem typeSystem() {
    final TypeSystem typeSystem = new TypeSystem();
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    return typeSystem;
  }

  @Test
  void testRowType() {
    final TypeSystem ts = typeSystem();
    // A column maps to the plain type whether or not it is nullable; a
    // nested nullable value maps to option. Fields are sorted by label.
    final DataType schema =
        struct(
            field("id", INT, false),
            field("s", STRING, true),
            field("big", LONG, true),
            field("x", DOUBLE, true),
            field("dec", DECIMAL, true),
            field("b", BOOL, true),
            field("bin", BINARY, true),
            field("d", DATE, true),
            field("ts", TIMESTAMP, true),
            field("ntz", TIMESTAMP_NTZ, true),
            field("arr", array(INT, true), true),
            field("arr2", array(STRING, false), false),
            field(
                "st",
                struct(field("a", INT, true), field("b", STRING, false)),
                true));
    final Type rowType = SparkTypes.rowType(ts, schema);
    assertThat(
        rowType.moniker(),
        is(
            "{arr:int option list, arr2:string list, b:bool, big:int,"
                + " bin:word list, d:date, dec:decimal, id:int, ntz:date,"
                + " s:string, st:{a:int option, b:string}, ts:time, x:real}"));
  }

  @Test
  void testStrict() {
    final TypeSystem ts = typeSystem();
    assertThat(
        SparkTypes.toType(ts, INT, true, true).moniker(), is("int option"));
    assertThat(SparkTypes.toType(ts, INT, true, false).moniker(), is("int"));
    assertThat(SparkTypes.toType(ts, INT, false, true).moniker(), is("int"));
    assertThat(
        SparkTypes.toType(ts, array(INT, true), true, true).moniker(),
        is("int option list option"));
  }

  @Test
  void testRejected() {
    final TypeSystem ts = typeSystem();
    final SparkBackend.SparkException e =
        assertThrows(
            SparkBackend.SparkException.class,
            () -> SparkTypes.rowType(ts, struct(field("m", MAP, true))));
    assertThat(e.errorClass, is("UNSUPPORTED_TYPE"));
    assertThat(
        e.getMessage(),
        is("Spark type map<string,integer> has no Morel equivalent"));
    final SparkBackend.SparkException e2 =
        assertThrows(
            SparkBackend.SparkException.class,
            () -> SparkTypes.rowType(ts, INT));
    assertThat(e2.getMessage(), is("Expected a struct, got integer"));
    final SparkBackend.SparkException e3 =
        assertThrows(
            SparkBackend.SparkException.class,
            () ->
                SparkTypes.rowType(
                    ts, struct(field("a", INT, true), field("a", INT, true))));
    assertThat(e3.getMessage(), is("Duplicate field name a"));
  }

  /**
   * Tests that a column declared nullable maps to an option, and that one not
   * declared keeps the plain type.
   *
   * <p>Spark's own flag cannot tell them apart: it is true for every column of
   * a stored table, whatever the DDL said, so the declaration is the only thing
   * that distinguishes `mgr`, which may be null, from `empno`, which may not.
   */
  @Test
  void testDeclaredNullableColumn() {
    final TypeSystem ts = typeSystem();
    final DataType schema =
        struct(
            field("empno", INT, true),
            field("mgr", INT, true),
            field("ename", STRING, true));
    assertThat(
        SparkTypes.rowType(ts, schema).moniker(),
        is("{empno:int, ename:string, mgr:int}"));
    assertThat(
        SparkTypes.rowType(ts, schema, ImmutableSet.of("mgr")).moniker(),
        is("{empno:int, ename:string, mgr:int option}"));
  }

  /**
   * Tests that each level takes its nullability from the source that knows it:
   * a column from the declaration, a struct's field from Spark's flag.
   *
   * <p>So a nullable column that nobody declared is plain, while a nullable
   * field of a struct in that same column is an option -- the flag being
   * recorded one level down and merely assumed at the top.
   */
  @Test
  void testNullabilityPerLevel() {
    final TypeSystem ts = typeSystem();
    final DataType schema =
        struct(
            field("a", INT, true),
            field(
                "st",
                struct(field("x", INT, true), field("y", STRING, false)),
                true));
    assertThat(
        SparkTypes.rowType(ts, schema).moniker(),
        is("{a:int, st:{x:int option, y:string}}"));
    // Declaring the column makes the column an option; its fields are
    // unaffected, because the flag already decided them.
    assertThat(
        SparkTypes.rowType(ts, schema, ImmutableSet.of("a", "st")).moniker(),
        is("{a:int option, st:{x:int option, y:string} option}"));
  }

  /**
   * Tests that a declaration of {@code *} makes every column of the row an
   * option.
   *
   * <p>It is what a connection says when it has been told nothing about the
   * tables it is reading and would rather unwrap a column than have one raise:
   * Spark's own flag says the same thing, and means nothing by it, so this is a
   * choice the connection makes rather than a fact it learned.
   */
  @Test
  void testDeclaredAll() {
    final TypeSystem ts = typeSystem();
    final DataType schema =
        struct(field("a", INT, true), field("b", STRING, true));
    assertThat(
        SparkTypes.rowType(ts, schema, ImmutableSet.of("*")).moniker(),
        is("{a:int option, b:string option}"));
    // A column named in a declaration that also has "*" is an option once.
    assertThat(
        SparkTypes.rowType(ts, schema, ImmutableSet.of("*", "a")).moniker(),
        is("{a:int option, b:string option}"));
  }

  /**
   * Tests that a declaration matches a column name whatever its case.
   *
   * <p>A declaration is written by a person, and Spark compares identifiers
   * without regard to case, so {@code comm} must name the column {@code Comm}.
   * It is held in lower case, and so the name it is compared with must be.
   */
  @Test
  void testDeclaredMixedCase() {
    final TypeSystem ts = typeSystem();
    final DataType schema =
        struct(field("Comm", INT, true), field("EmpNo", INT, true));
    assertThat(
        SparkTypes.rowType(ts, schema, ImmutableSet.of("comm")).moniker(),
        is("{Comm:int option, EmpNo:int}"));
  }

  @Test
  void testDescribe() {
    assertThat(SparkTypes.describe(DECIMAL), is("decimal(10,2)"));
    assertThat(
        SparkTypes.describe(struct(field("a", array(INT, true), true))),
        is("struct<a:array<integer>>"));
  }
}

// End SparkTypesTest.java
