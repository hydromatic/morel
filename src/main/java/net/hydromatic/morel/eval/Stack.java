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
package net.hydromatic.morel.eval;

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Evaluation stack for the Morel interpreter.
 *
 * <p>{@code Stack} replaces the linked-map {@link EvalEnv} as the primary
 * carrier of local variable bindings during evaluation. Local variables are
 * stored in a flat {@code Object[]} array at compile-time-computed offsets,
 * giving O(1) access without walking an environment chain.
 *
 * <p>Global (top-level and built-in) bindings are accessed via {@link
 * Session#globalEnv}, a flat {@link HashMap} that row-sink and aggregate code
 * temporarily mutate (put + restore) during relational evaluation.
 *
 * <p>{@code Stack} is shared across a function call chain. Built-in functions
 * that do not bind local variables pass the {@code Stack} through unchanged;
 * user-defined functions push their arguments and let-bindings onto {@link
 * #slots} and pop them on return.
 *
 * <p>{@code Stack} is NOT thread-safe. Each evaluation thread must use its own
 * {@code Stack} instance.
 */
public final class Stack {
  /** Empty stack with an empty session, for trivial calculations. */
  private static final Stack EMPTY = new Stack(Session.EMPTY, new Object[0], 0);

  /**
   * The current session.
   *
   * <p>Provides access to {@link Session#globalEnv}, the authoritative
   * environment for top-level declarations and built-ins. Row-sink and
   * aggregate code temporarily extend {@code session.globalEnv} during
   * evaluation.
   *
   * <p>Is {@link Session#EMPTY} when a stack is created for compile-time
   * constant evaluation (e.g. inlining or constant-folding in tests).
   */
  public final Session session;

  /**
   * Storage for local variables. Each slot holds one value.
   *
   * <p>The stack grows upward: {@code slots[top - 1]} is the most recently
   * pushed value.
   */
  public final Object[] slots;

  /**
   * Index of the next free slot. Equivalently, the number of currently live
   * local variables on the stack.
   */
  public int top;

  /**
   * Creates a Stack with a pre-allocated slots array.
   *
   * @param session The session holding top-level and built-in bindings; use
   *     {@link Session#EMPTY} for compile-time constant evaluation
   * @param capacity The number of slots to pre-allocate
   */
  public Stack(final Session session, final int capacity) {
    this(session, new Object[capacity], 0);
  }

  /**
   * Creates a Stack that shares the slots array of a parent stack.
   *
   * @param session The session; use {@link Session#EMPTY} for constant eval
   * @param parentSlots The slots array from the parent stack (shared by
   *     reference)
   * @param top The current stack top, copied from the parent
   */
  public Stack(
      final Session session, final Object[] parentSlots, final int top) {
    this.session = session;
    this.slots = parentSlots;
    this.top = top;
  }

  /** Creates a stack with {@link Session#EMPTY} and given capacity. */
  public static Stack withCapacity(final int capacity) {
    if (capacity == 0) {
      return EMPTY;
    }
    return new Stack(Session.EMPTY, capacity);
  }

  /**
   * Returns the current global environment.
   *
   * <p>Delegates to {@link Session#globalEnv}, which may have been temporarily
   * mutated by row-sink or aggregate code.
   */
  public Map<String, Object> currentEnv() {
    return requireNonNull(session.globalEnv, "globalEnv");
  }

  /**
   * Pushes {@code value} onto the stack.
   *
   * <p>The value may be null; see {@link Codes} {@code GlobalMarshalCode}.
   */
  public void push(@Nullable Object value) {
    slots[top++] = value;
  }

  /** Returns the current top (for save/restore). */
  public int save() {
    return top;
  }

  /** Restores top to a previously saved value. */
  public void restore(int savedTop) {
    top = savedTop;
  }

  /**
   * Returns this stack if it already has room for {@code needed} slots above
   * {@link #top}, or a new stack with a grown {@link #slots} array otherwise.
   *
   * <p>When compile-time slot estimates are accurate this method always returns
   * {@code this}; it exists as a safe fallback for cases (e.g. deep recursion)
   * where the required depth was not predictable at compile time.
   */
  public Stack ensureSize(int needed) {
    if (slots.length >= top + needed) {
      return this;
    }
    return new Stack(session, Arrays.copyOf(slots, top + needed), top);
  }
}

// End Stack.java
