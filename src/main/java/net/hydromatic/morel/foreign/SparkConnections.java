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

import com.google.common.collect.ImmutableSortedMap;
import java.util.AbstractList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.type.Keys;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;
import org.jspecify.annotations.Nullable;

/**
 * Helpers for the {@code Spark} structure: opening connections, and the record
 * that {@code Spark.connect} returns.
 */
public class SparkConnections {
  /** Environment variable that names the default server. */
  public static final String SPARK_REMOTE = "SPARK_REMOTE";

  /** The catalog of each open connection, so that discovery accumulates. */
  private static final Map<SparkBackend.Connection, SparkCatalog> CATALOGS =
      new IdentityHashMap<>();

  private SparkConnections() {}

  /**
   * Opens a connection and returns the record {@code {catalog, connection}}
   * that {@code Spark.connect} returns. The record is a {@link TypedValue}, so
   * that {@code spark.catalog.scott.emps} is typed progressively from the value
   * bound to {@code spark}.
   */
  public static ConnectionRecord open(Session session, String uri) {
    final TypeSystem typeSystem =
        requireNonNull(session.typeSystem, "typeSystem");
    return open(typeSystem, session.foreignValues, uri);
  }

  /**
   * Opens a connection and returns the record {@code {catalog, connection}}; as
   * {@link #open(Session, String)}, without a session.
   */
  public static ConnectionRecord open(
      TypeSystem typeSystem,
      Map<String, ForeignValue> foreignValues,
      String uri) {
    final SparkBackend.Connection connection =
        SparkBackend.open(typeSystem, foreignValues, uri);
    return new ConnectionRecord(connection, catalog(connection));
  }

  /** Opens a connection to the server named by {@code SPARK_REMOTE}. */
  public static ConnectionRecord openDefault(Session session) {
    return open(session, defaultUri());
  }

  /**
   * Opens a connection and returns it as a value to bind in an environment, so
   * that a session can read {@code spark.catalog.scott.emps} on its first line
   * rather than opening the connection itself.
   *
   * <p>An empty {@code uri} means the default server, from {@code
   * SPARK_REMOTE}. Opening costs nothing until the connection is used: the
   * channel does not dial until an RPC, and a catalog asks the server nothing
   * until a field of it is discovered. So a session starts whether or not the
   * server is up, and a server that is not there is reported where it is
   * noticed.
   */
  public static ForeignValue binding(
      TypeSystem typeSystem,
      Map<String, ForeignValue> foreignValues,
      String uri) {
    final ConnectionRecord record =
        open(typeSystem, foreignValues, uri.isEmpty() ? defaultUri() : uri);
    return new ForeignValue() {
      @Override
      public Type type(TypeSystem typeSystem) {
        return record.typeKey().toType(typeSystem);
      }

      @Override
      public Object value() {
        return record;
      }
    };
  }

  /** Returns the URI of the default server, from {@code SPARK_REMOTE}. */
  public static String defaultUri() {
    final String uri = System.getenv(SPARK_REMOTE);
    if (uri == null || uri.isEmpty()) {
      throw new SparkBackend.SparkException(
          "CONNECTION",
          "No default Spark Connect server: " + SPARK_REMOTE + " is not set");
    }
    return uri;
  }

  /** Returns the catalog of a connection, creating it on first use. */
  public static SparkCatalog catalog(SparkBackend.Connection connection) {
    synchronized (CATALOGS) {
      return CATALOGS.computeIfAbsent(connection, SparkCatalog::new);
    }
  }

  /** Closes a connection and forgets its catalog. */
  public static void close(SparkBackend.Connection connection) {
    synchronized (CATALOGS) {
      CATALOGS.remove(connection);
    }
    connection.close();
  }

  /** Returns the payload of the Morel exception {@code Spark} for an error. */
  public static List<Object> payload(SparkBackend.SparkException e) {
    return new Payload(e.errorClass, String.valueOf(e.getMessage()));
  }

  /**
   * Payload of the Morel exception {@code Spark}: the record {@code
   * {errorClass, message}}, with its fields in label order, printed as an error
   * message.
   */
  private static class Payload extends AbstractList<Object> {
    private final String errorClass;
    private final String message;

    Payload(String errorClass, String message) {
      this.errorClass = errorClass;
      this.message = message;
    }

    @Override
    public Object get(int index) {
      switch (index) {
        case 0:
          return errorClass;
        case 1:
          return message;
        default:
          throw new IndexOutOfBoundsException();
      }
    }

    @Override
    public int size() {
      return 2;
    }

    @Override
    public String toString() {
      return errorClass + ": " + message;
    }
  }

  /**
   * The record {@code {catalog: {...}, connection: connection}} that {@code
   * Spark.connect} returns. Its type key carries the catalog's current key, so
   * that the type of a name bound to it grows as the catalog is browsed.
   */
  public static class ConnectionRecord extends AbstractList<Object>
      implements TypedValue {
    public final SparkBackend.Connection connection;
    public final SparkCatalog catalog;

    ConnectionRecord(SparkBackend.Connection connection, SparkCatalog catalog) {
      this.connection = requireNonNull(connection, "connection");
      this.catalog = requireNonNull(catalog, "catalog");
    }

    @Override
    public Type.Key typeKey() {
      return Keys.record(
          ImmutableSortedMap.<String, Type.Key>orderedBy(RecordType.ORDERING)
              .put("catalog", catalog.typeKey())
              .put("connection", Keys.name(BuiltIn.Eqtype.CONNECTION.mlName()))
              .build());
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
      switch (fieldName) {
        case "catalog":
          return clazz.cast(catalog);
        case "connection":
          return clazz.cast(connection);
        default:
          throw new IllegalArgumentException("no field " + fieldName);
      }
    }

    @Override
    public <V> V fieldValueAs(int fieldIndex, Class<V> clazz) {
      return clazz.cast(get(fieldIndex));
    }

    @Override
    public Object get(int index) {
      switch (index) {
        case 0:
          return catalog;
        case 1:
          return connection;
        default:
          throw new IndexOutOfBoundsException();
      }
    }

    @Override
    public int size() {
      return 2;
    }

    @Override
    public @Nullable String toString() {
      return "{catalog=" + catalog + ",connection=" + connection + "}";
    }
  }
}

// End SparkConnections.java
