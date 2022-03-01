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

import com.google.common.base.Suppliers;
import org.apache.calcite.DataContext;
import org.apache.calcite.interpreter.Interpreter;
import org.apache.calcite.rel.RelNode;

import java.util.AbstractList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** A list whose contents are computed by evaluating a relational
 * expression. */
public class RelList extends AbstractList<Object> {
  public final RelNode rel;

  private final Supplier<List<Object>> supplier;

  RelList(RelNode rel, DataContext dataContext,
      Function<Object[], Object> converter) {
    this.rel = rel;
    //noinspection FunctionalExpressionCanBeFolded
    supplier = Suppliers.memoize(() ->
        new Interpreter(dataContext, rel)
            .select(converter::apply)
            .toList())::get;
  }

  public Object get(int index) {
    return supplier.get().get(index);
  }

  public int size() {
    return supplier.get().size();
  }
}

// End RelList.java
