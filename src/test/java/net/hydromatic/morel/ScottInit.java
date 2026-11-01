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
package net.hydromatic.morel;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableMap;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * Writes the part of {@code spark/init.py} that creates the {@code scott}
 * database, from Morel's own {@code scott} data set.
 *
 * <p>The container is supposed to hold the rows the data set holds, so that a
 * query answers the same way wherever it ran. Saying so twice -- once in the
 * data set and once in a fixture -- is how it stopped being true three times: a
 * salary that was a double where Morel's is a decimal, a commission that was a
 * zero where Morel's is absent, and a president with a manager. So the fixture
 * is generated from the data set, and {@code LintTest.testGeneratedSections}
 * fails when the committed file and the data set disagree.
 *
 * <p>The file is committed rather than generated as the container starts. A
 * fixture that nothing reviews is a fixture that can change under you, and the
 * plans of {@code script/spark-plan-generated.smli} are trustworthy for the
 * same reason: something states them independently of the code that has to
 * match them.
 */
class ScottInit {
  private ScottInit() {}

  /**
   * Type of a decimal column.
   *
   * <p>Morel's {@code decimal} is arbitrary-precision and Spark's is not, so
   * the fixture has to choose; this is the precision and scale that the scott
   * schema has always used.
   */
  private static final String DECIMAL = "DECIMAL(7, 2)";

  /** Width at which a generated line wraps, counting its indent. */
  private static final int WIDTH = 76;

  /** Writes the section named {@code key}. */
  static void generateSection(String key, PrintWriter pw) {
    if (!key.equals("scott")) {
      throw new IllegalArgumentException("unknown section '" + key + "'");
    }
    // The data set's types include "decimal", which is a built-in datatype,
    // so the type system has to know them.
    final TypeSystem typeSystem = new TypeSystem();
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    final Map<String, ForeignValue> values =
        Calcite.withDataSets(ImmutableMap.of("scott", BuiltInDataSet.SCOTT))
            .foreignValues();
    final ForeignValue scott = requireNonNull(values.get("scott"), "scott");
    final RecordLikeType type = (RecordLikeType) scott.type(typeSystem);
    @SuppressWarnings("unchecked")
    final List<Object> tables = (List<Object>) scott.value();
    int i = 0;
    for (Map.Entry<String, Type> table : type.argNameTypes().entrySet()) {
      @SuppressWarnings("unchecked")
      final List<Object> rows = (List<Object>) tables.get(i++);
      writeTable(pw, table.getKey(), table.getValue(), rows);
    }
  }

  /** Writes one call to the {@code table} function of {@code init.py}. */
  private static void writeTable(
      PrintWriter pw, String name, Type tableType, List<Object> rows) {
    final Type rowType = tableType.arg(0);
    final Map<String, Type> fields = ((RecordLikeType) rowType).argNameTypes();
    pw.println("table(");
    pw.printf("    \"scott.%s\",%n", name);
    final List<String> columns = new ArrayList<>();
    fields.forEach(
        (label, fieldType) -> columns.add(label + " " + ddl(fieldType)));
    writeString(pw, columns, false);
    // A table with no rows passes "" as its content, so that the call reads
    // the same as every other.
    final List<String> values = new ArrayList<>();
    for (Object row : rows) {
      values.add(rowValue(fields, row));
    }
    writeString(pw, values, nullable(fields).isEmpty());
    // Which columns hold nulls, for the table's own property: an option in
    // the data set is a column that holds them, and the rest do not. The
    // table says this about itself, so that a session needs to be told
    // nothing.
    final List<String> nullable = nullable(fields);
    if (!nullable.isEmpty()) {
      pw.printf("    \"%s\")%n", String.join(",", nullable));
    }
  }

  /** Returns the names of the fields whose type is an option, in order. */
  private static List<String> nullable(Map<String, Type> fields) {
    final List<String> names = new ArrayList<>();
    fields.forEach(
        (label, fieldType) -> {
          if (optionValueType(fieldType) != null) {
            names.add(label);
          }
        });
    return names;
  }

  /**
   * Writes a triple-quoted string holding a comma-separated list, wrapped, or
   * {@code ""} if the list is empty.
   */
  private static void writeString(
      PrintWriter pw, List<String> items, boolean last) {
    if (items.isEmpty()) {
      pw.printf("    \"\"%s%n", last ? ")" : ",");
      return;
    }
    pw.println("    \"\"\"");
    final StringBuilder line = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
      final String item = items.get(i) + (i < items.size() - 1 ? "," : "");
      if (line.length() > 0 && 4 + line.length() + 1 + item.length() > WIDTH) {
        pw.printf("    %s%n", line);
        line.setLength(0);
      }
      if (line.length() > 0) {
        line.append(' ');
      }
      line.append(item);
    }
    pw.printf("    %s\"\"\"%s%n", line, last ? ")" : ",");
  }

  /** Returns the Spark type of a column whose Morel type is given. */
  private static String ddl(Type type) {
    final Type valueType = optionValueType(type);
    final Type t = valueType != null ? valueType : type;
    if (t instanceof PrimitiveType) {
      switch ((PrimitiveType) t) {
        case BOOL:
          return "BOOLEAN";
        case INT:
          return "INT";
        case REAL:
          return "DOUBLE";
        case STRING:
          return "STRING";
        default:
          break;
      }
    }
    if (t.moniker().equals("decimal")) {
      return DECIMAL;
    }
    throw new IllegalArgumentException("no Spark type for " + t.moniker());
  }

  /** Returns the values of one row, as a SQL tuple. */
  private static String rowValue(Map<String, Type> fields, Object row) {
    final List<?> values = (List<?>) row;
    final StringBuilder b = new StringBuilder("(");
    int i = 0;
    for (Type fieldType : fields.values()) {
      if (i > 0) {
        b.append(", ");
      }
      b.append(value(fieldType, values.get(i++)));
    }
    return b.append(")").toString();
  }

  /** Returns one value as a SQL literal. */
  private static String value(Type type, Object value) {
    final Type valueType = optionValueType(type);
    if (valueType != null) {
      // An option: NONE is a null, and SOME v is v. Both are lists, the
      // constructor first.
      final List<?> option = (List<?>) value;
      return option.size() < 2 ? "NULL" : value(valueType, option.get(1));
    }
    if (value instanceof String) {
      return "'" + ((String) value).replace("'", "''") + "'";
    }
    if (value instanceof BigDecimal) {
      // Morel's canonical form strips trailing zeros, which turns 800 into
      // 8E+2; a fixture should say what it means.
      return ((BigDecimal) value).toPlainString();
    }
    if (value instanceof Number) {
      return value.toString();
    }
    if (value instanceof Boolean) {
      return ((Boolean) value).toString();
    }
    throw new IllegalArgumentException(
        "no SQL literal for " + value + " of type " + type.moniker());
  }

  /** Returns the value type of an option type, or null if it is not one. */
  private static @Nullable Type optionValueType(Type type) {
    return type.moniker().endsWith(" option") ? type.arg(0) : null;
  }
}

// End ScottInit.java
