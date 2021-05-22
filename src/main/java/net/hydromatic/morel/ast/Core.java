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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;

import net.hydromatic.morel.compile.BuiltIn;
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
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkArgument;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.ast.Pos.ZERO;

import static java.util.Objects.requireNonNull;

/** Core expressions.
 *
 * <p>Many expressions are sub-classes of similarly named expressions in
 * {@link Ast}. This class functions as a namespace, so that we can keep the
 * class names short.
 */
// TODO: remove 'parse tree for...' from all the comments below
public class Core {
  private Core() {}

  /** Abstract base class of Core nodes. */
  abstract static class BaseNode extends AstNode {
    BaseNode(Op op) {
      super(ZERO, op);
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
      super(op);
      this.type = requireNonNull(type);
    }

    /** Returns the type. */
    public Type type() {
      return type;
    }

    @Override public abstract Pat accept(Shuttle shuttle);
  }

  /** Named pattern.
   *
   * <p>Implements {@link Comparable} so that names are sorted correctly
   * for record fields (see {@link RecordType#ORDERING}).
   *
   * @see Ast.Id */
  public static class IdPat extends Pat implements Comparable<IdPat> {
    public final String name;
    public final int i;

    IdPat(Type type, String name, int i) {
      super(Op.ID_PAT, type);
      this.name = name;
      this.i = i;
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

    /** {@inheritDoc}
     *
     * <p>Collate first on name, then on ordinal. */
    @Override public int compareTo(IdPat o) {
      final int c = RecordType.compareNames(name, o.name);
      if (c != 0) {
        return c;
      }
      return Integer.compare(i, o.i);
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

    public IdPat withType(Type type) {
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
      Ord.forEach(args, (arg, i) ->
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
      Ord.forEach(args, (arg, i) ->
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
      Pair.forEachIndexed(type().argNameTypes.keySet(), args, (i, name, arg) ->
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

    Exp(Op op, Type type) {
      super(op);
      this.type = requireNonNull(type);
    }

    public void forEachArg(ObjIntConsumer<Exp> action) {
      // no args
    }

    /** Returns the type. */
    public Type type() {
      return type;
    }

    @Override public abstract Exp accept(Shuttle shuttle);
  }

  /** Reference to a variable.
   *
   * <p>While {@link Ast.Id} is widely used, and means an occurrence of a name
   * in the parse tree, {@code Id} is much narrower: it means a reference to a
   * value. What would be an {@code Id} in Ast is often a {@link String} in
   * Core; for example, compare {@link Ast.Con0Pat#tyCon}
   * with {@link Con0Pat#tyCon}. */
  public static class Id extends Exp {
    public final IdPat idPat;

    /** Creates an Id. */
    Id(IdPat idPat) {
      super(Op.ID, idPat.type);
      this.idPat = requireNonNull(idPat);
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
      super(Op.RECORD_SELECTOR, fnType);
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
      super(op, type);
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
      if (value instanceof Wrapper) {
        // Generate the original expression from which this value was derived.
        return ((Wrapper) value).exp.unparse(w, left, right);
      }
      return w.appendLiteral(value);
    }
  }

  /** Base class for declarations. */
  public abstract static class Decl extends BaseNode {
    Decl(Op op) {
      super(op);
    }

    @Override public abstract Decl accept(Shuttle shuttle);

    @Override public abstract void accept(Visitor visitor);
  }

  /** Datatype declaration. */
  public static class DatatypeDecl extends Decl {
    public final List<DataType> dataTypes;

    DatatypeDecl(ImmutableList<DataType> dataTypes) {
      super(Op.DATATYPE_DECL);
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
      Ord.forEach(dataTypes, (dataType, i) ->
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

  /** Value declaration. */
  public static class ValDecl extends Decl {
    public final boolean rec;
    public final IdPat pat;
    public final Exp exp;

    ValDecl(boolean rec, IdPat pat, Exp exp) {
      super(Op.VAL_DECL);
      this.rec = rec;
      this.pat = pat;
      this.exp = exp;
    }

    @Override public int hashCode() {
      return Objects.hash(rec, pat, exp);
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof ValDecl
          && rec == ((ValDecl) o).rec
          && pat.equals(((ValDecl) o).pat)
          && exp.equals(((ValDecl) o).exp);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(rec ? "val rec " : "val ")
          .append(pat, 0, 0).append(" = ").append(exp, 0, right);
    }

    @Override public ValDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public ValDecl copy(boolean rec, IdPat pat, Exp exp) {
      return rec == this.rec && pat == this.pat && exp == this.exp ? this
          : core.valDecl(rec, pat, exp);
    }
  }

  /** Tuple expression. Also implements record expression. */
  // TODO: remove, replace with a call to the constructor of the n-tuple type?
  public static class Tuple extends Exp {
    public final List<Exp> args;

    Tuple(RecordLikeType type, ImmutableList<Exp> args) {
      super(Op.TUPLE, type);
      this.args = ImmutableList.copyOf(args);
    }

    @Override public RecordLikeType type() {
      return (RecordLikeType) type;
    }

    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
      Ord.forEach(args, action);
    }

    @Override public Exp accept(Shuttle shuttle) {
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

    public Tuple copy(TypeSystem typeSystem, List<Exp> args) {
      return args.equals(this.args) ? this
          : core.tuple(typeSystem, type(), args);
    }
  }

  /** "Let" expression. */
  public static class Let extends Exp {
    public final ValDecl decl;
    public final Exp exp;

    Let(ValDecl decl, Exp exp) {
      super(Op.LET, exp.type);
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
      super(Op.LOCAL, exp.type);
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

    Match(Pat pat, Exp exp) {
      super(Op.MATCH);
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
          : core.match(pat, exp);
    }
  }

  /** Lambda expression. */
  public static class Fn extends Exp {
    public final IdPat idPat;
    public final Exp exp;

    Fn(FnType type, IdPat idPat, Exp exp) {
      super(Op.FN, type);
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

    Case(Type type, Exp exp, ImmutableList<Match> matchList) {
      super(Op.CASE, type);
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
          : core.caseOf(type, exp, matchList);
    }
  }

  /** From expression. */
  public static class From extends Exp {
    public final Map<Pat, Exp> sources;
    public final ImmutableList<FromStep> steps;
    public final Exp yieldExp;

    From(ListType type, ImmutableMap<Pat, Exp> sources,
        ImmutableList<FromStep> steps, Exp yieldExp) {
      super(Op.FROM, type);
      this.sources = requireNonNull(sources);
      this.steps = requireNonNull(steps);
      this.yieldExp = requireNonNull(yieldExp);
      checkArgument(type.elementType.equals(yieldExp.type));
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

    public Exp copy(TypeSystem typeSystem, Map<Pat, Exp> sources,
        List<FromStep> steps, Exp yieldExp) {
      return sources.equals(this.sources)
          && steps.equals(this.steps)
          && yieldExp.equals(this.yieldExp)
          ? this
          : core.from(typeSystem.listType(yieldExp.type), sources, steps,
              yieldExp);
    }
  }

  /** A step in a {@code from} expression - {@code where}, {@code group}
   * or {@code order}. */
  public abstract static class FromStep extends BaseNode {
    FromStep(Op op) {
      super(op);
    }

    /** Returns the names of the fields produced by this step, given the names
     * of the fields that are input to this step.
     *
     * <p>By default, a step outputs the same fields as it inputs.
     */
    public void deriveOutBindings(Iterable<Binding> inBindings,
        Function<IdPat, Binding> binder, Consumer<Binding> outBindings) {
      inBindings.forEach(outBindings);
    }

    @Override public abstract FromStep accept(Shuttle shuttle);
  }

  /** A {@code where} clause in a {@code from} expression. */
  public static class Where extends FromStep {
    public final Exp exp;

    Where(Exp exp) {
      super(Op.WHERE);
      this.exp = exp;
    }

    @Override public Where accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("where ").append(exp, 0, 0);
    }

    public Where copy(Exp exp) {
      return exp == this.exp ? this
          : core.where(exp);
    }
  }

  /** An {@code order} clause in a {@code from} expression. */
  public static class Order extends FromStep {
    public final ImmutableList<OrderItem> orderItems;

    Order(ImmutableList<OrderItem> orderItems) {
      super(Op.ORDER);
      this.orderItems = requireNonNull(orderItems);
    }

    @Override public Order accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("order ").appendAll(orderItems, ", ");
    }

    public Order copy(List<OrderItem> orderItems) {
      return orderItems.equals(this.orderItems) ? this
          : core.order(orderItems);
    }
  }

  /** An item in an {@code order} clause. */
  public static class OrderItem extends BaseNode {
    public final Exp exp;
    public final Ast.Direction direction;

    OrderItem(Exp exp, Ast.Direction direction) {
      super(Op.ORDER_ITEM);
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

    Group(ImmutableSortedMap<Core.IdPat, Exp> groupExps,
        ImmutableSortedMap<Core.IdPat, Aggregate> aggregates) {
      super(Op.GROUP);
      this.groupExps = groupExps;
      this.aggregates = aggregates;
    }

    @Override public Group accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      Pair.forEachIndexed(groupExps, (i, id, exp) ->
          w.append(i == 0 ? "group " : ", ")
              .append(id, 0, 0).append(" = ").append(exp, 0, 0));
      Pair.forEachIndexed(aggregates, (i, name, aggregate) ->
          w.append(i == 0 ? " compute " : ", ")
              .append(name, 0, 0).append(" = ").append(aggregate, 0, 0));
      return w;
    }

    @Override public void deriveOutBindings(Iterable<Binding> inBindings,
        Function<Core.IdPat, Binding> binder, Consumer<Binding> outBindings) {
      groupExps.keySet().forEach(id -> outBindings.accept(binder.apply(id)));
      aggregates.keySet().forEach(id -> outBindings.accept(binder.apply(id)));
    }

    public Group copy(SortedMap<Core.IdPat, Exp> groupExps,
        SortedMap<Core.IdPat, Aggregate> aggregates) {
      return groupExps.equals(this.groupExps)
          && aggregates.equals(this.aggregates)
          ? this
          : core.group(groupExps, aggregates);
    }
  }

  /** Application of a function to its argument. */
  public static class Apply extends Exp {
    public final Exp fn;
    public final Exp arg;

    Apply(Type type, Exp fn, Exp arg) {
      super(Op.APPLY, type);
      this.fn = fn;
      this.arg = arg;
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
          final List<Exp> args = ((Tuple) arg).args;
          return w.infix(left, args.get(0), op, args.get(1), right);
        }
      }
      return w.infix(left, fn, op, arg, right);
    }

    public Apply copy(Exp fn, Exp arg) {
      return fn == this.fn && arg == this.arg ? this
          : core.apply(type, fn, arg);
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
      super(Op.AGGREGATE);
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
  }
}

// End Core.java
