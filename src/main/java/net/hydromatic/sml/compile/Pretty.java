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
package net.hydromatic.sml.compile;

import net.hydromatic.sml.type.ListType;
import net.hydromatic.sml.type.PrimitiveType;
import net.hydromatic.sml.type.RecordType;
import net.hydromatic.sml.type.TupleType;
import net.hydromatic.sml.type.Type;
import net.hydromatic.sml.util.Pair;

import java.util.List;
import javax.annotation.Nonnull;

/** Prints values. */
class Pretty {

  private static final int LINE_LENGTH = 80;

  private Pretty() {}

  /** Prints a value to a buffer. */
  static StringBuilder pretty(@Nonnull StringBuilder buf,
      @Nonnull Type type, @Nonnull Object value) {
    return pretty1(buf, 2, new int[] {buf.length() + LINE_LENGTH}, type, value);
  }

  /** Prints a value to a buffer. If the first attempt goes beyond
   * {@code lineEnd}, back-tracks, adds a newline and indent, and
   * tries again one time. */
  private static StringBuilder pretty1(@Nonnull StringBuilder buf, int indent,
      int[] lineEnd, @Nonnull Type type, @Nonnull Object value) {
    final int start = buf.length();
    final int end = lineEnd[0];
    pretty2(buf, indent, lineEnd, type, value);
    if (buf.length() > end) {
      // Reset to start, remove trailing whitespace, add newline
      buf.setLength(start);
      while (buf.length() > 0
          && buf.charAt(buf.length() - 1) == ' ') {
        buf.setLength(buf.length() - 1);
      }
      buf.append("\n");

      lineEnd[0] = buf.length() + LINE_LENGTH;
      for (int i = 0; i < indent; i++) {
        buf.append(' ');
      }
      pretty2(buf, indent + 1, lineEnd, type, value);
    }
    return buf;
  }

  private static StringBuilder pretty2(@Nonnull StringBuilder buf,
      int indent, int[] lineEnd,
      @Nonnull Type type, @Nonnull Object value) {
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
      final ListType listType =
          (ListType) type;
      //noinspection unchecked
      list = (List) value;
      buf.append("[");
      start = buf.length();
      for (Object o : list) {
        if (buf.length() > start) {
          buf.append(",");
        }
        pretty1(buf, indent, lineEnd, listType.elementType, o);
      }
      return buf.append("]");

    case RECORD_TYPE:
      final RecordType recordType =
          (RecordType) type;
      //noinspection unchecked
      list = (List) value;
      buf.append("{");
      start = buf.length();
      Pair.forEach(list, recordType.argNameTypes.entrySet(),
          (o, nameType) -> {
            if (buf.length() > start) {
              buf.append(",");
            }
            buf.append(nameType.getKey())
                .append('=');
            pretty1(buf, indent, lineEnd, nameType.getValue(), o);
          });
      return buf.append("}");

    case TUPLE_TYPE:
      final TupleType tupleType = (TupleType) type;
      //noinspection unchecked
      list = (List) value;
      buf.append("(");
      start = buf.length();
      Pair.forEach(list, tupleType.argTypes,
          (o, elementType) -> {
            if (buf.length() > start) {
              buf.append(",");
            }
            pretty1(buf, indent, lineEnd, elementType, o);
          });
      return buf.append(")");

    default:
      return buf.append(value);
    }
  }
}

// End Pretty.java
