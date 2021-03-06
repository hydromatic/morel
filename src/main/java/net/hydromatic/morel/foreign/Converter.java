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

import java.util.function.Function;

/** Converts from a Calcite row to a Morel value (often a record).
 *
 * <p>The Calcite row is represented as an array, ordered by field ordinal;
 * the SML record is represented by a list, ordered by field name.
 *
 * @param <E> Source object type
 */
public interface Converter<E> extends Function<E, Object> {
}

// End Converter.java
