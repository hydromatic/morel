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

import static net.hydromatic.morel.util.Pair.allMatch;

import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import net.hydromatic.morel.ast.Op;
import org.checkerframework.checker.nullness.qual.Nullable;

public class TypeUnifier {

  private final Map<Integer, Type> variables = new HashMap<>();

  public static @Nullable Map<Integer, Type> unify(Type type1, Type type2) {
    TypeUnifier unifier = new TypeUnifier();
    if (unifier.tryUnify(type1, type2)) {
      return ImmutableMap.copyOf(unifier.variables);
    } else {
      return null;
    }
  }

  boolean tryUnify(Type type1, Type type2) {
    final DataType dataType1;
    final DataType dataType2;
    final TupleType tuple1;
    final TupleType tuple2;
    final RecordType record1;
    final RecordType record2;
    final ListType list1;
    final ListType list2;
    final PrimitiveType primitiveType1;
    final PrimitiveType primitiveType2;
    final TypeVar var1;
    final TypeVar var2;

    if (type2.op() == Op.TY_VAR && type1.op() != Op.TY_VAR) {
      return tryUnify(type2, type1);
    }
    switch (type1.op()) {
      case TY_VAR:
        var1 = (TypeVar) type1;
        @Nullable Type type1b = variables.get(var1.ordinal);
        if (type1b == null) {
          variables.put(var1.ordinal, type2);
          return true;
        } else {
          return tryUnify(type1b, type2);
        }

      case DATA_TYPE:
        dataType1 = (DataType) type1;
        switch (type2.op()) {
          case DATA_TYPE:
            dataType2 = (DataType) type2;
            return dataType1.name.equals(dataType2.name)
                && allMatch(
                    dataType1.arguments, dataType2.arguments, this::tryUnify);

          default:
            return false;
        }

      case TUPLE_TYPE:
        tuple1 = (TupleType) type1;
        switch (type2.op()) {
          case TUPLE_TYPE:
            tuple2 = (TupleType) type2;
            return tuple1.argTypes.size() == tuple2.argTypes.size()
                && allMatch(tuple1.argTypes, tuple2.argTypes, this::tryUnify);

          default:
            return false;
        }

      case RECORD_TYPE:
        record1 = (RecordType) type1;
        switch (type2.op()) {
          case RECORD_TYPE:
            record2 = (RecordType) type2;
            return record1.argNameTypes.size() == record2.argNameTypes.size()
                && record1
                    .argNameTypes
                    .keySet()
                    .equals(record2.argNameTypes.keySet())
                && allMatch(
                    record1.argNameTypes.values(),
                    record2.argNameTypes.values(),
                    this::tryUnify);

          default:
            return false;
        }

      case LIST:
        list1 = (ListType) type1;
        switch (type2.op()) {
          case LIST:
            list2 = (ListType) type2;
            return tryUnify(list1.elementType, list2.elementType);

          default:
            return false;
        }

      case ID:
        primitiveType1 = (PrimitiveType) type1;
        switch (type2.op()) {
          case ID:
            primitiveType2 = (PrimitiveType) type2;
            return primitiveType1 == primitiveType2;

          default:
            return false;
        }

      default:
        return false;
    }
  }
}

// End TypeUnifier.java
