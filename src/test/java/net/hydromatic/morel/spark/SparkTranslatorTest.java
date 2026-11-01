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
package net.hydromatic.morel.spark;

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Consumer;
import net.hydromatic.morel.BuiltInDataSet;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.compile.Inliner;
import net.hydromatic.morel.compile.Resolver;
import net.hydromatic.morel.compile.TypeResolver;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.foreign.MockSparkConnection;
import net.hydromatic.morel.foreign.SparkCatalog;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link SparkTranslator} against the plans that {@code
 * spark/seed-plans.txt} records.
 *
 * <p>Those plans were captured from the PySpark client and executed against a
 * real server, so they are the authority: a plan Morel produces is right if
 * Spark would have produced it.
 */
public class SparkTranslatorTest {
  /** A query compiled as far as core, with the type system that compiled it. */
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();
    final Core.Rel rel;

    Fixture(String ml) {
      // The catalog types its tables as it discovers them, and a salary is a
      // decimal, which is a built-in datatype: the type system has to know
      // them before anything asks.
      BuiltIn.dataTypes(typeSystem, new ArrayList<>());
      final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
      final AstNode statement = parser.statementEofSafe();
      final Map<String, ForeignValue> dataSets =
          Calcite.withDataSets(ImmutableMap.of("scott", BuiltInDataSet.SCOTT))
              .foreignValues();
      // A query reaches a table of the server through a catalog, and the
      // translator scans a table only if that is where the collection came
      // from, so "scott" here is a database of the offline catalog rather
      // than the data set itself. The offline catalog's databases are the
      // session's data sets, so the query reads
      // as it
      // would in a script and the table it names is the one Spark knows.
      final SparkCatalog catalog =
          new SparkCatalog(new MockSparkConnection(typeSystem, dataSets));
      Environment env = Environments.env(typeSystem, null, ImmutableMap.of());
      for (String name : dataSets.keySet()) {
        // Bound as a value rather than as a foreign value: a foreign value
        // stays an expression, for Calcite to push down, where a value folds
        // into the table it holds, which is what the translator reads.
        catalog.discoverField(typeSystem, name);
        final TypedValue database =
            catalog.fieldValueAs(name, TypedValue.class);
        env =
            env.bind(
                core.idPat(database.typeKey().toType(typeSystem), name, 0),
                database);
      }
      final Ast.ValDecl valDecl = Compiles.toValDecl(statement);
      final Consumer<CompileException> ignoreWarnings = w -> {};
      final TypeResolver.Resolved resolved =
          TypeResolver.deduceType(env, valDecl, typeSystem, ignoreWarnings);
      final Resolver resolver = Resolver.of(resolved.typeMap, env, null);
      // Inline once, as Compiles does: until then a built-in is an Id,
      // not the function literal the translator matches on.
      final Core.ValDecl valDecl2 =
          (Core.ValDecl)
              resolver
                  .toCore((Ast.ValDecl) resolved.node)
                  .accept(Inliner.of(typeSystem, env, null));
      final Core.Rel[] rels = {null};
      valDecl2.accept(
          new Visitor() {
            @Override
            protected void visitRel(Core.Rel rel) {
              if (rels[0] == null) {
                rels[0] = rel;
              }
            }
          });
      rel = requireNonNull(rels[0], "no query in " + ml);
    }

    String plan() {
      return SparkTranslator.translate(typeSystem, rel).text();
    }
  }

  /** Returns the plan that {@code seed-plans.txt} records for a query. */
  private static String seedPlan(String name) throws IOException {
    final URL url =
        requireNonNull(
            SparkTranslatorTest.class.getResource("/spark/seed-plans.txt"),
            "seed-plans.txt");
    final String text = Resources.toString(url, StandardCharsets.UTF_8);
    final String header = "=== " + name + " ===\n";
    final int start = text.indexOf(header);
    if (start < 0) {
      throw new IllegalArgumentException("no section " + header.trim());
    }
    final int from = start + header.length();
    // A plan runs to the next header, or, for the last plan, to the comment
    // that ends the file.
    final int next =
        Math.min(
            before(text, from, "\n=== "), // a header starts a line
            before(text, from, "\n# ")); // and so does the closing comment
    final String section = text.substring(from, next);
    // Sections are separated by blank lines that are not part of a plan.
    return section.replaceAll("\\s+$", "");
  }

  /**
   * Returns where {@code marker} next occurs at or after {@code from}, or the
   * end of the text if it does not occur again.
   */
  private static int before(String text, int from, String marker) {
    final int i = text.indexOf(marker, from);
    return i < 0 ? text.length() : i;
  }

  /**
   * Query 1: filter and project over an inline relation. Pins the local
   * relation, a conjunctive filter, and a projection that renames and computes.
   */
  @Test
  void testQuery1() throws IOException {
    final Fixture f =
        new Fixture(
            "from r in [{i = 1, s = \"one\", b = true, x = 1.5},\n"
                + "           {i = 2, s = \"two\", b = false, x = 2.5},\n"
                + "           {i = 3, s = \"three\", b = true, x = 3.5}]\n"
                + "  where r.b andalso r.i > 1\n"
                + "  yield {r.s, y = r.x * 2.0}");
    assertThat(f.plan(), is(seedPlan("q1")));
  }

  /**
   * Query 2: equijoin. Pins the join relation, the qualification of a column
   * name that both inputs have, and projection over the concatenated row.
   */
  @Test
  void testQuery2() throws IOException {
    final Fixture f =
        new Fixture(
            "from e in scott.emps\n"
                + "  join d in scott.depts on e.deptno = d.deptno\n"
                + "  yield {e.ename, d.dname}");
    assertThat(f.plan(), is(seedPlan("q2")));
  }

  /**
   * Query 3: group by with aggregates. Pins the aggregate relation, the
   * grouping key, and count, sum and max.
   */
  @Test
  void testQuery3() throws IOException {
    final Fixture f =
        new Fixture(
            "from e in scott.emps\n"
                + "  group e.deptno\n"
                + "    compute {c = count over (), total = sum over e.sal,\n"
                + "             top = max over e.sal}");
    assertThat(f.plan(), is(seedPlan("q3")));
  }

  /**
   * Query 4: sort and limit. Pins the sort relation with mixed directions, a
   * limit, and that the result is an ordered list.
   */
  @Test
  void testQuery4() throws IOException {
    final Fixture f =
        new Fixture(
            "from e in scott.emps\n"
                + "  yield {e.ename, e.sal}\n"
                + "  order (DESC sal, ename)\n"
                + "  take 3");
    assertThat(f.plan(), is(seedPlan("q4")));
  }

  /**
   * Query 5: correlated exists subquery. Pins the subquery expression whose
   * inner plan refers to the outer row.
   */
  @Test
  void testQuery5() throws IOException {
    final Fixture f =
        new Fixture(
            "from d in scott.depts\n"
                + "  where (exists e in scott.emps\n"
                + "    where e.deptno = d.deptno andalso e.job = \"CLERK\")\n"
                + "  yield d.dname");
    assertThat(f.plan(), is(seedPlan("q5")));
  }

  /** Query 6: order, skip, take, then project. */
  @Test
  void testQuery6() throws IOException {
    final Fixture f =
        new Fixture(
            "from e in scott.emps\n"
                + "  order e.empno\n"
                + "  skip 2\n"
                + "  take 3\n"
                + "  yield {e.empno, e.ename}");
    assertThat(f.plan(), is(seedPlan("q6")));
  }

  /** Query 7: distinct, a group with no aggregates. */
  @Test
  void testQuery7() throws IOException {
    final Fixture f = new Fixture("from e in scott.emps group e.job");
    assertThat(f.plan(), is(seedPlan("q7")));
  }

  /** Query 8: left join. The absent side is an option of the record. */
  @Test
  void testQuery8() throws IOException {
    final Fixture f =
        new Fixture(
            "from d in scott.depts\n"
                + "  left join e in scott.emps on d.deptno = e.deptno\n"
                + "  where d.deptno >= 30\n"
                + "  yield {d.dname, e}");
    assertThat(f.plan(), is(seedPlan("q8")));
  }

  /** Query 9: union, of bags, so every row is kept. */
  @Test
  void testQuery9() throws IOException {
    final Fixture f =
        new Fixture(
            "from e in scott.emps where e.deptno = 10 yield {n = e.empno}\n"
                + "  union (from d in scott.depts yield {n = d.deptno})");
    assertThat(f.plan(), is(seedPlan("q9")));
  }

  /** Query 10: arithmetic, including the templates for div and mod. */
  @Test
  void testQuery10() throws IOException {
    final Fixture f =
        new Fixture(
            "from e in scott.emps\n"
                + "  where e.deptno = 10\n"
                + "  yield {e.empno, w = ~e.empno, x = e.sal * decimal \"2\",\n"
                + "         y = e.empno div 7, z = e.empno mod 7}");
    assertThat(f.plan(), is(seedPlan("q10")));
  }

  /**
   * Query 11: string functions. {@code String.isPrefix} is curried, so the call
   * is two applications, and both of its arguments have to reach the template.
   */
  @Test
  void testQuery11() throws IOException {
    final Fixture f =
        new Fixture(
            "from e in scott.emps\n"
                + "  where String.isPrefix \"S\" e.ename\n"
                + "  yield {e.ename, n = size e.ename,\n"
                + "         s = String.substring (e.ename, 0, 2),\n"
                + "         t = e.ename ^ \"!\"}");
    assertThat(f.plan(), is(seedPlan("q11")));
  }

  /**
   * Query 12: {@code elem}. Spark Connect 4.0 has no IN subquery, so the
   * collection becomes an EXISTS whose condition holds the equality.
   */
  @Test
  void testQuery12() throws IOException {
    final Fixture f =
        new Fixture(
            "from e in scott.emps\n"
                + "  where e.deptno elem (from d in scott.depts\n"
                + "      where d.loc = \"CHICAGO\"\n"
                + "      yield d.deptno)\n"
                + "  yield e.ename");
    assertThat(f.plan(), is(seedPlan("q12")));
  }

  /** Query 13: a correlated scalar subquery. */
  @Test
  void testQuery13() throws IOException {
    final Fixture f =
        new Fixture(
            "from d in scott.depts\n"
                + "  yield {d.dname,\n"
                + "         n = (from e in scott.emps\n"
                + "              where e.deptno = d.deptno\n"
                + "              compute count over ())}");
    assertThat(f.plan(), is(seedPlan("q13")));
  }

  /**
   * Query 14: a result that is not a collection. Spark has only relations, so a
   * value is a relation of one row and one column.
   */
  @Test
  void testQuery14() throws IOException {
    final Fixture f = new Fixture("from e in scott.emps compute count over ()");
    assertThat(f.plan(), is(seedPlan("q14")));
  }

  /** Query 15: except, of bags, so every row counts. */
  @Test
  void testQuery15a() throws IOException {
    final Fixture f =
        new Fixture(
            "from e in scott.emps yield {n = e.deptno}\n"
                + "  except (from d in scott.depts\n"
                + "      where d.deptno > 20 yield {n = d.deptno})");
    assertThat(f.plan(), is(seedPlan("q15a")));
  }

  /** Query 15 again, intersecting rather than subtracting. */
  @Test
  void testQuery15b() throws IOException {
    final Fixture f =
        new Fixture(
            "from e in scott.emps yield {n = e.deptno}\n"
                + "  intersect (from d in scott.depts\n"
                + "      where d.deptno > 20 yield {n = d.deptno})");
    assertThat(f.plan(), is(seedPlan("q15b")));
  }

  /** Query 16: two grouping keys and two aggregates, then a sort. */
  @Test
  void testQuery16() throws IOException {
    final Fixture f =
        new Fixture(
            "from e in scott.emps\n"
                + "  group {e.deptno, e.job}\n"
                + "    compute {n = count over (), s = sum over e.sal}\n"
                + "  order (deptno, job)");
    assertThat(f.plan(), is(seedPlan("q16")));
  }
}

// End SparkTranslatorTest.java
