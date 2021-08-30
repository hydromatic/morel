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

import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import org.apache.calcite.avatica.util.DateTimeUtils;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.EnumerableDefaults;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.ImmutableNullableList;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.AbstractList;
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

  public static RelDataType toCalciteType(Type type,
      RelDataTypeFactory typeFactory) {
    return C2m.forMorel(type, typeFactory, false, true).calciteType;
  }

  /** Returns a function that converts from Morel objects to an Enumerable
   * over Calcite rows. */
  public static Function<Object, Enumerable<Object[]>> toCalciteEnumerable(
      Type type, RelDataTypeFactory typeFactory) {
    final C2m converter =
        C2m.forMorel(type, typeFactory, false, false);
    return converter::toCalciteEnumerable;
  }

  /** Returns a function that converts from Morel objects to Calcite objects. */
  public static Function<Object, Object> toCalcite(Type type,
      RelDataTypeFactory typeFactory) {
    final C2m converter =
        C2m.forMorel(type, typeFactory, false, true);
    return converter::toCalciteObject;
  }

  /** Returns a function that converts from Calcite objects to Morel objects. */
  public static Function<Object, Object> toMorel(Type type,
      RelDataTypeFactory typeFactory) {
    final C2m converter =
        C2m.forMorel(type, typeFactory, false, true);
    return converter.toMorelObjectFunction();
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

  /** Converter from Calcite types to Morel types. */
  private static class C2m {
    final RelDataType calciteType;
    final Type morelType;

    C2m(RelDataType calciteType, Type morelType) {
      this.calciteType = calciteType;
      this.morelType = morelType;
    }

    /** Creates a converter for a given Morel type, in the process deducing the
     * corresponding Calcite type. */
    static C2m forMorel(Type type, RelDataTypeFactory typeFactory,
        boolean nullable, boolean recordList) {
      final RelDataTypeFactory.Builder typeBuilder;
      switch (type.op()) {
      case DATA_TYPE:
        final DataType dataType = (DataType) type;
        if (dataType.name.equals("option")) {
          return forMorel(dataType.parameterTypes.get(0), typeFactory, true,
              false);
        }
        throw new AssertionError("unknown type " + type);

      case FUNCTION_TYPE:
        // Represent Morel functions (and closures) as SQL type ANY. UDFs have a
        // parameter of type Object, and the value is cast to Closure.
        return new C2m(typeFactory.createSqlType(SqlTypeName.ANY), type);

      case LIST:
        final ListType listType = (ListType) type;
        RelDataType elementType =
            forMorel(listType.elementType, typeFactory, nullable, false)
                .calciteType;
        if (recordList && !elementType.isStruct()) {
          elementType = typeFactory.builder()
              .add("1", elementType)
              .build();
        }
        return new C2m(
            typeFactory.createMultisetType(elementType, -1),
            type);

      case RECORD_TYPE:
      case TUPLE_TYPE:
        typeBuilder = typeFactory.builder();
        final RecordLikeType recordType = (RecordLikeType) type;
        recordType.argNameTypes().forEach((name, argType) ->
            typeBuilder.add(name,
                forMorel(argType, typeFactory, nullable, recordList)
                    .calciteType));
        return new C2m(typeBuilder.build(), type);

      case TY_VAR:
        // The reason that a type variable is present is because the type
        // doesn't matter. For example, in 'map (fn x => 1) []' it doesn't
        // matter what the element type of the empty list is, because the
        // lambda doesn't look at the elements. So, pretend the type is 'bool'.
        type = PrimitiveType.BOOL;
        // fall through

      case ID:
        final PrimitiveType primitiveType = (PrimitiveType) type;
        switch (primitiveType) {
        case BOOL:
          return new C2m(
              typeFactory.createTypeWithNullability(
                  typeFactory.createSqlType(SqlTypeName.BOOLEAN), nullable),
              type);
        case INT:
          return new C2m(
              typeFactory.createTypeWithNullability(
                  typeFactory.createSqlType(SqlTypeName.INTEGER), nullable),
              type);
        case REAL:
          return new C2m(
              typeFactory.createTypeWithNullability(
                  typeFactory.createSqlType(SqlTypeName.REAL), nullable),
              type);
        case CHAR:
          return new C2m(
              typeFactory.createTypeWithNullability(
                  typeFactory.createSqlType(SqlTypeName.SMALLINT), nullable),
              type);
        case UNIT:
          return new C2m(
              typeFactory.createTypeWithNullability(
              typeFactory.createSqlType(SqlTypeName.TINYINT), nullable),
              type);
        case STRING:
          return new C2m(
              typeFactory.createTypeWithNullability(
                  typeFactory.createSqlType(SqlTypeName.VARCHAR, -1), nullable),
              type);
        default:
          throw new AssertionError("unknown type " + type);
        }

      default:
        throw new UnsupportedOperationException("cannot convert type " + type);
      }
    }

    public Object toCalciteObject(Object v) {
      return v;
    }

    public Enumerable<Object[]> toCalciteEnumerable(Object v) {
      @SuppressWarnings({"unchecked", "rawtypes"})
      final Enumerable<Object> enumerable = Linq4j.asEnumerable((List) v);
      switch (morelType.op()) {
      case LIST:
        final ListType listType = (ListType) morelType;
        final C2m c =
            new C2m(calciteType.getComponentType(),
                listType.elementType);
        if (c.morelType instanceof PrimitiveType) {
          if (c.calciteType.isStruct()) {
            return EnumerableDefaults.select(enumerable, c::scalarToArray);
          } else {
            //noinspection unchecked
            return (Enumerable) enumerable;
          }
        } else {
          return EnumerableDefaults.select(enumerable, c::listToArray);
        }
      default:
        throw new AssertionError("cannot convert " + morelType);
      }
    }

    private Object[] listToArray(Object o) {
      @SuppressWarnings("unchecked")
      List<Object> list = (List<Object>) o;
      return list.toArray();
    }

    private Object[] scalarToArray(Object o) {
      return new Object[] {o};
    }

    public Function<Object, Object> toMorelObjectFunction() {
      switch (morelType.op()) {
      case TUPLE_TYPE:
        final ImmutableList.Builder<Function<Object, Object>> b =
            ImmutableList.builder();
        Pair.forEach(calciteType.getFieldList(),
            ((TupleType) morelType).argTypes, (field, argType) ->
                b.add(new C2m(field.getType(), argType)
                    .toMorelObjectFunction()));
        final ImmutableList<Function<Object, Object>> converters = b.build();
        return v -> {
          final Object[] values = (Object[]) v;
          return new AbstractList<Object>() {
            @Override public int size() {
              return values.length;
            }

            @Override public Object get(int index) {
              return converters.get(index).apply(values[index]);
            }
          };
        };

      case ID: // primitive type, e.g. int
        switch ((PrimitiveType) morelType) {
        case INT:
          return v -> ((Number) v).intValue();
        default:
          return v -> v;
        }

      default:
        throw new AssertionError("unknown type " + morelType);
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
