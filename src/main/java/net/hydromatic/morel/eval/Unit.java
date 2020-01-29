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

import java.util.AbstractList;

/** A placeholder value for the "unit" type.
 *
 * <p>We sometimes use it as a dummy value when we need to add a variable (and
 * its type) to the compilation environment but we don't have a value (because
 * it's not a runtime environment). */
public class Unit extends AbstractList implements Comparable<Unit> {
  public static final Unit INSTANCE = new Unit();

  private Unit() {}

  @Override public String toString() {
    return "()";
  }

  public Object get(int index) {
    throw new IndexOutOfBoundsException();
  }

  public int size() {
    return 0;
  }

  public int compareTo(Unit o) {
    return 0;
  }
}

// End Unit.java
