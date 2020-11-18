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

import org.apache.calcite.linq4j.function.Function1;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.Table;
import org.apache.calcite.util.ImmutableNullableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;

import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Value based on a Calcite schema.
 *
 * <p>In ML, it appears as a record with a field for each table.
 */
public class CalciteForeignValue implements ForeignValue {
  private final Calcite calcite;
  private final SchemaPlus schema;
  private final boolean lower;

  /** Creates a CalciteForeignValue. */
  public CalciteForeignValue(Calcite calcite, SchemaPlus schema, boolean lower) {
    this.calcite = calcite;
    this.schema = Objects.requireNonNull(schema);
    this.lower = lower;
  }

  public Type type(TypeSystem typeSystem) {
    final ImmutableSortedMap.Builder<String, Type> fields =
        ImmutableSortedMap.orderedBy(RecordType.ORDERING);
    schema.getTableNames().forEach(tableName ->
        fields.put(convert(tableName),
            toType(schema.getTable(tableName), typeSystem)));
    return typeSystem.recordType(fields.build());
  }

  private Type toType(Table table, TypeSystem typeSystem) {
    final ImmutableSortedMap.Builder<String, Type> fields =
        ImmutableSortedMap.orderedBy(RecordType.ORDERING);
    table.getRowType(calcite.typeFactory)
        .getFieldList()
        .forEach(field ->
            fields.put(convert(field.getName()),
                toType(field).mlType));
    return typeSystem.listType(typeSystem.recordType(fields.build()));
  }

  private String convert(String name) {
    return lower ? name.toLowerCase(Locale.ROOT) : name;
  }

  private FieldConverter toType(RelDataTypeField field) {
    final int ordinal = field.getIndex();
    switch (field.getType().getSqlTypeName()) {
    case BOOLEAN:
      return new FieldConverter(PrimitiveType.BOOL, ordinal) {
        public Boolean convertFrom(Object[] sourceValues) {
          return (Boolean) sourceValues[ordinal];
        }
      };

    case TINYINT:
    case SMALLINT:
    case INTEGER:
    case BIGINT:
      return new FieldConverter(PrimitiveType.INT, ordinal) {
        public Integer convertFrom(Object[] sourceValues) {
          final Number sourceValue = (Number) sourceValues[ordinal];
          return sourceValue == null ? 0 : sourceValue.intValue();
        }
      };

    case FLOAT:
    case REAL:
    case DOUBLE:
    case DECIMAL:
      return new FieldConverter(PrimitiveType.REAL, ordinal) {
        public Float convertFrom(Object[] sourceValues) {
          final Number sourceValue = (Number) sourceValues[ordinal];
          return sourceValue == null ? 0f : sourceValue.floatValue();
        }
      };

    case DATE:
      return new FieldConverter(PrimitiveType.STRING, ordinal) {
        public String convertFrom(Object[] sourceValues) {
          final Date sourceValue = (Date) sourceValues[ordinal];
          return sourceValue == null ? "" : sourceValue.toString();
        }
      };

    case TIME:
      return new FieldConverter(PrimitiveType.STRING, ordinal) {
        public String convertFrom(Object[] sourceValues) {
          final Time sourceValue = (Time) sourceValues[ordinal];
          return sourceValue == null ? "" : sourceValue.toString();
        }
      };

    case TIMESTAMP:
      return new FieldConverter(PrimitiveType.STRING, ordinal) {
        public String convertFrom(Object[] sourceValues) {
          final Timestamp sourceValue = (Timestamp) sourceValues[ordinal];
          return sourceValue == null ? "" : sourceValue.toString();
        }
      };

    case VARCHAR:
    case CHAR:
    default:
      return new FieldConverter(PrimitiveType.STRING, ordinal) {
        public String convertFrom(Object[] sourceValues) {
          final String sourceValue = (String) sourceValues[ordinal];
          return sourceValue == null ? "" : sourceValue;
        }
      };
    }
  }

  public Object value() {
    final ImmutableList.Builder<List<Object>> fieldValues =
        ImmutableList.builder();
    final List<String> names = Schemas.path(schema).names();
    schema.getTableNames().forEach(tableName -> {
      final RelNode scan =
          calcite.relBuilder.scan(plus(names, tableName)).build();
      final Converter converter = new Converter(scan.getRowType());
      fieldValues.add(new RelList(scan, calcite.dataContext, converter));
    });
    return fieldValues.build();
  }

  /** Returns a copy of a list with one element appended. */
  private static <E> List<E> plus(List<E> list, E e) {
    return ImmutableList.<E>builder().addAll(list).add(e).build();
  }

  /** Converts from a Calcite row to an SML record.
   *
   * <p>The Calcite row is represented as an array, ordered by field ordinal;
   * the SML record is represented by a list, ordered by field name
   * (lower-case if {@link #lower}). */
  private class Converter implements Function1<Object[], List<Object>> {
    final Object[] tempValues;
    final FieldConverter[] fieldConverters;

    Converter(RelDataType rowType) {
      final List<RelDataTypeField> fields =
          new ArrayList<>(rowType.getFieldList());
      fields.sort(Comparator.comparing(f -> convert(f.getName())));
      tempValues = new Object[fields.size()];
      fieldConverters = new FieldConverter[fields.size()];
      for (int i = 0; i < fieldConverters.length; i++) {
        fieldConverters[i] = toType(fields.get(i));
      }
    }

    public List<Object> apply(Object[] a) {
      for (int i = 0; i < tempValues.length; i++) {
        tempValues[i] = fieldConverters[i].convertFrom(a);
      }
      return ImmutableNullableList.copyOf(tempValues);
    }
  }

  /** Converts a field from Calcite to SML format. */
  private abstract static class FieldConverter {
    final Type mlType;
    final int ordinal;

    FieldConverter(Type mlType, int ordinal) {
      this.mlType = mlType;
      this.ordinal = ordinal;
    }

    /** Given a Calcite row, returns the value of this field in SML format. */
    public abstract Object convertFrom(Object[] sourceValues);
  }
}

// End CalciteForeignValue.java
