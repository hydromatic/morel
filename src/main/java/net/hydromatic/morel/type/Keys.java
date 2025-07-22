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
package net.hydromatic.morel.type;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Maps.transformValues;
import static java.util.Objects.hash;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.parse.Parsers.appendId;
import static net.hydromatic.morel.util.Static.transformEager;
import static net.hydromatic.morel.util.Static.transformValuesEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.util.AbstractList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.function.UnaryOperator;
import net.hydromatic.morel.ast.Op;

/** Type keys. */
public class Keys {
  private Keys() {}

  /** Returns a key that identifies types by name. */
  public static Type.Key name(String name) {
    return new NameKey(name);
  }

  /** Returns a key to the dummy type. */
  public static Type.Key dummy() {
    return name("");
  }

  /** Returns a key that gives a name to an existing type. */
  public static Type.Key alias(
      String name, Type.Key key, List<? extends Type.Key> arguments) {
    return new AliasKey(name, key, ImmutableList.copyOf(arguments));
  }

  /**
   * Returns a key that identifies types (especially {@link TypeVar type
   * variables}) by ordinal.
   */
  public static Type.Key ordinal(int ordinal) {
    return new OrdinalKey(ordinal);
  }

  /**
   * Returns a list of keys for type variables 0 through size - 1.
   *
   * @see TypeSystem#typeVariables(int)
   */
  public static List<Type.Key> ordinals(int size) {
    return new AbstractList<Type.Key>() {
      public int size() {
        return size;
      }

      public Type.Key get(int index) {
        return new OrdinalKey(index);
      }
    };
  }

  /** Returns a key that applies a polymorphic type to arguments. */
  public static Type.Key apply(
      Type.Key type, Iterable<? extends Type.Key> args) {
    return new ApplyKey(type, ImmutableList.copyOf(args));
  }

  /**
   * Returns a key that identifies a {@link RecordType} (or a {@link TupleType}
   * if the field names are ascending integers, or {@link PrimitiveType#UNIT
   * unit} if the fields are empty).
   */
  public static Type.Key record(
      SortedMap<String, ? extends Type.Key> argNameTypes) {
    return new RecordKey(ImmutableSortedMap.copyOfSorted(argNameTypes));
  }

  /** As {@link #record(SortedMap)} but an {@link Iterable} argument. */
  public static Type.Key record(
      Iterable<Map.Entry<String, ? extends Type.Key>> argNameTypes) {
    return record(ImmutableSortedMap.copyOf(argNameTypes, RecordType.ORDERING));
  }

  /** Returns a key that identifies a {@link TupleType}. */
  public static Type.Key tuple(List<? extends Type.Key> args) {
    return new RecordKey(TupleType.recordMap(args));
  }

  /** Returns a key that identifies a {@link ProgressiveRecordType}. */
  public static Type.Key progressiveRecord(
      SortedMap<String, ? extends Type.Key> argNameTypes) {
    return new ProgressiveRecordKey(
        ImmutableSortedMap.copyOfSorted(argNameTypes));
  }

  /** Returns a key that identifies a {@link FnType}. */
  public static Type.Key fn(Type.Key paramType, Type.Key resultType) {
    return new FnKey(paramType, resultType);
  }

  /** Returns a key that identifies a {@link ListType}. */
  public static Type.Key list(Type.Key elementType) {
    return new ListKey(elementType);
  }

  /** Returns a key that identifies a {@link ForallType}. */
  public static Type.Key forall(Type type, int parameterCount) {
    return new ForallKey(type, parameterCount);
  }

  /**
   * Returns a key that identifies a {@link DataType}.
   *
   * <p>Iteration order of the type constructors ({@code typeConstructors}) is
   * significant. We recommend that you use a sequenced map such as {@link
   * ImmutableMap} or {@link LinkedHashMap}.
   */
  public static DataTypeKey datatype(
      String name,
      List<? extends Type.Key> arguments,
      Map<String, Type.Key> typeConstructors) {
    return new DataTypeKey(
        name,
        ImmutableList.copyOf(arguments),
        ImmutableMap.copyOf(typeConstructors));
  }

  /** Returns a key that identifies a {@link MultiType}. */
  public static Type.Key multi(List<? extends Type.Key> keys) {
    return new MultiTypeKey(ImmutableList.copyOf(keys));
  }

  /** Converts a map of types to a map of keys. */
  public static SortedMap<String, Type.Key> toKeys(
      SortedMap<String, ? extends Type> nameTypes) {
    final ImmutableSortedMap.Builder<String, Type.Key> keys =
        ImmutableSortedMap.orderedBy(RecordType.ORDERING);
    nameTypes.forEach((name, t) -> keys.put(name, t.key()));
    return keys.build();
  }

  /** Converts a list of types to a list of keys. */
  public static List<Type.Key> toKeys(List<? extends Type> types) {
    return transformEager(types, Type::key);
  }

  /** Describes a record, progressive record, or tuple type. */
  static StringBuilder describeRecordType(
      StringBuilder buf,
      int left,
      int right,
      SortedMap<String, Type.Key> argNameTypes,
      Op op) {
    switch (argNameTypes.size()) {
      case 0:
        return buf.append(op == Op.PROGRESSIVE_RECORD_TYPE ? "{...}" : "()");

      default:
        if (op == Op.TUPLE_TYPE) {
          return TypeSystem.unparseList(
              buf, Op.TIMES, left, right, argNameTypes.values());
        }
        // fall through
      case 1:
        buf.append('{');
        int i = 0;
        for (Map.Entry<String, Type.Key> entry : argNameTypes.entrySet()) {
          String name = entry.getKey();
          Type.Key typeKey = entry.getValue();
          if (i++ > 0) {
            buf.append(", ");
          }
          appendId(buf, name).append(':').append(typeKey);
        }
        if (op == Op.PROGRESSIVE_RECORD_TYPE) {
          if (i > 0) {
            buf.append(", ");
          }
          buf.append("...");
        }
        return buf.append('}');
    }
  }

  /**
   * Converts a record key to a progressive record key, leaves other keys
   * unchanged.
   */
  public static Type.Key toProgressive(Type.Key key) {
    if (key instanceof RecordKey) {
      return progressiveRecord(((RecordKey) key).argNameTypes);
    }
    return key;
  }

  static StringBuilder describeParameterized(
      StringBuilder buf, String name, List<Type.Key> arguments) {
    if (arguments.isEmpty()) {
      return buf.append(name);
    }
    if (arguments.size() > 1) {
      buf.append('(');
    }
    final int length = buf.length();
    for (Type.Key key : arguments) {
      if (buf.length() > length) {
        buf.append(",");
      }
      if (key.op == Op.TUPLE_TYPE) {
        buf.append('(');
      }
      key.describe(buf, 0, 0);
      if (key.op == Op.TUPLE_TYPE) {
        buf.append(')');
      }
    }
    if (arguments.size() > 1) {
      buf.append(')');
    }
    return buf.append(' ').append(name);
  }

  /** Key that identifies a type by name. */
  private static class NameKey extends Type.Key {
    private final String name;

    NameKey(String name) {
      super(name.isEmpty() ? Op.DUMMY_TYPE : Op.DATA_TYPE);
      this.name = requireNonNull(name);
    }

    @Override
    public String toString() {
      return name;
    }

    @Override
    public StringBuilder describe(StringBuilder buf, int left, int right) {
      return buf.append(name);
    }

    @Override
    public int hashCode() {
      return name.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || obj instanceof NameKey && ((NameKey) obj).name.equals(name);
    }

    @Override
    public Type toType(TypeSystem typeSystem) {
      if (name.isEmpty()) {
        return DummyType.INSTANCE;
      }
      return typeSystem.lookup(name);
    }
  }

  /** Key that gives a new alias to an existing type. */
  private static class AliasKey extends Type.Key {
    private final String name;
    private final Type.Key key;
    private final ImmutableList<Type.Key> arguments;

    AliasKey(String name, Type.Key key, ImmutableList<Type.Key> arguments) {
      super(Op.ALIAS_TYPE);
      this.name = requireNonNull(name);
      this.key = key;
      this.arguments = requireNonNull(arguments);
    }

    @Override
    public String toString() {
      return name;
    }

    @Override
    public StringBuilder describe(StringBuilder buf, int left, int right) {
      return describeParameterized(buf, name, arguments);
    }

    @Override
    public int hashCode() {
      return name.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || obj instanceof AliasKey
              && ((AliasKey) obj).name.equals(name)
              && ((AliasKey) obj).key.equals(key);
    }

    @Override
    public Type toType(TypeSystem typeSystem) {
      return typeSystem.aliasType(
          name, key.toType(typeSystem), typeSystem.typesFor(arguments));
    }
  }

  /** Key that identifies a type by ordinal. */
  private static class OrdinalKey extends Type.Key {
    final int ordinal;

    OrdinalKey(int ordinal) {
      super(Op.TY_VAR);
      this.ordinal = ordinal;
    }

    @Override
    public String toString() {
      return TypeVar.name(ordinal);
    }

    @Override
    public StringBuilder describe(StringBuilder buf, int left, int right) {
      return buf.append(TypeVar.name(ordinal));
    }

    @Override
    public int hashCode() {
      return ordinal;
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || obj instanceof OrdinalKey && ((OrdinalKey) obj).ordinal == ordinal;
    }

    @Override
    public Type.Key substitute(List<? extends Type> types) {
      return types.get(ordinal).key();
    }

    public Type toType(TypeSystem typeSystem) {
      return new TypeVar(ordinal);
    }
  }

  /**
   * Key of a type that applies a parameterized type to specific type arguments.
   */
  private static class ApplyKey extends Type.Key {
    final Type.Key key;
    final ImmutableList<Type.Key> args;

    ApplyKey(Type.Key key, List<Type.Key> args) {
      super(Op.APPLY_TYPE);
      this.key = requireNonNull(key);
      this.args = ImmutableList.copyOf(args);
    }

    @Override
    public StringBuilder describe(StringBuilder buf, int left, int right) {
      if (!args.isEmpty()) {
        TypeSystem.unparseList(buf, Op.COMMA, left, Op.APPLY.left, args);
        buf.append(Op.APPLY.padded);
      }
      return buf.append(key);
    }

    @Override
    public int hashCode() {
      return hash(key, args);
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || obj instanceof ApplyKey
              && ((ApplyKey) obj).key.equals(key)
              && ((ApplyKey) obj).args.equals(args);
    }

    @Override
    public Type toType(TypeSystem typeSystem) {
      final Type type = key.toType(typeSystem);
      if (type instanceof ForallType) {
        return type.substitute(typeSystem, typeSystem.typesFor(args));
      }
      throw new AssertionError();
    }

    @Override
    public Type.Key copy(UnaryOperator<Type.Key> transform) {
      return new ApplyKey(
          key.copy(transform),
          transformEager(args, arg -> arg.copy(transform)));
    }
  }

  /**
   * Key of a type that applies a built-in type constructor to specific type
   * arguments.
   */
  private abstract static class OpKey extends Type.Key {
    final ImmutableList<Type.Key> args;

    OpKey(Op op, List<Type.Key> args) {
      super(op);
      this.args = ImmutableList.copyOf(args);
    }

    @Override
    public StringBuilder describe(StringBuilder buf, int left, int right) {
      return TypeSystem.unparseList(buf, op, left, right, args);
    }

    @Override
    public int hashCode() {
      return hash(op, args);
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || obj instanceof OpKey
              && ((OpKey) obj).op.equals(op)
              && ((OpKey) obj).args.equals(args);
    }
  }

  /** Key of a list type. */
  private static class ListKey extends OpKey {
    ListKey(Type.Key arg) {
      super(Op.LIST, ImmutableList.of(arg));
    }

    @Override
    public StringBuilder describe(StringBuilder buf, int left, int right) {
      return TypeSystem.unparse(buf, args.get(0), 0, Op.LIST.right)
          .append(" list");
    }

    @Override
    Type.Key copy(UnaryOperator<Type.Key> transform) {
      return super.copy(transform);
    }

    @Override
    public Type toType(TypeSystem typeSystem) {
      return new ListType(typeSystem.typeFor(args.get(0)));
    }
  }

  /** Key of a function type. */
  private static class FnKey extends OpKey {
    FnKey(Type.Key paramType, Type.Key resultType) {
      super(Op.FN, ImmutableList.of(paramType, resultType));
      checkArgument(args.size() == 2);
    }

    @Override
    public Type toType(TypeSystem typeSystem) {
      return new FnType(
          typeSystem.typeFor(args.get(0)), typeSystem.typeFor(args.get(1)));
    }
  }

  /** Key of a forall type. */
  private static class ForallKey extends Type.Key {
    final Type type;
    final int parameterCount;

    ForallKey(Type type, int parameterCount) {
      super(Op.FORALL_TYPE);
      this.type = requireNonNull(type);
      this.parameterCount = parameterCount;
      checkArgument(parameterCount >= 0);
    }

    @Override
    public int hashCode() {
      return hash(type, parameterCount);
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || obj instanceof ForallKey
              && ((ForallKey) obj).type.equals(type)
              && ((ForallKey) obj).parameterCount == parameterCount;
    }

    @Override
    public StringBuilder describe(StringBuilder buf, int left, int right) {
      buf.append("forall");
      for (int i = 0; i < parameterCount; i++) {
        buf.append(' ').append(TypeVar.name(i));
      }
      buf.append(". ");
      return TypeSystem.unparse(buf, type.key(), 0, 0);
    }

    public Type toType(TypeSystem typeSystem) {
      return new ForallType(parameterCount, type);
    }
  }

  /** Key of a record type. */
  private static class RecordKey extends Type.Key {
    final ImmutableSortedMap<String, Type.Key> argNameTypes;

    RecordKey(ImmutableSortedMap<String, Type.Key> argNameTypes) {
      super(
          TypeSystem.areContiguousIntegers(argNameTypes.keySet())
              ? Op.TUPLE_TYPE
              : Op.RECORD_TYPE);
      this.argNameTypes = requireNonNull(argNameTypes);
      checkArgument(argNameTypes.comparator() == RecordType.ORDERING);
    }

    @Override
    public Type.Key copy(UnaryOperator<Type.Key> transform) {
      return record(transformValues(argNameTypes, transform::apply));
    }

    @Override
    public StringBuilder describe(StringBuilder buf, int left, int right) {
      return describeRecordType(buf, left, right, argNameTypes, op);
    }

    @Override
    public int hashCode() {
      return argNameTypes.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || obj instanceof RecordKey
              && ((RecordKey) obj).argNameTypes.equals(argNameTypes);
    }

    public Type toType(TypeSystem typeSystem) {
      switch (argNameTypes.size()) {
        case 0:
          return PrimitiveType.UNIT;
        default:
          if (op == Op.TUPLE_TYPE) {
            return new TupleType(typeSystem.typesFor(argNameTypes.values()));
          }
          // fall through
        case 1:
          return new RecordType(typeSystem.typesFor(argNameTypes));
      }
    }
  }

  /** Key that identifies a {@link ProgressiveRecordType}. */
  private static class ProgressiveRecordKey extends Type.Key {
    final ImmutableSortedMap<String, Type.Key> argNameTypes;

    ProgressiveRecordKey(ImmutableSortedMap<String, Type.Key> argNameTypes) {
      super(Op.PROGRESSIVE_RECORD_TYPE);
      this.argNameTypes = requireNonNull(argNameTypes);
    }

    @Override
    public Type.Key copy(UnaryOperator<Type.Key> transform) {
      return progressiveRecord(transformValues(argNameTypes, transform::apply));
    }

    @Override
    public StringBuilder describe(StringBuilder buf, int left, int right) {
      return describeRecordType(buf, left, right, argNameTypes, op);
    }

    @Override
    public int hashCode() {
      return argNameTypes.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || obj instanceof ProgressiveRecordKey
              && ((ProgressiveRecordKey) obj).argNameTypes.equals(argNameTypes);
    }

    @Override
    public Type toType(TypeSystem typeSystem) {
      return new ProgressiveRecordType(typeSystem.typesFor(this.argNameTypes));
    }
  }

  /** Key that identifies a {@link MultiType}. */
  private static class MultiTypeKey extends Type.Key {
    private final List<Type.Key> args;

    MultiTypeKey(ImmutableList<Type.Key> args) {
      super(Op.MULTI_TYPE);
      this.args = requireNonNull(args);
    }

    @Override
    StringBuilder describe(StringBuilder buf, int left, int right) {
      buf.append("multi(");
      TypeSystem.unparseList(buf, Op.COMMA, 0, 0, args);
      return buf.append(')');
    }

    @Override
    public Type toType(TypeSystem typeSystem) {
      return new MultiType(typeSystem.typesFor(args));
    }
  }

  /** Key that identifies a {@code datatype} scheme. */
  public static class DataTypeKey extends Type.Key {
    /**
     * Ideally, a datatype would not have a name, just a list of named type
     * constructors, and the name would be associated later. When that happens,
     * we can remove the {@code name} field from this key.
     */
    private final String name;

    private final ImmutableList<Type.Key> arguments;
    private final ImmutableMap<String, Type.Key> typeConstructors;

    DataTypeKey(
        String name,
        ImmutableList<Type.Key> arguments,
        ImmutableMap<String, Type.Key> typeConstructors) {
      super(Op.DATA_TYPE);
      this.name = requireNonNull(name);
      this.arguments = requireNonNull(arguments);
      this.typeConstructors = requireNonNull(typeConstructors);
    }

    @Override
    public int hashCode() {
      return hash(name, arguments, typeConstructors);
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || obj instanceof DataTypeKey
              && ((DataTypeKey) obj).name.equals(name)
              && ((DataTypeKey) obj).arguments.equals(arguments)
              && ((DataTypeKey) obj).typeConstructors.equals(typeConstructors);
    }

    @Override
    Type.Key substitute(List<? extends Type> types) {
      ImmutableList<Type.Key> arguments =
          transformEager(this.arguments, arg -> arg.substitute(types));
      ImmutableMap<String, Type.Key> typeConstructors =
          transformValuesEager(
              this.typeConstructors, arg -> arg.substitute(types));
      if (arguments.equals(this.arguments)
          && typeConstructors.equals(this.typeConstructors)) {
        return this;
      }
      return new DataTypeKey(name, arguments, typeConstructors);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Prints the name of this datatype along with any type arguments.
     * Examples:
     *
     * <ul>
     *   <li>{@code order}
     *   <li>{@code 'a option}
     *   <li>{@code (int * int) option}
     *   <li>{@code bool option option}
     *   <li>{@code ('a,'b) tree}
     * </ul>
     *
     * @see ParameterizedType#computeMoniker(String, List)
     */
    @Override
    public StringBuilder describe(StringBuilder buf, int left, int right) {
      return describeParameterized(buf, name, arguments);
    }

    @Override
    public DataType toType(TypeSystem typeSystem) {
      return typeSystem.dataType(
          name,
          ImmutableList.copyOf(typeSystem.typesFor(arguments)),
          typeConstructors);
    }
  }
}

// End Keys.java
