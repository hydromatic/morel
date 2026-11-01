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

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link FromBuilder}, which builds a query as a relational tree from
 * expressions written over the names the query binds.
 *
 * <p>Its duty is the names: each expression it is given reads bindings by name,
 * and it rewrites them to paths into the tree's element. The tree's language is
 * tested from Morel text in {@code rel-tree.smli}; this tests the bookkeeping.
 */
public class FromBuilderTest {
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();

    {
      // Register 'bag' and the other built-in data types, which a built-in
      // function's type, such as count's, is written over.
      BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    }

    final PrimitiveType intType = PrimitiveType.INT;
    final PrimitiveType stringType = PrimitiveType.STRING;
    final RecordLikeType pairType =
        typeSystem.tupleType(ImmutableList.of(intType, stringType));
    /** A list of pairs, {@code [(1, "a"), (2, "b")]}. */
    final Core.Exp pairs =
        core.list(
            typeSystem,
            pairType,
            ImmutableList.of(
                core.tuple(pairType, intLiteral(1), stringLiteral("a")),
                core.tuple(pairType, intLiteral(2), stringLiteral("b"))));
    /** A record type, {@code {deptno:int, name:string}}. */
    final RecordType empType =
        (RecordType)
            typeSystem.recordType(
                PairList.copyOf("deptno", intType, "name", stringType));
    /** A list of records, {@code [{deptno=10,name="Fred"}, ...]}. */
    final Core.Exp emps =
        core.list(
            typeSystem,
            empType,
            ImmutableList.of(emp(10, "Fred"), emp(20, "Velma")));
    /** A list of scalars, {@code [1, 2, 3]}. */
    final Core.Exp ints =
        core.list(typeSystem, intLiteral(1), intLiteral(2), intLiteral(3));

    Core.Exp emp(int deptno, String name) {
      return core.record(
          typeSystem,
          PairList.copyOf(
              "deptno", intLiteral(deptno), "name", stringLiteral(name)));
    }

    Core.Exp intLiteral(int i) {
      return core.intLiteral(BigDecimal.valueOf(i));
    }

    Core.Exp stringLiteral(String s) {
      return core.stringLiteral(s);
    }

    Core.IdPat idPat(String name, Type type) {
      return core.idPat(type, name, 0);
    }

    Core.Pat tuplePat(Core.IdPat... pats) {
      return core.tuplePat(typeSystem, ImmutableList.copyOf(pats));
    }

    /** Returns {@code count over ()}. */
    Core.Aggregate count() {
      return core.aggregate(
          Pos.ZERO,
          intType,
          core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_COUNT),
          null);
    }
  }

  /**
   * A scan under a tuple pattern binds a name per component, and an expression
   * that reads a name is rewritten to read the component: {@code p} is {@code
   * #1 $0}, {@code s} is {@code #2 $0}.
   */
  @Test
  void testScanWhereYield() {
    final Fixture f = new Fixture();
    final Core.IdPat p = f.idPat("p", f.intType);
    final Core.IdPat s = f.idPat("s", f.stringType);
    final FromBuilder fb = core.fromBuilder(f.typeSystem);
    fb.scan(f.tuplePat(p, s), f.pairs)
        .where(core.equal(f.typeSystem, core.id(p), f.intLiteral(1)))
        .yield_(core.id(s));
    final Core.Exp exp = fb.build();
    assertThat(
        exp,
        hasToString(
            "project r [#2 r]\n"
                + "  filter r [#1 r = 1]\n"
                + "    [(1, \"a\"), (2, \"b\")]\n"));
    assertThat(exp.type, hasToString("string list"));
  }

  /**
   * Three ways to read a binding: an id of the pattern that bound it, {@link
   * FromBuilder#field(String)}, and {@link FromBuilder#field(int)} in the order
   * the bindings were made. After a second scan the element is a tuple, and a
   * binding from the first scan is a component of it.
   */
  @Test
  void testFieldByNameAndOrdinal() {
    final Fixture f = new Fixture();
    final Core.IdPat e = f.idPat("e", f.empType);
    final Core.IdPat p = f.idPat("p", f.intType);
    final Core.IdPat s = f.idPat("s", f.stringType);
    final FromBuilder fb = core.fromBuilder(f.typeSystem);
    fb.scan(e, f.emps);
    assertThat(fb.field("e"), hasToString("e"));
    assertThat(fb.field(0), hasToString("e"));
    assertThat(fb.field("e", "deptno"), hasToString("#deptno e"));

    fb.scan(f.tuplePat(p, s), f.pairs);
    assertThat(fb.field("e"), hasToString("#1 r"));
    assertThat(fb.field("p"), hasToString("#1 (#2 r)"));
    assertThat(fb.field(2), hasToString("#2 (#2 r)"));
    assertThat(fb.field("e", "name"), hasToString("#name (#1 r)"));
    assertThrows(IllegalArgumentException.class, () -> fb.field("q"));

    // The id and the field are interchangeable in an expression.
    fb.where(core.equal(f.typeSystem, fb.field("e", "deptno"), core.id(p)))
        .yield_(
            core.record(
                f.typeSystem,
                PairList.copyOf(
                    "name", fb.field("e", "name"), "s", core.id(s))));
    final Core.Exp exp = fb.build();
    assertThat(
        exp,
        hasToString(
            "project r [{name = #name (#1 r), s = #2 (#2 r)}]\n"
                + "  filter r [#deptno (#1 r) = #1 (#2 r)]\n"
                + "    join e r\n"
                + "      [{deptno = 10, name = \"Fred\"},"
                + " {deptno = 20, name = \"Velma\"}]\n"
                + "      [(1, \"a\"), (2, \"b\")]\n"));
    assertThat(exp.type, hasToString("{name:string, s:string} list"));
  }

  /**
   * A record pattern binds each field by name, and the names survive a join
   * that puts the record in a tuple.
   */
  @Test
  void testRecordPatternFields() {
    final Fixture f = new Fixture();
    final Core.IdPat deptno = f.idPat("deptno", f.intType);
    final Core.IdPat name = f.idPat("name", f.stringType);
    final Core.IdPat p = f.idPat("p", f.intType);
    final Core.IdPat s = f.idPat("s", f.stringType);
    final FromBuilder fb = core.fromBuilder(f.typeSystem);
    fb.scan(core.recordPat(f.empType, ImmutableList.of(deptno, name)), f.emps);
    assertThat(fb.field("deptno"), hasToString("#deptno r"));
    assertThat(fb.field("name"), hasToString("#name r"));

    fb.scan(f.tuplePat(p, s), f.pairs);
    assertThat(fb.field("deptno"), hasToString("#deptno (#1 r)"));
    assertThat(fb.field("s"), hasToString("#2 (#2 r)"));
    fb.where(core.equal(f.typeSystem, core.id(deptno), core.id(p)))
        .yield_(core.id(name));
    final Core.Exp exp = fb.build();
    assertThat(
        exp,
        hasToString(
            "project r [#name (#1 r)]\n"
                + "  filter r [#deptno (#1 r) = #1 (#2 r)]\n"
                + "    join r r_1\n"
                + "      [{deptno = 10, name = \"Fred\"},"
                + " {deptno = 20, name = \"Velma\"}]\n"
                + "      [(1, \"a\"), (2, \"b\")]\n"));
    assertThat(exp.type, hasToString("string list"));
  }

  /**
   * A scan whose collection reads an earlier binding is a dependent join: the
   * inner query is built by a builder of its own, over the outer binding's
   * name, and the outer builder rewrites that name to its id.
   */
  @Test
  void testCorrelatedSubquery() {
    final Fixture f = new Fixture();
    final Core.IdPat e = f.idPat("e", f.empType);
    final Core.IdPat p = f.idPat("p", f.intType);
    final Core.IdPat s = f.idPat("s", f.stringType);
    final Core.IdPat t = f.idPat("t", f.stringType);
    final FromBuilder inner = core.fromBuilder(f.typeSystem);
    inner
        .scan(f.tuplePat(p, s), f.pairs)
        .where(
            core.equal(
                f.typeSystem,
                core.id(p),
                core.field(f.typeSystem, core.id(e), 0)))
        .yield_(core.id(s));
    final FromBuilder fb = core.fromBuilder(f.typeSystem);
    fb.scan(e, f.emps).scan(t, inner.build()).yield_(core.id(t));
    final Core.Exp exp = fb.build();
    // The join carries a binder for its left id, which the subquery reads
    // under a generated name; the plan printer renumbers it.
    assertThat(
        ((Core.Rel) exp).describe(f.typeSystem),
        is(
            "project r [#2 r]\n" //
                + "  join e t\n"
                + "    [{deptno = 10, name = \"Fred\"}, {deptno = 20, name = \"Velma\"}]\n"
                + "    project r [#2 r]\n"
                + "      filter r [#1 r = #deptno e]\n"
                + "        [(1, \"a\"), (2, \"b\")]\n"
                + ""));
    assertThat(exp.type, hasToString("string list"));
  }

  /**
   * A group over a record scan: the keys and aggregates are the bindings after
   * it, and the element is a record of them.
   */
  @Test
  void testGroup() {
    final Fixture f = new Fixture();
    final Core.IdPat e = f.idPat("e", f.empType);
    final FromBuilder fb = core.fromBuilder(f.typeSystem);
    fb.scan(e, f.emps);
    final SortedMap<String, Core.Exp> keys = new TreeMap<>();
    keys.put("d", fb.field("e", "deptno"));
    final SortedMap<String, Core.Aggregate> aggregates = new TreeMap<>();
    aggregates.put("c", f.count());
    fb.group(false, keys, aggregates);
    assertThat(fb.field("d"), hasToString("#d r"));
    assertThat(fb.field("c"), hasToString("#c r"));
    fb.where(core.greaterThan(f.typeSystem, fb.field("c"), f.intLiteral(1)));
    final Core.Exp exp = fb.build();
    assertThat(
        ((Core.Rel) exp).describe(f.typeSystem),
        is(
            "filter r [#c r > 1]\n" //
                + "  group e [d = #deptno e] [c = #count Relational]\n"
                + "    [{deptno = 10, name = \"Fred\"}, {deptno = 20, name = \"Velma\"}]\n"
                + ""));
    assertThat(exp.type, hasToString("{c:int, d:int} list"));
  }

  /**
   * Scalars in and out: a scan over a list of {@code int} binds one name to the
   * whole element, and a yield of a value that is not a binding leaves nothing
   * to name.
   */
  @Test
  void testScalars() {
    final Fixture f = new Fixture();
    final Core.IdPat i = f.idPat("i", f.intType);
    final FromBuilder fb = core.fromBuilder(f.typeSystem);
    fb.scan(i, f.ints);
    assertThat(fb.field("i"), hasToString("i"));
    fb.where(core.greaterThan(f.typeSystem, core.id(i), f.intLiteral(1)))
        .yield_(core.equal(f.typeSystem, fb.field("i"), f.intLiteral(2)));
    assertThrows(IllegalArgumentException.class, () -> fb.field("i"));
    final Core.Exp exp = fb.build();
    assertThat(
        exp,
        hasToString(
            "project i [i = 2]\n" //
                + "  filter i [i > 1]\n"
                + "    [1, 2, 3]\n"
                + ""
                + ""));
    assertThat(exp.type, hasToString("bool list"));
  }

  /**
   * Two scans that share a name: the second renames it and tests it against the
   * first, as the grounding engine's chain does.
   */
  @Test
  void testChainDistinct() {
    final Fixture f = new Fixture();
    final Core.IdPat p = f.idPat("p", f.intType);
    final Core.IdPat x = f.idPat("x", f.stringType);
    final Core.IdPat p1 = f.idPat("p'1", f.intType);
    final Core.IdPat y = f.idPat("y", f.stringType);
    final FromBuilder fb = core.fromBuilder(f.typeSystem);
    fb.scan(f.tuplePat(p, x), f.pairs)
        .scan(
            f.tuplePat(p1, y),
            f.pairs,
            core.equal(f.typeSystem, core.id(p1), core.id(p)))
        .where(core.notEqual(f.typeSystem, core.id(x), core.id(y)));
    final List<Core.IdPat> names = ImmutableList.of(x, y);
    final Core.Exp row = core.recordOrAtom(f.typeSystem, names);
    fb.yield_(row).distinct().order(row);
    final Core.Exp exp = fb.build();
    assertThat(
        exp,
        hasToString(
            "sort r [r]\n"
                + "  group r [x = #x r, y = #y r]\n"
                + "    project r [{x = #2 (#1 r), y = #2 (#2 r)}]\n"
                + "      filter r [#2 (#1 r) <> #2 (#2 r)]\n"
                + "        join r r_1 [#1 r_1 = #1 r]\n"
                + "          [(1, \"a\"), (2, \"b\")]\n"
                + "          [(1, \"a\"), (2, \"b\")]\n"));
    assertThat(exp.type, hasToString("{x:string, y:string} list"));
  }
}

// End FromBuilderTest.java
