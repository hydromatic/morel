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

import java.util.List;

/**
 * Number base for formatting and scanning integers and words.
 *
 * <p>The constant names match the constructors of the {@code StringCvt.radix}
 * datatype, so {@link #of(List)} can recover the {@code Radix} from a runtime
 * {@code radix} value.
 */
enum Radix {
  BIN(2),
  OCT(8),
  DEC(10),
  HEX(16);

  /** The numeric base, e.g. 16 for {@link #HEX}. */
  final int base;

  Radix(int base) {
    this.base = base;
  }

  /**
   * Returns the {@code Radix} for a runtime {@code StringCvt.radix} value (a
   * one-element list holding the constructor name).
   */
  static Radix of(List radix) {
    return valueOf((String) radix.get(0));
  }
}

// End Radix.java
