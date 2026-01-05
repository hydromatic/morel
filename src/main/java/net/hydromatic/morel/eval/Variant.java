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
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.eval.Codes.appendFloat;
import static net.hydromatic.morel.eval.Codes.floatToString;
import static net.hydromatic.morel.eval.Codes.intToString;
import static net.hydromatic.morel.eval.Codes.optionSome;
import static net.hydromatic.morel.parse.Parsers.charToString;
import static net.hydromatic.morel.parse.Parsers.stringToString;
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.function.BiConsumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeCon;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.AbstractImmutableList;
import net.hydromatic.morel.util.ImmutablePairList;
import net.hydromatic.morel.util.PairList;
import org.apache.calcite.runtime.FlatLists;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A value with an explicit type.
 *
 * <p>This representation stores a Type alongside the actual Object value,
 * enabling efficient storage of homogeneous collections while maintaining full
 * type information.
 *
 * <p>For example:
 *
 * <ul>
 *   <li>{@code Variant(PrimitiveType.INT, 42)} represents an {@code int};
 *   <li>{@code Variant(ListType(INT), [1,2,3])} represents an {@code int list};
 *   <li>{@code Variant(ListType(ValueType), [Value(...), ...])} represents a
 *       heterogeneous value list
 * </ul>
 */
public class Variant extends AbstractImmutableList<Object> {
  private static final Variant UNIT_VARIANT =
      new Variant(PrimitiveType.UNIT, Unit.INSTANCE);

  public final Type type;
  public final Object value;

  private Variant(Type type, Object value) {
    this.type = requireNonNull(type, "type");
    this.value = requireNonNull(value, "value");
  }

  /** Creates a Value instance. */
  public static Variant of(Type type, Object value) {
    return new Variant(type, value);
  }

  /** Returns the {@code unit} instance. */
  public static Variant unit() {
    return UNIT_VARIANT;
  }

  /** Returns a variant that wraps a {@code bool}. */
  public static Variant ofBool(boolean b) {
    return new Variant(PrimitiveType.BOOL, b);
  }

  /** Returns a variant that wraps an {@code int}. */
  public static Variant ofInt(int i) {
    return new Variant(PrimitiveType.INT, i);
  }

  /** Returns a variant that wraps a {@code real}. */
  public static Variant ofReal(float v) {
    return new Variant(PrimitiveType.REAL, v);
  }

  /** Returns a variant that wraps a {@code string}. */
  public static Variant ofString(String s) {
    return new Variant(PrimitiveType.STRING, s);
  }

  /** Returns a variant that wraps a {@code char}. */
  public static Variant ofChar(char c) {
    return new Variant(PrimitiveType.CHAR, c);
  }

  /** Returns a variant that wraps a list with a given element type. */
  public static Variant ofList(
      TypeSystem typeSystem, Type elementType, List<Variant> list) {
    return new Variant(typeSystem.listType(elementType), list);
  }

  /**
   * Returns a variant that wraps a list of variants, perhaps with the same
   * element type.
   */
  public static Variant ofVariantList(
      TypeSystem typeSystem, List<Variant> variantList) {
    // Create a list value with the actual list type
    Type elementType = commonElementType(variantList);
    if (elementType != null) {
      final ListType listType = typeSystem.listType(elementType);
      final List<Object> list = transformEager(variantList, v -> v.value);
      return Variant.of(listType, list);
    } else {
      // If we can't determine a common element type, fall back to 'value'
      elementType = typeSystem.lookup(BuiltIn.Datatype.VARIANT);
      return Variant.ofList(typeSystem, elementType, variantList);
    }
  }

  /** Returns a variant that wraps a bag (treated as list for now). */
  public static Variant ofBag(
      TypeSystem typeSystem, Type elementType, List<?> list) {
    return new Variant(typeSystem.bagType(elementType), list);
  }

  /**
   * Returns a variant that wraps a bag of variants, perhaps with the same
   * element type.
   */
  public static Variant ofVariantBag(
      TypeSystem typeSystem, List<Variant> variantList) {
    // Create a bag value with the actual bag type
    Type elementType = commonElementType(variantList);
    if (elementType != null) {
      final Type bagType = typeSystem.bagType(elementType);
      final List<Object> list = transformEager(variantList, v -> v.value);
      return Variant.of(bagType, list);
    } else {
      // If we can't determine a common element type, fall back to 'variant'
      elementType = typeSystem.lookup(BuiltIn.Datatype.VARIANT);
      return Variant.ofBag(typeSystem, elementType, variantList);
    }
  }

  /** Returns a variant that wraps a vector (treated as list for now). */
  public static Variant ofVector(
      TypeSystem typeSystem, Type elementType, List<?> list) {
    // TODO: proper vector type when available
    return new Variant(typeSystem.vector(elementType), list);
  }

  /**
   * Returns a variant that wraps a vector of variants, perhaps with the same
   * element type.
   */
  public static Variant ofVariantVector(
      TypeSystem typeSystem, List<Variant> variantList) {
    // Create a vector value with the actual vector type
    Type elementType = commonElementType(variantList);
    if (elementType != null) {
      final Type vectorType = typeSystem.vector(elementType);
      final List<Object> list = transformEager(variantList, v -> v.value);
      return Variant.of(vectorType, list);
    } else {
      // If we can't determine a common element type, fall back to 'variant'
      elementType = typeSystem.lookup(BuiltIn.Datatype.VARIANT);
      return Variant.ofVector(typeSystem, elementType, variantList);
    }
  }

  /** Returns a variant that wraps an option NONE. */
  public static Variant ofNone(TypeSystem typeSystem, Type elementType) {
    return new Variant(typeSystem.option(elementType), Codes.OPTION_NONE);
  }

  /** Returns a variant that wraps an option SOME. */
  public static Variant ofSome(TypeSystem typeSystem, Variant variant) {
    return new Variant(
        typeSystem.option(variant.type), optionSome(variant.value));
  }

  /**
   * Returns a variant that is a call to a constant (zero-argument constructor).
   *
   * <p>For example, given the datatype declaration {@code datatype foo = BAR |
   * BAZ of int}, {@code CONSTANT "BAR"} returns a Variant with type {@code foo}
   * and value {@code "BAR"}.
   */
  public static Variant ofConstant(TypeSystem typeSystem, String conName) {
    TypeCon typeCon = typeSystem.lookupTyCon(conName);
    if (typeCon == null) {
      throw new IllegalArgumentException("Unknown constructor: " + conName);
    }
    return new Variant(typeCon.dataType, FlatLists.of(conName));
  }

  /**
   * Returns a variant that is a call to a constructor.
   *
   * <p>For example, given the datatype declaration {@code datatype foo = BAR |
   * BAZ of int}, {@code CONSTRUCT ("BAZ", INT 3)} returns a Variant with type
   * {@code foo} and value {@code ("BAZ", 3)}.
   *
   * <p>For parameterized datatypes like {@code datatype 'a option = NONE | SOME
   * of 'a}, this method uses unification to determine the type parameters. For
   * instance, {@code CONSTRUCT ("SOME", INT 42)} unifies {@code 'a} with {@code
   * int}, yielding type {@code int option}.
   */
  public static Variant ofConstructor(
      TypeSystem typeSystem, String conName, Variant conVariant) {
    @Nullable TypeCon typeCon = typeSystem.lookupTyCon(conName);
    if (typeCon == null) {
      throw new IllegalArgumentException("Unknown constructor: " + conName);
    }

    // Get the constructor's expected argument type (may contain type variables)
    final Type constructorArgType = typeCon.argTypeKey.toType(typeSystem);

    // Unify the expected type with the actual value's type to determine
    // type variable substitutions.
    final Map<Integer, Type> substitution =
        constructorArgType.unifyWith(conVariant.type);

    if (substitution == null) {
      throw new IllegalArgumentException(
          format(
              "Constructor %s expects argument of type %s but got %s",
              conName, constructorArgType, conVariant.type));
    }

    // Apply the substitution to the datatype to get the result type.
    // Build type args list in order by type variable ordinal, using the
    // substituted type if available, otherwise the original type variable.
    final ImmutableList.Builder<Type> typeArgsBuilder = ImmutableList.builder();
    for (int i = 0; i < typeCon.dataType.arguments.size(); i++) {
      final Type substitutedType = substitution.get(i);
      typeArgsBuilder.add(
          substitutedType != null
              ? substitutedType
              : typeCon.dataType.arguments.get(i));
    }
    final Type resultType =
        typeCon.dataType.substitute(typeSystem, typeArgsBuilder.build());

    // Unwrap the value before storing - consistent with optionSome behavior
    return new Variant(resultType, FlatLists.of(conName, conVariant.value));
  }

  public static Variant ofRecord(
      TypeSystem typeSystem, PairList<String, Variant> nameVariants) {
    final Type variantType = typeSystem.lookup(BuiltIn.Datatype.VARIANT);
    final ImmutablePairList<String, Variant> sortedNameVariants =
        nameVariants.withSortedKeys(Ordering.natural());
    final SortedMap<String, Variant> sortedNameVariantMap =
        sortedNameVariants.asSortedMap();
    if (nameVariants.noneMatch(
        (name, variant) -> variant.type == variantType)) {
      // None of the values are variants. Create a record over the raw values.
      final SortedMap<String, Type> nameTypes =
          Maps.transformValues(sortedNameVariantMap, v -> v.type);
      final Type recordType = typeSystem.recordType(nameTypes);
      final List<Object> rawValues =
          sortedNameVariants.transformEager((name, variant) -> variant.value);
      return new Variant(recordType, rawValues);
    } else {
      // Create a record whose fields all have type 'variant'.
      final SortedMap<String, Type> nameTypes =
          Maps.transformValues(sortedNameVariantMap, v -> variantType);
      final Type recordType = typeSystem.recordType(nameTypes);
      return new Variant(recordType, sortedNameVariants.rightList());
    }
  }

  public static boolean bindConsPat(
      BiConsumer<Core.NamedPat, Object> envRef,
      Variant variant,
      Core.ConPat consPat) {
    @SuppressWarnings("unchecked")
    final List<Object> consValue = (List<Object>) variant.value;
    if (consValue.isEmpty()) {
      return false;
    }
    final Type elementType = variant.type.elementType();
    final Variant head = of(elementType, consValue.get(0));
    final List<Variant> tail =
        transformEager(
            skip(consValue),
            e -> e instanceof Variant ? (Variant) e : of(elementType, e));
    List<Core.Pat> patArgs = ((Core.TuplePat) consPat.pat).args;
    return Closure.bindRecurse(patArgs.get(0), head, envRef)
        && Closure.bindRecurse(patArgs.get(1), tail, envRef);
  }

  public static boolean bindConPat(
      BiConsumer<Core.NamedPat, Object> envRef,
      Variant variant,
      Core.ConPat conPat) {
    if (!variant.constructor().constructor.equals(conPat.tyCon)) {
      return false;
    }
    // For list types, rewrap elements as Variants if needed.
    // For record types, reconstruct (name, value) pairs.
    Object innerValue;
    if (variant.type instanceof ListType) {
      final List<?> list = (List<?>) variant.value;
      // Check if elements are already Variants
      if (!list.isEmpty() && !(list.get(0) instanceof Variant)) {
        // Elements are unwrapped, rewrap them
        innerValue =
            transformEager(
                list,
                e ->
                    e instanceof Variant
                        ? (Variant) e
                        : of(variant.type.elementType(), e));
      } else {
        innerValue = variant.value;
      }
    } else if (variant.type instanceof RecordType) {
      final RecordType recordType = (RecordType) variant.type;
      final List<?> fieldValues = (List<?>) variant.value;
      // Reconstruct (name, variant) pairs for pattern matching.
      // Pattern expects a list of [name, variant] pairs.
      final ImmutableList.Builder<List<Object>> pairsBuilder =
          ImmutableList.builder();
      int i = 0;
      for (Map.Entry<String, Type> entry : recordType.argNameTypes.entrySet()) {
        final String name = entry.getKey();
        final Type fieldType = entry.getValue();
        final Object rawValue = fieldValues.get(i++);
        final Variant fieldValue =
            rawValue instanceof Variant
                ? (Variant) rawValue
                : of(fieldType, rawValue);
        pairsBuilder.add(ImmutableList.of(name, fieldValue));
      }
      innerValue = pairsBuilder.build();
    } else {
      innerValue = variant.value;
    }
    return Closure.bindRecurse(conPat.pat, innerValue, envRef);
  }

  /** Returns this variant's constructor name. */
  public BuiltIn.Constructor constructor() {
    // Primitive types
    if (type.op() == Op.ID) {
      switch ((PrimitiveType) type) {
        case UNIT:
          return BuiltIn.Constructor.VARIANT_UNIT;
        case BOOL:
          return BuiltIn.Constructor.VARIANT_BOOL;
        case INT:
          return BuiltIn.Constructor.VARIANT_INT;
        case REAL:
          return BuiltIn.Constructor.VARIANT_REAL;
        case CHAR:
          return BuiltIn.Constructor.VARIANT_CHAR;
        case STRING:
          return BuiltIn.Constructor.VARIANT_STRING;
      }
    } else {
      // List types - we can't distinguish LIST, BAG, VECTOR from type alone
      // For now, assume LIST (this is a limitation)
      if (type instanceof ListType) {
        return BuiltIn.Constructor.VARIANT_LIST;
      }

      // DataType for option
      if (type instanceof DataType) {
        final DataType dataType = (DataType) type;
        if (dataType.name.equals("option")) {
          return value == Codes.OPTION_NONE
              ? BuiltIn.Constructor.VARIANT_NONE
              : BuiltIn.Constructor.VARIANT_SOME;
        }
      }

      // Record types
      if (type instanceof RecordType) {
        return BuiltIn.Constructor.VARIANT_RECORD;
      }
    }

    // For other types, we can't determine a simple constructor name
    throw new IllegalArgumentException("unknown type " + type);
  }

  /**
   * Converts this Variant to a list with tag and value, the same format used
   * for other sum types in Morel.
   */
  private List<Object> toFlatList() {
    String tag = tag().constructor;
    return type == PrimitiveType.UNIT
        ? FlatLists.of(tag)
        : FlatLists.of(tag, this);
  }

  @Override
  public int size() {
    // Equivalent to "toFlatList().size()"
    return type == PrimitiveType.UNIT ? 1 : 2;
  }

  @Override
  public Object[] toArray() {
    // Equivalent to "toFlatList().toArray()"
    String tag = tag().constructor;
    return type == PrimitiveType.UNIT
        ? new Object[] {tag}
        : new Object[] {tag, this};
  }

  @Override
  public <T> T[] toArray(T[] a) {
    return toFlatList().toArray(a);
  }

  @Override
  public ListIterator<Object> listIterator() {
    return toFlatList().listIterator();
  }

  @Override
  public Iterator<Object> iterator() {
    return toFlatList().iterator();
  }

  @Override
  public ListIterator<Object> listIterator(int index) {
    return toFlatList().listIterator(index);
  }

  @Override
  public List<Object> subList(int fromIndex, int toIndex) {
    if (fromIndex == 1 && toIndex == 2 && type != PrimitiveType.UNIT) {
      // Equivalent to "toFlatList().subList(fromIndex, toIndex)", but optimized
      return FlatLists.of(this);
    }
    return toFlatList().subList(fromIndex, toIndex);
  }

  @Override
  public Object get(int index) {
    // Equivalent to "toFlatList().get(index)", but optimized
    switch (index) {
      case 0:
        return tag().constructor;
      case 1:
        if (type != PrimitiveType.UNIT) {
          return this;
        }
        // fall through
      default:
        throw new IndexOutOfBoundsException(
            "Index: " + index + ", Size: " + size());
    }
  }

  @Override
  public int indexOf(Object o) {
    return toFlatList().indexOf(o);
  }

  @Override
  public int lastIndexOf(Object o) {
    return toFlatList().lastIndexOf(o);
  }

  @Override
  public boolean equals(Object obj) {
    return this == obj
        || obj instanceof Variant
            // Logical equality: compare the actual values, not the type
            // representation. This means refined and unrefined variants are
            // equal if they represent the same logical value. For now, delegate
            // to value equality. In the future, we may need to normalize both
            // sides (e.g., unwrap collections) for true logical equality.
            && logicallyEqual(this, (Variant) obj);
  }

  /**
   * Checks logical equality between two variants.
   *
   * <p>Refined and unrefined representations of the same logical value should
   * be equal. For example:
   *
   * <ul>
   *   <li>{@code Variant(ListType(INT), [1,2])} (refined)
   *   <li>{@code Variant(ListType(VariantType), [Variant(INT,1),
   *       Variant(INT,2)])} (unrefined)
   * </ul>
   *
   * <p>These are logically equal even though they have different type and value
   * representations.
   */
  private static boolean logicallyEqual(Variant v1, Variant v2) {
    // For now, use structural equality on the wrapped values.
    // TODO: Implement proper logical equality that handles refined vs unrefined
    return Objects.equals(v1.value, v2.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, value);
  }

  @Override
  public String toString() {
    return "Variant(" + type + ", " + value + ")";
  }

  /** Returns the constructor tag for this variant. */
  private BuiltIn.Constructor tag() {
    switch (type.op()) {
      case ID:
        switch ((PrimitiveType) type) {
          case UNIT:
            return BuiltIn.Constructor.VARIANT_UNIT;
          case BOOL:
            return BuiltIn.Constructor.VARIANT_BOOL;
          case INT:
            return BuiltIn.Constructor.VARIANT_INT;
          case REAL:
            return BuiltIn.Constructor.VARIANT_REAL;
          case CHAR:
            return BuiltIn.Constructor.VARIANT_CHAR;
          case STRING:
            return BuiltIn.Constructor.VARIANT_STRING;
          default:
            throw new IllegalArgumentException(
                "No constructor for primitive type: " + type);
        }

      case LIST:
        return BuiltIn.Constructor.VARIANT_LIST;

      case RECORD_TYPE:
      case TUPLE_TYPE:
        return BuiltIn.Constructor.VARIANT_RECORD;

      case DATA_TYPE:
        final DataType dataType = (DataType) type;
        if (dataType.name.equals("option")) {
          return value == Codes.OPTION_NONE
              ? BuiltIn.Constructor.VARIANT_NONE
              : BuiltIn.Constructor.VARIANT_SOME;
        }
        return BuiltIn.Constructor.VARIANT_CONSTRUCT;

      default:
        throw new IllegalArgumentException(
            "No constructor for primitive type: " + type);
    }
  }

  private static @Nullable Type commonElementType(List<Variant> list) {
    if (list.isEmpty()) {
      return null;
    }
    Type commonType = list.get(0).type;
    for (int i = 1; i < list.size(); i++) {
      final Type currentType = list.get(i).type;
      if (!commonType.equals(currentType)) {
        return null; // No common type
      }
    }
    return commonType;
  }

  /**
   * Converts a Variant to its string representation.
   *
   * <p>Handles both refined (specific types like {@code int list}) and
   * unrefined (general types like {@code variant list}) representations.
   *
   * @see net.hydromatic.morel.compile.BuiltIn#VARIANT_PRINT
   */
  public String print() {
    // Handle primitive types
    if (type.op() == Op.ID) {
      switch ((PrimitiveType) type) {
        case UNIT:
          return "UNIT";
        case BOOL:
          return "BOOL " + value;
        case INT:
          final int intVal = (Integer) value;
          return "INT " + intToString(intVal);
        case REAL:
          final float realVal = (Float) value;
          return "REAL " + floatToString(realVal);
        case CHAR:
          final char ch = (Character) value;
          return "CHAR #\"" + charToString(ch) + "\"";
        case STRING:
          final String str = (String) value;
          return "STRING \"" + stringToString(str) + "\"";
        default:
          throw new AssertionError();
      }
    }

    // For more complex variants, delegate to append. A single StringBuilder
    // avoids excessive string concatenation.
    StringBuilder buf = new StringBuilder();
    append(buf);
    return buf.toString();
  }

  /**
   * Appends the string representation of this Variant to a StringBuilder.
   *
   * @see #print()
   */
  private StringBuilder append(StringBuilder buf) {
    if (type.op() == Op.ID) {
      switch ((PrimitiveType) type) {
        case UNIT:
          return buf.append("UNIT");
        case BOOL:
          return buf.append("BOOL ").append(value);
        case INT:
          final int intVal = (Integer) value;
          return buf.append("INT ").append(intToString(intVal));
        case REAL:
          buf.append("REAL ");
          final float realVal = (Float) value;
          return appendFloat(buf, realVal);
        case CHAR:
          final char ch = (Character) value;
          return buf.append("CHAR #\"").append(charToString(ch)).append("\"");
        case STRING:
          final String str = (String) value;
          buf.append("STRING \"");
          stringToString(str, buf);
          return buf.append("\"");
        default:
          throw new AssertionError();
      }
    }

    // Handle list types
    if (type instanceof ListType) {
      return appendList(buf, "LIST [", value, type.elementType(), "]");
    }

    // Handle DataTypes (bag, vector, option, custom datatypes)
    if (type instanceof DataType) {
      final DataType dataType = (DataType) type;

      final Type elementType;
      switch (dataType.name) {
        case "bag":
          elementType = dataType.arguments.get(0);
          return appendList(buf, "BAG [", value, elementType, "]");

        case "vector":
          elementType = dataType.arguments.get(0);
          return appendList(buf, "VECTOR [", value, elementType, "]");

        case "option":
          if (value == Codes.OPTION_NONE) {
            return buf.append("VARIANT_NONE");
          } else if (value instanceof Variant) {
            buf.append("VARIANT_SOME ");
            return ((Variant) value).append(buf);
          } else if (value instanceof List) {
            // Refined option stored as ["SOME", innerValue]
            final List<?> optionList = (List<?>) value;
            if (optionList.size() == 2 && "SOME".equals(optionList.get(0))) {
              final Object innerVal = optionList.get(1);
              final Type innerType = dataType.arguments.get(0);
              final Variant innerVariant =
                  innerVal instanceof Variant
                      ? (Variant) innerVal
                      : Variant.of(innerType, innerVal);
              buf.append("VARIANT_SOME ");
              return innerVariant.append(buf);
            }
            throw new IllegalArgumentException(
                "Invalid option value: " + optionList);
          } else {
            // Refined option: single value (SOME case)
            final Type innerType = dataType.arguments.get(0);
            buf.append("VARIANT_SOME ");
            return Variant.of(innerType, value).append(buf);
          }
      }

      // Handle other datatype constructors
      if (value instanceof List) {
        final List<?> conList = (List<?>) value;
        if (!conList.isEmpty() && conList.get(0) instanceof String) {
          final String conName = (String) conList.get(0);
          if (conList.size() == 1) {
            // Nullary constructor - use CONSTANT
            return buf.append("CONSTANT \"").append(conName).append("\"");
          } else if (conList.size() == 2) {
            // Unary constructor - use CONSTRUCT
            final Object conArg = conList.get(1);
            // conArg should be unwrapped due to ofConstructor change
            // Need to determine its type - use datatype's argument type
            final Variant conArgVariant;
            if (conArg instanceof Variant) {
              conArgVariant = (Variant) conArg;
            } else {
              // Unwrapped value - wrap it with the datatype's argument type
              final Type argType =
                  dataType.arguments.isEmpty()
                      ? PrimitiveType.UNIT
                      : dataType.arguments.get(0);
              conArgVariant = Variant.of(argType, conArg);
            }
            buf.append("CONSTRUCT (\"").append(conName).append("\", ");
            conArgVariant.append(buf);
            return buf.append(')');
          }
        }
      }
      throw new IllegalArgumentException(
          "Cannot print datatype value: " + dataType.name);
    }

    // Handle record types
    if (type instanceof RecordType) {
      final RecordType recordType = (RecordType) type;
      @SuppressWarnings("unchecked")
      final List<Object> recordValues = (List<Object>) value;
      return appendRecordPairs(buf, "RECORD [", recordType, recordValues, "]");
    }

    throw new IllegalArgumentException("Cannot print variant of type: " + type);
  }

  /**
   * Helper for print: prints a list of Variant instances (unrefined) or raw
   * values (refined).
   */
  @SuppressWarnings({"SameParameterValue", "rawtypes", "unchecked"})
  private static StringBuilder appendList(
      StringBuilder buf,
      String prefix,
      Object val,
      Type elementType,
      String suffix) {
    buf.append(prefix);
    final List list = (List) val;
    if (!list.isEmpty() && list.get(0) instanceof Variant) {
      final List<Variant> variants = (List<Variant>) list;
      for (int i = 0; i < variants.size(); i++) {
        if (i > 0) {
          buf.append(", ");
        }
        variants.get(i).append(buf);
      }
    } else {
      final List<Object> values = (List<Object>) list;
      for (int i = 0; i < values.size(); i++) {
        if (i > 0) {
          buf.append(", ");
        }
        // Wrap each raw value in a Variant instance for printing.
        Variant.of(elementType, values.get(i)).append(buf);
      }
    }
    buf.append(suffix);
    return buf;
  }

  /** Helper for print: prints record as list of (name, value) pairs. */
  @SuppressWarnings("SameParameterValue")
  private static StringBuilder appendRecordPairs(
      StringBuilder buf,
      String prefix,
      RecordType recordType,
      List<Object> values,
      String suffix) {
    buf.append(prefix);
    final Map<String, Type> fields = recordType.argNameTypes();
    int i = 0;
    for (Map.Entry<String, Type> entry : fields.entrySet()) {
      if (i > 0) {
        buf.append(", ");
      }
      final String fieldName = entry.getKey();
      final Type fieldType = entry.getValue();
      final Object fieldValue = values.get(i);

      buf.append("(\"").append(fieldName).append("\", ");
      Variant.of(fieldType, fieldValue).append(buf);
      buf.append(")");
      i++;
    }
    buf.append(suffix);
    return buf;
  }
}

// End Variant.java
