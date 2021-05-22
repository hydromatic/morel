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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates unique names.
 *
 * <p>Also keeps track of how many times each given name has been used in this
 * program, so that a new occurrence of a name can be given a fresh ordinal.
 */
public class NameGenerator {
  private int id = 0;
  private final Map<String, AtomicInteger> nameCounts = new HashMap<>();

  /** Generates a name that is unique in this program. */
  public String get() {
    return "v" + id++;
  }

  /** Returns the number of times that "name" has been used for a variable. */
  public int inc(String name) {
    return nameCounts.computeIfAbsent(name, n -> new AtomicInteger(0))
        .getAndIncrement();
  }
}

// End NameGenerator.java
