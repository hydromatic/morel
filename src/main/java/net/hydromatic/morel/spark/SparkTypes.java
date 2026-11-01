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

import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.apache.spark.connect.proto.DataType;

/**
 * Maps Spark types to Morel types.
 *
 * <p>A nullable type maps to {@code option}, except at the top level of a row,
 * where Spark's flag is true of every column of a stored table and therefore
 * says nothing; there, a column is an option only if something declared it so.
 */
public class SparkTypes {
  private SparkTypes() {}

  /** Returns the Morel type of a row of the given struct type. */
  public static Type rowType(TypeSystem typeSystem, DataType schema) {
    return rowType(typeSystem, schema, ImmutableSet.of());
  }

  /**
   * Returns the Morel type of a row of the given struct type, where the columns
   * named in {@code nullableColumns} are declared nullable and therefore map to
   * {@code option}.
   */
  public static Type rowType(
      TypeSystem typeSystem, DataType schema, Set<String> nullableColumns) {
    if (!schema.hasStruct()) {
      throw new SparkBackend.SparkException(
          "UNSUPPORTED_TYPE", "Expected a struct, got " + describe(schema));
    }
    return struct(typeSystem, schema.getStruct(), false, nullableColumns);
  }

  /**
   * Returns the Morel type of a Spark type.
   *
   * @param typeSystem Type system
   * @param dataType Spark type
   * @param nullable Whether the value may be null
   * @param strict Whether a nullable value maps to {@code option}; false for a
   *     table column, true inside an array, struct or map
   */
  public static Type toType(
      TypeSystem typeSystem,
      DataType dataType,
      boolean nullable,
      boolean strict) {
    final Type type = toType(typeSystem, dataType);
    return nullable && strict ? typeSystem.option(type) : type;
  }

  private static Type toType(TypeSystem typeSystem, DataType t) {
    switch (t.getKindCase()) {
      case BOOLEAN:
        return PrimitiveType.BOOL;
      case BYTE:
      case SHORT:
      case INTEGER:
      case LONG:
        return PrimitiveType.INT;
      case FLOAT:
      case DOUBLE:
        return PrimitiveType.REAL;
      case DECIMAL:
        return typeSystem.lookup(BuiltIn.Eqtype.DECIMAL);
      case STRING:
      case CHAR:
      case VAR_CHAR:
        return PrimitiveType.STRING;
      case BINARY:
        return typeSystem.listType(PrimitiveType.WORD);
      case DATE:
      case TIMESTAMP_NTZ:
        return typeSystem.lookup(BuiltIn.Eqtype.DATE);
      case TIMESTAMP:
        return typeSystem.lookup(BuiltIn.Eqtype.TIME);
      case ARRAY:
        final DataType.Array array = t.getArray();
        return typeSystem.listType(
            toType(
                typeSystem,
                array.getElementType(),
                array.getContainsNull(),
                true));
      case STRUCT:
        return struct(typeSystem, t.getStruct(), true);
      default:
        throw new SparkBackend.SparkException(
            "UNSUPPORTED_TYPE",
            "Spark type " + describe(t) + " has no Morel equivalent");
    }
  }

  private static Type struct(
      TypeSystem typeSystem, DataType.Struct struct, boolean strict) {
    return struct(typeSystem, struct, strict, ImmutableSet.of());
  }

  private static Type struct(
      TypeSystem typeSystem,
      DataType.Struct struct,
      boolean strict,
      Set<String> nullableColumns) {
    final SortedMap<String, Type> fields = new TreeMap<>();
    for (DataType.StructField field : struct.getFieldsList()) {
      if (fields.containsKey(field.getName())) {
        throw new SparkBackend.SparkException(
            "UNSUPPORTED_TYPE", "Duplicate field name " + field.getName());
      }
      // One level down -- inside a struct, an array or a map -- Spark's flag
      // is recorded rather than inferred, so believe it; at the top level of
      // a row it is true of everything, so believe the declaration instead.
      // "*" is a declaration of every column.
      final boolean nullable =
          strict
              ? field.getNullable()
              : nullableColumns.contains("*")
                  || nullableColumns.contains(
                      field.getName().toLowerCase(Locale.ROOT));
      fields.put(
          field.getName(),
          toType(typeSystem, field.getDataType(), nullable, true));
    }
    return typeSystem.recordType(fields);
  }

  /**
   * Describes a Spark type, for error messages, e.g. {@code map<string,int>}.
   */
  public static String describe(DataType t) {
    switch (t.getKindCase()) {
      case DECIMAL:
        return "decimal("
            + t.getDecimal().getPrecision()
            + ","
            + t.getDecimal().getScale()
            + ")";
      case ARRAY:
        return "array<" + describe(t.getArray().getElementType()) + ">";
      case MAP:
        return "map<"
            + describe(t.getMap().getKeyType())
            + ","
            + describe(t.getMap().getValueType())
            + ">";
      case STRUCT:
        final StringBuilder b = new StringBuilder("struct<");
        final List<DataType.StructField> fields = t.getStruct().getFieldsList();
        for (int i = 0; i < fields.size(); i++) {
          if (i > 0) {
            b.append(",");
          }
          b.append(fields.get(i).getName())
              .append(":")
              .append(describe(fields.get(i).getDataType()));
        }
        return b.append(">").toString();
      default:
        return t.getKindCase().name().toLowerCase(Locale.ROOT);
    }
  }
}

// End SparkTypes.java
