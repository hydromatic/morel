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
package net.hydromatic.morel.compile;

import static com.google.common.collect.Lists.reverse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.eval.EvalEnv;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Environment for validation/compilation.
 *
 * <p>Every environment is immutable; when you call {@link #bind}, a new
 * environment is created that inherits from the previous environment. The new
 * environment may obscure bindings in the old environment, but neither the new
 * nor the old will ever change.
 *
 * <p>To create an empty environment, call {@link Environments#empty()}.
 *
 * @see TypeResolver.TypeEnv
 * @see EvalEnv
 */
public abstract class Environment {
  /**
   * Visits every variable binding in this environment.
   *
   * <p>Bindings that are obscured by more recent bindings of the same name are
   * visited, but after the more obscuring bindings.
   */
  abstract void visit(Consumer<Binding> consumer);

  /**
   * Converts this environment to a string.
   *
   * <p>This method does not override the {@link #toString()} method; if we did,
   * debuggers would invoke it automatically, burning lots of CPU and memory.
   */
  public String asString() {
    final StringBuilder b = new StringBuilder();
    getValueMap().forEach((k, v) -> b.append(v).append("\n"));
    return b.toString();
  }

  /** Returns the binding of {@code name} if bound, null if not. */
  public abstract @Nullable Binding getOpt(String name);

  /** Returns the binding of {@code id} if bound, null if not. */
  public abstract @Nullable Binding getOpt(Core.NamedPat id);

  /**
   * Creates an environment that is the same as a given environment, plus one
   * more variable.
   */
  public Environment bind(Core.IdPat id, Object value) {
    return bind(Binding.of(id, value));
  }

  protected Environment bind(Binding binding) {
    return new Environments.SubEnvironment(this, binding);
  }

  /**
   * Calls a consumer for each variable and its type. Does not visit obscured
   * bindings.
   */
  public void forEachType(
      TypeSystem typeSystem, BiConsumer<String, Type> consumer) {
    final Set<String> names = new HashSet<>();
    visit(
        binding -> {
          if (names.add(binding.id.name)) {
            final Type type =
                binding.value instanceof TypedValue
                    ? ((TypedValue) binding.value).typeKey().toType(typeSystem)
                    : binding.id.type;
            consumer.accept(binding.id.name, type);
          }
        });
  }

  /**
   * Calls a consumer for each variable and its value. Does not visit obscured
   * bindings, or bindings to {@link Unit#INSTANCE}.
   */
  public void forEachValue(BiConsumer<String, Object> consumer) {
    final Set<String> names = new HashSet<>();
    visit(
        binding -> {
          if (names.add(binding.id.name) && binding.value != Unit.INSTANCE) {
            consumer.accept(binding.id.name, binding.value);
          }
        });
  }

  /** Returns a map of the values and bindings. */
  public final Map<String, Binding> getValueMap() {
    final Map<String, Binding> valueMap = new HashMap<>();
    visit(binding -> valueMap.putIfAbsent(binding.id.name, binding));
    return valueMap;
  }

  /**
   * Creates an environment that is the same as this, plus the given bindings.
   */
  public final Environment bindAll(Iterable<Binding> bindings) {
    return Environments.bind(this, bindings);
  }

  /**
   * If this environment only defines bindings in the given set, returns its
   * parent. Never returns null. The empty environment returns itself.
   */
  abstract Environment nearestAncestorNotObscuredBy(Set<Core.NamedPat> names);

  abstract int distance(int soFar, Core.NamedPat id);

  /** Returns this environment plus the bindings in the given environment. */
  public Environment plus(Environment env) {
    final List<Binding> bindingList = new ArrayList<>();
    env.visit(bindingList::add);
    return bindAll(reverse(bindingList));
  }

  /**
   * Creates an environment where all variables have ordinal 0.
   *
   * <p>A rather crude hack that papers over some problems when we evaluate
   * fragments of Morel code in Calcite. The conversion to code currently
   * produces variables with names like "wordCount_1", and the code is run in an
   * environment not exactly the same as that in which it was compiled.
   */
  public Environment renumber() {
    class BindingConsumer implements Consumer<Binding> {
      int changeCount = 0;
      final Set<String> names = new HashSet<>();
      final List<Binding> bindings = new ArrayList<>();

      public void accept(Binding binding) {
        if (!names.add(binding.id.name)) {
          // Don't consider bindings that are overshadowed
          return;
        }
        Binding binding1 = binding.withFlattenedName();
        if (binding1 != binding) {
          ++changeCount;
        }
        bindings.add(binding1);
      };
    }
    BindingConsumer consumer = new BindingConsumer();
    visit(consumer);
    if (consumer.changeCount == 0) {
      return this;
    }
    return Environments.empty().bindAll(consumer.bindings);
  }
}

// End Environment.java
