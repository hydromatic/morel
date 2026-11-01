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
package net.hydromatic.morel.spark;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import com.google.rpc.Status;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.apache.spark.connect.proto.AnalyzePlanRequest;
import org.apache.spark.connect.proto.AnalyzePlanResponse;
import org.apache.spark.connect.proto.DataType;
import org.apache.spark.connect.proto.ExecutePlanRequest;
import org.apache.spark.connect.proto.ExecutePlanResponse;
import org.apache.spark.connect.proto.Plan;
import org.apache.spark.connect.proto.Read;
import org.apache.spark.connect.proto.Relation;
import org.apache.spark.connect.proto.SQL;
import org.apache.spark.connect.proto.SparkConnectServiceGrpc;
import org.apache.spark.connect.proto.UserContext;
import org.jspecify.annotations.Nullable;

/** A connection to a Spark Connect server, over gRPC. */
class SparkConnection implements SparkBackend.Connection {
  /** Default port of a Spark Connect server. */
  static final int DEFAULT_PORT = 15002;

  private final TypeSystem typeSystem;
  private final String uri;
  private final String sessionId = UUID.randomUUID().toString();
  private final UserContext userContext;
  private final ManagedChannel channel;
  private final SparkConnectServiceGrpc.SparkConnectServiceBlockingStub stub;

  /**
   * Columns the connection was told may be null, as "database.table.column" in
   * lower case; see {@link SparkTypes}.
   */
  private final ImmutableSet<String> nullableColumns;

  private boolean closed;

  SparkConnection(TypeSystem typeSystem, String uri) {
    this.typeSystem = requireNonNull(typeSystem, "typeSystem");
    this.uri = requireNonNull(uri, "uri");
    final URI u = parse(uri);
    final int port = u.getPort() < 0 ? DEFAULT_PORT : u.getPort();
    String userId = System.getProperty("user.name", "morel");
    // Parameters follow the path, separated by ';', e.g.
    // "sc://host:port/;user_id=fred;token=...".
    final String path = u.getPath() == null ? "" : u.getPath();
    final ImmutableSet.Builder<String> nullable = ImmutableSet.builder();
    for (String param : path.split(";")) {
      final int eq = param.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      final String key = param.substring(0, eq);
      final String value = param.substring(eq + 1);
      if (key.equals("user_id")) {
        userId = value;
      } else if (key.equals("nullable")) {
        // Which columns may be null, as "database.table.column", separated
        // by commas. Spark cannot tell us -- it calls every column of a
        // stored table nullable, whatever the DDL said -- so whoever knows
        // the data says so here. Identifiers are matched without regard to
        // case, as Spark compares them.
        for (String column : value.split(",")) {
          if (!column.isEmpty()) {
            nullable.add(column.toLowerCase(Locale.ROOT));
          }
        }
      }
    }
    this.nullableColumns = nullable.build();
    this.userContext = UserContext.newBuilder().setUserId(userId).build();
    this.channel =
        ManagedChannelBuilder.forAddress(u.getHost(), port)
            .usePlaintext()
            .build();
    this.stub = SparkConnectServiceGrpc.newBlockingStub(channel);
  }

  private static URI parse(String uri) {
    final URI u;
    try {
      u = new URI(uri);
    } catch (URISyntaxException e) {
      throw new SparkBackend.SparkException(
          "CONNECTION", "Invalid Spark Connect URI: " + uri, e);
    }
    if (!"sc".equals(u.getScheme()) || u.getHost() == null) {
      throw new SparkBackend.SparkException(
          "CONNECTION",
          "Invalid Spark Connect URI: "
              + uri
              + " (expected sc://host[:port][/;param=value...])");
    }
    return u;
  }

  @Override
  public String uri() {
    return uri;
  }

  @Override
  public SparkBackend.Result sql(String sql) {
    final Relation relation =
        Relation.newBuilder().setSql(SQL.newBuilder().setQuery(sql)).build();
    return execute(relation);
  }

  @Override
  public List<Object> execute(SparkBackend.Plan plan, Type elementType) {
    if (!(plan instanceof SparkPlan)) {
      throw new SparkBackend.SparkException(
          "UNSUPPORTED", "Not a Spark plan: " + plan);
    }
    return ArrowDecoder.decode(
        batches(((SparkPlan) plan).proto().getRoot()).batches, elementType);
  }

  /** Executes a plan whose root is the given relation. */
  SparkBackend.Result execute(Relation relation) {
    final Batches batches = batches(relation);
    final Type rowType = SparkTypes.rowType(typeSystem, batches.schema);
    return new ResultImpl(
        rowType, ArrowDecoder.decode(batches.batches, rowType));
  }

  /** The Arrow batches of a result, and the schema Spark gave for them. */
  private static class Batches {
    final DataType schema;
    final List<ByteString> batches;

    Batches(DataType schema, List<ByteString> batches) {
      this.schema = schema;
      this.batches = batches;
    }
  }

  /**
   * Runs a plan whose root is the given relation, and returns what comes back
   * undecoded: a caller that knows what Morel type it wants decodes for itself.
   */
  private Batches batches(Relation relation) {
    checkOpen();
    final ExecutePlanRequest request =
        ExecutePlanRequest.newBuilder()
            .setSessionId(sessionId)
            .setUserContext(userContext)
            .setClientType("morel")
            .setPlan(Plan.newBuilder().setRoot(relation))
            .build();
    DataType schema = null;
    final List<ByteString> batches = new ArrayList<>();
    try {
      final Iterator<ExecutePlanResponse> responses = stub.executePlan(request);
      while (responses.hasNext()) {
        final ExecutePlanResponse response = responses.next();
        if (response.hasSchema()) {
          schema = response.getSchema();
        }
        if (response.hasArrowBatch()) {
          batches.add(response.getArrowBatch().getData());
        }
      }
    } catch (StatusRuntimeException e) {
      throw toException(e);
    }
    if (schema == null) {
      throw new SparkBackend.SparkException(
          "CONNECTION", "Spark returned no schema for the query");
    }
    return new Batches(schema, batches);
  }

  /**
   * Converts a gRPC failure into a {@link SparkBackend.SparkException},
   * carrying Spark's error class if the server sent one.
   *
   * <p>Spark attaches a {@code google.rpc.ErrorInfo} to the status, whose
   * reason is the Java exception class and whose metadata includes
   * "errorClass", Spark's error condition (e.g. "DIVIDE_BY_ZERO"), when the
   * error has one.
   */
  static SparkBackend.SparkException toException(StatusRuntimeException e) {
    final Status status = StatusProto.fromThrowable(e);
    String errorClass = "CONNECTION";
    final String description = e.getStatus().getDescription();
    final String message =
        description != null ? description : e.getStatus().toString();
    if (status != null) {
      for (Any any : status.getDetailsList()) {
        if (any.is(ErrorInfo.class)) {
          try {
            final ErrorInfo info = any.unpack(ErrorInfo.class);
            final String condition = info.getMetadataMap().get("errorClass");
            errorClass = condition != null ? condition : info.getReason();
          } catch (InvalidProtocolBufferException ignore) {
            // fall through
          }
        }
      }
    }
    return new SparkBackend.SparkException(errorClass, message, e);
  }

  @Override
  public List<String> databases() {
    return column(sql("SHOW DATABASES"), "namespace");
  }

  @Override
  public List<String> tables(String database) {
    return column(sql("SHOW TABLES IN " + quote(database)), "tableName");
  }

  /** Returns a column of a result as strings. */
  private static List<String> column(SparkBackend.Result result, String name) {
    final RecordLikeType rowType = (RecordLikeType) result.rowType();
    final int i =
        ImmutableList.copyOf(rowType.argNameTypes().keySet()).indexOf(name);
    if (i < 0) {
      throw new SparkBackend.SparkException(
          "CONNECTION", "Result has no column " + name);
    }
    final List<String> values = new ArrayList<>();
    for (Object row : result.rows()) {
      values.add((String) ((List<?>) row).get(i));
    }
    return values;
  }

  /** Returns a relation that reads a table. */
  private static Relation read(String database, String table) {
    return Relation.newBuilder()
        .setRead(
            Read.newBuilder()
                .setNamedTable(
                    Read.NamedTable.newBuilder()
                        .setUnparsedIdentifier(
                            quote(database) + "." + quote(table))))
        .build();
  }

  /**
   * Returns the columns of one table that the URI declared nullable, by their
   * own names.
   *
   * <p>A declaration of {@code *} means every column, of every table or of one,
   * and is passed on as {@code *}: the mapper reads it as "every column of this
   * row".
   */
  private Set<String> uriNullableColumns(String database, String table) {
    if (nullableColumns.contains("*")) {
      return ImmutableSet.of("*");
    }
    final String prefix =
        (database + "." + table + ".").toLowerCase(Locale.ROOT);
    final ImmutableSet.Builder<String> b = ImmutableSet.builder();
    for (String column : nullableColumns) {
      if (column.startsWith(prefix)) {
        b.add(column.substring(prefix.length()));
      }
    }
    return b.build();
  }

  /**
   * Name of the table property that says which of a table's columns may be
   * null, because Spark keeps no such constraint of its own.
   */
  public static final String NULLABLE_PROPERTY = "morel.nullable";

  /**
   * Returns the columns that a table's own metadata declares nullable, or null
   * if it declares nothing.
   *
   * <p>The property is a complete answer for the table it is on: a column it
   * names may be null, and a column it does not name may not. An empty property
   * therefore says that the table has no nulls at all, which is a thing worth
   * being able to say.
   */
  private @Nullable Set<String> propertyNullableColumns(
      String database, String table) {
    final SparkBackend.Result result =
        sql("SHOW TBLPROPERTIES " + quote(database) + "." + quote(table));
    for (Object row : result.rows()) {
      final List<?> pair = (List<?>) row;
      if (pair.size() == 2
          && NULLABLE_PROPERTY.equals(pair.get(0))
          && pair.get(1) instanceof String) {
        final ImmutableSet.Builder<String> b = ImmutableSet.builder();
        for (String column : ((String) pair.get(1)).split(",")) {
          final String trimmed = column.trim().toLowerCase(Locale.ROOT);
          if (!trimmed.isEmpty()) {
            b.add(trimmed);
          }
        }
        return b.build();
      }
    }
    return null;
  }

  /**
   * Returns which of a table's columns may be null, from whichever source says.
   *
   * <p>The connection's own declaration comes first, because whoever wrote the
   * URI is looking at this session; then the table's property, which is what
   * the table says about itself; and if neither speaks, no column is optional,
   * and a null in one is an error that names it. Saying nothing is therefore
   * how it was before any of this existed, and the error is the prompt to write
   * the property.
   */
  private Set<String> nullableColumnsOf(String database, String table) {
    final Set<String> uriColumns = uriNullableColumns(database, table);
    if (!uriColumns.isEmpty()) {
      return uriColumns;
    }
    final @Nullable Set<String> columns =
        propertyNullableColumns(database, table);
    return columns == null ? ImmutableSet.of() : columns;
  }

  @Override
  public Type tableType(String database, String table) {
    checkOpen();
    final Relation relation = read(database, table);
    final AnalyzePlanRequest request =
        AnalyzePlanRequest.newBuilder()
            .setSessionId(sessionId)
            .setUserContext(userContext)
            .setClientType("morel")
            .setSchema(
                AnalyzePlanRequest.Schema.newBuilder()
                    .setPlan(Plan.newBuilder().setRoot(relation)))
            .build();
    final AnalyzePlanResponse response;
    try {
      response = stub.analyzePlan(request);
    } catch (StatusRuntimeException e) {
      throw toException(e);
    }
    final Type rowType =
        SparkTypes.rowType(
            typeSystem,
            response.getSchema().getSchema(),
            nullableColumnsOf(database, table));
    return typeSystem.bagType(rowType);
  }

  @Override
  public List<Object> rows(String database, String table) {
    // Decoded as the type this connection says the table has, which is not
    // always the type Spark's own schema would give: a declared column is an
    // option here and a plain column there, and the rows must match what the
    // program was typed against.
    final Type rowType = tableType(database, table).arg(0);
    return ArrowDecoder.decode(batches(read(database, table)).batches, rowType);
  }

  /** Quotes an identifier with back-ticks, as Spark SQL does. */
  private static String quote(String identifier) {
    return "`" + identifier.replace("`", "``") + "`";
  }

  private void checkOpen() {
    if (closed) {
      throw new SparkBackend.SparkException(
          "CLOSED", "Connection to " + uri + " is closed");
    }
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      channel.shutdown();
      try {
        channel.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public String toString() {
    return "<connection " + uri + ">";
  }

  /** Implementation of {@link SparkBackend.Result}. */
  private static class ResultImpl implements SparkBackend.Result {
    private final Type rowType;
    private final List<Object> rows;

    ResultImpl(Type rowType, List<Object> rows) {
      this.rowType = rowType;
      this.rows = ImmutableList.copyOf(rows);
    }

    @Override
    public Type rowType() {
      return rowType;
    }

    @Override
    public List<Object> rows() {
      return rows;
    }
  }
}

// End SparkConnection.java
