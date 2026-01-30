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

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.checkerframework.checker.nullness.qual.Nullable;

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
 */
public class OutputMatcher {
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
    // Extract value portions
    final String code0 = extractValue(actual);
    final String code1 = extractValue(expected);
    if (code0 == null || code1 == null) {
      return false;
    }

    return codeEqual(type, code0, code1);
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

  /**
   * Extracts the value portion from output like "val x = value : type" or
   * "value : type".
   */
  static @Nullable String extractValue(String s) {
    // Find start of value: after "val <name> = " if present
    int valueStart = 0;
    int eqIdx = indexOfEqWhitespace(s);
    if (eqIdx >= 0 && s.substring(0, eqIdx).contains("val ")) {
      // Skip "=" and any following whitespace (space, newline, etc.)
      int start = eqIdx + 1;
      while (start < s.length() && isWhitespaceChar(s.charAt(start))) {
        start++;
      }
      valueStart = start;
    }

    // Find end of value: before the last top-level " : "
    int depth = 0;
    boolean inString = false;
    int lastColon = -1;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inString) {
        if (c == '"') {
          inString = false;
        } else if (c == '\\') {
          i++;
        }
        continue;
      }
      if (c == '"') {
        inString = true;
      } else if (c == '(' || c == '[' || c == '{') {
        depth++;
      } else if (c == ')' || c == ']' || c == '}') {
        depth--;
      } else if (c == ':'
          && depth == 0
          && i > 0
          && s.charAt(i - 1) == ' '
          && i + 1 < s.length()
          && s.charAt(i + 1) == ' ') {
        lastColon = i;
      }
    }
    if (lastColon < 0) {
      return null;
    }
    return s.substring(valueStart, lastColon - 1);
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
        lastWasSpace = false;
        continue;
      }
      if (c == '"') {
        if (lastWasSpace && buf.length() > 0) {
          buf.append(' ');
        }
        buf.append(c);
        inString = true;
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
    } else if (c == '"') {
      return sc.consumeString();
    } else if (c == '~' || Character.isDigit(c)) {
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

    /**
     * Peeks at the next word without consuming it. Returns null if next token
     * is not a word.
     */
    @Nullable
    String peekWord() {
      skipSpaces();
      if (pos >= s.length() || !isWordChar(s.charAt(pos))) {
        return null;
      }
      int start = pos;
      int end = start;
      while (end < s.length() && isWordChar(s.charAt(end))) {
        end++;
      }
      return s.substring(start, end);
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

    String consumeString() {
      skipSpaces();
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
      return s.substring(start, pos);
    }

    String consumeNumber() {
      skipSpaces();
      int start = pos;
      if (pos < s.length() && s.charAt(pos) == '~') {
        pos++; // negative sign
      }
      while (pos < s.length()
          && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
        pos++;
      }
      // Handle 'E' in real literals
      if (pos < s.length() && (s.charAt(pos) == 'E' || s.charAt(pos) == 'e')) {
        pos++;
        if (pos < s.length() && s.charAt(pos) == '~') {
          pos++;
        }
        while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
          pos++;
        }
      }
      return s.substring(start, pos);
    }

    private void skipSpaces() {
      while (pos < s.length() && s.charAt(pos) == ' ') {
        pos++;
      }
    }
  }
}

// End OutputMatcher.java
