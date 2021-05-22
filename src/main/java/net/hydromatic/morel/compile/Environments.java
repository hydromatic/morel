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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.EvalEnv;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Static;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static net.hydromatic.morel.ast.CoreBuilder.core;

/** Helpers for {@link Environment}. */
public abstract class Environments {

  /** An environment with only "true" and "false". */
  private static final Environment BASIC_ENVIRONMENT =
      EmptyEnvironment.INSTANCE
          .bind(core.idPat(PrimitiveType.BOOL, "true", 0), true)
          .bind(core.idPat(PrimitiveType.BOOL, "false", 0), false);

  private Environments() {}

  /** Creates an empty environment. */
  public static Environment empty() {
    return BASIC_ENVIRONMENT;
  }

  /** Creates an environment containing built-ins and the given foreign
   * values. */
  public static Environment env(TypeSystem typeSystem,
      Map<String, ForeignValue> valueMap) {
    return env(EmptyEnvironment.INSTANCE, typeSystem, valueMap);
  }

  /** Creates a compilation environment, including built-ins and foreign
   * values. */
  private static Environment env(Environment environment, TypeSystem typeSystem,
      Map<String, ForeignValue> valueMap) {
    final List<Binding> bindings = new ArrayList<>();
    BuiltIn.dataTypes(typeSystem, bindings);
    final NameGenerator nameGen = typeSystem.nameGenerator;
    Codes.BUILT_IN_VALUES.forEach((key, value) -> {
      if ("$".equals(key.structure)) {
        return; // ignore Z_ANDALSO, Z_LIST, etc.
      }
      final Type type = key.typeFunction.apply(typeSystem);
      if (key.structure == null) {
        bindings.add(Binding.of(core.idPat(type, key.mlName, nameGen), value));
      }
      if (key.alias != null) {
        bindings.add(Binding.of(core.idPat(type, key.alias, nameGen), value));
      }
    });

    final EvalEnv emptyEnv = Codes.emptyEnv();
    BuiltIn.forEachStructure(typeSystem, (structure, type) ->
        bindings.add(
            Binding.of(core.idPat(type, structure.name, nameGen),
                emptyEnv.getOpt(structure.name))));

    foreignBindings(typeSystem, valueMap, bindings);
    return bind(environment, bindings);
  }

  private static void foreignBindings(TypeSystem typeSystem,
      Map<String, ForeignValue> map, List<Binding> bindings) {
    map.forEach((name, value) -> bindings.add(
        Binding.of(
            core.idPat(value.type(typeSystem), name, typeSystem.nameGenerator),
            value.value())
            .withParameter(true)));
  }

  /** Creates an environment that is a given environment plus bindings. */
  static Environment bind(Environment env, Iterable<Binding> bindings) {
    if (Static.shorterThan(bindings, 5)) {
      for (Binding binding : bindings) {
        env = env.bind(binding);
      }
      return env;
    } else {
      // We assume that the set of bindings does not include two Core.IdPat
      // instances with the same name but different ordinals.
      final ImmutableMap.Builder<String, Binding> b = ImmutableMap.builder();
      bindings.forEach(binding -> b.put(binding.id.name, binding));
      final ImmutableMap<String, Binding> map = b.build();
      final ImmutableSet<String> names = map.keySet();
      env = env.nearestAncestorNotObscuredBy(names);
      return new MapEnvironment(env, map);
    }
  }

  /** Environment that inherits from a parent environment and adds one
   * binding. */
  static class SubEnvironment extends Environment {
    private final Environment parent;
    private final Binding binding;

    SubEnvironment(Environment parent, Binding binding) {
      this.parent = Objects.requireNonNull(parent);
      this.binding = Objects.requireNonNull(binding);
    }

    @Override public Binding getOpt(String name) {
      if (name.equals(binding.id.name)) {
        return binding;
      }
      return parent.getOpt(name);
    }

    @Override public Binding getOpt(Core.IdPat id) {
      if (id.equals(binding.id)) {
        return binding;
      }
      return parent.getOpt(id);
    }

    @Override protected Environment bind(Binding binding) {
      Environment env;
      if (this.binding.id.name.equals(binding.id.name)) {
        // The new binding will obscure the current environment's binding,
        // because it binds a variable of the same name. Bind the parent
        // environment instead. This strategy is worthwhile because it tends to
        // prevent long chains from forming, and allows obscured values to be
        // garbage-collected.
        env = parent;
        while (env instanceof SubEnvironment
            && ((SubEnvironment) env).binding.id.name.equals(binding.id.name)) {
          env = ((SubEnvironment) env).parent;
        }
      } else {
        env = this;
      }
      return new Environments.SubEnvironment(env, binding);
    }

    void visit(Consumer<Binding> consumer) {
      consumer.accept(binding);
      parent.visit(consumer);
    }

    @Override Environment nearestAncestorNotObscuredBy(Set<String> names) {
      return names.contains(binding.id.name)
          ? parent.nearestAncestorNotObscuredBy(names)
          : this;
    }
  }

  /** Empty environment. */
  private static class EmptyEnvironment extends Environment {
    static final EmptyEnvironment INSTANCE = new EmptyEnvironment();

    void visit(Consumer<Binding> consumer) {
    }

    @Override public Binding getOpt(String name) {
      return null;
    }

    @Override public Binding getOpt(Core.IdPat id) {
      return null;
    }

    @Override Environment nearestAncestorNotObscuredBy(Set<String> names) {
      return this;
    }
  }

  /** Environment that keeps bindings in a map. */
  static class MapEnvironment extends Environment {
    private final Environment parent;
    private final Map<String, Binding> map;

    MapEnvironment(Environment parent, ImmutableMap<String, Binding> map) {
      this.parent = Objects.requireNonNull(parent);
      this.map = Objects.requireNonNull(map);
    }

    void visit(Consumer<Binding> consumer) {
      map.values().forEach(consumer);
      parent.visit(consumer);
    }

    public Binding getOpt(String name) {
      final Binding binding = map.get(name);
      return binding != null ? binding : parent.getOpt(name);
    }

    public Binding getOpt(Core.IdPat id) {
      final Binding binding = map.get(id.name);
      return binding != null && binding.id.i == id.i ? binding
          : parent.getOpt(id);
    }

    @Override Environment nearestAncestorNotObscuredBy(Set<String> names) {
      return names.containsAll(map.keySet())
          ? parent.nearestAncestorNotObscuredBy(names)
          : this;
    }
  }
}

// End Environments.java
