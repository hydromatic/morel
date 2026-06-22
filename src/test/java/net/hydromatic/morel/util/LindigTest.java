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
package net.hydromatic.morel.util;

import static net.hydromatic.morel.Matchers.isLines;
import static net.hydromatic.morel.util.Lindig.EMPTY;
import static net.hydromatic.morel.util.Lindig.HARD_LINE;
import static net.hydromatic.morel.util.Lindig.LINE;
import static net.hydromatic.morel.util.Lindig.LINE_BREAK;
import static net.hydromatic.morel.util.Lindig.align;
import static net.hydromatic.morel.util.Lindig.beside;
import static net.hydromatic.morel.util.Lindig.brackets;
import static net.hydromatic.morel.util.Lindig.cat;
import static net.hydromatic.morel.util.Lindig.encloseSep;
import static net.hydromatic.morel.util.Lindig.fillSep;
import static net.hydromatic.morel.util.Lindig.group;
import static net.hydromatic.morel.util.Lindig.hang;
import static net.hydromatic.morel.util.Lindig.hcat;
import static net.hydromatic.morel.util.Lindig.hsep;
import static net.hydromatic.morel.util.Lindig.indent;
import static net.hydromatic.morel.util.Lindig.nest;
import static net.hydromatic.morel.util.Lindig.parens;
import static net.hydromatic.morel.util.Lindig.punctuate;
import static net.hydromatic.morel.util.Lindig.render;
import static net.hydromatic.morel.util.Lindig.sep;
import static net.hydromatic.morel.util.Lindig.text;
import static net.hydromatic.morel.util.Lindig.vcat;
import static net.hydromatic.morel.util.Lindig.vsep;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import net.hydromatic.morel.util.Lindig.Doc;
import org.junit.jupiter.api.Test;

/** Tests for {@link Lindig}. */
class LindigTest {

  /** Converts a string of words into a list of text documents. */
  private static List<Doc> words(String s) {
    return Arrays.stream(s.split(" "))
        .map(Lindig::text)
        .collect(Collectors.toList());
  }

  // -- Primitives -----------------------------------------------------------

  @Test
  void testEmpty() {
    assertThat(render(80, EMPTY), is(""));
  }

  @Test
  void testText() {
    assertThat(render(80, text("hello")), is("hello"));
  }

  @Test
  void testBeside() {
    assertThat(render(80, beside(text("a"), text("b"))), is("ab"));
  }

  @Test
  void testLine() {
    final Doc doc = beside(text("a"), beside(LINE, text("b")));
    // Without group, LINE always breaks.
    assertThat(render(80, doc), isLines("a", "b"));
  }

  @Test
  void testHardLine() {
    final Doc doc = beside(text("a"), beside(HARD_LINE, text("b")));
    // HARD_LINE always breaks, even in a group.
    assertThat(render(80, group(doc)), isLines("a", "b"));
  }

  // -- group and flatten ----------------------------------------------------

  @Test
  void testGroupFitsOnOneLine() {
    final Doc doc = group(beside(text("a"), beside(LINE, text("b"))));
    assertThat(render(80, doc), is("a b"));
  }

  @Test
  void testGroupDoesNotFit() {
    final Doc doc = group(beside(text("a"), beside(LINE, text("b"))));
    assertThat(render(2, doc), isLines("a", "b"));
  }

  // -- nest -----------------------------------------------------------------

  @Test
  void testNest() {
    final Doc body = vsep(ImmutableList.of(text("x"), text("y"), text("z")));
    final Doc doc =
        beside(
            text("{"),
            beside(nest(2, beside(LINE, body)), beside(LINE, text("}"))));
    assertThat(render(80, doc), isLines("{", "  x", "  y", "  z", "}"));
  }

  @Test
  void testNestWithGroup() {
    final Doc body = sep(ImmutableList.of(text("x"), text("y"), text("z")));
    final Doc doc =
        group(
            beside(
                text("{"),
                beside(
                    nest(2, beside(LINE_BREAK, body)),
                    beside(LINE_BREAK, text("}")))));
    // Fits on one line
    assertThat(render(80, doc), is("{x y z}"));
    // Does not fit — breaks and indents
    assertThat(render(6, doc), isLines("{", "  x", "  y", "  z", "}"));
  }

  // -- align ----------------------------------------------------------------

  @Test
  void testAlign() {
    final Doc doc =
        beside(
            text("prefix: "),
            align(
                vsep(
                    ImmutableList.of(
                        text("first"), text("second"), text("third")))));
    assertThat(
        render(80, doc),
        isLines("prefix: first", "        second", "        third"));
  }

  // -- hang -----------------------------------------------------------------

  @Test
  void testHang() {
    final Doc doc =
        hang(4, fillSep(words("the hang combinator indents these words")));
    assertThat(
        render(20, doc),
        isLines("the hang combinator", "    indents these", "    words"));
  }

  // -- indent ---------------------------------------------------------------

  @Test
  void testIndent() {
    final Doc doc =
        indent(4, fillSep(words("the indent combinator indents these words")));
    assertThat(
        render(24, doc),
        isLines(
            "    the indent",
            "        combinator",
            "        indents these",
            "        words"));
  }

  @Test
  void testNegativeIndentRejected() {
    final IllegalArgumentException nestEx =
        assertThrows(IllegalArgumentException.class, () -> nest(-1, text("x")));
    assertThat(
        nestEx.getMessage(), containsString("indent must be nonnegative"));

    final IllegalArgumentException hangEx =
        assertThrows(IllegalArgumentException.class, () -> hang(-1, text("x")));
    assertThat(
        hangEx.getMessage(), containsString("indent must be nonnegative"));

    final IllegalArgumentException indentEx =
        assertThrows(
            IllegalArgumentException.class, () -> indent(-1, text("x")));
    assertThat(
        indentEx.getMessage(), containsString("indent must be nonnegative"));
  }

  // -- hsep, vsep, sep ------------------------------------------------------

  @Test
  void testHsep() {
    assertThat(render(80, hsep(words("a b c"))), is("a b c"));
  }

  @Test
  void testVsep() {
    assertThat(render(80, vsep(words("a b c"))), isLines("a", "b", "c"));
  }

  @Test
  void testSepFits() {
    assertThat(render(80, sep(words("a b c"))), is("a b c"));
  }

  @Test
  void testSepBreaks() {
    assertThat(render(3, sep(words("a b c"))), isLines("a", "b", "c"));
  }

  // -- hcat, vcat, cat ------------------------------------------------------

  @Test
  void testHcat() {
    assertThat(render(80, hcat(words("a b c"))), is("abc"));
  }

  @Test
  void testVcat() {
    assertThat(render(80, vcat(words("a b c"))), isLines("a", "b", "c"));
  }

  @Test
  void testCatFits() {
    assertThat(render(80, cat(words("a b c"))), is("abc"));
  }

  @Test
  void testCatBreaks() {
    assertThat(render(2, cat(words("a b c"))), isLines("a", "b", "c"));
  }

  // -- fillSep --------------------------------------------------------------

  @Test
  void testFillSep() {
    final Doc doc =
        fillSep(
            words(
                "the fill combinator lays out words one"
                    + " at a time fitting as many on each line as possible"));
    assertThat(
        render(40, doc),
        isLines(
            "the fill combinator lays out words one",
            "at a time fitting as many on each line",
            "as possible"));
  }

  // -- fill -----------------------------------------------------------------

  /** {@code fill} packs atoms onto as many lines as fit. */
  @Test
  void testFillAtoms() {
    final List<Doc> docs =
        ImmutableList.of(
            text("1,"), text("2,"), text("3,"), text("4,"), text("5"));
    final Doc doc = align(Lindig.fill(EMPTY, docs));
    assertThat(render(80, doc), is("1,2,3,4,5"));
    assertThat(render(4, doc), isLines("1,2,", "3,4,", "5"));
  }

  /**
   * {@code fill} treats each element as an indivisible unit: a list of records
   * wraps between records (not within one), which {@code fillSep} cannot do.
   */
  @Test
  void testFillTreatsElementsAsUnits() {
    // Each "record" is itself a group that could break internally.
    final Doc r1 = group(brackets(fillSep(words("a b"))));
    final Doc r2 = group(brackets(fillSep(words("c d"))));
    final Doc r3 = group(brackets(fillSep(words("e f"))));
    final Doc doc =
        align(
            Lindig.fill(
                EMPTY,
                ImmutableList.of(
                    beside(r1, text(",")),
                    beside(r2, text(",")),
                    beside(r3, text("")))));
    // Wide: all on one line.
    assertThat(render(80, doc), is("[a b],[c d],[e f]"));
    // Narrow: wraps between records, each record kept intact.
    assertThat(render(8, doc), isLines("[a b],", "[c d],", "[e f]"));
  }

  // -- punctuate ------------------------------------------------------------

  @Test
  void testPunctuate() {
    final List<Doc> docs = punctuate(text(","), words("a b c"));
    assertThat(render(80, hsep(docs)), is("a, b, c"));
  }

  @Test
  void testPunctuateEmpty() {
    final List<Doc> docs = punctuate(text(","), ImmutableList.of());
    assertThat(docs.size(), is(0));
  }

  @Test
  void testPunctuateSingleton() {
    final List<Doc> docs = punctuate(text(","), ImmutableList.of(text("x")));
    assertThat(render(80, hsep(docs)), is("x"));
  }

  // -- encloseSep -----------------------------------------------------------

  @Test
  void testEncloseSepFits() {
    // Separator should be just the punctuation; space comes from LINE when flat
    final Doc doc = encloseSep(text("["), text("]"), text(","), words("a b c"));
    assertThat(render(80, doc), is("[a, b, c]"));
  }

  @Test
  void testEncloseSepBreaks() {
    // When breaking, no trailing space after comma (LINE becomes newline)
    final Doc doc =
        encloseSep(
            text("["),
            text("]"),
            text(","),
            ImmutableList.of(text("alpha"), text("bravo"), text("charlie")));
    assertThat(render(15, doc), isLines("[alpha,", " bravo,", " charlie]"));
  }

  @Test
  void testEncloseSepEmpty() {
    final Doc doc =
        encloseSep(text("["), text("]"), text(","), ImmutableList.of());
    assertThat(render(80, doc), is("[]"));
  }

  @Test
  void testEncloseSepSingleton() {
    final Doc doc =
        encloseSep(
            text("["), text("]"), text(","), ImmutableList.of(text("only")));
    assertThat(render(80, doc), is("[only]"));
  }

  // -- Bracketing helpers ---------------------------------------------------

  @Test
  void testParens() {
    assertThat(render(80, parens(text("x"))), is("(x)"));
  }

  @Test
  void testBrackets() {
    assertThat(render(80, brackets(text("x"))), is("[x]"));
  }

  // -- Combined examples ----------------------------------------------------

  /** Wadler's classic tree example. */
  @Test
  void testTreeExample() {
    // showTree (Node "aaa" [Node "bbb" [Node "ccc" [], Node "ddd" []],
    //                       Node "eee" []])
    final Doc ccc = text("ccc");
    final Doc ddd = text("ddd");
    final Doc bbb =
        beside(
            text("bbb"),
            nest(
                2,
                beside(
                    LINE,
                    beside(
                        text("["),
                        beside(
                            beside(ccc, beside(text(","), beside(LINE, ddd))),
                            text("]"))))));
    final Doc eee = text("eee");
    final Doc aaa =
        beside(
            text("aaa"),
            nest(
                2,
                beside(
                    LINE,
                    beside(
                        text("["),
                        beside(
                            beside(
                                group(bbb),
                                beside(text(","), beside(LINE, eee))),
                            text("]"))))));
    final Doc doc = group(aaa);

    // Wide enough: fits on one line
    assertThat(render(80, doc), is("aaa [bbb [ccc, ddd], eee]"));

    // Narrow: outer breaks, inner bbb group fits
    assertThat(render(20, doc), isLines("aaa", "  [bbb [ccc, ddd],", "  eee]"));

    // Very narrow: everything breaks
    assertThat(
        render(10, doc),
        isLines("aaa", "  [bbb", "    [ccc,", "    ddd],", "  eee]"));
  }

  /** SML-style let expression. */
  @Test
  void testLetExpression() {
    // let val x = 1; val y = 2 in x + y end
    final Doc valX = hsep(words("val x = 1"));
    final Doc valY = hsep(words("val y = 2"));
    final Doc body = hsep(words("x + y"));
    final Doc doc =
        group(
            beside(
                text("let"),
                beside(
                    nest(
                        2,
                        beside(
                            LINE,
                            beside(
                                valX, beside(text(";"), beside(LINE, valY))))),
                    beside(
                        LINE,
                        beside(
                            text("in"),
                            beside(
                                nest(2, beside(LINE, body)),
                                beside(LINE, text("end"))))))));

    // Wide enough: all on one line
    assertThat(render(80, doc), is("let val x = 1; val y = 2 in x + y end"));

    // Narrow: structured
    assertThat(
        render(20, doc),
        isLines("let", "  val x = 1;", "  val y = 2", "in", "  x + y", "end"));
  }

  /** SML-style function with match arms using align. */
  @Test
  void testMatchArms() {
    // fn 0 => 1 | n => n * fact (n - 1)
    final Doc arm1 = hsep(words("0 => 1"));
    final Doc arm2 = hsep(words("n => n * fact (n - 1)"));
    final Doc arms = beside(arm1, beside(LINE, beside(text("| "), arm2)));
    final Doc doc = beside(text("fn "), group(align(arms)));

    // Wide: all on one line
    assertThat(render(80, doc), is("fn 0 => 1 | n => n * fact (n - 1)"));

    // Narrow: arms on separate lines, | aligned
    assertThat(
        render(20, doc), isLines("fn 0 => 1", "   | n => n * fact (n - 1)"));
  }

  /** Verify that multiple groups independently decide to break or not. */
  @Test
  void testNestedGroups() {
    final Doc inner = group(sep(words("alpha bravo charlie")));
    final Doc outer =
        group(sep(ImmutableList.of(text("before"), inner, text("after"))));
    // Wide: everything on one line
    assertThat(render(80, outer), is("before alpha bravo charlie after"));
    // Medium: outer breaks, inner fits
    assertThat(
        render(30, outer), isLines("before", "alpha bravo charlie", "after"));
    // Narrow: everything breaks
    assertThat(
        render(10, outer),
        isLines("before", "alpha", "bravo", "charlie", "after"));
  }

  // -- Stack safety and scalability -----------------------------------------

  /**
   * Renders a deeply nested document. A recursive renderer would overflow the
   * stack at this depth; the iterative renderer uses constant stack.
   */
  @Test
  void testDeepNesting() {
    final int depth = 100_000;
    Doc doc = text("x");
    for (int n = 0; n < depth; n++) {
      doc = beside(text("("), beside(doc, text(")")));
    }
    final String s = render(80, doc);
    assertThat(s.length(), is(depth * 2 + 1));
    assertThat(s, containsString("(((x)))"));
  }

  /**
   * Renders a document with many deeply nested breaking groups. The naive
   * strict variant of Wadler's algorithm lays out each group's continuation
   * afresh, so this would take exponential time (and overflow the stack); the
   * iterative renderer is linear.
   */
  @Test
  void testDeepBreakingGroups() {
    final int depth = 2_000;
    Doc doc = text("leaf");
    for (int n = 0; n < depth; n++) {
      doc =
          group(
              nest(
                  2,
                  beside(
                      text("("),
                      beside(
                          LINE_BREAK,
                          beside(doc, beside(LINE_BREAK, text(")")))))));
    }
    // Width 10 forces every level to break.
    final String s = render(10, doc);
    assertThat(s, containsString("leaf"));
    assertThat(s.chars().filter(c -> c == '(').count(), is((long) depth));
    assertThat(s.chars().filter(c -> c == '\n').count(), is((long) depth * 2));
  }

  /** Renders a wide list with many elements without overflowing the stack. */
  @Test
  void testWideList() {
    final int size = 50_000;
    final List<Doc> elements = new ArrayList<>();
    for (int n = 0; n < size; n++) {
      elements.add(text("e"));
    }
    final Doc doc = encloseSep(text("["), text("]"), text(","), elements);
    final String s = render(80, doc);
    assertThat(s.chars().filter(c -> c == ',').count(), is((long) size - 1));
    assertThat(s, containsString("[e,"));
  }
}

// End LindigTest.java
