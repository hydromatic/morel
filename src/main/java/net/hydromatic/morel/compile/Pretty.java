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
import static net.hydromatic.morel.parse.Parsers.appendId;
import static net.hydromatic.morel.util.Lindig.EMPTY;
import static net.hydromatic.morel.util.Lindig.HARD_LINE;
import static net.hydromatic.morel.util.Lindig.LINE;
import static net.hydromatic.morel.util.Lindig.align;
import static net.hydromatic.morel.util.Lindig.beside;
import static net.hydromatic.morel.util.Lindig.fill;
import static net.hydromatic.morel.util.Lindig.flatten;
import static net.hydromatic.morel.util.Lindig.group;
import static net.hydromatic.morel.util.Lindig.nest;
import static net.hydromatic.morel.util.Lindig.render;
import static net.hydromatic.morel.util.Lindig.text;
import static net.hydromatic.morel.util.Lindig.union;
import static net.hydromatic.morel.util.Pair.forEachIndexed;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.Variant;
import net.hydromatic.morel.foreign.RelList;
import net.hydromatic.morel.parse.Parsers;
import net.hydromatic.morel.type.AliasType;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;
import net.hydromatic.morel.util.Lindig.Doc;

/** Prints values. */
class Pretty {
  private final TypeSystem typeSystem;
  private final int lineWidth;
  private final Prop.Output output;
  private final int printLength;
  private final int printDepth;
  private final int stringDepth;
  private final int stringFold;
  private final BagPrinter bagPrinter;

  Pretty(
      TypeSystem typeSystem,
      int lineWidth,
      Prop.Output output,
      int printLength,
      int printDepth,
      int stringDepth,
      int stringFold,
      BagPrinter bagPrinter) {
    this.typeSystem = requireNonNull(typeSystem);
    this.lineWidth = lineWidth;
    this.output = requireNonNull(output);
    this.printLength = printLength;
    this.printDepth = printDepth;
    this.stringDepth = stringDepth;
    this.stringFold = stringFold;
    this.bagPrinter = requireNonNull(bagPrinter);
  }

  /** Prints a binding to a buffer. */
  StringBuilder pretty(StringBuilder buf, TypedVal typedVal) {
    // In tabular mode, a value whose type is a list of records prints as a
    // table. The tabular printer may still decline at runtime (e.g. when
    // "printDepth" is too low to show the rows); then, and for every other
    // value, we use the classic printer.
    if (output == Prop.Output.TABULAR
        && TabularPrinter.canPrint(typedVal.type)
        && prettyTabular(buf, typedVal)) {
      return buf;
    }
    return prettyClassic(buf, typedVal);
  }

  private StringBuilder prettyPrimitive(
      StringBuilder buf, PrimitiveType primitiveType, Object value) {
    String s;
    switch (primitiveType) {
        // lint: sort where '#case' until '#default:'
      case CHAR:
        Character c = (Character) value;
        s = Parsers.charToString(c);
        return buf.append('#').append('"').append(s).append('"');
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
      case UNIT:
        return buf.append("()");
      case WORD:
        // Print in hexadecimal, like Standard ML (and Word.toString).
        return buf.append("0wx")
            .append(
                Long.toUnsignedString((Long) value, 16)
                    .toUpperCase(Locale.ROOT));
      default:
        return buf.append(value);
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

  // -- Classic (default) output ---------------------------------------------
  //
  // Builds a "util.Lindig.Doc" for the value and lets that engine choose line
  // breaks. Leaf values (primitives, "fn") are rendered directly by
  // "flatLeaf"; the list/record/tuple/data-type structure becomes a Doc.
  // Rendering at "lineWidth - 1" matches SML/NJ's right margin: its Oppen
  // printer allows one more column than ours before breaking.

  /** Renders a binding in classic (the default, non-tabular) style. */
  private StringBuilder prettyClassic(StringBuilder buf, TypedVal typedVal) {
    final StringBuilder prefix = new StringBuilder("val ");
    appendId(prefix, typedVal.name).append(" =");
    // The value is one level below the binding, so start at depth 1: at
    // "printDepth" 0 the whole value prints as "#", matching SML/NJ.
    final Doc valueDoc = valueDoc(typedVal.type, typedVal.o, 1);
    // A "variant" value prints its declared type with a " variant" suffix,
    // e.g. "INT 3 : int variant".
    final Doc typeBody;
    if (typedVal.o instanceof Variant) {
      final Type type1 = ((Variant) typedVal.o).type;
      typeBody =
          beside(
              typeDoc(typeSystem.unqualified(type1), 0, 0), text(" variant"));
    } else {
      typeBody = typeDoc(typeSystem.unqualified(typedVal.type), 0, 0);
    }
    // The value stays on the "val ... =" line only if it fits there entirely
    // flat; otherwise the whole value moves to its own line, indented by 2,
    // where it is free to wrap. Likewise the type stays on the value's last
    // line if it fits flat there, otherwise it moves to its own line. The
    // "wide" alternative is flattened so the decision is made on the value (or
    // type) as a whole, not deferred to its internal line breaks. This matches
    // how SML/NJ lays out a binding.
    final Doc valuePart =
        union(
            beside(text(" "), flatten(valueDoc)),
            nest(2, beside(HARD_LINE, valueDoc)));
    final Doc typePart =
        union(
            beside(text(" : "), flatten(typeBody)),
            nest(2, beside(HARD_LINE, beside(text(": "), typeBody))));
    final Doc doc =
        beside(text(prefix.toString()), beside(valuePart, typePart));
    final int width = lineWidth < 0 ? Integer.MAX_VALUE : lineWidth - 1;
    return buf.append(render(width, doc));
  }

  /**
   * Tries to render a binding as a table, for "output = tabular". On success
   * the table is written to {@code buf}, followed by a "val name : type" line
   * (there is no "= value" because the value is the table), and returns true.
   * If the tabular printer declines (for example when {@code printDepth} is too
   * low to show the rows), leaves {@code buf} unchanged and returns false.
   */
  private boolean prettyTabular(StringBuilder buf, TypedVal typedVal) {
    final int start = buf.length();
    final boolean printed =
        new TabularPrinter(printDepth, printLength, stringDepth, stringFold)
            .print(buf, 0, typedVal.type, typedVal.o);
    if (!printed) {
      buf.setLength(start);
      return false;
    }
    final StringBuilder line = new StringBuilder("val ");
    appendId(line, typedVal.name).append(" : ");
    // A "variant" value prints its declared type with a " variant" suffix.
    final Type type;
    final String suffix;
    if (typedVal.o instanceof Variant) {
      type = ((Variant) typedVal.o).type;
      suffix = " variant";
    } else {
      type = typedVal.type;
      suffix = "";
    }
    line.append(
        render(Integer.MAX_VALUE, typeDoc(typeSystem.unqualified(type), 0, 0)));
    line.append(suffix);
    buf.append(line);
    return true;
  }

  /**
   * Renders a leaf value (a primitive or a function) as a string. The Doc
   * printer handles all composite types itself, so this is only ever reached
   * for a value whose type is a primitive or a function.
   */
  private String flatLeaf(Object value, Type type) {
    if (type.op() == Op.FUNCTION_TYPE) {
      return "fn";
    }
    if (type instanceof PrimitiveType) {
      return prettyPrimitive(new StringBuilder(), (PrimitiveType) type, value)
          .toString();
    }
    return String.valueOf(value);
  }

  /** Builds a Doc for a value of the given type. */
  private Doc valueDoc(Type type, Object value, int depth) {
    while (type instanceof AliasType) {
      type = ((AliasType) type).type;
    }
    if (value instanceof Variant) {
      final Variant v = (Variant) value;
      return valueDoc(v.type, v.value, depth);
    }
    if (printDepth >= 0 && depth > printDepth) {
      return text("#");
    }
    switch (type.op()) {
      case LIST:
        final List<Object> list = toList(value);
        if (list instanceof RelList || value instanceof TypedValue) {
          return text(RelList.RELATION);
        }
        return seqDoc("[", "]", elementDocs(type.elementType(), list, depth));

      case RECORD_TYPE:
        final RecordType recordType = (RecordType) type;
        final Iterator<Object> iterator = toList(value).iterator();
        final List<Doc> fields = new ArrayList<>();
        final int fieldDepth = depth + 1;
        recordType.argNameTypes.forEach(
            (name, fieldType) -> {
              final StringBuilder b = new StringBuilder();
              appendId(b, name).append('=');
              fields.add(
                  beside(
                      text(b.toString()),
                      valueDoc(fieldType, iterator.next(), fieldDepth)));
            });
        return seqDoc("{", "}", fields);

      case TUPLE_TYPE:
        final TupleType tupleType = (TupleType) type;
        final List<Doc> elements = new ArrayList<>();
        forEachIndexed(
            toList(value),
            tupleType.argTypes,
            (ordinal, o, elementType) ->
                elements.add(valueDoc(elementType, o, depth + 1)));
        return seqDoc("(", ")", elements);

      case FORALL_TYPE:
        return valueDoc(((ForallType) type).type, value, depth + 1);

      case DATA_TYPE:
        return dataTypeDoc((DataType) type, value, depth);

      default:
        return text(flatLeaf(value, type));
    }
  }

  /**
   * Builds a Doc for a value of a data type: a "bag" or "vector" lays out like
   * a list, an opaque value prints directly, and a constructor application
   * prints its name followed by its (possibly parenthesized) argument.
   */
  private Doc dataTypeDoc(DataType dataType, Object value, int depth) {
    if (!(value instanceof List)) {
      // A "doc" (pretty-printer document) is abstract; print it as "-", as
      // Standard ML prints a value of an abstract type. Other opaque values
      // (e.g. "time" backed by Long) print directly.
      return text(dataType.name.equals("doc") ? "-" : String.valueOf(value));
    }
    final List<Object> list = toList(value);
    if (dataType.name.equals("vector")) {
      return beside(
          text("#"),
          seqDoc("[", "]", elementDocs(dataType.arg(0), list, depth)));
    }
    if (dataType.isCollection()) {
      // A bag prints like a list, distinguishable only by its type.
      if (list instanceof RelList) {
        return text(RelList.RELATION);
      }
      final Type elementType = dataType.elementType();
      final List<Object> ordered = bagPrinter.order(list, elementType);
      return seqDoc("[", "]", elementDocs(elementType, ordered, depth));
    }
    final String tyConName = (String) list.get(0);
    if (list.size() < 2) {
      return text(tyConName); // nullary constructor, e.g. "LESS"
    }
    Object arg = list.get(1);
    if (arg instanceof Variant) {
      arg = ((Variant) arg).value;
    }
    if (dataType.name.equals("continuous_set")
        || dataType.name.equals("discrete_set")) {
      arg = Codes.setToRangeList(arg);
    }
    final Type argType = dataType.typeConstructors(typeSystem).get(tyConName);
    // Parens disambiguate when the arg is itself a multi-token constructor
    // (e.g. "SOME (INL x)"). The arg is at the same conceptual level as the
    // constructor, so its depth is not incremented.
    final boolean needParentheses =
        argType.op() == Op.DATA_TYPE
            && arg instanceof List
            && ((List<?>) arg).size() > 1;
    Doc argDoc = valueDoc(argType, arg, depth);
    if (needParentheses) {
      argDoc = beside(text("("), beside(argDoc, text(")")));
    }
    return beside(text(tyConName), beside(text(" "), argDoc));
  }

  /** Builds Docs for list elements, applying the {@code printLength} limit. */
  private List<Doc> elementDocs(
      Type elementType, List<Object> list, int depth) {
    final List<Doc> docs = new ArrayList<>();
    int i = 0;
    for (Object o : list) {
      if (printLength >= 0 && i >= printLength) {
        docs.add(text("..."));
        break;
      }
      docs.add(valueDoc(elementType, o, depth + 1));
      ++i;
    }
    return docs;
  }

  /**
   * Builds a Doc for a bracketed sequence (a list, record, or tuple). Renders
   * flat as {@code (a,b,c)}, and when it does not fit, with each element on its
   * own line, aligned under the first.
   */
  private Doc seqDoc(String open, String close, List<Doc> docs) {
    if (docs.isEmpty()) {
      return text(open + close);
    }
    // Elements fill across lines: as many as fit share a line, and each element
    // is treated as a unit (a record in a list of records stays together, and
    // the list wraps between records). There is no space after the comma, the
    // way SML/NJ prints list, tuple, and record values. Continuation lines
    // align under the first element.
    final List<Doc> items = new ArrayList<>();
    for (int i = 0; i < docs.size(); i++) {
      items.add(
          i < docs.size() - 1 ? beside(docs.get(i), text(",")) : docs.get(i));
    }
    return beside(text(open), beside(align(fill(EMPTY, items)), text(close)));
  }

  /**
   * Builds a Doc for a type. Record types fill their fields across lines (as
   * many per line as fit, aligned under the first field), the way SML/NJ wraps
   * a wide record type; other composite types follow type-operator precedence,
   * adding parentheses where needed.
   */
  private Doc typeDoc(Type type, int leftPrec, int rightPrec) {
    // Unlike a value (see valueDoc), a type keeps its alias: a value of type
    // "myInt" (an alias for "int") prints its type as "myInt", not "int".
    final Op op = type.op();
    switch (op) {
      case DATA_TYPE:
        if (type.isCollection()) {
          return collectionTypeDoc(
              type, leftPrec, rightPrec, BuiltIn.Eqtype.BAG.mlName());
        }
        // fall through
      case ID:
      case ALIAS_TYPE:
      case TY_VAR:
        return text(type.moniker());

      case LIST:
        return collectionTypeDoc(
            type, leftPrec, rightPrec, BuiltIn.Eqtype.LIST.mlName());

      case TUPLE_TYPE:
        if (op.wraps(leftPrec, rightPrec)) {
          return parenthesize(typeDoc(type, 0, 0));
        }
        // A tuple type fills across lines like SML/NJ, breaking before "*": the
        // "*" leads the continuation line. Each element after the first is
        // prefixed with "* "; packed with a single space, the result reads
        // "a * b * c".
        final List<Type> argTypes = ((TupleType) type).argTypes;
        final List<Doc> productItems = new ArrayList<>();
        for (int i = 0; i < argTypes.size(); i++) {
          final int leftPrec1 = i == 0 ? leftPrec : op.right;
          final int rightPrec1 = i == argTypes.size() - 1 ? rightPrec : op.left;
          final Doc argDoc = typeDoc(argTypes.get(i), leftPrec1, rightPrec1);
          productItems.add(i == 0 ? argDoc : beside(text("* "), argDoc));
        }
        // Continuation lines indent one column past the first element, as
        // SML/NJ does (the same "+1" offset as a record type).
        return align(nest(1, fill(text(" "), productItems)));

      case RECORD_TYPE:
      case PROGRESSIVE_RECORD_TYPE:
        final RecordType recordType = (RecordType) type;
        final List<Doc> fields = new ArrayList<>();
        recordType.argNameTypes.forEach(
            (name, fieldType) -> {
              final StringBuilder b = new StringBuilder();
              appendId(b, name).append(':');
              fields.add(beside(text(b.toString()), typeDoc(fieldType, 0, 0)));
            });
        if (type.isProgressive()) {
          fields.add(text("..."));
        }
        // Fields fill across lines, joined by ", " when packed, so as many as
        // fit share a line. Unlike record values, SML/NJ indents continuation
        // lines of a record type to one column past the first field (the "{"
        // column plus two), so we align and then nest by one.
        final List<Doc> fieldItems = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
          fieldItems.add(
              i < fields.size() - 1
                  ? beside(fields.get(i), text(","))
                  : fields.get(i));
        }
        final Doc fieldsDoc = fill(text(" "), fieldItems);
        return beside(text("{"), beside(align(nest(1, fieldsDoc)), text("}")));

      case FUNCTION_TYPE:
        if (op.wraps(leftPrec, rightPrec)) {
          return parenthesize(typeDoc(type, 0, 0));
        }
        // A function type breaks before "->", which leads the continuation
        // line (indented one column, like a record or tuple type). We must use
        // an explicit union rather than "group": the result may contain a
        // breakable structure (a tuple or collection type), and "group" would
        // defer to that inner break and leave this "->" flat, cramming the
        // whole type onto one line. Flattening the wide branch makes the fit
        // test honest, so the outermost "->" that does not fit breaks first,
        // matching SML/NJ.
        final FnType fnType = (FnType) type;
        final Doc paramDoc = typeDoc(fnType.paramType, leftPrec, op.left);
        final Doc resultDoc = typeDoc(fnType.resultType, op.right, rightPrec);
        return union(
            flatten(beside(paramDoc, beside(text(" -> "), resultDoc))),
            align(
                nest(
                    1,
                    beside(
                        paramDoc,
                        beside(HARD_LINE, beside(text("-> "), resultDoc))))));

      default:
        return text(type.moniker());
    }
  }

  /** Builds a Doc for a "list" or "bag" type, e.g. {@code int list}. */
  private Doc collectionTypeDoc(
      Type type, int leftPrec, int rightPrec, String typeName) {
    final Op op = Op.NAMED_TYPE;
    if (op.wraps(leftPrec, rightPrec)) {
      return parenthesize(typeDoc(type, 0, 0));
    }
    final Doc elementDoc = typeDoc(type.elementType(), leftPrec, op.left);
    // The "list"/"bag" keyword may break onto its own line when the element
    // type does not leave room for it.
    return align(beside(elementDoc, group(beside(LINE, text(typeName)))));
  }

  private static Doc parenthesize(Doc doc) {
    return beside(text("("), beside(doc, text(")")));
  }
}

// End Pretty.java
