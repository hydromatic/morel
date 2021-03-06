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

import org.apache.calcite.avatica.util.DateTimeUtils;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.util.ImmutableNullableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.util.Ord;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Utilities for Converter. */
public class Converters {
  private Converters() {
  }

  public static Converter<Object[]> ofRow(RelDataType rowType) {
    final List<RelDataTypeField> fields = rowType.getFieldList();
    final ImmutableList.Builder<Converter<Object[]>> converters =
        ImmutableList.builder();
    Ord.forEach(fields, (field, i) ->
        converters.add(ofField(field.getType(), i)));
    return new RecordConverter(converters.build());
  }

  public static Converter<Object[]> ofRow2(RelDataType rowType,
      RecordLikeType type) {
    return ofRow3(rowType.getFieldList().iterator(),
        new AtomicInteger(), Linq4j.enumerator(type.argNameTypes().values()));
  }

  static Converter<Object[]> ofRow3(Iterator<RelDataTypeField> fields,
      AtomicInteger ordinal, Enumerator<Type> types) {
    final ImmutableList.Builder<Converter<Object[]>> converters =
        ImmutableList.builder();
    while (types.moveNext()) {
      converters.add(ofField2(fields, ordinal, types.current()));
    }
    return new RecordConverter(converters.build());
  }

  public static Converter<Object[]> ofField(RelDataType type, int ordinal) {
    final FieldConverter fieldConverter = FieldConverter.toType(type);
    return values -> fieldConverter.convertFrom(values[ordinal]);
  }

  static Converter<Object[]> ofField2(Iterator<RelDataTypeField> fields,
      AtomicInteger ordinal, Type type) {
    final RelDataTypeField field = fields.next();
    if (type instanceof RecordType) {
      if (field.getType().isStruct()) {
        return offset(ordinal.getAndIncrement(),
            ofRow3(field.getType().getFieldList().iterator(),
                new AtomicInteger(),
                Linq4j.enumerator(((RecordType) type).argNameTypes.values())));
      } else {
        return ofRow3(fields, ordinal,
            Linq4j.enumerator(((RecordType) type).argNameTypes.values()));
      }
    }
    return ofField3(field, ordinal, type);
  }

  /** Creates a converter that applies to the {@code i}th field of the input
   * array. */
  static Converter<Object[]> offset(int i, Converter<Object[]> converter) {
    return values -> converter.apply((Object[]) values[i]);
  }

  static Converter<Object[]> ofField3(RelDataTypeField field,
      AtomicInteger ordinal, Type type) {
    if (field.getType().isStruct()) {
      return ofRow3(field.getType().getFieldList().iterator(), ordinal,
          Linq4j.singletonEnumerator(type));
    }
    final FieldConverter fieldConverter =
        FieldConverter.toType(field.getType());
    final int i = ordinal.getAndIncrement();
    return values -> fieldConverter.convertFrom(values[i]);
  }

  @SuppressWarnings("unchecked")
  public static Function<Enumerable<Object[]>, List<Object>>
      fromEnumerable(RelNode rel, Type type) {
    final ListType listType = (ListType) type;
    final RelDataType rowType = rel.getRowType();
    final Function<Object[], Object> elementConverter =
        forType(rowType, listType.elementType);
    return enumerable -> enumerable.select(elementConverter::apply).toList();
  }

  @SuppressWarnings("unchecked")
  public static <E> Function<E, Object> forType(RelDataType fromType, Type type) {
    if (type == PrimitiveType.UNIT) {
      return o -> Unit.INSTANCE;
    }
    if (type instanceof PrimitiveType) {
      RelDataTypeField field =
          Iterables.getOnlyElement(fromType.getFieldList());
      return (Converter<E>) ofField(field.getType(), 0);
    }
    if (type instanceof RecordLikeType) {
      return (Converter<E>) ofRow2(fromType, (RecordLikeType) type);
    }
    if (fromType.isNullable()) {
      return o -> o == null ? BigDecimal.ZERO : o;
    }
    return o -> o;
  }

  public static Type fieldType(RelDataTypeField field) {
    return FieldConverter.toType(field.getType()).mlType;
  }

  /** Converts a field from Calcite to Morel format. */
  enum FieldConverter {
    FROM_BOOLEAN(PrimitiveType.BOOL) {
      public Boolean convertFrom(Object o) {
        return (Boolean) o;
      }
    },
    FROM_INTEGER(PrimitiveType.INT) {
      public Integer convertFrom(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
      }
    },
    FROM_FLOAT(PrimitiveType.REAL) {
      public Float convertFrom(Object o) {
        return o == null ? 0f : ((Number) o).floatValue();
      }
    },
    FROM_DATE(PrimitiveType.STRING) {
      public String convertFrom(Object o) {
        return o == null ? "" : new Date(
            (Integer) o * DateTimeUtils.MILLIS_PER_DAY).toString();
      }
    },
    FROM_TIME(PrimitiveType.STRING) {
      public String convertFrom(Object o) {
        return o == null ? "" : new Time(
            (Integer) o % DateTimeUtils.MILLIS_PER_DAY).toString();
      }
    },
    FROM_TIMESTAMP(PrimitiveType.STRING) {
      public String convertFrom(Object o) {
        return o == null ? "" : new Timestamp((Long) o).toString();
      }
    },
    FROM_STRING(PrimitiveType.STRING) {
      public String convertFrom(Object o) {
        return o == null ? "" : (String) o;
      }
    };

    final Type mlType;

    FieldConverter(Type mlType) {
      this.mlType = mlType;
    }

    /** Given a Calcite row, returns the value of this field in SML format. */
    public abstract Object convertFrom(Object sourceValue);

    static FieldConverter toType(RelDataType type) {
      switch (type.getSqlTypeName()) {
      case BOOLEAN:
        return FROM_BOOLEAN;

      case TINYINT:
      case SMALLINT:
      case INTEGER:
      case BIGINT:
        return FROM_INTEGER;

      case FLOAT:
      case REAL:
      case DOUBLE:
      case DECIMAL:
        return FROM_FLOAT;

      case DATE:
        return FROM_DATE;

      case TIME:
        return FROM_TIME;

      case TIMESTAMP:
        return FROM_TIMESTAMP;

      case VARCHAR:
      case CHAR:
      default:
        return FROM_STRING;
      }
    }
  }

  /** Converter that creates a record. Uses one sub-Converter per output
   * field. */
  private static class RecordConverter implements Converter<Object[]> {
    final Object[] tempValues;
    final ImmutableList<Converter<Object[]>> converterList;

    RecordConverter(ImmutableList<Converter<Object[]>> converterList) {
      tempValues = new Object[converterList.size()];
      this.converterList = converterList;
    }

    @Override public List<Object> apply(Object[] a) {
      for (int i = 0; i < tempValues.length; i++) {
        tempValues[i] = converterList.get(i).apply(a);
      }
      return ImmutableNullableList.copyOf(tempValues);
    }
  }
}

// End Converters.java
