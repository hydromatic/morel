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

/**
 * The Spark adapter: executes queries on a Spark cluster via Spark Connect.
 *
 * <p>This package needs JDK 17 and the gRPC, protobuf and Arrow libraries,
 * which the rest of Morel does not. It is compiled only when the "spark" Maven
 * profile is active, and the rest of Morel refers to it only through {@link
 * net.hydromatic.morel.foreign.SparkBackend}, which loads it by name.
 */
@NullMarked
package net.hydromatic.morel.spark;

import org.jspecify.annotations.NullMarked;

// End package-info.java
