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

import java.util.Map;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import org.jspecify.annotations.Nullable;

/**
 * The Spark schema of a Morel type, as the JSON that a {@code LocalRelation}
 * carries.
 *
 * <p>This is the direction {@link SparkTypes} does not go. Spark to Morel loses
 * information -- {@code byte}, {@code short}, {@code int} and {@code long} all
 * arrive as {@code int} -- so Morel to Spark must choose, and it chooses the
 * widest type that Morel can hold without loss: {@code int} becomes {@code
 * integer} and {@code real} becomes {@code double}.
 *
 * <p>Nullability is the other difference. A Spark column is nullable or not; a
 * Morel type says so by being an {@code option}. So {@code int} is a
 * non-nullable {@code integer} and {@code int option} a nullable one, and a
 * type that is an option of an option cannot be expressed.
 */
public class SparkSchema {
  private SparkSchema() {}

  /**
   * Returns the JSON schema of a row type, as {@code LocalRelation.schema}
   * wants it: an object whose {@code fields} are the record's fields, in the
   * order the record type holds them, which is sorted by name.
   */
  public static String schemaJson(Type rowType) {
    if (!(rowType instanceof RecordType)) {
      throw new SparkBackend.SparkException(
          "UNSUPPORTED_TYPE",
          "A relation's rows must be records, and these are " + rowType);
    }
    final StringBuilder b = new StringBuilder();
    struct((RecordType) rowType, b);
    return b.toString();
  }

  private static void struct(RecordType recordType, StringBuilder b) {
    b.append("{\"fields\":[");
    int i = 0;
    for (Map.Entry<String, Type> field : recordType.argNameTypes.entrySet()) {
      if (i++ > 0) {
        b.append(',');
      }
      b.append("{\"metadata\":{},\"name\":");
      quote(field.getKey(), b);
      final Type type = field.getValue();
      final Type valueType = optionValueType(type);
      b.append(",\"nullable\":").append(valueType != null).append(",\"type\":");
      type(valueType != null ? valueType : type, b);
      b.append('}');
    }
    b.append("],\"type\":\"struct\"}");
  }

  private static void type(Type type, StringBuilder b) {
    if (type instanceof RecordType) {
      struct((RecordType) type, b);
      return;
    }
    if (type instanceof ListType) {
      final Type elementType = ((ListType) type).elementType;
      final Type valueType = optionValueType(elementType);
      b.append("{\"containsNull\":")
          .append(valueType != null)
          .append(",\"elementType\":");
      type(valueType != null ? valueType : elementType, b);
      b.append(",\"type\":\"array\"}");
      return;
    }
    quote(primitive(type), b);
  }

  /** Precision of an uploaded decimal column: Spark's own default, 38. */
  static final int DECIMAL_PRECISION = 38;

  /** Scale of an uploaded decimal column: Spark's own default, 18. */
  static final int DECIMAL_SCALE = 18;

  /** Appends a JSON string literal. */
  private static void quote(String s, StringBuilder b) {
    b.append('"');
    for (int i = 0; i < s.length(); i++) {
      final char c = s.charAt(i);
      switch (c) {
        case '"':
        case '\\':
          b.append('\\').append(c);
          break;
        default:
          b.append(c);
      }
    }
    b.append('"');
  }

  private static String primitive(Type type) {
    if (type.moniker().equals("decimal")) {
      // Morel's decimal has no precision or scale of its own -- it is
      // decimal128, 34 significant digits, and each value carries its own
      // scale -- so a column of them has to be given one. This is the width
      // Spark itself gives a column it infers from a BigDecimal
      // (DecimalType.SYSTEM_DEFAULT), and a value too wide for it is refused
      // rather than rounded.
      return "decimal(" + DECIMAL_PRECISION + "," + DECIMAL_SCALE + ")";
    }
    if (type instanceof PrimitiveType) {
      switch ((PrimitiveType) type) {
        case BOOL:
          return "boolean";
        case INT:
          return "integer";
        case REAL:
          return "double";
        case STRING:
          return "string";
        default:
          break;
      }
    }
    throw new SparkBackend.SparkException(
        "UNSUPPORTED_TYPE", "Morel type " + type + " has no Spark equivalent");
  }

  /**
   * Returns the type an {@code option} wraps, or null if {@code type} is not an
   * option. The translator asks too, of a join's absent side. An option of an
   * option has no Spark equivalent, since a column is nullable or not, and is
   * rejected rather than flattened.
   */
  static @Nullable Type optionValueType(Type type) {
    if (type instanceof DataType && ((DataType) type).name.equals("option")) {
      final Type valueType = ((DataType) type).arguments.get(0);
      if (optionValueType(valueType) != null) {
        throw new SparkBackend.SparkException(
            "UNSUPPORTED_TYPE",
            "Morel type " + type + " is an option of an option");
      }
      return valueType;
    }
    return null;
  }
}

// End SparkSchema.java
