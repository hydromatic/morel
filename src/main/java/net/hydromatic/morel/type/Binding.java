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
import static java.util.Objects.requireNonNull;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.eval.Unit;
import org.jspecify.annotations.Nullable;

/**
 * Binding of a name to a type and a value.
 *
 * <p>Used in {@link net.hydromatic.morel.compile.Environment}.
 */
public class Binding {
  /** Compares bindings by name. */
  public static final Comparator<Binding> COMPARATOR =
      Comparator.comparing((Binding b) -> b.id.name, RecordType.ORDERING);

  public final Core.NamedPat id;
  public final Core.@Nullable Exp exp;
  public final Object value;
  /** If true, the binding is ignored by inlining. */
  public final boolean parameter;
  /** Distinguishes between regular and overloaded values. */
  public final Kind kind;

  public final Core.@Nullable IdPat overloadId;

  private Binding(
      Core.NamedPat id,
      Core.@Nullable IdPat overloadId,
      Core.@Nullable Exp exp,
      Object value,
      boolean parameter,
      Kind kind) {
    this.id = requireNonNull(id);
    this.overloadId = overloadId;
    this.exp = exp;
    this.value = requireNonNull(value);
    this.parameter = parameter;
    this.kind = requireNonNull(kind);
    checkArgument(!(value instanceof Core.IdPat));
    checkArgument((kind == Kind.INST) == (overloadId != null));
  }

  public static Binding of(Core.NamedPat id) {
    return new Binding(id, null, null, Unit.INSTANCE, false, Kind.VAL);
  }

  public static Binding over(Core.NamedPat id, Object value) {
    return new Binding(id, null, null, value, false, Kind.OVER);
  }

  public static Binding over(Core.NamedPat id) {
    return over(id, Unit.INSTANCE);
  }

  public static Binding of(Core.NamedPat id, Core.Exp exp) {
    return new Binding(id, null, exp, Unit.INSTANCE, false, Kind.VAL);
  }

  public static Binding inst(
      Core.NamedPat id, Core.IdPat overloadId, Core.Exp exp) {
    return new Binding(id, overloadId, exp, Unit.INSTANCE, false, Kind.INST);
  }

  public static Binding of(Core.NamedPat id, Object value) {
    return new Binding(id, null, null, value, false, Kind.VAL);
  }

  public static Binding of(
      Core.NamedPat id, Core.@Nullable Exp exp, Object value) {
    return new Binding(id, null, exp, value, false, Kind.VAL);
  }

  public static Binding inst(
      Core.NamedPat id, Core.IdPat overloadId, Object value) {
    return new Binding(id, overloadId, null, value, false, Kind.INST);
  }

  public static Binding inst(
      Core.NamedPat id,
      Core.IdPat overloadId,
      Core.@Nullable Exp exp,
      Object value) {
    return new Binding(id, overloadId, exp, value, false, Kind.INST);
  }

  /** Used by {@link Environment#renumber()}. */
  public Binding withFlattenedName() {
    if (id.i == 0) {
      return this;
    }
    Core.NamedPat id1 = id.withName(id.name + '_' + id.i);
    return new Binding(id1, overloadId, exp, value, parameter, kind);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, exp, value);
  }

  @Override
  public boolean equals(Object o) {
    return this == o
        || o instanceof Binding
            && id.equals(((Binding) o).id)
            && Objects.equals(exp, ((Binding) o).exp)
            && value.equals(((Binding) o).value);
  }

  public Binding withParameter(boolean parameter) {
    return parameter == this.parameter
        ? this
        : new Binding(id, overloadId, exp, value, parameter, kind);
  }

  public Binding withKind(Kind kind) {
    return kind == this.kind
        ? this
        : new Binding(id, overloadId, exp, value, parameter, kind);
  }

  /** Returns whether this binding is an instance of an overloaded name. */
  public boolean isInst() {
    return kind == Kind.INST;
  }

  /**
   * Returns whether {@code bindings} matches the fields of {@code recordType}
   * exactly: there are as many fields as bindings, and each binding has the
   * same name and type as a field. Type is compared as well as name because
   * {@link Binding} equality ignores type; a row binder whose name equals its
   * record's sole field name has a binding named like the field but typed as
   * the whole record, not the field.
   */
  public static boolean matchesFields(
      List<Binding> bindings, RecordLikeType recordType) {
    if (bindings.size() != recordType.argNameTypes().size()) {
      return false;
    }
    for (Binding binding : bindings) {
      final Type fieldType = recordType.argNameTypes().get(binding.id.name);
      if (!binding.id.type.equals(fieldType)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns whether two binding lists are equal. If {@code compareTypes}, also
   * requires corresponding bindings to have equal types. {@link Binding}
   * equality (via {@link Core.NamedPat}) compares name and ordinal but ignores
   * type, so a row binder can give a binding the same name and ordinal as its
   * input but a different type, which {@link List#equals} alone would miss.
   */
  public static boolean listsEqual(
      List<Binding> bindings, List<Binding> bindings2, boolean compareTypes) {
    if (!bindings.equals(bindings2)) {
      return false;
    }
    if (compareTypes) {
      for (int i = 0; i < bindings.size(); i++) {
        if (!bindings.get(i).id.type.equals(bindings2.get(i).id.type)) {
          return false;
        }
      }
    }
    return true;
  }

  @Override
  public String toString() {
    if (exp != null) {
      return id + " = " + exp;
    } else if (value == Unit.INSTANCE) {
      return id + " : " + id.type.moniker();
    } else {
      return id + " = " + value + " : " + id.type.moniker();
    }
  }

  /** What kind of binding? */
  public enum Kind {
    /** Regular, non-overloaded binding ({@code val}). */
    VAL,
    /** Declaration that a name is overloaded ({@code over}). */
    OVER,
    /** Instance of an overloaded name ({@code val inst}). */
    INST,
  }
}

// End Binding.java
