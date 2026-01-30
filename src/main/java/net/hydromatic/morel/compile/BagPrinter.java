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
package net.hydromatic.morel.compile;

import java.util.List;
import net.hydromatic.morel.type.Type;

/** Controls element ordering when printing bag values. */
public interface BagPrinter {
  /** Returns elements in the order they should be printed. */
  List<Object> order(List<Object> elements, Type elementType);

  /** Default: prints in natural iteration order. */
  BagPrinter NATURAL = (elements, type) -> elements;
}

// End BagPrinter.java
