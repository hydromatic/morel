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

import java.util.ArrayList;
import java.util.SortedMap;
import java.util.TreeMap;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/** Tests {@link SparkSchema}, the Spark schema of a Morel type. */
public class SparkSchemaTest {
  private final TypeSystem typeSystem = typeSystem();

  /**
   * Returns a type system that knows the built-in datatypes, so that {@code
   * option} can be looked up.
   */
  private static TypeSystem typeSystem() {
    final TypeSystem typeSystem = new TypeSystem();
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    return typeSystem;
  }

  private Type record(Object... nameTypes) {
    final SortedMap<String, Type> fields = new TreeMap<>();
    for (int i = 0; i < nameTypes.length; i += 2) {
      fields.put((String) nameTypes[i], (Type) nameTypes[i + 1]);
    }
    return typeSystem.recordType(fields);
  }

  /**
   * The row type of the first seed query's inline relation. Its schema is fixed
   * by {@code script/spark-plan-generated.smli}, which the PySpark client
   * writes too, so this is the authority on the format: field keys in
   * alphabetical order, and fields in the record type's order, which is by
   * name.
   */
  @Test
  void testSeedQueryRow() {
    final Type rowType =
        record(
            "b", PrimitiveType.BOOL,
            "i", PrimitiveType.INT,
            "s", PrimitiveType.STRING,
            "x", PrimitiveType.REAL);
    assertThat(
        SparkSchema.schemaJson(rowType),
        is(
            "{\"fields\":["
                + "{\"metadata\":{},\"name\":\"b\",\"nullable\":false,"
                + "\"type\":\"boolean\"},"
                + "{\"metadata\":{},\"name\":\"i\",\"nullable\":false,"
                + "\"type\":\"integer\"},"
                + "{\"metadata\":{},\"name\":\"s\",\"nullable\":false,"
                + "\"type\":\"string\"},"
                + "{\"metadata\":{},\"name\":\"x\",\"nullable\":false,"
                + "\"type\":\"double\"}"
                + "],\"type\":\"struct\"}"));
  }

  /** Tests that an option becomes a nullable column of the value's type. */
  @Test
  void testOptionIsNullable() {
    final Type rowType = record("c", typeSystem.option(PrimitiveType.INT));
    assertThat(
        SparkSchema.schemaJson(rowType),
        is(
            "{\"fields\":[{\"metadata\":{},\"name\":\"c\","
                + "\"nullable\":true,\"type\":\"integer\"}],"
                + "\"type\":\"struct\"}"));
  }

  /** Tests a nested record and a list. */
  @Test
  void testNested() {
    final Type rowType =
        record(
            "r", record("a", PrimitiveType.INT),
            "xs", typeSystem.listType(PrimitiveType.STRING));
    assertThat(
        SparkSchema.schemaJson(rowType),
        is(
            "{\"fields\":["
                + "{\"metadata\":{},\"name\":\"r\",\"nullable\":false,"
                + "\"type\":{\"fields\":[{\"metadata\":{},\"name\":\"a\","
                + "\"nullable\":false,\"type\":\"integer\"}],"
                + "\"type\":\"struct\"}},"
                + "{\"metadata\":{},\"name\":\"xs\",\"nullable\":false,"
                + "\"type\":{\"containsNull\":false,"
                + "\"elementType\":\"string\",\"type\":\"array\"}}"
                + "],\"type\":\"struct\"}"));
  }

  /** Tests that rows must be records. */
  @Test
  void testRowMustBeRecord() {
    assertThrows(
        SparkBackend.SparkException.class,
        () -> SparkSchema.schemaJson(PrimitiveType.INT));
  }

  /** Tests that a type with no Spark equivalent is rejected. */
  @Test
  void testUnsupported() {
    final Type rowType = record("c", PrimitiveType.CHAR);
    assertThrows(
        SparkBackend.SparkException.class,
        () -> SparkSchema.schemaJson(rowType));
  }
}

// End SparkSchemaTest.java
