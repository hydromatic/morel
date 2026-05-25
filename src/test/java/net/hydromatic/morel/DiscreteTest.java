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
package net.hydromatic.morel;

import static net.hydromatic.morel.Matchers.list;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.eval.Discrete;
import net.hydromatic.morel.eval.Discretes;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/** Unit test for {@link Discrete} and {@link Discretes}. */
@SuppressWarnings({"EqualsWithItself", "UnnecessaryUnicodeEscape"})
class DiscreteTest {
  private static final char CHR_255 = '\u00ff';
  private static final char CHR_0 = '\u0000';
  private static final char CHR_1 = '\u0001';

  private final TypeSystem typeSystem = new TypeSystem();

  /**
   * Populates {@code typeSystem} with built-in types (needed for DataType
   * tests).
   */
  private void initBuiltIns() {
    final List<Binding> bindings = new ArrayList<>();
    BuiltIn.dataTypes(typeSystem, bindings);
  }

  @Test
  void testIntDiscrete() {
    Discrete<Object> d = Discretes.discreteFor(typeSystem, PrimitiveType.INT);
    assertThat(d.minValue(), is(Integer.MIN_VALUE));
    assertThat(d.maxValue(), is(Integer.MAX_VALUE));
    assertThat(d.next(0), is(1));
    assertThat(d.next(3), is(4));
    assertThat(d.next(-1), is(0));
    assertThat(d.prev(0), is(-1));
    assertThat(d.prev(3), is(2));
    assertThat(d.prev(-1), is(-2));
    assertThat(d.comparator().compare(1, 2) < 0, is(true));
    assertThat(d.comparator().compare(2, 2), is(0));
    assertThat(d.comparator().compare(3, 2) > 0, is(true));
  }

  @Test
  void testCharDiscrete() {
    Discrete<Object> d = Discretes.discreteFor(typeSystem, PrimitiveType.CHAR);
    assertThat(d.minValue(), is(CHR_0));
    assertThat(d.maxValue(), is(CHR_255));
    assertThat(d.next(CHR_0), is(CHR_1));
    assertThat(d.next('a'), is('b'));
    assertThat(d.next('z'), is('{'));
    assertThat(d.next(CHR_255), nullValue());
    assertThat(d.prev(CHR_0), nullValue());
    assertThat(d.prev('b'), is('a'));
    assertThat(d.prev('{'), is('z'));
    assertThat(d.comparator().compare('a', 'b') < 0, is(true));
    assertThat(d.comparator().compare('b', 'b'), is(0));
    assertThat(d.comparator().compare('c', 'b') > 0, is(true));
  }

  @Test
  void testBoolDiscrete() {
    Discrete<Object> d = Discretes.discreteFor(typeSystem, PrimitiveType.BOOL);
    assertThat(d.minValue(), is(Boolean.FALSE));
    assertThat(d.maxValue(), is(Boolean.TRUE));
    assertThat(d.next(Boolean.FALSE), is(Boolean.TRUE));
    assertThat(d.next(Boolean.TRUE), nullValue());
    assertThat(d.prev(Boolean.FALSE), nullValue());
    assertThat(d.prev(Boolean.TRUE), is(Boolean.FALSE));
    assertThat(d.comparator().compare(false, true) < 0, is(true));
    assertThat(d.comparator().compare(true, true), is(0));
    assertThat(d.comparator().compare(true, false) > 0, is(true));
  }

  @Test
  void testUnitDiscrete() {
    Discrete<Object> d = Discretes.discreteFor(typeSystem, PrimitiveType.UNIT);
    assertThat(d.minValue(), is(Unit.INSTANCE));
    assertThat(d.maxValue(), is(Unit.INSTANCE));
    assertThat(d.next(Unit.INSTANCE), nullValue());
    assertThat(d.prev(Unit.INSTANCE), nullValue());
    assertThat(d.comparator().compare(Unit.INSTANCE, Unit.INSTANCE), is(0));
  }

  @Test
  void testTupleDiscrete() {
    // bool * int: lexicographic order, rightmost increments first
    final RecordLikeType boolIntType =
        typeSystem.tupleType(PrimitiveType.BOOL, PrimitiveType.INT);
    final Discrete<Object> d = Discretes.discreteFor(typeSystem, boolIntType);

    // min/max
    assertThat(d.minValue(), is(list(Boolean.FALSE, Integer.MIN_VALUE)));
    assertThat(d.maxValue(), is(list(Boolean.TRUE, Integer.MAX_VALUE)));

    // next: increment rightmost (int) component first
    final List<Object> falseZero = ImmutableList.of(false, 0);
    final List<Object> falseOne = ImmutableList.of(false, 1);
    assertThat(d.next(falseZero), is(falseOne));

    // prev: decrement rightmost component
    assertThat(d.prev(falseOne), is(falseZero));

    // comparator: false < true, then int order
    final List<Object> trueZero = ImmutableList.of(true, 0);
    assertThat(d.comparator().compare(falseZero, trueZero) < 0, is(true));
    assertThat(d.comparator().compare(falseZero, falseOne) < 0, is(true));
    assertThat(d.comparator().compare(falseZero, falseZero), is(0));
  }

  @Test
  void testBoolTupleDiscrete() {
    // bool * bool: fully bounded, can enumerate completely
    final RecordLikeType boolBoolType =
        typeSystem.tupleType(PrimitiveType.BOOL, PrimitiveType.BOOL);
    final Discrete<Object> d = Discretes.discreteFor(typeSystem, boolBoolType);

    final List<Object> ff = ImmutableList.of(false, false);
    final List<Object> ft = ImmutableList.of(false, true);
    final List<Object> tf = ImmutableList.of(true, false);
    final List<Object> tt = ImmutableList.of(true, true);

    assertThat(d.minValue(), is(ff));
    assertThat(d.maxValue(), is(tt));
    assertThat(d.next(ff), is(ft));
    assertThat(d.next(ft), is(tf));
    assertThat(d.next(tf), is(tt));
    assertThat(d.next(tt), nullValue());
    assertThat(d.prev(ff), nullValue());
    assertThat(d.prev(ft), is(ff));
    assertThat(d.prev(tf), is(ft));
    assertThat(d.prev(tt), is(tf));
  }

  @Test
  void testDescendingDiscrete() {
    initBuiltIns();
    final Type descendingScheme = typeSystem.descending();
    final DataType descendingInt =
        (DataType) typeSystem.apply(descendingScheme, PrimitiveType.INT);
    final Discrete<Object> d = Discretes.discreteFor(typeSystem, descendingInt);

    // In descending order, min is the largest int, max is smallest.
    assertThat(d.minValue(), is(list("DESC", Integer.MAX_VALUE)));
    assertThat(d.maxValue(), is(list("DESC", Integer.MIN_VALUE)));

    // Runtime values: ["DESC", innerValue]
    final List<Object> desc5 = ImmutableList.of("DESC", 5);
    final List<Object> desc4 = ImmutableList.of("DESC", 4);
    final List<Object> desc6 = ImmutableList.of("DESC", 6);

    // next in descending order = prev in ascending order (i.e. 5 → 4)
    assertThat(d.next(desc5), is(desc4));
    // prev in descending order = next in ascending order (i.e. 5 → 6)
    assertThat(d.prev(desc5), is(desc6));

    // comparator: 5 DESC > 3 DESC (larger number comes first)
    assertThat(d.comparator().compare(desc5, desc4) < 0, is(true));
    assertThat(d.comparator().compare(desc5, desc5), is(0));
    assertThat(d.comparator().compare(desc4, desc5) > 0, is(true));
  }

  @Test
  void testEnumDiscrete() {
    initBuiltIns();
    final Type orderType = typeSystem.order();
    final Discrete<Object> d = Discretes.discreteFor(typeSystem, orderType);

    // order has 3 constructors: LESS, EQUAL, GREATER
    final List<Object> less = ImmutableList.of("LESS");
    final List<Object> equal = ImmutableList.of("EQUAL");
    final List<Object> greater = ImmutableList.of("GREATER");

    assertThat(d.minValue(), is(less));
    assertThat(d.maxValue(), is(greater));
    assertThat(d.next(less), is(equal));
    assertThat(d.next(equal), is(greater));
    assertThat(d.next(greater), nullValue());
    assertThat(d.prev(less), nullValue());
    assertThat(d.prev(equal), is(less));
    assertThat(d.prev(greater), is(equal));
    assertThat(d.comparator().compare(less, equal) < 0, is(true));
    assertThat(d.comparator().compare(equal, equal), is(0));
    assertThat(d.comparator().compare(greater, equal) > 0, is(true));
  }

  @Test
  void testRealNotDiscrete() {
    assertThrows(
        CompileException.class,
        () -> Discretes.discreteFor(typeSystem, PrimitiveType.REAL));
  }

  @Test
  void testStringNotDiscrete() {
    assertThrows(
        CompileException.class,
        () -> Discretes.discreteFor(typeSystem, PrimitiveType.STRING));
  }
}

// End DiscreteTest.java
