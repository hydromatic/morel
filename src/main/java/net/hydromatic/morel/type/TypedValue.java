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
package net.hydromatic.morel.type;

/** A value that knows its own type. */
public interface TypedValue {
  /** Returns the value cast as a particular type. */
  <V> V valueAs(Class<V> clazz);

  /** Returns the value of a field, identified by name,
   * cast as a particular type. */
  default <V> V fieldValueAs(String fieldName, Class<V> clazz) {
    throw new UnsupportedOperationException("not a record");
  }

  /** Returns the value of a field, identified by ordinal,
   * cast as a particular type. */
  default <V> V fieldValueAs(int fieldIndex, Class<V> clazz) {
    throw new UnsupportedOperationException("not a record");
  }

  /** Key from which the type of this value can be constructed. */
  Type.Key typeKey();

  /** Tries to expand the type to include the given field name.
   *
   * <p>Returns this value or an expanded value. */
  default TypedValue discoverField(TypeSystem typeSystem, String fieldName) {
    return this;
  }
}

// End TypedValue.java
