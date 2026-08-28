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

/**
 * Visitor over {@link Type} objects.
 *
 * <p>Several methods return null, because most visitors are {@code
 * TypeVisitor<Void>}, and null is the only value of {@code Void}. NullAway
 * cannot express "null if and only if {@code R} is nullable" -- only its
 * JSpecify mode reasons about the nullability of type arguments -- and
 * annotating the methods only moves the complaint to the {@code accept} methods
 * that call them. Hence the suppression.
 *
 * @param <R> return type from {@code visit} methods
 * @see Type#accept(TypeVisitor)
 */
@SuppressWarnings("NullAway")
public class TypeVisitor<R> {
  /** Visits a {@link TypeVar}. */
  public R visit(TypeVar typeVar) {
    return null;
  }

  /** Visits a {@link ListType}. */
  public R visit(ListType listType) {
    return listType.elementType.accept(this);
  }

  /** Visits a {@link FnType}. */
  public R visit(FnType fnType) {
    R r = fnType.paramType.accept(this);
    return fnType.resultType.accept(this);
  }

  /** Visits a {@link TupleType}. */
  public R visit(TupleType tupleType) {
    R r = null;
    for (Type argType : tupleType.argTypes) {
      r = argType.accept(this);
    }
    return r;
  }

  /** Visits a {@link RecordType}. */
  public R visit(RecordType recordType) {
    R r = null;
    for (Type type : recordType.argNameTypes.values()) {
      r = type.accept(this);
    }
    return r;
  }

  /** Visits a {@link DataType}. */
  public R visit(DataType dataType) {
    dataType.parameterTypes.forEach(t -> t.accept(this));
    return null;
  }

  /** Visits an {@link AliasType}. */
  public R visit(AliasType aliasType) {
    aliasType.parameterTypes.forEach(t -> t.accept(this));
    return null;
  }

  /** Visits a {@link PrimitiveType}. */
  public R visit(PrimitiveType primitiveType) {
    return null;
  }

  /** Visits a {@link ForallType}. */
  public R visit(ForallType forallType) {
    return forallType.type.accept(this);
  }

  /** Visits a {@link QualifiedType}. */
  public R visit(QualifiedType qualifiedType) {
    // Visit the predicate types (so their variables are seen, e.g. for
    // quantification) and the body. Do not visit the predicates' candidate
    // instance types: they belong to a separate variable scope.
    for (QualifiedType.Predicate predicate : qualifiedType.predicates) {
      predicate.type.accept(this);
    }
    return qualifiedType.type.accept(this);
  }

  /** Visits a {@link DummyType}. */
  public R visit(DummyType dummyType) {
    return null;
  }
}

// End TypeVisitor.java
