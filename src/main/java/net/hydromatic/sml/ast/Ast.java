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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

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
   * <p>For example, "x" in "val x = 5" is a {@link IdPat};
   * the "(x, y) in "val (x, y) = makePair 1 2" is a {@link TuplePat}. */
  public abstract static class Pat extends AstNode {
    Pat(Pos pos, Op op) {
      super(pos, op);
    }

    public void forEachArg(ObjIntConsumer<Pat> action) {
      // no args
    }

    public void visit(Consumer<Pat> consumer) {
      consumer.accept(this);
      forEachArg((arg, i) -> arg.visit(consumer));
    }
  }

  /** Named pattern, the pattern analog of the {@link Id} expression.
   *
   * <p>For example, "x" in "val x = 5". */
  public static class IdPat extends Pat {
    public final String name;

    IdPat(Pos pos, String name) {
      super(pos, Op.ID_PAT);
      this.name = name;
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(name);
    }
  }

  /** Literal pattern, the pattern analog of the {@link Literal} expression.
   *
   * <p>For example, "0" in "fun fact 0 = 1 | fact n = n * fact (n - 1)".*/
  public static class LiteralPat extends Pat {
    public final Comparable value;

    LiteralPat(Pos pos, Op op, Comparable value) {
      super(pos, op);
      this.value = Objects.requireNonNull(value);
      Preconditions.checkArgument(op == Op.BOOL_LITERAL_PAT
          || op == Op.CHAR_LITERAL_PAT
          || op == Op.INT_LITERAL_PAT
          || op == Op.REAL_LITERAL_PAT
          || op == Op.STRING_LITERAL_PAT);
    }

    @Override public int hashCode() {
      return value.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof LiteralPat
          && this.value.equals(((LiteralPat) o).value);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      if (value instanceof String) {
        return w.append("\"")
            .append(((String) value).replaceAll("\"", "\\\""))
            .append("\"");
      } else {
        return w.append(value.toString());
      }
    }
  }

  /** Wildcard pattern.
   *
   * <p>For example, "{@code _}" in "{@code fn foo _ => 42}". */
  public static class WildcardPat extends Pat {
    WildcardPat(Pos pos) {
      super(pos, Op.WILDCARD_PAT);
    }

    @Override public int hashCode() {
      return "_".hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof WildcardPat;
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("_");
    }
  }

  /** Pattern build from an infix operator applied to two patterns. */
  public static class InfixPat extends Pat {
    public final Pat p0;
    public final Pat p1;

    InfixPat(Pos pos, Op op, Pat p0, Pat p1) {
      super(pos, op);
      this.p0 = Objects.requireNonNull(p0);
      this.p1 = Objects.requireNonNull(p1);
    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      action.accept(p0, 0);
      action.accept(p1, 1);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, p0, op, p1, right);
    }
  }

  /** Tuple pattern, the pattern analog of the {@link Tuple} expression.
   *
   * <p>For example, "(x, y)" in "fun sum (x, y) = x + y". */
  public static class TuplePat extends Pat {
    public final java.util.List<Pat> args;

    TuplePat(Pos pos, ImmutableList<Pat> args) {
      super(pos, Op.TUPLE_PAT);
      this.args = Objects.requireNonNull(args);
    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      int i = 0;
      for (Pat arg : args) {
        action.accept(arg, i++);
      }
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("(");
      forEachArg((arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append(")");
    }
  }

  /** List pattern, the pattern analog of the {@link List} expression.
   *
   * <p>For example, "(x, y)" in "fun sum (x, y) = x + y". */
  public static class ListPat extends Pat {
    public final java.util.List<Pat> args;

    ListPat(Pos pos, ImmutableList<Pat> args) {
      super(pos, Op.LIST_PAT);
      this.args = Objects.requireNonNull(args);
    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      int i = 0;
      for (Pat arg : args) {
        action.accept(arg, i++);
      }
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("[");
      forEachArg((arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append("]");
    }
  }

  /** Record pattern. */
  public static class RecordPat extends Pat {
    public final boolean ellipsis;
    public final Map<String, Pat> args;

    RecordPat(Pos pos, boolean ellipsis, ImmutableMap<String, Pat> args) {
      super(pos, Op.RECORD_PAT);
      this.ellipsis = ellipsis;
      this.args = Objects.requireNonNull(args);
    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      int i = 0;
      for (Pat arg : args.values()) {
        action.accept(arg, i++);
      }
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("{");
      int i = 0;
      for (Map.Entry<String, Pat> entry : args.entrySet()) {
        if (i++ > 0) {
          w.append(", ");
        }
        w.append(entry.getKey()).append(" = ").append(entry.getValue(), 0, 0);
      }
      if (ellipsis) {
        if (i++ > 0) {
          w.append(", ");
        }
        w.append("...");
      }
      return w.append("}");
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

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      action.accept(pat, 0);
    }
  }

  /** Base class for parse tree nodes that represent types. */
  public abstract static class TypeNode extends AstNode {
    /** Creates a type node. */
    TypeNode(Pos pos, Op op) {
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

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(name);
    }
  }

  /** Base class of expression ASTs. */
  public abstract static class Exp extends AstNode {
    Exp(Pos pos, Op op) {
      super(pos, op);
    }

    public void forEachArg(ObjIntConsumer<Exp> action) {
      // no args
    }
  }

  /** Parse tree node of an identifier. */
  public static class Id extends Exp {
    public final String name;

    /** Creates an Id. */
    Id(Pos pos, String name) {
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

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(name);
    }
  }

  /** Parse tree node of a record selector. */
  public static class RecordSelector extends Exp {
    public final String name;

    /** Set during validation, after the type of the argument has been deduced,
     * contains the ordinal of the field in the record or tuple that is to be
     * accessed.
     *
     * <p>A mutable field, it is not strictly a parse tree property, but just
     * convenient storage for a value needed by the compiler. Use with care. */
    public int slot = -1;

    /** Creates a record selector. */
    RecordSelector(Pos pos, String name) {
      super(pos, Op.RECORD_SELECTOR);
      this.name = Objects.requireNonNull(name);
      assert !name.startsWith("#");
    }

    @Override public int hashCode() {
      return name.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof Id
          && this.name.equals(((Id) o).name);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("#").append(name);
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

    AstWriter unparse(AstWriter w, int left, int right) {
      if (value instanceof String) {
        return w.append("\"")
            .append(((String) value).replaceAll("\"", "\\\""))
            .append("\"");
      } else {
        return w.append(value.toString());
      }
    }
  }

  /** Base class for declarations. */
  public abstract static class Decl extends AstNode {
    Decl(Pos pos, Op op) {
      super(pos, op);
    }
  }

  /** Parse tree node of a variable declaration. */
  public static class VarDecl extends Decl {
    public final java.util.List<Ast.ValBind> valBinds;

    VarDecl(Pos pos, ImmutableList<Ast.ValBind> valBinds) {
      super(pos, Op.VAL_DECL);
      this.valBinds = Objects.requireNonNull(valBinds);
      Preconditions.checkArgument(!valBinds.isEmpty());
    }

    @Override public int hashCode() {
      return valBinds.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof VarDecl
          && this.valBinds.equals(((VarDecl) o).valBinds);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      String sep = "val ";
      for (ValBind valBind : valBinds) {
        w.append(sep);
        sep = " and ";
        valBind.unparse(w, 0, right);
      }
      return w;
    }
  }

  /** Tuple. */
  public static class Tuple extends Exp {
    public final java.util.List<Exp> args;

    Tuple(Pos pos, Iterable<? extends Exp> args) {
      super(pos, Op.TUPLE);
      this.args = ImmutableList.copyOf(args);
    }

    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
      int i = 0;
      for (Exp arg : args) {
        action.accept(arg, i++);
      }
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("(");
      forEachArg((arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append(")");
    }
  }

  /** List. */
  public static class List extends Exp {
    public final java.util.List<Exp> args;

    List(Pos pos, Iterable<? extends Exp> args) {
      super(pos, Op.LIST);
      this.args = ImmutableList.copyOf(args);
    }

    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
      int i = 0;
      for (Exp arg : args) {
        action.accept(arg, i++);
      }
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("[");
      forEachArg((arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append("]");
    }
  }

  /** Record. */
  public static class Record extends Exp {
    public final Map<String, Exp> args;

    Record(Pos pos, ImmutableSortedMap<String, Exp> args) {
      super(pos, Op.RECORD);
      this.args = Objects.requireNonNull(args);
    }

    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
      int i = 0;
      for (Exp arg : args.values()) {
        action.accept(arg, i++);
      }
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("{");
      int i = 0;
      for (Map.Entry<String, Exp> entry : args.entrySet()) {
        if (i++ > 0) {
          w.append(", ");
        }
        w.append(entry.getKey()).append(" = ").append(entry.getValue(), 0, 0);
      }
      return w.append("}");
    }
  }

  /** Call to an infix operator. */
  public static class InfixCall extends Exp {
    public final Exp a0;
    public final Exp a1;

    InfixCall(Pos pos, Op op, Exp a0, Exp a1) {
      super(pos, op);
      this.a0 = Objects.requireNonNull(a0);
      this.a1 = Objects.requireNonNull(a1);
    }

    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
      action.accept(a0, 0);
      action.accept(a1, 1);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, a0, op, a1, right);
    }
  }

  /** If ... else expression. */
  public static class If extends Exp {
    public final Exp condition;
    public final Exp ifTrue;
    public final Exp ifFalse;

    public If(Pos pos, Exp condition, Exp ifTrue, Exp ifFalse) {
      super(pos, Op.IF);
      this.condition = Objects.requireNonNull(condition);
      this.ifTrue = Objects.requireNonNull(ifTrue);
      this.ifFalse = Objects.requireNonNull(ifFalse);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("if ").append(condition, 0, 0)
          .append(" then ").append(ifTrue, 0, 0)
          .append(" else ").append(ifFalse, 0, right);
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

  /** Value bind. */
  public static class ValBind extends AstNode {
    public final boolean rec;
    public final Pat pat;
    public final Exp e;

    ValBind(Pos pos, boolean rec, Pat pat, Exp e) {
      super(pos, Op.VAL_BIND);
      this.rec = rec;
      this.pat = pat;
      this.e = e;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      if (rec) {
        w.append("rec ");
      }
      return w.append(pat, 0, 0).append(" = ").append(e, 0, right);
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
    public final java.util.List<Match> matchList;

    Fn(Pos pos, ImmutableList<Match> matchList) {
      super(pos, Op.FN);
      this.matchList = Objects.requireNonNull(matchList);
      Preconditions.checkArgument(!matchList.isEmpty());
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      if (left > op.left || op.right < right) {
        return w.append("(").append(this, 0, 0).append(")");
      } else {
        return w.append("fn ").appendAll(matchList, 0, Op.BAR, right);
      }
    }
  }

  /** Case expression. */
  public static class Case extends Exp {
    public final Exp exp;
    public final java.util.List<Match> matchList;

    Case(Pos pos, Exp exp, ImmutableList<Match> matchList) {
      super(pos, Op.CASE);
      this.exp = exp;
      this.matchList = matchList;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      if (left > op.left || op.right < right) {
        return w.append("(").append(this, 0, 0).append(")");
      } else {
        return w.append("case ").append(exp, 0, 0).append(" of ")
            .appendAll(matchList, left, Op.BAR, right);
      }
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
