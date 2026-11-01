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

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Encodes Morel values as an Arrow record batch, which is how Spark Connect
 * carries the rows of a local relation.
 *
 * <p>The reverse of {@link ArrowDecoder}, and bound by the same conventions: a
 * row is a list of its fields in Morel's order (sorted by label), an {@code
 * option} is the only type that admits a null, and the Morel type decides what
 * Spark type a column has, as {@link SparkSchema} decides it for the schema
 * beside the batch. The two must agree, since the schema says what the bytes
 * mean.
 */
public class ArrowEncoder {
  private ArrowEncoder() {}

  /**
   * Encodes rows as one Arrow IPC stream, schema and all.
   *
   * @param rows Rows, each a list of fields in label order, or a value if the
   *     row type is not a record
   * @param rowType Morel type of a row
   */
  public static ByteString encode(List<Object> rows, Type rowType) {
    final Schema schema = schema(rowType);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator);
        ArrowStreamWriter writer = new ArrowStreamWriter(root, null, out)) {
      final List<Type> types = columnTypes(rowType);
      final List<FieldVector> vectors = root.getFieldVectors();
      final boolean columns = hasColumns(rowType);
      for (int c = 0; c < vectors.size(); c++) {
        final FieldVector vector = vectors.get(c);
        vector.allocateNew();
        final Type type = types.get(c);
        for (int r = 0; r < rows.size(); r++) {
          final Object row = rows.get(r);
          setValue(vector, r, columns ? ((List<?>) row).get(c) : row, type);
        }
        vector.setValueCount(rows.size());
      }
      root.setRowCount(rows.size());
      writer.start();
      writer.writeBatch();
      writer.end();
    } catch (IOException e) {
      throw new SparkBackend.SparkException(
          "CONNECTION", "Error writing Arrow data: " + e.getMessage(), e);
    }
    return ByteString.copyFrom(out.toByteArray());
  }

  /**
   * Returns whether a row of this type has columns of its own.
   *
   * <p>A primitive type answers {@code argNameTypes} as a record of no fields
   * would, so asking whether a type is record-like is not the same as asking
   * whether a row of it has columns: a collection of ints is one column, not
   * none.
   */
  static boolean hasColumns(Type rowType) {
    return rowType instanceof RecordLikeType
        && !(rowType instanceof PrimitiveType);
  }

  /** Returns the Arrow schema of a row type. */
  private static Schema schema(Type rowType) {
    final ImmutableList.Builder<Field> fields = ImmutableList.builder();
    if (hasColumns(rowType)) {
      ((RecordLikeType) rowType)
          .argNameTypes()
          .forEach((label, type) -> fields.add(field(label, type)));
    } else {
      // A collection of values rather than records; Spark names the column,
      // as it names the one column of a scalar result.
      fields.add(field("it", rowType));
    }
    return new Schema(fields.build());
  }

  /** Returns the Morel type of each column of a row type. */
  private static List<Type> columnTypes(Type rowType) {
    return hasColumns(rowType)
        ? ImmutableList.copyOf(
            ((RecordLikeType) rowType).argNameTypes().values())
        : ImmutableList.of(rowType);
  }

  /** Returns the Arrow field for one column. */
  private static Field field(String label, Type type) {
    final Type valueType = SparkSchema.optionValueType(type);
    return new Field(
        label,
        new FieldType(
            valueType != null,
            arrowType(valueType != null ? valueType : type),
            null),
        null);
  }

  /** Returns the Arrow type of a Morel type. */
  private static ArrowType arrowType(Type type) {
    if (type.moniker().equals("decimal")) {
      return new ArrowType.Decimal(
          SparkSchema.DECIMAL_PRECISION, SparkSchema.DECIMAL_SCALE, 128);
    }
    if (type instanceof PrimitiveType) {
      switch ((PrimitiveType) type) {
        case BOOL:
          return ArrowType.Bool.INSTANCE;
        case INT:
          return new ArrowType.Int(32, true);
        case REAL:
          return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
        case STRING:
          return ArrowType.Utf8.INSTANCE;
        default:
          break;
      }
    }
    throw new SparkBackend.SparkException(
        "UNSUPPORTED_TYPE", "Morel type " + type + " has no Spark equivalent");
  }

  /**
   * Returns a decimal at the scale an uploaded column has.
   *
   * <p>A Morel decimal carries its own scale, and every value of a column has
   * to carry the column's, so each is restated at that scale. A value that
   * cannot be -- more fractional digits than the scale, or more digits than the
   * precision -- is refused, because the alternative is to round it and send
   * something the program did not say.
   */
  private static BigDecimal decimal(BigDecimal value) {
    final BigDecimal scaled;
    try {
      scaled =
          value.setScale(SparkSchema.DECIMAL_SCALE, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw new SparkBackend.SparkException(
          "UNSUPPORTED_TYPE",
          "Decimal "
              + value
              + " has more digits after the point than Spark's"
              + " decimal("
              + SparkSchema.DECIMAL_PRECISION
              + ", "
              + SparkSchema.DECIMAL_SCALE
              + ") can hold");
    }
    if (scaled.precision() > SparkSchema.DECIMAL_PRECISION) {
      throw new SparkBackend.SparkException(
          "UNSUPPORTED_TYPE",
          "Decimal "
              + value
              + " has more digits than Spark's decimal("
              + SparkSchema.DECIMAL_PRECISION
              + ", "
              + SparkSchema.DECIMAL_SCALE
              + ") can hold");
    }
    return scaled;
  }

  /** Writes one value into a vector. */
  private static void setValue(
      FieldVector vector, int row, @Nullable Object value, Type type) {
    final Type valueType = SparkSchema.optionValueType(type);
    if (valueType != null) {
      // An option: NONE is the null, and SOME v is v. Both are lists, the
      // constructor first, as a datatype value always is.
      final List<?> option = (List<?>) requireNonNull(value, "option");
      if (option.size() < 2) {
        vector.setNull(row);
        return;
      }
      setValue(vector, row, option.get(1), valueType);
      return;
    }
    // An option is the only type that admits a null, and this is not one.
    final Object v = requireNonNull(value, "value");
    if (vector instanceof BitVector) {
      ((BitVector) vector).setSafe(row, (Boolean) v ? 1 : 0);
    } else if (vector instanceof IntVector) {
      ((IntVector) vector).setSafe(row, ((Number) v).intValue());
    } else if (vector instanceof Float8Vector) {
      ((Float8Vector) vector).setSafe(row, ((Number) v).doubleValue());
    } else if (vector instanceof DecimalVector) {
      ((DecimalVector) vector).setSafe(row, decimal((BigDecimal) v));
    } else if (vector instanceof VarCharVector) {
      ((VarCharVector) vector)
          .setSafe(row, v.toString().getBytes(StandardCharsets.UTF_8));
    } else {
      throw new SparkBackend.SparkException(
          "UNSUPPORTED_TYPE",
          "Morel type " + type.moniker() + " has no Spark equivalent");
    }
  }
}

// End ArrowEncoder.java
