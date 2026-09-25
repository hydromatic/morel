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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.only;
import static org.apache.calcite.avatica.util.DateTimeUtils.unixDateToString;
import static org.apache.calcite.avatica.util.DateTimeUtils.unixTimeToString;
import static org.apache.calcite.avatica.util.DateTimeUtils.unixTimestampToString;

import com.google.common.collect.ImmutableList;
import java.math.BigDecimal;
import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
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
import org.jspecify.annotations.Nullable;

/** Utilities for Converter. */
public class Converters {
  private Converters() {}

  /**
   * Creates a converter for a row. If {@code nullables[i]} is true, the {@code
   * i}th field is converted to an {@code option} value.
   */
  public static Converter<Object[]> ofRow(
      RelDataType rowType, List<Boolean> nullables) {
    final List<RelDataTypeField> fields = rowType.getFieldList();
    checkArgument(fields.size() == nullables.size());
    final ImmutableList.Builder<Converter<Object[]>> converters =
        ImmutableList.builder();
    forEachIndexed(
        fields,
        (field, i) ->
            converters.add(ofField(field.getType(), i, nullables.get(i))));
    return new RecordConverter(converters.build());
  }

  public static Converter<Object[]> ofRow2(
      RelDataType rowType, RecordLikeType type) {
    return ofRow3(
        rowType.getFieldList().iterator(),
        new AtomicInteger(),
        Linq4j.enumerator(type.argTypes()));
  }

  static Converter<Object[]> ofRow3(
      Iterator<RelDataTypeField> fields,
      AtomicInteger ordinal,
      Enumerator<Type> types) {
    final ImmutableList.Builder<Converter<Object[]>> converters =
        ImmutableList.builder();
    while (types.moveNext()) {
      converters.add(ofField2(fields, ordinal, types.current()));
    }
    return new RecordConverter(converters.build());
  }

  public static Converter<Object[]> ofField(RelDataType type, int ordinal) {
    return ofField(type, ordinal, false);
  }

  /**
   * Creates a converter for the {@code ordinal}th field of a row. If {@code
   * nullable}, converts to an {@code option} value: {@code NONE} if the field
   * is null, otherwise {@code SOME v}.
   */
  static Converter<Object[]> ofField(
      RelDataType type, int ordinal, boolean nullable) {
    final FieldConverter fieldConverter = FieldConverter.toType(type);
    if (nullable) {
      return values -> {
        final Object o = values[ordinal];
        return o == null
            ? Codes.OPTION_NONE
            : Codes.optionSome(fieldConverter.convertFrom(o));
      };
    }
    return values -> fieldConverter.convertFrom(values[ordinal]);
  }

  /** Returns whether a type is {@code option}. */
  private static boolean isOption(Type type) {
    return type instanceof DataType && ((DataType) type).name.equals("option");
  }

  static Converter<Object[]> ofField2(
      Iterator<RelDataTypeField> fields, AtomicInteger ordinal, Type type) {
    final RelDataTypeField field = fields.next();
    if (type instanceof RecordType) {
      if (field.getType().isStruct()) {
        return offset(
            ordinal.getAndIncrement(),
            ofRow3(
                field.getType().getFieldList().iterator(),
                new AtomicInteger(),
                Linq4j.enumerator(((RecordType) type).argNameTypes.values())));
      } else {
        return ofRow3(
            fields,
            ordinal,
            Linq4j.enumerator(((RecordType) type).argNameTypes.values()));
      }
    }
    return ofField3(field, ordinal, type);
  }

  /**
   * Creates a converter that applies to the {@code i}th field of the input
   * array.
   */
  static Converter<Object[]> offset(int i, Converter<Object[]> converter) {
    return values -> converter.apply((Object[]) values[i]);
  }

  static Converter<Object[]> ofField3(
      RelDataTypeField field, AtomicInteger ordinal, Type type) {
    if (field.getType().isStruct()) {
      return ofRow3(
          field.getType().getFieldList().iterator(),
          ordinal,
          Linq4j.singletonEnumerator(type));
    }
    final int i = ordinal.getAndIncrement();
    return ofField(field.getType(), i, isOption(type));
  }

  @SuppressWarnings("unchecked")
  public static Function<Enumerable<Object[]>, List<Object>> fromEnumerable(
      RelNode rel, Type type) {
    checkArgument(type.isCollection(), "not a collection type: %s", type);
    final Type elementType = type.elementType();
    final RelDataType rowType = rel.getRowType();
    final Function<Object[], Object> elementConverter =
        forType(rowType, elementType);
    return enumerable -> enumerable.select(elementConverter::apply).toList();
  }

  @SuppressWarnings("unchecked")
  public static <E> Function<E, Object> forType(
      RelDataType fromType, Type type) {
    if (type == PrimitiveType.UNIT) {
      return o -> Unit.INSTANCE;
    }
    if (type instanceof PrimitiveType) {
      RelDataTypeField field = only(fromType.getFieldList());
      return (Converter<E>) ofField(field.getType(), 0);
    }
    if (type instanceof RecordLikeType) {
      return (Converter<E>) ofRow2(fromType, (RecordLikeType) type);
    }
    if (isOption(type) && fromType.isStruct()) {
      RelDataTypeField field = only(fromType.getFieldList());
      return (Converter<E>) ofField(field.getType(), 0, true);
    }
    if (fromType.isStruct() && fromType.getFieldCount() == 1) {
      // A single-column row whose Morel type is not a record, e.g. a
      // collection. The column already holds the Morel value; unwrap it
      // rather than leaking the Object[] row.
      return (Converter<E>) (Converter<Object[]>) values -> values[0];
    }
    if (fromType.isNullable()) {
      return o -> o == null ? BigDecimal.ZERO : o;
    }
    return o -> o;
  }

  public static Type fieldType(RelDataTypeField field) {
    return FieldConverter.toType(field.getType()).mlType;
  }

  public static RelDataType toCalciteType(
      Type type, RelDataTypeFactory typeFactory) {
    return C2m.forMorel(type, typeFactory, false, true).calciteType;
  }

  /**
   * Returns a function that converts from Morel objects to an Enumerable over
   * Calcite rows.
   */
  public static Function<Object, Enumerable<Object[]>> toCalciteEnumerable(
      Type type, RelDataTypeFactory typeFactory) {
    final C2m converter = C2m.forMorel(type, typeFactory, false, false);
    return converter::toCalciteEnumerable;
  }

  /** Returns a function that converts from Morel objects to Calcite objects. */
  public static Function<Object, Object> toCalcite(
      Type type, RelDataTypeFactory typeFactory) {
    final C2m converter = C2m.forMorel(type, typeFactory, false, true);
    return converter::toCalciteObject;
  }

  /** Returns a function that converts from Calcite objects to Morel objects. */
  public static Function<Object, Object> toMorel(
      Type type, RelDataTypeFactory typeFactory) {
    final C2m converter = C2m.forMorel(type, typeFactory, false, true);
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
        return o == null ? "" : unixDateToString((Integer) o);
      }
    },
    FROM_TIME(PrimitiveType.STRING) {
      public String convertFrom(Object o) {
        return o == null ? "" : unixTimeToString((Integer) o);
      }
    },
    FROM_TIMESTAMP(PrimitiveType.STRING) {
      public String convertFrom(Object o) {
        return o == null ? "" : unixTimestampToString((Long) o);
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

    /**
     * Creates a converter between a given Calcite type and Morel type. If the
     * Morel type is {@code option}, the converter maps null to {@code NONE}.
     */
    static C2m of(RelDataType calciteType, Type morelType) {
      if (isOption(morelType)) {
        return new OptionC2m(
            new C2m(calciteType, ((DataType) morelType).arg(0)), morelType);
      }
      return new C2m(calciteType, morelType);
    }

    /**
     * Creates a converter for a given Morel type, in the process deducing the
     * corresponding Calcite type.
     */
    static C2m forMorel(
        Type type,
        RelDataTypeFactory typeFactory,
        boolean nullable,
        boolean recordList) {
      final RelDataTypeFactory.Builder typeBuilder;
      switch (type.op()) {
        case DATA_TYPE:
          final DataType dataType = (DataType) type;
          if (dataType.isCollection()) {
            return forMorelCollection(type, typeFactory, nullable, recordList);
          }
          if (dataType.name.equals("option")) {
            return new OptionC2m(
                forMorel(dataType.arg(0), typeFactory, true, false), type);
          }
          throw new AssertionError("unknown type " + type);

        case FUNCTION_TYPE:
          // Represent Morel functions (and closures) as SQL type ANY. UDFs have
          // a parameter of type Object, and the value is cast to Closure.
          return new C2m(typeFactory.createSqlType(SqlTypeName.ANY), type);

        case LIST:
          return forMorelCollection(type, typeFactory, nullable, recordList);

        case RECORD_TYPE:
        case TUPLE_TYPE:
          typeBuilder = typeFactory.builder();
          final RecordLikeType recordType = (RecordLikeType) type;
          recordType
              .argNameTypes()
              .forEach(
                  (name, argType) ->
                      typeBuilder.add(
                          name,
                          forMorel(argType, typeFactory, nullable, recordList)
                              .calciteType));
          return new C2m(typeBuilder.build(), type);

        case TY_VAR:
          // Why is a type variable present? Because the type doesn't matter.
          // For example, in 'map (fn x => 1) []' it doesn't
          // matter what the element type of the empty list is, because the
          // lambda doesn't look at the elements. So, pretend the type is
          // 'bool'.
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
                      typeFactory.createSqlType(SqlTypeName.SMALLINT),
                      nullable),
                  type);
            case UNIT:
              return new C2m(
                  typeFactory.createTypeWithNullability(
                      typeFactory.createSqlType(SqlTypeName.TINYINT), nullable),
                  type);
            case STRING:
              return new C2m(
                  typeFactory.createTypeWithNullability(
                      typeFactory.createSqlType(SqlTypeName.VARCHAR, -1),
                      nullable),
                  type);
            default:
              throw new AssertionError("unknown type " + type);
          }

        default:
          throw new UnsupportedOperationException(
              "cannot convert type " + type);
      }
    }

    private static C2m forMorelCollection(
        Type type,
        RelDataTypeFactory typeFactory,
        boolean nullable,
        boolean recordList) {
      checkArgument(type.isCollection(), "not a collection type: %s", type);
      RelDataType elementType =
          forMorel(type.elementType(), typeFactory, nullable, false)
              .calciteType;
      if (recordList && !elementType.isStruct()) {
        elementType = typeFactory.builder().add("1", elementType).build();
      }
      return new C2m(typeFactory.createMultisetType(elementType, -1), type);
    }

    public @Nullable Object toCalciteObject(Object v) {
      return v;
    }

    public Enumerable<Object[]> toCalciteEnumerable(Object v) {
      @SuppressWarnings({"unchecked", "rawtypes"})
      final Enumerable<Object> enumerable = Linq4j.asEnumerable((List) v);
      if (morelType.isCollection()) {
        final Type elementType = morelType.elementType();
        requireNonNull(calciteType.getComponentType());
        final C2m c = new C2m(calciteType.getComponentType(), elementType);
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
      }
      throw new AssertionError("cannot convert " + morelType);
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
          forEach(
              calciteType.getFieldList(),
              ((TupleType) morelType).argTypes,
              (field, argType) ->
                  b.add(of(field.getType(), argType).toMorelObjectFunction()));
          final ImmutableList<Function<Object, Object>> converters = b.build();
          return v -> {
            final Object[] values = (Object[]) v;
            return new AbstractList<Object>() {
              @Override
              public int size() {
                return values.length;
              }

              @Override
              public Object get(int index) {
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
          if (morelType.isCollection()) {
            // The value of a collection-typed column is already a Morel
            // collection (a List); no conversion is required.
            return v -> v;
          }
          throw new AssertionError("unknown type " + morelType);
      }
    }
  }

  /**
   * Converter between a Morel {@code option} and a nullable Calcite value.
   * {@code NONE} is null, and {@code SOME v} is {@code v}.
   */
  private static class OptionC2m extends C2m {
    private final C2m elementC2m;

    OptionC2m(C2m elementC2m, Type morelType) {
      super(elementC2m.calciteType, morelType);
      this.elementC2m = elementC2m;
    }

    @Override
    public @Nullable Object toCalciteObject(Object v) {
      final List<?> list = (List<?>) v;
      return list.size() < 2 ? null : elementC2m.toCalciteObject(list.get(1));
    }

    @Override
    public Function<Object, Object> toMorelObjectFunction() {
      final Function<Object, Object> f = elementC2m.toMorelObjectFunction();
      return v -> v == null ? Codes.OPTION_NONE : Codes.optionSome(f.apply(v));
    }
  }

  /**
   * Converter that creates a record. Uses one sub-Converter per output field.
   */
  private static class RecordConverter implements Converter<Object[]> {
    final Object[] tempValues;
    final ImmutableList<Converter<Object[]>> converterList;

    RecordConverter(ImmutableList<Converter<Object[]>> converterList) {
      tempValues = new Object[converterList.size()];
      this.converterList = converterList;
    }

    @Override
    public List<Object> apply(Object[] a) {
      for (int i = 0; i < tempValues.length; i++) {
        tempValues[i] = converterList.get(i).apply(a);
      }
      return ImmutableNullableList.copyOf(tempValues);
    }
  }
}

// End Converters.java
