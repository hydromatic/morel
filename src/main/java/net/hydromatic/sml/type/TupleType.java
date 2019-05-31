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
package net.hydromatic.sml.type;

import com.google.common.collect.ImmutableList;

import net.hydromatic.sml.ast.Op;

import java.util.List;
import java.util.Objects;

/** The type of a tuple value. */
public class TupleType extends BaseType {
  public final List<Type> argTypes;

  TupleType(String description, ImmutableList<Type> argTypes) {
    super(Op.TUPLE_TYPE, description);
    this.argTypes = Objects.requireNonNull(argTypes);
  }
}

// End TupleType.java
