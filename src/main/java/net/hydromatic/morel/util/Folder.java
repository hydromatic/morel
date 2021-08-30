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
package net.hydromatic.morel.util;

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Op;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static net.hydromatic.morel.ast.AstBuilder.ast;
import static net.hydromatic.morel.util.Static.skip;

/**
 * Enable creating right-deep trees.
 *
 * @param <E> Element type
 */
public abstract class Folder<E> {
  final E e;

  Folder(E e) {
    this.e = Objects.requireNonNull(e);
  }

  abstract E combine(List<Folder<E>> list);

  public static <E> E combineAll(List<Folder<E>> list) {
    if (list.size() == 0) {
      throw new AssertionError();
    }
    final Folder<E> head = list.get(0);
    final List<Folder<E>> tail = skip(list);
    return head.combine(tail);
  }

  private static <E> Folder<E> end(E e) {
    return new End<>(e);
  }

  /** Appends an element using "@". */
  public static void at(List<Folder<Ast.Exp>> list, Ast.Exp exp) {
    append(list, exp, e1 -> op(e1, Op.AT));
  }

  /** Appends an element using "::". */
  public static void cons(List<Folder<Ast.Exp>> list, Ast.Exp exp) {
    append(list, exp, e1 -> op(e1, Op.CONS));
  }

  /** Adds an element to an empty list. */
  public static <E> void start(List<Folder<E>> list, E e) {
    if (!list.isEmpty()) {
      throw new AssertionError();
    }
    list.add(end(e));
  }

  /** Adds an element and operator to a non-empty list. */
  private static <E> void append(List<Folder<E>> list, E e,
      Function<E, Folder<E>> fn) {
    if (list.isEmpty()) {
      throw new AssertionError();
    }
    @SuppressWarnings("unchecked")
    final End<E> end = (End) list.get(list.size() - 1);
    list.set(list.size() - 1, fn.apply(end.e));
    list.add(end(e));
  }

  /** Creates a folder that combines an expression with whatever follows
   * using an infix operator. */
  private static Folder<Ast.Exp> op(Ast.Exp exp, final Op at) {
    return new Folder<Ast.Exp>(exp) {
      Ast.Exp combine(List<Folder<Ast.Exp>> list) {
        final Ast.Exp rest = combineAll(list);
        return ast.infixCall(e.pos.plus(rest.pos), at, e, rest);
      }
    };
  }

  /** Sub-class of {@code Folder} that marks the end of a list.
   *
   * @param <E> element type */
  private static class End<E> extends Folder<E> {
    End(E e) {
      super(e);
    }

    E combine(List<Folder<E>> list) {
      assert list.isEmpty();
      return e;
    }
  }
}

// End Folder.java
