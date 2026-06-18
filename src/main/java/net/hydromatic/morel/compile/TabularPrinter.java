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
package net.hydromatic.morel.compile;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.util.PairList;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Prints a collection of records as a table. Used by {@link Pretty} when the
 * output mode is {@code tabular} and the value's type is tabular-printable (see
 * {@link #canPrint}).
 *
 * <p>Supports nested collections: a record field may itself be a collection of
 * primitives, or a collection of records (recursively, to any depth). Tuples
 * are treated as records with fields named {@code "1"}, {@code "2"}, etc.
 *
 * <p>The renderer works in two passes:
 *
 * <ol>
 *   <li><b>Stringify and measure.</b> A single recursive walk of the data
 *       stringifies each scalar exactly once and caches it in a {@link Cell}
 *       tree, while updating leaf widths in the parallel {@link Section} tree.
 *   <li><b>Emit.</b> Streaming {@link Iterator}s walk the cached strings to
 *       produce one row of output at a time. {@link RecordListIter} drives a
 *       {@link RowIter} per nested record; line height falls out of iterator
 *       exhaustion, so we never compute "longest nested collection" explicitly.
 * </ol>
 */
class TabularPrinter {
  private static final char NEWLINE = '\n';

  private static final String ELLIPSIS = "...";

  private final int printDepth;
  private final int printLength;
  private final int stringDepth;
  private final int stringFold;

  TabularPrinter(
      int printDepth, int printLength, int stringDepth, int stringFold) {
    this.printDepth = printDepth;
    this.printLength = printLength;
    this.stringDepth = stringDepth;
    this.stringFold = stringFold;
  }

  /**
   * Returns whether a type can be rendered as a table. A type is
   * tabular-printable if it is a collection (list or bag) of records or tuples
   * every field of which is itself a tabular-printable field type (see {@link
   * #canPrintField}).
   */
  static boolean canPrint(Type type) {
    if (!type.isCollection()) {
      return false;
    }
    final Type elementType = type.elementType();
    if (!(elementType instanceof RecordType)
        && !(elementType instanceof TupleType)) {
      return false;
    }
    return canPrintRecord((RecordLikeType) elementType);
  }

  /**
   * Renders a tabular value into {@code buf}. Returns {@code true} on success.
   * Returns {@code false} (without writing to {@code buf}) when {@code
   * printDepth} would force the outer collection's elements to be rendered as
   * {@code #} — in that case the caller should fall back to classic so the same
   * {@code #} appears there.
   */
  boolean print(StringBuilder buf, int depth, Type type, Object value) {
    if (printDepth >= 0 && depth + 1 > printDepth) {
      return false;
    }
    final RecordLikeType recordType = (RecordLikeType) type.elementType();
    final Section root = Section.forRecord("", recordType);
    final RecordListCell rootCell =
        (RecordListCell)
            root.buildCell(value, printLength, stringDepth, stringFold);
    root.finalizeWidths();

    int headerLines = 0;
    for (Section child : root.children) {
      headerLines = Math.max(headerLines, child.headerDepth());
    }
    for (int line = 0; line < headerLines; line++) {
      emitHeaderLine(buf, root.children, line);
    }
    emitSeparatorRow(buf, root.children);
    emitDataRows(buf, root, rootCell);
    buf.append(NEWLINE);
    return true;
  }

  private static boolean canPrintRecord(RecordLikeType recordType) {
    return PairList.viewOf(recordType.argNameTypes())
        .allMatch((label, type) -> canPrintField(type));
  }

  /**
   * Whether a type is acceptable as a field within a tabular row. It is
   * acceptable if it is one of:
   *
   * <ul>
   *   <li>a primitive (scalar leaf);
   *   <li>an option of a primitive (scalar leaf; {@code NONE} prints as blank);
   *   <li>a collection of primitives (scalar list);
   *   <li>a collection of records or tuples, each field of which is itself
   *       tabular-printable as a field (recursive).
   * </ul>
   */
  private static boolean canPrintField(Type type) {
    if (type instanceof PrimitiveType) {
      return true;
    }
    if (optionScalar(type) != null) {
      return true;
    }
    // A bare record or tuple field: a one-row nested sub-table.
    if (type instanceof RecordType || type instanceof TupleType) {
      return canPrintRecord((RecordLikeType) type);
    }
    // A record/tuple 'option' field: a nested sub-table, blank for 'NONE'. It
    // is renderable only if at least one field is non-option; otherwise a
    // 'NONE' (every cell blank) could not be told apart from a 'SOME' whose
    // every field happens to be 'NONE'.
    final RecordLikeType optionRecord = optionRecord(type);
    if (optionRecord != null) {
      return canPrintRecord(optionRecord) && !allFieldsOption(optionRecord);
    }
    if (type.isCollection()) {
      Type elementType = type.elementType();
      if (elementType instanceof PrimitiveType) {
        return true;
      }
      if (elementType instanceof RecordType
          || elementType instanceof TupleType) {
        return canPrintRecord((RecordLikeType) elementType);
      }
    }
    return false;
  }

  /**
   * If {@code type} is {@code T option} where {@code T} is a record or tuple,
   * returns {@code T}; otherwise returns null.
   */
  private static @Nullable RecordLikeType optionRecord(Type type) {
    if (type instanceof DataType) {
      final DataType dataType = (DataType) type;
      if (dataType.name.equals("option")
          && (dataType.arg(0) instanceof RecordType
              || dataType.arg(0) instanceof TupleType)) {
        return (RecordLikeType) dataType.arg(0);
      }
    }
    return null;
  }

  /** Returns whether every field of {@code recordType} has an option type. */
  private static boolean allFieldsOption(RecordLikeType recordType) {
    return PairList.viewOf(recordType.argNameTypes())
        .allMatch((label, type) -> isOption(type));
  }

  /** Returns whether {@code type} is an {@code option}. */
  private static boolean isOption(Type type) {
    return type instanceof DataType && ((DataType) type).name.equals("option");
  }

  /**
   * If {@code type} is {@code T option} where {@code T} is a primitive, returns
   * {@code T}; otherwise returns null. Such a field is rendered as a scalar
   * column: {@code SOME x} as {@code x}, and {@code NONE} as a blank cell.
   */
  private static @Nullable PrimitiveType optionScalar(Type type) {
    if (type instanceof DataType) {
      final DataType dataType = (DataType) type;
      if (dataType.name.equals("option")
          && dataType.arg(0) instanceof PrimitiveType) {
        return (PrimitiveType) dataType.arg(0);
      }
    }
    return null;
  }

  /** Emits one header line for the given top-level sections. */
  private void emitHeaderLine(
      StringBuilder buf, List<Section> children, int line) {
    final StringBuilder lineBuf = new StringBuilder();
    for (int i = 0; i < children.size(); i++) {
      if (i > 0) {
        lineBuf.append(' ');
      }
      children.get(i).appendHeaderCell(lineBuf, line);
    }
    appendLine(buf, lineBuf);
  }

  /** Emits the dashed separator row, one cell per leaf section. */
  private void emitSeparatorRow(StringBuilder buf, List<Section> children) {
    final StringBuilder lineBuf = new StringBuilder();
    for (int i = 0; i < children.size(); i++) {
      if (i > 0) {
        lineBuf.append(' ');
      }
      children.get(i).appendSeparator(lineBuf);
    }
    buf.append(lineBuf).append(NEWLINE);
  }

  /**
   * Emits data rows. The root section is a RECORD_LIST whose children are the
   * top-level fields; for each top-level record we drive a {@link RowIter}
   * until exhaustion, guaranteeing at least one emitted line per top-level row
   * (even when every nested collection is empty). If the root cell was
   * truncated to {@code printLength} rows, a final {@link #ELLIPSIS} line is
   * emitted.
   */
  private void emitDataRows(
      StringBuilder buf, Section root, RecordListCell rootCell) {
    for (List<Cell> record : rootCell.records) {
      final List<Iterator<String>> iters = new ArrayList<>();
      for (int i = 0; i < root.children.size(); i++) {
        iters.add(record.get(i).iter(root.children.get(i)));
      }
      final RowIter row = new RowIter(root.children, iters);
      do {
        appendLine(buf, row.next());
      } while (row.hasNext());
    }
    if (rootCell.truncated) {
      appendLine(buf, ELLIPSIS);
    }
  }

  /**
   * Returns the unquoted string form used for a scalar value in a table. String
   * values longer than {@code stringDepth} are truncated and marked with a
   * {@code #} (matching classic mode's behavior).
   */
  private static String stringifyScalar(Object value, int stringDepth) {
    if (value instanceof Float) {
      return Codes.floatToString((Float) value);
    }
    if (value instanceof Long) {
      // The only Long-backed primitive type is 'word'; print it in hexadecimal,
      // like classic mode (and Word.toString).
      return "0wx"
          + Long.toUnsignedString((Long) value, 16).toUpperCase(Locale.ROOT);
    }
    if (value instanceof String && stringDepth >= 0) {
      final String s = (String) value;
      if (s.length() > stringDepth) {
        return s.substring(0, stringDepth) + "#";
      }
      return s;
    }
    return value.toString();
  }

  /**
   * Renders the value of a {@code string option} cell so that it cannot be
   * confused with the blank cell used for {@code NONE}. A string is shown as an
   * SML string literal (in double-quotes, with embedded double-quotes and
   * backslashes escaped) if it is empty or contains a double-quote; otherwise
   * it is shown verbatim (subject to {@code stringDepth} truncation).
   */
  private static String optionString(String s, int stringDepth) {
    if (s.isEmpty() || s.indexOf('"') >= 0) {
      return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
    return stringifyScalar(s, stringDepth);
  }

  /**
   * Folds {@code s} into lines no longer than {@code width}, breaking at
   * whitespace where possible and otherwise hard-breaking at the limit. If
   * folding is disabled or {@code s} already fits, returns a singleton list.
   */
  private static List<String> foldString(String s, int width) {
    if (width <= 0 || s.length() <= width) {
      return ImmutableList.of(s);
    }
    final ImmutableList.Builder<String> result = ImmutableList.builder();
    int pos = 0;
    while (s.length() - pos > width) {
      final int end = pos + width;
      // Prefer to break at the last space at or before `end`.
      final int breakAt = s.lastIndexOf(' ', end);
      if (breakAt > pos) {
        result.add(stripTrailing(s.substring(pos, breakAt)));
        pos = breakAt + 1;
        // Skip any additional consecutive spaces at the start of the next
        // line so they don't appear as leading whitespace.
        while (pos < s.length() && s.charAt(pos) == ' ') {
          pos++;
        }
      } else {
        // No suitable break point; hard-break at width.
        result.add(s.substring(pos, end));
        pos = end;
      }
    }
    if (pos < s.length()) {
      result.add(s.substring(pos));
    }
    return result.build();
  }

  private static String stripTrailing(String s) {
    int end = s.length();
    while (end > 0 && s.charAt(end - 1) == ' ') {
      end--;
    }
    return end == s.length() ? s : s.substring(0, end);
  }

  /**
   * Appends a line to {@code buf}, stripping trailing spaces, and adds a
   * newline.
   */
  private static void appendLine(StringBuilder buf, CharSequence line) {
    int end = line.length();
    while (end > 0 && line.charAt(end - 1) == ' ') {
      end--;
    }
    buf.append(line, 0, end).append(NEWLINE);
  }

  private static void padSpaces(StringBuilder buf, int n) {
    for (int i = 0; i < n; i++) {
      buf.append(' ');
    }
  }

  private static <T, U, R> List<R> transformEntries(
      Map<T, U> map,
      BiFunction<? super T, ? super U, ? extends R> transformer) {
    final ImmutableList.Builder<R> builder = ImmutableList.builder();
    map.forEach((k, v) -> builder.add(transformer.apply(k, v)));
    return builder.build();
  }

  /** A node in the column tree describing one tabular field. */
  private static class Section {
    enum Kind {
      SCALAR,
      SCALAR_LIST,
      RECORD_LIST
    }

    /** For a {@link Kind#RECORD_LIST}, how its value maps to a list of rows. */
    enum RecordShape {
      /** Value is already a list of records (a nested collection). */
      LIST,
      /** Value is a single record; render it as a one-row sub-table. */
      SINGLE,
      /**
       * Value is a record {@code option}; {@code SOME} is one row, {@code NONE}
       * is no rows (so its columns are blank).
       */
      OPTION
    }

    final Kind kind;
    final String name;
    final boolean rightAlign;
    /**
     * Whether a SCALAR value is wrapped in {@code option} (so {@code SOME x}
     * prints as {@code x} and {@code NONE} as a blank cell).
     */
    final boolean optional;

    /** For a RECORD_LIST, how its value maps to a list of records. */
    final RecordShape shape;

    final List<Section> children;
    int width;

    private Section(
        Kind kind,
        String name,
        boolean rightAlign,
        boolean optional,
        List<Section> children) {
      this(kind, name, rightAlign, optional, RecordShape.LIST, children);
    }

    private Section(
        Kind kind,
        String name,
        boolean rightAlign,
        boolean optional,
        RecordShape shape,
        List<Section> children) {
      this.kind = kind;
      this.name = name;
      this.rightAlign = rightAlign;
      this.optional = optional;
      this.shape = shape;
      this.children = children;
      this.width = name.length();
    }

    /** Builds a Section tree for a record-like (record or tuple) type. */
    static Section forRecord(String name, RecordLikeType recordType) {
      return forRecord(name, recordType, RecordShape.LIST);
    }

    /** Builds a Section tree for a record-like type with a given shape. */
    static Section forRecord(
        String name, RecordLikeType recordType, RecordShape shape) {
      final List<Section> children =
          transformEntries(recordType.argNameTypes(), Section::forField);
      return new Section(Kind.RECORD_LIST, name, false, false, shape, children);
    }

    /** Builds a Section for one field of a record-like type. */
    static Section forField(String name, Type type) {
      if (type instanceof PrimitiveType) {
        return new Section(
            Kind.SCALAR,
            name,
            isNumeric((PrimitiveType) type),
            false,
            ImmutableList.of());
      }
      final PrimitiveType optionType = optionScalar(type);
      if (optionType != null) {
        return new Section(
            Kind.SCALAR, name, isNumeric(optionType), true, ImmutableList.of());
      }
      // A bare record or tuple field renders as a one-row nested sub-table.
      if (type instanceof RecordType || type instanceof TupleType) {
        return forRecord(name, (RecordLikeType) type, RecordShape.SINGLE);
      }
      // A record/tuple 'option' field renders as a nested sub-table that is
      // blank for 'NONE'.
      final RecordLikeType optionRecord = optionRecord(type);
      if (optionRecord != null) {
        return forRecord(name, optionRecord, RecordShape.OPTION);
      }
      final Type elementType = type.elementType();
      if (elementType instanceof PrimitiveType) {
        return new Section(
            Kind.SCALAR_LIST,
            name,
            isNumeric((PrimitiveType) elementType),
            false,
            ImmutableList.of());
      }
      // Element is a record or tuple: recurse.
      return forRecord(name, (RecordLikeType) elementType);
    }

    private static boolean isNumeric(PrimitiveType type) {
      return type == PrimitiveType.INT
          || type == PrimitiveType.REAL
          || type == PrimitiveType.WORD;
    }

    /**
     * Builds a Cell tree from a value, updating leaf widths as a side effect.
     * For SCALAR_LIST and RECORD_LIST sections, {@code value} is the nested
     * collection (a {@link List}). For SCALAR sections, {@code value} is the
     * primitive value. String values are truncated by {@code stringDepth} (see
     * {@link #stringifyScalar}) and folded by {@code stringFold} (see {@link
     * #foldString}). Collections longer than {@code printLength} are truncated
     * and marked with {@link #ELLIPSIS}.
     */
    @SuppressWarnings("unchecked")
    Cell buildCell(
        Object value, int printLength, int stringDepth, int stringFold) {
      switch (kind) {
        case SCALAR:
          {
            final String s;
            if (optional) {
              // Runtime form: NONE is ["NONE"]; SOME x is ["SOME", x].
              final List<?> option = (List<?>) value;
              if (option.size() == 1) {
                s = ""; // NONE: a blank cell
              } else {
                final Object some = option.get(1);
                // A 'string option' value must be distinguishable from the
                // blank 'NONE' cell, so an empty or quote-containing string is
                // shown as a quoted SML string literal.
                s =
                    some instanceof String
                        ? optionString((String) some, stringDepth)
                        : stringifyScalar(some, stringDepth);
              }
            } else {
              s = stringifyScalar(value, stringDepth);
            }
            final List<String> lines = foldString(s, stringFold);
            for (String line : lines) {
              if (line.length() > width) {
                width = line.length();
              }
            }
            return new ScalarCell(lines);
          }
        case SCALAR_LIST:
          {
            final List<String> items = new ArrayList<>();
            int count = 0;
            for (Object item : (List<?>) value) {
              if (printLength >= 0 && count >= printLength) {
                items.add(ELLIPSIS);
                if (ELLIPSIS.length() > width) {
                  width = ELLIPSIS.length();
                }
                break;
              }
              final String s = stringifyScalar(item, stringDepth);
              for (String line : foldString(s, stringFold)) {
                if (line.length() > width) {
                  width = line.length();
                }
                items.add(line);
              }
              count++;
            }
            return new ScalarListCell(ImmutableList.copyOf(items));
          }
        case RECORD_LIST:
        default:
          {
            // Normalize the value to a list of records according to the shape:
            // a nested collection is already a list; a single record becomes a
            // one-element list; a record option becomes empty (NONE) or a
            // one-element list (SOME).
            final List<List<?>> recordList;
            switch (shape) {
              case SINGLE:
                recordList = ImmutableList.of((List<?>) value);
                break;
              case OPTION:
                final List<?> option = (List<?>) value;
                recordList =
                    option.size() == 1
                        ? ImmutableList.of()
                        : ImmutableList.of((List<?>) option.get(1));
                break;
              case LIST:
              default:
                recordList = (List<List<?>>) value;
            }
            final List<List<Cell>> records = new ArrayList<>();
            boolean truncated = false;
            int count = 0;
            for (List<?> record : recordList) {
              if (printLength >= 0 && count >= printLength) {
                truncated = true;
                break;
              }
              final List<Cell> rowCells = new ArrayList<>();
              for (int i = 0; i < children.size(); i++) {
                rowCells.add(
                    children
                        .get(i)
                        .buildCell(
                            record.get(i),
                            printLength,
                            stringDepth,
                            stringFold));
              }
              records.add(ImmutableList.copyOf(rowCells));
              count++;
            }
            if (truncated && ELLIPSIS.length() > width) {
              width = ELLIPSIS.length();
            }
            return new RecordListCell(ImmutableList.copyOf(records), truncated);
          }
      }
    }

    /**
     * Finalizes widths bottom-up. For a RECORD_LIST section, the width is the
     * sum of child widths plus separators; if the section's name is wider, the
     * last child is grown to compensate.
     */
    void finalizeWidths() {
      if (kind != Kind.RECORD_LIST) {
        return;
      }
      int sum = 0;
      for (Section child : children) {
        child.finalizeWidths();
        sum += child.width;
      }
      if (!children.isEmpty()) {
        sum += children.size() - 1;
      }
      if (name.length() > sum) {
        children.get(children.size() - 1).growWidth(name.length() - sum);
        sum = name.length();
      }
      width = sum;
    }

    /**
     * Grows this section's width by the given amount. For a RECORD_LIST, the
     * extra space is absorbed by its last child (recursively, so the slack
     * eventually lands on a leaf).
     */
    void growWidth(int extra) {
      width += extra;
      if (kind == Kind.RECORD_LIST && !children.isEmpty()) {
        children.get(children.size() - 1).growWidth(extra);
      }
    }

    /** Returns the number of header lines this section contributes. */
    int headerDepth() {
      if (kind != Kind.RECORD_LIST) {
        return 1;
      }
      int max = 0;
      for (Section c : children) {
        max = Math.max(max, c.headerDepth());
      }
      return 1 + max;
    }

    /**
     * Appends this section's header cell at the given line index. The cell is
     * exactly {@code width} characters wide.
     */
    void appendHeaderCell(StringBuilder buf, int line) {
      if (line == 0) {
        // Top of this section's subtree: name left-aligned, padded to width.
        buf.append(name);
        padSpaces(buf, width - name.length());
      } else if (kind == Kind.RECORD_LIST) {
        // Recurse into children at level - 1.
        for (int i = 0; i < children.size(); i++) {
          if (i > 0) {
            buf.append(' ');
          }
          children.get(i).appendHeaderCell(buf, line - 1);
        }
      } else {
        // Leaf with no sub-header: blank padding.
        padSpaces(buf, width);
      }
    }

    /** Appends the dashed separator cells for this section. */
    void appendSeparator(StringBuilder buf) {
      if (kind == Kind.RECORD_LIST) {
        for (int i = 0; i < children.size(); i++) {
          if (i > 0) {
            buf.append(' ');
          }
          children.get(i).appendSeparator(buf);
        }
      } else {
        for (int k = 0; k < width; k++) {
          buf.append('-');
        }
      }
    }

    /** Appends a string padded (or right-aligned) to this section's width. */
    void appendPadded(StringBuilder buf, String s) {
      if (rightAlign) {
        padSpaces(buf, width - s.length());
        buf.append(s);
      } else {
        buf.append(s);
        padSpaces(buf, width - s.length());
      }
    }
  }

  /** Cached row data — mirrors the Section tree. */
  private abstract static class Cell {
    /** Creates an iterator that emits this cell's lines (padded to width). */
    abstract Iterator<String> iter(Section section);
  }

  /**
   * A scalar cell. {@code lines} normally has one element, but folding
   * (controlled by {@code stringFold}) may produce several.
   */
  private static class ScalarCell extends Cell {
    final List<String> lines;

    ScalarCell(List<String> lines) {
      this.lines = lines;
    }

    @Override
    Iterator<String> iter(Section section) {
      return new ScalarListIter(section, lines);
    }
  }

  private static class ScalarListCell extends Cell {
    final List<String> items;

    ScalarListCell(List<String> items) {
      this.items = items;
    }

    @Override
    Iterator<String> iter(Section section) {
      return new ScalarListIter(section, items);
    }
  }

  private static class RecordListCell extends Cell {
    /**
     * Outer list: one entry per nested record; inner list: one Cell per field.
     */
    final List<List<Cell>> records;

    /** True if the records were truncated by {@code printLength}. */
    final boolean truncated;

    RecordListCell(List<List<Cell>> records, boolean truncated) {
      this.records = records;
      this.truncated = truncated;
    }

    @Override
    Iterator<String> iter(Section section) {
      return new RecordListIter(section, records, truncated);
    }
  }

  private static class ScalarListIter implements Iterator<String> {
    private final Section section;
    private final List<String> items;
    private int idx;

    ScalarListIter(Section section, List<String> items) {
      this.section = section;
      this.items = items;
    }

    @Override
    public boolean hasNext() {
      return idx < items.size();
    }

    @Override
    public String next() {
      if (idx >= items.size()) {
        throw new NoSuchElementException();
      }
      final StringBuilder b = new StringBuilder(section.width);
      section.appendPadded(b, items.get(idx++));
      return b.toString();
    }
  }

  /**
   * Joins a list of child iterators side-by-side with single-space separators,
   * padding shorter children with blank space. Guarantees at least one line per
   * record even when all child iterators are empty.
   */
  private static class RowIter extends AbstractIterator<String> {
    private final List<Section> sections;
    private final List<Iterator<String>> children;
    private boolean firstDone;

    RowIter(List<Section> sections, List<Iterator<String>> children) {
      this.sections = sections;
      this.children = children;
    }

    @Override
    protected @Nullable String computeNext() {
      if (firstDone) {
        boolean any = false;
        for (Iterator<String> it : children) {
          if (it.hasNext()) {
            any = true;
            break;
          }
        }
        if (!any) {
          return endOfData();
        }
      }
      firstDone = true;
      final StringBuilder buf = new StringBuilder();
      for (int i = 0; i < children.size(); i++) {
        if (i > 0) {
          buf.append(' ');
        }
        final Section sec = sections.get(i);
        final Iterator<String> it = children.get(i);
        if (it.hasNext()) {
          buf.append(it.next());
        } else {
          padSpaces(buf, sec.width);
        }
      }
      return buf.toString();
    }
  }

  /** Streams lines from a nested collection of records. */
  private static class RecordListIter extends AbstractIterator<String> {
    private final Section section;
    private final List<List<Cell>> records;
    private final boolean truncated;
    private int recordIdx;
    private @Nullable Iterator<String> currentRow;
    private boolean truncationEmitted;

    RecordListIter(
        Section section, List<List<Cell>> records, boolean truncated) {
      this.section = section;
      this.records = records;
      this.truncated = truncated;
    }

    @Override
    protected @Nullable String computeNext() {
      while (true) {
        if (currentRow == null) {
          if (recordIdx >= records.size()) {
            if (truncated && !truncationEmitted) {
              truncationEmitted = true;
              // Emit ellipsis padded to the cell's width so column
              // alignment is preserved.
              final StringBuilder b = new StringBuilder(section.width);
              b.append(ELLIPSIS);
              padSpaces(b, section.width - b.length());
              return b.toString();
            }
            return endOfData();
          }
          final List<Cell> rec = records.get(recordIdx++);
          final List<Iterator<String>> subIters = new ArrayList<>();
          for (int i = 0; i < section.children.size(); i++) {
            subIters.add(rec.get(i).iter(section.children.get(i)));
          }
          currentRow = new RowIter(section.children, subIters);
        }
        if (currentRow.hasNext()) {
          return currentRow.next();
        }
        currentRow = null;
      }
    }
  }
}

// End TabularPrinter.java
