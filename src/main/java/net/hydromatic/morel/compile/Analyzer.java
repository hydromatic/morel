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

import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shuttle that counts how many times each expression is used.
 */
public class Analyzer extends EnvVisitor {
  private final Map<Core.IdPat, MutableUse> map;

  /** Private constructor. */
  private Analyzer(TypeSystem typeSystem, Environment env,
      Map<Core.IdPat, MutableUse> map) {
    super(typeSystem, env);
    this.map = map;
  }

  /** Analyzes an expression. */
  public static Analysis analyze(TypeSystem typeSystem, Environment env,
      AstNode node) {
    final Map<Core.IdPat, MutableUse> map = new HashMap<>();
    final Analyzer analyzer = new Analyzer(typeSystem, env, map);

    // Mark all top-level bindings so that they will not be removed
    if (node instanceof Core.ValDecl) {
      analyzer.use(((Core.ValDecl) node).pat).top = true;
    }
    node.accept(analyzer);
    return analyzer.result();
  }

  /** Returns the result of an analysis. */
  private Analysis result() {
    final ImmutableMap.Builder<Core.IdPat, Use> b = ImmutableMap.builder();
    map.forEach((k, v) -> b.put(k, v.fix()));
    return new Analysis(b.build());
  }

  @Override protected Analyzer bind(Binding binding) {
    return new Analyzer(typeSystem, env.bind(binding), map);
  }

  @Override protected Analyzer bind(List<Binding> bindingList) {
    // The "!bindingList.isEmpty()" and "env2 != env" checks are optimizations.
    // If you remove them, this method will have the same effect, just slower.
    if (!bindingList.isEmpty()) {
      final Environment env2 = env.bindAll(bindingList);
      if (env2 != env) {
        return new Analyzer(typeSystem, env2, map);
      }
    }
    return this;
  }

  @Override protected void visit(Core.IdPat idPat) {
    use(idPat);
  }

  @Override public void visit(Core.Id id) {
    use(id.idPat).useCount++;
    super.visit(id);
  }

  /** Gets or creates a {@link MutableUse} for a given name. */
  private MutableUse use(Core.IdPat name) {
    return map.computeIfAbsent(name, k -> new MutableUse());
  }

  @Override protected void visit(Core.ValDecl valDecl) {
    super.visit(valDecl);
    if (isAtom(valDecl.exp)) {
      use(valDecl.pat).atomic = true;
    }
  }


  private static boolean isAtom(Core.Exp exp) {
    switch (exp.op) {
    case ID:
    case BOOL_LITERAL:
    case CHAR_LITERAL:
    case INT_LITERAL:
    case REAL_LITERAL:
    case STRING_LITERAL:
    case UNIT_LITERAL:
      return true;
    default:
      return false;
    }
  }

  @Override protected void visit(Core.Case kase) {
    kase.exp.accept(this);
    if (kase.matchList.size() == 1) {
      // When there is a single branch, we don't need to check for a single use
      // on multiple branches, so we can expedite.
      kase.matchList.get(0).accept(this);
    } else {
      // Create a multi-map of all of the uses of bindings along the separate
      // branches. Example:
      //  case e of
      //    1 => a + c
      //  | 2 => a + b + a
      //  | _ => c
      //
      // a has use counts [1, 2] and is therefore MULTI_UNSAFE
      // b has use counts [1] and is therefore ONCE_SAFE
      // c has use counts [1, 1] and is therefore MULTI_SAFE
      final Multimap<Core.IdPat, MutableUse> multimap = HashMultimap.create();
      final Map<Core.IdPat, MutableUse> subMap = new HashMap<>();
      final Analyzer analyzer = new Analyzer(typeSystem, env, subMap);
      kase.matchList.forEach(e -> {
        subMap.clear();
        e.accept(analyzer);
        subMap.forEach(multimap::put);
      });
      multimap.asMap().forEach((id, uses) -> {
        final MutableUse baseUse = use(id);
        int maxCount = MutableUse.max(uses);
        if (uses.size() > 1) {
          baseUse.parallel = true;
        }
        baseUse.useCount += maxCount;
      });
    }
  }

  /** How a binding (assignment of a value to a variable) is used. */
  public enum Use {
    /** Indicates that the binding cannot be inlined because recursively
     * refers to itself (or more precisely, is part of a recursive cycle
     * and has been chosen as the link to remove to break the cycle). */
    LOOP_BREAKER,

    /** Binding is not used. For a let (whether recursive or not), the binding
     * can be discarded. */
    DEAD,

    /** The binding occurs exactly once, and that occurrence is not inside a
     * lambda, nor is a constructor argument. Inlining is unconditionally safe;
     * it duplicates neither code nor work. */
    ONCE_SAFE,

    /** The binding is an atom (variable or literal). Regardless of how many
     * times it is used, inlining is unconditionally safe;
     * it duplicates neither code nor work. */
    ATOMIC,

    /** The binding occurs at most once in each of several distinct case
     * branches; none of these occurrences is inside a lambda. For example:
     *
     * <pre>{@code
     * case xs of
     *   [] => y + 1
     * | x :: xs => y + 2
     * }</pre>
     *
     * <p>In this expression, {@code y} occurs only once in each case branch.
     * Inlining {@code y} may duplicate code, but it will not duplicate work. */
    MULTI_SAFE,

    /** The binding occurs exactly once, but inside a lambda. Inlining will not
     * duplicate code, but it might duplicate work.
     *
     * <p>We must not inline an arbitrary expression inside a lambda, as the
     * following example (from GHC inlining section 2.2) shows:
     *
     * <pre>{@code
     * val f = fn x => E
     * val g = fn ys => map f ys
     * }</pre>
     *
     * <p>If we were to inline f inside g, thus:
     *
     * <pre>{@code
     * val g = fn ys => map (fn x => E) ys
     * }</pre>
     *
     * no code is duplicated, but a small bounded amount of work is duplicated,
     * because the closure {@code fn x => E} must be allocated each time
     * {@code g} is called. */
    ONCE_UNSAFE,

    /** The binding may occur many times, including inside lambdas. */
    MULTI_UNSAFE,
  }

  /** Work space where the uses of a binding are counted. When all the uses
   * have been found, call {@link #fix} to convert this into a {@link Use}. */
  private static class MutableUse {
    boolean top;
    boolean atomic;
    boolean parallel;
    int useCount;

    static int max(Collection<MutableUse> uses) {
      int max = 0;
      for (MutableUse use : uses) {
        max = Math.max(max, use.useCount);
      }
      return max;
    }

    @Override public String toString() {
      return "[" + useCount + (parallel ? " parallel]" : "]");
    }

    Use fix() {
      return top ? Use.MULTI_UNSAFE
          : useCount == 0 ? Use.DEAD
          : atomic ? Use.ATOMIC
          : useCount == 1 ? (parallel ? Use.MULTI_SAFE : Use.ONCE_SAFE)
          : Use.MULTI_UNSAFE;
    }
  }

  /** Result of an analysis. */
  public static class Analysis {
    public final ImmutableMap<Core.IdPat, Use> map;

    Analysis(ImmutableMap<Core.IdPat, Use> map) {
      this.map = map;
    }
  }
}

// End Analyzer.java
