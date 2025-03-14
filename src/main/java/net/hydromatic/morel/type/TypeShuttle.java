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

/** Visitor over {@link Type} objects that returns types. */
public class TypeShuttle extends TypeVisitor<Type> {
  private final TypeSystem typeSystem;

  protected TypeShuttle(TypeSystem typeSystem) {
    this.typeSystem = typeSystem;
  }

  @Override
  public Type visit(TypeVar typeVar) {
    return typeVar.copy(typeSystem, t -> t.accept(this));
  }

  @Override
  public ListType visit(ListType listType) {
    return listType.copy(typeSystem, t -> t.accept(this));
  }

  @Override
  public FnType visit(FnType fnType) {
    return fnType.copy(typeSystem, t -> t.accept(this));
  }

  @Override
  public TupleType visit(TupleType tupleType) {
    return tupleType.copy(typeSystem, t -> t.accept(this));
  }

  @Override
  public RecordType visit(RecordType recordType) {
    return recordType.copy(typeSystem, t -> t.accept(this));
  }

  @Override
  public Type visit(DataType dataType) {
    return dataType.copy(typeSystem, t -> t.accept(this));
  }

  @Override
  public PrimitiveType visit(PrimitiveType primitiveType) {
    return primitiveType.copy(typeSystem, t -> t.accept(this));
  }

  @Override
  public ForallType visit(ForallType forallType) {
    return forallType.copy(typeSystem, t -> t.accept(this));
  }

  @Override
  public DummyType visit(DummyType dummyType) {
    return dummyType.copy(typeSystem, t -> t.accept(this));
  }
}

// End TypeShuttle.java
