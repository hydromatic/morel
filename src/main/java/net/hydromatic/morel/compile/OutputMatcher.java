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

import static java.lang.Character.isDigit;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.util.Characters.scanNumber;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.hydromatic.morel.parse.Parsers;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * Compares two output strings for semantic equivalence, treating bag values as
 * unordered (multisets).
 *
 * <p>Output strings have the form {@code val name = value : type} or {@code
 * value : type}. The type suffix tells us which brackets represent bags
 * (unordered) vs lists (ordered).
 *
 * <p>Values use Morel's native representation: {@link List} for lists, tuples,
 * bags, and datatypes; records are stored as tuples with field values in the
 * order they occur in the type; a datatype instance is a list of length 1 or 2;
 * atoms are represented as {@link String}.
 *
 * <p>A string value may be written as a raw string literal, {@code {|...|}} or
 * {@code {id|...|id}} where the tag {@code id} consists of lower-case letters
 * {@code a} to {@code z} and underscores, whose content is verbatim (no escape
 * processing, and newlines are real newlines), except that if the tag starts
 * with an underscore, a newline right after the opening fence is not content. A
 * raw literal is equivalent to the regular literal with the same content. Raw
 * literals are a feature of the script format, not of the Morel language;
 * {@link #toRawStrings} writes them.
 */
public class OutputMatcher {
  /**
   * A top-level string value in a statement's output: {@code val name = "..." :
   * string}, the literal and the type possibly wrapped onto following lines.
   */
  private static final Pattern TOP_LEVEL_STRING =
      Pattern.compile(
          "^(val \\S+ =)(\\s+)(\"(?:[^\"\\\\]|\\\\.)*\")\\s+: string$",
          Pattern.MULTILINE);

  /** Separator used when the printer wrapped a value onto the next line. */
  private static final String WRAPPED_INDENT =
      "\n" //
          + "  ";

  private final TypeSystem typeSystem;

  public OutputMatcher(TypeSystem typeSystem) {
    this.typeSystem = requireNonNull(typeSystem);
  }

  /**
   * Returns whether {@code actual} and {@code expected} are semantically
   * equivalent. Bag-typed values are compared as multisets (order-independent).
   *
   * <p>If in doubt, we return false; we cannot afford false-positives.
   */
  public boolean equivalent(Type type, String actual, String expected) {
    final Split split0 = Split.of(actual);
    final Split split1 = Split.of(expected);
    if (split0.val == null || split1.val == null) {
      return false;
    }

    if (split0.type == null || !split0.type.equals(split1.type)) {
      return false;
    }

    // The prefix is everything before the value: the warnings that the
    // statement raised, and the name that the value is bound to. Only its
    // whitespace may differ; a different warning (or a different name) is a
    // different output, not an equivalent one.
    if (!normalizeWhitespace(split0.prefix)
        .equals(normalizeWhitespace(split1.prefix))) {
      return false;
    }

    return codeEqual(type, split0.val, split1.val);
  }

  /** Returns whether two value strings are equivalent. */
  public boolean codeEqual(Type type, String code0, String code1) {
    try {
      Object o0 = parseValue(new Scanner(normalizeWhitespace(code0)), type);
      Object o1 = parseValue(new Scanner(normalizeWhitespace(code1)), type);
      return valuesEqual(type, o0, o1);
    } catch (RuntimeException e) {
      return false;
    }
  }

  static String normalizeWhitespace(String s) {
    StringBuilder buf = new StringBuilder();
    boolean inString = false;
    boolean lastWasSpace = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inString) {
        buf.append(c);
        if (c == '"') {
          inString = false;
        } else if (c == '\\' && i + 1 < s.length()) {
          buf.append(s.charAt(++i));
        }
        continue;
      }
      if (c == '"') {
        if (lastWasSpace && buf.length() > 0) {
          buf.append(' ');
        }
        buf.append(c);
        inString = true;
        lastWasSpace = false;
      } else if (c == '{' && rawFenceLength(s, i) > 0) {
        // Copy a raw string literal verbatim, newlines included. (If the
        // literal is not closed, rawEnd is past the end; copy to the end.)
        final int end = Math.min(rawEnd(s, i), s.length());
        if (lastWasSpace && buf.length() > 0) {
          buf.append(' ');
        }
        buf.append(s, i, end);
        i = end - 1;
        lastWasSpace = false;
      } else if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
        lastWasSpace = true;
      } else {
        if (lastWasSpace && buf.length() > 0 && needsSpace(buf, c)) {
          buf.append(' ');
        }
        buf.append(c);
        lastWasSpace = false;
      }
    }
    return buf.toString();
  }

  /**
   * Determines whether a space is needed between the last char in buf and the
   * next char c. Spaces are needed between alphanumeric tokens but not around
   * brackets and punctuation.
   */
  private static boolean needsSpace(StringBuilder buf, char c) {
    char prev = buf.charAt(buf.length() - 1);
    // Space needed between two word chars, or after '=' before value,
    // or before/after certain keywords
    if (isWordChar(prev) && isWordChar(c)) {
      return true;
    }
    if (prev == '=' && c != '{' && c != '[' && c != '(' && c != ')') {
      return true;
    }
    return c == '=' && prev != '>' && prev != '<' && prev != '!';
  }

  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '\'' || c == '~';
  }

  // --- Value parser ---

  /**
   * Parses a value from whitespace-normalized text, guided by the expected
   * type. Returns a String for atoms, or a {@link List} for compound values.
   */
  Object parseValue(Scanner sc, Type type) {
    // Handle grouping parentheses around non-tuple values,
    // e.g. SOME ([1,2]) where the argument is a bag wrapped in parens.
    if (sc.peek() == '(' && !(type instanceof TupleType)) {
      sc.consume("(");
      Object value = parseValue(sc, type);
      sc.consume(")");
      return value;
    }
    if (type instanceof DataType && type.isCollection()) {
      // Bag type: parse as list
      return parseListElements(sc, type.elementType());
    } else if (type instanceof ListType) {
      return parseListElements(sc, type.elementType());
    } else if (type instanceof TupleType) {
      return parseTupleElements(sc, (TupleType) type);
    } else if (type instanceof RecordType) {
      return parseRecordToTuple(sc, (RecordType) type);
    } else if (type instanceof DataType) {
      return parseDatatypeValue(sc, (DataType) type);
    } else {
      return parseAtom(sc);
    }
  }

  /** Parses {@code [e1, e2, ...]} into a list. */
  private List<Object> parseListElements(Scanner sc, Type elemType) {
    sc.consume("[");
    List<Object> elements = new ArrayList<>();
    if (sc.peek() != ']') {
      for (; ; ) {
        elements.add(parseValue(sc, elemType));
        if (sc.peek() != ',') {
          break;
        }
        sc.consume(",");
      }
    }
    sc.consume("]");
    return elements;
  }

  /** Parses {@code (e1, e2, ...)} into a list. */
  private List<Object> parseTupleElements(Scanner sc, TupleType type) {
    sc.consume("(");
    if (sc.peek() == ')') {
      sc.consume(")");
      return Collections.emptyList();
    }
    final List<Object> fields = new ArrayList<>();
    for (int i = 0; i < type.argTypes.size(); i++) {
      if (i > 0) {
        sc.consume(",");
      }
      fields.add(parseValue(sc, type.argTypes.get(i)));
    }
    sc.consume(")");
    return fields;
  }

  /**
   * Parses {@code {f1=v1, f2=v2, ...}} into a list with values in the type's
   * field order.
   */
  private List<Object> parseRecordToTuple(Scanner sc, RecordType type) {
    sc.consume("{");
    // Parse fields into a map
    final Map<String, Object> fieldMap = new LinkedHashMap<>();
    final SortedMap<String, Type> argNameTypes = type.argNameTypes();
    if (sc.peek() != '}') {
      for (; ; ) {
        final String name = sc.consumeWord();
        sc.consume("=");
        final Type fieldType = argNameTypes.get(name);
        if (fieldType == null) {
          // Unknown field; parse as atom
          fieldMap.put(name, parseAtom(sc));
        } else {
          fieldMap.put(name, parseValue(sc, fieldType));
        }
        if (sc.peek() != ',') {
          break;
        }
        sc.consume(",");
      }
    }
    sc.consume("}");

    // Produce values in type's field order.
    ImmutableList.Builder<Object> values = ImmutableList.builder();
    for (String fieldName : argNameTypes.keySet()) {
      final Object v = fieldMap.get(fieldName);
      if (v == null) {
        throw new IllegalStateException("missing field: " + fieldName);
      }
      values.add(v);
    }
    return values.build();
  }

  /**
   * Parses a datatype value: a constructor name optionally followed by an
   * argument. Returns a list of length 1 (nullary) or 2 (with argument).
   */
  private List<Object> parseDatatypeValue(Scanner sc, DataType type) {
    final String constructor = sc.consumeWord();
    if (!sc.hasMore()
        || sc.peek() == ','
        || sc.peek() == ')'
        || sc.peek() == ']'
        || sc.peek() == '}') {
      return ImmutableList.of(constructor);
    }
    // Has an argument; look up the argument type from the datatype
    Map<String, Type> constructors = type.typeConstructors(typeSystem);
    Type argType = constructors.get(constructor);
    if (argType == null) {
      // Unknown constructor; parse argument as atom
      return ImmutableList.of(constructor, parseAtom(sc));
    }
    return ImmutableList.of(constructor, parseValue(sc, argType));
  }

  /** Parses a single atom: a string, char literal, number, or word. */
  private static String parseAtom(Scanner sc) {
    char c = sc.peek();
    if (c == '#') {
      sc.consume("#");
      return "#" + sc.consumeString();
    } else if (c == '"' || c == '{' && sc.atRawFence()) {
      return sc.consumeString();
    } else if (c == '~' || isDigit(c)) {
      return sc.consumeNumber();
    } else if (c == '(' && sc.peekAt(1) == ')') {
      sc.consume("(");
      sc.consume(")");
      return "()";
    } else {
      return sc.consumeWord();
    }
  }

  // --- Value comparison ---

  /**
   * Compares two parsed values for equivalence, treating bag-typed collections
   * as unordered. For types with no bags, {@link Object#equals} suffices.
   */
  boolean valuesEqual(Type type, Object o0, Object o1) {
    if (type instanceof DataType && type.isCollection()) {
      return bagEqual(type.elementType(), o0, o1);
    } else if (type instanceof ListType) {
      return listEqual(type.elementType(), o0, o1);
    } else if (type instanceof TupleType) {
      return tupleEqual(((TupleType) type).argTypes(), o0, o1);
    } else if (type instanceof RecordType) {
      return tupleEqual(((RecordType) type).argTypes(), o0, o1);
    } else if (type instanceof DataType) {
      return datatypeEqual((DataType) type, o0, o1);
    } else {
      return o0.equals(o1);
    }
  }

  /** Compares two lists element-wise with the same element type. */
  private boolean listEqual(Type elemType, Object actual, Object expected) {
    if (!(actual instanceof List) || !(expected instanceof List)) {
      return actual.equals(expected);
    }
    @SuppressWarnings("unchecked")
    List<Object> list0 = (List<Object>) actual;
    @SuppressWarnings("unchecked")
    List<Object> list1 = (List<Object>) expected;
    if (list0.size() != list1.size()) {
      return false;
    }
    for (int i = 0; i < list0.size(); i++) {
      if (!valuesEqual(elemType, list0.get(i), list1.get(i))) {
        return false;
      }
    }
    return true;
  }

  /** Compares two tuples/records element-wise with per-field types. */
  private boolean tupleEqual(
      List<Type> fieldTypes, Object actual, Object expected) {
    if (!(actual instanceof List) || !(expected instanceof List)) {
      return actual.equals(expected);
    }
    @SuppressWarnings("unchecked")
    List<Object> list0 = (List<Object>) actual;
    @SuppressWarnings("unchecked")
    List<Object> list1 = (List<Object>) expected;
    if (list0.size() != list1.size() || list0.size() != fieldTypes.size()) {
      return false;
    }
    for (int i = 0; i < list0.size(); i++) {
      if (!valuesEqual(fieldTypes.get(i), list0.get(i), list1.get(i))) {
        return false;
      }
    }
    return true;
  }

  /** Compares two datatype values (lists of length 1 or 2). */
  @SuppressWarnings("unchecked")
  private boolean datatypeEqual(DataType dataType, Object o0, Object o1) {
    if (o0 instanceof List && o1 instanceof List) {
      List<Object> list0 = (List<Object>) o0;
      List<Object> list1 = (List<Object>) o1;
      if (list0.size() == list1.size()) {
        if (list0.get(0).equals(list1.get(0))) {
          if (list0.size() == 1) {
            return true;
          }
          final String constructor = (String) list0.get(0);
          final Type argType =
              dataType.typeConstructors(typeSystem).get(constructor);
          return argType != null
              && valuesEqual(argType, list0.get(1), list1.get(1));
        }
      }
    }
    return false;
  }

  /**
   * Compares two lists as multi-sets: every element in {@code list0} must match
   * exactly one element in {@code list1} (using bag-aware equality).
   */
  @SuppressWarnings("unchecked")
  private boolean bagEqual(Type elemType, Object o0, Object o1) {
    final List<Object> list0 = (List<Object>) o0;
    final List<Object> list1 = (List<Object>) o1;
    if (list0.size() == list1.size()) {
      final List<Object> remaining = new ArrayList<>(list1);
      for (Object a : list0) {
        final int j = indexOf(elemType, a, remaining);
        if (j < 0) {
          return false;
        }
        remaining.remove(j);
      }
      return true;
    }
    return false;
  }

  private int indexOf(Type type, Object o, List<Object> list) {
    for (int j = 0; j < list.size(); j++) {
      if (valuesEqual(type, o, list.get(j))) {
        return j;
      }
    }
    return -1;
  }

  // --- Raw string literals ---

  /**
   * Returns the length of the opening fence of a raw string literal starting at
   * {@code pos}: 2 for "{|", or 2 + n for "{id|" where the tag has n
   * characters, each a lower-case letter or underscore; or 0 if there is no raw
   * literal there.
   */
  static int rawFenceLength(String s, int pos) {
    if (pos >= s.length() || s.charAt(pos) != '{') {
      return 0;
    }
    int i = pos + 1;
    while (i < s.length() && isTagChar(s.charAt(i))) {
      i++;
    }
    if (i < s.length() && s.charAt(i) == '|') {
      return i + 1 - pos;
    }
    return 0;
  }

  /**
   * Returns whether a character may appear in a raw literal's tag: a lower-case
   * letter {@code a} to {@code z}, or an underscore.
   */
  static boolean isTagChar(char c) {
    return c >= 'a' && c <= 'z' || c == '_';
  }

  /**
   * Returns the position just after the closing fence of the raw string literal
   * that starts at {@code pos}; if the literal is not closed, returns a
   * position beyond the end of the string.
   */
  static int rawEnd(String s, int pos) {
    final int fence = rawFenceLength(s, pos);
    final String closing = "|" + s.substring(pos + 1, pos + fence - 1) + "}";
    final int i = s.indexOf(closing, pos + fence);
    return i < 0 ? s.length() + 1 : i + closing.length();
  }

  /**
   * Rewrites the output of a statement so that each top-level string value that
   * contains a newline, and has no space or tab before a newline, is a raw
   * string literal.
   *
   * <p>A top-level string value is a line {@code val name = "..." : string},
   * the literal and the type possibly wrapped onto following lines. It is
   * replaced by {@code val name = {|...|} : string}, the content verbatim, its
   * lines after the first starting at column 0, and the type following the
   * closing fence. If the printer had wrapped the literal onto the line after
   * {@code val name =}, the raw literal starts there too, indented by two
   * spaces. A trailing newline in the content leaves the closing fence alone on
   * the last line. If the content contains "|}", the fences carry the shortest
   * identifier that does not occur in it. See {@link #rawLiteral} for the "{_|"
   * form, whose content starts on the line after the opening fence.
   *
   * <p>Strings without a newline, strings with trailing whitespace on a line,
   * and strings inside collections and records, are unchanged.
   */
  public static String toRawStrings(String output) {
    final Matcher m = TOP_LEVEL_STRING.matcher(output);
    StringBuilder b = null;
    int last = 0;
    while (m.find()) {
      final String content = Parsers.unquoteString(m.group(3));
      if (!wantsRaw(content)) {
        continue;
      }
      if (b == null) {
        b = new StringBuilder();
      }
      b.append(output, last, m.start())
          .append(m.group(1))
          // Keep the printer's layout: if it wrapped the value onto the next
          // line, the raw literal starts on the next line too.
          .append(m.group(2).indexOf('\n') < 0 ? " " : WRAPPED_INDENT)
          .append(rawLiteral(content))
          .append(" : string");
      last = m.end();
    }
    if (b == null) {
      return output;
    }
    return b.append(output, last, output.length()).toString();
  }

  /**
   * Returns whether a string is written as a raw literal: it contains a
   * newline; every other character is printable ASCII (so no tab, carriage
   * return, control character or non-ASCII character, which would be invisible
   * or fragile in the script, and a tab would fail the linter); and no line
   * ends with a space (which is invisible, and easily lost by editors).
   */
  static boolean wantsRaw(String content) {
    boolean newline = false;
    for (int i = 0; i < content.length(); i++) {
      final char c = content.charAt(i);
      if (c == '\n') {
        newline = true;
        if (i > 0 && content.charAt(i - 1) == ' ') {
          return false;
        }
      } else if (c < ' ' || c > '~') {
        return false;
      }
    }
    return newline;
  }

  /**
   * Writes a string as a raw literal whose fences do not occur in it.
   *
   * <p>If the content's second line starts with a space, the content starts on
   * the line after the opening fence, so that its lines line up in the script;
   * the tag then starts with "_", which tells the reader to discard the newline
   * after the fence: the literal reads "{_|", a newline, the content, "|_}".
   * Otherwise the content starts right after the opening fence, "{|", and every
   * newline in the literal is content.
   */
  public static String rawLiteral(String content) {
    final boolean nextLine = startsOnNextLine(content);
    final String prefix = nextLine ? "_" : "";
    String tag = prefix;
    for (int i = 1; content.contains("|" + tag + "}"); i++) {
      tag = prefix + identifier(i);
    }
    return "{" + tag + "|" + (nextLine ? "\n" : "") + content + "|" + tag + "}";
  }

  /**
   * Returns whether a raw literal's content starts on the line after the
   * opening fence: when its second line starts with a space, so that the lines
   * line up in the script.
   */
  static boolean startsOnNextLine(String content) {
    final int i = content.indexOf('\n');
    return i > 0 && i + 1 < content.length() && content.charAt(i + 1) == ' ';
  }

  /** Returns the i-th identifier in the sequence a, b, ..., z, aa, ab, ... */
  private static String identifier(int i) {
    final StringBuilder b = new StringBuilder();
    for (; i > 0; i = (i - 1) / 26) {
      b.append((char) ('a' + (i - 1) % 26));
    }
    return b.reverse().toString();
  }

  // --- Scanner ---

  /** Simple scanner over whitespace-normalized text. */
  static class Scanner {
    private final String s;
    private int pos;

    Scanner(String s) {
      this.s = s;
      this.pos = 0;
    }

    boolean hasMore() {
      skipSpaces();
      return pos < s.length();
    }

    char peek() {
      skipSpaces();
      return pos < s.length() ? s.charAt(pos) : 0;
    }

    /**
     * Peeks at the character at offset {@code offset} from current position,
     * without skipping spaces. Returns 0 if out of bounds.
     */
    char peekAt(int offset) {
      skipSpaces();
      int i = pos + offset;
      return i < s.length() ? s.charAt(i) : 0;
    }

    void consume(String expected) {
      skipSpaces();
      if (!s.startsWith(expected, pos)) {
        throw new IllegalStateException(
            "expected '" + expected + "' at pos " + pos + " in: " + s);
      }
      pos += expected.length();
    }

    String consumeWord() {
      skipSpaces();
      int start = pos;
      while (pos < s.length() && isWordChar(s.charAt(pos))) {
        pos++;
      }
      if (pos == start) {
        throw new IllegalStateException(
            "expected word at pos " + pos + " in: " + s);
      }
      return s.substring(start, pos);
    }

    boolean atRawFence() {
      skipSpaces();
      return rawFenceLength(s, pos) > 0;
    }

    /**
     * Consumes a string literal, regular or raw, and returns its content in a
     * canonical form: a double-quote followed by the unescaped content. Thus a
     * regular literal and a raw literal with the same content are equal, and no
     * string is equal to a word or a number.
     */
    String consumeString() {
      skipSpaces();
      final int fence = rawFenceLength(s, pos);
      if (fence > 0) {
        final int end = rawEnd(s, pos);
        if (end > s.length()) {
          throw new IllegalStateException(
              "unterminated raw string at pos " + pos + " in: " + s);
        }
        int start = pos + fence;
        if (fence > 2
            && s.charAt(pos + 1) == '_'
            && start < end - fence
            && s.charAt(start) == '\n') {
          // The tag starts with "_": the content starts on the next line, and
          // the newline right after the opening fence is not content.
          start++;
        }
        final String content = s.substring(start, end - fence);
        pos = end;
        return '"' + content;
      }
      if (s.charAt(pos) != '"') {
        throw new IllegalStateException(
            "expected '\"' at pos " + pos + " in: " + s);
      }
      int start = pos;
      pos++; // skip opening "
      while (pos < s.length() && s.charAt(pos) != '"') {
        if (s.charAt(pos) == '\\') {
          pos++; // skip escape
        }
        pos++;
      }
      pos++; // skip closing "
      return '"' + Parsers.unquoteString(s.substring(start, pos));
    }

    String consumeNumber() {
      skipSpaces();
      final int start = pos;
      pos = scanNumber(s, pos, s.length(), true);
      // Word literal: canonicalize "0w255" / "0wxFF" to "0w" + unsigned
      // decimal, so that different representations of the same value match but
      // different values do not.
      if (start + 1 < pos
          && s.charAt(start) == '0'
          && s.charAt(start + 1) == 'w') {
        int digitsStart = start + 2;
        int radix = 10;
        if (digitsStart < pos
            && (s.charAt(digitsStart) == 'x' || s.charAt(digitsStart) == 'X')) {
          radix = 16;
          digitsStart++;
        }
        final String digits = s.substring(digitsStart, pos);
        return "0w"
            + Long.toUnsignedString(Long.parseUnsignedLong(digits, radix));
      }
      return s.substring(start, pos);
    }

    private void skipSpaces() {
      while (pos < s.length() && s.charAt(pos) == ' ') {
        pos++;
      }
    }
  }

  /** An output line split into sections. */
  private static class Split {
    final String prefix;
    final @Nullable String val;
    final @Nullable String type;

    private Split(String prefix, @Nullable String val, @Nullable String type) {
      this.prefix = prefix;
      this.val = val;
      this.type = type;
    }

    /**
     * Splits an output line. Given "val x = value : type", returns ["val x = ",
     * "value", "type"].
     */
    static Split of(String s) {
      // Start of value: after "val <name> = " if present
      final int valueStart = valueStart(s);

      // Find end of value: before the last top-level " : "
      int lastColon = lastColon(s);
      String prefix = s.substring(0, valueStart);
      if (lastColon < 0) {
        return new Split(prefix, null, null);
      } else {
        String val = s.substring(valueStart, lastColon - 1);
        String type = s.substring(lastColon + 1);
        return new Split(prefix, val, type);
      }
    }

    private static int valueStart(String s) {
      final int eq = indexOfEqWhitespace(s);
      if (eq < 0 || !s.substring(0, eq).contains("val ")) {
        return 0;
      }
      // Skip "=" and any following whitespace (space, newline, etc.)
      int start = eq + 1;
      while (start < s.length() && isWhitespaceChar(s.charAt(start))) {
        start++;
      }
      return start;
    }

    private static int lastColon(String s) {
      int depth = 0;
      boolean inString = false;
      int lastColon = -1;
      for (int i = 0; i < s.length(); i++) {
        final char c = s.charAt(i);
        if (inString) {
          if (c == '"') {
            inString = false;
          } else if (c == '\\') {
            i++;
          }
        } else {
          switch (c) {
            case '"':
              inString = true;
              break;
            case '{':
              if (rawFenceLength(s, i) > 0) {
                i = rawEnd(s, i) - 1;
                break;
              }
              depth++;
              break;
            case '(':
            case '[':
              depth++;
              break;
            case ')':
            case ']':
            case '}':
              depth--;
              break;
            case ':':
              if (depth == 0
                  && i > 0
                  && s.charAt(i - 1) == ' '
                  && i + 1 < s.length()
                  && s.charAt(i + 1) == ' ') {
                lastColon = i;
              }
              break;
          }
        }
      }
      return lastColon;
    }

    private static boolean isWhitespaceChar(char c) {
      return c == ' ' || c == '\n' || c == '\r' || c == '\t';
    }

    /** Finds the first '=' followed by whitespace (space or newline). */
    private static int indexOfEqWhitespace(String s) {
      for (int i = 0; i < s.length() - 1; i++) {
        if (s.charAt(i) == '=' && isWhitespaceChar(s.charAt(i + 1))) {
          return i;
        }
      }
      return -1;
    }
  }
}

// End OutputMatcher.java
