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
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

/**
 * The offline Spark connection: a connection to no server whose catalog is the
 * session's foreign data sets, for testing without a cluster.
 *
 * <p>Each data set is a database, and each of its tables a table, so {@code
 * spark.catalog.scott.emps} is the environment's {@code scott.emps}. A server
 * whose catalog holds the same tables and rows gives the same type and values,
 * so a query written for one connection runs on the other.
 */
public class MockSparkConnection implements SparkBackend.Connection {
  /** The URI that opens the offline connection. */
  public static final String URI = "mock:";

  private final TypeSystem typeSystem;
  private final Map<String, ForeignValue> foreignValues;
  private boolean closed;

  public MockSparkConnection(
      TypeSystem typeSystem, Map<String, ForeignValue> foreignValues) {
    this.typeSystem = requireNonNull(typeSystem, "typeSystem");
    this.foreignValues = ImmutableMap.copyOf(foreignValues);
  }

  @Override
  public String uri() {
    return URI;
  }

  private void checkOpen() {
    if (closed) {
      throw new SparkBackend.SparkException(
          "CLOSED", "Connection to " + URI + " is closed");
    }
  }

  @Override
  public SparkBackend.Result sql(String sql) {
    checkOpen();
    throw new SparkBackend.SparkException(
        "MOCK", "The offline connection cannot execute SQL");
  }

  @Override
  public List<String> databases() {
    checkOpen();
    return ImmutableList.copyOf(foreignValues.keySet());
  }

  private ForeignValue database(String database) {
    checkOpen();
    final ForeignValue foreignValue = foreignValues.get(database);
    if (foreignValue == null) {
      throw new SparkBackend.SparkException(
          "SCHEMA_NOT_FOUND", "Database " + database + " not found");
    }
    return foreignValue;
  }

  private SortedMap<String, Type> tableTypes(String database) {
    final Type type = database(database).type(typeSystem);
    if (!(type instanceof RecordLikeType)) {
      throw new SparkBackend.SparkException(
          "SCHEMA_NOT_FOUND", "Data set " + database + " is not a record");
    }
    return ((RecordLikeType) type).argNameTypes();
  }

  @Override
  public List<String> tables(String database) {
    return ImmutableList.copyOf(tableTypes(database).keySet());
  }

  @Override
  public Type tableType(String database, String table) {
    final Type type = tableTypes(database).get(table);
    if (type == null) {
      throw new SparkBackend.SparkException(
          "TABLE_OR_VIEW_NOT_FOUND",
          "Table " + database + "." + table + " not found");
    }
    return type;
  }

  @Override
  public List<Object> execute(SparkBackend.Plan plan, Type elementType) {
    checkOpen();
    throw new SparkBackend.SparkException(
        "MOCK",
        "The offline connection cannot execute a plan; connect to a server");
  }

  @Override
  public List<Object> rows(String database, String table) {
    final SortedMap<String, Type> tableTypes = tableTypes(database);
    final int index = ImmutableList.copyOf(tableTypes.keySet()).indexOf(table);
    if (index < 0) {
      throw new SparkBackend.SparkException(
          "TABLE_OR_VIEW_NOT_FOUND",
          "Table " + database + "." + table + " not found");
    }
    @SuppressWarnings("unchecked")
    final List<Object> record = (List<Object>) database(database).value();
    @SuppressWarnings("unchecked")
    final List<Object> rows = (List<Object>) record.get(index);
    return rows;
  }

  @Override
  public void close() {
    closed = true;
  }

  @Override
  public String toString() {
    return "<connection " + URI + ">";
  }
}

// End MockSparkConnection.java
