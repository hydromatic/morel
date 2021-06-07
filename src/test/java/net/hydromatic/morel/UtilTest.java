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

import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.Util;

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.util.Folder;
import net.hydromatic.morel.util.MapList;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Static;
import net.hydromatic.morel.util.TailList;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static net.hydromatic.morel.ast.AstBuilder.ast;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;

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
    assertThat(tailList.toString(), is("[c]"));

    tailList.set(0, "d");
    assertThat(tailList.toString(), is("[d]"));

    tailList.add(0, "e");
    assertThat(tailList.toString(), is("[e, d]"));

    final StringBuilder s = new StringBuilder();
    for (String item : tailList) {
      s.append(item);
    }
    assertThat(s.toString(), is("ed"));

    tailList.add("f");
    assertThat(tailList.toString(), is("[e, d, f]"));
    assertThat(list.size(), is(5));

    tailList.addAll(Arrays.asList("x", "y", "z"));
    assertThat(tailList.toString(), is("[e, d, f, x, y, z]"));

    tailList.clear();
    assertThat(tailList.size(), is(0));
    assertThat(tailList.isEmpty(), is(true));
    assertThat(list.size(), is(2));
    assertThat(list.isEmpty(), is(false));
  }

  @Test void testOrd() {
    final List<String> abc = Arrays.asList("a", "b", "c");
    final StringBuilder buf = new StringBuilder();
    Ord.forEach(abc, (e, i) ->
        buf.append(i).append("#").append(e).append(";"));
    assertThat(buf.toString(), is("0#a;1#b;2#c;"));
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
    assertThat(Folder.combineAll(list).toString(), is("\"a\" @ \"b\" @ \"c\""));

    list.clear();
    Folder.start(list, ast.stringLiteral(Pos.ZERO, "a"));
    Folder.cons(list, ast.stringLiteral(Pos.ZERO, "b"));
    assertThat(Folder.combineAll(list).toString(), is("\"a\" :: \"b\""));
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
}

// End UtilTest.java
