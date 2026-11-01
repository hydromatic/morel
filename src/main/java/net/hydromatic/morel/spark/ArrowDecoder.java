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

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Decimals;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.apache.arrow.vector.util.Text;
import org.jspecify.annotations.Nullable;

/**
 * Decodes Arrow record batches, as Spark Connect sends them, into Morel values.
 *
 * <p>Decoding is driven by the Morel type: an {@code option} type admits a null
 * (as {@code NONE}), any other type does not, and a null there raises {@link
 * SparkBackend.SparkException} with class "NULL_VALUE". Values are converted
 * thus: integers of every width to {@code int} (raising if a {@code bigint}
 * does not fit), floats, doubles and decimals to {@code real}, and so on.
 */
public class ArrowDecoder {
  private ArrowDecoder() {}

  private static final List<Object> NONE = ImmutableList.of("NONE");

  /**
   * Decodes batches into rows.
   *
   * @param batches Arrow IPC streams, each with its schema
   * @param rowType Morel type of a row; a record type, or a primitive type if
   *     the row has one field
   */
  public static List<Object> decode(List<ByteString> batches, Type rowType) {
    final List<Object> rows = new ArrayList<>();
    try (BufferAllocator allocator = new RootAllocator()) {
      for (ByteString batch : batches) {
        try (ArrowStreamReader reader =
            new ArrowStreamReader(batch.newInput(), allocator)) {
          while (reader.loadNextBatch()) {
            decodeBatch(reader.getVectorSchemaRoot(), rowType, rows);
          }
        }
      }
    } catch (IOException e) {
      throw new SparkBackend.SparkException(
          "CONNECTION", "Error reading Arrow data: " + e.getMessage(), e);
    }
    return rows;
  }

  private static void decodeBatch(
      VectorSchemaRoot root, Type rowType, List<Object> rows) {
    final List<FieldVector> vectors = root.getFieldVectors();
    final int rowCount = root.getRowCount();
    // Rows are numbered within the result, not within the batch: Spark
    // decides how many batches to send, so a batch-relative number would
    // name a different row depending on how the result arrived.
    final int offset = rows.size();
    if (ArrowEncoder.hasColumns(rowType)) {
      final RecordLikeType recordType = (RecordLikeType) rowType;
      // Vectors are in Spark's column order; a record's fields are in
      // Morel's order (sorted by label). Map by name.
      final List<String> labels =
          ImmutableList.copyOf(recordType.argNameTypes().keySet());
      final List<Type> types =
          ImmutableList.copyOf(recordType.argNameTypes().values());
      final FieldVector[] byLabel = new FieldVector[labels.size()];
      for (FieldVector vector : vectors) {
        final int i = labels.indexOf(vector.getName());
        if (i >= 0) {
          byLabel[i] = vector;
        }
      }
      for (int r = 0; r < rowCount; r++) {
        final Object[] fields = new Object[labels.size()];
        for (int c = 0; c < labels.size(); c++) {
          final FieldVector vector = byLabel[c];
          if (vector == null) {
            throw new SparkBackend.SparkException(
                "CONNECTION", "Result has no column " + labels.get(c));
          }
          fields[c] =
              toValue(
                  vector.getObject(r), types.get(c), labels.get(c), offset + r);
        }
        rows.add(ImmutableList.copyOf(fields));
      }
    } else {
      // A single unnamed column
      final FieldVector vector = vectors.get(0);
      for (int r = 0; r < rowCount; r++) {
        rows.add(
            toValue(
                vector.getObject(r), rowType, vector.getName(), offset + r));
      }
    }
  }

  /**
   * Converts a value, as Arrow's {@code getObject} returns it, to a Morel value
   * of the given type.
   */
  static Object toValue(@Nullable Object o, Type type, String column, int row) {
    if (isOption(type)) {
      if (o == null) {
        return NONE;
      }
      final Type elementType = ((DataType) type).arg(0);
      return ImmutableList.of(
          BuiltIn.Constructor.OPTION_SOME.constructor,
          toValue(o, elementType, column, row));
    }
    if (o == null) {
      throw new SparkBackend.SparkException(
          "NULL_VALUE",
          "Null in column "
              + column
              + " of row "
              + row
              + ", whose type "
              + type.moniker()
              + " has no room for it");
    }
    if (type instanceof PrimitiveType) {
      switch ((PrimitiveType) type) {
        case BOOL:
          return (Boolean) o;
        case INT:
          return toInt(o, column, row);
        case REAL:
          return toReal(o);
        case STRING:
          return o.toString();
        case CHAR:
          final String s = o.toString();
          return s.isEmpty() ? (char) 0 : s.charAt(0);
        case UNIT:
          return Unit.INSTANCE;
        default:
          break;
      }
    }
    if (type instanceof ListType) {
      final Type elementType = ((ListType) type).elementType();
      if (o instanceof byte[]) {
        // binary -> word list
        final byte[] bytes = (byte[]) o;
        final List<Object> words = new ArrayList<>(bytes.length);
        for (byte b : bytes) {
          words.add((long) (b & 0xFF));
        }
        return ImmutableList.copyOf(words);
      }
      final List<?> list = (List<?>) o;
      final List<Object> values = new ArrayList<>(list.size());
      for (Object e : list) {
        values.add(toValue(e, elementType, column, row));
      }
      return ImmutableList.copyOf(values);
    }
    if (type instanceof RecordLikeType) {
      final RecordLikeType recordType = (RecordLikeType) type;
      final Map<?, ?> map = (Map<?, ?>) o;
      final List<Object> values = new ArrayList<>();
      recordType
          .argNameTypes()
          .forEach(
              (label, fieldType) ->
                  values.add(
                      toValue(
                          map.get(label),
                          fieldType,
                          column + "." + label,
                          row)));
      return ImmutableList.copyOf(values);
    }
    if (type instanceof DataType) {
      final DataType dataType = (DataType) type;
      if (dataType.name.equals("decimal")) {
        // Spark's decimal has the precision its column declares, and Morel's
        // has 34 digits, so a value is rounded to those as half-even -- and
        // one too large for them is no more a decimal than an overflowing
        // bigint is an int.
        final BigDecimal decimal =
            o instanceof BigDecimal
                ? (BigDecimal) o
                : new BigDecimal(o.toString());
        final BigDecimal canonical = Decimals.canonical(decimal);
        if (canonical == null) {
          throw new SparkBackend.SparkException(
              "CAST_OVERFLOW",
              "Value "
                  + decimal
                  + " in column "
                  + column
                  + " of row "
                  + row
                  + " is too large for a Morel decimal");
        }
        return canonical;
      }
      if (dataType.name.equals("time")) {
        // timestamp: microseconds since epoch -> nanoseconds
        return Math.multiplyExact(((Number) o).longValue(), 1000L);
      }
      if (dataType.name.equals("date")) {
        if (o instanceof Integer) {
          // date: days since epoch
          return OffsetDateTime.of(
              LocalDate.ofEpochDay((Integer) o).atStartOfDay(), ZoneOffset.UTC);
        }
        if (o instanceof LocalDateTime) {
          // timestamp_ntz
          return OffsetDateTime.of((LocalDateTime) o, ZoneOffset.UTC);
        }
        if (o instanceof Long) {
          // timestamp_ntz as microseconds since epoch
          final long micros = (Long) o;
          return OffsetDateTime.of(
              LocalDateTime.ofEpochSecond(
                  Math.floorDiv(micros, 1_000_000L),
                  (int) Math.floorMod(micros, 1_000_000L) * 1000,
                  ZoneOffset.UTC),
              ZoneOffset.UTC);
        }
      }
    }
    throw new SparkBackend.SparkException(
        "UNSUPPORTED_TYPE",
        "Cannot decode "
            + o.getClass().getSimpleName()
            + " in column "
            + column
            + " as "
            + type.moniker());
  }

  private static boolean isOption(Type type) {
    return type instanceof DataType && ((DataType) type).name.equals("option");
  }

  private static Integer toInt(Object o, String column, int row) {
    if (o instanceof Integer) {
      return (Integer) o;
    }
    final long v = ((Number) o).longValue();
    if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
      throw new SparkBackend.SparkException(
          "CAST_OVERFLOW",
          "Value "
              + v
              + " in column "
              + column
              + " of row "
              + row
              + " does not fit in int");
    }
    return (int) v;
  }

  private static Float toReal(Object o) {
    if (o instanceof BigDecimal) {
      return ((BigDecimal) o).floatValue();
    }
    return ((Number) o).floatValue();
  }

  /** Converts Arrow text to a string; other objects via {@code toString}. */
  static String toString(Object o) {
    return o instanceof Text ? o.toString() : String.valueOf(o);
  }
}

// End ArrowDecoder.java
