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

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RangeExtent;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Folder;
import net.hydromatic.morel.util.MapList;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.Static;
import net.hydromatic.morel.util.TailList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import org.apache.calcite.runtime.FlatLists;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.Util;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static net.hydromatic.morel.ast.AstBuilder.ast;
import static net.hydromatic.morel.eval.Codes.isNegative;
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Static.nextPowerOfTwo;
import static net.hydromatic.morel.util.Static.transform;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.fail;

/** Tests for various utility classes. */
public class UtilTest {
  /** Tests {@link TailList}. */
  @Test void testTailList() {
    final List<String> list = new ArrayList<>();
    list.add("a");
    list.add("b");

    final List<String> tailList = new TailList<>(list);
    assertThat(tailList.size(), is(0));
    list.add("c");
    assertThat(tailList.size(), is(1));
    assertThat(tailList.get(0), is("c"));
    assertThat(tailList, hasToString("[c]"));

    tailList.set(0, "d");
    assertThat(tailList, hasToString("[d]"));

    tailList.add(0, "e");
    assertThat(tailList, hasToString("[e, d]"));

    final StringBuilder s = new StringBuilder();
    for (String item : tailList) {
      s.append(item);
    }
    assertThat(s, hasToString("ed"));

    tailList.add("f");
    assertThat(tailList, hasToString("[e, d, f]"));
    assertThat(list.size(), is(5));

    tailList.addAll(Arrays.asList("x", "y", "z"));
    assertThat(tailList, hasToString("[e, d, f, x, y, z]"));

    tailList.clear();
    assertThat(tailList.size(), is(0));
    assertThat(tailList.isEmpty(), is(true));
    assertThat(list.size(), is(2));
    assertThat(list.isEmpty(), is(false));
  }

  @Test void testOrd() {
    final List<String> abc = Arrays.asList("a", "b", "c");
    final StringBuilder buf = new StringBuilder();
    forEachIndexed(abc, (e, i) ->
        buf.append(i).append("#").append(e).append(";"));
    assertThat(buf, hasToString("0#a;1#b;2#c;"));
  }

  @Test void testMapList() {
    final List<String> abc =
        MapList.of(3, i -> "" + (char) ('a' + i));
    assertThat(abc.size(), is(3));
    assertThat(abc.get(0), is("a"));
    assertThat(abc.get(2), is("c"));
    assertThat(String.join(",", abc), is("a,b,c"));
  }

  @Test void testFolder() {
    final List<Folder<Ast.Exp>> list = new ArrayList<>();
    Folder.start(list, ast.stringLiteral(Pos.ZERO, "a"));
    Folder.at(list, ast.stringLiteral(Pos.ZERO, "b"));
    Folder.at(list, ast.stringLiteral(Pos.ZERO, "c"));
    assertThat(Folder.combineAll(list), hasToString("\"a\" @ \"b\" @ \"c\""));

    list.clear();
    Folder.start(list, ast.stringLiteral(Pos.ZERO, "a"));
    Folder.cons(list, ast.stringLiteral(Pos.ZERO, "b"));
    assertThat(Folder.combineAll(list), hasToString("\"a\" :: \"b\""));
  }

  /** Tests {@link Static#shorterThan(Iterable, int)}. */
  @Test void testShorterThan() {
    // A list of length n is shorter than n + 1, but not shorter than n
    final List<Integer> list2 = Arrays.asList(0, 1);
    assertThat(Static.shorterThan(list2, 1), is(false));
    assertThat(Static.shorterThan(list2, 2), is(false));
    assertThat(Static.shorterThan(list2, 3), is(true));

    // Collections of length 3
    final List<Integer> list3 = ImmutableIntList.identity(3);
    final HashSet<Integer> set3 = new HashSet<>(list3);
    final Iterable<Integer> iterable3 = list3::iterator;
    assertThat(iterable3, not(instanceOf(Collection.class)));
    checkShorterThan(list3, 3);
    checkShorterThan(set3, 3);
    checkShorterThan(iterable3, 3);

    // Collections of length 1
    final Set<String> set1 = Collections.singleton("x");
    final List<String> list1 = new ArrayList<>(set1);
    final Iterable<String> iterable1 = list1::iterator;
    assertThat(iterable1, not(instanceOf(Collection.class)));
    checkShorterThan(list1, 1);
    checkShorterThan(set1, 1);
    checkShorterThan(iterable1, 1);

    // Empty collections
    final Set<String> set0 = Collections.emptySet();
    final List<String> list0 = Collections.emptyList();
    final Iterable<String> iterable0 = set0::iterator;
    assertThat(iterable0, not(instanceOf(Collection.class)));
    checkShorterThan(list0, 0);
    checkShorterThan(set0, 0);
    checkShorterThan(iterable0, 0);

    // Very large collections (too large to materialize)
    final int bigSize = Integer.MAX_VALUE - 10;
    final List<Integer> listBig = Util.range(bigSize);
    final Iterable<Integer> iterableBig = listBig::iterator;
    assertThat(iterableBig, not(instanceOf(Collection.class)));
    checkShorterThan(listBig, bigSize);
  }

  private <E> void checkShorterThan(Iterable<E> iterable, int size) {
    assertThat(Static.shorterThan(iterable, -1), is(size < -1));
    assertThat(Static.shorterThan(iterable, 0), is(size < 0));
    assertThat(Static.shorterThan(iterable, 1), is(size < 1));
    assertThat(Static.shorterThan(iterable, 2), is(size < 2));
    assertThat(Static.shorterThan(iterable, 3), is(size < 3));
    assertThat(Static.shorterThan(iterable, 4), is(size < 4));
    assertThat(Static.shorterThan(iterable, 1_000_000), is(size < 1_000_000));
  }

  /** Tests {@link Static#find(List, Predicate)}. */
  @Test void testFind() {
    final List<Integer> list = Arrays.asList(1, 7, 3);
    final List<Integer> emptyList = Collections.emptyList();
    assertThat(Static.find(list, i -> i > 0), is(0));
    assertThat(Static.find(list, i -> i > 1), is(1));
    assertThat(Static.find(list, i -> i > 10), is(-1));
    assertThat(Static.find(emptyList, i -> i > 0), is(-1));
  }

  /** Unit tests for {@link Pos}. */
  @Test void testPos() {
    final BiConsumer<String, String> check = (s, posString) -> {
      final Pair<String, Pos> pos = Pos.split(s, '$', "stdIn");
      assertThat(pos.left, is("abcdefgh"));
      assertThat(pos.right, notNullValue());
      assertThat(pos.right, hasToString(posString));
    };
    // starts and ends in middle
    check.accept("abc$def$gh", "stdIn:1.4-1.7");
    // ends at end
    check.accept("abc$defgh$", "stdIn:1.4-1.9");
    // starts at start
    check.accept("$abc$defgh", "stdIn:1.1-1.4");
    // one character long
    check.accept("abc$d$efgh", "stdIn:1.4");

    final BiConsumer<String, String> check2 = (s, posString) -> {
      final Pair<String, Pos> pos = Pos.split(s, '$', "stdIn");
      assertThat(pos.left,
          is("abc\n"
              + "de\n"
              + "\n"
              + "fgh"));
      assertThat(pos.right, notNullValue());
      assertThat(pos.right, hasToString(posString));
    };
    // start of line
    check2.accept("abc\n"
        + "$de$\n"
        + "\n"
        + "fgh", "stdIn:2.1-2.3");
    // spans multiple lines
    check2.accept("abc\n"
        + "d$e\n"
        + "\n"
        + "fg$h", "stdIn:2.2-4.3");

    // too many, too few
    Consumer<String> checkTooFew = s -> {
      try {
        final Pair<String, Pos> pos4 = Pos.split(s, '$', "stdIn");
        fail("expected error, got " + pos4);
      } catch (IllegalArgumentException e) {
        assertThat(e.getMessage(),
            is("expected exactly two occurrences of delimiter, '$'"));
      }
    };
    checkTooFew.accept("$abc$de$f");
    checkTooFew.accept("abc$def");
    checkTooFew.accept("abcdef");
  }

  /** Tests {@link Static#nextPowerOfTwo(int)}. */
  @Test void testPower() {
    assertThat(nextPowerOfTwo(0), is(1));
    assertThat(nextPowerOfTwo(-1), is(1)); // 2^16
    assertThat(nextPowerOfTwo(-2), is(1)); // 2^16
    assertThat(nextPowerOfTwo(-3), is(1)); // 2^16
    assertThat(nextPowerOfTwo(-4), is(1)); // 2^16
    assertThat(nextPowerOfTwo(-65_536), is(1)); // 2^16

    assertThat(nextPowerOfTwo(1), is(2));
    assertThat(nextPowerOfTwo(2), is(4));
    assertThat(nextPowerOfTwo(3), is(4));
    assertThat(nextPowerOfTwo(4), is(8));
    assertThat(nextPowerOfTwo(5), is(8));

    assertThat(nextPowerOfTwo(16_383), is(16_384)); // 2^14
    assertThat(nextPowerOfTwo(16_384), is(32_768)); // 2^15
    assertThat(nextPowerOfTwo(16_384), is(32_768));
    assertThat(nextPowerOfTwo(32_768), is(65_536)); // 2^16

    assertThat(nextPowerOfTwo(1_073_741_823), is(1_073_741_824)); // 2^31
    assertThat(nextPowerOfTwo(1_073_741_824), is(-2_147_483_648)); // 2^32
    assertThat(nextPowerOfTwo(2_147_483_647), is(-2_147_483_648)); // 2^32
    assertThat(nextPowerOfTwo(-2_147_483_648), is(1)); // 2^0
  }

  @Test void testTransform() {
    final List<String> list = Arrays.asList("john", "paul", "george", "ringo");
    assertThat(transform(list, String::length), is(Arrays.asList(4, 4, 6, 5)));
    assertThat(transform(Collections.emptyList(), String::length),
        is(Collections.emptyList()));
  }

  /** Tests that {@code Real.toString} returns values consistent with JDK 19 and
   * later, incorporating the fix to
   * <a href="https://bugs.openjdk.org/browse/JDK-4511638">[JDK-4511638]
   * Double.toString(double) sometimes produces incorrect results</a>. */
  @Test void testToString() {
    Function<String, String> fn = s -> {
      float f = Float.parseFloat(s);
      return Codes.floatToString(f);
    };
    assertThat(fn.apply("1.17549435E-38"), is("1.1754944E~38"));
    assertThat(fn.apply("1.1754944E-38"), is("1.1754944E~38"));

    assertThat(fn.apply("1.23456795E12"), is("1.234568E12"));
    assertThat(fn.apply("1.234568E12"), is("1.234568E12"));

    assertThat(fn.apply("1.23456791E11"), is("1.2345679E11"));
    assertThat(fn.apply("1.2345679E11"), is("1.2345679E11"));

    assertThat(fn.apply("1.23456788E10"), is("1.2345679E10"));
    assertThat(fn.apply("1.2345679E10"), is("1.2345679E10"));

    assertThat(fn.apply("1.23456792E8"), is("1.2345679E8"));
    assertThat(fn.apply("1.2345679E8"), is("1.2345679E8"));

    assertThat(fn.apply("1.0"), is("1.0"));
    assertThat(fn.apply("-1.234"), is("~1.234"));
    assertThat(fn.apply("-1.234e-10"), is("~1.234E~10"));
  }

  /** Tests the {@link Codes#isNegative(float)} function,
   * which is used to implement {@code Real.signBit}. */
  @SuppressWarnings("ConstantValue")
  @Test void testFloatBit() {
    assertThat(isNegative(0f), is(false));
    assertThat(isNegative(3.5f), is(false));
    assertThat(isNegative(Float.POSITIVE_INFINITY), is(false));
    assertThat(isNegative(-0f), is(true));
    assertThat(isNegative(-10.25f), is(true));
    assertThat(isNegative(Float.NEGATIVE_INFINITY), is(true));

    // The standard basis library is unclear, but in SMLNJ and Mlton
    // nan is negative, and we do the same.
    assertThat(isNegative(Float.NaN), is(true));
    // In SMLNJ and Mlton ~nan is positive, and we do the same.
    assertThat(isNegative(Codes.NEGATIVE_NAN), is(false));
    assertThat(Float.isNaN(Float.NaN), is(true));
    assertThat(Float.isNaN(Codes.NEGATIVE_NAN), is(true));
  }

  @Test void testPairAllMatch() {
    final List<Integer> list1 = Arrays.asList(1, 3, 5);
    final List<Integer> list2 = Arrays.asList(2, 3, 4);
    final List<Integer> list0 = Collections.emptyList();
    assertThat(Pair.anyMatch(list1, list2, Objects::equals), is(true));
    assertThat(Pair.allMatch(list1, list2, Objects::equals), is(false));
    assertThat(Pair.noneMatch(list1, list2, Objects::equals), is(false));

    assertThat(Pair.anyMatch(list1, list2, (i, j) -> i == 0), is(false));
    assertThat(Pair.allMatch(list1, list2, (i, j) -> i == 0), is(false));
    assertThat(Pair.noneMatch(list1, list2, (i, j) -> i == 0), is(true));

    assertThat(Pair.anyMatch(list1, list2, (i, j) -> i > 0), is(true));
    assertThat(Pair.allMatch(list1, list2, (i, j) -> i > 0), is(true));
    assertThat(Pair.noneMatch(list1, list2, (i, j) -> i > 0), is(false));

    assertThat(Pair.anyMatch(list0, list0, (i, j) -> true), is(false));
    assertThat(Pair.allMatch(list0, list0, (i, j) -> true), is(true));
    assertThat(Pair.noneMatch(list0, list0, (i, j) -> true), is(true));
  }

  @SuppressWarnings("UnstableApiUsage")
  @Test void testRangeExtent() {
    final TypeSystem typeSystem = new TypeSystem();
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());

    // Integer range [(4, 7]]
    final Range<BigDecimal> range =
        Range.openClosed(BigDecimal.valueOf(4), BigDecimal.valueOf(7));
    final RangeExtent rangeExtent =
        new RangeExtent(typeSystem, PrimitiveType.INT,
            ImmutableMap.of("/", ImmutableRangeSet.of(range)));
    assertThat(rangeExtent.iterable, notNullValue());
    assertThat(Lists.newArrayList(rangeExtent.iterable),
        is(Arrays.asList(5, 6, 7)));

    // Integer range set [(4, 7], [10, 12]]
    final Range<BigDecimal> range2 =
        Range.closed(BigDecimal.valueOf(10), BigDecimal.valueOf(12));
    final RangeExtent rangeExtent2 =
        new RangeExtent(
            typeSystem, PrimitiveType.INT,
            ImmutableMap.of("/",
                ImmutableRangeSet.unionOf(ImmutableList.of(range, range2))));
    assertThat(rangeExtent2.iterable, notNullValue());
    assertThat(Lists.newArrayList(rangeExtent2.iterable),
        is(Arrays.asList(5, 6, 7, 10, 11, 12)));

    // Boolean range set
    final Range<Boolean> range3 = Range.closed(false, true);
    final RangeExtent rangeExtent3 =
        new RangeExtent(typeSystem, PrimitiveType.BOOL,
            ImmutableMap.of("/", ImmutableRangeSet.of(range3)));
    assertThat(rangeExtent3.iterable, notNullValue());
    assertThat(Lists.newArrayList(rangeExtent3.iterable),
        is(Arrays.asList(false, true)));

    // Range set of (Boolean, Boolean) tuples
    final Range<Comparable> range4 =
        Range.closed((Comparable) FlatLists.of(false, true),
            (Comparable) FlatLists.of(true, true));
    final RangeExtent rangeExtent4 =
        new RangeExtent(typeSystem,
            typeSystem.tupleType(PrimitiveType.BOOL, PrimitiveType.BOOL),
            ImmutableMap.of("/", ImmutableRangeSet.of(range4)));
    assertThat(rangeExtent4.iterable, notNullValue());
    assertThat(Lists.newArrayList(rangeExtent4.iterable),
        is(
            Arrays.asList(FlatLists.of(false, true),
                FlatLists.of(true, false), FlatLists.of(true, true))));

    // Range set of (boolean option, int) tuples
    final RangeExtent rangeExtent5 =
        new RangeExtent(typeSystem,
            typeSystem.tupleType(
                typeSystem.option(PrimitiveType.BOOL),
                PrimitiveType.INT),
            ImmutableMap.of("/1/SOME/",
                ImmutableRangeSet.of(Range.singleton(true)),
                "/2/",
                ImmutableRangeSet.of(
                    Range.closed(BigDecimal.valueOf(4),
                        BigDecimal.valueOf(6)))));
    assertThat(rangeExtent5.iterable, notNullValue());
    assertThat(ImmutableList.copyOf(rangeExtent5.iterable),
        hasToString("[[[NONE], 4], [[NONE], 5], [[NONE], 6],"
            + " [[SOME, true], 4], [[SOME, true], 5], [[SOME, true], 6]]"));
  }
}

// End UtilTest.java
