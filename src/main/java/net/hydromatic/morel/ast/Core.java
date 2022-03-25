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

import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Resolver;
import net.hydromatic.morel.eval.Closure;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.ObjIntConsumer;
import javax.annotation.Nullable;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Pair.forEachIndexed;

import static com.google.common.base.Preconditions.checkArgument;

import static java.util.Objects.requireNonNull;

/** Core expressions.
 *
 * <p>Many expressions are sub-classes of similarly named expressions in
 * {@link Ast}. This class functions as a namespace, so that we can keep the
 * class names short.
 */
// TODO: remove 'parse tree for...' from all the comments below
@SuppressWarnings("StaticPseudoFunctionalStyleMethod")
public class Core {
  private Core() {}

  /** Abstract base class of Core nodes. */
  abstract static class BaseNode extends AstNode {
    BaseNode(Pos pos, Op op) {
      super(pos, op);
    }

    @Override public AstNode accept(Shuttle shuttle) {
      throw new UnsupportedOperationException(getClass() + " cannot accept "
          + shuttle.getClass());
    }

    @Override public void accept(Visitor visitor) {
      throw new UnsupportedOperationException(getClass() + " cannot accept "
          + visitor.getClass());
    }
  }

  /** Base class for a pattern.
   *
   * <p>For example, "x" in "val x = 5" is a {@link IdPat};
   * the "(x, y) in "val (x, y) = makePair 1 2" is a {@link TuplePat}. */
  public abstract static class Pat extends BaseNode {
    public final Type type;

    Pat(Op op, Type type) {
      super(Pos.ZERO, op);
      this.type = requireNonNull(type);
    }

    /** Returns the type. */
    public Type type() {
      return type;
    }

    @Override public abstract Pat accept(Shuttle shuttle);
  }

  /** Base class for named patterns ({@link IdPat} and {@link AsPat}).
   *
   * <p>Implements {@link Comparable} so that names are sorted correctly
   * for record fields (see {@link RecordType#ORDERING}).
   *
   * <p>A {@link Core.ValDecl} must be one of these. */
  public abstract static class NamedPat extends Pat
      implements Comparable<NamedPat> {
    /** Ordering that compares named patterns by their names, then by their
     * ordinal. */
    public static final Ordering<NamedPat> ORDERING =
        Ordering.from(NamedPat::compare);

    public final String name;
    public final int i;

    NamedPat(Op op, Type type, String name, int i) {
      super(op, type);
      this.name = requireNonNull(name, "name");
      this.i = i;
      checkArgument(!name.isEmpty(), "empty name");
    }

    /** {@inheritDoc}
     *
     * <p>Collate first on name, then on ordinal. */
    @Override public int compareTo(NamedPat o) {
      return compare(this, o);
    }

    /** Helper for {@link #ORDERING}. */
    static int compare(NamedPat o1, NamedPat o2) {
      int c = RecordType.compareNames(o1.name, o2.name);
      if (c != 0) {
        return c;
      }
      return Integer.compare(o1.i, o2.i);
    }

    public abstract NamedPat withType(Type type);

    @Override public abstract NamedPat accept(Shuttle shuttle);
  }

  /** Named pattern.
   *
   * @see Ast.Id */
  public static class IdPat extends NamedPat {
    IdPat(Type type, String name, int i) {
      super(Op.ID_PAT, type, name, i);
    }

    @Override public int hashCode() {
      return name.hashCode() + i;
    }

    @Override public boolean equals(Object obj) {
      return obj == this
          || obj instanceof IdPat
          && ((IdPat) obj).name.equals(name)
          && ((IdPat) obj).i == i;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(name, i);
    }

    @Override public IdPat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override public IdPat withType(Type type) {
      return type == this.type ? this : new IdPat(type, name, i);
    }
  }

  /** Literal pattern, the pattern analog of the {@link Literal} expression.
   *
   * <p>For example, "0" in "fun fact 0 = 1 | fact n = n * fact (n - 1)".*/
  @SuppressWarnings("rawtypes")
  public static class LiteralPat extends Pat {
    public final Comparable value;

    LiteralPat(Op op, Type type, Comparable value) {
      super(op, type);
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

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.appendLiteral(value);
    }

    @Override public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }
  }

  /** Wildcard pattern.
   *
   * <p>For example, "{@code _}" in "{@code fn foo _ => 42}". */
  public static class WildcardPat extends Pat {
    WildcardPat(Type type) {
      super(Op.WILDCARD_PAT, type);
    }

    @Override public int hashCode() {
      return "_".hashCode();
    }

    @Override public boolean equals(Object o) {
      return o instanceof WildcardPat;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("_");
    }

    @Override public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }
  }

  /** Layered pattern. */
  public static class AsPat extends NamedPat {
    public final Pat pat;

    protected AsPat(Type type, String name, int i, Pat pat) {
      super(Op.AS_PAT, type, name, i);
      this.pat = requireNonNull(pat);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(name).append(" as ").append(pat, 0, 0);
    }

    @Override public AsPat withType(Type type) {
      return type == this.type ? this : new AsPat(type, name, i, pat);
    }

    @Override public AsPat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    /** Creates a copy of this {@code AsPat} with given contents,
     * or {@code this} if the contents are the same. */
    public Core.AsPat copy(String name, int i, Core.Pat pat) {
      return this.name.equals(name)
          && this.i == i
          && this.pat.equals(pat)
          ? this
          : new AsPat(type, name, i, pat);
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
    public final String tyCon;
    public final Pat pat;

    /** Mostly-private constructor.
     *
     * <p>Exposed so that "op ::" (cons) can supply a different {@link Op}
     * value. The "list" datatype is not represented the same as other
     * datatypes, and the separate "op" value allows us to deconstruct it in a
     * different way. */
    protected ConPat(Op op, Type type, String tyCon, Pat pat) {
      super(op, type);
      this.tyCon = requireNonNull(tyCon);
      this.pat = requireNonNull(pat);
      checkArgument(op == Op.CON_PAT || op == Op.CONS_PAT);
    }

    ConPat(Type type, String tyCon, Pat pat) {
      this(Op.CON_PAT, type, tyCon, pat);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(tyCon).append("(").append(pat, 0, 0).append(")");
    }

    @Override public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    /** Creates a copy of this {@code ConPat} with given contents,
     * or {@code this} if the contents are the same. */
    public Core.ConPat copy(String tyCon, Core.Pat pat) {
      return this.tyCon.equals(tyCon)
          && this.pat.equals(pat)
          ? this
          : new ConPat(op, type, tyCon, pat);
    }
  }

  /** Type constructor pattern with no argument.
   *
   * <p>For example, in "fun nvl NIL = 0 | OPTION x = x",
   * "NIL" is a zero-arg type constructor pattern.
   *
   * @see ConPat */
  public static class Con0Pat extends Pat {
    public final String tyCon;

    Con0Pat(DataType type, String tyCon) {
      super(Op.CON0_PAT, type);
      this.tyCon = requireNonNull(tyCon);
    }

    @Override public DataType type() {
      return (DataType) type;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(tyCon);
    }

    @Override public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }
  }

  /** Tuple pattern, the pattern analog of the {@link Tuple} expression.
   *
   * <p>For example, "(x, y)" in "fun sum (x, y) = x + y". */
  public static class TuplePat extends Pat {
    public final List<Pat> args;

    TuplePat(Type type, ImmutableList<Pat> args) {
      super(Op.TUPLE_PAT, type);
      this.args = requireNonNull(args);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("(");
      forEachIndexed(args, (arg, i) ->
          w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append(")");
    }

    @Override public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public TuplePat copy(TypeSystem typeSystem, List<Pat> args) {
      return args.equals(this.args) ? this
          : core.tuplePat(typeSystem, args);
    }
  }

  /** List pattern.
   *
   * <p>For example, "[x, y]" in "fun sum [x, y] = x + y". */
  public static class ListPat extends Pat {
    public final List<Pat> args;

    ListPat(Type type, ImmutableList<Pat> args) {
      super(Op.LIST_PAT, type);
      this.args = requireNonNull(args);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("[");
      forEachIndexed(args, (arg, i) ->
          w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append("]");
    }

    @Override public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public ListPat copy(TypeSystem typeSystem, List<Pat> args) {
      return args.equals(this.args) ? this
          : core.listPat(typeSystem, args);
    }
  }

  /** Record pattern. */
  public static class RecordPat extends Pat {
    public final List<Pat> args;

    RecordPat(RecordType type, ImmutableList<Pat> args) {
      super(Op.RECORD_PAT, type);
      this.args = requireNonNull(args);
      checkArgument(args.size() == type.argNameTypes.size());
    }

    @Override public RecordType type() {
      return (RecordType) type;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("{");
      forEachIndexed(type().argNameTypes.keySet(), args, (i, name, arg) ->
          w.append(i > 0 ? ", " : "").append(name)
              .append(" = ").append(arg, 0, 0));
      return w.append("}");
    }

    @Override public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Pat copy(TypeSystem typeSystem, Set<String> argNames,
        List<Pat> args) {
      return args.equals(this.args) ? this
          : core.recordPat(typeSystem, argNames, args);
    }
  }

  /** Base class of core expressions. */
  public abstract static class Exp extends BaseNode {
    public final Type type;

    Exp(Pos pos, Op op, Type type) {
      super(pos, op);
      this.type = requireNonNull(type);
    }

    public void forEachArg(ObjIntConsumer<Exp> action) {
      // no args
    }

    /** Returns the {@code i}<sup>th</sup> argument. */
    public Exp arg(int i) {
      throw new UnsupportedOperationException();
    }

    /** Returns the type. */
    public Type type() {
      return type;
    }

    @Override public abstract Exp accept(Shuttle shuttle);

    /** Returns whether this expression is a constant.
     *
     * <p>Examples include literals {@code 1}, {@code true},
     * constructors applied to constants,
     * records and tuples whose arguments are constant.
     */
    public boolean isConstant() {
      return false;
    }

    /** Returns whether this expression is a call to the given built-in. */
    public boolean isCallTo(BuiltIn builtIn) {
      return false;
    }
  }

  /** Reference to a variable.
   *
   * <p>While {@link Ast.Id} is widely used, and means an occurrence of a name
   * in the parse tree, {@code Id} is much narrower: it means a reference to a
   * value. What would be an {@code Id} in Ast is often a {@link String} in
   * Core; for example, compare {@link Ast.Con0Pat#tyCon}
   * with {@link Con0Pat#tyCon}. */
  public static class Id extends Exp implements Comparable<Id> {
    public final NamedPat idPat;

    /** Creates an Id. */
    Id(NamedPat idPat) {
      super(Pos.ZERO, Op.ID, idPat.type);
      this.idPat = requireNonNull(idPat);
    }

    @Override public int compareTo(Id o) {
      return idPat.compareTo(o.idPat);
    }

    @Override public int hashCode() {
      return idPat.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof Id
          && this.idPat.equals(((Id) o).idPat);
    }

    @Override public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(idPat.name, idPat.i);
    }
  }

  /** Record selector function. */
  public static class RecordSelector extends Exp {
    /** The ordinal of the field in the record or tuple that is to be
     * accessed. */
    public final int slot;

    /** Creates a record selector. */
    RecordSelector(FnType fnType, int slot) {
      super(Pos.ZERO, Op.RECORD_SELECTOR, fnType);
      this.slot = slot;
    }

    @Override public int hashCode() {
      return slot + 2237;
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof RecordSelector
          && this.slot == ((RecordSelector) o).slot
          && this.type.equals(((RecordSelector) o).type);
    }

    public String fieldName() {
      final RecordLikeType recordType = (RecordLikeType) type().paramType;
      return Iterables.get(recordType.argNameTypes().keySet(), slot);
    }

    @Override public FnType type() {
      return (FnType) type;
    }

    @Override public RecordSelector accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("#").append(fieldName());
    }
  }

  /** Code of a literal (constant). */
  @SuppressWarnings("rawtypes")
  public static class Literal extends Exp {
    public final Comparable value;

    /** Creates a Literal. */
    Literal(Op op, Type type, Comparable value) {
      super(Pos.ZERO, op, type);
      this.value = requireNonNull(value);
    }

    static Comparable wrap(Exp exp, Object value) {
      return new Wrapper(exp, value);
    }

    public Object unwrap() {
      return ((Wrapper) value).o;
    }

    @Override public int hashCode() {
      return value.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof Literal
          && value.equals(((Literal) o).value);
    }

    @Override public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      switch (op) {
      case VALUE_LITERAL:
        // Generate the original expression from which this value was derived.
        return ((Wrapper) value).exp.unparse(w, left, right);
      case INTERNAL_LITERAL:
        // Print the value as if it were a string.
        return w.appendLiteral(((Wrapper) value).o.toString());
      }
      return w.appendLiteral(value);
    }

    @Override public boolean isConstant() {
      return true;
    }
  }

  /** Base class for declarations. */
  public abstract static class Decl extends BaseNode {
    Decl(Pos pos, Op op) {
      super(pos, op);
    }

    @Override public abstract Decl accept(Shuttle shuttle);
  }

  /** Datatype declaration. */
  public static class DatatypeDecl extends Decl {
    public final List<DataType> dataTypes;

    DatatypeDecl(ImmutableList<DataType> dataTypes) {
      super(Pos.ZERO, Op.DATATYPE_DECL);
      this.dataTypes = requireNonNull(dataTypes);
      checkArgument(!this.dataTypes.isEmpty());
    }

    @Override public int hashCode() {
      return Objects.hash(dataTypes);
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof DatatypeDecl
          && dataTypes.equals(((DatatypeDecl) o).dataTypes);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      forEachIndexed(dataTypes, (dataType, i) ->
          w.append(i == 0 ? "datatype " : " and ").append(dataType.toString()));
      return w;
    }

    @Override public DatatypeDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }
  }

  /** Abstract (recursive or non-recursive) value declaration. */
  public abstract static class ValDecl extends Decl {
    ValDecl(Pos pos, Op op) {
      super(pos, op);
    }

    @Override public abstract ValDecl accept(Shuttle shuttle);

    public abstract void forEachBinding(BindingConsumer consumer);
  }

  /** Consumer of bindings. */
  @FunctionalInterface
  public interface BindingConsumer {
    void accept(NamedPat namedPat, Exp exp, Pos pos);
  }

  /** Non-recursive value declaration.
   *
   * @see RecValDecl#list */
  public static class NonRecValDecl extends ValDecl {
    public final NamedPat pat;
    public final Exp exp;

    NonRecValDecl(NamedPat pat, Exp exp, Pos pos) {
      super(pos, Op.VAL_DECL);
      this.pat = pat;
      this.exp = exp;
    }

    @Override public int hashCode() {
      return Objects.hash(pat, exp);
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof NonRecValDecl
          && pat.equals(((NonRecValDecl) o).pat)
          && exp.equals(((NonRecValDecl) o).exp);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("val ")
          .append(pat, 0, 0).append(" = ").append(exp, 0, right);
    }

    @Override public NonRecValDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public NonRecValDecl copy(NamedPat pat, Exp exp) {
      return pat == this.pat && exp == this.exp ? this
          : core.nonRecValDecl(pos, pat, exp);
    }

    @Override public void forEachBinding(BindingConsumer consumer) {
      consumer.accept(pat, exp, pos);
    }
  }

  /** Recursive value declaration. */
  public static class RecValDecl extends ValDecl {
    public final ImmutableList<NonRecValDecl> list;

    RecValDecl(ImmutableList<NonRecValDecl> list) {
      super(Pos.ZERO, Op.REC_VAL_DECL);
      this.list = requireNonNull(list);
    }

    @Override public int hashCode() {
      return list.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof RecValDecl
          && list.equals(((RecValDecl) o).list);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("val rec ");
      forEachIndexed(list, (decl, i) ->
          w.append(i == 0 ? "" : " and ").append(decl.pat, 0, 0)
              .append(" = ").append(decl.exp, 0, right));
      return w;
    }

    @Override public RecValDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override public void forEachBinding(BindingConsumer consumer) {
      list.forEach(b -> b.forEachBinding(consumer));
    }

    public RecValDecl copy(List<NonRecValDecl> list) {
      return list.equals(this.list) ? this
          : core.recValDecl(list);
    }
  }

  /** Tuple expression. Also implements record expression. */
  // TODO: remove, replace with a call to the constructor of the n-tuple type?
  public static class Tuple extends Exp {
    public final List<Exp> args;

    Tuple(RecordLikeType type, ImmutableList<Exp> args) {
      super(Pos.ZERO, Op.TUPLE, type);
      this.args = ImmutableList.copyOf(args);
    }

    @Override public boolean equals(Object o) {
      return this == o
          || o instanceof Tuple
          && args.equals(((Tuple) o).args)
          && type.equals(((Tuple) o).type);
    }

    @Override public int hashCode() {
      return Objects.hash(args, type);
    }

    @Override public RecordLikeType type() {
      return (RecordLikeType) type;
    }

    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
      forEachIndexed(args, action);
    }

    @Override public Exp arg(int i) {
      return args.get(i);
    }

    @Override public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      if (type instanceof RecordType) {
        w.append("{");
        forEachIndexed(type().argNameTypes().keySet(), args,
            (i, name, exp) ->
                w.append(i > 0 ? ", " : "").append(name).append(" = ")
                    .append(exp, 0, 0));
        return w.append("}");
      } else {
        w.append("(");
        forEachArg((arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
        return w.append(")");
      }
    }

    public Tuple copy(TypeSystem typeSystem, List<Exp> args) {
      return args.equals(this.args) ? this
          : core.tuple(typeSystem, type(), args);
    }

    @Override public boolean isConstant() {
      return args.stream().allMatch(Exp::isConstant);
    }
  }

  /** "Let" expression. */
  public static class Let extends Exp {
    public final ValDecl decl;
    public final Exp exp;

    Let(ValDecl decl, Exp exp) {
      super(Pos.ZERO, Op.LET, exp.type);
      this.decl = requireNonNull(decl);
      this.exp = requireNonNull(exp);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("let ").append(decl, 0, 0)
          .append(" in ").append(exp, 0, 0)
          .append(" end");
    }

    @Override public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Exp copy(ValDecl decl, Exp exp) {
      return decl == this.decl && exp == this.exp ? this
          : core.let(decl, exp);
    }
  }

  /** "Local" expression. */
  public static class Local extends Exp {
    public final DataType dataType;
    public final Exp exp;

    Local(DataType dataType, Exp exp) {
      super(Pos.ZERO, Op.LOCAL, exp.type);
      this.dataType = requireNonNull(dataType);
      this.exp = requireNonNull(exp);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("local datatype ").append(dataType.toString())
          .append(" in ").append(exp, 0, 0)
          .append(" end");
    }

    @Override public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Exp copy(DataType dataType, Exp exp) {
      return dataType == this.dataType && exp == this.exp ? this
          : core.local(dataType, exp);
    }
  }

  /** Match.
   *
   * <p>In AST, there are several places that can deconstruct values via
   * patterns: {@link Ast.FunDecl fun}, {@link Ast.Fn fn}, {@link Ast.Let let},
   * {@link Ast.Case case}. But in Core, there is only {@code Match}, and
   * {@code Match} only occurs within {@link Ast.Case case}. This makes the Core
   * language a little more verbose than AST but a lot more uniform. */
  public static class Match extends BaseNode {
    public final Pat pat;
    public final Exp exp;

    Match(Pos pos, Pat pat, Exp exp) {
      super(pos, Op.MATCH);
      this.pat = pat;
      this.exp = exp;
    }

    @Override public Match accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(pat, 0, 0).append(" => ").append(exp, 0, right);
    }

    public Match copy(Pat pat, Exp exp) {
      return pat == this.pat && exp == this.exp ? this
          : core.match(pos, pat, exp);
    }
  }

  /** Lambda expression. */
  public static class Fn extends Exp {
    public final IdPat idPat;
    public final Exp exp;

    Fn(FnType type, IdPat idPat, Exp exp) {
      super(Pos.ZERO, Op.FN, type);
      this.idPat = requireNonNull(idPat);
      this.exp = requireNonNull(exp);
    }

    @Override public FnType type() {
      return (FnType) type;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("fn ")
          .append(idPat, 0, 0).append(" => ").append(exp, 0, right);
    }

    @Override public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Fn copy(IdPat idPat, Exp exp) {
      return idPat == this.idPat && exp == this.exp ? this
          : core.fn(type(), idPat, exp);
    }
  }

  /** Case expression.
   *
   * <p>Also implements {@link Ast.If}. */
  public static class Case extends Exp {
    public final Exp exp;
    public final List<Match> matchList;

    Case(Pos pos, Type type, Exp exp, ImmutableList<Match> matchList) {
      super(pos, Op.CASE, type);
      this.exp = exp;
      this.matchList = matchList;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("case ").append(exp, 0, 0).append(" of ")
          .appendAll(matchList, left, Op.BAR, right);
    }

    @Override public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Case copy(Exp exp, List<Match> matchList) {
      return exp == this.exp && matchList.equals(this.matchList) ? this
          : core.caseOf(pos, type, exp, matchList);
    }
  }

  /** From expression. */
  public static class From extends Exp {
    public final ImmutableList<FromStep> steps;

    From(ListType type, ImmutableList<FromStep> steps) {
      super(Pos.ZERO, Op.FROM, type);
      this.steps = requireNonNull(steps);
    }

    @Override public boolean equals(Object o) {
      return this == o
          || o instanceof From
          && steps.equals(((From) o).steps);
    }

    @Override public int hashCode() {
      return steps.hashCode();
    }

    @Override public ListType type() {
      return (ListType) type;
    }

    @Override public Exp accept(Shuttle shuttle) {
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
        forEachIndexed(steps, (step, i) -> step.unparse(w, this, i, 0, 0));
        return w;
      }
    }

    public Exp copy(TypeSystem typeSystem, Environment env,
        List<FromStep> steps) {
      return steps.equals(this.steps)
          ? this
          : core.fromBuilder(typeSystem, env).addAll(steps).build();
    }
  }

  /** A step in a {@code from} expression - {@code where}, {@code group}
   * or {@code order}. */
  public abstract static class FromStep extends BaseNode {
    public final ImmutableList<Binding> bindings;

    FromStep(Op op, ImmutableList<Binding> bindings) {
      super(Pos.ZERO, op);
      this.bindings = bindings;
    }

    @Override final AstWriter unparse(AstWriter w, int left, int right) {
      return unparse(w, null, -1, left, right);
    }

    protected abstract AstWriter unparse(AstWriter w, From from, int ordinal,
        int left, int right);

    @Override public abstract FromStep accept(Shuttle shuttle);
  }

  /** A {@code join} or {@code v in listExpr} or {@code v = expr} clause in a
   *  {@code from} expression. */
  public static class Scan extends FromStep {
    public final Pat pat;
    public final Exp exp;
    public final Exp condition;

    Scan(Op op, ImmutableList<Binding> bindings, Pat pat, Exp exp,
        Exp condition) {
      super(op, bindings);
      switch (op) {
      case INNER_JOIN:
      case SUCH_THAT:
        break;
      default:
        // SCAN and CROSS_JOIN are valid in ast, not core.
        throw new AssertionError("not a join type " + op);
      }
      this.pat = requireNonNull(pat, "pat");
      this.exp = requireNonNull(exp, "exp");
      this.condition = requireNonNull(condition, "condition");
    }

    @Override public Scan accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override protected AstWriter unparse(AstWriter w, From from, int ordinal,
        int left, int right) {
      final String prefix = ordinal == 0 ? " "
          : op == Op.SUCH_THAT ? " join "
          : op.padded;
      final String infix = op == Op.SUCH_THAT ? " suchthat "
          : " in ";
      w.append(prefix)
          // for these purposes 'in' and 'suchthat' have same precedence as '='
          .append(pat, 0, Op.EQ.left)
          .append(infix)
          .append(exp, Op.EQ.right, 0);
      if (!isLiteralTrue()) {
        w.append("on").append(condition, 0, 0);
      }
      return w;
    }

    private boolean isLiteralTrue() {
      return condition instanceof Literal
          && ((Literal) condition).value.equals(true);
    }

    public Scan copy(List<Binding> bindings, Pat pat, Exp exp, Exp condition) {
      return pat == this.pat
          && exp == this.exp
          && condition == this.condition
          && bindings.equals(this.bindings)
          ? this
          : core.scan(op, bindings, pat, exp, condition);
    }
  }

  /** A {@code where} clause in a {@code from} expression. */
  public static class Where extends FromStep {
    public final Exp exp;

    Where(ImmutableList<Binding> bindings, Exp exp) {
      super(Op.WHERE, bindings);
      this.exp = requireNonNull(exp, "exp");
    }

    @Override public Where accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override protected AstWriter unparse(AstWriter w, From from, int ordinal,
        int left, int right) {
      return w.append(" where ").append(exp, 0, 0);
    }

    public Where copy(Exp exp, List<Binding> bindings) {
      return exp == this.exp
          && bindings.equals(this.bindings)
          ? this
          : core.where(bindings, exp);
    }
  }

  /** An {@code order} clause in a {@code from} expression. */
  public static class Order extends FromStep {
    public final ImmutableList<OrderItem> orderItems;

    Order(ImmutableList<Binding> bindings,
        ImmutableList<OrderItem> orderItems) {
      super(Op.ORDER, bindings);
      this.orderItems = requireNonNull(orderItems);
    }

    @Override public Order accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override protected AstWriter unparse(AstWriter w, From from, int ordinal,
        int left, int right) {
      return w.append(" order ").appendAll(orderItems, ", ");
    }

    public Order copy(List<Binding> bindings,
        List<OrderItem> orderItems) {
      return bindings.equals(this.bindings)
          && orderItems.equals(this.orderItems)
          ? this
          : core.order(bindings, orderItems);
    }
  }

  /** An item in an {@code order} clause. */
  public static class OrderItem extends BaseNode {
    public final Exp exp;
    public final Ast.Direction direction;

    OrderItem(Exp exp, Ast.Direction direction) {
      super(Pos.ZERO, Op.ORDER_ITEM);
      this.exp = requireNonNull(exp);
      this.direction = requireNonNull(direction);
    }

    @Override public AstNode accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(exp, 0, 0)
          .append(direction == Ast.Direction.DESC ? " desc" : "");
    }

    public OrderItem copy(Exp exp, Ast.Direction direction) {
      return exp == this.exp && direction == this.direction ? this
          : core.orderItem(exp, direction);
    }
  }

  /** A {@code group} clause in a {@code from} expression. */
  public static class Group extends FromStep {
    public final SortedMap<Core.IdPat, Exp> groupExps;
    public final SortedMap<Core.IdPat, Aggregate> aggregates;

    Group(ImmutableList<Binding> bindings,
        ImmutableSortedMap<Core.IdPat, Exp> groupExps,
        ImmutableSortedMap<Core.IdPat, Aggregate> aggregates) {
      super(Op.GROUP, bindings);
      this.groupExps = groupExps;
      this.aggregates = aggregates;
    }

    @Override public Group accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override protected AstWriter unparse(AstWriter w, From from, int ordinal,
        int left, int right) {
      w.append(" group");
      Pair.forEachIndexed(groupExps, (i, id, exp) ->
          w.append(i == 0 ? " " : ", ")
              .append(id, 0, 0).append(" = ").append(exp, 0, 0));
      Pair.forEachIndexed(aggregates, (i, name, aggregate) ->
          w.append(i == 0 ? " compute " : ", ")
              .append(name, 0, 0).append(" = ").append(aggregate, 0, 0));
      return w;
    }

    public Group copy(SortedMap<Core.IdPat, Exp> groupExps,
        SortedMap<Core.IdPat, Aggregate> aggregates) {
      return groupExps.equals(this.groupExps)
          && aggregates.equals(this.aggregates)
          ? this
          : core.group(groupExps, aggregates);
    }
  }

  /** Step that computes an expression. */
  public static class Yield extends FromStep {
    public final Exp exp;

    Yield(ImmutableList<Binding> bindings, Exp exp) {
      super(Op.YIELD, bindings);
      this.exp = exp;
    }

    @Override protected AstWriter unparse(AstWriter w, From from, int ordinal,
        int left, int right) {
      return w.append(" yield ").append(exp, 0, 0);
    }

    @Override public Yield accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Yield copy(List<Binding> bindings, Exp exp) {
      return bindings.equals(this.bindings)
          && exp == this.exp
          ? this
          : core.yield_(bindings, exp);
    }
  }

  /** Application of a function to its argument. */
  public static class Apply extends Exp {
    public final Exp fn;
    public final Exp arg;

    Apply(Pos pos, Type type, Exp fn, Exp arg) {
      super(pos, Op.APPLY, type);
      this.fn = fn;
      this.arg = arg;
    }

    /** Returns the argument list (assuming that the arguments are a tuple
     * or record).
     *
     * @throws ClassCastException if argument is not a tuple */
    public List<Exp> args() {
      return ((Tuple) arg).args;
    }

    @Override public Exp arg(int i) {
      // Throws if the argument is not a tuple.
      return arg.arg(i);
    }

    @Override public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      switch (fn.op) {
      case FN_LITERAL:
        final BuiltIn builtIn = (BuiltIn) ((Literal) fn).value;

        // Because the Core language is narrower than AST, a few AST expression
        // types do not exist in Core and are translated to function
        // applications. Here we convert them back to original syntax.
        switch (builtIn) {
        case Z_LIST:
          w.append("[");
          arg.forEachArg((arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
          return w.append("]");
        }

        // Convert built-ins to infix operators.
        final Op op = Resolver.BUILT_IN_OP_MAP.get(builtIn);
        if (op != null) {
          return w.infix(left, args().get(0), op, args().get(1), right);
        }
      }
      return w.infix(left, fn, op, arg, right);
    }

    public Apply copy(Exp fn, Exp arg) {
      return fn == this.fn && arg == this.arg ? this
          : core.apply(pos, type, fn, arg);
    }

    @Override public boolean isConstant() {
      // A list of constants is constant
      return isCallTo(BuiltIn.Z_LIST)
          && args().stream().allMatch(Exp::isConstant);
    }

    @Override public boolean isCallTo(BuiltIn builtIn) {
      return fn.op == Op.FN_LITERAL && ((Literal) fn).value == builtIn;
    }
  }

  /** Call to an aggregate function in a {@code compute} clause.
   *
   * <p>For example, in {@code compute sumId = sum of #id e},
   * {@code aggregate} is "sum", {@code argument} is "#id e". */
  public static class Aggregate extends BaseNode {
    public final Type type;
    public final Exp aggregate;
    public final @Nullable Exp argument;

    Aggregate(Type type, Exp aggregate, @Nullable Exp argument) {
      super(Pos.ZERO, Op.AGGREGATE);
      this.type = type;
      this.aggregate = requireNonNull(aggregate);
      this.argument = argument;
    }

    @Override public Aggregate accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append(aggregate, 0, 0);
      if (argument != null) {
        w.append(" of ")
            .append(argument, 0, 0);
      }
      return w;
    }

    public Aggregate copy(Type type, Exp aggregate, @Nullable Exp argument) {
      return aggregate == this.aggregate && argument == this.argument
          ? this
          : core.aggregate(type, aggregate, argument);
    }
  }

  /** Wraps a value as a Comparable, and stores the global expression from which
   * the value was derived. That global expression will be used if the value is
   * converted by to Morel code. */
  static class Wrapper implements Comparable<Wrapper> {
    private final Exp exp;
    private final Object o;

    private Wrapper(Exp exp, Object o) {
      this.exp = exp;
      this.o = o;
      assert isValidValue(exp, o) : o;
    }

    private static boolean isValidValue(Exp exp, Object o) {
      if (o instanceof Code) {
        return false;
      }
      if (o instanceof Closure) {
        return false;
      }
      if (o instanceof Id) {
        final String name = ((Id) exp).idPat.name;
        return !("true".equals(name) || "false".equals(name));
      }
      return true;
    }

    @Override public int compareTo(Wrapper o) {
      return Integer.compare(this.o.hashCode(), o.o.hashCode());
    }

    @Override public String toString() {
      return o.toString();
    }

    @Override public int hashCode() {
      return o.hashCode();
    }

    @Override public boolean equals(Object obj) {
      return this == obj
          || obj instanceof Wrapper
          && this.o.equals(((Wrapper) obj).o);
    }

    /** Returns the value. */
    <T> T unwrap(Class<T> valueClass) {
      return valueClass.cast(o);
    }
  }
}

// End Core.java
