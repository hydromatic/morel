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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.parse.Parsers.appendId;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Pair.forEachIndexed;
import static net.hydromatic.morel.util.Static.endsWith;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.foreign.RelList;
import net.hydromatic.morel.parse.Parsers;
import net.hydromatic.morel.type.AliasType;
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
import net.hydromatic.morel.util.PairList;

/** Prints values. */
class Pretty {
  private final TypeSystem typeSystem;
  private final int lineWidth;
  private final Prop.Output output;
  private final int printLength;
  private final int printDepth;
  private final int stringDepth;
  private final char newline;

  Pretty(
      TypeSystem typeSystem,
      int lineWidth,
      Prop.Output output,
      int printLength,
      int printDepth,
      int stringDepth) {
    this.typeSystem = requireNonNull(typeSystem);
    this.lineWidth = lineWidth;
    this.output = requireNonNull(output);
    this.printLength = printLength;
    this.printDepth = printDepth;
    this.stringDepth = stringDepth;
    this.newline = '\n';
  }

  /** Prints a value to a buffer. */
  StringBuilder pretty(StringBuilder buf, Type type, Object value) {
    int lineEnd = lineWidth < 0 ? -1 : (buf.length() + lineWidth);
    return pretty1(buf, 0, new int[] {lineEnd}, 0, type, value, 0, 0);
  }

  /**
   * Prints a value to a buffer. If the first attempt goes beyond {@code
   * lineEnd}, back-tracks, adds a newline and indent, and tries again one time.
   */
  private StringBuilder pretty1(
      StringBuilder buf,
      int indent,
      int[] lineEnd,
      int depth,
      Type type,
      Object value,
      int leftPrec,
      int rightPrec) {
    final int start = buf.length();
    final int end = lineEnd[0];
    pretty2(buf, indent, lineEnd, depth, type, value, leftPrec, rightPrec);
    if (end >= 0 && buf.length() > end) {
      // Reset to start, remove trailing whitespace, add newline
      buf.setLength(start);
      while (buf.length() > 0
          && (buf.charAt(buf.length() - 1) == ' '
              || buf.charAt(buf.length() - 1) == newline)) {
        buf.setLength(buf.length() - 1);
      }
      if (buf.length() > 0) {
        buf.append(newline);
      }

      lineEnd[0] = lineWidth < 0 ? -1 : (buf.length() + lineWidth);
      indent(buf, indent);
      pretty2(buf, indent, lineEnd, depth, type, value, leftPrec, rightPrec);
    }
    return buf;
  }

  private static void indent(StringBuilder buf, int indent) {
    for (int i = 0; i < indent; i++) {
      buf.append(' ');
    }
  }

  private StringBuilder pretty2(
      StringBuilder buf,
      int indent,
      int[] end,
      int depth,
      Type type,
      Object value,
      int leftPrec,
      int rightPrec) {
    // Strip any alias. If 'pair' is an alias for 'int * int', we print a 'pair'
    // value the same way we would print an 'int * int' value.
    while (type instanceof AliasType) {
      type = ((AliasType) type).type;
    }

    if (value instanceof TypedVal) {
      final TypedVal typedVal = (TypedVal) value;
      final StringBuilder buf2 = new StringBuilder("val ");
      appendId(buf2, typedVal.name);
      if (customPrint(buf, indent, end, depth, typedVal.type, typedVal.o)) {
        end[0] = -1; // no limit
        prettyRaw(buf, indent, end, depth, buf2.toString());
      } else {
        buf2.append(" = ");
        prettyRaw(buf, indent, end, depth, buf2.toString());
        pretty1(
            buf, indent + 2, end, depth + 1, typedVal.type, typedVal.o, 0, 0);
      }
      buf.append(' ');
      TypeVal typeVal =
          new TypeVal(": ", typeSystem.unqualified(typedVal.type));
      prettyRaw(buf, indent + 2, end, depth, typeVal);
      return buf;
    }

    if (value instanceof NamedVal) {
      final NamedVal namedVal = (NamedVal) value;
      appendId(buf, namedVal.name).append('=');
      pretty1(buf, indent, end, depth, type, namedVal.o, 0, 0);
      return buf;
    }

    if (value instanceof LabelVal) {
      final LabelVal labelVal = (LabelVal) value;
      final String prefix =
          appendId(new StringBuilder(), labelVal.label).append(':').toString();
      TypeVal typeVal = new TypeVal(prefix, labelVal.type);
      pretty1(buf, indent, end, depth, type, typeVal, 0, 0);
      return buf;
    }

    if (value instanceof TypeVal) {
      TypeVal typeVal = (TypeVal) value;
      prettyType(buf, indent, end, depth, type, typeVal, leftPrec, rightPrec);
      return buf;
    }

    if (printDepth >= 0 && depth > printDepth) {
      buf.append('#');
      return buf;
    }
    final List<Object> list;
    final int start;
    switch (type.op()) {
      case ID:
        return prettyPrimitive(buf, (PrimitiveType) type, value);

      case FUNCTION_TYPE:
        return buf.append("fn");

      case LIST:
        final ListType listType = (ListType) type;
        list = toList(value);
        if (list instanceof RelList) {
          // Do not attempt to print the elements of a foreign list. It might be
          // huge.
          return buf.append(RelList.RELATION);
        }
        if (value instanceof TypedValue) {
          // A TypedValue is probably a field in a record that represents a
          // database catalog or a directory of CSV files. If the user wishes to
          // see the contents of each file they should use a query.
          return buf.append(RelList.RELATION);
        }
        return printList(buf, indent, end, depth, listType.elementType, list);

      case RECORD_TYPE:
        final RecordType recordType = (RecordType) type;
        list = toList(value);
        buf.append("{");
        start = buf.length();
        final Iterator<Object> iterator = list.iterator();
        recordType.argNameTypes.forEach(
            (name, type1) -> {
              if (buf.length() > start) {
                buf.append(",");
              }
              final Object o = iterator.next();
              final NamedVal namedVal = new NamedVal(name, o);
              pretty1(buf, indent + 1, end, depth + 1, type1, namedVal, 0, 0);
            });
        return buf.append("}");

      case TUPLE_TYPE:
        final TupleType tupleType = (TupleType) type;
        list = toList(value);
        buf.append("(");
        start = buf.length();
        forEachIndexed(
            list,
            tupleType.argTypes,
            (ordinal, o, elementType) -> {
              if (buf.length() > start) {
                buf.append(",");
              }
              pretty1(buf, indent + 1, end, depth + 1, elementType, o, 0, 0);
            });
        return buf.append(")");

      case FORALL_TYPE:
        return pretty2(
            buf, indent, end, depth + 1, ((ForallType) type).type, value, 0, 0);

      case DATA_TYPE:
        return prettyDataType(buf, indent, end, depth, (DataType) type, value);

      default:
        return buf.append(value);
    }
  }

  private void prettyRaw(
      StringBuilder buf, int indent, int[] end, int depth, Object value) {
    pretty1(buf, indent, end, depth, PrimitiveType.BOOL, value, 0, 0);
  }

  /**
   * Tries to print a value using a custom formatter.
   *
   * <p>If successful, returns true, and {@code buf} contains the value; if
   * unsuccessful, returns false, and the contents of {@code buf} are not
   * changed.
   */
  @SuppressWarnings("unchecked")
  private boolean customPrint(
      StringBuilder buf,
      int indent,
      int[] lineEnd,
      int depth,
      Type type,
      Object o) {
    if (output != Prop.Output.CLASSIC && canPrintTabular(type)) {
      final RecordType recordType = (RecordType) type.arg(0);
      final List<List<String>> recordList = new ArrayList<>();
      final List<String> valueList = new ArrayList<>();
      for (List<?> record : (List<List<?>>) o) {
        for (Object value : record) {
          valueList.add(value.toString());
        }
        recordList.add(ImmutableList.copyOf(valueList));
        valueList.clear();
      }

      // Default widths are based on headers.
      final List<Integer> widths = new ArrayList<>();
      final List<String> headers =
          ImmutableList.copyOf(recordType.argNameTypes.keySet());
      headers.forEach(s -> widths.add(s.length()));

      // Compute the widest value in each column.
      for (List<String> values : recordList) {
        for (int i = 0; i < values.size(); i++) {
          String s = values.get(i);
          if (widths.get(i) < s.length()) {
            widths.set(i, s.length());
          }
        }
      }

      row(buf, headers, widths, ' ');
      row(buf, Collections.nCopies(headers.size(), ""), widths, '-');
      for (List<String> strings : recordList) {
        row(buf, strings, widths, ' ');
      }
      buf.append(newline);
      return true;
    }
    return false;
  }

  /** Can print a type in tabular format if it is a list of records. */
  private static boolean canPrintTabular(Type type) {
    return type.isCollection()
        && type.arg(0) instanceof RecordType
        && canPrintTabular2((RecordType) type.arg(0));
  }

  /** Can print a record in tabular format if its fields are all primitive. */
  private static boolean canPrintTabular2(RecordType recordType) {
    return PairList.viewOf(recordType.argNameTypes)
        .allMatch((label, type) -> type instanceof PrimitiveType);
  }

  private void row(
      StringBuilder buf, List<String> values, List<Integer> widths, char pad) {
    final int i = buf.length();
    forEach(
        values,
        widths,
        (value, width) -> {
          if (buf.length() > i) {
            buf.append(' ');
          }
          final int j = buf.length() + width;
          buf.append(value);
          padTo(buf, j, pad);
        });
    int j = buf.length();
    while (j > 0 && buf.charAt(j - 1) == ' ') {
      --j;
    }
    buf.setLength(j);
    buf.append(newline);
  }

  private void padTo(StringBuilder buf, int desiredLength, char pad) {
    while (buf.length() < desiredLength) {
      buf.append(pad);
    }
  }

  private StringBuilder prettyPrimitive(
      StringBuilder buf, PrimitiveType primitiveType, Object value) {
    String s;
    switch (primitiveType) {
      case UNIT:
        return buf.append("()");
      case CHAR:
        Character c = (Character) value;
        s = Parsers.charToString(c);
        return buf.append('#').append('"').append(s).append('"');
      case STRING:
        s = (String) value;
        buf.append('"');
        if (stringDepth >= 0 && s.length() > stringDepth) {
          Parsers.stringToString(s.substring(0, stringDepth), buf);
          buf.append('#');
        } else {
          Parsers.stringToString(s, buf);
        }
        return buf.append('"');
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
  }

  private StringBuilder prettyDataType(
      StringBuilder buf,
      int indent,
      int[] lineEnd,
      int depth,
      DataType dataType,
      Object value) {
    final List<Object> list = toList(value);
    if (dataType.name.equals("vector")) {
      final Type argType = dataType.arg(0);
      return printList(buf.append('#'), indent, lineEnd, depth, argType, list);
    }
    if (dataType.name.equals("bag")) {
      // A bag value is printed the same as a list, distinguishable only by
      // its type, e.g.
      //  val odds = [1,3,5] : int list
      //  val evens = [0,2,4] : int bag
      if (list instanceof RelList) {
        // Do not attempt to print the elements of a foreign list. It might
        // be huge.
        return buf.append(RelList.RELATION);
      }
      final Type argType = dataType.arg(0);
      return printList(buf, indent, lineEnd, depth, argType, list);
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
  }

  private StringBuilder prettyType(
      StringBuilder buf,
      int indent,
      int[] end,
      int depth,
      Type type,
      TypeVal typeVal,
      int leftPrec,
      int rightPrec) {
    String prefix = typeVal.prefix;
    if (endsWith(buf, " ")) {
      // If the buffer ends with space, don't print any spaces at the start of
      // the prefix.
      while (prefix.startsWith(" ")) {
        prefix = prefix.substring(1);
      }
    }
    buf.append(prefix);

    final int indent2 = indent + prefix.length();
    final int start;
    switch (typeVal.type.op()) {
      case DATA_TYPE:
        if (typeVal.type.isCollection()) {
          return prettyCollectionType(
              buf,
              end,
              depth,
              type,
              typeVal,
              leftPrec,
              rightPrec,
              indent2,
              BuiltIn.Eqtype.BAG.mlName());
        }
        // fall through
      case ID:
      case ALIAS_TYPE:
      case TY_VAR:
        return pretty1(
            buf, indent2, end, depth, type, typeVal.type.moniker(), 0, 0);

      case LIST:
        return prettyCollectionType(
            buf,
            end,
            depth,
            type,
            typeVal,
            leftPrec,
            rightPrec,
            indent2,
            BuiltIn.Eqtype.LIST.mlName());

      case TUPLE_TYPE:
        if (leftPrec > Op.TUPLE_TYPE.left || rightPrec > Op.TUPLE_TYPE.right) {
          pretty1(buf, indent2, end, depth, type, "(", 0, 0);
          pretty1(buf, indent2, end, depth, type, typeVal, 0, 0);
          pretty1(buf, indent2, end, depth, type, ")", 0, 0);
          return buf;
        }
        final TupleType tupleType = (TupleType) typeVal.type;
        start = buf.length();
        List<Type> argTypes = tupleType.argTypes;
        for (int i = 0; i < argTypes.size(); i++) {
          Type argType = argTypes.get(i);
          if (buf.length() > start) {
            pretty1(buf, indent2, end, depth, type, " * ", 0, 0);
          }
          final TypeVal typeVal1 = new TypeVal("", argType);
          final int leftPrec1 = i == 0 ? leftPrec : Op.TUPLE_TYPE.right;
          final int rightPrec1 =
              i == argTypes.size() - 1 ? rightPrec : Op.TUPLE_TYPE.left;
          pretty1(
              buf, indent2, end, depth, type, typeVal1, leftPrec1, rightPrec1);
        }
        return buf;

      case RECORD_TYPE:
      case PROGRESSIVE_RECORD_TYPE:
        final RecordType recordType = (RecordType) typeVal.type;
        final boolean progressive = typeVal.type.isProgressive();
        buf.append("{");
        start = buf.length();
        recordType.argNameTypes.forEach(
            (name, elementType) -> {
              if (buf.length() > start) {
                buf.append(", ");
              }
              LabelVal labelVal = new LabelVal(name, elementType);
              pretty1(buf, indent2 + 1, end, depth, type, labelVal, 0, 0);
            });
        if (progressive) {
          if (buf.length() > start) {
            buf.append(", ");
          }
          pretty1(buf, indent2 + 1, end, depth, type, "...", 0, 0);
        }
        return buf.append("}");

      case FUNCTION_TYPE:
        if (leftPrec > Op.FUNCTION_TYPE.left
            || rightPrec > Op.FUNCTION_TYPE.right) {
          pretty1(buf, indent2, end, depth, type, "(", 0, 0);
          pretty1(buf, indent2, end, depth, type, typeVal, 0, 0);
          pretty1(buf, indent2, end, depth, type, ")", 0, 0);
          return buf;
        }
        final FnType fnType = (FnType) typeVal.type;
        final TypeVal typeVal1 = new TypeVal("", fnType.paramType);
        final int rightPrec1 = Op.FUNCTION_TYPE.left;
        pretty1(buf, indent2, end, depth, type, typeVal1, leftPrec, rightPrec1);
        final TypeVal typeVal2 = new TypeVal(" -> ", fnType.resultType);
        final int leftPrec1 = Op.FUNCTION_TYPE.right;
        pretty1(buf, indent2, end, depth, type, typeVal2, leftPrec1, rightPrec);
        return buf;

      default:
        throw new AssertionError("unknown type " + typeVal.type);
    }
  }

  /** Prints "list" and "bag" types in a similar way. */
  private StringBuilder prettyCollectionType(
      StringBuilder buf,
      int[] lineEnd,
      int depth,
      Type type,
      TypeVal typeVal,
      int leftPrec,
      int rightPrec,
      int indent2,
      String typeName) {
    if (leftPrec > Op.LIST.left || rightPrec > Op.LIST.right) {
      pretty1(buf, indent2, lineEnd, depth, type, "(", 0, 0);
      pretty1(buf, indent2, lineEnd, depth, type, typeVal, 0, 0);
      pretty1(buf, indent2, lineEnd, depth, type, ")", 0, 0);
      return buf;
    }
    checkArgument(
        typeVal.type.isCollection(), "not a collection type: %s", type);
    final Type elementType = typeVal.type.arg(0);
    final TypeVal typeVal1 = new TypeVal("", elementType);
    pretty1(
        buf, indent2, lineEnd, depth, type, typeVal1, leftPrec, Op.LIST.left);
    return buf.append(" ").append(typeName);
  }

  @SuppressWarnings("unchecked")
  private static List<Object> toList(Object value) {
    if (value instanceof TypedValue) {
      TypedValue typedValue = (TypedValue) value;
      return (List<Object>) typedValue.valueAs(List.class);
    }
    return (List<Object>) value;
  }

  private StringBuilder printList(
      StringBuilder buf,
      int indent,
      int[] lineEnd,
      int depth,
      Type elementType,
      List<Object> list) {
    buf.append("[");
    int start = buf.length();
    for (Ord<Object> o : Ord.zip(list)) {
      if (buf.length() > start) {
        buf.append(",");
      }
      if (printLength >= 0 && o.i >= printLength) {
        prettyRaw(buf, indent + 1, lineEnd, depth + 1, "...");
        break;
      } else {
        pretty1(buf, indent + 1, lineEnd, depth + 1, elementType, o.e, 0, 0);
      }
    }
    return buf.append("]");
  }

  /**
   * Wrapper that indicates that a value should be printed "val name = value :
   * type".
   */
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
