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
package net.hydromatic.sml.ast;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Position of a parse-tree node. */
public class Pos {
  public static final Pos ZERO = new Pos(0, 0, 0, 0);

  public final int startLine;
  public final int startColumn;
  public final int endLine;
  public final int endColumn;

  /** Creates a Pos. */
  public Pos(int startLine, int startColumn, int endLine, int endColumn) {
    this.startLine = startLine;
    this.startColumn = startColumn;
    this.endLine = endLine;
    this.endColumn = endColumn;
  }

  @Override public int hashCode() {
    return Objects.hash(startLine, startColumn, endLine, endColumn);
  }

  @Override public boolean equals(Object o) {
    return o == this
        || o instanceof Pos
        && this.startLine == ((Pos) o).startLine
        && this.startColumn == ((Pos) o).startColumn
        && this.endLine == ((Pos) o).endLine
        && this.endColumn == ((Pos) o).endColumn;
  }

  @Override public String toString() {
    return "line " + startLine + ", column " + startColumn;
  }

  /**
   * Combines an iterable of parser positions to create a position which spans
   * from the beginning of the first to the end of the last.
   */
  public static Pos sum(Iterable<Pos> poses) {
    final List<Pos> list =
        poses instanceof List
            ? (List<Pos>) poses
            : Lists.newArrayList(poses);
    return sum_(list);
  }

  public static <E> Pos sum(Iterable<E> elements, Function<E, Pos> fn) {
    //noinspection StaticPseudoFunctionalStyleMethod
    return sum(Iterables.transform(elements, fn::apply));
  }

  public static Pos sum(List<? extends AstNode> nodes) {
    return sum(nodes, node -> node.pos);
  }

  /**
   * Combines a list of parser positions to create a position which spans
   * from the beginning of the first to the end of the last.
   */
  private static Pos sum_(final List<Pos> positions) {
    switch (positions.size()) {
    case 0:
      throw new AssertionError();
    case 1:
      return positions.get(0);
    default:
      final List<Pos> poses = new AbstractList<Pos>() {
        public Pos get(int index) {
          return positions.get(index + 1);
        }
        public int size() {
          return positions.size() - 1;
        }
      };
      final Pos p = positions.get(0);
      return sum(poses, p.startLine, p.startColumn, p.endLine, p.endColumn);
    }
  }


  /**
   * Computes the parser position which is the sum of an array of parser
   * positions and of a parser position represented by (line, column, endLine,
   * endColumn).
   *
   * @param poses     Array of parser positions
   * @param line      Start line
   * @param column    Start column
   * @param endLine   End line
   * @param endColumn End column
   * @return Sum of parser positions
   */
  private static Pos sum(
      Iterable<Pos> poses,
      int line,
      int column,
      int endLine,
      int endColumn) {
    int testLine;
    int testColumn;
    for (Pos pos : poses) {
      if (pos == null || pos.equals(Pos.ZERO)) {
        continue;
      }
      testLine = pos.startLine;
      testColumn = pos.startColumn;
      if (testLine < line || testLine == line && testColumn < column) {
        line = testLine;
        column = testColumn;
      }

      testLine = pos.endLine;
      testColumn = pos.endColumn;
      if (testLine > endLine || testLine == endLine && testColumn > endColumn) {
        endLine = testLine;
        endColumn = testColumn;
      }
    }
    return new Pos(line, column, endLine, endColumn);
  }

  public Pos plus(Pos pos) {
    return new Pos(startLine, startColumn, pos.endLine, pos.endColumn);
  }
}

// End Pos.java
