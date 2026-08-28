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

import static java.lang.String.join;
import static java.util.Objects.requireNonNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import net.hydromatic.morel.util.MorelHighlighter;
import org.junit.jupiter.api.Test;

/** Tests for {@link Darn}, the Morel notebook kernel. */
public class DarnTest {

  /** Kernel supplier for tests that execute Morel code. */
  private static final Supplier<Kernel> KERNEL =
      () -> Main.kernel(ImmutableMap.of());

  /** Keywords that Morel does not have, for testing keyword amendment. */
  private static final List<String> PIVOT_KEYWORDS =
      ImmutableList.of("pivot", "unpivot");

  // -----------------------------------------------------------------------
  // ProcessResult statistics

  @Test
  void testProcessResultStatsMixed() {
    // run=1, skip=1, silent=1: verify cellCount, executedCount, divCount.
    List<String> input =
        Arrays.asList(
            "<!-- morel",
            "1 + 2;",
            "> val it = 3 : int",
            "-->",
            "<!-- morel skip",
            "fun f x = x;",
            "-->",
            "<!-- morel silent",
            "val z = 0;",
            "> val z = 0 : int",
            "-->");
    Darn.ProcessResult result = Darn.processLines(input, KERNEL);
    assertThat(result.cellCount, is(3)); // run + skip + silent
    assertThat(result.executedCount, is(2)); // run + silent (not skip)
    assertThat(result.mismatchCount, is(0)); // all output already correct
    assertThat(result.divCount, is(2)); // run + skip (not silent)
  }

  @Test
  void testProcessResultDivChangedFreshFile() {
    // No existing div in the input: the generated div counts as changed.
    List<String> input =
        Arrays.asList("<!-- morel", "1 + 2;", "> val it = 3 : int", "-->");
    Darn.ProcessResult result = Darn.processLines(input, KERNEL);
    assertThat(result.divCount, is(1));
    assertThat(result.divChangedCount, is(1));
  }

  @Test
  void testProcessResultDivChangedIdempotent() {
    // After the first run the div is present; a second run sees no change.
    List<String> input =
        Arrays.asList("<!-- morel", "1 + 2;", "> val it = 3 : int", "-->");
    List<String> afterFirstRun = Darn.processLines(input, KERNEL).lines;
    Darn.ProcessResult secondRun = Darn.processLines(afterFirstRun, KERNEL);
    assertThat(secondRun.divChangedCount, is(0));
    assertThat(secondRun.mismatchCount, is(0));
  }

  // -----------------------------------------------------------------------
  // parseAttrs

  @Test
  void testParseAttrsDefault() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel");
    assertThat(attrs.command, is(Darn.Command.RUN));
    assertThat(attrs.fail, is(false));
    assertThat(attrs.env, is("default"));
  }

  @Test
  void testParseAttrsRun() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel run");
    assertThat(attrs.command, is(Darn.Command.RUN));
  }

  @Test
  void testParseAttrsSilent() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel silent");
    assertThat(attrs.command, is(Darn.Command.SILENT));
  }

  @Test
  void testParseAttrsSkip() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel skip");
    assertThat(attrs.command, is(Darn.Command.SKIP));
  }

  @Test
  void testParseAttrsFail() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel fail");
    assertThat(attrs.fail, is(true));
  }

  @Test
  void testParseAttrsNoFail() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel fail no-fail");
    assertThat(attrs.fail, is(false));
  }

  @Test
  void testParseAttrsNoOutput() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel no-output");
    assertThat(attrs.noOutput, is(true));
    assertThat(attrs.command, is(Darn.Command.RUN));
  }

  @Test
  void testParseAttrsOutput() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel no-output output");
    assertThat(attrs.noOutput, is(false));
  }

  @Test
  void testParseAttrsEnv() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel env=mykernel");
    assertThat(attrs.env, is("mykernel"));
  }

  @Test
  void testParseAttrsMultiple() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel silent env=foo");
    assertThat(attrs.command, is(Darn.Command.SILENT));
    assertThat(attrs.env, is("foo"));
  }

  // -----------------------------------------------------------------------
  // parseSegments

  @Test
  void testParseSegmentsSingleInputOutput() {
    List<String> lines = ImmutableList.of("1 + 2;", "> val it = 3 : int");
    List<Darn.Segment> segments = Darn.parseSegments(lines);
    assertThat(segments, hasSize(1));
    assertThat(segments.get(0).input, is(ImmutableList.of("1 + 2;")));
    assertThat(
        segments.get(0).output, is(ImmutableList.of("val it = 3 : int")));
  }

  @Test
  void testParseSegmentsInputOnly() {
    List<String> lines = ImmutableList.of("val x = 5;");
    List<Darn.Segment> segments = Darn.parseSegments(lines);
    assertThat(segments, hasSize(1));
    assertThat(segments.get(0).input, is(ImmutableList.of("val x = 5;")));
    assertThat(segments.get(0).output, is(ImmutableList.of()));
  }

  @Test
  void testParseSegmentsMultiLineInput() {
    List<String> lines =
        ImmutableList.of(
            "fun len [] = 0",
            "  | len (_ :: tl) = 1 + len tl;",
            "> val len = fn : 'a list -> int");
    List<Darn.Segment> segments = Darn.parseSegments(lines);
    assertThat(segments, hasSize(1));
    assertThat(segments.get(0).input, hasSize(2));
    assertThat(segments.get(0).output, hasSize(1));
  }

  @Test
  void testParseSegmentsMultiLineOutput() {
    List<String> lines =
        ImmutableList.of(
            "val x = 1;", "> val x = 1 : int", "> val y = 2 : int");
    List<Darn.Segment> segments = Darn.parseSegments(lines);
    assertThat(segments, hasSize(1));
    assertThat(segments.get(0).output, hasSize(2));
  }

  @Test
  void testParseSegmentsEmptyOutputLine() {
    // A bare ">" line becomes an empty output line.
    List<String> lines = ImmutableList.of("1;", ">");
    List<Darn.Segment> segments = Darn.parseSegments(lines);
    assertThat(segments, hasSize(1));
    assertThat(segments.get(0).output, is(ImmutableList.of("")));
  }

  // -----------------------------------------------------------------------
  // generateHtmlLines

  @Test
  void testGenerateHtmlLinesInputOnly() {
    Darn.Segment seg =
        new Darn.Segment(ImmutableList.of("1 + 2;"), ImmutableList.of());
    List<String> html = Darn.generateHtmlLines(ImmutableList.of(seg));
    assertThat(html.get(0), is("<div class=\"code-block\">"));
    // 1 -> mi, + -> o, 2 -> mi, ; -> p
    assertThat(
        html.get(1),
        is(
            "<div class=\"code-input\">"
                + "<span class=\"mi\">1</span> "
                + "<span class=\"o\">+</span> "
                + "<span class=\"mi\">2</span>"
                + "<span class=\"p\">;</span>"
                + "</div>"));
    assertThat(html.get(html.size() - 1), is("</div>"));
  }

  @Test
  void testGenerateHtmlLinesInputAndOutput() {
    Darn.Segment seg =
        new Darn.Segment(
            ImmutableList.of("1 + 2;"), ImmutableList.of("val it = 3 : int"));
    List<String> html = Darn.generateHtmlLines(ImmutableList.of(seg));
    assertThat(html.get(0), is("<div class=\"code-block\">"));
    // Output line as plain HTML-escaped text
    assertThat(
        html.get(2),
        is("<div class=\"code-output\">" + "val it = 3 : int" + "</div>"));
    assertThat(html.get(3), is("</div>"));
  }

  @Test
  void testGenerateHtmlLinesKeywords() {
    Darn.Segment seg =
        new Darn.Segment(ImmutableList.of("fun f x = x;"), ImmutableList.of());
    List<String> html = Darn.generateHtmlLines(ImmutableList.of(seg));
    // "fun" -> kr, "f" -> nf, "x" -> n, "=" -> p, ";" -> p
    final String expected =
        "<div class=\"code-input\">"
            + "<span class=\"kr\">fun</span> <span class=\"nf\">f</span> "
            + "<span class=\"n\">x</span> <span class=\"p\">=</span> "
            + "<span class=\"n\">x</span><span class=\"p\">;</span>"
            + "</div>";
    assertThat(html.get(1), is(expected));
  }

  @Test
  void testHighlightOutputHtmlEscapes() {
    // Output is HTML-escaped but otherwise plain (no spans).
    String highlighted =
        MorelHighlighter.DEFAULT.highlightOutput("'a list -> int");
    assertThat(highlighted, is("'a list -&gt; int"));
  }

  @Test
  void testHighlightOutputMultiLine() {
    String highlighted =
        MorelHighlighter.DEFAULT.highlightOutput(
            "line1\n" //
                + "line2");
    assertThat(
        highlighted,
        is(
            "line1\n" //
                + "line2"));
  }

  @Test
  void testGenerateHtmlLinesFailUsesErrorClass() {
    // A fail cell uses code-error class, not code-output.
    Darn.Segment seg =
        new Darn.Segment(
            ImmutableList.of("1 + \"hello\";"),
            ImmutableList.of("0.0-0.0 Error: Type mismatch"));
    List<String> html =
        Darn.generateHtmlLines(ImmutableList.of(seg), false, true);
    assertThat(
        html.stream().anyMatch(l -> l.contains("\"code-error\"")), is(true));
    assertThat(
        html.stream().anyMatch(l -> l.contains("\"code-output\"")), is(false));
  }

  @Test
  void testGenerateHtmlLinesNoOutput() {
    // no-output: div shows input block only; output pre is suppressed.
    Darn.Segment seg =
        new Darn.Segment(
            ImmutableList.of("val x = 5;"),
            ImmutableList.of("val x = 5 : int"));
    List<String> html = Darn.generateHtmlLines(ImmutableList.of(seg), true);
    assertThat(html.get(0), is("<div class=\"code-block\">"));
    assertThat(html.get(html.size() - 1), is("</div>"));
    // Input div block is present.
    assertThat(html.stream().anyMatch(l -> l.contains("code-input")), is(true));
    // Output div block is absent.
    assertThat(
        html.stream().anyMatch(l -> l.contains("code-output")), is(false));
  }

  @Test
  void testProcessLinesNoOutputCellNoOutputDiv() {
    // A no-output cell is executed, div is emitted, but output pre is absent.
    List<String> input =
        Arrays.asList(
            "<!-- morel no-output", "val x = 5;", "> val x = 5 : int", "-->");
    Darn.ProcessResult result = Darn.processLines(input, KERNEL);
    assertThat(result.mismatchCount, is(0));
    assertThat(
        result.lines.stream()
            .anyMatch(l -> l.equals("<div class=\"code-block\">")),
        is(true));
    assertThat(
        result.lines.stream().anyMatch(l -> l.contains("code-input")),
        is(true));
    assertThat(
        result.lines.stream().anyMatch(l -> l.contains("code-output")),
        is(false));
  }

  // -----------------------------------------------------------------------
  // Rouge output

  @Test
  void testHighlightRouge0() {
    // Exercises keyword→kr, val-binding→nv, fun-binding→nf, number→mi,
    // plain identifier→n, and punctuation→p in the Rouge output format.
    String code =
        "let\n"
            + "  val edge_facts = [(1, 2), (2, 3)]\n"
            + "  fun edge (x, y) = (x, y) elem edge_facts\n"
            + "  fun path (x, y) =\n"
            + "    edge (x, y) orelse\n"
            + "    (exists v0 where path (x, v0) andalso edge (v0, y))\n"
            + "in\n"
            + "  {path = from x, y where path (x, y)}\n"
            + "end\n";
    final String expected =
        "<div class=\"language-sml highlighter-rouge\">"
            + "<div class=\"highlight\">"
            + "<pre class=\"highlight\">"
            + "<code>"
            + "<span class=\"kr\">let</span>\n"
            + "  <span class=\"kr\">val</span>"
            + " <span class=\"nv\">edge_facts</span>"
            + " <span class=\"p\">=</span>"
            + " <span class=\"p\">[(</span>"
            + "<span class=\"mi\">1</span>"
            + "<span class=\"p\">,</span>"
            + " <span class=\"mi\">2</span>"
            + "<span class=\"p\">),</span>"
            + " <span class=\"p\">(</span>"
            + "<span class=\"mi\">2</span>"
            + "<span class=\"p\">,</span>"
            + " <span class=\"mi\">3</span>"
            + "<span class=\"p\">)]</span>\n"
            + "  <span class=\"kr\">fun</span>"
            + " <span class=\"nf\">edge</span>"
            + " <span class=\"p\">(</span>"
            + "<span class=\"n\">x</span>"
            + "<span class=\"p\">,</span>"
            + " <span class=\"n\">y</span>"
            + "<span class=\"p\">)</span>"
            + " <span class=\"p\">=</span>"
            + " <span class=\"p\">(</span>"
            + "<span class=\"n\">x</span>"
            + "<span class=\"p\">,</span>"
            + " <span class=\"n\">y</span>"
            + "<span class=\"p\">)</span>"
            + " <span class=\"kr\">elem</span>"
            + " <span class=\"n\">edge_facts</span>\n"
            + "  <span class=\"kr\">fun</span>"
            + " <span class=\"nf\">path</span>"
            + " <span class=\"p\">(</span>"
            + "<span class=\"n\">x</span>"
            + "<span class=\"p\">,</span>"
            + " <span class=\"n\">y</span>"
            + "<span class=\"p\">)</span>"
            + " <span class=\"p\">=</span>\n"
            + "    <span class=\"n\">edge</span>"
            + " <span class=\"p\">(</span>"
            + "<span class=\"n\">x</span>"
            + "<span class=\"p\">,</span>"
            + " <span class=\"n\">y</span>"
            + "<span class=\"p\">)</span>"
            + " <span class=\"kr\">orelse</span>\n"
            + "    <span class=\"p\">(</span>"
            + "<span class=\"kr\">exists</span>"
            + " <span class=\"n\">v0</span>"
            + " <span class=\"kr\">where</span>"
            + " <span class=\"n\">path</span>"
            + " <span class=\"p\">(</span>"
            + "<span class=\"n\">x</span>"
            + "<span class=\"p\">,</span>"
            + " <span class=\"n\">v0</span>"
            + "<span class=\"p\">)</span>"
            + " <span class=\"kr\">andalso</span>"
            + " <span class=\"n\">edge</span>"
            + " <span class=\"p\">(</span>"
            + "<span class=\"n\">v0</span>"
            + "<span class=\"p\">,</span>"
            + " <span class=\"n\">y</span>"
            + "<span class=\"p\">))</span>\n"
            + "<span class=\"kr\">in</span>\n"
            + "  <span class=\"p\">{</span>"
            + "<span class=\"n\">path</span>"
            + " <span class=\"p\">=</span>"
            + " <span class=\"kr\">from</span>"
            + " <span class=\"nv\">x</span>"
            + "<span class=\"p\">,</span>"
            + " <span class=\"nv\">y</span>"
            + " <span class=\"kr\">where</span>"
            + " <span class=\"n\">path</span>"
            + " <span class=\"p\">(</span>"
            + "<span class=\"n\">x</span>"
            + "<span class=\"p\">,</span>"
            + " <span class=\"n\">y</span>"
            + "<span class=\"p\">)}</span>\n"
            + "<span class=\"kr\">end</span>\n"
            + "</code></pre></div></div>";
    assertThat(MorelHighlighter.DEFAULT.highlightRouge(code), is(expected));
  }

  /**
   * Exercises keyword&rarr;kr, val-binding&rarr;nv, fun-binding&rarr;nf,
   * number&rarr;mi, plain identifier&rarr;n and punctuation&rarr;p in the
   * concise Rouge format.
   *
   * <p>This is the only test of that format here; the rest live in {@code
   * script/highlight.smli}, which reaches it via {@code Test.highlight} and
   * which the Morel implementations in other languages run too.
   */
  @Test
  void testHighlightRouge() {
    String code =
        "let\n" //
            + "  val edge_facts = [(1, 2), (2, 3)]\n"
            + "  fun edge (x, y) = (x, y) elem edge_facts\n"
            + "  fun path (x, y) =\n"
            + "    edge (x, y) orelse\n"
            + "    (exists v0 where path (x, v0) andalso edge (v0, y))\n"
            + "in\n"
            + "  {path = from x, y where path (x, y)}\n"
            + "end\n";
    final String expected =
        "kr{let}\n"
            + "  kr{val} nv{edge_facts} p{=} p{[(}mi{1}p{,} mi{2}p{),} p{(}"
            + "mi{2}p{,} mi{3}p{)]}\n"
            + "  kr{fun} nf{edge} p{(}n{x}p{,} n{y}p{)} p{=} p{(}n{x}p{,}"
            + " n{y}p{)} kr{elem} n{edge_facts}\n"
            + "  kr{fun} nf{path} p{(}n{x}p{,} n{y}p{)} p{=}\n"
            + "    n{edge} p{(}n{x}p{,} n{y}p{)} kr{orelse}\n"
            + "    p{(}kr{exists} n{v0} kr{where} n{path} p{(}n{x}p{,} n{v0}"
            + "p{)} kr{andalso} n{edge} p{(}n{v0}p{,} n{y}p{))}\n"
            + "kr{in}\n"
            + "  p{{}n{path} p{=} kr{from} nv{x}p{,} nv{y} kr{where} n{path} p{(}"
            + "n{x}p{,} n{y}p{)}}\n"
            + "kr{end}\n";
    assertThat(MorelHighlighter.DEFAULT.highlightRouge2(code), is(expected));
  }

  /**
   * A highlighter can be given extra keywords, leaving the default highlighter
   * alone. {@link Darn} does this so that the blog post about DML highlights
   * {@code delete}, {@code insert} and {@code update}, which are not keywords
   * of the language as it stands.
   */
  @Test
  void testHighlightRougeExtraKeywords() {
    final MorelHighlighter pivotal =
        MorelHighlighter.DEFAULT.amendKeywords(
            keywords -> Iterables.concat(keywords, PIVOT_KEYWORDS));
    final String code = "from d in depts pivot deptno";

    // 'pivot' is an ordinary identifier to the default highlighter, and a
    // keyword to one that has been given it. 'depts' and 'deptno', which
    // neither highlighter was given, stay identifiers in both.
    assertThat(
        MorelHighlighter.DEFAULT.highlightRouge2(code),
        is("kr{from} nv{d} kr{in} n{depts} n{pivot} n{deptno}"));
    assertThat(
        pivotal.highlightRouge2(code),
        is("kr{from} nv{d} kr{in} n{depts} kr{pivot} n{deptno}"));

    // Amending again is cumulative, and still does not affect DEFAULT.
    final MorelHighlighter both =
        pivotal.amendKeywords(
            keywords ->
                Iterables.concat(keywords, MorelHighlighter.DML_KEYWORDS));
    final String code2 = "delete from d in depts pivot deptno";
    assertThat(
        both.highlightRouge2(code2),
        is("kr{delete} kr{from} nv{d} kr{in} n{depts} kr{pivot} n{deptno}"));
    assertThat(
        MorelHighlighter.DEFAULT.highlightRouge2(code2),
        is("n{delete} kr{from} nv{d} kr{in} n{depts} n{pivot} n{deptno}"));
  }

  @Test
  void testGenerateHtmlLinesHtmlEscape() {
    String highlighted =
        MorelHighlighter.DEFAULT.highlightInput("val x = a < b andalso c > d;");
    // < and > must be escaped; "andalso" must use kw span
    assertThat(highlighted.contains("&lt;"), is(true));
    assertThat(highlighted.contains("&gt;"), is(true));
    assertThat(
        highlighted.contains("<span class=\"kr\">andalso</span>"), is(true));
  }

  // -----------------------------------------------------------------------
  // updateSegments

  @Test
  void testUpdateSegmentsNoChange() {
    final List<String> lines = ImmutableList.of("val it = 3 : int");
    Darn.Segment seg = new Darn.Segment(ImmutableList.of("1 + 2;"), lines);
    List<Darn.Segment> segments = ImmutableList.of(seg);
    List<Darn.Segment> updated = Darn.updateSegments(segments, lines);
    assertThat(updated, is(segments));
  }

  @Test
  void testUpdateSegmentsChanged() {
    final List<String> inLines = ImmutableList.of("val it = 99 : int");
    Darn.Segment seg = new Darn.Segment(ImmutableList.of("1 + 2;"), inLines);
    List<Darn.Segment> segments = ImmutableList.of(seg);
    final List<String> lines = ImmutableList.of("val it = 3 : int");
    List<Darn.Segment> updated = Darn.updateSegments(segments, lines);
    assertThat(updated.get(0).output, is(lines));
  }

  // -----------------------------------------------------------------------
  // rebuildComment

  @Test
  void testRebuildComment() {
    Darn.Segment seg =
        new Darn.Segment(
            ImmutableList.of("1 + 2;"), ImmutableList.of("val it = 3 : int"));
    List<String> rebuilt =
        Darn.rebuildComment("<!-- morel", ImmutableList.of(seg));
    assertThat(rebuilt.get(0), is("<!-- morel"));
    assertThat(rebuilt.get(1), is("1 + 2;"));
    assertThat(rebuilt.get(2), is("> val it = 3 : int"));
    assertThat(rebuilt.get(3), is("-->"));
  }

  // -----------------------------------------------------------------------
  // splitStatements

  @Test
  void testSplitStatementsSingle() {
    List<List<String>> groups =
        Darn.splitStatements(ImmutableList.of("1 + 2;"));
    assertThat(groups, hasSize(1));
    assertThat(groups.get(0), is(ImmutableList.of("1 + 2;")));
  }

  @Test
  void testSplitStatementsMultiple() {
    List<List<String>> groups =
        Darn.splitStatements(ImmutableList.of("val x = 1;", "val y = 2;"));
    assertThat(groups, hasSize(2));
    assertThat(groups.get(0), is(ImmutableList.of("val x = 1;")));
    assertThat(groups.get(1), is(ImmutableList.of("val y = 2;")));
  }

  @Test
  void testSplitStatementsMultiLine() {
    // A multi-line val declaration is one group (';' only on last line).
    List<List<String>> groups =
        Darn.splitStatements(
            ImmutableList.of(
                "val emps =", "  [{id=1}];", "val depts =", "  [{d=10}];"));
    assertThat(groups, hasSize(2));
    assertThat(
        groups.get(0), is(ImmutableList.of("val emps =", "  [{id=1}];")));
    assertThat(
        groups.get(1), is(ImmutableList.of("val depts =", "  [{d=10}];")));
  }

  @Test
  void testSplitStatementsNoSemicolon() {
    // Input without ';' is treated as one group.
    List<List<String>> groups =
        Darn.splitStatements(ImmutableList.of("1 + 2", "  + 3"));
    assertThat(groups, hasSize(1));
    assertThat(groups.get(0), is(ImmutableList.of("1 + 2", "  + 3")));
  }

  // -----------------------------------------------------------------------
  // processLines — multi-segment and multi-line output

  @Test
  void testProcessLinesMultiSegmentUpdatedCorrectly() {
    // Two segments with wrong output: after update, each segment's output
    // stays next to its code, not all moved to the last segment.
    List<String> input =
        Arrays.asList(
            "<!-- morel",
            "val x = 1;",
            "> WRONG",
            "val y = 2;",
            "> WRONG",
            "-->");
    Darn.ProcessResult result = Darn.processLines(input, KERNEL);
    assertThat(result.mismatchCount, is(1));
    assertThat(result.lines.get(0), is("<!-- morel"));
    assertThat(result.lines.get(1), is("val x = 1;"));
    assertThat(result.lines.get(2), is("> val x = 1 : int"));
    assertThat(result.lines.get(3), is("val y = 2;"));
    assertThat(result.lines.get(4), is("> val y = 2 : int"));
    assertThat(result.lines.get(5), is("-->"));
  }

  @Test
  void testProcessLinesMultiLineOutputInterleaved() {
    // Two statements; the first (List.tabulate with records) produces
    // multi-line output. Each statement's output must remain adjacent to
    // its code, not all collected at the end.
    List<String> input =
        Arrays.asList(
            "<!-- morel",
            "val a = List.tabulate (8, fn i =>"
                + " {idx = i, sq = i * i, cube = i * i * i});",
            "> WRONG",
            "val b = 1 + 2;",
            "> WRONG",
            "-->");
    Darn.ProcessResult result = Darn.processLines(input, KERNEL);
    assertThat(result.mismatchCount, is(1));
    final List<String> ls = result.lines;
    // Find a's first output line (starts with '> val a') and b's code line.
    int aOutIdx = -1;
    for (int i = 0; i < ls.size(); i++) {
      if (ls.get(i).startsWith("> val a")) {
        aOutIdx = i;
        break;
      }
    }
    final int bCodeIdx = ls.indexOf("val b = 1 + 2;");
    assertThat("a has output", aOutIdx >= 0, is(true));
    assertThat("b code present", bCodeIdx >= 0, is(true));
    // a's output line must appear before b's code line in the comment.
    assertThat(aOutIdx < bCodeIdx, is(true));
    // Every line between the opening and '-->' that isn't a code line or
    // output line is a continuation — it must start with '>'.
    final int commentClose = ls.indexOf("-->");
    for (int i = 1; i < commentClose; i++) {
      String l = ls.get(i);
      if (!l.isEmpty() && !l.startsWith("val ") && !l.startsWith("> ")) {
        throw new AssertionError(
            "Output continuation line missing '>' prefix at index "
                + i
                + ": "
                + l);
      }
    }
  }

  // -----------------------------------------------------------------------
  // probeLines

  @Test
  void testProbeLinesSkipOkNoOutput() {
    // A skip cell that executes with no output → suggest no-output.
    List<String> input = Arrays.asList("<!-- morel skip", "val x = 5;", "-->");
    List<Darn.ProbeResult> results = Darn.probeLines(input, KERNEL);
    assertThat(results, hasSize(1));
    assertThat(results.get(0).isOk(), is(true));
    assertThat(results.get(0).output, is("val x = 5 : int"));
    assertThat(results.get(0).lineNumber, is(1));
  }

  @Test
  void testProbeLinesSkipError() {
    // A skip cell with an unbound name: interpreter prints an error message
    // (no Java exception), captured as output. isOk() returns false because
    // the output does not match the "val ..." / "type ..." success pattern.
    List<String> input =
        Arrays.asList("<!-- morel skip", "unboundName;", "-->");
    List<Darn.ProbeResult> results = Darn.probeLines(input, KERNEL);
    assertThat(results, hasSize(1));
    assertThat(results.get(0).isOk(), is(false));
    assertThat(results.get(0).error, is((String) null)); // no Java exception
    assertThat(
        requireNonNull(results.get(0).output).contains("unbound"), is(true));
  }

  @Test
  void testProbeLinesNonSkipBuildsEnv() {
    // A non-skip cell before a skip cell contributes to the environment, so
    // the skip cell can reference the definition.
    List<String> input =
        Arrays.asList(
            "<!-- morel",
            "val x = 42;",
            "> val x = 42 : int",
            "-->",
            "<!-- morel skip",
            "x + 1;",
            "-->");
    List<Darn.ProbeResult> results = Darn.probeLines(input, KERNEL);
    assertThat(results, hasSize(1));
    assertThat(results.get(0).isOk(), is(true));
    assertThat(results.get(0).output, is("val it = 43 : int"));
  }

  // -----------------------------------------------------------------------
  // processLines — idempotency test against the basic.md fixture

  @Test
  void testProcessLinesIdempotent() throws Exception {
    URL url = DarnTest.class.getResource("/darn/basic.md");
    assert url != null : "resource /darn/basic.md not found";
    File file = new File(url.toURI());
    List<String> original =
        Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    Darn.ProcessResult result = Darn.processLines(original, KERNEL);
    assertThat(
        "processLines should produce no changes on an already-processed file",
        result.lines,
        is(original));
    assertThat(result.mismatchCount, is(0));
  }

  // -----------------------------------------------------------------------
  // processLines — cell execution smoke test

  @Test
  void testProcessLinesSimpleExpression() {
    List<String> input =
        Arrays.asList(
            "<!-- morel",
            "val x = 1;",
            "> val x = 1 : int",
            "x + 2;",
            "> val it = 3 : int",
            "-->");
    Darn.ProcessResult result = Darn.processLines(input, KERNEL);
    assertThat(result.mismatchCount, is(0));
    // Output should contain the div block.
    final String expected =
        "<!-- morel\n"
            + "val x = 1;\n"
            + "> val x = 1 : int\n"
            + "x + 2;\n"
            + "> val it = 3 : int\n"
            + "-->\n"
            + "\n"
            + "<div class=\"code-block\">\n"
            + "<div class=\"code-input\"><span class=\"kr\">val</span> <span class=\"nv\">x</span> <span class=\"p\">=</span> <span class=\"mi\">1</span><span class=\"p\">;</span></div>\n"
            + "<div class=\"code-output\">val x = 1 : int</div>\n"
            + "<div class=\"code-input\"><span class=\"n\">x</span> <span class=\"o\">+</span> <span class=\"mi\">2</span><span class=\"p\">;</span></div>\n"
            + "<div class=\"code-output\">val it = 3 : int</div>\n"
            + "</div>\n";
    assertThat(join("\n", result.lines), is(expected));
  }

  @Test
  void testProcessLinesSkipCellNotExecuted() {
    // A skip cell is not executed but its div block is generated.
    List<String> input =
        Arrays.asList("<!-- morel skip", "fun f x = x;", "-->");
    Darn.ProcessResult result = Darn.processLines(input, KERNEL);
    assertThat(result.mismatchCount, is(0));
    assertThat(
        result.lines.stream()
            .anyMatch(l -> l.contains("<span class=\"kr\">fun</span>")),
        is(true));
  }

  @Test
  void testProcessLinesSilentCellNoDiv() {
    // A silent cell is executed but no div block is emitted.
    List<String> input =
        Arrays.asList(
            "<!-- morel silent",
            "val hidden = 99;",
            "> val hidden = 99 : int",
            "-->");
    Darn.ProcessResult result = Darn.processLines(input, KERNEL);
    assertThat(result.mismatchCount, is(0));
    assertThat(
        result.lines.stream()
            .anyMatch(l -> l.equals("<div class=\"code-block\">")),
        is(false));
  }

  @Test
  void testSilentCellBuildsEnvForSubsequentCell() {
    // A silent cell executes and its bindings are available to the next cell.
    List<String> input =
        Arrays.asList(
            "<!-- morel silent",
            "val x = 42;",
            "> val x = 42 : int",
            "-->",
            "<!-- morel",
            "x + 1;",
            "> val it = 43 : int",
            "-->");
    Darn.ProcessResult result = Darn.processLines(input, KERNEL);
    assertThat(result.mismatchCount, is(0));
    // Only one div — from the non-silent cell.
    assertThat(
        result.lines.stream()
            .filter(l -> l.equals("<div class=\"code-block\">"))
            .count(),
        is(1L));
  }

  @Test
  void testSysSetSucceeds() {
    // Sys.set must not throw UnsupportedOperationException; the kernel's
    // session must use a mutable prop map.
    List<String> input =
        Arrays.asList(
            "<!-- morel silent",
            "Sys.set (\"printLength\", ~1);",
            "> val it = () : unit",
            "-->");
    Darn.ProcessResult result = Darn.processLines(input, KERNEL);
    assertThat(result.mismatchCount, is(0));
    assertThat(result.executedCount, is(1));
  }
}

// End DarnTest.java
