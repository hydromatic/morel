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

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.util.Objects.hash;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.type.TypeSystem.canAssign;
import static net.hydromatic.morel.util.Comparators.compareNames;
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Pair.forEachIndexed;
import static net.hydromatic.morel.util.PairList.zip;
import static net.hydromatic.morel.util.Static.allMatch;
import static net.hydromatic.morel.util.Static.transform;
import static org.apache.calcite.util.Util.first;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.primitives.UnsignedLong;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.ObjIntConsumer;
import java.util.regex.Pattern;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Resolver;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.Applicable3;
import net.hydromatic.morel.eval.Applicable4;
import net.hydromatic.morel.eval.Closure;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Describer;
import net.hydromatic.morel.type.AliasType;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RangeExtent;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/**
 * Core expressions.
 *
 * <p>Many expressions are subclasses of similarly named expressions in {@link
 * Ast}. This class functions as a namespace, so that we can keep the class
 * names short.
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

    @Override
    public AstNode accept(Shuttle shuttle) {
      throw new UnsupportedOperationException(
          getClass() + " cannot accept " + shuttle.getClass());
    }

    @Override
    public void accept(Visitor visitor) {
      throw new UnsupportedOperationException(
          getClass() + " cannot accept " + visitor.getClass());
    }
  }

  /**
   * Base class for a pattern.
   *
   * <p>For example, "x" in "val x = 5" is a {@link IdPat}; the "(x, y) in "val
   * (x, y) = makePair 1 2" is a {@link TuplePat}.
   */
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

    @Override
    public abstract Pat accept(Shuttle shuttle);

    /**
     * Converts this pattern to a string.
     *
     * <p>The result is similar to that of {@link #toString()}, except that
     * identifiers are permuted. For example, if a pattern includes "c.3" and
     * "c.2" but not "c.0" or "c.1", then "c.3" will be printed as "c" and "c.2"
     * will be printed as "c.1".
     *
     * <p>This behavior makes plans more concise if their local variables happen
     * to have the same name as other variables that occur in functions in the
     * session history.
     */
    public String describe(Describer describer) {
      final AstWriter w =
          new AstWriter() {
            @Override
            public AstWriter id(String name, int i) {
              final String generated = generated(name, i);
              if (generated != null) {
                return super.id(generated, 0);
              }
              int j = describer.register(name, i);
              return super.id(name, j);
            }

            @Override
            public AstWriter idQuoted(String name, int i) {
              final String generated = generated(name, i);
              if (generated != null) {
                return super.id(generated, 0);
              }
              int j = describer.register(name, i);
              return super.idQuoted(name, j);
            }

            /**
             * Returns the name under which a generated pattern prints, or null
             * if the pattern is not generated.
             *
             * <p>A node's pattern that no binder names ({@code $0}, {@code $1},
             * {@code $ordinal}) prints as {@code v$}-ordinal; its ordinal comes
             * from the counter that numbers generated binders, so it names no
             * binder. (An id named after a binder, {@code $e}, prints as {@code
             * e}, and does not reach here.) A generated binder such as {@code
             * t$12} keeps its prefix. Either is renumbered by first occurrence.
             */
            private @Nullable String generated(String name, int i) {
              if (name.startsWith("$")) {
                return describer.rename("v$" + i);
              }
              if (i == 0 && NamedPat.isGenerated(name)) {
                return describer.rename(name);
              }
              return null;
            }
          };
      return unparse(w);
    }

    /** Returns all named patterns in this pattern. */
    public List<NamedPat> expand() {
      final ImmutableList.Builder<NamedPat> list = ImmutableList.builder();
      accept(
          new Visitor() {
            @Override
            protected void visit(Core.IdPat idPat) {
              list.add(idPat);
            }

            @Override
            protected void visit(Core.AsPat asPat) {
              list.add(asPat);
              super.visit(asPat);
            }
          });
      return list.build();
    }
  }

  /**
   * Base class for named patterns ({@link IdPat} and {@link AsPat}).
   *
   * <p>Implements {@link Comparable} so that names are sorted correctly for
   * record fields (see {@link RecordType#ORDERING}).
   *
   * <p>A {@link Core.ValDecl} must be one of these.
   */
  public abstract static class NamedPat extends Pat
      implements Comparable<NamedPat> {
    /**
     * Ordering that compares named patterns by their names, then by their
     * ordinal.
     */
    public static final Ordering<NamedPat> ORDERING =
        Ordering.from(NamedPat::compare);

    /**
     * A binder that the compiler generated, such as {@code v$0}.
     *
     * <p>Package-private because a plan's type legend has to find these inside
     * a type's text; see {@code AstNode.RenumberingAstWriter}.
     */
    static final Pattern GENERATED = Pattern.compile("[a-z]+\\$[0-9]+");

    public final String name;
    public final int i;

    /**
     * Returns whether a name is one the compiler generated, such as {@code
     * v$0}.
     *
     * <p>Such a name is nothing the user wrote, so a diagnostic leaves it out
     * and a printer renumbers it.
     */
    public static boolean isGenerated(String name) {
      return GENERATED.matcher(name).matches();
    }

    /**
     * Returns the name under which a node's id prints, if the id is named after
     * the binder whose collection the node reads, or null.
     *
     * <p>Such an id is called {@code $e} for {@code from e in emps}, and prints
     * as {@code e}. The {@code $} keeps it a node's pattern -- {@code $0},
     * {@code $1} and {@code $ordinal} are the others -- which is what tells a
     * pass that an expression reads a node's input, and keeps the id from
     * shadowing the binder it is named after.
     */
    public static @Nullable String idName(String name) {
      return name.length() > 1
              && name.charAt(0) == '$'
              && !Character.isDigit(name.charAt(1))
              && !name.equals("$ordinal")
          ? name.substring(1)
          : null;
    }

    NamedPat(Op op, Type type, String name, int i) {
      super(op, type);
      this.name = requireNonNull(name, "name");
      this.i = i;
      checkArgument(!name.isEmpty(), "empty name");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Collate first on name, then on ordinal.
     */
    @Override
    public int compareTo(NamedPat o) {
      return compare(this, o);
    }

    /** Helper for {@link #ORDERING}. */
    static int compare(NamedPat o1, NamedPat o2) {
      int c = compareNames(o1.name, o2.name);
      if (c != 0) {
        return c;
      }
      return Integer.compare(o1.i, o2.i);
    }

    /**
     * Returns a copy of this pattern with a given type; or this pattern if the
     * type is the same.
     */
    public abstract NamedPat withType(Type type);

    /**
     * Returns a copy of this pattern with a given name and ordinal 0; or this
     * pattern if the name is the same.
     */
    public abstract NamedPat withName(String name);

    @Override
    public abstract NamedPat accept(Shuttle shuttle);
  }

  /**
   * Named pattern.
   *
   * @see Ast.Id
   */
  public static class IdPat extends NamedPat {
    IdPat(Type type, String name, int i) {
      super(Op.ID_PAT, type, name, i);
    }

    @Override
    public int hashCode() {
      return name.hashCode() + i;
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || obj instanceof IdPat
              && ((IdPat) obj).name.equals(name)
              && ((IdPat) obj).i == i;
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      final @Nullable String idName = idName(name);
      return w.idQuoted(idName != null ? idName : name, i);
    }

    @Override
    public IdPat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    public List<NamedPat> expand() {
      return ImmutableList.of(this);
    }

    @Override
    public IdPat withType(Type type) {
      return type == this.type ? this : new IdPat(type, name, i);
    }

    @Override
    public IdPat withName(String name) {
      return name.equals(this.name) ? this : new IdPat(type, name, 0);
    }
  }

  /**
   * Literal pattern, the pattern analog of the {@link Literal} expression.
   *
   * <p>For example, "0" in "fun fact 0 = 1 | fact n = n * fact (n - 1)".
   */
  @SuppressWarnings("rawtypes")
  public static class LiteralPat extends Pat {
    public final Comparable value;

    LiteralPat(Op op, Type type, Comparable value) {
      super(op, type);
      this.value = requireNonNull(value);
      checkArgument(
          op == Op.BOOL_LITERAL_PAT
              || op == Op.CHAR_LITERAL_PAT
              || op == Op.INT_LITERAL_PAT
              || op == Op.REAL_LITERAL_PAT
              || op == Op.STRING_LITERAL_PAT
              || op == Op.WORD_LITERAL_PAT);
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof LiteralPat
              && this.value.equals(((LiteralPat) o).value);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      if (op == Op.WORD_LITERAL_PAT) {
        return w.appendLiteral(
            UnsignedLong.valueOf(((BigDecimal) value).toBigIntegerExact()));
      }
      return w.appendLiteral(value);
    }

    @Override
    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }
  }

  /**
   * Wildcard pattern.
   *
   * <p>For example, "{@code _}" in "{@code fn foo _ => 42}".
   */
  public static class WildcardPat extends Pat {
    WildcardPat(Type type) {
      super(Op.WILDCARD_PAT, type);
    }

    @Override
    public int hashCode() {
      return "_".hashCode();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof WildcardPat;
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("_");
    }

    @Override
    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
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

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(name).append(" as ").append(pat, 0, 0);
    }

    @Override
    public AsPat withType(Type type) {
      return type == this.type ? this : new AsPat(type, name, i, pat);
    }

    @Override
    public AsPat withName(String name) {
      return name.equals(this.name) ? this : new AsPat(type, name, 0, pat);
    }

    @Override
    public AsPat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    /**
     * Creates a copy of this {@code AsPat} with given contents, or {@code this}
     * if the contents are the same.
     */
    public Core.AsPat copy(String name, int i, Core.Pat pat) {
      return this.name.equals(name) && this.i == i && this.pat.equals(pat)
          ? this
          : new AsPat(type, name, i, pat);
    }
  }

  /**
   * Type constructor pattern with an argument.
   *
   * <p>For example, in "fun nvl NIL = 0 | OPTION x = x", "OPTION x" is a type
   * constructor pattern that binds "x"; and "NIL" is a type constructor pattern
   * whose {@link #pat} is null.
   *
   * @see Con0Pat
   */
  public static class ConPat extends Pat {
    public final String tyCon;
    public final Pat pat;

    /**
     * Mostly-private constructor.
     *
     * <p>Exposed so that "op ::" (cons) can supply a different {@link Op}
     * value. The "list" datatype is not represented the same as other
     * datatypes, and the separate "op" value allows us to deconstruct it in a
     * different way.
     */
    protected ConPat(Op op, Type type, String tyCon, Pat pat) {
      super(op, type);
      this.tyCon = requireNonNull(tyCon);
      this.pat = requireNonNull(pat);
      checkArgument(op == Op.CON_PAT || op == Op.CONS_PAT);
    }

    ConPat(Type type, String tyCon, Pat pat) {
      this(Op.CON_PAT, type, tyCon, pat);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(tyCon).append("(").append(pat, 0, 0).append(")");
    }

    @Override
    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    /**
     * Creates a copy of this {@code ConPat} with given contents, or {@code
     * this} if the contents are the same.
     */
    public Core.ConPat copy(String tyCon, Core.Pat pat) {
      return this.tyCon.equals(tyCon) && this.pat.equals(pat)
          ? this
          : new ConPat(op, type, tyCon, pat);
    }
  }

  /**
   * Type constructor pattern with no argument.
   *
   * <p>For example, in "fun nvl NIL = 0 | OPTION x = x", "NIL" is a zero-arg
   * type constructor pattern.
   *
   * @see ConPat
   */
  public static class Con0Pat extends Pat {
    public final String tyCon;

    Con0Pat(DataType type, String tyCon) {
      super(Op.CON0_PAT, type);
      this.tyCon = requireNonNull(tyCon);
    }

    @Override
    public DataType type() {
      return (DataType) type;
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(tyCon);
    }

    @Override
    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }
  }

  /**
   * Tuple pattern, the pattern analog of the {@link Tuple} expression.
   *
   * <p>For example, "(x, y)" in "fun sum (x, y) = x + y".
   */
  public static class TuplePat extends Pat {
    public final List<Pat> args;

    /**
     * Creates a TuplePat.
     *
     * <p>Type is {@link PrimitiveType#UNIT} if {@code args} is empty, otherwise
     * a {@link TupleType}.
     */
    TuplePat(RecordLikeType type, ImmutableList<Pat> args) {
      super(Op.TUPLE_PAT, type);
      this.args = requireNonNull(args);
      checkArgument(args.size() == type.argNameTypes().size());
      checkArgument(
          args.isEmpty()
              ? type == PrimitiveType.UNIT
              : type instanceof TupleType);
    }

    @Override
    public RecordLikeType type() {
      return (RecordLikeType) type;
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      w.append("(");
      forEachIndexed(
          args, (arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append(")");
    }

    @Override
    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public TuplePat copy(TypeSystem typeSystem, List<Pat> args) {
      return args.equals(this.args) ? this : core.tuplePat(typeSystem, args);
    }

    /** Returns the names of all components that are named. */
    public List<String> fieldNames() {
      final ImmutableList.Builder<String> names = ImmutableList.builder();
      for (Pat arg : args) {
        if (arg instanceof NamedPat) {
          names.add(((NamedPat) arg).name);
        }
      }
      return names.build();
    }
  }

  /**
   * List pattern.
   *
   * <p>For example, "[x, y]" in "fun sum [x, y] = x + y".
   */
  public static class ListPat extends Pat {
    public final List<Pat> args;

    ListPat(Type type, ImmutableList<Pat> args) {
      super(Op.LIST_PAT, type);
      this.args = requireNonNull(args);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      w.append("[");
      forEachIndexed(
          args, (arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append("]");
    }

    @Override
    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public ListPat copy(TypeSystem typeSystem, List<Pat> args) {
      return args.equals(this.args) ? this : core.listPat(typeSystem, args);
    }
  }

  /** Record pattern. */
  public static class RecordPat extends Pat {
    public final List<Pat> args;

    RecordPat(RecordType type, ImmutableList<Pat> args) {
      super(Op.RECORD_PAT, type);
      this.args = requireNonNull(args);
      checkArgument(args.size() == type.argNameTypes.size());
      checkArgument(!args.isEmpty(), "Use TuplePat for empty record");
    }

    @Override
    public RecordType type() {
      return (RecordType) type;
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      w.append("{");
      forEachIndexed(
          type().argNameTypes.keySet(),
          args,
          (i, name, arg) ->
              w.append(i > 0 ? ", " : "")
                  .append(name)
                  .append(" = ")
                  .append(arg, 0, 0));
      return w.append("}");
    }

    @Override
    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Pat copy(TypeSystem typeSystem, RecordType type, List<Pat> args) {
      if (args.equals(this.args)) {
        return this;
      }
      if (!transform(args, Core.Pat::type).equals(type.argTypes())) {
        // The field types do not match. Create a new record type with the same
        // field names, new field types.
        // Cast is safe. If the previous type was a record type (non-empty,
        // field names are not consecutive integers) the new one will be also.
        type =
            (RecordType)
                typeSystem.recordType(
                    zip(type.argNames(), transform(args, Core.Pat::type)));
      }
      return core.recordPat(type, args);
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

    /**
     * Renders this expression as a query's plan: the tree the resolver built
     * for it.
     *
     * <p>A tree with no node in it is still a tree. A query that translates to
     * a bare expression -- {@code from}, whose tree is the unit leaf, or {@code
     * from u: unit} -- prints as a leaf line with its collection type, the way
     * the leaf of any other tree does.
     */
    public final String unparsePlan(TypeSystem typeSystem, int width) {
      final AstWriter w =
          AstNode.renumberingWriter(typeSystem, this, width, true);
      w.describeInput(this, 0);
      return AstNode.finish(w);
    }

    /** Returns the {@code i}<sup>th</sup> argument. */
    public Exp arg(int i) {
      throw new UnsupportedOperationException();
    }

    /** Returns the type. */
    public Type type() {
      return type;
    }

    /**
     * Returns the variables this expression uses but does not bind.
     *
     * <p>Scope-aware: a name bound by a {@code let}, a {@code fn}, a match or a
     * scan inside this expression is not free in it, however often it is read.
     */
    public Set<NamedPat> freePats(TypeSystem typeSystem) {
      return FreeFinder.freePats(typeSystem, this);
    }

    @Override
    public abstract Exp accept(Shuttle shuttle);

    /**
     * Returns whether this expression is a constant.
     *
     * <p>Examples include literals {@code 1}, {@code true}, constructors
     * applied to constants, records and tuples whose arguments are constant.
     */
    public boolean isConstant() {
      return false;
    }

    /**
     * Returns the {@link BuiltIn} that this is a call to, or {@link
     * BuiltIn#Z_VOID} if not a call.
     */
    public BuiltIn builtIn() {
      return BuiltIn.Z_VOID;
    }

    /** Returns whether this expression is a call to the given built-in. */
    public boolean isCallTo(BuiltIn builtIn) {
      return builtIn() == builtIn;
    }

    /**
     * Returns whether this expression is a call to the given constructor of a
     * built-in datatype.
     */
    public boolean isCallTo(BuiltIn.Constructor constructor) {
      return false;
    }

    /**
     * Returns whether this expression is the given nullary constructor of a
     * built-in datatype, such as {@code NONE}.
     *
     * <p>For a call to a constructor that has an argument, such as {@code SOME
     * x}, use {@link #isCallTo(BuiltIn.Constructor)}.
     */
    public boolean isConstructor(BuiltIn.Constructor constructor) {
      return false;
    }

    /** Returns whether the type of this expression is a constructor's type. */
    boolean isTypeOf(BuiltIn.Constructor constructor) {
      return type instanceof DataType
          && ((DataType) type).name.equals(constructor.datatype.mlName());
    }

    /**
     * Returns whether this expression iterates over the values of a type. Some
     * extents are infinite.
     */
    public boolean isExtent() {
      return isCallTo(BuiltIn.Z_EXTENT);
    }

    /** Returns the extent, or throws if not {@link #isExtent()}. */
    public RangeExtent getRangeExtent() {
      checkArgument(isExtent());
      final Apply apply = (Apply) this;
      final Core.Literal argLiteral = (Core.Literal) apply.arg;
      return argLiteral.unwrap(RangeExtent.class);
    }

    /** Returns whether this is a boolean literal with value {@code b}. */
    public boolean isBoolLiteral(boolean b) {
      return op == Op.BOOL_LITERAL
          && ((Literal) this).unwrap(Boolean.class) == b;
    }
  }

  /**
   * Reference to a variable.
   *
   * <p>While {@link Ast.Id} is widely used, and means an occurrence of a name
   * in the parse tree, {@code Id} is much narrower: it means a reference to a
   * value. What would be an {@code Id} in Ast is often a {@link String} in
   * Core; for example, compare {@link Ast.Con0Pat#tyCon} with {@link
   * Con0Pat#tyCon}.
   */
  public static class Id extends Exp implements Comparable<Id> {
    public final NamedPat idPat;

    /** Creates an Id. */
    Id(Pos pos, NamedPat idPat) {
      super(pos, Op.ID, idPat.type);
      this.idPat = requireNonNull(idPat);
    }

    @Override
    public int compareTo(Id o) {
      return idPat.compareTo(o.idPat);
    }

    @Override
    public int hashCode() {
      return idPat.hashCode();
    }

    @Override
    public boolean equals(Object o) {
      return o == this || o instanceof Id && this.idPat.equals(((Id) o).idPat);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      if (idPat.name.charAt(0) == '$') {
        // A node's pattern: an id, such as `$e` or `$0`, or `$ordinal`. It
        // prints under the name its node gave it.
        return w.idRef((IdPat) idPat);
      }
      return w.idQuoted(idPat.name, idPat.i);
    }

    @Override
    public boolean isConstructor(BuiltIn.Constructor constructor) {
      return idPat.name.equals(constructor.constructor)
          && isTypeOf(constructor);
    }
  }

  /** Record selector function. */
  public static class RecordSelector extends Exp {
    /**
     * The ordinal of the field in the record or tuple that is to be accessed.
     */
    public final int slot;

    /** Creates a record selector. */
    RecordSelector(FnType fnType, int slot) {
      super(Pos.ZERO, Op.RECORD_SELECTOR, fnType);
      this.slot = slot;
    }

    @Override
    public int hashCode() {
      return slot + 2237;
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof RecordSelector
              && this.slot == ((RecordSelector) o).slot
              && this.type.equals(((RecordSelector) o).type);
    }

    public String fieldName() {
      final RecordLikeType recordType = (RecordLikeType) type().paramType;
      return recordType.argNames().get(slot);
    }

    @Override
    public FnType type() {
      return (FnType) type;
    }

    @Override
    public RecordSelector accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.selector(fieldName());
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

    /**
     * Returns the value of this literal as a given class, or throws {@link
     * ClassCastException}. If the class is not {@link Comparable}, the value
     * will be in a wrapper.
     */
    public <C> C unwrap(Class<C> clazz) {
      Object v;
      if (value instanceof Wrapper
          && ((Wrapper) value).o instanceof TypedValue) {
        return ((TypedValue) ((Wrapper) value).o).valueAs(clazz);
      }
      if (clazz.isInstance(value) && clazz != Object.class) {
        v = value;
      } else if (Number.class.isAssignableFrom(clazz)
          && value instanceof Number) {
        Number number = (Number) value;
        if (clazz == Double.class) {
          v = number.doubleValue();
        } else if (clazz == Float.class) {
          v = number.floatValue();
        } else if (clazz == Long.class) {
          v = number.longValue();
        } else if (clazz == Integer.class) {
          v = number.intValue();
        } else if (clazz == Short.class) {
          v = number.shortValue();
        } else if (clazz == Byte.class) {
          v = number.byteValue();
        } else if (clazz == BigInteger.class && number instanceof BigDecimal) {
          v = ((BigDecimal) number).toBigIntegerExact();
        } else if (clazz == UnsignedLong.class
            && number instanceof BigDecimal) {
          // Throws if the value is negative or exceeds 2^64 - 1.
          v = UnsignedLong.valueOf(((BigDecimal) number).toBigIntegerExact());
        } else {
          v = value;
        }
      } else {
        v = ((Wrapper) value).o;
      }
      return clazz.cast(v);
    }

    /** Converts to a built-in. */
    public Object toBuiltIn(TypeSystem typeSystem, @Nullable Pos pos) {
      final BuiltIn builtIn = unwrap(BuiltIn.class);
      Object o = requireNonNull(Codes.BUILT_IN_VALUES.get(builtIn));
      final Pos pos2 = first(pos, this.pos);
      if (o instanceof Codes.Typed) {
        o = ((Codes.Typed) o).withType(typeSystem, type, pos2);
      }
      if (o instanceof Codes.Positioned) {
        o = ((Codes.Positioned) o).withPos(pos2);
      }
      return o;
    }

    /** Converts to an {@link Applicable} with one argument, or returns null. */
    public @Nullable Applicable1 toApplicable1(
        TypeSystem typeSystem, @Nullable Pos pos) {
      Object o = toBuiltIn(typeSystem, pos);
      if (o instanceof Applicable2) {
        return ((Applicable2) o).curry();
      }
      if (o instanceof Applicable3) {
        return ((Applicable3) o).curry();
      }
      return null;
    }

    /** Converts to an {@link Applicable2}, or returns null. */
    public @Nullable Applicable2 toApplicable2(
        TypeSystem typeSystem, @Nullable Pos pos) {
      Object o = toBuiltIn(typeSystem, pos);
      if (o instanceof Applicable2) {
        return (Applicable2) o;
      }
      return null;
    }

    /** Converts to an {@link Applicable3}, or returns null. */
    public @Nullable Applicable3 toApplicable3(
        TypeSystem typeSystem, @Nullable Pos pos) {
      Object o = toBuiltIn(typeSystem, pos);
      if (o instanceof Applicable3) {
        return (Applicable3) o;
      }
      return null;
    }

    /** Converts to an {@link Applicable4}, or returns null. */
    public @Nullable Applicable4 toApplicable4(
        TypeSystem typeSystem, @Nullable Pos pos) {
      Object o = toBuiltIn(typeSystem, pos);
      if (o instanceof Applicable4) {
        return (Applicable4) o;
      }
      return null;
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof Literal && value.equals(((Literal) o).value);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      switch (op) {
        case VALUE_LITERAL:
          // Generate the original expression from which this value was derived.
          return ((Wrapper) value).exp.unparse(w, left, right);
        case INTERNAL_LITERAL:
          // Print the value as if it were a string.
          return w.appendLiteral(((Wrapper) value).o.toString());
        case WORD_LITERAL:
          return w.appendLiteral(unwrap(UnsignedLong.class));
      }
      return w.appendLiteral(value);
    }

    @Override
    public boolean isConstant() {
      return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A nullary constructor such as {@code NONE} becomes a value literal,
     * whose value is a singleton list containing the constructor's name.
     */
    @Override
    public boolean isConstructor(BuiltIn.Constructor constructor) {
      if (op != Op.VALUE_LITERAL || !isTypeOf(constructor)) {
        return false;
      }
      final Object o = unwrap(Object.class);
      return o instanceof List
          && ((List<?>) o).size() == 1
          && constructor.constructor.equals(((List<?>) o).get(0));
    }
  }

  /** Base class for declarations. */
  public abstract static class Decl extends BaseNode {
    Decl(Pos pos, Op op) {
      super(pos, op);
    }

    @Override
    public abstract Decl accept(Shuttle shuttle);
  }

  /** Overloaded operator declaration. */
  public static class OverDecl extends Decl {
    public final IdPat pat;

    OverDecl(IdPat pat) {
      super(Pos.ZERO, Op.OVER_DECL);
      this.pat = requireNonNull(pat);
    }

    @Override
    public int hashCode() {
      return hash(Op.OVER_DECL, pat);
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof OverDecl && pat.equals(((OverDecl) o).pat);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("over ").append(pat.name);
    }

    @Override
    public OverDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }
  }

  /** Type declaration. */
  public static class TypeDecl extends Decl {
    public final List<AliasType> types;

    TypeDecl(ImmutableList<AliasType> types) {
      super(Pos.ZERO, Op.TYPE_DECL);
      this.types = requireNonNull(types);
      checkArgument(!this.types.isEmpty());
    }

    @Override
    public int hashCode() {
      return hash(types);
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof TypeDecl && types.equals(((TypeDecl) o).types);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      forEachIndexed(
          types,
          (type, i) ->
              w.append(i == 0 ? "type " : " and ").append(type.toString()));
      return w;
    }

    @Override
    public TypeDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }
  }

  /** Datatype declaration. */
  public static class DatatypeDecl extends Decl {
    public final List<DataType> dataTypes;

    DatatypeDecl(ImmutableList<DataType> dataTypes) {
      super(Pos.ZERO, Op.DATATYPE_DECL);
      this.dataTypes = requireNonNull(dataTypes);
      checkArgument(!this.dataTypes.isEmpty());
    }

    @Override
    public int hashCode() {
      return hash(dataTypes);
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof DatatypeDecl
              && dataTypes.equals(((DatatypeDecl) o).dataTypes);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      forEachIndexed(
          dataTypes,
          (dataType, i) ->
              w.append(i == 0 ? "datatype " : " and ")
                  .append(dataType.toString()));
      return w;
    }

    @Override
    public DatatypeDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }
  }

  /** Abstract (recursive or non-recursive) value declaration. */
  public abstract static class ValDecl extends Decl {
    ValDecl(Pos pos, Op op) {
      super(pos, op);
    }

    @Override
    public abstract ValDecl accept(Shuttle shuttle);

    public abstract void forEachBinding(BindingConsumer consumer);

    /** Returns all patterns bound. */
    public List<NamedPat> boundPats() {
      final ImmutableList.Builder<Core.NamedPat> list = ImmutableList.builder();
      forEachBinding((pat, exp2, overloadPat, pos) -> list.add(pat));
      return list.build();
    }
  }

  /** Consumer of bindings. */
  @FunctionalInterface
  public interface BindingConsumer {
    void accept(NamedPat pat, Exp exp, @Nullable IdPat overloadPat, Pos pos);
  }

  /**
   * Non-recursive value declaration.
   *
   * @see RecValDecl#list
   */
  public static class NonRecValDecl extends ValDecl {
    public final NamedPat pat;
    public final Exp exp;
    /** If an 'inst', the overloaded name, otherwise null. */
    public final Core.@Nullable IdPat overloadPat;

    NonRecValDecl(NamedPat pat, Exp exp, @Nullable IdPat overloadPat, Pos pos) {
      super(pos, Op.VAL_DECL);
      this.pat = pat;
      this.exp = exp;
      this.overloadPat = overloadPat;
      if (!canAssign(exp.type, pat.type)) {
        throw new IllegalArgumentException(
            format(
                "cannot assign '%s' (type '%s') to pattern '%s' (type '%s')",
                exp, exp.type, pat, pat.type));
      }
    }

    @Override
    public int hashCode() {
      return hash(pat, exp);
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof NonRecValDecl
              && pat.equals(((NonRecValDecl) o).pat)
              && exp.equals(((NonRecValDecl) o).exp);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      if (w.treeMode() && exp instanceof Rel) {
        // Break after the '=' so that the plan's root operator is the first
        // non-whitespace on its line. Otherwise every plan of
        // a query would begin `val it = r[1]`, and its one interesting line
        // would be an indirection.
        w.append("val ").append(pat, 0, 0).append(" =\n");
        w.describe((Rel) exp, 2);
        return w;
      }
      if (w.treeMode()) {
        // The value may start on the next line, indented two, which is what a
        // `let` wants: `let` belongs at the head of a line of its own, not
        // trailing an `=`.
        w.startGroup(2);
        w.append("val ").append(pat, 0, 0).append(" =").softBreak();
        w.append(exp, 0, right);
        return w.endGroup();
      }
      return w.append("val ")
          .append(pat, 0, 0)
          .append(" = ")
          .append(exp, 0, right);
    }

    @Override
    public NonRecValDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public NonRecValDecl copy(
        NamedPat pat, Exp exp, @Nullable IdPat overloadPat) {
      return pat == this.pat
              && exp == this.exp
              && overloadPat == this.overloadPat
          ? this
          : core.nonRecValDecl(pos, pat, overloadPat, exp);
    }

    @Override
    public void forEachBinding(BindingConsumer consumer) {
      consumer.accept(pat, exp, overloadPat, pos);
    }
  }

  /** Recursive value declaration. */
  public static class RecValDecl extends ValDecl {
    public final ImmutableList<NonRecValDecl> list;

    RecValDecl(ImmutableList<NonRecValDecl> list) {
      super(Pos.ZERO, Op.REC_VAL_DECL);
      this.list = requireNonNull(list);
    }

    @Override
    public int hashCode() {
      return list.hashCode();
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof RecValDecl && list.equals(((RecValDecl) o).list);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      w.append("val rec ");
      forEachIndexed(
          list,
          (decl, i) ->
              w.append(i == 0 ? "" : " and ")
                  .append(decl.pat, 0, 0)
                  .append(" = ")
                  .append(decl.exp, 0, right));
      return w;
    }

    @Override
    public RecValDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    public void forEachBinding(BindingConsumer consumer) {
      list.forEach(b -> b.forEachBinding(consumer));
    }

    public RecValDecl copy(List<NonRecValDecl> list) {
      return list.equals(this.list) ? this : core.recValDecl(list);
    }
  }

  /** Tuple expression. Also implements record expression. */
  // TODO: remove, replace with a call to the constructor of the n-tuple type?
  public static class Tuple extends Exp {
    public final List<Exp> args;

    Tuple(RecordLikeType type, ImmutableList<Exp> args) {
      super(Pos.ZERO, Op.TUPLE, type);
      checkArgument(
          !(type instanceof PrimitiveType) || type == PrimitiveType.UNIT,
          "primitive type '%s' is not a tuple",
          type);
      this.args = ImmutableList.copyOf(args);
    }

    @Override
    public boolean equals(Object o) {
      return this == o
          || o instanceof Tuple
              && args.equals(((Tuple) o).args)
              && type.equals(((Tuple) o).type);
    }

    @Override
    public int hashCode() {
      return hash(args, type);
    }

    @Override
    public RecordLikeType type() {
      return (RecordLikeType) type;
    }

    @Override
    public void forEachArg(ObjIntConsumer<Exp> action) {
      forEachIndexed(args, action);
    }

    @Override
    public Exp arg(int i) {
      return args.get(i);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      // A record or tuple may break after a comma, one field to a line, which
      // is the other place a plan's lines get long.
      w.startGroup(0);
      if (type instanceof RecordType) {
        w.append("{");
        forEach(
            (i, name, exp) -> {
              if (i > 0) {
                w.append(",").softBreak();
              }
              w.append(name).append(" = ").append(exp, 0, 0);
            });
        w.append("}");
      } else {
        w.append("(");
        forEach(
            (i, name, arg) -> {
              if (i > 0) {
                w.append(",").softBreak();
              }
              w.append(arg, 0, 0);
            });
        w.append(")");
      }
      return w.endGroup();
    }

    /**
     * Calls a consumer with the name, expression and ordinal of each field of
     * this tuple.
     */
    public void forEach(PairList.IndexedBiConsumer<String, Exp> consumer) {
      forEachIndexed(type().argNames(), args, consumer::accept);
    }

    public Tuple copy(TypeSystem typeSystem, List<Exp> args) {
      return args.equals(this.args)
          ? this
          : core.tuple(typeSystem, type(), args);
    }

    @Override
    public boolean isConstant() {
      return allMatch(args, Exp::isConstant);
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

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      // One decision, four breaks: a `let` that does not fit becomes `let`,
      // its declaration, `in`, its body and `end`, each on a line, with the
      // declaration and the body indented two. One group, so they are taken
      // together; nests rather than groups inside it, so the two that are
      // indented do not decide for themselves.
      if (!w.treeMode()) {
        // Outside a plan there is no width to fit, and a tree still prints in
        // place here -- its line breaks would fall inside this indentation.
        return w.append("let ")
            .append(decl, 0, 0)
            .append(" in ")
            .append(exp, 0, 0)
            .append(" end");
      }
      w.startGroup(0);
      w.startNest(2).append("let").softBreak().append(decl, 0, 0).endNest();
      w.softBreak().append("in");
      w.startNest(2).softBreak().append(exp, 0, 0).endNest();
      w.softBreak().append("end");
      return w.endGroup();
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Exp copy(ValDecl decl, Exp exp) {
      return decl == this.decl && exp == this.exp ? this : core.let(decl, exp);
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

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("local datatype ")
          .append(dataType.toString())
          .append(" in ")
          .append(exp, 0, 0)
          .append(" end");
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Exp copy(DataType dataType, Exp exp) {
      return dataType == this.dataType && exp == this.exp
          ? this
          : core.local(dataType, exp);
    }
  }

  /**
   * Match.
   *
   * <p>In AST, there are several places that can deconstruct values via
   * patterns: {@link Ast.FunDecl fun}, {@link Ast.Fn fn}, {@link Ast.Let let},
   * {@link Ast.Case case}. But in Core, there is only {@code Match}, and {@code
   * Match} only occurs within {@link Ast.Case case}. This makes the Core
   * language a little more verbose than AST but a lot more uniform.
   */
  public static class Match extends BaseNode {
    public final Pat pat;
    public final Exp exp;

    Match(Pos pos, Pat pat, Exp exp) {
      super(pos, Op.MATCH);
      this.pat = pat;
      this.exp = exp;
    }

    @Override
    public Match accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(pat, 0, 0).append(" => ").append(exp, 0, right);
    }

    public Match copy(Pat pat, Exp exp) {
      return pat == this.pat && exp == this.exp
          ? this
          : core.match(pos, pat, exp);
    }
  }

  /** Lambda expression. */
  public static class Fn extends Exp {
    public final IdPat idPat;
    public final Exp exp;

    Fn(Pos pos, FnType type, IdPat idPat, Exp exp) {
      super(pos, Op.FN, type);
      this.idPat = requireNonNull(idPat);
      this.exp = requireNonNull(exp);
    }

    @Override
    public FnType type() {
      return (FnType) type;
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("fn ")
          .append(idPat, 0, 0)
          .append(" => ")
          .append(exp, 0, right);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Fn copy(IdPat idPat, Exp exp) {
      return idPat == this.idPat && exp == this.exp
          ? this
          : core.fn(type(), idPat, exp);
    }
  }

  /**
   * Raise expression: evaluates {@code exp} (of type {@code exn}) and throws.
   */
  public static class Raise extends Exp {
    public final Exp exp;

    Raise(Pos pos, Type type, Exp exp) {
      super(pos, Op.RAISE, type);
      this.exp = requireNonNull(exp);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("raise ").append(exp, 0, right);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Raise copy(Type type, Exp exp) {
      return type == this.type && exp == this.exp
          ? this
          : new Raise(pos, type, exp);
    }
  }

  /**
   * Case expression.
   *
   * <p>Also implements {@link Ast.If}.
   */
  public static class Case extends Exp {
    public final Exp exp;
    public final List<Match> matchList;

    Case(Pos pos, Type type, Exp exp, ImmutableList<Match> matchList) {
      super(pos, Op.CASE, type);
      this.exp = exp;
      this.matchList = matchList;
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      if (!w.treeMode()) {
        return w.append("case ")
            .append(exp, 0, 0)
            .append(" of ")
            .appendAll(matchList, left, Op.BAR, right);
      }
      // A `case` that does not fit puts each arm after the first on a line of
      // its own, behind the `|` that introduces it. `if` reaches here too: it
      // is a `case` over `true` and `false` by the time a plan sees it.
      w.startGroup(2);
      w.append("case ").append(exp, 0, 0).append(" of ");
      for (int i = 0; i < matchList.size(); i++) {
        if (i > 0) {
          w.softBreak().append("| ");
        }
        w.append(
            matchList.get(i),
            i == 0 ? left : Op.BAR.left,
            i == matchList.size() - 1 ? right : Op.BAR.right);
      }
      return w.endGroup();
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Case copy(Exp exp, List<Match> matchList) {
      return exp == this.exp && matchList.equals(this.matchList)
          ? this
          : core.caseOf(pos, type, exp, matchList);
    }
  }

  /** Returns whether an expression mentions a pattern. */
  public static boolean mentions(Exp exp, NamedPat pat) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Id id) {
            if (id.idPat.equals(pat)) {
              found[0] = true;
            }
          }
        });
    return found[0];
  }

  /**
   * Node in a relational tree: an operator whose inputs are collections and
   * whose value is a collection of a definite type.
   *
   * <p>The resolver builds it, the rewrite passes carry it, and the compilers
   * read it.
   *
   * <p>A node is an {@link Exp} whose type is a collection type. Therefore a
   * node's input is simply an expression: a nested node, or a leaf such as
   * {@code scott.emps} or {@code [1, 2]}. There is no scan operator; any
   * collection-valued expression will do, and the node above it names its
   * element {@code $0}.
   *
   * <p>A node carries no bindings. Its element type is derived from its inputs
   * and its expressions, and is exactly the type of the value that flows out of
   * it. A node binds patterns for its expressions, as {@code fn} and {@code
   * case} do: the id by which it refers to the current element, {@code $0}
   * (and, in a {@link Join}, the right input's id {@code $1}), and optionally
   * the id of the element's ordinal, {@code $ordinal}. See {@link #patterns()}.
   */
  public abstract static class Rel extends Exp {
    Rel(Pos pos, Op op, Type type) {
      super(pos, op, type);
      if (!type.isCollection()) {
        throw new IllegalArgumentException("not a collection type: " + type);
      }
    }

    /**
     * Returns the patterns this node binds for its expressions: its id, and its
     * ordinal id if it has one. Empty for a node with no per-element
     * expressions.
     */
    public List<IdPat> patterns() {
      return ImmutableList.of();
    }

    /** Returns this node's inputs. */
    public abstract List<Exp> inputs();

    /**
     * Appends this node's arguments, each in brackets, to a plan-text line.
     *
     * <p>Arguments that carry no information (an inner join's kind, a condition
     * that is {@code true}) are omitted.
     */
    protected void describeArgs(AstWriter w) {}

    /** Returns this node's plan text, as {@code Sys.plan} prints it. */
    public String describe(TypeSystem typeSystem) {
      return describe(typeSystem, AstWriter.DEFAULT_WIDTH, false);
    }

    /**
     * Returns this node's plan text; if {@code withTypes}, appends the
     * collection type of every node, as {@code Sys.planEx} prints it.
     */
    public String describe(
        TypeSystem typeSystem, int width, boolean withTypes) {
      final AstWriter w =
          AstNode.renumberingWriter(typeSystem, this, width, withTypes);
      w.describe(this, 0);
      return AstNode.finish(w);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      // Onto the caller's writer, not as a string built by another: the
      // binders a plan renumbers are numbered by first occurrence over the
      // whole text, so a tree nested in an expression must share the count.
      if (w.treeMode()) {
        // Reached from inside an expression, which is the only way `unparse`
        // is reached at all: a node's inputs go through `describe`, and a
        // root through `describe` as well. A relational operator is the first
        // non-whitespace on its line, and this one would not be, so it prints
        // as a reference and its lines go below, where they can be read.
        // Spliced in here, its indentation would begin again from zero inside
        // the line that held it, so two nodes at different depths could print
        // at the same indent.
        return w.append(w.relRef(this));
      }
      return w.describe(this, 0);
    }
  }

  /** Node with one input. */
  public abstract static class SingleRel extends Rel {
    public final Exp input;

    SingleRel(Pos pos, Op op, Type type, Exp input) {
      super(pos, op, type);
      this.input = requireNonNull(input, "input");
    }

    @Override
    public List<Exp> inputs() {
      return ImmutableList.of(input);
    }
  }

  /**
   * A one-input node whose expressions run once per element of its input, and
   * that binds patterns for them: {@link #id}, the id by which the node refers
   * to the current element, and optionally {@link #ordinalId}, the id by which
   * it refers to the element's ordinal.
   */
  public abstract static class RowRel extends SingleRel {
    public final IdPat id;
    public final @Nullable IdPat ordinalId;

    RowRel(
        Pos pos,
        Op op,
        Type type,
        IdPat id,
        @Nullable IdPat ordinalId,
        Exp input) {
      super(pos, op, type, input);
      this.id = requireNonNull(id, "id");
      this.ordinalId = ordinalId;
      checkArgument(
          id.type.equals(input.type.elementType()),
          "id %s must have the element type %s",
          id,
          input.type.elementType());
      checkArgument(
          ordinalId == null || ordinalId.type == PrimitiveType.INT,
          "ordinalId %s must be int",
          ordinalId);
    }

    @Override
    public List<IdPat> patterns() {
      return ordinalId == null
          ? ImmutableList.of(id)
          : ImmutableList.of(id, ordinalId);
    }
  }

  /**
   * Removes the elements for which a condition, an expression over {@code $0},
   * is false.
   */
  public static class Filter extends RowRel {
    public final Exp condition;

    Filter(
        Pos pos,
        IdPat id,
        @Nullable IdPat ordinalId,
        Exp input,
        Exp condition) {
      super(pos, Op.FILTER, input.type, id, ordinalId, input);
      this.condition = requireNonNull(condition, "condition");
    }

    @Override
    protected void describeArgs(AstWriter w) {
      w.arg(condition);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Filter copy(Exp input, Exp condition) {
      return input == this.input && condition == this.condition
          ? this
          : core.filter(pos, id, ordinalId, input, condition);
    }
  }

  /** Maps each element to one element, via an expression over {@code $0}. */
  public static class Project extends RowRel {
    public final Exp exp;

    Project(
        Pos pos,
        Type type,
        IdPat id,
        @Nullable IdPat ordinalId,
        Exp input,
        Exp exp) {
      super(pos, Op.PROJECT, type, id, ordinalId, input);
      this.exp = requireNonNull(exp, "exp");
    }

    @Override
    protected void describeArgs(AstWriter w) {
      w.arg(exp);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Project copy(TypeSystem typeSystem, Exp input, Exp exp) {
      return input == this.input && exp == this.exp
          ? this
          : core.project(pos, typeSystem, id, ordinalId, input, exp);
    }
  }

  /**
   * Pairs elements of two inputs that satisfy a condition over {@code $0} and
   * {@code $1}; the element is the inputs' components concatenated.
   */
  public static class Join extends Rel {
    public final JoinType joinType;

    /**
     * The id by which the join refers to the current element of its left input,
     * {@code $0}. It is in scope in the condition and in {@link #right}: the
     * right input is evaluated once per left element, and a right input that
     * reads it makes the join <em>dependent</em>. Dependence is a free
     * occurrence, not a mode, and decorrelation is rewriting the right input
     * until it no longer reads it.
     */
    public final IdPat leftId;

    /**
     * The id by which the join refers to the current element of its right
     * input, {@code $1}, in scope in the condition only.
     */
    public final IdPat rightId;

    public final @Nullable IdPat ordinalId;
    public final Exp left;
    public final Exp right;
    public final Exp condition;

    Join(
        Pos pos,
        Type type,
        JoinType joinType,
        IdPat leftId,
        IdPat rightId,
        @Nullable IdPat ordinalId,
        Exp left,
        Exp right,
        Exp condition) {
      super(pos, Op.JOIN, type);
      this.joinType = requireNonNull(joinType, "joinType");
      this.leftId = requireNonNull(leftId, "leftId");
      this.rightId = requireNonNull(rightId, "rightId");
      this.ordinalId = ordinalId;
      this.left = requireNonNull(left, "left");
      this.right = requireNonNull(right, "right");
      this.condition = requireNonNull(condition, "condition");
      checkArgument(
          leftId.type.equals(left.type.elementType()),
          "left id %s must have the element type %s",
          leftId,
          left.type.elementType());
      checkArgument(
          rightId.type.equals(right.type.elementType()),
          "right id %s must have the element type %s",
          rightId,
          right.type.elementType());
      checkArgument(
          ordinalId == null || ordinalId.type == PrimitiveType.INT,
          "ordinalId %s must be int",
          ordinalId);
    }

    @Override
    public List<IdPat> patterns() {
      return ordinalId == null
          ? ImmutableList.of(leftId, rightId)
          : ImmutableList.of(leftId, rightId, ordinalId);
    }

    /**
     * Returns whether the right input reads the left id, which makes this a
     * dependent join.
     */
    public boolean isDependent() {
      return mentions(right, leftId);
    }

    @Override
    public List<Exp> inputs() {
      return ImmutableList.of(left, right);
    }

    @Override
    protected void describeArgs(AstWriter w) {
      if (joinType != JoinType.INNER) {
        w.arg(joinType.opName());
      }
      if (!condition.isBoolLiteral(true)) {
        w.arg(condition);
      }
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Join copy(
        TypeSystem typeSystem,
        JoinType joinType,
        Exp left,
        Exp right,
        Exp condition) {
      return joinType == this.joinType
              && left == this.left
              && right == this.right
              && condition == this.condition
          ? this
          : core.join(
              pos,
              typeSystem,
              joinType,
              leftId,
              rightId,
              ordinalId,
              left,
              right,
              condition);
    }
  }

  /**
   * Groups elements by zero or more keys, computing zero or more aggregates.
   *
   * <p>Keys and aggregate arguments are expressions over {@code $0}; the labels
   * are the output record's labels. {@code distinct} is this node with the
   * whole element as its only key and no aggregates.
   */
  public static class Group extends RowRel {
    public final ImmutableSortedMap<String, Exp> keys;
    public final ImmutableSortedMap<String, Aggregate> aggregates;

    Group(
        Pos pos,
        Type type,
        IdPat id,
        @Nullable IdPat ordinalId,
        Exp input,
        ImmutableSortedMap<String, Exp> keys,
        ImmutableSortedMap<String, Aggregate> aggregates) {
      super(pos, Op.GROUP, type, id, ordinalId, input);
      this.keys = requireNonNull(keys, "keys");
      this.aggregates = requireNonNull(aggregates, "aggregates");
    }

    @Override
    protected void describeArgs(AstWriter w) {
      w.args(keys);
      if (!aggregates.isEmpty()) {
        w.args(aggregates);
      }
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Group copy(
        TypeSystem typeSystem,
        Exp input,
        SortedMap<String, Exp> keys,
        SortedMap<String, Aggregate> aggregates) {
      return input == this.input
              && keys.equals(this.keys)
              && aggregates.equals(this.aggregates)
          ? this
          : core.group(pos, typeSystem, id, ordinalId, input, keys, aggregates);
    }
  }

  /**
   * Sorts elements by an expression over {@code $0}; always yields a {@code
   * list}.
   */
  public static class Sort extends RowRel {
    public final Exp exp;

    Sort(
        Pos pos,
        Type type,
        IdPat id,
        @Nullable IdPat ordinalId,
        Exp input,
        Exp exp) {
      super(pos, Op.SORT, type, id, ordinalId, input);
      this.exp = requireNonNull(exp, "exp");
    }

    @Override
    protected void describeArgs(AstWriter w) {
      w.arg(exp);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Sort copy(TypeSystem typeSystem, Exp input, Exp exp) {
      return input == this.input && exp == this.exp
          ? this
          : core.sort(pos, typeSystem, id, ordinalId, input, exp);
    }
  }

  /** Discards order; always yields a {@code bag}. */
  public static class Unorder extends SingleRel {
    Unorder(Pos pos, Type type, Exp input) {
      super(pos, Op.UNORDER, type, input);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Unorder copy(TypeSystem typeSystem, Exp input) {
      return input == this.input ? this : core.unorder(pos, typeSystem, input);
    }
  }

  /**
   * Discards the first {@code count} elements.
   *
   * <p>{@code count} is evaluated once, before the first element exists, and
   * therefore cannot mention {@code $0}.
   */
  public static class Skip extends SingleRel {
    public final Exp count;

    Skip(Pos pos, Exp input, Exp count) {
      super(pos, Op.SKIP, input.type, input);
      this.count = requireNonNull(count, "count");
    }

    @Override
    protected void describeArgs(AstWriter w) {
      w.arg(count);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Skip copy(Exp input, Exp count) {
      return input == this.input && count == this.count
          ? this
          : core.skip(pos, input, count);
    }
  }

  /**
   * Keeps the first {@code count} elements.
   *
   * <p>{@code count} is evaluated once, before the first element exists, and
   * therefore cannot mention {@code $0}.
   */
  public static class Take extends SingleRel {
    public final Exp count;

    Take(Pos pos, Exp input, Exp count) {
      super(pos, Op.TAKE, input.type, input);
      this.count = requireNonNull(count, "count");
    }

    @Override
    protected void describeArgs(AstWriter w) {
      w.arg(count);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Take copy(Exp input, Exp count) {
      return input == this.input && count == this.count
          ? this
          : core.take(pos, input, count);
    }
  }

  /**
   * Base class of {@link Union}, {@link Intersect} and {@link Except}, which
   * combine the elements of two or more inputs of the same element type.
   */
  public abstract static class SetRel extends Rel {
    public final boolean distinct;
    public final ImmutableList<Exp> inputs;

    SetRel(
        Pos pos,
        Op op,
        Type type,
        boolean distinct,
        ImmutableList<Exp> inputs) {
      super(pos, op, type);
      this.distinct = distinct;
      this.inputs = requireNonNull(inputs, "inputs");
      if (inputs.size() < 2) {
        throw new IllegalArgumentException(
            "set operator needs at least two inputs: " + inputs.size());
      }
    }

    @Override
    public List<Exp> inputs() {
      return inputs;
    }

    @Override
    protected void describeArgs(AstWriter w) {
      if (!distinct) {
        w.arg("all");
      }
    }

    public abstract SetRel copy(
        TypeSystem typeSystem, boolean distinct, List<Exp> inputs);
  }

  /** Combines the elements of its inputs. */
  public static class Union extends SetRel {
    Union(Pos pos, Type type, boolean distinct, ImmutableList<Exp> inputs) {
      super(pos, Op.UNION, type, distinct, inputs);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    public Union copy(
        TypeSystem typeSystem, boolean distinct, List<Exp> inputs) {
      return distinct == this.distinct && inputs.equals(this.inputs)
          ? this
          : core.union(pos, typeSystem, distinct, inputs);
    }
  }

  /** Keeps the elements that occur in every input. */
  public static class Intersect extends SetRel {
    Intersect(Pos pos, Type type, boolean distinct, ImmutableList<Exp> inputs) {
      super(pos, Op.INTERSECT, type, distinct, inputs);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    public Intersect copy(
        TypeSystem typeSystem, boolean distinct, List<Exp> inputs) {
      return distinct == this.distinct && inputs.equals(this.inputs)
          ? this
          : core.intersect(pos, typeSystem, distinct, inputs);
    }
  }

  /** Keeps the elements of the first input that occur in no other input. */
  public static class Except extends SetRel {
    Except(Pos pos, Type type, boolean distinct, ImmutableList<Exp> inputs) {
      super(pos, Op.EXCEPT, type, distinct, inputs);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    public Except copy(
        TypeSystem typeSystem, boolean distinct, List<Exp> inputs) {
      return distinct == this.distinct && inputs.equals(this.inputs)
          ? this
          : core.except(pos, typeSystem, distinct, inputs);
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

    /**
     * Returns the argument list (assuming that the arguments are a tuple or
     * record).
     *
     * @throws ClassCastException if argument is not a tuple
     */
    public List<Exp> args() {
      return ((Tuple) arg).args;
    }

    @Override
    public Exp arg(int i) {
      // Throws if the argument is not a tuple.
      return arg.arg(i);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      switch (fn.op) {
        case FN_LITERAL:
          final BuiltIn builtIn = ((Literal) fn).unwrap(BuiltIn.class);

          // Because the Core language is narrower than AST, a few AST
          // expression
          // types do not exist in Core and are translated to function
          // applications. Here we convert them back to original syntax.
          switch (builtIn) {
            case Z_LIST:
              w.append("[");
              arg.forEachArg(
                  (arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
              return w.append("]");
          }

          // Convert built-ins to infix operators.
          final Op op = Resolver.toOp(builtIn);
          if (op != null) {
            return w.infix(left, args().get(0), op, args().get(1), right);
          }
      }
      return w.apply(left, fn, arg, right);
    }

    public Apply copy(Exp fn, Exp arg) {
      return fn == this.fn && arg == this.arg
          ? this
          : core.apply(pos, type, fn, arg);
    }

    /**
     * Creates an Apply with the same function and a list of new arguments. If
     * the arguments are the same, returns this Apply.
     */
    public Apply withArgs(List<Exp> args) {
      if (args.size() == 1) {
        return copy(fn, args.get(0));
      }
      return withTupleArgs(args);
    }

    /**
     * Creates an Apply with the same function and new arguments. If the
     * arguments are the same, returns this Apply.
     */
    public Apply withArgs(Exp arg0, Exp... args) {
      if (args.length == 0) {
        return copy(fn, arg0);
      }
      return withArgs(Lists.asList(arg0, args));
    }

    private Apply withTupleArgs(List<Exp> args) {
      if (args.equals(args())) {
        return this;
      }
      final Tuple newArg = core.tuple((RecordLikeType) this.arg.type, args);
      return core.apply(pos, type, fn, newArg);
    }

    @Override
    public boolean isConstant() {
      // A list of constants is constant
      return isCallTo(BuiltIn.Z_LIST) && allMatch(args(), Exp::isConstant);
    }

    @Override
    public BuiltIn builtIn() {
      if (fn.op == Op.FN_LITERAL) {
        return ((Literal) fn).unwrap(BuiltIn.class);
      }
      return super.builtIn();
    }

    @Override
    public boolean isCallTo(BuiltIn.Constructor constructor) {
      return fn instanceof Id
          && ((Id) fn).idPat.name.equals(constructor.constructor);
    }
  }

  /**
   * Call to an aggregate function in a {@code compute} clause.
   *
   * <p>For example, in {@code compute {sumId = sum over #id e}}, {@code
   * aggregate} is "sum", {@code argument} is "#id e".
   */
  public static class Aggregate extends BaseNode {
    public final Type type;
    public final Exp aggregate;
    public final @Nullable Exp argument;

    Aggregate(Pos pos, Type type, Exp aggregate, @Nullable Exp argument) {
      super(pos, Op.AGGREGATE);
      this.type = type;
      this.aggregate = requireNonNull(aggregate);
      this.argument = argument;
    }

    @Override
    public Aggregate accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      w.append(aggregate, 0, 0);
      if (argument != null) {
        w.append(" over ").append(argument, 0, 0);
      }
      return w;
    }

    public Aggregate copy(Type type, Exp aggregate, @Nullable Exp argument) {
      return aggregate == this.aggregate && argument == this.argument
          ? this
          : core.aggregate(pos, type, aggregate, argument);
    }
  }

  /**
   * Wraps a value as a Comparable, and stores the global expression from which
   * the value was derived. That global expression will be used if the value is
   * converted by to Morel code.
   */
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

    @Override
    public int compareTo(Wrapper o) {
      return Integer.compare(this.o.hashCode(), o.o.hashCode());
    }

    @Override
    public String toString() {
      return o.toString();
    }

    @Override
    public int hashCode() {
      return o.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return this == obj
          || obj instanceof Wrapper && this.o.equals(((Wrapper) obj).o);
    }

    /** Returns the value. */
    <T> T unwrap(Class<T> valueClass) {
      return valueClass.cast(o);
    }
  }
}

// End Core.java
