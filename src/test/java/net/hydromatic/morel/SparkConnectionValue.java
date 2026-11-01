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
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.foreign.SparkConnections;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * A Spark connection as a foreign value, so that the test harness can bind
 * {@code spark} in a script's environment, as {@code Spark.connect} would
 * return it, and close it after the script.
 *
 * <p>The connection is opened when the type is first asked for, which the
 * environment does before it asks for the value.
 */
class SparkConnectionValue implements ForeignValue, AutoCloseable {
  private final String uri;
  private SparkConnections.@Nullable ConnectionRecord record;

  SparkConnectionValue(String uri) {
    this.uri = requireNonNull(uri, "uri");
  }

  @Override
  public Type type(TypeSystem typeSystem) {
    SparkConnections.ConnectionRecord r = record;
    if (r == null) {
      r = SparkConnections.open(typeSystem, ImmutableMap.of(), uri);
      record = r;
    }
    return r.typeKey().toType(typeSystem);
  }

  @Override
  public Object value() {
    return requireNonNull(record, "type() must be called before value()");
  }

  @Override
  public void close() {
    final SparkConnections.ConnectionRecord r = record;
    if (r != null) {
      record = null;
      SparkConnections.close(r.connection);
    }
  }
}

// End SparkConnectionValue.java
