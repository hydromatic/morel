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
import com.google.common.collect.Iterables;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;
import java.util.stream.Collectors;

import static net.hydromatic.sml.ast.AstBuilder.ast;

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

    @Override public abstract Pat accept(Shuttle shuttle);

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

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
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
    public final Pat pat;
    public final Type type;

    AnnotatedPat(Pos pos, Pat pat, Type type) {
      super(pos, Op.ANNOTATED_PAT);
      this.pat = pat;
      this.type = type;
    }

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, pat, op, type, right);
    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      action.accept(pat, 0);
    }
  }

  /** Base class for parse tree nodes that represent types. */
  public abstract static class Type extends AstNode {
    /** Creates a type node. */
    Type(Pos pos, Op op) {
      super(pos, op);
    }

    @Override public abstract Type accept(Shuttle shuttle);
  }

  /** Parse tree node of an expression annotated with a type. */
  public static class AnnotatedExp extends Exp {
    public final Type type;
    public final Exp expression;

    /** Creates a type annotation. */
    AnnotatedExp(Pos pos, Type type, Exp expression) {
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

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, expression, op, type, right);
    }
  }

  /** Parse tree for a named type (e.g. "int"). */
  public static class NamedType extends Type {
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

    public Type accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    @Override public abstract Exp accept(Shuttle shuttle);
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

    public Id accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    @Override public abstract Decl accept(Shuttle shuttle);
  }

  /** Parse tree node of a value declaration. */
  public static class ValDecl extends Decl {
    public final java.util.List<ValBind> valBinds;

    ValDecl(Pos pos, ImmutableList<ValBind> valBinds) {
      super(pos, Op.VAL_DECL);
      this.valBinds = Objects.requireNonNull(valBinds);
      Preconditions.checkArgument(!valBinds.isEmpty());
    }

    @Override public int hashCode() {
      return valBinds.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof ValDecl
          && this.valBinds.equals(((ValDecl) o).valBinds);
    }

    public ValDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

  /** Parse tree node of a function declaration. */
  public static class FunDecl extends Decl {
    public final java.util.List<FunBind> funBinds;

    FunDecl(Pos pos, ImmutableList<FunBind> funBinds) {
      super(pos, Op.FUN_DECL);
      this.funBinds = Objects.requireNonNull(funBinds);
      Preconditions.checkArgument(!funBinds.isEmpty());
      // TODO: check that functions have the same name
    }

    @Override public int hashCode() {
      return funBinds.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof FunDecl
          && this.funBinds.equals(((FunDecl) o).funBinds);
    }

    public Decl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.appendAll(funBinds, "fun ", " | ", "");
    }
  }

  /** One of the branches (separated by 'and') in a 'fun' function
   * declaration. */
  public static class FunBind extends AstNode {
    public final ImmutableList<FunMatch> matchList;
    public final String name;

    FunBind(Pos pos, ImmutableList<FunMatch> matchList) {
      super(pos, Op.FUN_BIND);
      Preconditions.checkArgument(!matchList.isEmpty());
      this.matchList = matchList;
      // We assume that the function name is the same in all matches.
      // We will check during validation.
      this.name = matchList.get(0).name;
    }

    public FunBind accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.appendAll(matchList, " and ");
    }
  }

  /** One of the branches (separated by '|') in a 'fun' function declaration. */
  public static class FunMatch extends AstNode {
    public final String name;
    public final ImmutableList<Pat> patList;
    public final Exp exp;

    FunMatch(Pos pos, String name, ImmutableList<Pat> patList, Exp exp) {
      super(pos, Op.FUN_MATCH);
      this.name = name;
      this.patList = patList;
      this.exp = exp;
    }

    public FunMatch accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(name).appendAll(patList, " ", " ", " = ")
          .append(exp, 0, right);
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

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    public List accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("if ").append(condition, 0, 0)
          .append(" then ").append(ifTrue, 0, 0)
          .append(" else ").append(ifFalse, 0, right);
    }
  }

  /** "Let" expression. */
  public static class LetExp extends Exp {
    public final java.util.List<Decl> decls;
    public final Exp e;

    LetExp(Pos pos, ImmutableList<Decl> decls, Exp e) {
      super(pos, Op.LET);
      this.decls = Objects.requireNonNull(decls);
      this.e = Objects.requireNonNull(e);
    }

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.appendAll(decls, "let ", "; ", " in ")
          .append(e, 0, 0).append(" end");
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

    public AstNode accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    public Match accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    public Fn accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

  /** From expression. */
  public static class From extends Exp {
    public final Map<Id, Exp> sources;
    public final Exp filterExp;
    public final Exp yieldExp;
    /** The expression in the yield clause, or the default yield expression
     * if not specified; never null. */
    public final Exp yieldExpOrDefault;

    From(Pos pos, ImmutableMap<Id, Exp> sources, Exp filterExp, Exp yieldExp) {
      super(pos, Op.FROM);
      this.sources = Objects.requireNonNull(sources);
      this.filterExp = filterExp; // may be null
      this.yieldExp = yieldExp; // may be null
      if (yieldExp != null) {
        this.yieldExpOrDefault = this.yieldExp;
      } else if (sources.size() == 1) {
        this.yieldExpOrDefault = Iterables.getOnlyElement(sources.keySet());
      } else {
        this.yieldExpOrDefault = ast.record(pos,
            sources.keySet().stream()
                .collect(Collectors.toMap(id -> id.name, id -> id)));
      }
      Objects.requireNonNull(this.yieldExpOrDefault);
    }

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      if (left > op.left || op.right < right) {
        return w.append("(").append(this, 0, 0).append(")");
      } else {
        int i = 0;
        for (Map.Entry<Id, Exp> entry : sources.entrySet()) {
          final Exp exp = entry.getValue();
          final Id id = entry.getKey();
          w.append(i++ == 0 ? "from " : ", ").append(exp, 0, 0)
              .append(" as ").append(id, 0, 0);
        }
        if (filterExp != null) {
          w.append(" where ").append(filterExp, 0, 0);
        }
        return w.append(" yield ").append(yieldExp, 0, 0);
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

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, fn, op, arg, right);
    }
  }
}

// End Ast.java
