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

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.SortedMap;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A type that has named fields, as a record type does. */
public interface RecordLikeType extends Type {
  /** Returns a map of the field types, keyed by field names. */
  SortedMap<String, Type> argNameTypes();

  /** Returns a list of field types, ordered by field names. */
  default List<Type> argTypes() {
    return ImmutableList.copyOf(argNameTypes().values());
  }

  /** Returns the type of the {@code i}th field, or throws. */
  Type argType(int i);

  /**
   * Returns a {@link TypedValue} if this type wraps a single dynamically typed
   * value, otherwise null.
   */
  default @Nullable TypedValue asTypedValue() {
    return null;
  }
}

// End RecordLikeType.java
