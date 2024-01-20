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
package net.hydromatic.morel.ast;

import net.hydromatic.morel.util.ImmutablePairList;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.PairList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;
import java.util.stream.Collectors;

import static net.hydromatic.morel.ast.AstBuilder.ast;
import static net.hydromatic.morel.type.RecordType.ORDERING;
import static net.hydromatic.morel.type.RecordType.mutableMap;
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Pair.forEachIndexed;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getLast;

import static java.util.Objects.requireNonNull;

/** Various sub-classes of AST nodes. */
public class Ast {
  private Ast() {}

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

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(name);
    }
  }

  /** Literal pattern, the pattern analog of the {@link Literal} expression.
   *
   * <p>For example, "0" in "fun fact 0 = 1 | fact n = n * fact (n - 1)".*/
  @SuppressWarnings("rawtypes")
  public static class LiteralPat extends Pat {
    public final Comparable value;

    LiteralPat(Pos pos, Op op, Comparable value) {
      super(pos, op);
      this.value = requireNonNull(value);
      checkArgument(op == Op.BOOL_LITERAL_PAT
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

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.appendLiteral(value);
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
      return o instanceof WildcardPat;
    }

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
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
      this.p0 = requireNonNull(p0);
      this.p1 = requireNonNull(p1);
    }

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      action.accept(p0, 0);
      action.accept(p1, 1);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, p0, op, p1, right);
    }

    /** Creates a copy of this {@code InfixPat} with given contents
     * and same operator,
     * or {@code this} if the contents are the same. */
    public InfixPat copy(Pat p0, Pat p1) {
      return this.p0.equals(p0)
          && this.p1.equals(p1)
          ? this
          : ast.infixPat(pos, op, p0, p1);
    }
  }

  /** Type constructor pattern with an argument.
   *
   * <p>For example, in "fun nvl NIL = 0 | OPTION x = x",
   * "OPTION x" is a type constructor pattern that binds "x";
   * and "NIL" is a type constructor pattern whose {@link #pat} is null.
   *
   * @see Con0Pat */
  public static class ConPat extends Pat {
    public final Id tyCon;
    public final Pat pat;

    ConPat(Pos pos, Id tyCon, Pat pat) {
      super(pos, Op.CON_PAT);
      this.tyCon = requireNonNull(tyCon);
      this.pat = requireNonNull(pat);
    }

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      action.accept(pat, 0);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, tyCon, op, pat, right);
    }

    /** Creates a copy of this {@code ConPat} with given contents,
     * or {@code this} if the contents are the same. */
    public ConPat copy(Id tyCon, Pat pat) {
      return this.tyCon.equals(tyCon)
          && this.pat.equals(pat)
          ? this
          : ast.conPat(pos, tyCon, pat);
    }
  }

  /** Layered pattern.
   *
   * <p>For example, in "val h as (i, j) = (1, 2)",
   * if the pattern matches, "h" is assigned the whole tuple,
   * and "i" and "j" are assigned the left and right members of the tuple. */
  public static class AsPat extends Pat {
    public final IdPat id;
    public final Pat pat;

    AsPat(Pos pos, IdPat id, Pat pat) {
      super(pos, Op.AS_PAT);
      this.id = requireNonNull(id);
      this.pat = requireNonNull(pat);
    }

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      action.accept(id, 0);
      action.accept(pat, 1);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, id, op, pat, right);
    }

    /** Creates a copy of this {@code AsPat} with given contents,
     * or {@code this} if the contents are the same. */
    public AsPat copy(IdPat id, Pat pat) {
      return this.id.equals(id)
          && this.pat.equals(pat)
          ? this
          : ast.asPat(pos, id, pat);
    }
  }

  /** Type constructor pattern with no argument.
   *
   * <p>For example, in "fun nvl NIL = 0 | OPTION x = x",
   * "NIL" is a zero-arg type constructor pattern.
   *
   * @see ConPat */
  public static class Con0Pat extends Pat {
    public final Id tyCon;

    Con0Pat(Pos pos, Id tyCon) {
      super(pos, Op.CON0_PAT);
      this.tyCon = requireNonNull(tyCon);
    }

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return tyCon.unparse(w, left, right);
    }

    /** Creates a copy of this {@code Con0Pat} with given contents,
     * or {@code this} if the contents are the same. */
    public Con0Pat copy(Id tyCon) {
      return this.tyCon.equals(tyCon)
          ? this
          : ast.con0Pat(pos, tyCon);
    }
  }

  /** Tuple pattern, the pattern analog of the {@link Tuple} expression.
   *
   * <p>For example, "(x, y)" in "fun sum (x, y) = x + y". */
  public static class TuplePat extends Pat {
    public final List<Pat> args;

    TuplePat(Pos pos, ImmutableList<Pat> args) {
      super(pos, Op.TUPLE_PAT);
      this.args = requireNonNull(args);
    }

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      forEachIndexed(args, action);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("(");
      forEachArg((arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append(")");
    }

    /** Creates a copy of this {@code TuplePat} with given contents,
     * or {@code this} if the contents are the same. */
    public TuplePat copy(List<Pat> args) {
      return this.args.equals(args)
          ? this
          : ast.tuplePat(pos, args);
    }
  }

  /** List pattern, the pattern analog of the {@link ListExp} expression.
   *
   * <p>For example, "[x, y]" in "fun sum [x, y] = x + y". */
  public static class ListPat extends Pat {
    public final List<Pat> args;

    ListPat(Pos pos, ImmutableList<Pat> args) {
      super(pos, Op.LIST_PAT);
      this.args = requireNonNull(args);
    }

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      forEachIndexed(args, action);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("[");
      forEachArg((arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append("]");
    }

    /** Creates a copy of this {@code ListPat} with given contents,
     * or {@code this} if the contents are the same. */
    public ListPat copy(List<Pat> args) {
      return this.args.equals(args)
          ? this
          : ast.listPat(pos, args);
    }
  }

  /** Record pattern. */
  public static class RecordPat extends Pat {
    public final boolean ellipsis;
    public final SortedMap<String, Pat> args;

    RecordPat(Pos pos, boolean ellipsis, ImmutableSortedMap<String, Pat> args) {
      super(pos, Op.RECORD_PAT);
      this.ellipsis = ellipsis;
      this.args = requireNonNull(args);
      checkArgument(args.comparator() == ORDERING);
    }

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      forEachIndexed(args.values(), action);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("{");
      Ord.forEachIndexed(args, (i, k, v) ->
          w.append(i > 0 ? ", " : "").append(k).append(" = ").append(v, 0, 0));
      if (ellipsis) {
        w.append(args.isEmpty() ? "..." : ", ...");
      }
      return w.append("}");
    }

    public RecordPat copy(boolean ellipsis, Map<String, ? extends Pat> args) {
      return this.ellipsis == ellipsis
          && this.args.equals(args)
          ? this
          : ast.recordPat(pos, ellipsis, args);
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

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, pat, op, type, right);
    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      action.accept(pat, 0);
    }

    /** Creates a copy of this {@code AnnotatedPat} with given contents,
     * or {@code this} if the contents are the same. */
    public AnnotatedPat copy(Pat pat, Type type) {
      return this.pat.equals(pat)
          && this.type.equals(type)
          ? this
          : ast.annotatedPat(pos, pat, type);
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
    public final Exp exp;

    /** Creates a type annotation. */
    AnnotatedExp(Pos pos, Exp exp, Type type) {
      super(pos, Op.ANNOTATED_EXP);
      this.type = requireNonNull(type);
      this.exp = requireNonNull(exp);
    }

    @Override public int hashCode() {
      return Objects.hash(type, exp);
    }

    @Override public boolean equals(Object obj) {
      return this == obj
          || obj instanceof AnnotatedExp
              && type.equals(((AnnotatedExp) obj).type)
              && exp.equals(((AnnotatedExp) obj).exp);
    }

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, exp, op, type, right);
    }
  }

  /** Parse tree for a named type (e.g. "int" or "(int, string) list"). */
  public static class NamedType extends Type {
    public final List<Type> types;
    public final String name;

    /** Creates a type. */
    NamedType(Pos pos, ImmutableList<Type> types, String name) {
      super(pos, Op.NAMED_TYPE);
      this.types = requireNonNull(types);
      this.name = requireNonNull(name);
    }

    @Override public int hashCode() {
      return Objects.hash(types, name);
    }

    @Override public boolean equals(Object obj) {
      return obj == this
          || obj instanceof NamedType
          && types.equals(((NamedType) obj).types)
          && name.equals(((NamedType) obj).name);
    }

    public Type accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      switch (types.size()) {
      case 0:
        return w.id(name);
      case 1:
        return w.append(types.get(0), left, op.left)
            .append(" ").id(name);
      default:
        w.append("(");
        forEachIndexed(types, (type, i) ->
            w.append(i == 0 ? "" : ", ").append(type, 0, 0));
        return w.append(") ")
            .id(name);
      }
    }
  }

  /** Parse tree node of a type variable. */
  public static class TyVar extends Type {
    public final String name;

    /** Creates a TyVar. */
    TyVar(Pos pos, String name) {
      super(pos, Op.TY_VAR);
      this.name = requireNonNull(name);
    }

    @Override public int hashCode() {
      return name.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof TyVar
          && this.name.equals(((TyVar) o).name);
    }

    public TyVar accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(name);
    }
  }

  /** Parse tree node of a record type. */
  public static class RecordType extends Type {
    public final Map<String, Type> fieldTypes;

    /** Creates a record type. */
    RecordType(Pos pos, ImmutableMap<String, Type> fieldTypes) {
      super(pos, Op.RECORD_TYPE);
      this.fieldTypes = requireNonNull(fieldTypes);
    }

    @Override public int hashCode() {
      return fieldTypes.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof RecordType
          && this.fieldTypes.equals(((RecordType) o).fieldTypes);
    }

    public RecordType accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      w.append("{");
      Ord.forEachIndexed(fieldTypes, (i, field, type) ->
          w.append(i > 0 ? ", " : "")
              .id(field).append(": ").append(type, 0, 0));
      return w.append("}");
    }
  }

  /** Tuple type. */
  public static class TupleType extends Type {
    public final List<Type> types;

    TupleType(Pos pos, ImmutableList<Type> types) {
      super(pos, Op.TUPLE_TYPE);
      this.types = requireNonNull(types);
    }

    public Type accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      // "*" is non-associative. Elevate both left and right precedence
      // to force parentheses if the inner expression is also "*".
      forEachIndexed(types, (arg, i) ->
          w.append(i == 0 ? "" : " * ")
              .append(arg, op.left + 1, op.right + 1));
      return w;
    }
  }

  /** Not really a type, just a way for the parser to represent the type
   * arguments to a type constructor.
   *
   * <p>For example, in {@code datatype foo = Pair of (int, string) list},
   * {@code (int, string)} is briefly represented as a composite type,
   * then {@code int} and {@code string} becomes the two type parameters to
   * the {@code list} {@link NamedType}. */
  public static class CompositeType extends Type {
    public final List<Type> types;

    CompositeType(Pos pos, ImmutableList<Type> types) {
      super(pos, Op.TUPLE_TYPE);
      this.types = requireNonNull(types);
    }

    public Type accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      w.append("(");
      forEachIndexed(types, (arg, i) ->
          w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append(")");
    }
  }

  /** Function type. */
  public static class FunctionType extends Type {
    public final Type paramType;
    public final Type resultType;

    FunctionType(Pos pos, Type paramType, Type resultType) {
      super(pos, Op.FUNCTION_TYPE);
      this.paramType = requireNonNull(paramType);
      this.resultType = requireNonNull(resultType);
    }

    public Type accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(paramType, left, op.left)
          .append(" -> ")
          .append(resultType, op.right, right);
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

    /** Returns a list of all arguments. */
    public final List<Exp> args() {
      final ImmutableList.Builder<Exp> args = ImmutableList.builder();
      forEachArg((exp, value) -> args.add(exp));
      return args.build();
    }
  }

  /** Parse tree node of an identifier. */
  public static class Id extends Exp {
    public final String name;

    /** Creates an Id. */
    Id(Pos pos, String name) {
      super(pos, Op.ID);
      this.name = requireNonNull(name);
    }

    public Id accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(name);
    }
  }

  /** Parse tree node of a record selector. */
  public static class RecordSelector extends Exp {
    public final String name;

    /** Creates a record selector. */
    RecordSelector(Pos pos, String name) {
      super(pos, Op.RECORD_SELECTOR);
      this.name = requireNonNull(name);
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

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("#").append(name);
    }
  }

  /** Parse tree node of a literal (constant). */
  @SuppressWarnings("rawtypes")
  public static class Literal extends Exp {
    public final Comparable value;

    /** Creates a Literal. */
    Literal(Pos pos, Op op, Comparable value) {
      super(pos, op);
      this.value = requireNonNull(value);
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

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.appendLiteral(value);
    }
  }

  /** Base class for declarations. */
  public abstract static class Decl extends AstNode {
    Decl(Pos pos, Op op) {
      super(pos, op);
    }

    @Override public abstract Decl accept(Shuttle shuttle);
  }

  /** Parse tree node of a datatype declaration. */
  public static class DatatypeDecl extends Decl {
    public final List<DatatypeBind> binds;

    DatatypeDecl(Pos pos, ImmutableList<DatatypeBind> binds) {
      super(pos, Op.DATATYPE_DECL);
      this.binds = requireNonNull(binds);
      checkArgument(!this.binds.isEmpty());
    }

    @Override public int hashCode() {
      return Objects.hash(binds);
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof DatatypeDecl
          && binds.equals(((DatatypeDecl) o).binds);
    }

    public DatatypeDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.appendAll(binds, "datatype ", " and ", "");
    }
  }

  /** Parse tree node of a datatype binding.
   *
   * <p>Example: the datatype declaration
   * {@code datatype 'a x = X1 of 'a | X2 and y = Y}
   * consists of type bindings {@code 'a x = X1 of 'a | X2} and
   * {@code y = Y}. */
  public static class DatatypeBind extends AstNode {
    public final List<TyVar> tyVars;
    public final Id name;
    public final List<TyCon> tyCons;

    DatatypeBind(Pos pos, ImmutableList<TyVar> tyVars, Id name,
        ImmutableList<TyCon> tyCons) {
      super(pos, Op.DATATYPE_DECL);
      this.tyVars = requireNonNull(tyVars);
      this.name = requireNonNull(name);
      this.tyCons = requireNonNull(tyCons);
      checkArgument(!this.tyCons.isEmpty());
    }

    @Override public int hashCode() {
      return Objects.hash(tyVars, tyCons);
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof DatatypeBind
          && name.equals(((DatatypeBind) o).name)
          && tyVars.equals(((DatatypeBind) o).tyVars)
          && tyCons.equals(((DatatypeBind) o).tyCons);
    }

    public DatatypeBind accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      switch (tyVars.size()) {
      case 0:
        break;
      case 1:
        w.append(tyVars.get(0), 0, 0).append(" ");
        break;
      default:
        w.appendAll(tyVars, "(", ", ", ") ");
      }
      return w.id(name.name)
          .appendAll(tyCons, " = ", " | ", "");
    }
  }

  /** Type constructor.
   *
   * <p>For example, in the {@link DatatypeDecl datatype declaration}
   * {@code datatype 'a option = NIL | SOME of 'a}, "NIL" and "SOME of 'a"
   * are both type constructors.
   */
  public static class TyCon extends AstNode {
    public final Id id;
    public final @org.checkerframework.checker.nullness.qual.Nullable Type type;

    TyCon(Pos pos, Id id, Type type) {
      super(pos, Op.TY_CON);
      this.id = requireNonNull(id);
      this.type = type; // optional
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      if (type != null) {
        return w.append(id, left, op.left)
            .append(" of ")
            .append(type, op.right, right);
      } else {
        return w.append(id, left, right);
      }
    }

    public AstNode accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }
  }

  /** Parse tree node of a value declaration. */
  public static class ValDecl extends Decl {
    public final boolean rec;
    public final List<ValBind> valBinds;

    protected ValDecl(Pos pos, boolean rec, ImmutableList<ValBind> valBinds) {
      super(pos, Op.VAL_DECL);
      this.rec = rec;
      this.valBinds = requireNonNull(valBinds);
      checkArgument(!valBinds.isEmpty());
    }

    @Override public int hashCode() {
      return Objects.hash(rec, valBinds);
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof ValDecl
          && this.rec == ((ValDecl) o).rec
          && this.valBinds.equals(((ValDecl) o).valBinds);
    }

    public ValDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      String sep = rec ? "val rec " : "val ";
      for (ValBind valBind : valBinds) {
        w.append(sep);
        sep = " and ";
        valBind.unparse(w, 0, right);
      }
      return w;
    }

    /** Creates a copy of this {@code ValDecl} with given contents,
     * or {@code this} if the contents are the same. */
    public ValDecl copy(Iterable<ValBind> valBinds) {
      return Iterables.elementsEqual(this.valBinds, valBinds)
          ? this
          : ast.valDecl(pos, rec, valBinds);
    }
  }

  /** Parse tree node of a function declaration. */
  public static class FunDecl extends Decl {
    public final List<FunBind> funBinds;

    FunDecl(Pos pos, ImmutableList<FunBind> funBinds) {
      super(pos, Op.FUN_DECL);
      this.funBinds = requireNonNull(funBinds);
      checkArgument(!funBinds.isEmpty());
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

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.appendAll(funBinds, "fun ", " and ", "");
    }
  }

  /** One of the branches (separated by 'and') in a 'fun' function
   * declaration. */
  public static class FunBind extends AstNode {
    public final List<FunMatch> matchList;
    public final String name;

    FunBind(Pos pos, ImmutableList<FunMatch> matchList) {
      super(pos, Op.FUN_BIND);
      checkArgument(!matchList.isEmpty());
      this.matchList = matchList;
      // We assume that the function name is the same in all matches.
      // We will check during validation.
      this.name = matchList.get(0).name;
    }

    public FunBind accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.appendAll(matchList, " | ");
    }
  }

  /** One of the branches (separated by '|') in a 'fun' function declaration. */
  public static class FunMatch extends AstNode {
    public final String name;
    public final List<Pat> patList;
    public final @Nullable Type returnType;
    public final Exp exp;

    FunMatch(Pos pos, String name, ImmutableList<Pat> patList,
        @Nullable Type returnType, Exp exp) {
      super(pos, Op.FUN_MATCH);
      this.name = name;
      this.patList = patList;
      this.returnType = returnType;
      this.exp = exp;
    }

    public FunMatch accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      w.id(name);
      for (Pat pat : patList) {
        w.append(" ").append(pat, Op.APPLY.left, Op.APPLY.right);
      }
      return w.append(" = ").append(exp, 0, right);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }
  }

  /** Tuple. */
  public static class Tuple extends Exp {
    public final List<Exp> args;

    Tuple(Pos pos, Iterable<? extends Exp> args) {
      super(pos, Op.TUPLE);
      this.args = ImmutableList.copyOf(args);
    }

    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
      forEachIndexed(args, action);
    }

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("(");
      forEachArg((arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append(")");
    }

    public Tuple copy(List<Exp> args) {
      return this.args.equals(args) ? this : new Tuple(pos, args);
    }
  }

  /** List expression. */
  public static class ListExp extends Exp {
    public final List<Exp> args;

    ListExp(Pos pos, Iterable<? extends Exp> args) {
      super(pos, Op.LIST);
      this.args = ImmutableList.copyOf(args);
    }

    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
      forEachIndexed(args, action);
    }

    public ListExp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("[");
      forEachArg((arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append("]");
    }

    public ListExp copy(List<Exp> args) {
      return args.equals(this.args) ? this
          : ast.list(pos, args);
    }
  }

  /** Record. */
  public static class Record extends Exp {
    public final SortedMap<String, Exp> args;

    Record(Pos pos, ImmutableSortedMap<String, Exp> args) {
      super(pos, Op.RECORD);
      this.args = requireNonNull(args);
      checkArgument(args.comparator() == ORDERING);
    }

    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
      forEachIndexed(args.values(), action);
    }

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("{");
      Ord.forEachIndexed(args, (i, k, v) ->
          w.append(i > 0 ? ", " : "").append(k).append(" = ").append(v, 0, 0));
      return w.append("}");
    }

    public Record copy(Map<String, Ast.Exp> args) {
      return args.equals(this.args) ? this : ast.record(pos, args);
    }
  }

  /** Call to an infix operator. */
  public static class InfixCall extends Exp {
    public final Exp a0;
    public final Exp a1;

    InfixCall(Pos pos, Op op, Exp a0, Exp a1) {
      super(pos, op);
      this.a0 = requireNonNull(a0);
      this.a1 = requireNonNull(a1);
    }

    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
      action.accept(a0, 0);
      action.accept(a1, 1);
    }

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, a0, op, a1, right);
    }

    /** Creates a copy of this {@code InfixCall} with given contents,
     * or {@code this} if the contents are the same. */
    public InfixCall copy(Exp a0, Exp a1) {
      return this.a0.equals(a0)
          && this.a1.equals(a1)
          ? this
          : new InfixCall(pos, op, a0, a1);
    }
  }

  /** Call to a prefix operator. */
  public static class PrefixCall extends Exp {
    public final Exp a;

    PrefixCall(Pos pos, Op op, Exp a) {
      super(pos, op);
      this.a = requireNonNull(a);
    }

    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
      action.accept(a, 0);
    }

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.prefix(left, op, a, right);
    }
  }

  /** "If ... else" expression. */
  public static class If extends Exp {
    public final Exp condition;
    public final Exp ifTrue;
    public final Exp ifFalse;

    public If(Pos pos, Exp condition, Exp ifTrue, Exp ifFalse) {
      super(pos, Op.IF);
      this.condition = requireNonNull(condition);
      this.ifTrue = requireNonNull(ifTrue);
      this.ifFalse = requireNonNull(ifFalse);
    }

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("if ").append(condition, 0, 0)
          .append(" then ").append(ifTrue, 0, 0)
          .append(" else ").append(ifFalse, 0, right);
    }

    /** Creates a copy of this {@code If} with given contents,
     * or {@code this} if the contents are the same. */
    public If copy(Exp condition, Exp ifTrue, Exp ifFalse) {
      return this.condition.equals(condition)
          && this.ifTrue.equals(ifTrue)
          && this.ifFalse.equals(ifFalse)
          ? this
          : new If(pos, condition, ifTrue, ifFalse);
    }
  }

  /** "Let" expression. */
  public static class Let extends Exp {
    public final List<Decl> decls;
    public final Exp exp;

    Let(Pos pos, ImmutableList<Decl> decls, Exp exp) {
      super(pos, Op.LET);
      this.decls = requireNonNull(decls);
      this.exp = requireNonNull(exp);
    }

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.appendAll(decls, "let ", "; ", " in ")
          .append(exp, 0, 0).append(" end");
    }

    /** Creates a copy of this {@code LetExp} with given contents,
     * or {@code this} if the contents are the same. */
    public Let copy(Iterable<Decl> decls, Exp exp) {
      return Iterables.elementsEqual(this.decls, decls)
          && Objects.equals(this.exp, exp)
          ? this
          : ast.let(pos, decls, exp);
    }
  }

  /** Value bind. */
  public static class ValBind extends AstNode {
    public final Pat pat;
    public final Exp exp;

    ValBind(Pos pos, Pat pat, Exp exp) {
      super(pos, Op.VAL_BIND);
      this.pat = pat;
      this.exp = exp;
    }

    public AstNode accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(pat, 0, 0).append(" = ").append(exp, 0, right);
    }

    /** Creates a copy of this {@code ValBind} with given contents,
     * or {@code this} if the contents are the same. */
    public ValBind copy(Pat pat, Exp exp) {
      return this.pat.equals(pat)
          && this.exp.equals(exp)
          ? this
          : ast.valBind(pos, pat, exp);
    }
  }

  /** Match. */
  public static class Match extends AstNode {
    public final Pat pat;
    public final Exp exp;

    Match(Pos pos, Pat pat, Exp exp) {
      super(pos, Op.MATCH);
      this.pat = pat;
      this.exp = exp;
    }

    public Match accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(pat, 0, 0).append(" => ").append(exp, 0, right);
    }

    /** Creates a copy of this {@code Match} with given contents,
     * or {@code this} if the contents are the same. */
    public Match copy(Pat pat, Exp exp) {
      return this.pat.equals(pat)
          && this.exp.equals(exp)
          ? this
          : ast.match(pos, pat, exp);
    }
  }

  /** Lambda expression. */
  public static class Fn extends Exp {
    public final List<Match> matchList;

    Fn(Pos pos, ImmutableList<Match> matchList) {
      super(pos, Op.FN);
      this.matchList = requireNonNull(matchList);
      checkArgument(!matchList.isEmpty());
    }

    public Fn accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("fn ").appendAll(matchList, 0, Op.BAR, right);
    }

    /** Creates a copy of this {@code Fn} with given contents,
     * or this if the contents are the same. */
    public Fn copy(List<Match> matchList) {
      return this.matchList.equals(matchList)
          ? this
          : ast.fn(pos, matchList);
    }
  }

  /** Case expression. */
  public static class Case extends Exp {
    public final Exp exp;
    public final List<Match> matchList;

    Case(Pos pos, Exp exp, ImmutableList<Match> matchList) {
      super(pos, Op.CASE);
      this.exp = exp;
      this.matchList = matchList;
    }

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("case ").append(exp, 0, 0).append(" of ")
          .appendAll(matchList, left, Op.BAR, right);
    }

    public Case copy(Exp exp, List<Match> matchList) {
      return this.exp.equals(exp)
          && this.matchList.equals(matchList)
          ? this
          : ast.caseOf(pos, exp, matchList);
    }
  }

  /** From expression. */
  public static class From extends Exp {
    public final ImmutableList<FromStep> steps;
    /** An implicit yield expression, if the last step is not a {@link Yield};
     * null if the last step is a {@link Yield}. */
    public final @Nullable Exp implicitYieldExp;

    From(Pos pos, ImmutableList<FromStep> steps,
        @Nullable Exp implicitYieldExp) {
      super(pos, Op.FROM);
      this.steps = requireNonNull(steps);
      this.implicitYieldExp = implicitYieldExp;
    }

    static @Nullable Exp implicitYieldExp(Pos pos, List<FromStep> steps) {
      if (!steps.isEmpty()
          && getLast(steps) instanceof Ast.Yield) {
        // No implicit yield is needed; the last step is an explicit yield
        return null;
      }
      Set<Id> fields = ImmutableSet.of();
      final Set<Id> nextFields = new HashSet<>();
      for (FromStep step : steps) {
        switch (step.op) {
        case SCAN:
          final Scan scan = (Scan) step;
          nextFields.clear();
          nextFields.addAll(fields);
          scan.pat.visit(p -> {
            if (p instanceof IdPat) {
              nextFields.add(ast.id(Pos.ZERO, ((IdPat) p).name));
            }
          });
          fields = ImmutableSet.copyOf(nextFields);
          break;

        case COMPUTE:
        case GROUP:
          final Group group = (Group) step;
          final ImmutablePairList<Id, Exp> groupExps = group.groupExps;
          final List<Aggregate> aggregates = group.aggregates;

          // The type of
          //   from e in emps group a = e1, b = e2 compute c = sum of e3
          // is the same as the type of
          //   {a = e1, b = e2, c = sum (map (fn e => e3) [])}
          nextFields.clear();
          nextFields.addAll(Pair.left(groupExps));
          groupExps.forEach((id, exp) -> nextFields.add(id));
          aggregates.forEach(aggregate -> nextFields.add(aggregate.id));
          fields = nextFields;
          break;

        case YIELD:
          final Yield yield = (Yield) step;
          if (yield.exp instanceof Record) {
            fields =
                ((Record) yield.exp).args.keySet()
                    .stream()
                    .map(label -> ast.id(Pos.ZERO, label))
                    .collect(Collectors.toSet());
          }
          break;
        }
      }

      if (fields.size() == 1
          && (steps.isEmpty()
          || getLast(steps).op != Op.YIELD
          || ((Yield) getLast(steps)).exp.op != Op.RECORD)) {
        return Iterables.getOnlyElement(fields);
      } else {
        final SortedMap<String, Ast.Exp> map = mutableMap();
        fields.forEach(field -> map.put(field.name, field));
        return ast.record(pos, map);
      }
    }

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      if (left > op.left || op.right < right) {
        return w.append("(").append(this, 0, 0).append(")");
      } else {
        w.append("from");
        forEachIndexed(steps, (step, i) -> {
          if (step.op == Op.SCAN && i > 0) {
            if (steps.get(i - 1).op == Op.SCAN) {
              w.append(",");
            } else {
              w.append(" join");
            }
          }
          step.unparse(w, 0, 0);
        });
        return w;
      }
    }

    /** Creates a copy of this {@code From} with given contents,
     * or {@code this} if the contents are the same. */
    public From copy(List<FromStep> steps, @Nullable Exp implicitYieldExp) {
      return this.steps.equals(steps)
          ? this
          : ast.from(pos, steps, implicitYieldExp);
    }

    /** Returns whether this {@code from} expression ends with a {@code compute}
     * step. If so, it is a <em>monoid</em> comprehension, not a <em>monad</em>
     * comprehension, and its type is a scalar value (or record), not a list. */
    public boolean isCompute() {
      return !steps.isEmpty()
          && steps.get(steps.size() - 1).op == Op.COMPUTE;
    }
  }

  /** A step in a {@code from} expression - {@code where}, {@code group}
   * or {@code order}. */
  public abstract static class FromStep extends AstNode {
    FromStep(Pos pos, Op op) {
      super(pos, op);
    }
  }

  /** A scan (e.g. "e in emps", "e")
   * or scan-and-join (e.g. "left join d in depts on e.deptno = d.deptno")
   * in a {@code from} expression. */
  public static class Scan extends FromStep {
    public final Pat pat;
    public final @Nullable Exp exp;
    public final @Nullable Exp condition;

    Scan(Pos pos, Pat pat, @Nullable Exp exp, @Nullable Exp condition) {
      super(pos, Op.SCAN);
      this.pat = pat;
      this.exp = exp;
      this.condition = condition;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append(op.padded)
          .append(pat, 0, 0);
      if (exp != null) {
        if (exp.op == Op.FROM_EQ) {
          w.append(" = ")
              .append(((PrefixCall) this.exp).a, Op.EQ.right, 0);
        } else {
          w.append(" in ")
              .append(this.exp, Op.EQ.right, 0);
        }
      }
      if (condition != null) {
        w.append(" on ")
            .append(condition, 0, 0);
      }
      return w;
    }

    @Override public Scan accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Scan copy(Pat pat, @Nullable Exp exp, @Nullable Exp condition) {
      return this.pat.equals(pat)
          && Objects.equals(this.exp, exp)
          && Objects.equals(this.condition, condition)
          ? this
          : new Scan(pos, pat, exp, condition);
    }
  }

  /** A {@code where} clause in a {@code from} expression. */
  public static class Where extends FromStep {
    public final Exp exp;

    Where(Pos pos, Exp exp) {
      super(pos, Op.WHERE);
      this.exp = exp;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(" where ").append(exp, 0, 0);
    }

    @Override public AstNode accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Where copy(Exp exp) {
      return this.exp.equals(exp) ? this : new Where(pos, exp);
    }
  }

  /** A {@code skip} clause in a {@code from} expression. */
  public static class Skip extends FromStep {
    public final Exp exp;

    Skip(Pos pos, Exp exp) {
      super(pos, Op.SKIP);
      this.exp = exp;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(" skip ").append(exp, 0, 0);
    }

    @Override public AstNode accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Skip copy(Exp exp) {
      return this.exp.equals(exp) ? this : new Skip(pos, exp);
    }
  }

  /** A {@code take} clause in a {@code from} expression. */
  public static class Take extends FromStep {
    public final Exp exp;

    Take(Pos pos, Exp exp) {
      super(pos, Op.TAKE);
      this.exp = exp;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(" take ").append(exp, 0, 0);
    }

    @Override public AstNode accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Take copy(Exp exp) {
      return this.exp.equals(exp) ? this : new Take(pos, exp);
    }
  }

  /** A {@code yield} clause in a {@code from} expression. */
  public static class Yield extends FromStep {
    public final Exp exp;

    Yield(Pos pos, Exp exp) {
      super(pos, Op.YIELD);
      this.exp = exp;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(" yield ").append(exp, 0, 0);
    }

    @Override public AstNode accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Yield copy(Exp exp) {
      return this.exp.equals(exp) ? this : new Yield(pos, exp);
    }
  }

  /** An {@code order} clause in a {@code from} expression. */
  public static class Order extends FromStep {
    public final ImmutableList<OrderItem> orderItems;

    Order(Pos pos, ImmutableList<OrderItem> orderItems) {
      super(pos, Op.ORDER);
      this.orderItems = requireNonNull(orderItems);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(" order ").appendAll(orderItems, ", ");
    }

    @Override public AstNode accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Order copy(List<OrderItem> orderItems) {
      return this.orderItems.equals(orderItems)
          ? this
          : new Order(pos, ImmutableList.copyOf(orderItems));
    }
  }

  /** An item in an {@code order} clause. */
  public static class OrderItem extends AstNode {
    public final Exp exp;
    public final Direction direction;

    OrderItem(Pos pos, Exp exp, Direction direction) {
      super(pos, Op.ORDER_ITEM);
      this.exp = requireNonNull(exp);
      this.direction = requireNonNull(direction);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(exp, 0, 0)
          .append(direction == Direction.DESC ? " desc" : "");
    }

    public AstNode accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    public OrderItem copy(Exp exp, Direction direction) {
      return this.exp.equals(exp)
          && this.direction == direction
          ? this
          : new OrderItem(pos, exp, direction);
    }
  }

  /** Sort order. */
  public enum Direction {
    ASC,
    DESC
  }

  /** A {@code group} clause in a {@code from} expression. */
  public static class Group extends FromStep {
    public final ImmutablePairList<Id, Exp> groupExps;
    public final ImmutableList<Aggregate> aggregates;

    Group(Pos pos, Op op, ImmutablePairList<Id, Exp> groupExps,
        ImmutableList<Aggregate> aggregates) {
      super(pos, op);
      this.groupExps = groupExps;
      this.aggregates = aggregates;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      if (op == Op.GROUP) {
        w.append(" group");
      }
      forEachIndexed(groupExps, (i, id, exp) ->
          w.append(i == 0 ? " " : ", ")
              .append(id, 0, 0)
              .append(" = ")
              .append(exp, 0, 0));
      forEachIndexed(aggregates, (aggregate, i) ->
          w.append(i == 0 ? " compute " : ", ")
              .append(aggregate, 0, 0));
      return w;
    }

    @Override public AstNode accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Group copy(PairList<Id, Exp> groupExps,
        List<Aggregate> aggregates) {
      checkArgument(op == Op.GROUP, "use Compute.copy instead?");
      return this.groupExps.equals(groupExps)
          && this.aggregates.equals(aggregates)
          ? this
          : ast.group(pos, groupExps, aggregates);
    }
  }

  /** A {@code compute} clause in a {@code from} expression.
   *
   * <p>Because {@code compute} and {@code group} are structurally similar, this
   * is a  sub-class of {@link Group}, with an empty list of group keys. But
   * remember that the type derivation rules are different. */
  public static class Compute extends Group {
    Compute(Pos pos, ImmutableList<Aggregate> aggregates) {
      super(pos, Op.COMPUTE, ImmutablePairList.of(), aggregates);
    }

    @Override public AstNode accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Compute copy(List<Aggregate> aggregates) {
      return this.aggregates.equals(aggregates)
          ? this
          : ast.compute(pos, aggregates);
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

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, fn, op, arg, right);
    }

    public Apply copy(Exp fn, Exp arg) {
      return this.fn.equals(fn) && this.arg.equals(arg) ? this
          : new Apply(pos, fn, arg);
    }
  }

  /** Call to an aggregate function in a {@code compute} clause.
   *
   * <p>For example, in {@code compute sumId = sum of #id e},
   * {@code aggregate} is "sum", {@code argument} is "#id e",
   * and {@code id} is "sumId". */
  public static class Aggregate extends AstNode {
    public final Exp aggregate;
    public final Exp argument;
    public final Id id;

    Aggregate(Pos pos, Exp aggregate, @Nullable Exp argument, Id id) {
      super(pos, Op.AGGREGATE);
      this.aggregate = requireNonNull(aggregate);
      this.argument = argument;
      this.id = requireNonNull(id);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      w.id(id.name)
          .append(" = ")
          .append(aggregate, 0, 0);
      if (argument != null) {
        w.append(" of ")
            .append(argument, 0, 0);
      }
      return w;
    }

    public AstNode accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Aggregate copy(Exp aggregate, Exp argument, Id id) {
      return this.aggregate.equals(aggregate)
          && Objects.equals(this.argument, argument)
          && this.id.equals(id)
          ? this
          : ast.aggregate(pos, aggregate, argument, id);
    }
  }
}

// End Ast.java
