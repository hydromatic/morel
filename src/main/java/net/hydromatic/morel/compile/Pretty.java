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

import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.foreign.RelList;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;
import net.hydromatic.morel.util.Ord;

import com.google.common.collect.Iterables;

import java.util.List;
import javax.annotation.Nonnull;

import static net.hydromatic.morel.parse.Parsers.appendId;
import static net.hydromatic.morel.util.Pair.forEachIndexed;

import static java.util.Objects.requireNonNull;

/** Prints values. */
class Pretty {

  private final TypeSystem typeSystem;
  private final int lineWidth;
  private final int printLength;
  private final int printDepth;
  private final int stringDepth;

  Pretty(TypeSystem typeSystem, int lineWidth, int printLength, int printDepth,
      int stringDepth) {
    this.typeSystem = requireNonNull(typeSystem);
    this.lineWidth = lineWidth;
    this.printLength = printLength;
    this.printDepth = printDepth;
    this.stringDepth = stringDepth;
  }

  /** Prints a value to a buffer. */
  StringBuilder pretty(@Nonnull StringBuilder buf,
      @Nonnull Type type, @Nonnull Object value) {
    int lineEnd = lineWidth < 0 ? -1 : (buf.length() + lineWidth);
    return pretty1(buf, 0, new int[] {lineEnd}, 0, type, value, 0, 0);
  }

  /** Prints a value to a buffer. If the first attempt goes beyond
   * {@code lineEnd}, back-tracks, adds a newline and indent, and
   * tries again one time. */
  private StringBuilder pretty1(@Nonnull StringBuilder buf, int indent,
      int[] lineEnd, int depth, @Nonnull Type type, @Nonnull Object value,
      int leftPrec, int rightPrec) {
    final int start = buf.length();
    final int end = lineEnd[0];
    pretty2(buf, indent, lineEnd, depth, type, value, leftPrec, rightPrec);
    if (end >= 0 && buf.length() > end) {
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

      lineEnd[0] = lineWidth < 0 ? -1 : (buf.length() + lineWidth);
      indent(buf, indent);
      pretty2(buf, indent, lineEnd, depth, type, value, leftPrec, rightPrec);
    }
    return buf;
  }

  private static void indent(@Nonnull StringBuilder buf, int indent) {
    for (int i = 0; i < indent; i++) {
      buf.append(' ');
    }
  }

  private StringBuilder pretty2(@Nonnull StringBuilder buf,
      int indent, int[] lineEnd, int depth, @Nonnull Type type,
      @Nonnull Object value, int leftPrec, int rightPrec) {
    if (value instanceof TypedVal) {
      final TypedVal typedVal = (TypedVal) value;
      final StringBuilder buf2 = new StringBuilder("val ");
      appendId(buf2, typedVal.name)
          .append(" = ");
      pretty1(buf, indent, lineEnd, depth, PrimitiveType.BOOL,
          buf2.toString(), 0, 0);
      pretty1(buf, indent + 2, lineEnd, depth + 1, typedVal.type, typedVal.o,
          0, 0);
      buf.append(' ');
      pretty1(buf, indent + 2, lineEnd, depth, PrimitiveType.BOOL,
          new TypeVal(": ", unqualified(typedVal.type)), 0, 0);
      return buf;
    }

    if (value instanceof NamedVal) {
      final NamedVal namedVal = (NamedVal) value;
      appendId(buf, namedVal.name)
          .append('=');
      pretty1(buf, indent, lineEnd, depth, type, namedVal.o, 0, 0);
      return buf;
    }

    if (value instanceof LabelVal) {
      final LabelVal labelVal = (LabelVal) value;
      final String prefix =
          appendId(new StringBuilder(), labelVal.label)
              .append(':')
              .toString();
      pretty1(buf, indent, lineEnd, depth, type,
          new TypeVal(prefix, labelVal.type), 0, 0);
      return buf;
    }

    if (value instanceof TypeVal) {
      return prettyType(buf, indent, lineEnd, depth, type, (TypeVal) value,
          leftPrec, rightPrec);
    }

    if (printDepth >= 0 && depth > printDepth) {
      buf.append('#');
      return buf;
    }
    final List<Object> list;
    final int start;
    String s;
    switch (type.op()) {
    case ID:
      switch ((PrimitiveType) type) {
      case UNIT:
        return buf.append("()");
      case CHAR:
        s = ((Character) value).toString();
        return buf.append('#')
            .append('"')
            .append(s.replace("\\", "\\\\").replace("\"", "\\\""))
            .append('"');
      case STRING:
        s = (String) value;
        if (stringDepth >= 0 && s.length() > stringDepth) {
          s = s.substring(0, stringDepth) + "#";
        }
        return buf.append('"')
            .append(s.replace("\\", "\\\\").replace("\"", "\\\""))
            .append('"');
      case INT:
        int i = (Integer) value;
        if (i < 0) {
          if (i == Integer.MIN_VALUE) {
            return buf.append("~2147483648");
          }
          buf.append('~');
          i = -i;
        }
        return buf.append(i);
      case REAL:
        return Codes.appendFloat(buf, (Float) value);
      default:
        return buf.append(value);
      }

    case FUNCTION_TYPE:
      return buf.append("fn");

    case LIST:
      final ListType listType = (ListType) type;
      list = toList(value);
      if (list instanceof RelList) {
        // Do not attempt to print the elements of a foreign list. It might be
        // huge.
        return buf.append("<relation>");
      }
      if (value instanceof TypedValue) {
        // A TypedValue is probably a field in a record that represents a
        // database catalog or a directory of CSV files. If the user wishes to
        // see the contents of each file they should use a query.
        return buf.append("<relation>");
      }
      return printList(buf, indent, lineEnd, depth, listType.elementType, list);

    case RECORD_TYPE:
      final RecordType recordType = (RecordType) type;
      list = toList(value);
      buf.append("{");
      start = buf.length();
      forEachIndexed(list, recordType.argNameTypes.entrySet(),
          (ordinal, o, nameType) -> {
            if (buf.length() > start) {
              buf.append(",");
            }
            pretty1(buf, indent + 1, lineEnd, depth + 1, nameType.getValue(),
                new NamedVal(nameType.getKey(), o), 0, 0);
          });
      return buf.append("}");

    case TUPLE_TYPE:
      final TupleType tupleType = (TupleType) type;
      list = toList(value);
      buf.append("(");
      start = buf.length();
      forEachIndexed(list, tupleType.argTypes,
          (ordinal, o, elementType) -> {
            if (buf.length() > start) {
              buf.append(",");
            }
            pretty1(buf, indent + 1, lineEnd, depth + 1, elementType, o, 0, 0);
          });
      return buf.append(")");

    case FORALL_TYPE:
      return pretty2(buf, indent, lineEnd, depth + 1, ((ForallType) type).type,
          value, 0, 0);

    case DATA_TYPE:
      final DataType dataType = (DataType) type;
      list = toList(value);
      if (dataType.name.equals("vector")) {
        final Type argType = Iterables.getOnlyElement(dataType.arguments);
        return printList(buf.append('#'), indent, lineEnd, depth, argType,
            list);
      }
      final String tyConName = (String) list.get(0);
      buf.append(tyConName);
      final Type typeConArgType =
          dataType.typeConstructors(typeSystem).get(tyConName);
      requireNonNull(typeConArgType);
      if (list.size() == 2) {
        final Object arg = list.get(1);
        buf.append(' ');
        final boolean needParentheses =
            typeConArgType.op() == Op.DATA_TYPE && arg instanceof List;
        if (needParentheses) {
          buf.append('(');
        }
        pretty2(buf, indent, lineEnd, depth + 1, typeConArgType, arg, 0, 0);
        if (needParentheses) {
          buf.append(')');
        }
      }
      return buf;

    default:
      return buf.append(value);
    }
  }

  private StringBuilder prettyType(StringBuilder buf, int indent, int[] lineEnd,
      int depth, Type type, TypeVal typeVal, int leftPrec, int rightPrec) {
    buf.append(typeVal.prefix);
    final int indent2 = indent + typeVal.prefix.length();
    final int start;
    switch (typeVal.type.op()) {
    case DATA_TYPE:
    case ID:
    case TY_VAR:
      return pretty1(buf, indent2, lineEnd, depth, type,
          typeVal.type.moniker(), 0, 0);

    case LIST:
      if (leftPrec > Op.LIST.left
          || rightPrec > Op.LIST.right) {
        pretty1(buf, indent2, lineEnd, depth, type, "(", 0, 0);
        pretty1(buf, indent2, lineEnd, depth, type, typeVal, 0, 0);
        pretty1(buf, indent2, lineEnd, depth, type, ")", 0, 0);
        return buf;
      }
      final ListType listType = (ListType) typeVal.type;
      pretty1(buf, indent2, lineEnd, depth, type,
          new TypeVal("", listType.elementType), leftPrec, Op.LIST.left);
      return buf.append(" list");

    case TUPLE_TYPE:
      if (leftPrec > Op.TUPLE_TYPE.left
          || rightPrec > Op.TUPLE_TYPE.right) {
        pretty1(buf, indent2, lineEnd, depth, type, "(", 0, 0);
        pretty1(buf, indent2, lineEnd, depth, type, typeVal, 0, 0);
        pretty1(buf, indent2, lineEnd, depth, type, ")", 0, 0);
        return buf;
      }
      final TupleType tupleType = (TupleType) typeVal.type;
      start = buf.length();
      List<Type> argTypes = tupleType.argTypes;
      for (int i = 0; i < argTypes.size(); i++) {
        Type argType = argTypes.get(i);
        if (buf.length() > start) {
          pretty1(buf, indent2, lineEnd, depth, type,
              " * ", 0, 0);
        }
        pretty1(buf, indent2, lineEnd, depth, type,
            new TypeVal("", argType),
            i == 0 ? leftPrec : Op.TUPLE_TYPE.right,
            i == argTypes.size() - 1 ? rightPrec : Op.TUPLE_TYPE.left);
      }
      return buf;

    case RECORD_TYPE:
    case PROGRESSIVE_RECORD_TYPE:
      final RecordType recordType = (RecordType) typeVal.type;
      final boolean progressive = typeVal.type.isProgressive();
      buf.append("{");
      start = buf.length();
      recordType.argNameTypes.forEach((name, elementType) -> {
        if (buf.length() > start) {
          buf.append(", ");
        }
        pretty1(buf, indent2 + 1, lineEnd, depth, type,
            new LabelVal(name, elementType), 0, 0);
      });
      if (progressive) {
        if (buf.length() > start) {
          buf.append(", ");
        }
        pretty1(buf, indent2 + 1, lineEnd, depth, type, "...", 0, 0);
      }
      return buf.append("}");

    case FUNCTION_TYPE:
      if (leftPrec > Op.FUNCTION_TYPE.left
          || rightPrec > Op.FUNCTION_TYPE.right) {
        pretty1(buf, indent2, lineEnd, depth, type, "(", 0, 0);
        pretty1(buf, indent2, lineEnd, depth, type, typeVal, 0, 0);
        pretty1(buf, indent2, lineEnd, depth, type, ")", 0, 0);
        return buf;
      }
      final FnType fnType = (FnType) typeVal.type;
      pretty1(buf, indent2 + 1, lineEnd, depth, type,
          new TypeVal("", fnType.paramType),
          leftPrec, Op.FUNCTION_TYPE.left);
      pretty1(buf, indent2 + 1, lineEnd, depth, type, " -> ", 0, 0);
      pretty1(buf, indent2 + 1, lineEnd, depth, type,
          new TypeVal("", fnType.resultType),
          Op.FUNCTION_TYPE.right, rightPrec);
      return buf;

    default:
      throw new AssertionError("unknown type " + typeVal.type);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Object> toList(Object value) {
    if (value instanceof TypedValue) {
      TypedValue typedValue = (TypedValue) value;
      return (List<Object>) typedValue.valueAs(List.class);
    }
    return (List<Object>) value;
  }

  private static Type unqualified(Type type) {
    return type instanceof ForallType ? unqualified(((ForallType) type).type)
        : type;
  }

  private StringBuilder printList(@Nonnull StringBuilder buf,
      int indent, int[] lineEnd, int depth, @Nonnull Type elementType,
      @Nonnull List<Object> list) {
    buf.append("[");
    int start = buf.length();
    for (Ord<Object> o : Ord.zip(list)) {
      if (buf.length() > start) {
        buf.append(",");
      }
      if (printLength >= 0 && o.i >= printLength) {
        pretty1(buf, indent + 1, lineEnd, depth + 1, PrimitiveType.BOOL,
            "...", 0, 0);
        break;
      } else {
        pretty1(buf, indent + 1, lineEnd, depth + 1, elementType, o.e, 0, 0);
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

  /** Wrapper that indicates that a value should be printed "label:type". */
  private static class LabelVal {
    final String label;
    final Type type;

    LabelVal(String label, Type type) {
      this.label = label;
      this.type = type;
    }
  }

  /** Wrapper around a type value. */
  private static class TypeVal {
    final String prefix;
    final Type type;

    TypeVal(String prefix, Type type) {
      this.prefix = prefix;
      this.type = type;
    }
  }
}

// End Pretty.java
