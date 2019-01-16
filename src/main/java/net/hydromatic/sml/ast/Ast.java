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

import java.util.Objects;

/** Various sub-classes of AST nodes. */
public class Ast {

  /** Base class for a pattern.
   *
   * <p>For example, "x" in "val x = 5",
   * or "(x, y) in "val (x, y) = makePair 1 2". */
  public abstract static class PatNode extends AstNode {
    PatNode(Pos pos) {
      super(pos);
    }
  }

  /** Named pattern.
   *
   * <p>For example, "x" in "val x = 5". */
  static class NamedPat extends PatNode {
    public final String name;

    NamedPat(Pos pos, String name) {
      super(pos);
      this.name = name;
    }

    @Override public String toString() {
      return name;
    }
  }

  /** Pattern that is a pattern annotated with a type.
   *
   * <p>For example, "x : int" in "val x : int = 5". */
  static class AnnotatedPatNode extends PatNode {
    private final PatNode pat;
    private final TypeNode type;

    AnnotatedPatNode(Pos pos, PatNode pat, TypeNode type) {
      super(pos);
      this.pat = pat;
      this.type = type;
    }

    @Override public String toString() {
      return pat + " : " + type;
    }
  }

  /** Base class for parse tree nodes that represent types. */
  public abstract static class TypeNode extends AstNode {
    /** Creates a type node. */
    protected TypeNode(Pos pos) {
      super(pos);
    }
  }

  /** Parse tree node of an expression annotated with a type. */
  public static class TypeAnnotation extends AstNode {
    public final TypeNode type;
    public final AstNode expression;

    /** Creates a type annotation. */
    private TypeAnnotation(Pos pos, TypeNode type, AstNode expression) {
      super(pos);
      this.type = Objects.requireNonNull(type);
      this.expression = Objects.requireNonNull(expression);
    }

    @Override public int hashCode() {
      return Objects.hash(type, expression);
    }

    @Override public boolean equals(Object obj) {
      return this == obj
          || obj instanceof TypeAnnotation
              && type.equals(((TypeAnnotation) obj).type)
              && expression.equals(((TypeAnnotation) obj).expression);
    }

    @Override public String toString() {
      return expression + " : " + type;
    }
  }

  /** Parse tree for a named type (e.g. "int"). */
  public static class NamedType extends TypeNode {
    private final String name;

    /** Creates a type. */
    NamedType(Pos pos, String name) {
      super(pos);
      this.name = Objects.requireNonNull(name);
    }

    @Override public int hashCode() {
      return name.hashCode();
    }

    @Override public boolean equals(Object obj) {
      return obj == this
          || obj instanceof NamedType
          && name.equals(((NamedType) obj).name);
    }

    @Override public String toString() {
      return name;
    }
  }

  /** Parse tree node of an identifier. */
  public static class Id extends AstNode {
    public final String name;

    /** Creates an Id. */
    public Id(Pos pos, String name) {
      super(pos);
      this.name = Objects.requireNonNull(name);
    }

    @Override public int hashCode() {
      return name.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof Id
          && this.name.equals(((Id) o).name);
    }

    @Override public String toString() {
      return name;
    }
  }

  /** Parse tree node of a literal (constant). */
  public static class Literal extends AstNode {
    public final Comparable value;

    /** Creates a Literal. */
    Literal(Pos pos, Comparable value) {
      super(pos);
      this.value = Objects.requireNonNull(value);
    }

    @Override public int hashCode() {
      return value.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof Literal
          && this.value.equals(((Literal) o).value);
    }

    @Override public String toString() {
      return value.toString();
    }
  }

  /** Parse tree node of a variable declaration. */
  public static class VarDecl extends AstNode {
    public final PatNode pat;
    public final AstNode expression;

    /** Creates an Id. */
    public VarDecl(Pos pos, PatNode pat, AstNode expression) {
      super(pos);
      this.pat = Objects.requireNonNull(pat);
      this.expression = Objects.requireNonNull(expression);
    }

    @Override public int hashCode() {
      return Objects.hash(pat, expression);
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof VarDecl
          && this.pat.equals(((VarDecl) o).pat)
          && this.expression.equals(((VarDecl) o).expression);
    }

    @Override public String toString() {
      return "val " + pat + " = " + expression;
    }
  }
}

// End Ast.java
