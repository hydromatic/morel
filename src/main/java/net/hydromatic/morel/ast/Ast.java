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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;

import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import static net.hydromatic.morel.ast.AstBuilder.ast;

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
      this.tyCon = Objects.requireNonNull(tyCon);
      this.pat = Objects.requireNonNull(pat);
    }

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      action.accept(pat, 0);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, tyCon, op, pat, right);
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
      this.tyCon = Objects.requireNonNull(tyCon);
    }

    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return tyCon.unparse(w, left, right);
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
      Ord.forEach(args, action);
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
      Ord.forEach(args, action);
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
      Ord.forEach(args.values(), action);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("{");
      Ord.forEach(args, (i, k, v) ->
          w.append(i > 0 ? ", " : "").append(k).append(" = ").append(v, 0, 0));
      if (ellipsis) {
        if (!args.isEmpty()) {
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
    public final Exp e;

    /** Creates a type annotation. */
    AnnotatedExp(Pos pos, Type type, Exp e) {
      super(pos, Op.ANNOTATED_EXP);
      this.type = Objects.requireNonNull(type);
      this.e = Objects.requireNonNull(e);
    }

    @Override public int hashCode() {
      return Objects.hash(type, e);
    }

    @Override public boolean equals(Object obj) {
      return this == obj
          || obj instanceof AnnotatedExp
              && type.equals(((AnnotatedExp) obj).type)
              && e.equals(((AnnotatedExp) obj).e);
    }

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, e, op, type, right);
    }
  }

  /** Parse tree for a named type (e.g. "int" or "(int, string) list"). */
  public static class NamedType extends Type {
    public final java.util.List<Type> types;
    public final String name;

    /** Creates a type. */
    NamedType(Pos pos, ImmutableList<Type> types, String name) {
      super(pos, Op.NAMED_TYPE);
      this.types = Objects.requireNonNull(types);
      this.name = Objects.requireNonNull(name);
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

    AstWriter unparse(AstWriter w, int left, int right) {
      switch (types.size()) {
      case 0:
        return w.append(name);
      case 1:
        return w.append(types.get(0), left, op.left)
            .append(" ").append(name);
      default:
        w.append("(");
        Ord.forEach(types, (type, i) ->
            w.append(i == 0 ? "" : ", ").append(type, 0, 0));
        return w.append(") ")
            .append(name);
      }
    }
  }

  /** Parse tree node of a type variable. */
  public static class TyVar extends Type {
    public final String name;

    /** Creates a TyVar. */
    TyVar(Pos pos, String name) {
      super(pos, Op.TY_VAR);
      this.name = Objects.requireNonNull(name);
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

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(name);
    }
  }

  /** Parse tree node of a record type. */
  public static class RecordType extends Type {
    public final Map<String, Type> fieldTypes;

    /** Creates a TyVar. */
    RecordType(Pos pos, ImmutableMap<String, Type> fieldTypes) {
      super(pos, Op.RECORD_TYPE);
      this.fieldTypes = Objects.requireNonNull(fieldTypes);
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

    AstWriter unparse(AstWriter w, int left, int right) {
      w.append("{");
      Ord.forEach(fieldTypes, (i, field, type) ->
          w.append(i > 0 ? ", " : "")
              .append(field).append(": ").append(type, 0, 0));
      return w.append("}");
    }
  }

  /** Tuple type. */
  public static class TupleType extends Type {
    public final java.util.List<Type> types;

    TupleType(Pos pos, ImmutableList<Type> types) {
      super(pos, Op.TUPLE_TYPE);
      this.types = Objects.requireNonNull(types);
    }

    public Type accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      // "*" is non-associative. Elevate both left and right precedence
      // to force parentheses if the inner expression is also "*".
      Ord.forEach(types, (arg, i) ->
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
    public final java.util.List<Type> types;

    CompositeType(Pos pos, ImmutableList<Type> types) {
      super(pos, Op.TUPLE_TYPE);
      this.types = Objects.requireNonNull(types);
    }

    public Type accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      w.append("(");
      Ord.forEach(types, (arg, i) ->
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
      this.paramType = Objects.requireNonNull(paramType);
      this.resultType = Objects.requireNonNull(resultType);
    }

    public Type accept(Shuttle shuttle) {
      return shuttle.visit(this);
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
    public final java.util.List<Exp> args() {
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
    public final java.util.List<DatatypeBind> binds;

    DatatypeDecl(Pos pos, ImmutableList<DatatypeBind> binds) {
      super(pos, Op.DATATYPE_DECL);
      this.binds = Objects.requireNonNull(binds);
      Preconditions.checkArgument(!this.binds.isEmpty());
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
    public final java.util.List<TyVar> tyVars;
    public final Id name;
    public final java.util.List<TyCon> tyCons;

    DatatypeBind(Pos pos, ImmutableList<TyVar> tyVars, Id name,
        ImmutableList<TyCon> tyCons) {
      super(pos, Op.DATATYPE_DECL);
      this.tyVars = Objects.requireNonNull(tyVars);
      this.name = Objects.requireNonNull(name);
      this.tyCons = Objects.requireNonNull(tyCons);
      Preconditions.checkArgument(!this.tyCons.isEmpty());
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
      return w.append(name.name)
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
    public final Type type;

    TyCon(Pos pos, Id id, Type type) {
      super(pos, Op.TY_CON);
      this.id = Objects.requireNonNull(id);
      this.type = type; // optional
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

    /** Creates a copy of this {@code ValDecl} with given contents,
     * or {@code this} if the contents are the same. */
    public ValDecl copy(Iterable<ValBind> valBinds) {
      return Iterables.elementsEqual(this.valBinds, valBinds)
          ? this
          : ast.valDecl(pos, valBinds);
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
      return w.appendAll(funBinds, "fun ", " and ", "");
    }
  }

  /** One of the branches (separated by 'and') in a 'fun' function
   * declaration. */
  public static class FunBind extends AstNode {
    public final java.util.List<FunMatch> matchList;
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
      return w.appendAll(matchList, " | ");
    }
  }

  /** One of the branches (separated by '|') in a 'fun' function declaration. */
  public static class FunMatch extends AstNode {
    public final String name;
    public final java.util.List<Pat> patList;
    public final Exp e;

    FunMatch(Pos pos, String name, ImmutableList<Pat> patList, Exp e) {
      super(pos, Op.FUN_MATCH);
      this.name = name;
      this.patList = patList;
      this.e = e;
    }

    public FunMatch accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      w.append(name);
      for (Pat pat : patList) {
        w.append(" ").append(pat, Op.APPLY.left, Op.APPLY.right);
      }
      return w.append(" = ").append(e, 0, right);
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
      Ord.forEach(args, action);
    }

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("(");
      forEachArg((arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append(")");
    }

    public Tuple copy(java.util.List<Exp> args) {
      return this.args.equals(args) ? this : new Tuple(pos, args);
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
      Ord.forEach(args, action);
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
      Ord.forEach(args.values(), action);
    }

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("{");
      Ord.forEach(args, (i, k, v) ->
          w.append(i > 0 ? ", " : "").append(k).append(" = ").append(v, 0, 0));
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

  /** Call to an prefix operator. */
  public static class PrefixCall extends Exp {
    public final Exp a;

    PrefixCall(Pos pos, Op op, Exp a) {
      super(pos, op);
      this.a = Objects.requireNonNull(a);
    }

    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
      action.accept(a, 0);
    }

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
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

    /** Creates a copy of this {@code LetExp} with given contents,
     * or {@code this} if the contents are the same. */
    public LetExp copy(Iterable<Decl> decls, Exp e) {
      return Iterables.elementsEqual(this.decls, decls)
          && Objects.equals(this.e, e)
          ? this
          : ast.let(pos, decls, e);
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

    /** Creates a copy of this {@code ValBind} with given contents,
     * or {@code this} if the contents are the same. */
    public ValBind copy(boolean rec, Pat pat, Exp e) {
      return this.rec == rec
          && this.pat.equals(pat)
          && this.e.equals(e)
          ? this
          : ast.valBind(pos, rec, pat, e);
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

    /** Creates a copy of this {@code Match} with given contents,
     * or {@code this} if the contents are the same. */
    public Match copy(Pat pat, Exp e) {
      return this.pat.equals(pat)
          && this.e.equals(e)
          ? this
          : ast.match(pos, pat, e);
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
      return w.append("fn ").appendAll(matchList, 0, Op.BAR, right);
    }

    /** Creates a copy of this {@code Fn} with given contents,
     * or this if the contents are the same. */
    public Fn copy(java.util.List<Match> matchList) {
      return this.matchList.equals(matchList)
          ? this
          : ast.fn(pos, matchList);
    }
  }

  /** Case expression. */
  public static class Case extends Exp {
    public final Exp e;
    public final java.util.List<Match> matchList;

    Case(Pos pos, Exp e, ImmutableList<Match> matchList) {
      super(pos, Op.CASE);
      this.e = e;
      this.matchList = matchList;
    }

    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("case ").append(e, 0, 0).append(" of ")
          .appendAll(matchList, left, Op.BAR, right);
    }
  }

  /** From expression. */
  public static class From extends Exp {
    public final Map<Id, Exp> sources;
    public final ImmutableList<FromStep> steps;
    public final Exp yieldExp;
    /** The expression in the yield clause, or the default yield expression
     * if not specified; never null. */
    public final Exp yieldExpOrDefault;

    From(Pos pos, ImmutableMap<Id, Exp> sources, ImmutableList<FromStep> steps,
        Exp yieldExp) {
      super(pos, Op.FROM);
      this.sources = Objects.requireNonNull(sources);
      this.steps = Objects.requireNonNull(steps);
      Set<Id> fields = sources.keySet();
      for (FromStep step : steps) {
        if (step instanceof Group) {
          final Group group = (Group) step;
          final ImmutableList<Pair<Id, Exp>> groupExps = group.groupExps;
          final ImmutableList<Aggregate> aggregates = group.aggregates;

          // The type of
          //   from emps as e group by a = e1, b = e2 compute c = sum of e3
          // is the same as the type of
          //   {e1 as a, e2 as b, sum (map (fn e => c) list) as x}
          final Set<Id> nextFields = new HashSet<>(Pair.left(groupExps));
          groupExps.forEach(pair -> nextFields.add(pair.left));
          final Literal aggResult =
              ast.intLiteral(BigDecimal.ZERO, pos); // FIXME
          aggregates.forEach(aggregate -> nextFields.add(aggregate.id));
          fields = nextFields;
        }
      }
      this.yieldExp = yieldExp;
      if (yieldExp != null) {
        this.yieldExpOrDefault = this.yieldExp;
      } else if (fields.size() == 1) {
        this.yieldExpOrDefault = Iterables.getOnlyElement(fields);
      } else {
        this.yieldExpOrDefault =
            ast.record(pos, fields.stream()
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
        w.append("from");
        Ord.forEach(sources, (i, id, exp) ->
            w.append(i == 0 ? " " : ", ")
                .append(id, 0, 0).append(" in ").append(exp, 0, 0));
        for (FromStep step : steps) {
          w.append(" ");
          step.unparse(w, 0, 0);
        }
        if (yieldExp != null) {
          w.append(" yield ").append(yieldExp, 0, 0);
        }
        return w;
      }
    }

    /** Creates a copy of this {@code From} with given contents,
     * or {@code this} if the contents are the same. */
    public From copy(Map<Ast.Id, Ast.Exp> sources,
        java.util.List<FromStep> steps, Ast.Exp yieldExp) {
      return this.sources.equals(sources)
          && this.steps.equals(steps)
          && Objects.equals(this.yieldExp, yieldExp)
          ? this
          : ast.from(pos, sources, steps, yieldExp);
    }
  }

  /** A step in a {@code from} expression - {@code where}, {@code group}
   * or {@code order}. */
  public abstract static class FromStep extends AstNode {
    FromStep(Pos pos, Op op) {
      super(pos, op);
    }

    /** Returns the names of the fields produced by this step, given the names
     * of the fields that are input to this step.
     *
     * <p>By default, a step outputs the same fields as it inputs.
     */
    public void deriveOutBindings(Iterable<Binding> inBindings,
        BiFunction<String, AstNode, Binding> binder,
        Consumer<Binding> outBindings) {
      inBindings.forEach(outBindings);
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
      return w.append("where ").append(exp, 0, 0);
    }

    @Override public AstNode accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    public Where copy(Exp exp) {
      return this.exp.equals(exp) ? this : new Where(pos, exp);
    }
  }

  /** An {@code order} clause in a {@code from} expression. */
  public static class Order extends FromStep {
    public final ImmutableList<OrderItem> orderItems;

    Order(Pos pos, ImmutableList<OrderItem> orderItems) {
      super(pos, Op.ORDER);
      this.orderItems = Objects.requireNonNull(orderItems);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("order ").appendAll(orderItems, ", ");
    }

    @Override public AstNode accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    public Order copy(java.util.List<OrderItem> orderItems) {
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
      this.exp = Objects.requireNonNull(exp);
      this.direction = Objects.requireNonNull(direction);
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
    public final ImmutableList<Pair<Id, Exp>> groupExps;
    public final ImmutableList<Aggregate> aggregates;

    Group(Pos pos, ImmutableList<Pair<Id, Exp>> groupExps,
        ImmutableList<Aggregate> aggregates) {
      super(pos, Op.GROUP);
      this.groupExps = groupExps;
      this.aggregates = aggregates;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      Pair.forEachIndexed(groupExps, (i, id, exp) ->
          w.append(i == 0 ? "group " : ", ")
              .append(id, 0, 0)
              .append(" = ")
              .append(exp, 0, 0));
      Ord.forEach(aggregates, (aggregate, i) ->
          w.append(i == 0 ? " compute " : ", ")
              .append(aggregate, 0, 0));
      return w;
    }

    @Override public AstNode accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void deriveOutBindings(Iterable<Binding> inBindings,
        BiFunction<String, AstNode, Binding> binder,
        Consumer<Binding> outBindings) {
      groupExps.forEach(idExp ->
          outBindings.accept(binder.apply(idExp.left.name, idExp.left)));
      aggregates.forEach(aggregate ->
          outBindings.accept(binder.apply(aggregate.id.name, aggregate)));
    }

    public Group copy(java.util.List<Pair<Id, Exp>> groupExps,
        java.util.List<Aggregate> aggregates) {
      return this.groupExps.equals(groupExps)
          && this.aggregates.equals(aggregates)
          ? this
          : ast.group(pos, groupExps, aggregates);
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

    public Apply copy(Exp fn, Exp arg) {
      return this.fn.equals(fn) && this.arg.equals(arg) ? this
          : new Apply(pos, fn, arg);
    }
  }

  /** Call to an aggregate function in a {@code compute} clause.
   *
   * <p>For example, in {@code sum of #id e as sumId},
   * {@code aggregate} is "sum", {@code argument} is "#id e",
   * and {@code id} is "sumId". */
  public static class Aggregate extends AstNode {
    public final Exp aggregate;
    public final Exp argument;
    public final Id id;

    Aggregate(Pos pos, Exp aggregate, @Nullable Exp argument, Id id) {
      super(pos, Op.AGGREGATE);
      this.aggregate = Objects.requireNonNull(aggregate);
      this.argument = argument;
      this.id = Objects.requireNonNull(id);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      w.append(id.name)
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

    public Aggregate copy(Exp aggregate, Exp argument, Id id) {
      return this.aggregate.equals(aggregate)
          && Objects.equals(this.argument, argument)
          && this.id.equals(id)
          ? this
          : ast.aggregate(pos, aggregate, argument, id);
    }
  }

  /** Expression that is a wrapped {@link Applicable}.
   *
   * <p>Does not occur in the output of the parser, only as an intermediate
   * value during compilation. */
  public static class ApplicableExp extends Ast.Exp {
    public final Applicable applicable;

    ApplicableExp(Applicable applicable) {
      super(Pos.ZERO, Op.WRAPPED_APPLICABLE);
      this.applicable = Objects.requireNonNull(applicable);
    }

    public Ast.Exp accept(Shuttle shuttle) {
      return this;
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w;
    }
  }
}

// End Ast.java
