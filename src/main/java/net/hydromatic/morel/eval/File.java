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
package net.hydromatic.morel.eval;

import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;

/** Directory in the file system.
 *
 * <p>Its type is progressive, so that it can discover new files and
 * subdirectories.
 *
 * @see Files
 * @see Files#create(java.io.File)
 */
public interface File extends TypedValue {
  /** Expands this file to a file with a more precise type.
   *
   * <p>During expansion, record types may get new fields, never lose them.
   *
   * <p>This file object may or may not be mutable. If this file is immutable
   * and is expanded, returns the new file. If this file is mutable, returns
   * this file regardless of whether expansion occurred; the caller cannot
   * discern whether expansion occurred. */
  default File expand() {
    return this;
  }

  default File discoverField(TypeSystem typeSystem,
      String fieldName) {
    return this;
  }
}

// End File.java
