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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.util.ComparableSingletonList;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;

/** A table that contains all types in use, indexed by their description (e.g.
 * "{@code int -> int}"). */
public class TypeSystem {
  private final Map<String, Type> typeByName = new HashMap<>();

  private final Map<String, Pair<DataType, Type>> typeConstructorByName =
      new HashMap<>();

  public TypeSystem() {
    for (PrimitiveType primitiveType : PrimitiveType.values()) {
      typeByName.put(primitiveType.moniker, primitiveType);
    }
  }

  /** Creates a binding of a type constructor value. */
  public Binding bindTyCon(DataType dataType, String tyConName) {
    final Type type = dataType.typeConstructors.get(tyConName);
    if (type == DummyType.INSTANCE) {
      return Binding.of(tyConName, dataType,
          Codes.constant(ComparableSingletonList.of(tyConName)));
    } else {
      return Binding.of(tyConName, wrap(dataType, fnType(type, dataType)),
          Codes.tyCon(dataType, tyConName));
    }
  }

  private Type wrap(DataType dataType, Type type) {
    @SuppressWarnings("UnstableApiUsage") final List<TypeVar> typeVars =
        dataType.typeVars.stream().filter(t -> t instanceof TypeVar)
            .map(t -> (TypeVar) t)
            .collect(ImmutableList.toImmutableList());
    return typeVars.isEmpty() ? type : forallType(typeVars, type);
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
    return typeByName.get(name);
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
    final String description =
        unparseList(new StringBuilder(), Op.FUNCTION_TYPE, 0, 0,
            Arrays.asList(paramType, resultType)).toString();
    return (FnType) typeByName.computeIfAbsent(description,
        d -> new FnType(d, paramType, resultType));
  }

  /** Creates a tuple type from an array of types. */
  public Type tupleType(Type... argTypes) {
    return tupleType(ImmutableList.copyOf(argTypes));
  }

  /** Creates a tuple type. */
  public Type tupleType(List<? extends Type> argTypes) {
    if (argTypes.isEmpty()) {
      return PrimitiveType.UNIT;
    }
    final String description =
        unparseList(new StringBuilder(), Op.TIMES, 0, 0, argTypes).toString();
    return typeByName.computeIfAbsent(description,
        d -> new TupleType(d, ImmutableList.copyOf(argTypes)));
  }

  /** Creates a list type. */
  public ListType listType(Type elementType) {
    final String description =
        unparse(new StringBuilder(), elementType, 0, Op.LIST.right)
            .append(" list")
            .toString();
    return (ListType) typeByName.computeIfAbsent(description,
        d -> new ListType(d, elementType));
  }

  /** Creates a data type. */
  public DataType dataType(String name, List<? extends Type> types,
      Map<String, Type> tyCons) {
    final boolean allVars = types.stream().allMatch(t -> t instanceof TypeVar);
    String moniker = DataType.computeMoniker(name, types);
    final DataType dataType0 = (DataType) typeByName.computeIfAbsent(moniker,
        moniker2 -> {
          final DataType dataType;
          final String description = DataType.computeDescription(tyCons);
          if (allVars) {
            @SuppressWarnings("unchecked") final List<TypeVar> typeVars =
                (List) types;
            dataType = new PolymorphicDataType(TypeSystem.this, name,
                description, ImmutableList.copyOf(typeVars),
                ImmutableSortedMap.copyOf(tyCons));
          } else {
            dataType = new DataType(TypeSystem.this, name,
                description, ImmutableList.copyOf(types),
                ImmutableSortedMap.copyOf(tyCons));
          }
          tyCons.forEach((name3, type) ->
              typeConstructorByName.put(name3, Pair.of(dataType, type)));
          return dataType;
        });
    if (allVars) {
      // We have just created an entry for the moniker "'a option", so now
      // create an entry for the name "option".
      typeByName.putIfAbsent(name, dataType0);
    }
    return dataType0;
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
  public Type recordType(SortedMap<String, ? extends Type> argNameTypes) {
    if (argNameTypes.isEmpty()) {
      return PrimitiveType.UNIT;
    }
    final StringBuilder builder = new StringBuilder("{");
    final ImmutableSortedMap<String, Type> argNameTypes2 =
        ImmutableSortedMap.copyOfSorted(argNameTypes);
    argNameTypes2.forEach((name, type) -> {
      if (builder.length() > 1) {
        builder.append(", ");
      }
      builder.append(name).append(':').append(type.moniker());
    });
    if (areContiguousIntegers(argNameTypes2.keySet())
        && argNameTypes2.size() != 1) {
      return tupleType(ImmutableList.copyOf(argNameTypes2.values()));
    }
    final String description = builder.append('}').toString();
    return this.typeByName.computeIfAbsent(description,
        d -> new RecordType(d, argNameTypes2));
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
    final ImmutableList.Builder<TypeVar> typeVars = ImmutableList.builder();
    for (int i = 0; i < typeCount; i++) {
      typeVars.add(typeVariable(i));
    }
    final ImmutableList<TypeVar> typeVarList = typeVars.build();
    final ForallHelper helper = new ForallHelper() {
      public TypeVar get(int i) {
        return typeVarList.get(i);
      }

      public ListType list(int i) {
        return listType(get(i));
      }

      public Type option(int i) {
        return apply(lookup("'a option"), get(i));
      }

      public FnType predicate(int i) {
        return fnType(get(i), PrimitiveType.BOOL);
      }
    };
    final Type type = builder.apply(helper);
    return forallType(typeVarList, type);
  }

  /** Creates a "forall" type. */
  public Type forallType(Iterable<TypeVar> typeVars, Type type) {
    final StringBuilder b = new StringBuilder();
    b.append("forall");
    for (TypeVar typeVar : typeVars) {
      b.append(' ').append(typeVar.moniker());
    }
    b.append(". ");
    unparse(b, type, 0, 0);

    final String description = b.toString();
    return typeByName.computeIfAbsent(description,
        d -> new ForallType(d, ImmutableList.copyOf(typeVars), type));
  }

  private static StringBuilder unparseList(StringBuilder builder, Op op,
      int left, int right, List<? extends Type> argTypes) {
    Ord.forEach(argTypes, (e, i) -> {
      if (i == 0) {
        unparse(builder, e, left, op.left);
      } else {
        builder.append(op.padded);
        if (i < argTypes.size() - 1) {
          unparse(builder, e, op.right, op.left);
        } else {
          unparse(builder, e, op.right, right);
        }
      }
    });
    return builder;
  }

  private static StringBuilder unparse(StringBuilder builder, Type type,
      int left, int right) {
    final Op op = type.op();
    if (left > op.left || op.right < right) {
      return builder.append("(").append(type.moniker()).append(")");
    } else {
      return builder.append(type.moniker());
    }
  }

  public TemporaryType temporaryType(String name) {
    final TemporaryType temporaryType = new TemporaryType(this, name);
    typeByName.put(name, temporaryType);
    return temporaryType;
  }

  public Pair<DataType, Type> lookupTyCon(String tyConName) {
    return typeConstructorByName.get(tyConName);
  }

  public Type apply(Type type, Type... types) {
    return apply(type, ImmutableList.copyOf(types));
  }

  public Type apply(Type type, List<Type> types) {
    if (type instanceof PolymorphicDataType) {
      // Create a copy of the datatype with type variables substituted with
      // actual types.
      final PolymorphicDataType dataType = (PolymorphicDataType) type;
      assert types.size() == dataType.typeVars.size();
      final TypeVisitor<Type> typeVisitor = new TypeVisitor<Type>() {
        @Override public Type visit(TypeVar typeVar) {
          return types.get(typeVar.ordinal);
        }
      };
      final SortedMap<String, Type> typeConstructors = new TreeMap<>();
      dataType.typeConstructors.forEach((tyConName, tyConType) ->
          typeConstructors.put(tyConName, tyConType.accept(typeVisitor)));
      return dataType(dataType.name, types, typeConstructors);
    }
    final String description = ApplyType.computeDescription(type, types);
    return new ApplyType(type, ImmutableList.copyOf(types), description);
  }

  public TypeVar typeVariable(int ordinal) {
    final String description = "'#" + ordinal;
    return (TypeVar) typeByName.computeIfAbsent(description,
        d -> new TypeVar(ordinal));
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

  /** Visitor that finds all {@link TypeVar} instances within a {@link Type}. */
  private static class VariableCollector extends TypeVisitor<Void> {
    final Set<TypeVar> vars = new LinkedHashSet<>();

    @Override public Void visit(TypeVar typeVar) {
      vars.add(typeVar);
      return super.visit(typeVar);
    }
  }

  /** Placeholder for a type that is being recursively defined.
   *
   * <p>For example, while defining datatype "list" as follows,
   *
   * <blockquote>
   *   <code>datatype 'a list = NIL | CONS of ('a, 'a list)</code>
   * </blockquote>
   *
   * <p>we define a temporary type "list", it is used in {@code CONS}, and
   * later we convert it to the real data type "list".
   */
  public static class TemporaryType implements NamedType {
    private final TypeSystem typeSystem;
    private final String name;

    private TemporaryType(TypeSystem typeSystem, String name) {
      this.typeSystem = Objects.requireNonNull(typeSystem);
      this.name = Objects.requireNonNull(name);
    }

    @Override public String description() {
      return name;
    }

    @Override public Op op() {
      return Op.TEMPORARY_DATA_TYPE;
    }

    @Override public String name() {
      return name;
    }

    public Type copy(TypeSystem typeSystem, Function<Type, Type> transform) {
      return transform.apply(this);
    }

    public <R> R accept(TypeVisitor<R> typeVisitor) {
      throw new UnsupportedOperationException();
    }

    public void delete() {
      this.typeSystem.typeByName.remove(name);
    }
  }

  /** Provides access to type variables from within a call to
   * {@link TypeSystem#forallType(int, Function)}. */
  public interface ForallHelper {
    /** Creates type {@code `i}. */
    TypeVar get(int i);
    /** Creates type {@code `i list}. */
    ListType list(int i);
    /** Creates type {@code `i option}. */
    Type option(int i);
    /** Creates type <code>`i &rarr; bool</code>. */
    FnType predicate(int i);
  }
}

// End TypeSystem.java
