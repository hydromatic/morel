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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import java.util.AbstractList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.type.Keys;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;
import org.jspecify.annotations.Nullable;

/**
 * A Spark catalog as a progressively typed record: databases, then tables, then
 * a table's rows.
 *
 * <p>The structure is the same as {@link net.hydromatic.morel.eval.Files} gives
 * the file system. Nothing is fetched until it is needed: the databases when
 * the root's type is first asked for, a database's tables when the database is
 * first discovered, a table's type when the table is first discovered, and its
 * rows when its value is first forced. Each fetch happens once, and each one
 * that widens a type bumps the type system's expand count, so that the resolver
 * deduces the statement again with the wider type.
 */
public class SparkCatalog extends AbstractList<Object> implements TypedValue {
  /** Key for the type "{...}", a progressive record with no known fields. */
  static final Type.Key PROGRESSIVE_UNIT =
      Keys.progressiveRecord(ImmutableSortedMap.of());

  /** Key for the type "{...} bag", the type of a table not yet discovered. */
  static final Type.Key PROGRESSIVE_UNIT_BAG =
      Keys.apply(
          Keys.name(BuiltIn.Eqtype.BAG.mlName()),
          ImmutableList.of(PROGRESSIVE_UNIT));

  private final SparkBackend.Connection connection;
  private @Nullable SortedMap<String, Database> databases;

  public SparkCatalog(SparkBackend.Connection connection) {
    this.connection = requireNonNull(connection, "connection");
  }

  /**
   * Calls the connection, converting an error into the Morel exception {@code
   * Spark}. A catalog is browsed during type resolution as well as during
   * evaluation, and the harness reports the Morel exception either way.
   */
  private static <T> T call(Supplier<T> supplier) {
    try {
      return supplier.get();
    } catch (SparkBackend.SparkException e) {
      throw Codes.sparkException(e, Pos.ZERO);
    }
  }

  private SortedMap<String, Database> databases() {
    SortedMap<String, Database> map = databases;
    if (map == null) {
      map = new TreeMap<>(RecordType.ORDERING);
      for (String name : call(connection::databases)) {
        map.put(name, new Database(name));
      }
      databases = map;
    }
    return map;
  }

  @Override
  public Type.Key typeKey() {
    return Keys.progressiveRecord(
        Maps.transformValues(databases(), TypedValue::typeKey));
  }

  @Override
  public TypedValue discoverField(TypeSystem typeSystem, String fieldName) {
    final Database database = databases().get(fieldName);
    if (database != null && database.tables == null) {
      database.tables();
      typeSystem.expandCount.incrementAndGet();
    }
    return this;
  }

  @Override
  public <V> V valueAs(Class<V> clazz) {
    if (clazz.isInstance(this)) {
      return clazz.cast(this);
    }
    throw new IllegalArgumentException("not a " + clazz);
  }

  @Override
  public <V> V fieldValueAs(String fieldName, Class<V> clazz) {
    // Asking the catalog for a database it does not have is the user's
    // mistake, not the adapter's, so it reads as Spark's own error for it,
    // and reaches the program as the exception every Spark failure raises.
    final Database database =
        call(
            () -> {
              final Database d = databases().get(fieldName);
              if (d == null) {
                throw new SparkBackend.SparkException(
                    "SCHEMA_NOT_FOUND", "Database " + fieldName + " not found");
              }
              return d;
            });
    return clazz.cast(database);
  }

  @Override
  public <V> V fieldValueAs(int fieldIndex, Class<V> clazz) {
    return clazz.cast(get(fieldIndex));
  }

  @Override
  public Object get(int index) {
    return requireNonNull(Iterables.get(databases().values(), index));
  }

  @Override
  public int size() {
    return databases().size();
  }

  @Override
  public String toString() {
    return "<catalog>";
  }

  /** A database: a progressive record of tables. */
  class Database extends AbstractList<Object> implements TypedValue {
    final String name;
    @Nullable SortedMap<String, Table> tables;

    Database(String name) {
      this.name = name;
    }

    SortedMap<String, Table> tables() {
      SortedMap<String, Table> map = tables;
      if (map == null) {
        map = new TreeMap<>(RecordType.ORDERING);
        for (String tableName : call(() -> connection.tables(name))) {
          map.put(tableName, new Table(this, tableName));
        }
        tables = map;
      }
      return map;
    }

    @Override
    public Type.Key typeKey() {
      if (tables == null) {
        return PROGRESSIVE_UNIT;
      }
      return Keys.progressiveRecord(
          Maps.transformValues(tables, TypedValue::typeKey));
    }

    @Override
    public TypedValue discoverField(TypeSystem typeSystem, String fieldName) {
      final Table table = tables().get(fieldName);
      if (table != null && table.type == null) {
        table.type(typeSystem);
        typeSystem.expandCount.incrementAndGet();
      }
      return this;
    }

    @Override
    public <V> V valueAs(Class<V> clazz) {
      if (clazz.isInstance(this)) {
        return clazz.cast(this);
      }
      throw new IllegalArgumentException("not a " + clazz);
    }

    @Override
    public <V> V fieldValueAs(String fieldName, Class<V> clazz) {
      final Table table =
          call(
              () -> {
                final Table t = tables().get(fieldName);
                if (t == null) {
                  throw new SparkBackend.SparkException(
                      "TABLE_OR_VIEW_NOT_FOUND",
                      "Table " + name + "." + fieldName + " not found");
                }
                return t;
              });
      return clazz.cast(table);
    }

    @Override
    public <V> V fieldValueAs(int fieldIndex, Class<V> clazz) {
      return clazz.cast(get(fieldIndex));
    }

    @Override
    public Object get(int index) {
      return requireNonNull(Iterables.get(tables().values(), index));
    }

    @Override
    public int size() {
      return tables().size();
    }

    @Override
    public String toString() {
      return "<database " + name + ">";
    }
  }

  /** A table: a bag of records, fetched when first forced. */
  public class Table extends AbstractList<Object> implements TypedValue {
    final Database database;
    final String name;
    @Nullable Type type;
    @Nullable List<Object> rows;

    Table(Database database, String name) {
      this.database = database;
      this.name = name;
    }

    Type type(TypeSystem typeSystem) {
      Type t = type;
      if (t == null) {
        t = call(() -> connection.tableType(database.name, name));
        type = t;
      }
      return t;
    }

    private List<Object> rows() {
      List<Object> list = rows;
      if (list == null) {
        list =
            ImmutableList.copyOf(
                call(() -> connection.rows(database.name, name)));
        rows = list;
      }
      return list;
    }

    @Override
    public Type.Key typeKey() {
      return type == null ? PROGRESSIVE_UNIT_BAG : type.key();
    }

    @Override
    public <V> V valueAs(Class<V> clazz) {
      if (clazz.isInstance(this) && clazz != Object.class) {
        // Asked for a TypedValue (or a Table, or a List): the table itself,
        // which fetches its rows only when they are read.
        return clazz.cast(this);
      }
      if (clazz.isAssignableFrom(ImmutableList.class)) {
        // Asked for the value: the rows.
        return clazz.cast(rows());
      }
      throw new IllegalArgumentException("not a " + clazz);
    }

    @Override
    public Object get(int index) {
      return rows().get(index);
    }

    @Override
    public int size() {
      return rows().size();
    }

    @Override
    public String toString() {
      return "<relation>";
    }

    /**
     * Returns the name Spark knows this table by: the database and the table,
     * joined by a dot.
     */
    public String qualifiedName() {
      return database.name + "." + name;
    }
  }
}

// End SparkCatalog.java
