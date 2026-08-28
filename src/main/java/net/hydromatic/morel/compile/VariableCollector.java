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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BiConsumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Pair;
import org.jspecify.annotations.Nullable;

/**
 * Shuttle that identifies variables that are defined outside the scope of a
 * given function body — i.e., the free (captured) variables.
 *
 * <p>Free variables must be captured into a closure at closure-creation time.
 * This information is used when compiling functions to compute {@code
 * captureCodes}: the set of stack-reads that snapshot outer variables into the
 * new closure.
 *
 * <p>Usage:
 *
 * <blockquote>
 *
 * <pre>{@code
 * Map<Core.NamedPat, Scope> scopeMap = new LinkedHashMap<>();
 * VariableCollector.create(typeSystem, outerEnv,
 *     (id, scope) -> scopeMap.put(id.idPat, scope))
 *     .accept(fnExpression);
 * }</pre>
 *
 * </blockquote>
 *
 * <p>The {@code outerEnv} should be the environment in effect <em>before</em>
 * entering the function being analyzed. Only variables that appear in {@code
 * outerEnv} (and are referenced inside the function body, possibly through
 * nested lambda boundaries) are reported.
 *
 * <p>Variables that are local to the function (the argument, let-bindings, or
 * pattern-match bindings) are never reported, because they are not in {@code
 * outerEnv}.
 *
 * <p>Note: Recursive variables (those declared in a {@code val rec} that
 * defines the function itself) are reported with scope {@link Scope#FREE} here.
 * The compiler is responsible for re-classifying them as {@link Scope#REC}
 * after running the collector.
 */
abstract class VariableCollector extends EnvVisitor {
  /**
   * Stack of lambda-boundary environments. Each time we cross a {@code fn}
   * boundary (or a {@code RecValDecl} boundary), we push the environment at
   * that point. A variable reference is only reported if it was declared in an
   * environment that is an ancestor of (or equal to) the current innermost
   * lambda boundary.
   *
   * <p>This prevents double-reporting: if the same variable is referenced both
   * in the outer function and in a nested lambda, it is only reported once (at
   * the outermost crossing).
   */
  protected final Deque<Environment> lambdaEnvStack;

  private VariableCollector(
      TypeSystem typeSystem,
      Environment env,
      Deque<FromContext> fromStack,
      Deque<Environment> lambdaEnvStack) {
    super(typeSystem, env, fromStack);
    this.lambdaEnvStack = lambdaEnvStack;
  }

  /**
   * Creates a {@link VariableCollector} that reports free variables found in
   * the expression(s) passed to {@link #accept}.
   *
   * @param typeSystem Type system
   * @param env The environment in effect outside the function being analyzed.
   *     Variables present here (and referenced inside) are reported as free.
   * @param consumer Called for each free variable found. The first argument is
   *     the {@link Core.Id} reference node; the second is the {@link Scope}.
   */
  public static VariableCollector create(
      TypeSystem typeSystem,
      Environment env,
      BiConsumer<Core.Id, Scope> consumer) {
    return new RootVariableCollector(typeSystem, env, consumer);
  }

  @Override
  protected VariableCollector push(Environment env) {
    return new SubVariableCollector(env, this);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Overridden to push a lambda-boundary environment onto {@link
   * #lambdaEnvStack} before visiting the function body. This allows {@link
   * RootVariableCollector#visitX} to distinguish between variables declared
   * inside the current function (not reported) and variables declared in an
   * outer scope (reported as free).
   */
  @Override
  protected void visit(Core.Fn fn) {
    fn.idPat.accept(this);
    try {
      lambdaEnvStack.push(env);
      fn.exp.accept(bind(Binding.of(fn.idPat)));
    } finally {
      lambdaEnvStack.pop();
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Overridden to push a lambda-boundary environment before visiting each
   * declaration body, so that recursive references within the group are
   * properly tracked. The recursive names are bound into the environment (as in
   * {@link EnvVisitor#visit(Core.RecValDecl)}), and that extended environment
   * is used as the lambda boundary.
   */
  @Override
  protected void visit(Core.RecValDecl recValDecl) {
    final List<Binding> bindings = new ArrayList<>();
    recValDecl.list.forEach(decl -> Compiles.acceptBinding(decl.pat, bindings));
    final VariableCollector v2 = (VariableCollector) bind(bindings);
    try {
      lambdaEnvStack.push(v2.env);
      recValDecl.list.forEach(v2::accept);
    } finally {
      lambdaEnvStack.pop();
    }
  }

  @Override
  protected void visit(Core.Id id) {
    visitX(id);
  }

  /** Handles a variable reference. */
  abstract void visitX(Core.Id id);

  /** Variable collector at the root (outermost) level. */
  private static class RootVariableCollector extends VariableCollector {
    final BiConsumer<Core.Id, Scope> consumer;

    RootVariableCollector(
        TypeSystem typeSystem,
        Environment env,
        BiConsumer<Core.Id, Scope> consumer) {
      super(typeSystem, env, new ArrayDeque<>(), new ArrayDeque<>());
      this.consumer = consumer;
    }

    /**
     * Checks whether this variable reference is a free variable.
     *
     * <p>A variable is free if:
     *
     * <ol>
     *   <li>It is found in the root environment ({@code this.env}) — meaning it
     *       was declared in the outer scope, not locally inside the function.
     *   <li>At least one lambda boundary has been crossed (the variable is
     *       referenced inside a {@code fn} or {@code RecValDecl}).
     *   <li>The innermost lambda-boundary environment is an ancestor of (or
     *       equal to) the declaring environment — ensuring the variable was
     *       declared before or at the lambda boundary, not inside it.
     * </ol>
     */
    @Override
    void visitX(Core.Id id) {
      final @Nullable Pair<Binding, Environment> pair = env.getOpt2(id.idPat);
      if (pair != null && !lambdaEnvStack.isEmpty()) {
        final Environment lambdaBoundary = lambdaEnvStack.peek();
        // A variable is free if it was declared before (or at) the innermost
        // lambda boundary. "pair.right" is the declaring environment;
        // if it is an ancestor of (i.e., older than) the lambda boundary, the
        // variable comes from an outer scope.
        if (pair.right.isAncestorOf(lambdaBoundary)) {
          consumer.accept(id, Scope.FREE);
        }
      }
    }
  }

  /**
   * Variable collector at an inner (nested) level. Delegates {@code visitX} to
   * the parent collector so that free-variable detection always uses the
   * original outer environment.
   */
  private static class SubVariableCollector extends VariableCollector {
    final VariableCollector parent;

    SubVariableCollector(Environment env, VariableCollector parent) {
      super(parent.typeSystem, env, parent.fromStack, parent.lambdaEnvStack);
      this.parent = parent;
    }

    @Override
    void visitX(Core.Id id) {
      parent.visitX(id);
    }
  }

  /** Whether a free variable is ordinary or recursive. */
  public enum Scope {
    /**
     * Variable is free in the function body — it was declared in an enclosing
     * scope and must be captured into the closure.
     *
     * <p>Example: in {@code fn x => x + y}, {@code y} is free.
     */
    FREE,

    /**
     * Variable is the function's own recursive reference — it refers to the
     * closure currently being created.
     *
     * <p>This scope is assigned by the compiler (not by {@link
     * VariableCollector} itself) after identifying which free variables
     * correspond to the function's own name in a {@code val rec} declaration.
     *
     * <p>Example: in {@code val rec f = fn i => if i = 0 then 0 else f (i-1)},
     * {@code f} inside the body is recursive.
     */
    REC
  }
}

// End VariableCollector.java
