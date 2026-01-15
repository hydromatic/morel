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

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Static.SKIP;
import static net.hydromatic.morel.util.Static.last;
import static net.hydromatic.morel.util.Static.shorterThan;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.EvalEnv;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.MultiType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.checkerframework.checker.nullness.qual.Nullable;

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

  /**
   * Creates an environment containing built-ins and the given foreign values.
   */
  public static Environment env(
      TypeSystem typeSystem,
      @Nullable Session session,
      Map<String, ForeignValue> valueMap) {
    return env(EmptyEnvironment.INSTANCE, typeSystem, session, valueMap);
  }

  /**
   * Creates a compilation environment, including built-ins and foreign values.
   */
  private static Environment env(
      Environment environment,
      TypeSystem typeSystem,
      @Nullable Session session,
      Map<String, ForeignValue> valueMap) {
    if (SKIP) {
      return environment;
    }
    final List<Binding> bindings = new ArrayList<>();
    BuiltIn.dataTypes(typeSystem, bindings);
    final ToIntFunction<String> nameGen = typeSystem.nameGenerator::inc;
    Codes.BUILT_IN_VALUES.forEach(
        (key, value) -> {
          if ("$".equals(key.structure)) {
            return; // ignore Z_ANDALSO, Z_LIST, etc.
          }
          final Type type = key.typeFunction.apply(typeSystem);
          if (key.sessionValue != null) {
            if (session == null) {
              return;
            }
            value = key.sessionValue.apply(session);
          }
          // If type is a MultiType, define several overloads.
          if (type instanceof MultiType) {
            List<Type> types = ((MultiType) type).types;
            Core.IdPat overloadId =
                core.idPat(types.get(0), key.mlName, nameGen);
            final Supplier<String> nameGen2 =
                () -> typeSystem.nameGenerator.getPrefixed(overloadId.name);
            for (int i = 0; i < types.size(); i++) {
              Type type1 = types.get(i);
              if (key.structure == null) {
                if (i == 0) {
                  bindings.add(Binding.over(overloadId, value));
                }
                Core.IdPat id = core.idPat(type1, nameGen2);
                bindings.add(Binding.inst(id, overloadId, value));
              }
              if (key.alias != null) {
                if (i == 0) {
                  bindings.add(Binding.over(overloadId, value));
                }
                Core.IdPat id = core.idPat(type1, nameGen2);
                bindings.add(Binding.inst(id, overloadId, value));
              }
            }
          } else {
            if (key.structure == null) {
              bindings.add(
                  Binding.of(core.idPat(type, key.mlName, nameGen), value));
            }
            if (key.alias != null) {
              bindings.add(
                  Binding.of(core.idPat(type, key.alias, nameGen), value));
            }
          }
        });

    final EvalEnv emptyEnv = Codes.emptyEnv();
    BuiltIn.forEachStructure(
        typeSystem,
        (structure, type) ->
            bindings.add(
                Binding.of(
                    core.idPat(type, structure.name, nameGen),
                    requireNonNull(emptyEnv.getOpt(structure.name)))));

    foreignBindings(typeSystem, valueMap, bindings);
    return bind(environment, bindings);
  }

  private static void foreignBindings(
      TypeSystem typeSystem,
      Map<String, ForeignValue> map,
      List<Binding> bindings) {
    map.forEach(
        (name, value) ->
            bindings.add(
                Binding.of(
                        core.idPat(
                            value.type(typeSystem),
                            name,
                            typeSystem.nameGenerator::inc),
                        value.value())
                    .withParameter(true)));
  }

  /** Creates an environment that is a given environment plus bindings. */
  static Environment bind(Environment env, Iterable<Binding> bindings) {
    if (shorterThan(bindings, 5)) {
      for (Binding binding : bindings) {
        env = env.bind(binding);
      }
      return env;
    } else {
      // We assume that the set of bindings does not include two Core.IdPat
      // instances with the same name but different ordinals.
      //
      // Instances (overloaded bindings) go in multimap, separate from the
      // regular map for 'val' bindings, because there may be several instances
      // with the same name.
      final ImmutableMap.Builder<Core.NamedPat, Binding> builder =
          ImmutableMap.builder();
      final ImmutableMultimap.Builder<Core.IdPat, Binding> instanceBuilder =
          ImmutableMultimap.builder();
      for (Binding binding : bindings) {
        if (binding.isInst()) {
          instanceBuilder.put(requireNonNull(binding.overloadId), binding);
        } else {
          builder.put(binding.id, binding);
        }
      }
      final ImmutableMap<Core.NamedPat, Binding> map = builder.build();
      final ImmutableMultimap<Core.IdPat, Binding> instanceMap =
          instanceBuilder.build();
      if (instanceMap.isEmpty()) {
        // Optimize by skipping ancestor environments that are completely
        // obscured. If there are overloaded bindings, the optimization logic
        // would be complicated, so don't even try.
        env = env.nearestAncestorNotObscuredBy(map.keySet());
      }
      return new MapEnvironment(env, map, instanceMap);
    }
  }

  /**
   * Environment that inherits from a parent environment and adds one binding.
   */
  static class SubEnvironment extends Environment {
    private final Environment parent;
    private final Binding binding;

    SubEnvironment(Environment parent, Binding binding) {
      this.parent = requireNonNull(parent);
      this.binding = requireNonNull(binding);
    }

    @Override
    public String toString() {
      return binding.id + ", ...";
    }

    @Override
    public @Nullable Binding getTop(String name) {
      if (binding.id.name.equals(name)) {
        return binding;
      }
      if (binding.overloadId != null && binding.overloadId.name.equals(name)) {
        return binding;
      }
      return parent.getTop(name);
    }

    @Override
    public @Nullable Binding getOpt(Core.NamedPat id) {
      if (binding.id.equals(id)) {
        return binding;
      }
      if (binding.overloadId != null && binding.overloadId.equals(id)) {
        return binding;
      }
      return parent.getOpt(id);
    }

    @Override
    public @Nullable Binding getOpt2(Core.NamedPat id) {
      if (binding.id.equals(id)) {
        return binding;
      }
      if (binding.overloadId != null && binding.overloadId.equals(id)) {
        return binding;
      }
      return parent.getOpt2(id);
    }

    @Override
    public void collect(Core.NamedPat id, Consumer<Binding> consumer) {
      if (id.equals(binding.overloadId) || id.equals(binding.id)) { // TODO
        switch (binding.kind) {
          case VAL:
            // Send this binding to the consumer. It obscures all other
            // bindings, so we're done.
            consumer.accept(binding);
            return;
          case OVER:
            // We have hit the 'over <id>' declaration. There are no more
            // instances to see.
            return;
          case INST:
            // Send this instance to the consumer, but carry on looking for
            // more.
            consumer.accept(binding);
            break;
        }
      }
      parent.collect(id, consumer);
    }

    @Override
    protected Environment bind(Binding binding) {
      Environment env;
      if (this.binding.id.equals(binding.id)
          && binding.kind == Binding.Kind.VAL) {
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

    @Override
    void visit(Consumer<Binding> consumer) {
      consumer.accept(binding);
      parent.visit(consumer);
    }

    @Override
    Environment nearestAncestorNotObscuredBy(Set<Core.NamedPat> names) {
      return names.contains(binding.id)
          ? parent.nearestAncestorNotObscuredBy(names)
          : this;
    }

    @Override
    int distance(int soFar, Core.NamedPat id) {
      if (id.equals(this.binding.id)) {
        return soFar;
      } else {
        return parent.distance(soFar + 1, id);
      }
    }
  }

  /** Empty environment. */
  private static class EmptyEnvironment extends Environment {
    static final EmptyEnvironment INSTANCE = new EmptyEnvironment();

    @Override
    void visit(Consumer<Binding> consumer) {}

    @Override
    public @Nullable Binding getTop(String name) {
      return null;
    }

    @Override
    public @Nullable Binding getOpt(Core.NamedPat id) {
      return null;
    }

    @Override
    public @Nullable Binding getOpt2(Core.NamedPat id) {
      return null;
    }

    @Override
    public void collect(Core.NamedPat id, Consumer<Binding> consumer) {
      // do nothing
    }

    @Override
    Environment nearestAncestorNotObscuredBy(Set<Core.NamedPat> names) {
      return this;
    }

    @Override
    int distance(int soFar, Core.NamedPat id) {
      return -1;
    }
  }

  /** Environment that keeps bindings in a map. */
  static class MapEnvironment extends Environment {
    private final Environment parent;
    private final Map<Core.NamedPat, Binding> map;
    private final ImmutableMultimap<Core.IdPat, Binding> instanceMap;

    MapEnvironment(
        Environment parent,
        ImmutableMap<Core.NamedPat, Binding> map,
        ImmutableMultimap<Core.IdPat, Binding> instanceMap) {
      this.parent = requireNonNull(parent);
      this.map = requireNonNull(map);
      this.instanceMap = requireNonNull(instanceMap);
    }

    @Override
    void visit(Consumer<Binding> consumer) {
      map.values().forEach(consumer);
      instanceMap.values().forEach(consumer);
      parent.visit(consumer);
    }

    @Override
    public @Nullable Binding getTop(String name) {
      final List<Binding> bindings = new ArrayList<>();
      map.values()
          .forEach(
              binding -> {
                if (binding.id.name.equals(name)) {
                  bindings.add(binding);
                }
              });
      instanceMap
          .asMap()
          .forEach(
              (overloadId, bindings1) -> {
                if (overloadId.name.equals(name)) {
                  bindings.addAll(bindings1);
                }
              });
      if (!bindings.isEmpty()) {
        return last(bindings);
      }
      return parent.getTop(name);
    }

    @Override
    public @Nullable Binding getOpt(Core.NamedPat id) {
      final Binding binding = map.get(id);
      if (binding != null && binding.id.i == id.i) {
        return binding;
      }
      for (Binding binding2 : instanceMap.values()) {
        if (binding2.id.equals(id)) {
          return binding2;
        }
      }
      return parent.getOpt(id);
    }

    @Override
    public @Nullable Binding getOpt2(Core.NamedPat id) {
      final Binding binding = map.get(id);
      if (binding != null && binding.id.i == id.i) {
        return binding;
      }
      return parent.getOpt2(id);
    }

    @Override
    public void collect(Core.NamedPat id, Consumer<Binding> consumer) {
      final Binding binding = map.get(id);
      final ImmutableCollection<Binding> instBindings =
          id instanceof Core.IdPat
              ? instanceMap.get((Core.IdPat) id)
              : ImmutableList.of();
      if (binding != null && binding.kind != Binding.Kind.OVER) {
        // Send this binding to the consumer. It obscures all other
        // bindings, so we're done.
        if (!instBindings.isEmpty()) {
          // We have not yet written the code to handle the case where a
          // MapEnvironment contains VAL and INST bindings for the same id.
          // It is not possible to figure out bindings came later.
          throw new UnsupportedOperationException();
        }
        consumer.accept(binding);
        return;
      }

      // Traverse the instance bindings in reverse order, so that we see
      // instances before overload declarations.
      for (Binding instBinding : instBindings.asList().reverse()) {
        switch (instBinding.kind) {
          case OVER:
            // We have hit the 'over <id>' declaration. There are no more
            // instances to see.
            return;
          case INST:
            // Send this instance to the consumer, but carry on looking for
            // more.
            consumer.accept(instBinding);
            break;
        }
      }
      parent.collect(id, consumer);
    }

    @Override
    Environment nearestAncestorNotObscuredBy(Set<Core.NamedPat> names) {
      return names.containsAll(map.keySet())
          ? parent.nearestAncestorNotObscuredBy(names)
          : this;
    }

    @Override
    int distance(int soFar, Core.NamedPat id) {
      final int i = find(map.keySet(), id);
      if (i >= 0) {
        return soFar + map.size() - 1 - i;
      } else {
        return parent.distance(soFar + map.size(), id);
      }
    }

    private <E> int find(Iterable<E> iterable, E e) {
      int i = 0;
      for (E e1 : iterable) {
        if (e1.equals(e)) {
          return i;
        }
        ++i;
      }
      return -1;
    }
  }
}

// End Environments.java
