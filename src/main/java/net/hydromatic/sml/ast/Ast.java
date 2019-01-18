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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.Objects;

/** Various sub-classes of AST nodes. */
public class Ast {
  private Ast() {}

  public static String toString(AstNode node) {
    final AstWriter w = new AstWriter();
    node.unparse(w, 0, 0);
    return w.toString();
  }

  /** Base class for a pattern.
   *
   * <p>For example, "x" in "val x = 5",
   * or "(x, y) in "val (x, y) = makePair 1 2". */
  public abstract static class Pat extends AstNode {
    Pat(Pos pos, Op op) {
      super(pos, op);
    }
  }

  /** Named pattern.
   *
   * <p>For example, "x" in "val x = 5". */
  public static class NamedPat extends Pat {
    public final String name;

    NamedPat(Pos pos, String name) {
      super(pos, Op.NAMED_PAT);
      this.name = name;
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(name);
    }

    @Override public String toString() {
      return name;
    }
  }

  /** Pattern that is a pattern annotated with a type.
   *
   * <p>For example, "x : int" in "val x : int = 5". */
  public static class AnnotatedPat extends Pat {
    private final Pat pat;
    private final TypeNode type;

    AnnotatedPat(Pos pos, Pat pat, TypeNode type) {
      super(pos, Op.ANNOTATED_PAT);
      this.pat = pat;
      this.type = type;
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, pat, op, type, right);
    }

    @Override public String toString() {
      return pat + " : " + type;
    }
  }

  /** Base class for parse tree nodes that represent types. */
  public abstract static class TypeNode extends AstNode {
    /** Creates a type node. */
    protected TypeNode(Pos pos, Op op) {
      super(pos, op);
    }
  }

  /** Parse tree node of an expression annotated with a type. */
  public static class AnnotatedExp extends AstNode {
    public final TypeNode type;
    public final AstNode expression;

    /** Creates a type annotation. */
    private AnnotatedExp(Pos pos, TypeNode type, AstNode expression) {
      super(pos, Op.ANNOTATED_EXP);
      this.type = Objects.requireNonNull(type);
      this.expression = Objects.requireNonNull(expression);
    }

    @Override public int hashCode() {
      return Objects.hash(type, expression);
    }

    @Override public boolean equals(Object obj) {
      return this == obj
          || obj instanceof AnnotatedExp
              && type.equals(((AnnotatedExp) obj).type)
              && expression.equals(((AnnotatedExp) obj).expression);
    }

    @Override public String toString() {
      return expression + " : " + type;
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, expression, op, type, right);
    }
  }

  /** Parse tree for a named type (e.g. "int"). */
  public static class NamedType extends TypeNode {
    private final String name;

    /** Creates a type. */
    NamedType(Pos pos, String name) {
      super(pos, Op.NAMED_TYPE);
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

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(name);
    }
  }

  /** Base class of expression ASTs. */
  public abstract static class Exp extends AstNode {
    public Exp(Pos pos, Op op) {
      super(pos, op);
    }
  }

  /** Parse tree node of an identifier. */
  public static class Id extends Exp {
    public final String name;

    /** Creates an Id. */
    public Id(Pos pos, String name) {
      super(pos, Op.ID);
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
  public static class Literal extends Exp {
    public final Comparable value;

    /** Creates a Literal. */
    Literal(Pos pos, Op op, Comparable value) {
      super(pos, op);
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
      if (value instanceof String) {
        return "\"" + ((String) value).replaceAll("\"", "\\\"") + "\"";
      }
      return value.toString();
    }
  }

  /** Parse tree node of a variable declaration. */
  public static class VarDecl extends AstNode {
    public final Map<Pat, Exp> patExps;

    VarDecl(Pos pos, ImmutableMap<Pat, Exp> patExps) {
      super(pos, Op.VAL_DECL);
      this.patExps = Objects.requireNonNull(patExps);
      Preconditions.checkArgument(!patExps.isEmpty());
    }

    @Override public int hashCode() {
      return patExps.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof VarDecl
          && this.patExps.equals(((VarDecl) o).patExps);
    }

    @Override public String toString() {
      return "val " + patExps;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      String sep = "val ";
      for (Map.Entry<Pat, Exp> patExp : patExps.entrySet()) {
        w.append(sep);
        sep = " and ";
        patExp.getKey().unparse(w, 0, 0);
        w.append(" = ");
        patExp.getValue().unparse(w, 0, right);
      }
      return w;
    }
  }

  /** Call to an infix operator. */
  public static class InfixCall extends Exp {
    public final Exp a0;
    public final Exp a1;

    public InfixCall(Pos pos, Op op, Exp a0, Exp a1) {
      super(pos, op);
      this.a0 = Objects.requireNonNull(a0);
      this.a1 = Objects.requireNonNull(a1);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, a0, op, a1, right);
    }
  }

  /** "Let" expression. */
  public static class LetExp extends Exp {
    public final VarDecl decl;
    public final Exp e;

    LetExp(Pos pos, VarDecl decl, Exp e) {
      super(pos, Op.LET);
      this.decl = Objects.requireNonNull(decl);
      this.e = Objects.requireNonNull(e);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.binary("let ", decl, " in ", e, " end");
    }
  }

  /** Match. */
  public static class Match extends AstNode {
    public final Pat pat;
    public final Exp e;

    Match(Pos pos, Pat pat, Exp e) {
      super(pos, Op.MATCH);
      this.pat = pat;
      this.e = e;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(pat, 0, 0).append(" => ").append(e, 0, right);
    }
  }

  /** Lambda expression. */
  public static class Fn extends Exp {
    public final Match match;

    Fn(Pos pos, Match match) {
      super(pos, Op.FN);
      this.match = match;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("fn ").append(match, 0, right);
    }
  }

  /** Application of a function to its argument. */
  public static class Apply extends Exp {
    public final Exp fn;
    public final Exp arg;

    Apply(Pos pos, Exp fn, Exp arg) {
      super(pos, Op.APPLY);
      this.fn = fn;
      this.arg = arg;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, fn, op, arg, right);
    }
  }
}

// End Ast.java
