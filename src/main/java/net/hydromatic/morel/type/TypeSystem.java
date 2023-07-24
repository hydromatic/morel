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

import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.compile.NameGenerator;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.type.Type.Key;
import net.hydromatic.morel.util.ComparableSingletonList;
import net.hydromatic.morel.util.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.lang.reflect.Field;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import javax.annotation.Nonnull;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.toImmutableList;

/** A table that contains all types in use, indexed by their description (e.g.
 * "{@code int -> int}"). */
public class TypeSystem {
  final Map<String, Type> typeByName = new HashMap<>();
  final Map<String, Type> internalTypeByName = new HashMap<>();
  final Map<Key, Type> typeByKey = new HashMap<>();

  private final Map<String, Pair<DataType, Type>> typeConstructorByName =
      new HashMap<>();

  public final NameGenerator nameGenerator = new NameGenerator();

  public TypeSystem() {
    for (PrimitiveType primitiveType : PrimitiveType.values()) {
      typeByName.put(primitiveType.moniker, primitiveType);
    }
  }

  ImmutableSortedMap<String, Type> copyTypeConstructors(
      @Nonnull SortedMap<String, Type> typeConstructors,
      @Nonnull UnaryOperator<Type> transform) {
    final ImmutableSortedMap.Builder<String, Type> builder =
        ImmutableSortedMap.naturalOrder();
    typeConstructors.forEach((k, v) ->
        builder.put(k, v.copy(this, transform)));
    return builder.build();
  }

  /** Creates a binding of a type constructor value. */
  public Binding bindTyCon(DataType dataType, String tyConName) {
    final Type type = dataType.typeConstructors.get(tyConName);
    if (type == DummyType.INSTANCE) {
      return Binding.of(core.idPat(dataType, tyConName, 0),
          Codes.constant(ComparableSingletonList.of(tyConName)));
    } else {
      final Type type2 = wrap(dataType, fnType(type, dataType));
      return Binding.of(core.idPat(type2, tyConName, 0),
          Codes.tyCon(dataType, tyConName));
    }
  }

  private Type wrap(DataType dataType, Type type) {
    final List<TypeVar> typeVars =
        dataType.parameterTypes.stream().filter(t -> t instanceof TypeVar)
            .map(t -> (TypeVar) t)
            .collect(toImmutableList());
    return typeVars.isEmpty() ? type : forallType(typeVars, type);
  }

  /** Looks up an internal type by name. */
  public Type lookupInternal(String name) {
    final Type type = internalTypeByName.get(name);
    if (type == null) {
      throw new AssertionError("unknown type: " + name);
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
    return typeByKey.computeIfAbsent(key, this::keyToType);
  }

  /** Creates a type from a key. */
  private Type keyToType(Key key) {
    return key.toType(this);
  }

  /** Creates a multi-step function type.
   *
   * <p>For example, {@code fnType(a, b, c, d)} returns the same as
   * {@code fnType(a, fnType(b, fnType(c, d)))},
   * viz <code>a &rarr; b &rarr; c &rarr; d</code>. */
  public Type fnType(Type paramType, Type type1, Type type2,
      Type... moreTypes) {
    final List<Type> types = ImmutableList.<Type>builder()
        .add(paramType).add(type1).add(type2).add(moreTypes).build();
    Type t = null;
    for (Type type : Lists.reverse(types)) {
      if (t == null) {
        t = type;
      } else {
        t = fnType(type, t);
      }
    }
    return Objects.requireNonNull(t);
  }

  /** Creates a function type. */
  public FnType fnType(Type paramType, Type resultType) {
    return (FnType) typeFor(Keys.fn(paramType, resultType));
  }

  /** Creates a tuple type from an array of types. */
  public TupleType tupleType(Type argType0, Type... argTypes) {
    return (TupleType) tupleType(Lists.asList(argType0, argTypes));
  }

  /** Creates a tuple type. */
  public RecordLikeType tupleType(List<? extends Type> argTypes) {
    return (RecordLikeType) typeFor(Keys.tuple(argTypes));
  }

  /** Creates a list type. */
  public ListType listType(Type elementType) {
    return (ListType) typeFor(Keys.list(elementType));
  }

  /** Creates several data types simultaneously. */
  public List<Type> dataTypes(List<Keys.DataTypeDef> defs) {
    return dataTypes(defs, (type, typeMap) -> {
      if (type instanceof DataType) {
        final DataType dataType = (DataType) type;
        setTypeConstructors(dataType,
            copyTypeConstructors(dataType.typeConstructors,
                t -> t instanceof TemporaryType ? typeMap.get(t.key()) : t));
      }
    });
  }

  /** Sets the {@link DataType#typeConstructors} field of a newly constructed
   * {@link DataType}.
   *
   * <p>The field is {@code public final}, which is for the greater good; the
   * assignment occurs immediately after construction, before anyone sees the
   * {@link DataType} instance, so is safe. We would have assigned the field
   * in the constructor if we did not have to deal with datatypes that refer
   * to each other, like this:
   *
   * <pre>{@code
   * datatype 'a tree = Empty | Node of 'a * 'a forest
   * and      'a forest = Nil | Cons of 'a tree * 'a forest;
   * }</pre> */
  private static void setTypeConstructors(DataType dataType,
      ImmutableSortedMap<String, Type> typeConstructors) {
    try {
      final Field field = DataType.class.getDeclaredField("typeConstructors");
      field.setAccessible(true);
      field.set(dataType, typeConstructors);
      field.setAccessible(false);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  private List<Type> dataTypes(List<Keys.DataTypeDef> defs, DataTypeFixer fixer) {
    final Map<Type.Key, Type> dataTypeMap = new LinkedHashMap<>();
    defs.forEach(def -> {
      final Key key;
      final Type type;
      if (def.scheme) {
        key = Keys.name(def.name);
        type = def.toType(this);
        typeByKey.put(key, type);
      } else {
        final ForallType type1 = (ForallType) lookup(def.name);
        key = Keys.forallTypeApply(type1, def.types);
        type = typeFor(key);
      }
      dataTypeMap.put(key, type);
    });
    final ImmutableList.Builder<Type> types = ImmutableList.builder();
    forEach(defs, dataTypeMap.values(), (def, dataType) -> {
      fixer.apply(dataType, dataTypeMap);
      if (def.scheme) {
        if (!def.types.isEmpty()
            && def.types.equals(typeVariables(def.types.size()))) {
          // We have just created an entry for the moniker (e.g. "'a option"),
          // so now create an entry for the name (e.g. "option").
          @SuppressWarnings({"rawtypes", "unchecked"})
          final List<TypeVar> typeVars = (List) def.types;
          final ForallType forallType = forallType(typeVars, dataType);
          typeByName.put(def.name, forallType);
          types.add(forallType);
        } else {
          if (def.types.isEmpty()) {
            typeByName.put(def.name, dataType);
          }
          types.add(dataType);
        }
      } else {
        types.add(dataType);
      }
    });
    return types.build();
  }

  DataType dataType(String name, Key key, List<? extends Type> types,
      SortedMap<String, Type> tyCons) {
    final DataType dataType = new DataType(name, key,
        ImmutableList.copyOf(types), ImmutableSortedMap.copyOf(tyCons));
    tyCons.forEach((name3, type) ->
        typeConstructorByName.put(name3, Pair.of(dataType, type)));
    return dataType;
  }

  /** Converts a regular type to an internal type. Throws if the type is not
   * known. */
  public void setInternal(String name) {
    final Type type = typeByName.remove(name);
    internalTypeByName.put(name, type);
  }

  /** Replaces temporary data types with real data types, using the supplied
   * map. */
  @FunctionalInterface
  private interface DataTypeFixer {
    void apply(Type type, Map<Key, Type> typeMap);
  }

  /** Creates a data type scheme: a datatype if there are no type arguments
   * (e.g. "{@code ordering}"), or a forall type if there are type arguments
   * (e.g. "{@code forall 'a . 'a option}"). */
  public Type dataTypeScheme(String name, List<TypeVar> typeParameters,
      SortedMap<String, Type> tyCons) {
    final Keys.DataTypeDef def =
        Keys.dataTypeDef(name, typeParameters, tyCons, true);
    return dataTypes(ImmutableList.of(def)).get(0);
  }

  /** Creates a record type, or returns a scalar type if {@code argNameTypes}
   * has one entry. */
  public Type recordOrScalarType(
      SortedMap<String, ? extends Type> argNameTypes) {
    switch (argNameTypes.size()) {
    case 1:
      return Iterables.getOnlyElement(argNameTypes.values());
    default:
      return recordType(argNameTypes);
    }
  }

  /** Creates a record type. (Or a tuple type if the fields are named "1", "2"
   * etc.; or "unit" if the field list is empty.) */
  public RecordLikeType recordType(SortedMap<String, ? extends Type> argNameTypes) {
    if (argNameTypes.isEmpty()) {
      return PrimitiveType.UNIT;
    }
    final ImmutableSortedMap<String, Type> argNameTypes2 =
        ImmutableSortedMap.copyOfSorted(argNameTypes);
    if (areContiguousIntegers(argNameTypes2.keySet())
        && argNameTypes2.size() != 1) {
      return tupleType(ImmutableList.copyOf(argNameTypes2.values()));
    }
    return (RecordLikeType) typeFor(Keys.record(argNameTypes2));
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

  /** Creates a "forall" type. */
  public Type forallType(int typeCount, Function<ForallHelper, Type> builder) {
    final List<TypeVar> typeVars = typeVariables(typeCount);
    final ForallHelper helper = new ForallHelper() {
      public TypeVar get(int i) {
        return typeVars.get(i);
      }

      public ListType list(int i) {
        return listType(get(i));
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
    return forallType(typeVars, type);
  }

  /** Creates a "for all" type. */
  public ForallType forallType(List<TypeVar> typeVars, Type type) {
    assert typeVars.equals(typeVariables(typeVars.size()));
    final Key key = Keys.forall(type, typeVars);
    return (ForallType) typeFor(key);
  }

  static StringBuilder unparseList(StringBuilder builder, Op op, int left,
      int right, Collection<? extends Type> argTypes) {
    forEachIndexed(argTypes, (type, i) -> {
      if (i > 0) {
        builder.append(op.padded);
      }
      unparse(builder, type,
          i == 0 ? left : op.right,
          i == argTypes.size() - 1 ? right : op.left);
    });
    return builder;
  }

  static StringBuilder unparse(StringBuilder builder, Type type, int left,
      int right) {
    final Op op = type.op();
    if (left > op.left || op.right < right) {
      return builder.append("(").append(type.moniker()).append(")");
    } else {
      return builder.append(type.moniker());
    }
  }

  /** Creates a temporary type.
   *
   * <p>(Temporary types exist for a brief period while defining a recursive
   * {@code datatype}.) */
  public TemporaryType temporaryType(String name,
      List<? extends Type> parameterTypes, Transaction transaction_,
      boolean withScheme) {
    final TemporaryType temporaryType =
        new TemporaryType(name, parameterTypes);
    final TransactionImpl transaction = (TransactionImpl) transaction_;
    transaction.put(temporaryType.moniker, temporaryType);
    if (withScheme && !parameterTypes.isEmpty()) {
      final List<TypeVar> typeVars = typeVariables(parameterTypes.size());
      transaction.put(name,
          new ForallType(ImmutableList.copyOf(typeVars), temporaryType));
    }
    return temporaryType;
  }

  private List<TypeVar> typeVariables(int size) {
    return new AbstractList<TypeVar>() {
      public int size() {
        return size;
      }

      public TypeVar get(int index) {
        return typeVariable(index);
      }
    };
  }

  public Pair<DataType, Type> lookupTyCon(String tyConName) {
    return typeConstructorByName.get(tyConName);
  }

  public Type apply(Type type, Type... types) {
    return apply(type, ImmutableList.copyOf(types));
  }

  public Type apply(Type type, List<Type> types) {
    if (type instanceof TemporaryType) {
      final TemporaryType temporaryType = (TemporaryType) type;
      if (types.equals(temporaryType.parameterTypes)) {
        return type;
      }
      throw new AssertionError();
    }
    if (type instanceof ForallType) {
      final ForallType forallType = (ForallType) type;
      try (Transaction transaction = transaction()) {
        return forallType.type.substitute(this, types, transaction);
      }
    }
    if (type instanceof DataType) {
      final DataType dataType = (DataType) type;
      try (Transaction transaction = transaction()) {
        return dataType.substitute(this, types, transaction);
      }
    }
    if (type instanceof ApplyType
        && ((ApplyType) type).type instanceof DataType) {
      final ApplyType applyType = (ApplyType) type;
      final DataType dataType = (DataType) applyType.type;
      try (Transaction transaction = transaction()) {
        return dataType.substitute(this, types, transaction);
      }
    }
    return new ApplyType((ParameterizedType) type, ImmutableList.copyOf(types));
  }

  /** Creates a type variable. */
  public TypeVar typeVariable(int ordinal) {
    return (TypeVar) typeFor(Keys.ordinal(ordinal));
  }

  /** Creates an "option" type.
   *
   * <p>"option(type)" is short-hand for "apply(lookup("option"), type)". */
  public Type option(Type type) {
    final Type optionType = lookup("option");
    return apply(optionType, type);
  }

  /** Creates a "vector" type.
   *
   * <p>"vector(type)" is short-hand for "apply(lookup("vector"), type)". */
  public Type vector(Type type) {
    final Type vectorType = lookup("vector");
    return apply(vectorType, type);
  }

  /** Converts a type into a {@link ForallType} if it has free type
   * variables. */
  public Type ensureClosed(Type type) {
    final VariableCollector collector = new VariableCollector();
    type.accept(collector);
    if (collector.vars.isEmpty()) {
      return type;
    }
    final TypeSystem ts = this;
    return forallType(collector.vars.size(), h ->
        type.copy(ts, t ->
            t instanceof TypeVar ? h.get(((TypeVar) t).ordinal) : t));
  }

  public TypeSystem.Transaction transaction() {
    return new TransactionImpl();
  }

  /** Holds temporary changes to the type system. */
  public interface Transaction extends AutoCloseable {
    void close();
  }

  /** Implementation of {@link Transaction}. */
  private class TransactionImpl implements Transaction {
    final List<String> names = new ArrayList<>();

    void put(String moniker, Type type) {
      typeByName.put(moniker, Objects.requireNonNull(type));
      names.add(moniker);
    }

    public void close() {
      for (String name : names) {
        typeByName.remove(name);
      }
      names.clear();
    }
  }

  /** Visitor that finds all {@link TypeVar} instances within a {@link Type}. */
  private static class VariableCollector extends TypeVisitor<Void> {
    final Set<TypeVar> vars = new LinkedHashSet<>();

    @Override public Void visit(DataType dataType) {
      return null; // ignore type variables in the datatype
    }

    @Override public Void visit(TypeVar typeVar) {
      vars.add(typeVar);
      return super.visit(typeVar);
    }
  }

  /** Provides access to type variables from within a call to
   * {@link TypeSystem#forallType(int, Function)}. */
  public interface ForallHelper {
    /** Creates type {@code `i}. */
    TypeVar get(int i);
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
