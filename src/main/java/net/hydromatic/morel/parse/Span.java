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
package net.hydromatic.morel.parse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Pos;

/**
 * Builder for {@link Pos}.
 *
 * <p>Because it is mutable, it is convenient for keeping track of the positions
 * of the tokens that go into a non-terminal. It can be passed into methods,
 * which can add the positions of tokens consumed to it.
 *
 * <p>Some patterns:
 *
 * <ul>
 *   <li>{@code final Span s;} declaration of a Span at the top of a production
 *   <li>{@code s = span();} initializes s to a Span that includes the token we
 *       just saw; very often occurs immediately after the first token in the
 *       production
 *   <li>{@code s.end(this);} adds the most recent token to span s and evaluates
 *       to a Position that spans from beginning to end; commonly used when
 *       making a call to a function
 *   <li>{@code s.pos()} returns a position spanning all tokens in the list
 *   <li>{@code s.add(node);} adds a AstNode's parser position to a span
 *   <li>{@code s.addAll(nodeList);} adds several AstNodes' parser positions to
 *       a span
 *   <li>{@code s = Span.of();} initializes s to an empty Span, not even
 *       including the most recent token; rarely used
 * </ul>
 */
public final class Span {
  private final List<Pos> posList = new ArrayList<>();

  /** Use one of the {@link #of} methods. */
  private Span() {}

  /** Creates an empty Span. */
  public static Span of() {
    return new Span();
  }

  /** Creates a Span with one position. */
  public static Span of(Pos p) {
    return new Span().add(p);
  }

  /** Creates a Span of one node. */
  public static Span of(AstNode n) {
    return new Span().add(n);
  }

  /** Creates a Span between two nodes. */
  public static Span of(AstNode n0, AstNode n1) {
    return new Span().add(n0).add(n1);
  }

  /** Creates a Span of a list of nodes. */
  public static Span of(Collection<? extends AstNode> nodes) {
    return new Span().addAll(nodes);
  }

  /** Adds a node's position to the list, and returns this Span. */
  public Span add(AstNode n) {
    return add(n.pos);
  }

  /**
   * Adds a node's position to the list if the node is not null, and returns
   * this Span.
   */
  public Span addIf(AstNode n) {
    return n == null ? this : add(n);
  }

  /** Adds a position to the list, and returns this Span. */
  public Span add(Pos pos) {
    posList.add(pos);
    return this;
  }

  /**
   * Adds the positions of a collection of nodes to the list, and returns this
   * Span.
   */
  public Span addAll(Iterable<? extends AstNode> nodes) {
    for (AstNode node : nodes) {
      add(node);
    }
    return this;
  }

  /**
   * Adds the position of the last token emitted by a parser to the list, and
   * returns this Span.
   */
  public Span add(MorelParser parser) {
    try {
      final Pos pos = parser.pos();
      return add(pos);
    } catch (Exception e) {
      // getPos does not really throw an exception
      throw new AssertionError(e);
    }
  }

  /**
   * Returns a position spanning the earliest position to the latest. Does not
   * assume that the positions are sorted. Throws if the list is empty.
   */
  public Pos pos() {
    switch (posList.size()) {
      case 0:
        throw new AssertionError();
      case 1:
        return posList.get(0);
      default:
        return Pos.sum(posList);
    }
  }

  /**
   * Adds the position of the last token emitted by a parser to the list, and
   * returns a position that covers the whole range.
   */
  public Pos end(MorelParser parser) {
    return add(parser).pos();
  }

  /**
   * Adds a node's position to the list, and returns a position that covers the
   * whole range.
   */
  public Pos end(AstNode n) {
    return add(n).pos();
  }

  /** Clears the contents of this Span, and returns this Span. */
  public Span clear() {
    posList.clear();
    return this;
  }
}

// End Span.java
