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

import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Ord;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.Table;
import org.apache.calcite.tools.RelBuilder;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

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
    this.calcite = requireNonNull(calcite);
    this.schema = requireNonNull(schema);
    this.lower = lower;
  }

  public Type type(TypeSystem typeSystem) {
    return toType(schema, typeSystem);
  }

  private Type toType(SchemaPlus schema, TypeSystem typeSystem) {
    final ImmutableSortedMap.Builder<String, Type> fields =
        ImmutableSortedMap.orderedBy(RecordType.ORDERING);
    schema.getTableNames().forEach(tableName -> {
      Table table = requireNonNull(schema.getTable(tableName));
      fields.put(convert(tableName), toType(table, typeSystem));
    });
    schema.getSubSchemaNames().forEach(subSchemaName -> {
      final SchemaPlus subSchema =
          requireNonNull(schema.getSubSchema(subSchemaName));
      fields.put(convert(subSchemaName), toType(subSchema, typeSystem));
    });

    return typeSystem.recordType(fields.build());
  }

  private Type toType(Table table, TypeSystem typeSystem) {
    final ImmutableSortedMap.Builder<String, Type> fields =
        ImmutableSortedMap.orderedBy(RecordType.ORDERING);
    table.getRowType(calcite.typeFactory)
        .getFieldList()
        .forEach(field ->
            fields.put(convert(field.getName()), Converters.fieldType(field)));
    return typeSystem.listType(typeSystem.recordType(fields.build()));
  }

  private String convert(String name) {
    return convert(lower, name);
  }

  private static String convert(boolean lower, String name) {
    return lower ? name.toLowerCase(Locale.ROOT) : name;
  }

  public Object value() {
    return valueFor(schema);
  }

  private ImmutableList<Object> valueFor(SchemaPlus schema) {
    final SortedMap<String, Object> fieldValues =
        new TreeMap<>(RecordType.ORDERING);
    final List<String> names = Schemas.path(schema).names();
    schema.getTableNames().forEach(tableName -> {
      final RelBuilder b = calcite.relBuilder;
      b.scan(plus(names, tableName));
      final List<RexNode> exprList = b.peek().getRowType()
          .getFieldList().stream()
          .map(f -> Ord.of(f.getIndex(), convert(f.getName())))
          .sorted(Map.Entry.comparingByValue())
          .map(p -> b.alias(b.field(p.i), p.e))
          .collect(Collectors.toList());
      b.project(exprList, ImmutableList.of(), true);
      final RelNode rel = b.build();
      final Converter<Object[]> converter = Converters.ofRow(rel.getRowType());
      fieldValues.put(convert(tableName),
          new RelList(rel, calcite.dataContext, converter));
    });

    // Recursively walk sub-schemas and add their tables to fieldValues
    schema.getSubSchemaNames().forEach(subSchemaName -> {
      final SchemaPlus subSchema =
          requireNonNull(schema.getSubSchema(subSchemaName));
      fieldValues.put(convert(subSchemaName),
          valueFor(subSchema));
    });
    return ImmutableList.copyOf(fieldValues.values());
  }

  /** Returns a copy of a list with one element appended. */
  private static <E> List<E> plus(List<E> list, E e) {
    return ImmutableList.<E>builder().addAll(list).add(e).build();
  }

}

// End CalciteForeignValue.java
