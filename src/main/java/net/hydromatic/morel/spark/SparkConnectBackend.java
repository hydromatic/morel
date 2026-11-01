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

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Implementation of {@link SparkBackend} over Spark Connect.
 *
 * <p>Loaded by name; see {@link SparkBackend#load()}.
 */
@SuppressWarnings("unused") // loaded by name
public class SparkConnectBackend implements SparkBackend {
  public SparkConnectBackend() {}

  @Override
  public Connection connect(TypeSystem typeSystem, String uri) {
    return new SparkConnection(typeSystem, uri);
  }

  @Override
  public Plan prepare(TypeSystem typeSystem, Core.Exp query) {
    return SparkTranslator.translate(typeSystem, query);
  }
}

// End SparkConnectBackend.java
