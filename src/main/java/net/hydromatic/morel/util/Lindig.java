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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.function.BinaryOperator;
import java.util.function.IntFunction;
import org.apache.calcite.avatica.util.Spaces;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Pretty-printer that lays out a document within a line-width limit.
 *
 * <p>The {@link Doc} algebraic data type represents a set of possible layouts
 * for a document; {@link #render(int, Doc)} chooses the best layout that fits a
 * given line width.
 *
 * <p>The design draws on a line of work on pretty-printing combinators: <a
 * href="https://dl.acm.org/doi/pdf/10.1145/357114.357115">Oppen's
 * "Prettyprinting"</a> (1980), <a
 * href="https://homepages.inf.ed.ac.uk/wadler/papers/prettier/prettier.pdf">
 * Wadler's "A prettier printer"</a> (2002), and Leijen's {@code Column}, {@code
 * Nesting}, and {@code FlatAlt} extensions (which enable {@link #align(Doc)}).
 * The class is named after Christian Lindig, whose <a
 * href="https://lindig.github.io/papers/strictly-pretty-2000.pdf">"Strictly
 * Pretty"</a> (2000) gives the iterative work-list formulation of the layout
 * algorithm used here.
 *
 * <p>{@link #render(int, Doc)} lays the document out as a lazy stream of {@link
 * Out} chunks (Leijen's {@code SimpleDoc}), produced on demand by {@link Thunk}
 * and memoized. The fit test, {@link #fits}, scans that stream rather than
 * re-deciding the document, so each layout decision is computed once and shared
 * by every scan that passes over it. A strict {@code fits} that re-decides
 * downstream groups costs O(2<sup>n</sup>) in the number of nested decision
 * points; scanning the stream makes the lookahead both affordable and exact,
 * because what it measures is the text that will actually be emitted.
 *
 * <p>This class has no dependency on Morel's AST.
 */
public class Lindig {
  private Lindig() {}

  // -- Doc algebraic type ---------------------------------------------------

  /**
   * A document that can be laid out in multiple ways.
   *
   * <p>Instances are created via the static methods in {@link Lindig}.
   */
  public abstract static class Doc {
    private Doc() {}
  }

  /** The empty document. */
  static final class Empty extends Doc {
    static final Empty INSTANCE = new Empty();

    private Empty() {}

    @Override
    public String toString() {
      return "Empty";
    }
  }

  /** Literal text {@code s} followed by {@code doc}. */
  static final class Text extends Doc {
    final String text;
    final Doc doc;

    Text(String text, Doc doc) {
      this.text = requireNonNull(text);
      this.doc = requireNonNull(doc);
    }

    @Override
    public String toString() {
      return "Text(" + text + ")";
    }
  }

  /** Newline, then {@code indent} spaces, then {@code doc}. */
  static final class Line extends Doc {
    final Doc doc;

    Line(Doc doc) {
      this.doc = requireNonNull(doc);
    }

    @Override
    public String toString() {
      return "Line";
    }
  }

  /**
   * {@code primary} when broken across lines; {@code flat} when flattened to
   * one line.
   */
  static final class FlatAlt extends Doc {
    final Doc primary;
    final Doc flat;

    FlatAlt(Doc primary, Doc flat) {
      this.primary = requireNonNull(primary);
      this.flat = requireNonNull(flat);
    }

    @Override
    public String toString() {
      return "FlatAlt";
    }
  }

  /** Concatenation of {@code a} followed by {@code b}. */
  static final class Cat extends Doc {
    final Doc a;
    final Doc b;

    Cat(Doc a, Doc b) {
      this.a = requireNonNull(a);
      this.b = requireNonNull(b);
    }

    @Override
    public String toString() {
      return "Cat";
    }
  }

  /** Increase indentation by {@code indent} for the sub-document. */
  static final class Nest extends Doc {
    final int indent;
    final Doc doc;

    Nest(int indent, Doc doc) {
      this.indent = indent;
      this.doc = requireNonNull(doc);
    }

    @Override
    public String toString() {
      return "Nest(" + indent + ")";
    }
  }

  /**
   * Lay out {@code doc} flat if it fits the remaining space, otherwise broken.
   *
   * <p>Flattening happens on the fly during rendering (via {@link Mode#FLAT}),
   * so there is no separate flattened copy of {@code doc}.
   */
  static final class Group extends Doc {
    final Doc doc;

    Group(Doc doc) {
      this.doc = requireNonNull(doc);
    }

    @Override
    public String toString() {
      return "Group";
    }
  }

  /**
   * Choose {@code wide} if its first line fits the remaining space, otherwise
   * {@code narrow}.
   *
   * <p>Unlike {@link Group}, the two alternatives may have different structure;
   * {@code wide} is typically more flattened than {@code narrow}. This is what
   * {@link #pack} needs: each gap lays the following element out flat for the
   * fit-test but leaves it free to break in the real layout.
   */
  static final class Union extends Doc {
    final Doc wide;
    final Doc narrow;

    Union(Doc wide, Doc narrow) {
      this.wide = requireNonNull(wide);
      this.narrow = requireNonNull(narrow);
    }

    @Override
    public String toString() {
      return "Union";
    }
  }

  /** Access the current column to produce a document. */
  static final class Column extends Doc {
    final IntFunction<Doc> fn;

    Column(IntFunction<Doc> fn) {
      this.fn = requireNonNull(fn);
    }

    @Override
    public String toString() {
      return "Column";
    }
  }

  /** Access the current nesting level to produce a document. */
  static final class Nesting extends Doc {
    final IntFunction<Doc> fn;

    Nesting(IntFunction<Doc> fn) {
      this.fn = requireNonNull(fn);
    }

    @Override
    public String toString() {
      return "Nesting";
    }
  }

  // -- Primitives -----------------------------------------------------------

  /** The empty document. */
  public static final Doc EMPTY = Empty.INSTANCE;

  /**
   * A line break that is replaced by a space when flattened.
   *
   * <p>This is the most common separator for {@link #group(Doc)}: when the
   * group fits on one line the line breaks become spaces.
   */
  public static final Doc LINE =
      new FlatAlt(new Line(EMPTY), new Text(" ", EMPTY));

  /**
   * A line break that is replaced by nothing when flattened.
   *
   * <p>Useful when the line break only exists for formatting, e.g. after an
   * opening bracket.
   */
  public static final Doc LINE_BREAK = new FlatAlt(new Line(EMPTY), EMPTY);

  /**
   * A space when it fits, otherwise a line break.
   *
   * <p>Equivalent to {@code group(line)}.
   */
  public static final Doc SOFT_LINE = group(LINE);

  /**
   * Nothing when it fits, otherwise a line break.
   *
   * <p>Equivalent to {@code group(lineBreak)}.
   */
  public static final Doc SOFT_BREAK = group(LINE_BREAK);

  /**
   * A line break that is always rendered, even when flattened.
   *
   * <p>Use sparingly — this prevents the enclosing {@code group} from
   * flattening.
   */
  public static final Doc HARD_LINE = new Line(EMPTY);

  /**
   * Creates a document containing literal text. The text must not contain
   * newlines; use {@link #LINE} or {@link #HARD_LINE} for those.
   */
  public static Doc text(String s) {
    if (s.isEmpty()) {
      return EMPTY;
    }
    return new Text(s, EMPTY);
  }

  // -- Composition ----------------------------------------------------------

  /** Concatenation: {@code a} followed by {@code b}. */
  public static Doc beside(Doc a, Doc b) {
    return new Cat(a, b);
  }

  /** Increases indentation of {@code doc} by {@code indent} spaces. */
  public static Doc nest(int indent, Doc doc) {
    checkArgument(indent >= 0, "indent must be nonnegative: %s", indent);
    return nestUnchecked(indent, doc);
  }

  /**
   * Marks {@code doc} as a group: {@link #render(int, Doc)} lays the group out
   * flat (line breaks become their flat alternatives) if it fits the remaining
   * width, and otherwise lays it out broken.
   *
   * <p>A group that contains a {@link #HARD_LINE} can never be laid out flat,
   * so it always breaks.
   */
  public static Doc group(Doc doc) {
    if (doc instanceof Group) {
      return doc; // already a group
    }
    return new Group(doc);
  }

  /**
   * Lays out {@code wide} if its first line fits the remaining space, otherwise
   * {@code narrow}.
   *
   * <p>Unlike {@link #group(Doc)}, the alternatives may differ in structure,
   * and the choice is the union's own — it is not forced flat by an enclosing
   * flat layout. The caller must ensure {@code wide} is at least as flat as
   * {@code narrow} (typically {@code wide} is {@link #flatten(Doc) flattened}),
   * so that "fits" implies "is a valid layout". This is the primitive behind
   * {@link #pack}.
   */
  public static Doc union(Doc wide, Doc narrow) {
    return new Union(wide, narrow);
  }

  /**
   * Lays out {@code doc} with the nesting level set to the current column.
   *
   * <p>This is used to align sub-documents to the current position, e.g. to
   * align {@code |} in match arms or {@code ,} in tuples.
   */
  public static Doc align(Doc doc) {
    // The relative nesting "k - i" is negative when the current column k is
    // left of the current indent i, so it must bypass the nonnegative check
    // that the public "nest" applies to caller-supplied indents.
    return new Column(k -> new Nesting(i -> nestUnchecked(k - i, doc)));
  }

  /**
   * Lays out {@code doc} with a nesting level of {@code indent} relative to the
   * current column.
   *
   * <p>Equivalent to {@code align(nest(indent, doc))}.
   */
  public static Doc hang(int indent, Doc doc) {
    checkArgument(indent >= 0, "indent must be nonnegative: %s", indent);
    return align(nestUnchecked(indent, doc));
  }

  /**
   * Indents {@code doc} by {@code indent} spaces, and then aligns subsequent
   * lines to the first.
   */
  public static Doc indent(int indent, Doc doc) {
    checkArgument(indent >= 0, "indent must be nonnegative: %s", indent);
    return beside(text(Spaces.of(indent)), align(nestUnchecked(indent, doc)));
  }

  // -- List combinators -----------------------------------------------------

  /** Concatenates documents horizontally, separated by spaces. */
  public static Doc hsep(List<Doc> docs) {
    return fold(docs, Lindig::withSpace);
  }

  /** Concatenates documents vertically, separated by line breaks. */
  public static Doc vsep(List<Doc> docs) {
    return fold(docs, Lindig::withLine);
  }

  /**
   * Concatenates documents separated by spaces if they fit on one line,
   * otherwise separates them with line breaks. Equivalent to {@code
   * group(vsep(docs))}.
   */
  public static Doc sep(List<Doc> docs) {
    return group(vsep(docs));
  }

  /** Concatenates documents horizontally with no separator. */
  public static Doc hcat(List<Doc> docs) {
    return fold(docs, Lindig::beside);
  }

  /** Concatenates documents vertically, separated by empty line breaks. */
  public static Doc vcat(List<Doc> docs) {
    return fold(docs, Lindig::withLineBreak);
  }

  /**
   * Concatenates documents with no separator if they fit on one line, otherwise
   * separates them with line breaks. Equivalent to {@code group(vcat(docs))}.
   */
  public static Doc cat(List<Doc> docs) {
    return group(vcat(docs));
  }

  /**
   * Concatenates documents, filling each line with as many as will fit,
   * separated by spaces.
   */
  public static Doc fillSep(List<Doc> docs) {
    return fold(docs, Lindig::withSoftLine);
  }

  /**
   * Concatenates documents, filling each line with as many as will fit, with no
   * separator.
   */
  public static Doc fillCat(List<Doc> docs) {
    return fold(docs, Lindig::withSoftBreak);
  }

  /**
   * Packs documents onto as many lines as needed, putting as many as fit on
   * each line, joined by {@code glue} when packed and by a line break
   * otherwise.
   *
   * <p>Unlike {@link #fillSep} and {@link #fillCat}, each gap is decided by
   * whether the <em>following</em> document, laid out flat, fits on the current
   * line; a document is treated as an indivisible unit even if it contains its
   * own line breaks. This matches the way an Oppen printer breaks an
   * inconsistent block whose members are themselves blocks — for example a list
   * of records, where each record stays together and the list wraps between
   * records.
   *
   * @param glue separator inserted between two documents that share a line
   *     (often a space, or {@link #EMPTY})
   * @param docs documents to pack
   */
  public static Doc pack(Doc glue, List<Doc> docs) {
    if (docs.isEmpty()) {
      return EMPTY;
    }
    // The first element renders normally; each later element is preceded by a
    // gap that is either `glue` (stay on the line) or a line break. The gap
    // before element i is decided by whether element i, laid out flat, fits
    // after `glue`; if it does the element is committed flat, otherwise it
    // moves to a fresh line where it is free to break internally. Built
    // right-to-left so each suffix is shared (linear, not exponential).
    final int n = docs.size();
    Doc tail = EMPTY;
    for (int i = n - 1; i >= 1; i--) {
      final Doc x = docs.get(i);
      final Doc rest = tail;
      tail =
          new Union(
              beside(glue, beside(flatten(x), rest)),
              beside(HARD_LINE, beside(x, rest)));
    }
    return beside(docs.get(0), tail);
  }

  /**
   * Returns the flattened form of {@code doc}: every soft line break takes its
   * flat alternative and every {@link #group(Doc)} is laid out flat. A {@link
   * #HARD_LINE} cannot be flattened and is left as a line break.
   */
  public static Doc flatten(Doc doc) {
    if (doc instanceof Empty || doc instanceof Line) {
      return doc;
    } else if (doc instanceof Text) {
      final Text t = (Text) doc;
      return new Text(t.text, flatten(t.doc));
    } else if (doc instanceof Cat) {
      final Cat cat = (Cat) doc;
      return new Cat(flatten(cat.a), flatten(cat.b));
    } else if (doc instanceof Nest) {
      final Nest nest = (Nest) doc;
      return new Nest(nest.indent, flatten(nest.doc));
    } else if (doc instanceof FlatAlt) {
      return flatten(((FlatAlt) doc).flat);
    } else if (doc instanceof Group) {
      return flatten(((Group) doc).doc);
    } else if (doc instanceof Union) {
      return flatten(((Union) doc).wide);
    } else if (doc instanceof Column) {
      final Column c = (Column) doc;
      return new Column(k -> flatten(c.fn.apply(k)));
    } else if (doc instanceof Nesting) {
      final Nesting nesting = (Nesting) doc;
      return new Nesting(i -> flatten(nesting.fn.apply(i)));
    } else {
      throw new AssertionError("unknown Doc: " + doc);
    }
  }

  /**
   * Intersperses {@code separator} between the documents.
   *
   * <p>For example, {@code punctuate(text(","), [a, b, c])} returns {@code
   * [beside(a, text(",")), beside(b, text(",")), c]}.
   */
  public static List<Doc> punctuate(Doc separator, List<Doc> docs) {
    if (docs.size() <= 1) {
      return docs;
    }
    final ImmutableList.Builder<Doc> b = ImmutableList.builder();
    for (int i = 0; i < docs.size() - 1; i++) {
      b.add(beside(docs.get(i), separator));
    }
    b.add(docs.get(docs.size() - 1));
    return b.build();
  }

  /**
   * Encloses a list of documents between {@code open} and {@code close},
   * separated by {@code separator}.
   *
   * <p>If the result fits on one line it is rendered horizontally with spaces
   * after each separator; otherwise each element is placed on its own line,
   * aligned.
   *
   * <p>The separator should be just the punctuation (e.g., {@code text(",")})
   * without a trailing space; the space or line break is added automatically.
   */
  public static Doc encloseSep(
      Doc open, Doc close, Doc separator, List<Doc> docs) {
    if (docs.isEmpty()) {
      return beside(open, close);
    }
    if (docs.size() == 1) {
      return beside(open, beside(docs.get(0), close));
    }
    final List<Doc> punctuated = punctuate(separator, docs);
    return group(beside(open, beside(align(vsep(punctuated)), close)));
  }

  // -- Bracketing helpers ---------------------------------------------------

  /** Encloses {@code doc} in parentheses. */
  public static Doc parens(Doc doc) {
    return beside(text("("), beside(doc, text(")")));
  }

  /** Encloses {@code doc} in braces. */
  public static Doc braces(Doc doc) {
    return beside(text("{"), beside(doc, text("}")));
  }

  /** Encloses {@code doc} in square brackets. */
  public static Doc brackets(Doc doc) {
    return beside(text("["), beside(doc, text("]")));
  }

  // -- Rendering ------------------------------------------------------------

  /**
   * Renders a document to a string, choosing the best layout for the given line
   * width.
   */
  public static String render(int width, Doc doc) {
    final StringBuilder b = new StringBuilder();
    Thunk thunk = new Thunk(width, 0, new Item(0, Mode.BREAK, doc, null));
    for (; ; ) {
      final Out out = thunk.force();
      if (out instanceof OutEnd) {
        return b.toString();
      } else if (out instanceof OutText) {
        final OutText t = (OutText) out;
        b.append(t.text);
        thunk = t.rest;
      } else {
        final OutLine line = (OutLine) out;
        b.append('\n').append(Spaces.of(line.indent));
        thunk = line.rest;
      }
    }
  }

  /**
   * Lays out the work list until it produces the next output chunk.
   *
   * <p>Each chunk carries a {@link Thunk} for the rest of the stream, so the
   * layout is produced on demand; {@link #fits} forces only as much of it as
   * the fit test needs, and {@link Thunk} memoizes what has been forced so that
   * a later pass does not redo the work.
   *
   * @param width page width
   * @param col current column
   * @param item work list to lay out
   */
  private static Out best(int width, int col, @Nullable Item item) {
    while (item != null) {
      final int i = item.indent;
      final Mode mode = item.mode;
      final Doc d = item.doc;
      final Item next = item.next;
      if (d instanceof Empty) {
        item = next;
      } else if (d instanceof Text) {
        final Text t = (Text) d;
        final Item rest =
            t.doc instanceof Empty ? next : new Item(i, mode, t.doc, next);
        return new OutText(
            t.text, new Thunk(width, col + t.text.length(), rest));
      } else if (d instanceof Cat) {
        final Cat cat = (Cat) d;
        item = new Item(i, mode, cat.a, new Item(i, mode, cat.b, next));
      } else if (d instanceof Nest) {
        final Nest nest = (Nest) d;
        item = new Item(i + nest.indent, mode, nest.doc, next);
      } else if (d instanceof Line) {
        // A bare line cannot be flattened, so one that survives into a flat
        // layout still emits a line break but marks the layout as not fitting;
        // that is what makes a group containing HARD_LINE always break.
        final Doc rest = ((Line) d).doc;
        final Item r =
            rest instanceof Empty ? next : new Item(i, mode, rest, next);
        return new OutLine(i, mode == Mode.FLAT, new Thunk(width, i, r));
      } else if (d instanceof FlatAlt) {
        final FlatAlt f = (FlatAlt) d;
        item = new Item(i, mode, mode == Mode.FLAT ? f.flat : f.primary, next);
      } else if (d instanceof Group) {
        // Inside a flat layout every group is flat too, and needs no decision.
        // Otherwise the group is flat if its flattened layout, followed by
        // whatever comes after it, reaches the end of the line within the page
        // width. The lookahead is exact: it measures the stream that rendering
        // would emit, including the text that a downstream group contributes
        // to this line before it breaks.
        final Doc inner = ((Group) d).doc;
        final Item flat = new Item(i, Mode.FLAT, inner, next);
        if (mode == Mode.FLAT) {
          item = flat;
          continue;
        }
        final Thunk thunk = new Thunk(width, col, flat);
        if (fits(width - col, thunk)) {
          return thunk.force();
        }
        item = new Item(i, Mode.BREAK, inner, next);
      } else if (d instanceof Union) {
        // A union always makes its own decision (it is not forced flat by an
        // enclosing flat layout), so the gaps of a fill break independently.
        final Union u = (Union) d;
        final Thunk thunk =
            new Thunk(width, col, new Item(i, Mode.FLAT, u.wide, next));
        if (fits(width - col, thunk)) {
          return thunk.force();
        }
        item = new Item(i, Mode.BREAK, u.narrow, next);
      } else if (d instanceof Column) {
        item = new Item(i, mode, ((Column) d).fn.apply(col), next);
      } else if (d instanceof Nesting) {
        item = new Item(i, mode, ((Nesting) d).fn.apply(i), next);
      } else {
        throw new AssertionError("unknown Doc: " + d);
      }
    }
    return OutEnd.INSTANCE;
  }

  // -- Private helpers ------------------------------------------------------

  /** Folds a list of documents using a binary operator. */
  private static Doc fold(List<Doc> docs, BinaryOperator<Doc> op) {
    switch (docs.size()) {
      case 0:
        return EMPTY;
      case 1:
        return docs.get(0);
      default:
        Doc result = docs.get(docs.size() - 1);
        for (int i = docs.size() - 2; i >= 0; i--) {
          result = op.apply(docs.get(i), result);
        }
        return result;
    }
  }

  private static Doc nestUnchecked(int indent, Doc doc) {
    if (indent == 0) {
      return doc;
    }
    return new Nest(indent, doc);
  }

  private static Doc withSpace(Doc a, Doc b) {
    return beside(a, beside(text(" "), b));
  }

  private static Doc withLine(Doc a, Doc b) {
    return beside(a, beside(LINE, b));
  }

  private static Doc withLineBreak(Doc a, Doc b) {
    return beside(a, beside(LINE_BREAK, b));
  }

  private static Doc withSoftLine(Doc a, Doc b) {
    return beside(a, beside(SOFT_LINE, b));
  }

  private static Doc withSoftBreak(Doc a, Doc b) {
    return beside(a, beside(SOFT_BREAK, b));
  }

  /**
   * Returns whether the rest of the layout fits in the remaining space on the
   * current line. Scans the output stream forward until the first line break
   * (which ends the current line, so what precedes it fits) or until the
   * remaining space runs out.
   *
   * <p>Because the scan consumes the chunks that rendering will emit, the
   * layout decisions it passes over have already been made — and are memoized,
   * so the caller that commits to this stream, and any later scan over it, gets
   * them for free.
   *
   * @param remaining space left on the current line
   * @param thunk rest of the output stream
   */
  private static boolean fits(int remaining, Thunk thunk) {
    for (; ; ) {
      if (remaining < 0) {
        return false;
      }
      final Out out = thunk.force();
      if (out instanceof OutEnd) {
        return true;
      } else if (out instanceof OutText) {
        final OutText t = (OutText) out;
        remaining -= t.text.length();
        thunk = t.rest;
      } else {
        // A line break ends the current line, so what precedes it fits --
        // unless it is a bare line that a flat layout could not flatten, in
        // which case the layout is invalid and the group must break.
        return !((OutLine) out).flat;
      }
    }
  }

  // -- Output stream --------------------------------------------------------

  /**
   * A chunk of laid-out output: literal text, a line break, or the end of the
   * stream. Leijen calls this a {@code SimpleDoc}: a document from which every
   * layout choice has been removed.
   */
  private abstract static class Out {
    private Out() {}
  }

  /** End of the output stream. */
  private static final class OutEnd extends Out {
    static final OutEnd INSTANCE = new OutEnd();
  }

  /** Literal text, followed by the rest of the stream. */
  private static final class OutText extends Out {
    final String text;
    final Thunk rest;

    OutText(String text, Thunk rest) {
      this.text = text;
      this.rest = rest;
    }
  }

  /**
   * A line break and the indent of the following line, followed by the rest of
   * the stream.
   *
   * <p>{@code flat} means the break came from a bare {@link Line} that a flat
   * layout could not flatten. Rendering emits it like any other break, but
   * {@link #fits} rejects the layout that contains it.
   */
  private static final class OutLine extends Out {
    final int indent;
    final boolean flat;
    final Thunk rest;

    OutLine(int indent, boolean flat, Thunk rest) {
      this.indent = indent;
      this.flat = flat;
      this.rest = rest;
    }
  }

  /**
   * The unevaluated rest of an output stream: a work list plus the column it
   * starts at, which {@link #force()} lays out into the next {@link Out} chunk.
   *
   * <p>The chunk is computed at most once, however many scans pass over it.
   * That sharing is what keeps the lookahead in {@link #fits} affordable.
   */
  private static final class Thunk {
    private final int width;
    private final int col;
    private @Nullable Item item;
    private @Nullable Out out;

    Thunk(int width, int col, @Nullable Item item) {
      this.width = width;
      this.col = col;
      this.item = item;
    }

    Out force() {
      if (out == null) {
        out = best(width, col, item);
        item = null; // let the work list be collected
      }
      return out;
    }
  }

  /** Layout mode of a work-list item: {@code FLAT} suppresses line breaks. */
  private enum Mode {
    FLAT,
    BREAK
  }

  /**
   * An entry in the work list for the layout algorithm: render {@link #doc} at
   * the given indent and mode, then continue with {@link #next}.
   *
   * <p>The list is immutable, so the tail can be shared between the flat and
   * broken alternatives of a {@link Group} without copying.
   */
  private static final class Item {
    final int indent;
    final Mode mode;
    final Doc doc;
    final @Nullable Item next;

    Item(int indent, Mode mode, Doc doc, @Nullable Item next) {
      this.indent = indent;
      this.mode = mode;
      this.doc = doc;
      this.next = next;
    }
  }
}

// End Lindig.java
