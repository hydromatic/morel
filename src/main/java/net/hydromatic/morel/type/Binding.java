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

import java.util.Objects;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.eval.Unit;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Binding of a name to a type and a value.
 *
 * <p>Used in {@link net.hydromatic.morel.compile.Environment}.
 */
public class Binding {
  public final Core.NamedPat id;
  public final Core.Exp exp;
  public final Object value;
  /** If true, the binding is ignored by inlining. */
  public final boolean parameter;
  /** Distinguishes between regular and overloaded values. */
  public final Kind kind;

  public final Core.@Nullable IdPat overloadId;

  private Binding(
      Core.NamedPat id,
      Core.@Nullable IdPat overloadId,
      Core.Exp exp,
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

  public static Binding over(Core.NamedPat id) {
    return new Binding(id, null, null, Unit.INSTANCE, false, Kind.OVER);
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

  public static Binding inst(
      Core.NamedPat id, Core.IdPat overloadId, Object value) {
    return new Binding(id, overloadId, null, value, false, Kind.INST);
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
