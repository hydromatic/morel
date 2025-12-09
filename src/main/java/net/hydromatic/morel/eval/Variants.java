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
package net.hydromatic.morel.eval;

import static java.lang.String.format;

import com.google.common.collect.ImmutableList;
import java.util.List;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.PairList;

/**
 * Utilities for the Variant structure - universal value representation for
 * embedded language interoperability.
 *
 * <p>Provides parsing and printing functions for the {@code variant} datatype
 * defined in {@code variant.sig}.
 */
public class Variants {
  private Variants() {}

  /**
   * Creates a {@code Variant} instance from a constructor name and argument.
   *
   * <p>Used by variant constructors (e.g., {@code INT 42}) to create {@code
   * Variant} instances at runtime.
   *
   * @param name Constructor name (e.g., "INT", "BOOL", "LIST")
   * @param arg Constructor argument
   * @param typeSystem Type system for looking up types
   * @return Value instance with appropriate type
   */
  @SuppressWarnings("unchecked")
  public static Variant fromConstructor(
      String name, Object arg, TypeSystem typeSystem) {
    switch (name) {
      case "UNIT":
        return Variant.unit();
      case "BOOL":
        return Variant.ofBool((Boolean) arg);
      case "INT":
        return Variant.ofInt((Integer) arg);
      case "REAL":
        return Variant.ofReal((Float) arg);
      case "CHAR":
        return Variant.ofChar((Character) arg);
      case "STRING":
        return Variant.ofString((String) arg);
      case "LIST":
        return Variant.ofVariantList(typeSystem, (List<Variant>) arg);
      case "BAG":
        return Variant.ofVariantBag(typeSystem, (List<Variant>) arg);
      case "VECTOR":
        return Variant.ofVariantVector(typeSystem, (List<Variant>) arg);
      case "VARIANT_NONE":
        {
          final Type variantType = typeSystem.lookup(BuiltIn.Datatype.VARIANT);
          return Variant.ofNone(typeSystem, variantType);
        }
      case "VARIANT_SOME":
        return Variant.ofSome(typeSystem, (Variant) arg);
      case "RECORD":
        // arg is a list of (name, value) pairs
        PairList<String, Variant> nameValues =
            PairList.fromTransformed(
                (List<List<Object>>) arg,
                (lists, consumer) ->
                    consumer.accept(
                        (String) lists.get(0), (Variant) lists.get(1)));
        return Variant.ofRecord(typeSystem, nameValues);
      case "CONSTANT":
        // Nullary datatype constructor
        return Variant.ofConstant(typeSystem, (String) arg);
      case "CONSTRUCT":
        // Unary datatype constructor with value
        final List<Object> list = (List<Object>) arg;
        final String conName = (String) list.get(0);
        final Variant conArg = (Variant) list.get(1);
        return Variant.ofConstructor(typeSystem, conName, conArg);
      default:
        throw new IllegalArgumentException(
            String.format("Unknown variant constructor: %s", name));
    }
  }

  /**
   * Creates a Variant by parsing a string.
   *
   * @see net.hydromatic.morel.compile.BuiltIn#VARIANT_PARSE
   */
  public static Variant parse(String s, TypeSystem typeSystem) {
    final Parser parser = new Parser(s, typeSystem);
    return parser.parse();
  }

  /** Helper class for parsing value representations. */
  private static class Parser {
    private final String input;
    private final TypeSystem typeSystem;
    private int pos;

    Parser(String input, TypeSystem typeSystem) {
      this.input = input;
      this.typeSystem = typeSystem;
      this.pos = 0;
    }

    Variant parse() {
      skipWhitespace();
      return parseValue();
    }

    private Variant parseValue() {
      skipWhitespace();
      if (pos >= input.length()) {
        throw new IllegalArgumentException("Unexpected end of input");
      }

      final char c = input.charAt(pos);
      switch (c) {
        case '(':
          return parseUnit();
        case 't':
        case 'f':
          return parseBool();
        case '"':
          return parseString();
        case '#':
          // Check if it's a VECTOR (#[...]) or CHAR (#"...")
          if (pos + 1 < input.length() && input.charAt(pos + 1) == '[') {
            return parseVector();
          } else {
            return parseChar();
          }
        case '[':
          return parseList();
        case '{':
          // Braces are always RECORD (BAG uses 'bag [...]' format)
          return parseRecord();
        case '~':
        case '0':
        case '1':
        case '2':
        case '3':
        case '4':
        case '5':
        case '6':
        case '7':
        case '8':
        case '9':
          return parseNumber();
        default:
          // Check if it's an identifier (bag, CONST, CON, VARIANT_NONE,
          // VARIANT_SOME)
          if (Character.isLetter(c)) {
            return parseIdentifierValue();
          }
          throw new IllegalArgumentException(
              "Unexpected character at position " + pos + ": " + c);
      }
    }

    private Variant parseUnit() {
      expect("()");
      return (Variant) Codes.VARIANT_UNIT;
    }

    private Variant parseBool() {
      if (tryConsume("true")) {
        return Variant.ofBool(true);
      } else if (tryConsume("false")) {
        return Variant.ofBool(false);
      } else {
        throw new IllegalArgumentException(
            "Expected 'true' or 'false' at position " + pos);
      }
    }

    private Variant parseNumber() {
      // Call parseReal and parseInt in succession to try both
      final String remaining = input.substring(pos);

      // First, parse as a real. With strict=true, will return null if the value
      // looks like an int.
      final Ord<Float> realOrd = Codes.parseReal(remaining, true);
      if (realOrd != null) {
        pos += realOrd.i;
        return Variant.ofReal(realOrd.e);
      }

      // Next, parse as an int.
      final Ord<Integer> intOrd = Codes.parseInt(remaining);
      if (intOrd != null) {
        pos += intOrd.i;
        return Variant.ofInt(intOrd.e);
      }

      throw new IllegalArgumentException("Expected number at position " + pos);
    }

    private Variant parseString() {
      expect("\"");
      final StringBuilder sb = new StringBuilder();
      while (pos < input.length() && input.charAt(pos) != '"') {
        if (input.charAt(pos) == '\\') {
          pos++;
          if (pos >= input.length()) {
            throw new IllegalArgumentException("Incomplete escape sequence");
          }
          final char c = input.charAt(pos);
          switch (c) {
            case 'n':
              sb.append('\n');
              break;
            case 't':
              sb.append('\t');
              break;
            case 'r':
              sb.append('\r');
              break;
            case '\\':
              sb.append('\\');
              break;
            case '"':
              sb.append('"');
              break;
            default:
              sb.append(c);
              break;
          }
        } else {
          sb.append(input.charAt(pos));
        }
        pos++;
      }
      expect("\"");
      return Variant.ofString(sb.toString());
    }

    private Variant parseChar() {
      expect("#\"");
      if (pos >= input.length()) {
        throw new IllegalArgumentException("Incomplete character literal");
      }
      char c;
      if (input.charAt(pos) == '\\') {
        pos++;
        if (pos >= input.length()) {
          throw new IllegalArgumentException("Incomplete escape sequence");
        }
        final char escapeChar = input.charAt(pos);
        switch (escapeChar) {
          case 'n':
            c = '\n';
            break;
          case 't':
            c = '\t';
            break;
          case 'r':
            c = '\r';
            break;
          case '\\':
            c = '\\';
            break;
          case '"':
            c = '"';
            break;
          default:
            c = escapeChar;
            break;
        }
      } else {
        c = input.charAt(pos);
      }
      pos++;
      expect("\"");
      return Variant.ofChar(c);
    }

    private Variant parseList() {
      expect("[");
      skipWhitespace();
      if (tryConsume("]")) {
        // Empty list of values
        final Type variantType = typeSystem.lookup(BuiltIn.Datatype.VARIANT);
        return Variant.ofList(typeSystem, variantType, ImmutableList.of());
      }

      final ImmutableList.Builder<Variant> values = ImmutableList.builder();
      do {
        values.add(parseValue());
        skipWhitespace();
      } while (tryConsume(","));
      expect("]");
      return Variant.ofVariantList(typeSystem, values.build());
    }

    private Variant parseBag() {
      expect("[");
      skipWhitespace();
      if (tryConsume("]")) {
        // Empty bag of values
        final Type variantType = typeSystem.lookup(BuiltIn.Datatype.VARIANT);
        return Variant.ofBag(typeSystem, variantType, ImmutableList.of());
      }

      final ImmutableList.Builder<Variant> values = ImmutableList.builder();
      do {
        values.add(parseValue());
        skipWhitespace();
      } while (tryConsume(","));
      expect("]");
      return Variant.ofVariantBag(typeSystem, values.build());
    }

    private Variant parseVector() {
      expect("[");
      skipWhitespace();
      if (tryConsume("]")) {
        // Empty vector of values
        final Type variantType = typeSystem.lookup(BuiltIn.Datatype.VARIANT);
        return Variant.ofVector(typeSystem, variantType, ImmutableList.of());
      }

      final ImmutableList.Builder<Variant> values = ImmutableList.builder();
      do {
        values.add(parseValue());
        skipWhitespace();
      } while (tryConsume(","));
      expect("]");
      return Variant.ofVariantVector(typeSystem, values.build());
    }

    private Variant parseRecord() {
      expect("{");
      skipWhitespace();
      if (tryConsume("}")) {
        // Empty record.
        return Variant.of(PrimitiveType.UNIT, ImmutableList.of());
      }

      final ImmutableList.Builder<String> fieldNames = ImmutableList.builder();
      final ImmutableList.Builder<Variant> fieldValues =
          ImmutableList.builder();

      // Parse first field
      do {
        skipWhitespace();
        final String name = parseIdentifier();
        skipWhitespace();
        expect("=");
        skipWhitespace();
        final Variant value = parseValue();
        fieldNames.add(name);
        fieldValues.add(value);
        skipWhitespace();
      } while (tryConsume(","));
      expect("}");

      // TODO: construct proper RecordType from field names and types
      // For now, use a temporary representation
      return Variant.of(PrimitiveType.UNIT, fieldValues.build());
    }

    private Variant parseIdentifierValue() {
      // Handle variant constructors: UNIT, BOOL, INT, REAL, CHAR, STRING, LIST,
      // BAG, VECTOR, etc.
      if (tryConsume("UNIT")) {
        return Variant.unit();
      }
      if (tryConsume("BOOL")) {
        skipWhitespace();
        if (tryConsume("true")) {
          return Variant.ofBool(true);
        } else if (tryConsume("false")) {
          return Variant.ofBool(false);
        }
        throw new IllegalArgumentException(
            "Expected 'true' or 'false' after BOOL");
      }
      if (tryConsume("INT")) {
        skipWhitespace();
        final Variant numValue = parseNumber();
        return Variant.ofInt((Integer) numValue.value);
      }
      if (tryConsume("REAL")) {
        skipWhitespace();
        final Variant numValue = parseNumber();
        return Variant.ofReal((Float) numValue.value);
      }
      if (tryConsume("CHAR")) {
        skipWhitespace();
        final Variant charValue = parseChar();
        return Variant.ofChar((Character) charValue.value);
      }
      if (tryConsume("STRING")) {
        skipWhitespace();
        final Variant strValue = parseString();
        return Variant.ofString((String) strValue.value);
      }
      if (tryConsume("LIST")) {
        skipWhitespace();
        return parseList(); // parseList already returns a list of Values
      }
      if (tryConsume("BAG")) {
        skipWhitespace();
        return parseBag(); // parseBag already returns a bag of Values
      }
      if (tryConsume("VECTOR")) {
        skipWhitespace();
        return parseVector(); // parseVector already returns a vector of Values
      }
      if (tryConsume("RECORD")) {
        skipWhitespace();
        expect("[");
        skipWhitespace();
        if (tryConsume("]")) {
          // Empty record
          return Variant.ofRecord(typeSystem, PairList.of());
        }
        final PairList<String, Variant> pairs = PairList.of();
        do {
          skipWhitespace();
          expect("(");
          skipWhitespace();
          expect("\"");
          final String name1 = parseStringContent();
          expect("\"");
          skipWhitespace();
          expect(",");
          skipWhitespace();
          final Variant value1 = parseValue();
          skipWhitespace();
          expect(")");
          pairs.add(name1, value1);
          skipWhitespace();
        } while (tryConsume(","));
        expect("]");
        return Variant.ofRecord(typeSystem, pairs);
      }
      // Check for 'bag' prefix (old format)
      if (tryConsume("bag")) {
        skipWhitespace();
        expect("[");
        skipWhitespace();
        if (tryConsume("]")) {
          // Empty bag of values
          final Type variantType = typeSystem.lookup(BuiltIn.Datatype.VARIANT);
          return Variant.ofBag(typeSystem, variantType, ImmutableList.of());
        }
        final ImmutableList.Builder<Variant> values = ImmutableList.builder();
        do {
          values.add(parseValue());
          skipWhitespace();
        } while (tryConsume(","));
        expect("]");
        final Type variantType = typeSystem.lookup(BuiltIn.Datatype.VARIANT);
        return Variant.ofBag(typeSystem, variantType, values.build());
      }
      // Check for CONSTRUCT format, e.g. CONSTRUCT ("INL", INT 5)
      if (tryConsume("CONSTRUCT")) {
        skipWhitespace();
        expect("(");
        skipWhitespace();
        expect("\"");
        final String conName = parseStringContent();
        expect("\"");
        skipWhitespace();
        expect(",");
        skipWhitespace();
        final Variant conValue = parseValue();
        skipWhitespace();
        expect(")");
        return fromConstructor(conName, conValue, typeSystem);
      }
      // Check for CONSTANT, e.g. CONSTANT "LESS"
      if (tryConsume("CONSTANT")) {
        skipWhitespace();
        expect("\"");
        final String conName = parseStringContent();
        expect("\"");
        return fromConstructor("CONSTANT", conName, typeSystem);
      }
      // Try VARIANT_NONE and VARIANT_SOME constructors
      if (tryConsume("VARIANT_NONE")) {
        final Type variantType = typeSystem.lookup(BuiltIn.Datatype.VARIANT);
        return Variant.ofNone(typeSystem, variantType);
      } else if (tryConsume("VARIANT_SOME")) {
        skipWhitespace();
        final Variant value = parseValue();
        return Variant.ofSome(typeSystem, value);
      }
      throw new IllegalArgumentException(
          "Unknown identifier at position " + pos);
    }

    private String parseStringContent() {
      final StringBuilder sb = new StringBuilder();
      while (pos < input.length() && input.charAt(pos) != '"') {
        if (input.charAt(pos) == '\\') {
          pos++;
          if (pos >= input.length()) {
            throw new IllegalArgumentException("Incomplete escape sequence");
          }
          final char c = input.charAt(pos);
          switch (c) {
            case 'n':
              sb.append('\n');
              break;
            case 't':
              sb.append('\t');
              break;
            case 'r':
              sb.append('\r');
              break;
            case '\\':
              sb.append('\\');
              break;
            case '"':
              sb.append('"');
              break;
            default:
              sb.append(c);
              break;
          }
          pos++;
        } else {
          sb.append(input.charAt(pos));
          pos++;
        }
      }
      return sb.toString();
    }

    private String parseIdentifier() {
      final int start = pos;
      while (pos < input.length()
          && (Character.isLetterOrDigit(input.charAt(pos))
              || input.charAt(pos) == '_')) {
        pos++;
      }
      if (start == pos) {
        throw new IllegalArgumentException(
            "Expected identifier at position " + pos);
      }
      return input.substring(start, pos);
    }

    private void skipWhitespace() {
      while (pos < input.length()
          && Character.isWhitespace(input.charAt(pos))) {
        pos++;
      }
    }

    private void expect(String expected) {
      skipWhitespace();
      if (!input.startsWith(expected, pos)) {
        throw new IllegalArgumentException(
            format(
                "Expected '%s' at position %d but found: %s",
                expected,
                pos,
                input.substring(pos, Math.min(pos + 10, input.length()))));
      }
      pos += expected.length();
    }

    private boolean tryConsume(String expected) {
      skipWhitespace();
      if (input.startsWith(expected, pos)) {
        pos += expected.length();
        return true;
      }
      return false;
    }
  }
}

// End Variants.java
