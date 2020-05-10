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
import net.hydromatic.morel.util.Folder;
import net.hydromatic.morel.util.MapList;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.TailList;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static net.hydromatic.morel.ast.AstBuilder.ast;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

/** Tests for various utility classes. */
public class UtilTest {
  /** Tests {@link TailList}. */
  @Test public void testTailList() {
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

  @Test public void testOrd() {
    final List<String> abc = Arrays.asList("a", "b", "c");
    final StringBuilder buf = new StringBuilder();
    Ord.forEach(abc, (e, i) ->
        buf.append(i).append("#").append(e).append(";"));
    assertThat(buf.toString(), is("0#a;1#b;2#c;"));
  }

  @Test public void testMapList() {
    final List<String> abc =
        MapList.of(3, i -> "" + (char) ('a' + i));
    assertThat(abc.size(), is(3));
    assertThat(abc.get(0), is("a"));
    assertThat(abc.get(2), is("c"));
    assertThat(String.join(",", abc), is("a,b,c"));
  }

  @Test public void testFolder() {
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
}

// End UtilTest.java
