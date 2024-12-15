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
import java.util.AbstractList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import net.hydromatic.morel.compile.Environment;
import org.apache.calcite.DataContext;
import org.apache.calcite.interpreter.Interpreter;
import org.apache.calcite.rel.RelNode;

/** A list whose contents are computed by evaluating a relational expression. */
public class RelList extends AbstractList<Object> {
  /** Value printed instead of the contents of an external relation. */
  public static final String RELATION = "<relation>";

  public final RelNode rel;
  private final Supplier<List<Object>> supplier;

  RelList(
      RelNode rel,
      DataContext dataContext,
      Function<Object[], Object> converter) {
    this.rel = rel;
    //noinspection FunctionalExpressionCanBeFolded
    supplier =
        Suppliers.memoize(
                () ->
                    new Interpreter(dataContext, rel)
                        .select(converter::apply)
                        .toList())
            ::get;
  }

  public Object get(int index) {
    return supplier.get().get(index);
  }

  public int size() {
    return supplier.get().size();
  }

  /**
   * Returns "{@code <list>}". Does not obey the usual behavior for collections,
   * concatenating the string representations of all elements, because some
   * tables are huge.
   *
   * @see #asString()
   */
  @Override
  public String toString() {
    return "<list>";
  }

  /**
   * Returns the contents of this list as a string.
   *
   * <p>This method does not override the {@link #toString()} method; if we did,
   * debuggers would invoke it automatically, burning lots of CPU and memory.
   *
   * @see Environment#asString()
   */
  public String asString() {
    return supplier.get().toString();
  }
}

// End RelList.java
