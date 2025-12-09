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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.NameGenerator;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.eval.Variants;
import net.hydromatic.morel.type.Type.Key;
import net.hydromatic.morel.util.ComparableSingletonList;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A table that contains all types in use, indexed by their description (e.g.
 * "{@code int -> int}").
 */
public class TypeSystem {
  final Map<String, Type> typeByName = new HashMap<>();
  final Map<BuiltIn.BuiltInType, Type> builtInTypes = new HashMap<>();
  final Map<Key, Type> typeByKey = new HashMap<>();

  private final Map<String, TypeCon> typeConstructorByName = new HashMap<>();

  public final NameGenerator nameGenerator = new NameGenerator();

  /**
   * Number of times that {@link TypedValue#discoverField(TypeSystem, String)}
   * has caused a type to change.
   */
  public final AtomicInteger expandCount = new AtomicInteger();

  public TypeSystem() {
    for (PrimitiveType primitiveType : PrimitiveType.values()) {
      typeByName.put(primitiveType.moniker, primitiveType);
    }
  }

  /** Creates a binding of a type constructor value. */
  public Binding bindTyCon(DataType dataType, String tyConName) {
    final Type type = dataType.typeConstructors(this).get(tyConName);
    if (type == DummyType.INSTANCE) {
      Object o = ComparableSingletonList.of(tyConName);
      if (dataType.name.equals(BuiltIn.Datatype.VARIANT.mlName())) {
        // Nullary variant constructors: create proper variant instance.
        o = Variants.fromConstructor(tyConName, Unit.INSTANCE, this);
      }
      return Binding.of(core.idPat(dataType, tyConName, 0), Codes.constant(o));
    } else {
      final Type type2 = wrap(dataType, fnType(type, dataType));
      return Binding.of(
          core.idPat(type2, tyConName, 0), Codes.tyCon(dataType, tyConName));
    }
  }

  private Type wrap(DataType dataType, Type type) {
    final List<TypeVar> typeVars =
        dataType.parameterTypes.stream()
            .filter(t -> t instanceof TypeVar)
            .map(t -> (TypeVar) t)
            .collect(toImmutableList());
    return typeVars.isEmpty() ? type : forallType(typeVars.size(), type);
  }

  /**
   * Looks up a built-in type.
   *
   * <p>This is the only way to get internal built-in types (such as {@link
   * BuiltIn.Datatype#PSEUDO_BOOL}); non-internal built-in types (such as {@link
   * BuiltIn.Datatype#ORDER} and {@link BuiltIn.Datatype#OPTION}) can also be
   * retrieved by name.
   */
  public Type lookup(BuiltIn.BuiltInType builtInType) {
    final Type type = builtInTypes.get(builtInType);
    if (type == null) {
      throw new AssertionError("unknown type: " + builtInType);
    }
    return type;
  }

  /** Looks up a type by name. */
  public Type lookup(String name) {
    final Type type = typeByName.get(name);
    if (type == null) {
      throw new AssertionError("unknown type: " + name);
    }
    return type;
  }

  /** Looks up a type by name, returning null if not found. */
  public Type lookupOpt(String name) {
    // TODO: only use this for names, e.g. 'option',
    // not monikers e.g. 'int option';
    // assert !name.contains(" ") : name;
    return typeByName.get(name);
  }

  /** Gets a type that matches a key, creating if necessary. */
  public Type typeFor(Key key) {
    Type type = typeByKey.get(key);
    if (type == null) {
      type = key.toType(this);
      typeByKey.putIfAbsent(key, type);
    }
    return type;
  }

  /** Converts a list of keys to a list of types. */
  public List<Type> typesFor(Iterable<? extends Key> keys) {
    return transformEager(keys, key -> key.toType(this));
  }

  /** Converts a map of keys to a map of types. */
  public SortedMap<String, Type> typesFor(Map<String, ? extends Key> keys) {
    final ImmutableSortedMap.Builder<String, Type> types =
        ImmutableSortedMap.orderedBy(RecordType.ORDERING);
    keys.forEach((name, key) -> types.put(name, typeFor(key)));
    return types.build();
  }

  /**
   * Creates a multi-step function type.
   *
   * <p>For example, {@code fnType(a, b, c, d)} returns the same as
   * <!-- prevent wrapping -->
   * {@code fnType(a, fnType(b, fnType(c, d)))},
   * <!-- prevent wrapping -->
   * viz <code>a &rarr; b &rarr; c &rarr; d</code>.
   */
  public Type fnType(
      Type paramType, Type type1, Type type2, Type... moreTypes) {
    final List<Type> types =
        ImmutableList.<Type>builder()
            .add(paramType)
            .add(type1)
            .add(type2)
            .add(moreTypes)
            .build();
    Type t = null;
    for (Type type : Lists.reverse(types)) {
      if (t == null) {
        t = type;
      } else {
        t = fnType(type, t);
      }
    }
    return requireNonNull(t);
  }

  /** Creates a function type. */
  public FnType fnType(Type paramType, Type resultType) {
    return (FnType) typeFor(Keys.fn(paramType.key(), resultType.key()));
  }

  /** Creates a tuple type from an array of types. */
  public TupleType tupleType(Type argType0, Type... argTypes) {
    return (TupleType) tupleType(Lists.asList(argType0, argTypes));
  }

  /** Creates a tuple type. */
  public RecordLikeType tupleType(List<? extends Type> argTypes) {
    return (RecordLikeType) typeFor(Keys.tuple(Keys.toKeys(argTypes)));
  }

  /** Creates a bag type. */
  public Type bagType(Type elementType) {
    return typeFor(
        Keys.apply(
            Keys.name(BuiltIn.Eqtype.BAG.mlName()),
            ImmutableList.of(elementType.key())));
  }

  /** Creates a list type. */
  public ListType listType(Type elementType) {
    return (ListType) typeFor(Keys.list(elementType.key()));
  }

  /** Creates several data types simultaneously. */
  public List<Type> dataTypes(List<Keys.DataTypeKey> keys) {
    final Map<Type.Key, DataType> dataTypeMap = new LinkedHashMap<>();
    keys.forEach(
        key -> {
          final DataType dataType = key.toType(this);
          final Key nameKey = Keys.name(dataType.name);
          typeByKey.put(nameKey, dataType);

          dataType.typeConstructors.forEach(
              (name, typeKey) ->
                  typeConstructorByName.put(
                      name, TypeCon.of(dataType, name, typeKey)));
          dataTypeMap.put(nameKey, dataType);
        });

    final ImmutableList.Builder<Type> types = ImmutableList.builder();
    dataTypeMap
        .values()
        .forEach(
            dataType -> {
              // We have just created an entry for the moniker
              // (e.g. "'a option"), so now create an entry for the name
              // (e.g. "option").
              Type t =
                  dataType.arguments.isEmpty()
                      ? dataType
                      : forallType(dataType.arguments.size(), dataType);
              typeByName.put(dataType.name, t);
              types.add(t);
            });
    return types.build();
  }

  /**
   * Creates an algebraic type.
   *
   * <p>Parameter types is empty unless this is a type scheme. For example,
   *
   * <ul>
   *   <li>{@code datatype 'a option = NONE | SOME of 'a} has parameter types
   *       and argument types {@code ['a]}, type constructors {@code [NONE:
   *       dummy, SOME: 'a]};
   *   <li>{@code int option} has empty parameter types, argument types {@code
   *       [int]}, type constructors {@code [NONE: dummy, SOME: int]};
   *   <li>{@code datatype color = RED | GREEN} has empty parameter types and
   *       argument types, type constructors {@code [RED: dummy, GREEN: dummy]}.
   * </ul>
   *
   * @param name Name (e.g. "option")
   * @param argumentTypes Argument types
   * @param tyCons Type constructors
   */
  DataType dataType(
      String name,
      ImmutableList<Type> argumentTypes,
      ImmutableMap<String, Key> tyCons) {
    final String moniker = DataType.computeMoniker(name, argumentTypes);
    final DataType dataType =
        new DataType(name, moniker, argumentTypes, tyCons);
    if (argumentTypes.isEmpty()) {
      // There are no type parameters, therefore there will be no ForallType to
      // register its type constructors, so this DataType needs to register.
      tyCons.forEach(
          (name3, typeKey) ->
              typeConstructorByName.put(
                  name3, TypeCon.of(dataType, name3, typeKey)));
    }
    return dataType;
  }

  /** Creates a type that is an alias for another type. */
  Type aliasType(String name, Type type, List<Type> arguments) {
    final AliasType aliasType = new AliasType(name, type, arguments);
    typeByName.put(name, aliasType);
    return aliasType;
  }

  /**
   * Converts a regular type to an internal type. Throws if the type is not
   * known.
   */
  public void setBuiltIn(BuiltIn.BuiltInType datatype) {
    final Type type;
    if (datatype.isInternal()) {
      // Internal types can only be accessed by name.
      type = typeByName.remove(datatype.mlName());
    } else {
      // Non-internal built-in types can be accessed by name or enum.
      type = typeByName.get(datatype.mlName());
    }
    requireNonNull(type, datatype.mlName());
    builtInTypes.put(datatype, type);
  }

  /**
   * Creates a data type scheme: a datatype if there are no type arguments (e.g.
   * "{@code ordering}"), or a forall type if there are type arguments (e.g.
   * "{@code forall 'a . 'a option}").
   *
   * <p>Iteration order of the type constructors ({@code tyCons}) is
   * significant. We recommend that you use a sequenced map such as {@link
   * ImmutableMap} or {@link LinkedHashMap}.
   */
  public Type dataTypeScheme(
      String name, List<TypeVar> parameters, Map<String, Type.Key> tyCons) {
    final List<Key> keys = Keys.toKeys(parameters);
    final Keys.DataTypeKey key = Keys.datatype(name, keys, tyCons);
    return dataTypes(ImmutableList.of(key)).get(0);
  }

  /**
   * Creates a record type, or returns a scalar type if {@code argNameTypes} has
   * one entry.
   */
  public Type recordOrScalarType(
      Collection<Map.Entry<String, Type>> argNameTypes) {
    switch (argNameTypes.size()) {
      case 1:
        return Iterables.getOnlyElement(argNameTypes).getValue();
      default:
        return recordType(argNameTypes);
    }
  }

  /**
   * Creates a record type. (Or a tuple type if the fields are named "1", "2"
   * etc.; or "unit" if the field list is empty.)
   */
  public RecordLikeType recordType(
      Collection<Map.Entry<String, Type>> argNameTypes) {
    return recordType(
        ImmutableSortedMap.copyOf(argNameTypes, RecordType.ORDERING));
  }

  /**
   * Creates a record type. (Or a tuple type if the fields are named "1", "2"
   * etc.; or "unit" if the field list is empty.)
   */
  public RecordLikeType recordType(
      SortedMap<String, ? extends Type> argNameTypes) {
    final ImmutableSortedMap<String, Type> argNameTypes2 =
        ImmutableSortedMap.copyOf(argNameTypes, RecordType.ORDERING);
    if (argNameTypes2.isEmpty()) {
      return PrimitiveType.UNIT;
    }
    if (areContiguousIntegers(argNameTypes2.keySet())
        && argNameTypes2.size() != 1) {
      return tupleType(ImmutableList.copyOf(argNameTypes2.values()));
    }
    return (RecordLikeType) typeFor(Keys.record(Keys.toKeys(argNameTypes2)));
  }

  /** Returns whether the collection is ["1", "2", ... n]. */
  public static boolean areContiguousIntegers(Iterable<String> strings) {
    int i = 1;
    for (String string : strings) {
      if (!string.equals(Integer.toString(i++))) {
        return false;
      }
    }
    return true;
  }

  /** Creates a progressive record type. */
  public ProgressiveRecordType progressiveRecordType(
      Collection<Map.Entry<String, Type>> argNameTypes) {
    return progressiveRecordType(
        ImmutableSortedMap.copyOf(argNameTypes, RecordType.ORDERING));
  }

  /** Creates a progressive record type. */
  public ProgressiveRecordType progressiveRecordType(
      SortedMap<String, Type> argNameTypes) {
    final ImmutableSortedMap<String, Type> argNameTypes2 =
        ImmutableSortedMap.copyOf(argNameTypes, RecordType.ORDERING);
    Key key = Keys.progressiveRecord(Keys.toKeys(argNameTypes2));
    return (ProgressiveRecordType) typeFor(key);
  }

  /** Creates a "forall" type. */
  public Type forallType(int typeCount, Function<ForallHelper, Type> builder) {
    final ForallHelper helper =
        new ForallHelper() {
          public TypeVar get(int i) {
            checkArgument(
                i >= 0 && i < typeCount,
                "type variable index %s out of range [0, %s)",
                i,
                typeCount);
            return typeVariable(i);
          }

          public ListType list(int i) {
            return listType(get(i));
          }

          public Type bag(int i) {
            return bagType(get(i));
          }

          public Type either(int i, int j) {
            return TypeSystem.this.either(get(i), get(j));
          }

          public Type vector(int i) {
            return TypeSystem.this.vector(get(i));
          }

          public Type option(int i) {
            return TypeSystem.this.option(get(i));
          }

          public FnType predicate(int i) {
            return fnType(get(i), PrimitiveType.BOOL);
          }
        };
    final Type type = builder.apply(helper);
    return forallType(typeCount, type);
  }

  /** Creates a "for all" type. */
  public ForallType forallType(int typeCount, Type type) {
    final Key key = Keys.forall(type, typeCount);
    return (ForallType) typeFor(key);
  }

  /** Creates a multi-type from an array of types. */
  public MultiType multi(Type... types) {
    return multi(ImmutableList.copyOf(types));
  }

  /** Creates a multi-type. */
  public MultiType multi(List<? extends Type> types) {
    return new MultiType(ImmutableList.copyOf(types));
  }

  static StringBuilder unparseList(
      StringBuilder builder,
      Op op,
      int left,
      int right,
      Collection<? extends Type.Key> argTypes) {
    if (op == Op.COMMA && argTypes.size() != 1 && !(left == 0 && right == 0)) {
      builder.append('(');
      unparseList(builder, op, 0, 0, argTypes);
      builder.append(')');
    } else {
      forEachIndexed(
          argTypes,
          (type, i) -> {
            if (i > 0) {
              builder.append(op.padded);
            }
            unparse(
                builder,
                type,
                i == 0 ? left : op.right,
                i == argTypes.size() - 1 ? right : op.left);
          });
    }
    return builder;
  }

  static StringBuilder unparse(
      StringBuilder builder, Type.Key type, int left, int right) {
    if (left > type.op.left || type.op.right < right) {
      builder.append("(");
      unparse(builder, type, 0, 0);
      return builder.append(")");
    } else {
      return type.describe(builder, left, right);
    }
  }

  public List<TypeVar> typeVariables(int size) {
    return new AbstractList<TypeVar>() {
      public int size() {
        return size;
      }

      public TypeVar get(int index) {
        return typeVariable(index);
      }
    };
  }

  /**
   * Removes any "forall" qualifier of a type, and renumbers the remaining type
   * variables.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code forall 'a. 'a list} &rarr; {@code 'a list}
   *   <li>{@code forall 'a 'b. 'b list} &rarr; {@code 'a list}
   *   <li>{@code forall 'a 'b 'c. 'c * 'a -> {x:'a, y:'c} } &rarr; {@code 'a *
   *       'b -> {x:b, y:a'} }
   * </ul>
   */
  public Type unqualified(Type type) {
    final Type type0 = type;
    while (type instanceof ForallType) {
      type = ((ForallType) type).type;
    }
    if (type == type0) {
      return type0;
    }
    // Renumber type variables:
    //   'b list   ->  'a list
    //   ('b * 'a * 'b)  ->  ('a * 'b * 'a)
    //   ('a * 'c * 'a)  ->  ('a * 'b * 'a)
    return type.accept(
        new TypeShuttle(this) {
          final Map<Integer, TypeVar> map = new HashMap<>();

          @Override
          public Type visit(TypeVar typeVar) {
            return map.computeIfAbsent(
                typeVar.ordinal, i -> typeVariable(map.size()));
          }
        });
  }

  public @Nullable TypeCon lookupTyCon(String tyConName) {
    return typeConstructorByName.get(tyConName);
  }

  public Type apply(Type type, Type... types) {
    return apply(type, ImmutableList.copyOf(types));
  }

  public Type apply(Type type, List<Type> types) {
    if (type instanceof ForallType) {
      final ForallType forallType = (ForallType) type;
      return forallType.substitute(this, types);
    }
    if (type instanceof DataType) {
      final DataType dataType = (DataType) type;
      return dataType.substitute(this, types);
    }
    throw new AssertionError();
  }

  /** Creates a type variable. */
  public TypeVar typeVariable(int ordinal) {
    return (TypeVar) typeFor(Keys.ordinal(ordinal));
  }

  /** Returns the "descending" built-in data type. */
  public Type descending() {
    return lookup(BuiltIn.Datatype.DESCENDING);
  }

  /** Returns the "order" built-in data type. */
  public Type order() {
    return lookup(BuiltIn.Datatype.ORDER);
  }

  /**
   * Creates a "bag" type.
   *
   * <p>"bag(type)" is shorthand for "apply(lookup("bag"), type)".
   */
  public Type bag(Type type) {
    final Type bagType = lookup(BuiltIn.Eqtype.BAG);
    return apply(bagType, type);
  }

  /**
   * Creates an "either" type.
   *
   * <p>"either(type1, type2)" is shorthand for "apply(lookup("either"), type)".
   */
  public Type either(Type type1, Type type2) {
    final Type eitherType = lookup(BuiltIn.Datatype.EITHER);
    return apply(eitherType, type1, type2);
  }

  /**
   * Creates an "option" type.
   *
   * <p>"option(type)" is shorthand for "apply(lookup("option"), type)".
   */
  public Type option(Type type) {
    final Type optionType = lookup(BuiltIn.Datatype.OPTION);
    return apply(optionType, type);
  }

  /**
   * Creates a "vector" type.
   *
   * <p>"vector(type)" is shorthand for "apply(lookup("vector"), type)".
   */
  public Type vector(Type type) {
    final Type vectorType = lookup(BuiltIn.Eqtype.VECTOR);
    return apply(vectorType, type);
  }

  /**
   * Converts a type into a {@link ForallType} if it has free type variables.
   */
  public Type ensureClosed(Type type) {
    final VariableCollector collector = new VariableCollector();
    type.accept(collector);
    if (collector.vars.isEmpty()) {
      return type;
    }
    final List<Type> types = new ArrayList<>();
    int i = 0;
    for (TypeVar var : collector.vars) {
      while (var.ordinal >= types.size()) {
        types.add(null);
      }
      types.set(var.ordinal, typeVariable(i++));
    }
    final TypeSystem ts = this;
    return forallType(collector.vars.size(), h -> type.substitute(ts, types));
  }

  /**
   * Returns whether you can assign a value of {@code fromType} to a variable of
   * type {@code toType}.
   *
   * <p>Cases:
   *
   * <ul>
   *   <li>You can assign {@code int} to {@code int};
   *   <li>You can not assign {@code int} to {@code string};
   *   <li>You can assign {@code int} to {@code myInt} if {@code myInt} is an
   *       alias defined by {@code type myInt = int};
   *   <li>You can assign {@code {emps: {empno: int, ename: string} list, depts:
   *       {deptno: int} list} } to {@code {}} if the latter is a progressive
   *       type.
   * </ul>
   */
  public static boolean canAssign(Type fromType, Type toType) {
    while (fromType instanceof ForallType) {
      fromType = ((ForallType) fromType).type;
    }
    return fromType.equals(toType)
        || fromType instanceof RecordType && toType.isProgressive()
        || toType.containsAlias()
        || fromType instanceof ListType
            && toType instanceof ListType
            && canAssign(
                ((ListType) fromType).elementType,
                ((ListType) toType).elementType);
  }

  /** Visitor that finds all {@link TypeVar} instances within a {@link Type}. */
  private static class VariableCollector extends TypeVisitor<Void> {
    final Set<TypeVar> vars = new LinkedHashSet<>();

    @Override
    public Void visit(DataType dataType) {
      // Include arguments but ignore parameters
      dataType.arguments.forEach(t -> t.accept(this));
      return null;
    }

    @Override
    public Void visit(TypeVar typeVar) {
      vars.add(typeVar);
      return super.visit(typeVar);
    }
  }

  /**
   * Provides access to type variables from within a call to {@link
   * TypeSystem#forallType(int, Function)}.
   */
  public interface ForallHelper {
    /** Creates type {@code `i}. */
    TypeVar get(int i);
    /** Creates type {@code `i bag}. */
    Type bag(int i);
    /** Creates type {@code (`i, `j) either}. */
    Type either(int i, int j);
    /** Creates type {@code `i list}. */
    ListType list(int i);
    /** Creates type {@code `i vector}. */
    Type vector(int i);
    /** Creates type {@code `i option}. */
    Type option(int i);
    /** Creates type <code>`i &rarr; bool</code>. */
    FnType predicate(int i);
  }
}

// End TypeSystem.java
