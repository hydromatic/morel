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

import static java.util.Objects.requireNonNull;

/**
 * Type constructor.
 *
 * <p>Given
 *
 * <blockquote>
 *
 * <pre>
 * datatype 'a option = NONE | SOME of 'a
 * datatype ('a, 'b) either = LEFT of 'a | RIGHT of 'b
 * </pre>
 *
 * </blockquote>
 *
 * <p>Datatype {@code option} has constructors:
 *
 * <table>
 *   <caption>Type constructors for datatype option</caption>
 *   <tr><th>dataType</th><th>name</th><th>argTypeKey</th></tr>
 *   <tr><td>option</td><td>NONE</td><td>dummy</td></tr>
 *   <tr><td>option</td><td>SOME</td><td>Var(0)</td></tr>
 * </table>
 *
 * <p>Datatype {@code either} has constructors:
 *
 * <table>
 *   <caption>Type constructors for datatype either</caption>
 *   <tr><th>dataType</th><th>name</th><th>argTypeKey</th></tr>
 *   <tr><td>either</td><td>LEFT</td><td>Var(0)</td></tr>
 *   <tr><td>either</td><td>RIGHT</td><td>Var(1)</td></tr>
 * </table>
 */
public class TypeCon {
  public final DataType dataType;
  public final String name;
  public final Type.Key argTypeKey;

  private TypeCon(DataType dataType, String name, Type.Key argTypeKey) {
    this.dataType = requireNonNull(dataType);
    this.name = requireNonNull(name);
    this.argTypeKey = requireNonNull(argTypeKey);
  }

  /** Creates a type constructor. */
  public static TypeCon of(DataType dataType, String name, Type.Key typeKey) {
    return new TypeCon(dataType, name, typeKey);
  }
}

// End TypeCon.java
