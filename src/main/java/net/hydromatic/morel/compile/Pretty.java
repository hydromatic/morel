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

import net.hydromatic.morel.foreign.RelList;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;

import java.util.List;
import javax.annotation.Nonnull;

/** Prints values. */
class Pretty {

  private static final int LINE_LENGTH = 78;
  private static final int LIST_LENGTH = 12;
  private static final int DEPTH_LIMIT = 5;

  private Pretty() {}

  /** Prints a value to a buffer. */
  static StringBuilder pretty(@Nonnull StringBuilder buf,
      @Nonnull Type type, @Nonnull Object value) {
    return pretty1(buf, 0, new int[] {buf.length() + LINE_LENGTH}, 0, type,
        value);
  }

  /** Prints a value to a buffer. If the first attempt goes beyond
   * {@code lineEnd}, back-tracks, adds a newline and indent, and
   * tries again one time. */
  private static StringBuilder pretty1(@Nonnull StringBuilder buf, int indent,
      int[] lineEnd, int depth, @Nonnull Type type, @Nonnull Object value) {
    final int start = buf.length();
    final int end = lineEnd[0];
    pretty2(buf, indent, lineEnd, depth, type, value);
    if (buf.length() > end) {
      // Reset to start, remove trailing whitespace, add newline
      buf.setLength(start);
      while (buf.length() > 0
          && (buf.charAt(buf.length() - 1) == ' '
              || buf.charAt(buf.length() - 1) == '\n')) {
        buf.setLength(buf.length() - 1);
      }
      if (buf.length() > 0) {
        buf.append("\n");
      }

      lineEnd[0] = buf.length() + LINE_LENGTH;
      indent(buf, indent);
      pretty2(buf, indent, lineEnd, depth, type, value);
    }
    return buf;
  }

  private static boolean endsWithSpaces(StringBuilder buf, int n) {
    if (buf.length() < n) {
      return false;
    }
    for (int i = 0; i < n; i++) {
      if (buf.charAt(buf.length() - 1 - i) != ' ') {
        return false;
      }
    }
    return true;
  }

  private static boolean hasIndent(StringBuilder buf, int n) {
    int i = buf.length() - 1 - n;
    if (i < 0) {
      return false;
    }
    if (buf.charAt(i) != '\n') {
      return false;
    }
    for (; i < buf.length(); i++) {
      if (buf.charAt(i) != ' ') {
        return false;
      }
    }
    return true;
  }

  private static void indent(@Nonnull StringBuilder buf, int indent) {
    for (int i = 0; i < indent; i++) {
      buf.append(' ');
    }
  }

  private static int currentIndent(StringBuilder buf) {
    int i = buf.length() - 1;
    while (i > 0 && buf.charAt(i) != '\n') {
      --i;
    }
    return buf.length() - 1 - i;
  }

  private static StringBuilder pretty2(@Nonnull StringBuilder buf,
      int indent, int[] lineEnd, int depth,
      @Nonnull Type type, @Nonnull Object value) {
    if (value instanceof TypedVal) {
      final TypedVal typedVal = (TypedVal) value;
      pretty1(buf, indent, lineEnd, depth + 1, PrimitiveType.BOOL,
          "val " + typedVal.name + " = ");
      pretty1(buf, indent + 2, lineEnd, depth + 1, type, typedVal.o);
      buf.append(' ');
      pretty1(buf, indent + 2, lineEnd, depth + 1, PrimitiveType.BOOL,
          ": " + typedVal.type.moniker());
      return buf;
    }
    if (value instanceof NamedVal) {
      final NamedVal namedVal = (NamedVal) value;
      buf.append(namedVal.name);
      buf.append('=');
      pretty1(buf, indent, lineEnd, depth, type, namedVal.o);
      return buf;
    }
    if (depth > DEPTH_LIMIT) {
      buf.append('#');
      return buf;
    }
    final List<Object> list;
    final int start;
    switch (type.op()) {
    case ID:
      switch ((PrimitiveType) type) {
      case UNIT:
        return buf.append("()");
      case CHAR:
        return buf.append("#\"").append((char) (Character) value).append("\"");
      case STRING:
        return buf.append('"')
            .append(((String) value).replace("\"", "\\\""))
            .append('"');
      case INT:
        Integer i = (Integer) value;
        if (i < 0) {
          if (i == Integer.MIN_VALUE) {
            return buf.append("~2147483648");
          }
          buf.append('~');
          i = -i;
        }
        return buf.append(i);
      case REAL:
        Float f = (Float) value;
        if (f < 0) {
          buf.append('~');
          f = -f;
        }
        return buf.append(f);
      default:
        return buf.append(value);
      }

    case FUNCTION_TYPE:
      return buf.append("fn");

    case LIST:
      final ListType listType = (ListType) type;
      //noinspection unchecked
      list = (List) value;
      if (list instanceof RelList) {
        // Do not attempt to print the elements of a foreign list. It might be huge.
        return buf.append("<relation>");
      }
      return printList(buf, indent, lineEnd, depth, listType.elementType, list);

    case RECORD_TYPE:
      final RecordType recordType =
          (RecordType) type;
      //noinspection unchecked
      list = (List) value;
      buf.append("{");
      start = buf.length();
      Pair.forEachIndexed(list, recordType.argNameTypes.entrySet(),
          (ordinal, o, nameType) -> {
            if (buf.length() > start) {
              buf.append(",");
            }
            pretty1(buf, indent + 1, lineEnd, depth + 1, nameType.getValue(),
                new NamedVal(nameType.getKey(), o));
          });
      return buf.append("}");

    case TUPLE_TYPE:
      final TupleType tupleType = (TupleType) type;
      //noinspection unchecked
      list = (List) value;
      buf.append("(");
      start = buf.length();
      Pair.forEachIndexed(list, tupleType.argTypes,
          (ordinal, o, elementType) -> {
            if (buf.length() > start) {
              buf.append(",");
            }
            pretty1(buf, indent, lineEnd, depth + 1, elementType, o);
          });
      return buf.append(")");

    case FORALL_TYPE:
      return pretty2(buf, indent, lineEnd, depth + 1, ((ForallType) type).type,
          value);

    case DATA_TYPE:
      final DataType dataType = (DataType) type;
      //noinspection unchecked
      list = (List) value;
      if (dataType.name.equals("vector")) {
        return printList(buf.append('#'), indent, lineEnd, depth,
            dataType.typeVars.get(0), list);
      }
      final String tyConName = (String) list.get(0);
      buf.append(tyConName);
      final Type typeConArgType = dataType.typeConstructors.get(tyConName);
      if (list.size() == 2) {
        final Object arg = list.get(1);
        buf.append(' ');
        pretty2(buf, indent, lineEnd, depth + 1, typeConArgType, arg);
      }
      return buf;

    default:
      return buf.append(value);
    }
  }

  private static StringBuilder printList(@Nonnull StringBuilder buf,
      int indent, int[] lineEnd, int depth, @Nonnull Type elementType,
      @Nonnull List<Object> list) {
    buf.append("[");
    int start = buf.length();
    for (Ord<Object> o : Ord.zip(list)) {
      if (buf.length() > start) {
        buf.append(",");
      }
      if (o.i >= LIST_LENGTH) {
        pretty1(buf, indent + 1, lineEnd, depth + 1, PrimitiveType.BOOL,
            "...");
        break;
      } else {
        pretty1(buf, indent + 1, lineEnd, depth + 1, elementType, o.e);
      }
    }
    return buf.append("]");
  }

  /** Wrapper that indicates that a value should be printed
   * "val name = value : type". */
  static class TypedVal {
    final String name;
    final Object o;
    final Type type;

    TypedVal(String name, Object o, Type type) {
      this.name = name;
      this.o = o;
      this.type = type;
    }
  }

  /** Wrapper that indicates that a value should be printed "name = value". */
  private static class NamedVal {
    final String name;
    final Object o;

    NamedVal(String name, Object o) {
      this.name = name;
      this.o = o;
    }
  }
}

// End Pretty.java
