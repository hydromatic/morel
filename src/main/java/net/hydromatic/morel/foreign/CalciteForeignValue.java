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

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.util.Static.append;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.stream.Collectors;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.PairList;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.Table;
import org.apache.calcite.tools.RelBuilder;

/**
 * Value based on a Calcite schema.
 *
 * <p>In ML, it appears as a record with a field for each table.
 */
public class CalciteForeignValue implements ForeignValue {
  private final Calcite calcite;
  private final SchemaPlus schema;
  private final NameConverter nameConverter;

  /** Creates a CalciteForeignValue. */
  public CalciteForeignValue(
      Calcite calcite, SchemaPlus schema, NameConverter nameConverter) {
    this.calcite = requireNonNull(calcite);
    this.schema = requireNonNull(schema);
    this.nameConverter = requireNonNull(nameConverter);
  }

  /**
   * Creates a CalciteForeignValue, optionally converting schema, table and
   * column names to lower case.
   */
  @Deprecated
  public CalciteForeignValue(
      Calcite calcite, SchemaPlus schema, boolean lower) {
    this(
        calcite,
        schema,
        lower ? NameConverter.TO_LOWER : NameConverter.IDENTITY);
  }

  public Type type(TypeSystem typeSystem) {
    return toType(schema, typeSystem);
  }

  private Type toType(SchemaPlus schema, TypeSystem typeSystem) {
    final SortedMap<String, Type> fields = RecordType.mutableMap();
    final List<String> schemaPath = Schemas.path(schema).names();
    schema
        .getTableNames()
        .forEach(
            tableName -> {
              Table table = requireNonNull(schema.getTable(tableName));
              final List<String> tablePath = append(schemaPath, tableName);
              fields.put(
                  nameConverter.convert(schemaPath, tableName),
                  toType(tablePath, table, typeSystem));
            });
    schema
        .getSubSchemaNames()
        .forEach(
            subSchemaName -> {
              final SchemaPlus subSchema =
                  requireNonNull(schema.getSubSchema(subSchemaName));
              fields.put(
                  nameConverter.convert(schemaPath, subSchemaName),
                  toType(subSchema, typeSystem));
            });

    return typeSystem.recordType(fields);
  }

  private Type toType(
      List<String> tablePath, Table table, TypeSystem typeSystem) {
    final PairList<String, Type> fields = PairList.of();
    table
        .getRowType(calcite.typeFactory)
        .getFieldList()
        .forEach(
            field ->
                fields.add(
                    nameConverter.convert(tablePath, field.getName()),
                    Converters.fieldType(field)));
    return typeSystem.bagType(typeSystem.recordType(fields));
  }

  public Object value() {
    return valueFor(schema);
  }

  private ImmutableList<Object> valueFor(SchemaPlus schema) {
    final SortedMap<String, Object> fieldValues = RecordType.mutableMap();
    final List<String> schemaPath = Schemas.path(schema).names();
    schema
        .getTableNames()
        .forEach(
            tableName -> {
              final RelBuilder b = calcite.relBuilder;
              b.scan(plus(schemaPath, tableName));
              final List<String> tablePath =
                  append(Schemas.path(schema).names(), tableName);
              final List<RexNode> exprList =
                  b.peek().getRowType().getFieldList().stream()
                      .map(
                          f ->
                              Ord.of(
                                  f.getIndex(),
                                  nameConverter.convert(
                                      tablePath, f.getName())))
                      .sorted(Map.Entry.comparingByValue())
                      .map(p -> b.alias(b.field(p.i), p.e))
                      .collect(Collectors.toList());
              b.project(exprList, ImmutableList.of(), true);
              final RelNode rel = b.build();
              final Converter<Object[]> converter =
                  Converters.ofRow(rel.getRowType());
              fieldValues.put(
                  nameConverter.convert(schemaPath, tableName),
                  new RelList(rel, calcite.dataContext, converter));
            });

    // Recursively walk sub-schemas and add their tables to fieldValues
    schema
        .getSubSchemaNames()
        .forEach(
            subSchemaName -> {
              final SchemaPlus subSchema =
                  requireNonNull(schema.getSubSchema(subSchemaName));
              fieldValues.put(
                  nameConverter.convert(schemaPath, subSchemaName),
                  valueFor(subSchema));
            });
    return ImmutableList.copyOf(fieldValues.values());
  }

  /** Returns a copy of a list with one element appended. */
  private static <E> List<E> plus(List<E> list, E e) {
    return ImmutableList.<E>builder().addAll(list).add(e).build();
  }

  /** Converts a database object name to a Morel field name. */
  public interface NameConverter {
    String convert(List<String> path, String name);

    /** Converter that converts all names to lower-case. */
    NameConverter TO_LOWER = (path, name) -> name.toLowerCase(Locale.ROOT);

    /** Converter that leaves all names unchanged. */
    NameConverter IDENTITY = (path, name) -> name;
  }
}

// End CalciteForeignValue.java
