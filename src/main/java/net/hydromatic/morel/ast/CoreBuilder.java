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
import static com.google.common.collect.Maps.transformValues;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.type.RecordType.ORDERING;
import static net.hydromatic.morel.util.PairList.fromTransformed;
import static net.hydromatic.morel.util.PairList.zip;
import static net.hydromatic.morel.util.Static.allMatch;
import static net.hydromatic.morel.util.Static.filterEager;
import static net.hydromatic.morel.util.Static.plus;
import static net.hydromatic.morel.util.Static.transform;
import static net.hydromatic.morel.util.Static.transformEager;
import static net.hydromatic.morel.util.Static.transformToMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.primitives.UnsignedLong;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.compile.Extents;
import net.hydromatic.morel.compile.NameGenerator;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.AliasType;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.MultiType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RangeExtent;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;
import net.hydromatic.morel.util.Pair;
import org.jspecify.annotations.Nullable;

/** Builds parse tree nodes. */
public enum CoreBuilder {
  /**
   * The singleton instance of the CORE builder. The short name is convenient
   * for use via 'import static', but checkstyle does not approve.
   */
  // CHECKSTYLE: IGNORE 1
  core;

  private final Core.LiteralPat truePat =
      literalPat(Op.BOOL_LITERAL_PAT, PrimitiveType.BOOL, Boolean.TRUE);

  private final Core.WildcardPat boolWildcardPat =
      wildcardPat(PrimitiveType.BOOL);

  private final Core.Literal trueLiteral = boolLiteral_(true);

  private final Core.Literal falseLiteral = boolLiteral_(false);

  /** Creates a literal. */
  public Core.Literal literal(PrimitiveType type, Object value) {
    switch (type) {
      case BOOL:
        return boolLiteral((Boolean) value);
      case CHAR:
        return charLiteral((Character) value);
      case INT:
        return intLiteral(
            value instanceof BigDecimal
                ? (BigDecimal) value
                : BigDecimal.valueOf(((Number) value).longValue()));
      case REAL:
        if (value instanceof Float && ((Float) value).isNaN()) {
          return new Core.Literal(
              Op.REAL_LITERAL, PrimitiveType.REAL, (Float) value);
        }
        return realLiteral(
            value instanceof BigDecimal
                ? (BigDecimal) value
                : BigDecimal.valueOf(((Number) value).doubleValue()));
      case STRING:
        return stringLiteral((String) value);
      case WORD:
        // A non-BigDecimal value is a runtime word, i.e. the bit pattern of a
        // signed long that should be read as unsigned.
        return wordLiteral(
            value instanceof BigDecimal
                ? (BigDecimal) value
                : new BigDecimal(
                    UnsignedLong.fromLongBits(((Number) value).longValue())
                        .bigIntegerValue()));
      case UNIT:
        return unitLiteral();
      default:
        throw new AssertionError("unexpected " + type);
    }
  }

  /** Used only during initialization. */
  private static Core.Literal boolLiteral_(boolean b) {
    return new Core.Literal(Op.BOOL_LITERAL, PrimitiveType.BOOL, b);
  }

  /** Creates a {@code boolean} literal. */
  public Core.Literal boolLiteral(boolean b) {
    return b ? trueLiteral : falseLiteral;
  }

  /** Creates a {@code char} literal. */
  public Core.Literal charLiteral(char c) {
    return new Core.Literal(Op.CHAR_LITERAL, PrimitiveType.CHAR, c);
  }

  /** Creates an {@code int} literal. */
  public Core.Literal intLiteral(BigDecimal value) {
    return new Core.Literal(Op.INT_LITERAL, PrimitiveType.INT, value);
  }

  /** Creates a {@code real} literal. */
  public Core.Literal realLiteral(BigDecimal value) {
    return new Core.Literal(Op.REAL_LITERAL, PrimitiveType.REAL, value);
  }

  /** Creates a {@code real} literal with a non-normal value. */
  public Core.Literal realLiteral(Float value) {
    return new Core.Literal(Op.REAL_LITERAL, PrimitiveType.REAL, value);
  }

  /** Creates a {@code string} literal. */
  public Core.Literal stringLiteral(String value) {
    return new Core.Literal(Op.STRING_LITERAL, PrimitiveType.STRING, value);
  }

  /** Creates a unit literal. */
  public Core.Literal unitLiteral() {
    return new Core.Literal(Op.UNIT_LITERAL, PrimitiveType.UNIT, Unit.INSTANCE);
  }

  /** Creates a {@code word} literal. */
  public Core.Literal wordLiteral(BigDecimal value) {
    return new Core.Literal(Op.WORD_LITERAL, PrimitiveType.WORD, value);
  }

  /** Creates a function literal. */
  public Core.Literal functionLiteral(Type type, BuiltIn builtIn) {
    return new Core.Literal(Op.FN_LITERAL, type, builtIn);
  }

  /** Creates a function literal, deducing its type. */
  public Core.Literal functionLiteral(TypeSystem typeSystem, BuiltIn builtIn) {
    final Type type = builtIn.typeFunction.apply(typeSystem);
    return functionLiteral(type, builtIn);
  }

  /** Creates a function literal, possibly overloaded. */
  public Core.Literal functionLiteral(
      TypeSystem typeSystem, BuiltIn builtIn, List<Core.Exp> argList, Pos pos) {
    Type type = builtIn.typeFunction.apply(typeSystem);
    if (type instanceof MultiType) {
      final Type arg0Type =
          argList.size() == 1
              ? argList.get(0).type
              : typeSystem.tupleType(transform(argList, Core.Exp::type));
      final List<Type> applicableTypes =
          filterEager(((MultiType) type).types, t -> t.canCallArgOf(arg0Type));
      if (applicableTypes.isEmpty()) {
        // No instance of the overloaded name takes this argument type. The
        // user is at fault; "from i in [1] compute sum" gets here, because
        // nothing says what is summed and the argument stays a type variable.
        throw new CompileException(
            format(
                "operator '%s' not defined for type '%s'",
                builtIn.mlName, arg0Type),
            false,
            pos);
      }
      checkArgument(
          applicableTypes.size() == 1,
          "expected one overload for arguments %s, got %s %s",
          argList,
          applicableTypes.size(),
          applicableTypes);
      type = applicableTypes.get(0);
    }
    return functionLiteral(type, builtIn);
  }

  /** Creates a value literal. */
  public Core.Literal valueLiteral(Core.Exp exp, Object value) {
    return new Core.Literal(
        Op.VALUE_LITERAL, exp.type, Core.Literal.wrap(exp, value));
  }

  /** Creates an internal literal. */
  public Core.Literal internalLiteral(Object value) {
    final Core.Literal exp = unitLiteral();
    return new Core.Literal(
        Op.INTERNAL_LITERAL, exp.type, Core.Literal.wrap(exp, value));
  }

  /** Wraps an expression in "DESC". */
  public Core.Exp desc(TypeSystem typeSystem, Core.Exp exp) {
    Core.Id desc = constructor(typeSystem, BuiltIn.Constructor.DESCENDING_DESC);
    return apply(Pos.ZERO, desc.type, desc, exp);
  }

  /**
   * Creates a reference to a value, at a given position. Use this where the
   * reference comes from the user's source, so that an error about it can say
   * where it is.
   */
  public Core.Id id(Pos pos, Core.NamedPat idPat) {
    return new Core.Id(pos, idPat);
  }

  /** Creates a reference to a value that the compiler synthesized. */
  public Core.Id id(Core.NamedPat idPat) {
    return new Core.Id(Pos.ZERO, idPat);
  }

  /**
   * Returns an expression at a given position, if it is of a kind whose
   * position can be moved; otherwise returns it unchanged.
   *
   * <p>Use this where one expression is substituted for another, so that an
   * error is blamed on the occurrence that was replaced rather than on wherever
   * the replacement happened to be built. {@code from i in xs order i} becomes
   * an order over the id, and it is the {@code i} the user wrote that
   * "comparison not defined" should point at.
   */
  public Core.Exp at(Core.Exp exp, Pos pos) {
    if (pos.equals(Pos.ZERO) || pos.equals(exp.pos)) {
      return exp;
    }
    switch (exp.op) {
      case ID:
        return id(pos, ((Core.Id) exp).idPat);
      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        return apply(pos, apply.type, apply.fn, apply.arg);
      default:
        return exp;
    }
  }

  /** Creates a reference to a constructor. */
  public Core.Id constructor(
      TypeSystem typeSystem, BuiltIn.Constructor constructor) {
    Type type = typeSystem.lookup(constructor.datatype);
    DataType dataType = (DataType) typeSystem.unqualified(type);
    Type x =
        requireNonNull(
            dataType.typeConstructors(typeSystem).get(constructor.constructor),
            constructor.constructor);
    Core.IdPat idPat = idPat(x, constructor.constructor, 0);
    return new Core.Id(Pos.ZERO, idPat);
  }

  public Core.RecordSelector recordSelector(
      TypeSystem typeSystem, RecordLikeType recordType, String fieldName) {
    final @Nullable TypedValue typedValue = recordType.asTypedValue();
    if (typedValue != null) {
      TypedValue typedValue2 = typedValue.discoverField(typeSystem, fieldName);
      recordType = (RecordLikeType) typedValue2.typeKey().toType(typeSystem);
    }
    int slot = 0;
    for (Map.Entry<String, Type> pair : recordType.argNameTypes().entrySet()) {
      if (pair.getKey().equals(fieldName)) {
        final Type fieldType = pair.getValue();
        final FnType fnType = typeSystem.fnType(recordType, fieldType);
        return recordSelector(fnType, slot);
      }
      ++slot;
    }

    throw new IllegalArgumentException(
        "no field '" + fieldName + "' in type '" + recordType + "'");
  }

  public Core.RecordSelector recordSelector(
      TypeSystem typeSystem, RecordLikeType recordType, int slot) {
    final Type fieldType = recordType.argType(slot);
    final FnType fnType = typeSystem.fnType(recordType, fieldType);
    return recordSelector(fnType, slot);
  }

  public Core.RecordSelector recordSelector(FnType fnType, int slot) {
    return new Core.RecordSelector(fnType, slot);
  }

  /**
   * Creates an IdPat with a given name and ordinal. You must ensure that the
   * ordinal is unique for this name in this program.
   */
  public Core.IdPat idPat(Type type, String name, int i) {
    return new Core.IdPat(type, name, i);
  }

  /** Creates an IdPat with a system-generated unique name. */
  public Core.IdPat idPat(Type type, Supplier<String> nameGenerator) {
    return idPat(type, nameGenerator.get(), 0);
  }

  /**
   * Creates an IdPat with a given name, generating an ordinal to distinguish it
   * from other declarations with the same name elsewhere in the program.
   */
  public Core.IdPat idPat(
      Type type, String name, ToIntFunction<String> nameGenerator) {
    return idPat(type, name, nameGenerator.applyAsInt(name));
  }

  @SuppressWarnings("rawtypes")
  public Core.LiteralPat literalPat(Op op, Type type, Comparable value) {
    return new Core.LiteralPat(op, type, value);
  }

  public Core.WildcardPat wildcardPat(Type type) {
    return new Core.WildcardPat(type);
  }

  public Core.AsPat asPat(Type type, String name, int i, Core.Pat pat) {
    return new Core.AsPat(type, name, i, pat);
  }

  /**
   * Creates an AsPat with a given name, generating an ordinal to distinguish it
   * from other declarations with the same name elsewhere in the program.
   */
  public Core.AsPat asPat(
      Type type, String name, NameGenerator nameGenerator, Core.Pat pat) {
    return asPat(type, name, nameGenerator.inc(name), pat);
  }

  public Core.ConPat consPat(Type type, String tyCon, Core.Pat pat) {
    return new Core.ConPat(Op.CONS_PAT, type, tyCon, pat);
  }

  public Core.ConPat conPat(Type type, String tyCon, Core.Pat pat) {
    return new Core.ConPat(type, tyCon, pat);
  }

  public Core.Con0Pat con0Pat(DataType type, String tyCon) {
    return new Core.Con0Pat(type, tyCon);
  }

  public Core.TuplePat tuplePat(
      RecordLikeType type, Iterable<? extends Core.Pat> args) {
    return new Core.TuplePat(type, ImmutableList.copyOf(args));
  }

  public Core.TuplePat tuplePat(RecordLikeType type, Core.Pat... args) {
    return new Core.TuplePat(type, ImmutableList.copyOf(args));
  }

  public Core.TuplePat tuplePat(
      TypeSystem typeSystem, List<? extends Core.Pat> args) {
    RecordLikeType tupleType =
        typeSystem.tupleType(transform(args, Core.Pat::type));
    return tuplePat(tupleType, args);
  }

  public Core.ListPat listPat(Type type, Iterable<? extends Core.Pat> args) {
    return new Core.ListPat(type, ImmutableList.copyOf(args));
  }

  public Core.ListPat listPat(Type type, Core.Pat... args) {
    return new Core.ListPat(type, ImmutableList.copyOf(args));
  }

  public Core.ListPat listPat(TypeSystem typeSystem, List<Core.Pat> args) {
    // We assume that there is at least one pattern, and that all patterns
    // have the same type. There will be at least one pattern when this method
    // is called from ListPat.copy.
    return listPat(typeSystem.listType(args.get(0).type), args);
  }

  public Core.RecordPat recordPat(
      RecordType type, Iterable<? extends Core.Pat> args) {
    return new Core.RecordPat(type, ImmutableList.copyOf(args));
  }

  public Core.Pat recordPat(
      TypeSystem typeSystem, Map<String, Core.Pat> namePats) {
    // Ensure sorted. If the names need to be permuted, apply the same
    // permutation to patterns and types.
    final ImmutableSortedMap<String, Core.Pat> sortedNamePats =
        ImmutableSortedMap.copyOf(namePats, ORDERING);
    final RecordLikeType recordType =
        typeSystem.recordType(transformValues(sortedNamePats, Core.Pat::type));
    return recordPat((RecordType) recordType, sortedNamePats.values());
  }

  /**
   * Creates a pattern for a list of named patterns.
   *
   * <p>If the list has one element, returns that element. Otherwise, returns a
   * record pattern with the patterns as fields.
   */
  public Core.Pat recordOrAtomPat(
      TypeSystem typeSystem, List<? extends Core.NamedPat> pats) {
    if (pats.size() == 1) {
      return pats.get(0);
    }
    return recordPat(
        typeSystem, transformToMap(pats, (p, c) -> c.accept(p.name, p)));
  }

  public Core.Tuple tuple(
      RecordLikeType type, Iterable<? extends Core.Exp> args) {
    return new Core.Tuple(type, ImmutableList.copyOf(args));
  }

  public Core.Tuple tuple(RecordLikeType type, Core.Exp... args) {
    return new Core.Tuple(type, ImmutableList.copyOf(args));
  }

  public Core.Tuple tuple(TypeSystem typeSystem, Core.Exp... args) {
    return tuple(typeSystem, null, ImmutableList.copyOf(args));
  }

  /**
   * As {@link #tuple(RecordLikeType, Iterable)}, but derives type.
   *
   * <p>If present, {@code type} serves as a template, dictating whether to
   * produce a record or a tuple type, and if a record type, the field names. If
   * not present, the result is a tuple type.
   */
  public Core.Tuple tuple(
      TypeSystem typeSystem,
      @Nullable RecordLikeType type,
      Iterable<? extends Core.Exp> args) {
    final ImmutableList<Core.Exp> argList = ImmutableList.copyOf(args);
    final RecordLikeType tupleType;
    if (type instanceof RecordType) {
      tupleType =
          typeSystem.recordType(
              zip(type.argNames(), transform(argList, Core.Exp::type)));
    } else {
      tupleType = typeSystem.tupleType(transform(argList, Core.Exp::type));
    }
    return new Core.Tuple(tupleType, argList);
  }

  public Core.Let let(Core.ValDecl decl, Core.Exp exp) {
    return new Core.Let(decl, exp);
  }

  public Core.Local local(DataType dataType, Core.Exp exp) {
    return new Core.Local(dataType, exp);
  }

  public Core.NonRecValDecl nonRecValDecl(
      Pos pos,
      Core.NamedPat pat,
      Core.@Nullable IdPat overloadPat,
      Core.Exp exp) {
    return new Core.NonRecValDecl(pat, exp, overloadPat, pos);
  }

  public Core.RecValDecl recValDecl(
      Iterable<? extends Core.NonRecValDecl> list) {
    return new Core.RecValDecl(ImmutableList.copyOf(list));
  }

  public Core.Match match(Pos pos, Core.Pat pat, Core.Exp exp) {
    return new Core.Match(pos, pat, exp);
  }

  public Core.Case caseOf(
      Pos pos,
      Type type,
      Core.Exp exp,
      Iterable<? extends Core.Match> matchList) {
    return new Core.Case(pos, type, exp, ImmutableList.copyOf(matchList));
  }

  /** Creates a builder that builds a query, step by step, as a tree. */
  public FromBuilder fromBuilder(TypeSystem typeSystem) {
    return new FromBuilder(typeSystem);
  }

  public Core.Fn fn(FnType type, Core.IdPat idPat, Core.Exp exp) {
    return new Core.Fn(exp.pos, type, idPat, exp);
  }

  public Core.Fn fn(
      Pos pos,
      FnType type,
      List<Core.Match> matchList,
      ToIntFunction<String> nameGenerator) {
    if (matchList.size() == 1) {
      final Core.Match match = matchList.get(0);
      if (match.pat instanceof Core.IdPat) {
        // Simple function, "fn x => exp". Does not need 'case'.
        return new Core.Fn(pos, type, (Core.IdPat) match.pat, match.exp);
      }
      if (match.pat instanceof Core.TuplePat
          && ((Core.TuplePat) match.pat).args.isEmpty()) {
        // Simple function with unit arg, "fn () => exp";
        // needs a new variable, but doesn't need case, "fn (v0: unit) => exp"
        final Core.IdPat idPat = idPat(type.paramType, "v", nameGenerator);
        return new Core.Fn(pos, type, idPat, match.exp);
      }
    }
    // Complex function, "fn (x, y) => exp";
    // needs intermediate variable and case, "fn v => case v of (x, y) => exp"
    final Core.IdPat idPat = idPat(type.paramType, "v", nameGenerator);
    final Core.Id id = id(idPat);
    return new Core.Fn(
        pos, type, idPat, caseOf(pos, type.resultType, id, matchList));
  }

  /** Creates a {@link Core.Apply}. */
  public Core.Apply apply(Pos pos, Type type, Core.Exp fn, Core.Exp arg) {
    return new Core.Apply(pos, type, fn, arg);
  }

  /**
   * Creates a {@link Core.Apply}, assuming that the expression has a function
   * type.
   */
  public Core.Apply apply(Pos pos, Core.Exp fn, Core.Exp arg) {
    final FnType fnType = (FnType) fn.type;
    return apply(pos, fnType.resultType, fn, arg);
  }

  public Core.Case ifThenElse(
      Core.Exp condition, Core.Exp ifTrue, Core.Exp ifFalse) {
    // Translate "if c then a else b"
    // as if user had written "case c of true => a | _ => b".
    // Pos.ZERO is ok because match failure is impossible.
    final Pos pos = Pos.ZERO;
    return new Core.Case(
        pos,
        ifTrue.type,
        condition,
        ImmutableList.of(
            match(pos, truePat, ifTrue), match(pos, boolWildcardPat, ifFalse)));
  }

  public Core.Raise raise(Pos pos, Type type, Core.Exp exp) {
    return new Core.Raise(pos, type, exp);
  }

  public Core.OverDecl overDecl(Core.IdPat pat) {
    return new Core.OverDecl(pat);
  }

  public Core.TypeDecl typeDecl(Iterable<AliasType> types) {
    return new Core.TypeDecl(ImmutableList.copyOf(types));
  }

  public Core.DatatypeDecl datatypeDecl(Iterable<DataType> dataTypes) {
    return new Core.DatatypeDecl(ImmutableList.copyOf(dataTypes));
  }

  public Core.Aggregate aggregate(
      Pos pos, Type type, Core.Exp aggregate, Core.@Nullable Exp argument) {
    return new Core.Aggregate(pos, type, aggregate, argument);
  }

  // Relational tree (Core.Rel) nodes.

  /**
   * Creates the id by which a node refers to the current element of its input,
   * {@code $0}. The ordinal makes it distinct from every other node's, and
   * comes from the counter that numbers generated binders, so that the printer
   * can name the pattern {@code v$}-ordinal outside its node without naming a
   * binder.
   */
  public Core.IdPat elementPat(Type elementType, ToIntFunction<String> gen) {
    return idPat(elementType, "$0", gen.applyAsInt("v$"));
  }

  /**
   * Creates the id by which a node refers to the current element of its input,
   * named after the binder whose collection it is, {@code $e} for {@code from e
   * in emps}; see {@link Core.NamedPat#idName}.
   *
   * <p>The ordinal comes from the counter for the binder's name, so the id and
   * a variable of that name never print alike. A binder called {@code ordinal}
   * would make the id indistinguishable from an ordinal id, so its id is {@code
   * $0}.
   */
  public Core.IdPat elementPat(
      Type elementType, String name, ToIntFunction<String> gen) {
    if (name.equals("ordinal")) {
      return elementPat(elementType, gen);
    }
    return idPat(elementType, "$" + name, gen.applyAsInt(name));
  }

  /**
   * Creates the id by which a join refers to the current element of its right
   * input, {@code $1}.
   */
  public Core.IdPat rightElementPat(
      Type elementType, ToIntFunction<String> gen) {
    return idPat(elementType, "$1", gen.applyAsInt("v$"));
  }

  /**
   * Creates the id by which a node refers to the position of the current
   * element of its input, {@code $ordinal}.
   */
  public Core.IdPat ordinalPat(ToIntFunction<String> gen) {
    return idPat(PrimitiveType.INT, "$ordinal", gen.applyAsInt("v$"));
  }

  /**
   * Creates a collection type: a {@code list} if {@code ordered}, otherwise a
   * {@code bag}.
   */
  public Type collectionType(
      TypeSystem typeSystem, boolean ordered, Type elementType) {
    return ordered
        ? typeSystem.listType(elementType)
        : typeSystem.bagType(elementType);
  }

  /**
   * Creates a filter; its condition is a {@code bool} expression over {@code
   * $0}.
   */
  public Core.Filter filter(
      Core.IdPat id,
      Core.@Nullable IdPat ordinalId,
      Core.Exp input,
      Core.Exp condition) {
    return filter(Pos.ZERO, id, ordinalId, input, condition);
  }

  public Core.Filter filter(
      Pos pos,
      Core.IdPat id,
      Core.@Nullable IdPat ordinalId,
      Core.Exp input,
      Core.Exp condition) {
    checkCollection(input);
    checkBoolType(condition, "filter condition");
    return new Core.Filter(pos, id, ordinalId, input, condition);
  }

  /**
   * Creates a projection; the element type is the type of {@code exp}, an
   * expression over {@code $0}, and the kind is that of the input.
   */
  public Core.Project project(
      TypeSystem typeSystem,
      Core.IdPat id,
      Core.@Nullable IdPat ordinalId,
      Core.Exp input,
      Core.Exp exp) {
    return project(Pos.ZERO, typeSystem, id, ordinalId, input, exp);
  }

  public Core.Project project(
      Pos pos,
      TypeSystem typeSystem,
      Core.IdPat id,
      Core.@Nullable IdPat ordinalId,
      Core.Exp input,
      Core.Exp exp) {
    checkCollection(input);
    final Type type = collectionType(typeSystem, isOrdered(input), exp.type);
    return new Core.Project(pos, type, id, ordinalId, input, exp);
  }

  /** Creates an inner join. */
  public Core.Join join(
      TypeSystem typeSystem,
      Core.IdPat leftId,
      Core.IdPat rightId,
      Core.Exp left,
      Core.Exp right,
      Core.Exp condition) {
    return join(Pos.ZERO, typeSystem, leftId, rightId, left, right, condition);
  }

  public Core.Join join(
      Pos pos,
      TypeSystem typeSystem,
      Core.IdPat leftId,
      Core.IdPat rightId,
      Core.Exp left,
      Core.Exp right,
      Core.Exp condition) {
    return join(
        pos,
        typeSystem,
        JoinType.INNER,
        leftId,
        rightId,
        null,
        left,
        right,
        condition);
  }

  /**
   * Creates a join that may be dependent: {@code leftId} is in scope in {@code
   * right}, and a right input that reads it makes the join dependent, which is
   * how a scan whose collection reads an earlier binder is expressed.
   */
  public Core.Join join(
      TypeSystem typeSystem,
      JoinType joinType,
      Core.IdPat leftId,
      Core.IdPat rightId,
      Core.@Nullable IdPat ordinalId,
      Core.Exp left,
      Core.Exp right,
      Core.Exp condition) {
    return join(
        Pos.ZERO,
        typeSystem,
        joinType,
        leftId,
        rightId,
        ordinalId,
        left,
        right,
        condition);
  }

  public Core.Join join(
      Pos pos,
      TypeSystem typeSystem,
      JoinType joinType,
      Core.IdPat leftId,
      Core.IdPat rightId,
      Core.@Nullable IdPat ordinalId,
      Core.Exp left,
      Core.Exp right,
      Core.Exp condition) {
    checkCollection(left);
    checkCollection(right);
    checkBoolType(condition, "join condition");
    final boolean ordered = isOrdered(left) && isOrdered(right);
    final Type type =
        collectionType(
            typeSystem,
            ordered,
            joinElementType(typeSystem, joinType, left, right));
    return new Core.Join(
        pos, type, joinType, leftId, rightId, ordinalId, left, right,
        condition);
  }

  /**
   * Returns expressions for a node's components, read out of an expression for
   * its element: a join contributes its own, anything else contributes one.
   */
  public List<Core.Exp> components(
      TypeSystem typeSystem, Core.Exp node, Core.Exp exp) {
    final Core.@Nullable Exp input = elementPreserved(node);
    if (input != null) {
      return components(typeSystem, input, exp);
    }
    if (!(node instanceof Core.Join)) {
      return ImmutableList.of(exp);
    }
    final int n = componentCount(node);
    if (exp instanceof Core.Tuple && ((Core.Tuple) exp).args.size() == n) {
      // The element is already a tuple of the components, so read them off
      // it rather than projecting each out of it. `#1 (a, b)` is `a`, and
      // leaving it unreduced hands whatever reads it a projection of a
      // construction, which Calcite cannot resolve as a bare `#1`.
      return ((Core.Tuple) exp).args;
    }
    final List<Core.Exp> exps = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      exps.add(field(typeSystem, exp, i));
    }
    return exps;
  }

  /** Returns how many components a node's element has. */
  public int componentCount(Core.Exp node) {
    final Core.@Nullable Exp input = elementPreserved(node);
    if (input != null) {
      return componentCount(input);
    }
    if (node instanceof Core.Join) {
      final Core.Join join = (Core.Join) node;
      return componentCount(join.left) + componentCount(join.right);
    }
    return 1;
  }

  /**
   * Returns the input whose components a node passes through, or null if the
   * node's element is its own.
   *
   * <p>A filter emits its input's elements unchanged, so its element *is* its
   * input's element and its components are its input's components. Saying
   * otherwise -- one component, because the node is not a join -- is invisible
   * until something removes the filter: {@code join(filter(join(a, b)), c)}
   * would have element {@code ((a, b), c)} where {@code join(join(a, b), c)}
   * has {@code (a, b, c)}. The rules remove and reorder filters constantly, so
   * this has to be the node's own answer rather than a caller's care. The same
   * holds of every node that changes which elements there are, or in what
   * order, but not what an element is.
   */
  private static Core.@Nullable Exp elementPreserved(Core.Exp node) {
    switch (node.op) {
      case FILTER:
      case SORT:
      case UNORDER:
      case SKIP:
      case TAKE:
        return ((Core.SingleRel) node).input;
      default:
        return null;
    }
  }

  /**
   * Replaces the reads of a pattern in an expression with an expression, which
   * takes the position of each read.
   */
  public Core.Exp substitute(
      TypeSystem typeSystem, Core.Exp exp, Core.NamedPat pat, Core.Exp e0) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Id id) {
            return id.idPat.equals(pat) ? at(e0, id.pos) : id;
          }
        });
  }

  /**
   * Returns the element type of a join: its inputs' components, in order.
   *
   * <p>An outer join wraps each component of the absent side in {@code option},
   * one option per component, which is Morel's own rule: {@code left join (j,
   * k) in pairs} binds {@code j : int option} and {@code k : int option}, not
   * {@code (int * int) option}. A join above one wraps them again, and wrapping
   * is additive, which is where {@code int option option} comes from when two
   * outer joins chain.
   */
  public Type joinElementType(
      TypeSystem typeSystem, JoinType joinType, Core.Exp left, Core.Exp right) {
    final List<Type> types = new ArrayList<>();
    componentTypes(left)
        .forEach(
            t -> types.add(joinType.leftIsOption() ? typeSystem.option(t) : t));
    componentTypes(right)
        .forEach(
            t ->
                types.add(joinType.rightIsOption() ? typeSystem.option(t) : t));
    return typeSystem.tupleType(types);
  }

  /**
   * Returns the component types of a node's element, read off a join's own
   * element rather than recomputed from its inputs, so that an outer join's
   * option-wrapping is not applied twice.
   */
  public List<Type> componentTypes(Core.Exp node) {
    final Core.@Nullable Exp input = elementPreserved(node);
    if (input != null) {
      return componentTypes(input);
    }
    if (node instanceof Core.Join) {
      return ImmutableList.copyOf(
          ((RecordLikeType) node.type.elementType()).argTypes());
    }
    return ImmutableList.of(node.type.elementType());
  }

  /**
   * Creates a {@code group}; the element type is a record of the keys and
   * aggregates.
   */
  public Core.Group group(
      TypeSystem typeSystem,
      Core.IdPat id,
      Core.@Nullable IdPat ordinalId,
      Core.Exp input,
      SortedMap<String, Core.Exp> keys,
      SortedMap<String, Core.Aggregate> aggregates) {
    return group(Pos.ZERO, typeSystem, id, ordinalId, input, keys, aggregates);
  }

  public Core.Group group(
      Pos pos,
      TypeSystem typeSystem,
      Core.IdPat id,
      Core.@Nullable IdPat ordinalId,
      Core.Exp input,
      SortedMap<String, Core.Exp> keys,
      SortedMap<String, Core.Aggregate> aggregates) {
    checkCollection(input);
    final Map<String, Type> nameTypes = new LinkedHashMap<>();
    keys.forEach((name, exp) -> nameTypes.put(name, exp.type));
    aggregates.forEach((name, agg) -> nameTypes.put(name, agg.type));
    if (nameTypes.size() < keys.size() + aggregates.size()) {
      throw new IllegalArgumentException(
          format(
              "duplicate label in group keys %s and aggregates %s",
              keys.keySet(), aggregates.keySet()));
    }
    // A record whether there is one label or many. Collapsing a one-label
    // element to its bare type would make the element's *shape* depend on how
    // many labels there are, so a rule that drops one of two labels would
    // change the shape and everything above it would have to rewrite. Where
    // the query wants the bare value, an ordinary projection of the field
    // says so, and a projection is a node a rule can see.
    final Type elementType =
        typeSystem.recordType(ImmutableSortedMap.copyOf(nameTypes, ORDERING));
    final Type type = collectionType(typeSystem, isOrdered(input), elementType);
    return new Core.Group(
        pos,
        type,
        id,
        ordinalId,
        input,
        ImmutableSortedMap.copyOf(keys, ORDERING),
        ImmutableSortedMap.copyOf(aggregates, ORDERING));
  }

  /** Creates a {@code sort}; the output is always a {@code list}. */
  public Core.Sort sort(
      TypeSystem typeSystem,
      Core.IdPat id,
      Core.@Nullable IdPat ordinalId,
      Core.Exp input,
      Core.Exp exp) {
    return sort(Pos.ZERO, typeSystem, id, ordinalId, input, exp);
  }

  public Core.Sort sort(
      Pos pos,
      TypeSystem typeSystem,
      Core.IdPat id,
      Core.@Nullable IdPat ordinalId,
      Core.Exp input,
      Core.Exp exp) {
    checkCollection(input);
    final Type type = typeSystem.listType(input.type.elementType());
    return new Core.Sort(pos, type, id, ordinalId, input, exp);
  }

  /** Creates an {@code unorder}; the output is always a {@code bag}. */
  public Core.Unorder unorder(TypeSystem typeSystem, Core.Exp input) {
    return unorder(Pos.ZERO, typeSystem, input);
  }

  public Core.Unorder unorder(Pos pos, TypeSystem typeSystem, Core.Exp input) {
    checkCollection(input);
    final Type type = typeSystem.bagType(input.type.elementType());
    return new Core.Unorder(pos, type, input);
  }

  /**
   * Creates a {@code skip}; its count is evaluated before the first element
   * exists, and therefore cannot mention {@code $0}.
   */
  public Core.Skip skip(Core.Exp input, Core.Exp count) {
    return skip(Pos.ZERO, input, count);
  }

  public Core.Skip skip(Pos pos, Core.Exp input, Core.Exp count) {
    checkCollection(input);
    return new Core.Skip(pos, input, count);
  }

  /**
   * Creates a {@code take}; its count is evaluated before the first element
   * exists, and therefore cannot mention {@code $0}.
   */
  public Core.Take take(Core.Exp input, Core.Exp count) {
    return take(Pos.ZERO, input, count);
  }

  public Core.Take take(Pos pos, Core.Exp input, Core.Exp count) {
    checkCollection(input);
    return new Core.Take(pos, input, count);
  }

  /** Creates a {@code union}. */
  public Core.Union union(
      TypeSystem typeSystem,
      boolean distinct,
      Iterable<? extends Core.Exp> inputs) {
    return union(Pos.ZERO, typeSystem, distinct, inputs);
  }

  public Core.Union union(
      Pos pos,
      TypeSystem typeSystem,
      boolean distinct,
      Iterable<? extends Core.Exp> inputs) {
    final ImmutableList<Core.Exp> inputList = ImmutableList.copyOf(inputs);
    return new Core.Union(
        pos, setRelType(typeSystem, inputList), distinct, inputList);
  }

  /** Creates an {@code intersect}. */
  public Core.Intersect intersect(
      TypeSystem typeSystem,
      boolean distinct,
      Iterable<? extends Core.Exp> inputs) {
    return intersect(Pos.ZERO, typeSystem, distinct, inputs);
  }

  public Core.Intersect intersect(
      Pos pos,
      TypeSystem typeSystem,
      boolean distinct,
      Iterable<? extends Core.Exp> inputs) {
    final ImmutableList<Core.Exp> inputList = ImmutableList.copyOf(inputs);
    return new Core.Intersect(
        pos, setRelType(typeSystem, inputList), distinct, inputList);
  }

  /** Creates an {@code except}. */
  public Core.Except except(
      TypeSystem typeSystem,
      boolean distinct,
      Iterable<? extends Core.Exp> inputs) {
    return except(Pos.ZERO, typeSystem, distinct, inputs);
  }

  public Core.Except except(
      Pos pos,
      TypeSystem typeSystem,
      boolean distinct,
      Iterable<? extends Core.Exp> inputs) {
    final ImmutableList<Core.Exp> inputList = ImmutableList.copyOf(inputs);
    return new Core.Except(
        pos, setRelType(typeSystem, inputList), distinct, inputList);
  }

  /**
   * Returns the type of a set operator: all inputs must have the same element
   * type, and the output is ordered only if every input is ordered.
   */
  private Type setRelType(
      TypeSystem typeSystem, List<? extends Core.Exp> inputs) {
    inputs.forEach(CoreBuilder::checkCollection);
    final Type elementType = inputs.get(0).type.elementType();
    boolean ordered = true;
    for (Core.Exp input : inputs) {
      if (!input.type.elementType().equals(elementType)) {
        throw new IllegalArgumentException(
            format(
                "set operator inputs have different element types: %s, %s",
                elementType, input.type.elementType()));
      }
      ordered = ordered && isOrdered(input);
    }
    return collectionType(typeSystem, ordered, elementType);
  }

  /** Returns whether an expression's collection type is a {@code list}. */
  private static boolean isOrdered(Core.Exp exp) {
    return exp.type instanceof ListType;
  }

  private static void checkCollection(Core.Exp exp) {
    if (!exp.type.isCollection()) {
      throw new IllegalArgumentException(
          "input must be list or bag: " + exp.type);
    }
  }

  private static void checkBoolType(Core.Exp exp, String what) {
    if (exp.type != PrimitiveType.BOOL) {
      throw new IllegalArgumentException(
          format("%s must be bool: %s", what, exp.type));
    }
  }

  // Shorthands

  /**
   * Creates a reference to the {@code slot}th field of an expression, "{@code
   * #slot exp}". The expression's type must be record or tuple.
   */
  public Core.Exp field(TypeSystem typeSystem, Core.Exp exp, int slot) {
    final Core.RecordSelector selector =
        recordSelector(typeSystem, (RecordLikeType) exp.type, slot);
    return apply(exp.pos, selector.type().resultType, selector, exp);
  }

  /** Creates a list. */
  public Core.Exp list(
      TypeSystem typeSystem, Type elementType, List<? extends Core.Exp> args) {
    final Core.Literal literal = functionLiteral(typeSystem, BuiltIn.Z_LIST);
    final ListType listType = typeSystem.listType(elementType);
    return apply(
        Pos.ZERO, listType, literal, core.tuple(typeSystem, null, args));
  }

  /** Creates a list with one or more elements. */
  public Core.Exp list(TypeSystem typeSystem, Core.Exp arg0, Core.Exp... args) {
    return list(typeSystem, arg0.type, Lists.asList(arg0, args));
  }

  /** Creates a bag. */
  public Core.Exp bag(
      TypeSystem typeSystem, Type elementType, List<Core.Exp> args) {
    final Core.Exp list = list(typeSystem, elementType, args);
    final Core.Literal literal =
        functionLiteral(typeSystem, BuiltIn.BAG_FROM_LIST);
    final Type bagType = typeSystem.bagType(elementType);
    return apply(Pos.ZERO, bagType, literal, list);
  }

  /** Creates a bag with one or more elements. */
  public Core.Exp bag(TypeSystem typeSystem, Core.Exp arg0, Core.Exp... args) {
    return bag(typeSystem, arg0.type, Lists.asList(arg0, args));
  }

  /** Converts a collection to ordered (list) or unordered (bag). */
  public Core.Exp withOrdered(
      boolean ordered, Core.Exp collection, TypeSystem typeSystem) {
    if (ordered == (collection.type instanceof ListType)) {
      return collection;
    }
    final Type elementType = collection.type.elementType();
    final BuiltIn builtIn;
    final Type collectionType;
    if (ordered) {
      builtIn = BuiltIn.BAG_TO_LIST;
      collectionType = typeSystem.listType(elementType);
    } else {
      builtIn = BuiltIn.BAG_FROM_LIST;
      collectionType = typeSystem.bagType(elementType);
    }
    return core.apply(
        Pos.ZERO,
        collectionType,
        core.functionLiteral(typeSystem, builtIn),
        collection);
  }

  /**
   * Creates an extent. It returns a list of all values of a given type that
   * fall into a given range-set. The range-set might consist of just {@link
   * Range#all()}, in which case, the list returns all values of the type.
   *
   * <p>The position is attached to the resulting expression so that downstream
   * errors (e.g. "pattern is not grounded") can point back to the original
   * {@code from} pattern; pass {@link Pos#ZERO} for compiler-synthesized
   * extents that have no source location.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public Core.Exp extent(
      Pos pos, TypeSystem typeSystem, Type type, RangeSet rangeSet) {
    return extent(pos, typeSystem, type, rangeSet, ImmutableList.of());
  }

  /**
   * As {@link #extent(Pos, TypeSystem, Type, RangeSet)}, with the names the
   * query bound to the values, which a diagnostic quotes.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public Core.Exp extent(
      Pos pos,
      TypeSystem typeSystem,
      Type type,
      RangeSet rangeSet,
      List<String> names) {
    final Map<String, ImmutableRangeSet> map;
    if (rangeSet.complement().isEmpty()) {
      map = ImmutableMap.of();
    } else {
      map = ImmutableMap.of("/", ImmutableRangeSet.copyOf(rangeSet));
    }
    return extent(pos, typeSystem, type, map, names);
  }

  /**
   * As {@link #extent(Pos, TypeSystem, Type, RangeSet)}, with a range-set map.
   */
  @SuppressWarnings("rawtypes")
  public Core.Exp extent(
      Pos pos,
      TypeSystem typeSystem,
      Type type,
      Map<String, ImmutableRangeSet> rangeSetMap) {
    return extent(pos, typeSystem, type, rangeSetMap, ImmutableList.of());
  }

  /**
   * As {@link #extent(Pos, TypeSystem, Type, RangeSet, List)}, with a range-set
   * map.
   */
  @SuppressWarnings("rawtypes")
  public Core.Exp extent(
      Pos pos,
      TypeSystem typeSystem,
      Type type,
      Map<String, ImmutableRangeSet> rangeSetMap,
      List<String> names) {
    // An extent yields its values in the natural order of the type, so it is
    // a list; 'Z_EXTENT' is declared to return one.
    final Type listType = typeSystem.listType(type);
    // Store an ImmutableRangeSet value inside a literal of type 'unit'.
    // The value of such literals is usually Unit.INSTANCE, but we cheat.
    return core.apply(
        pos,
        listType,
        core.functionLiteral(listType, BuiltIn.Z_EXTENT),
        core.internalLiteral(
            new RangeExtent(typeSystem, type, rangeSetMap, names)));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public Pair<Core.Exp, List<Core.Exp>> intersectExtents(
      TypeSystem typeSystem, List<? extends Core.Exp> exps) {
    switch (exps.size()) {
      case 0:
        throw new AssertionError();

      case 1:
        return Pair.of(simplify(typeSystem, exps.get(0)), ImmutableList.of());

      default:
        final List<Map<String, ImmutableRangeSet>> rangeSetMaps =
            new ArrayList<>();
        final List<Core.Exp> remainingExps = new ArrayList<>();
        for (Core.Exp exp : exps) {
          if (exp.isExtent()) {
            final RangeExtent list = exp.getRangeExtent();
            rangeSetMaps.add(list.rangeSetMap);
            continue;
          }
          remainingExps.add(exp);
        }
        final Type listType = exps.get(0).type;
        Map<String, ImmutableRangeSet> rangeSetMap =
            Extents.intersect((List) rangeSetMaps);
        Core.Exp exp =
            core.listIntersect(
                typeSystem,
                plus(
                    core.extent(
                        Pos.ZERO,
                        typeSystem,
                        listType.elementType(),
                        rangeSetMap),
                    remainingExps));
        return Pair.of(exp, remainingExps);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public Pair<Core.Exp, List<Core.Exp>> unionExtents(
      TypeSystem typeSystem, List<? extends Core.Exp> exps) {
    switch (exps.size()) {
      case 0:
        throw new AssertionError();

      case 1:
        return Pair.of(simplify(typeSystem, exps.get(0)), ImmutableList.of());

      default:
        final List<Map<String, ImmutableRangeSet>> rangeSetMaps =
            new ArrayList<>();
        final List<Core.Exp> remainingExps = new ArrayList<>();
        for (Core.Exp exp : exps) {
          if (exp.isExtent()) {
            rangeSetMaps.add(exp.getRangeExtent().rangeSetMap);
            continue;
          }
          remainingExps.add(exp);
        }
        final Type listType = exps.get(0).type;
        Map<String, ImmutableRangeSet> rangeSetMap =
            Extents.union((List) rangeSetMaps);
        Core.Exp exp =
            core.listConcat(
                typeSystem,
                plus(
                    core.extent(
                        Pos.ZERO,
                        typeSystem,
                        listType.elementType(),
                        rangeSetMap),
                    remainingExps));
        return Pair.of(exp, remainingExps);
    }
  }

  /**
   * Simplifies an expression.
   *
   * <p>In particular, it merges extents. For example,
   *
   * <pre>{@code
   * extent "[10, 20]" orelse (extent "[-inf,5]" andalso "[1,int]")
   * }</pre>
   *
   * <p>becomes
   *
   * <pre>{@code
   * extent "[[1, 5], [10, 20]]"
   * }</pre>
   */
  public Core.Exp simplify(TypeSystem typeSystem, Core.Exp exp) {
    switch (exp.op) {
      case TUPLE:
        final Core.Tuple tuple = (Core.Tuple) exp;
        final List<Core.Exp> simplifiedArgs =
            transformEager(tuple.args, e -> simplify(typeSystem, e));
        final Core.Exp simplified = simplifyTuple(simplifiedArgs, tuple.type());
        if (simplified != null) {
          return simplified;
        }
        return tuple.copy(typeSystem, simplifiedArgs);

      case APPLY:
        Core.Apply apply = (Core.Apply) exp;
        final Core.Exp simplifiedArg = simplify(typeSystem, apply.arg);
        if (!simplifiedArg.equals(apply.arg)) {
          apply = apply.copy(apply.fn, simplifiedArg);
        }
        if (apply.isCallTo(BuiltIn.LIST_CONCAT)
            && apply.arg.isCallTo(BuiltIn.Z_LIST)) {
          final Core.Apply apply2 = (Core.Apply) apply.arg;
          if (allMatch(apply2.args(), Core.Exp::isExtent)) {
            Pair<Core.Exp, List<Core.Exp>> pair =
                unionExtents(typeSystem, apply2.args());
            if (pair.right.isEmpty()) {
              return pair.left;
            }
          }
        }
        if (apply.isCallTo(BuiltIn.BAG_CONCAT)
            && apply.arg.isCallTo(BuiltIn.BAG_FROM_LIST)
            && ((Core.Apply) apply.arg).arg.isCallTo(BuiltIn.Z_LIST)) {
          final Core.Apply apply1 = (Core.Apply) apply.arg;
          final Core.Apply apply2 = (Core.Apply) apply1.arg;
          if (allMatch(apply2.args(), Core.Exp::isExtent)) {
            Pair<Core.Exp, List<Core.Exp>> pair =
                unionExtents(typeSystem, apply2.args());
            if (pair.right.isEmpty()) {
              return pair.left;
            }
          }
        }
        if (apply.isCallTo(BuiltIn.LIST_INTERSECT)
            && apply.arg.isCallTo(BuiltIn.Z_LIST)) {
          final Core.Apply apply2 = (Core.Apply) apply.arg;
          if (allMatch(apply2.args(), Core.Exp::isExtent)) {
            Pair<Core.Exp, List<Core.Exp>> pair =
                intersectExtents(typeSystem, apply2.args());
            if (pair.right.isEmpty()) {
              return pair.left;
            }
          }
        }
        return apply;
    }
    return exp;
  }

  /** Creates a record from a collection of named expressions. */
  public Core.Exp record(
      TypeSystem typeSystem,
      Collection<? extends Map.Entry<String, ? extends Core.Exp>> nameExps) {
    // Ensure sorted. If the names need to be permuted, apply the same
    // permutation to patterns and types.
    final SortedMap<String, Core.Exp> sortedNameExps =
        ImmutableSortedMap.copyOf(nameExps, ORDERING);
    final RecordLikeType recordType =
        typeSystem.recordType(transformValues(sortedNameExps, Core.Exp::type));
    return tuple(typeSystem, recordType, sortedNameExps.values());
  }

  /** Creates a record whose fields are the values of a list of bindings. */
  public Core.Exp record(TypeSystem typeSystem, List<Binding> bindings) {
    return record(
        typeSystem,
        fromTransformed(bindings, (b, c) -> c.accept(b.id.name, id(b.id))));
  }

  /**
   * Creates an expression for a list of named patterns.
   *
   * <p>If the list has one element, returns an id expression for that pattern.
   * Otherwise, returns a record expression with the patterns as fields.
   */
  public Core.Exp recordOrAtom(
      TypeSystem typeSystem, List<? extends Core.NamedPat> pats) {
    if (pats.size() == 1) {
      return id(pats.get(0));
    }
    return record(
        typeSystem, fromTransformed(pats, (p, c) -> c.accept(p.name, id(p))));
  }

  /** Calls a built-in function. */
  public Core.Apply call(
      TypeSystem typeSystem, BuiltIn builtIn, Core.Exp... args) {
    final Core.Literal literal = functionLiteral(typeSystem, builtIn);
    final FnType fnType = (FnType) literal.type;
    return apply(
        Pos.ZERO, fnType.resultType, literal, args(fnType.paramType, args));
  }

  /** Calls a built-in function with one type parameter. */
  public Core.Apply call(
      TypeSystem typeSystem,
      BuiltIn builtIn,
      Type type,
      Pos pos,
      Core.Exp... args) {
    final Core.Literal forallLiteral =
        functionLiteral(typeSystem, builtIn, ImmutableList.copyOf(args), pos);
    final ForallType forallType = (ForallType) forallLiteral.type;
    final FnType fnType = (FnType) typeSystem.apply(forallType, type);
    // Use the concrete FnType for the literal so that Typed.withType can
    // extract the element type at compile time (e.g. Range functions).
    final Core.Literal literal = functionLiteral(fnType, builtIn);
    return apply(pos, fnType.resultType, literal, args(fnType.paramType, args));
  }

  private Core.Exp args(Type paramType, Core.Exp[] args) {
    return args.length == 1 ? args[0] : tuple((TupleType) paramType, args);
  }

  public Core.Exp equal(TypeSystem typeSystem, Core.Exp a0, Core.Exp a1) {
    return call(typeSystem, BuiltIn.OP_EQ, a0.type, Pos.ZERO, a0, a1);
  }

  public Core.Exp notEqual(TypeSystem typeSystem, Core.Exp a0, Core.Exp a1) {
    return call(typeSystem, BuiltIn.OP_NE, a0.type, Pos.ZERO, a0, a1);
  }

  public Core.Exp lessThan(TypeSystem typeSystem, Core.Exp a0, Core.Exp a1) {
    return call(typeSystem, BuiltIn.OP_LT, a0.type, Pos.ZERO, a0, a1);
  }

  public Core.Exp greaterThan(TypeSystem typeSystem, Core.Exp a0, Core.Exp a1) {
    return call(typeSystem, BuiltIn.OP_GT, a0.type, Pos.ZERO, a0, a1);
  }

  public Core.Exp greaterThanOrEqualTo(
      TypeSystem typeSystem, Core.Exp a0, Core.Exp a1) {
    return call(typeSystem, BuiltIn.OP_GE, a0.type, Pos.ZERO, a0, a1);
  }

  public Core.Exp elem(TypeSystem typeSystem, Core.Exp a0, Core.Exp a1) {
    if (a1.isCallTo(BuiltIn.Z_LIST) && ((Core.Apply) a1).args().size() == 1) {
      // If "a1 = [x]", rather than "a0 elem [x]", generate "a0 = x"
      return equal(typeSystem, a0, ((Core.Apply) a1).args().get(0));
    }
    return call(typeSystem, BuiltIn.OP_ELEM, a0.type, Pos.ZERO, a0, a1);
  }

  public Core.Exp not(TypeSystem typeSystem, Core.Exp a0) {
    Core.Literal not = functionLiteral(typeSystem, BuiltIn.BOOL_NOT);
    return apply(a0.pos, PrimitiveType.BOOL, not, a0);
  }

  public Core.Exp andAlso(TypeSystem typeSystem, Core.Exp a0, Core.Exp a1) {
    return call(typeSystem, BuiltIn.Z_ANDALSO, a0, a1);
  }

  /**
   * Converts a list of 0 or more expressions into an {@code andalso};
   * simplifies empty list to "true" and singleton list "[e]" to "e".
   */
  public Core.Exp andAlso(TypeSystem typeSystem, Iterable<Core.Exp> exps) {
    final List<Core.Exp> expList = new ArrayList<>();
    flattenAnds(exps, expList::add);
    if (expList.isEmpty()) {
      return trueLiteral;
    }
    return foldRight(expList, (e1, e2) -> andAlso(typeSystem, e1, e2));
  }

  public Core.Exp orElse(TypeSystem typeSystem, Core.Exp a0, Core.Exp a1) {
    return call(typeSystem, BuiltIn.Z_ORELSE, a0, a1);
  }

  /**
   * Converts a list of 0 or more expressions into an {@code orelse}; simplifies
   * empty list to "false" and singleton list "[e]" to "e".
   */
  public Core.Exp orElse(TypeSystem typeSystem, Iterable<Core.Exp> exps) {
    final List<Core.Exp> expList = new ArrayList<>();
    flattenOrs(exps, expList::add);
    if (expList.isEmpty()) {
      return falseLiteral;
    }
    return foldRight(expList, (e1, e2) -> orElse(typeSystem, e1, e2));
  }

  private <E> E foldRight(List<E> list, BiFunction<E, E, E> fold) {
    E e = list.get(list.size() - 1);
    for (int i = list.size() - 2; i >= 0; i--) {
      e = fold.apply(list.get(i), e);
    }
    return e;
  }

  public Core.Exp only(TypeSystem typeSystem, Pos pos, Core.Exp a0) {
    return call(
        typeSystem, BuiltIn.RELATIONAL_ONLY, a0.type.elementType(), pos, a0);
  }

  public Core.Exp nonEmpty(TypeSystem typeSystem, Pos pos, Core.Exp a0) {
    return call(
        typeSystem, BuiltIn.RELATIONAL_NON_EMPTY, PrimitiveType.BOOL, pos, a0);
  }

  public Core.Exp empty(TypeSystem typeSystem, Pos pos, Core.Exp a0) {
    return call(
        typeSystem, BuiltIn.RELATIONAL_EMPTY, PrimitiveType.BOOL, pos, a0);
  }

  /**
   * Creates a list concatenation expression.
   *
   * <p>For example, {@code listConcat([1, 2, 3], [3, 4])} becomes {@code
   * List.concat [[1, 2, 3], [3, 4]]}. Note that this has a single argument, and
   * that argument is a call to {@link BuiltIn#Z_LIST}.
   */
  public Core.Exp listConcat(TypeSystem typeSystem, List<Core.Exp> args) {
    switch (args.size()) {
      case 0:
        throw new IllegalArgumentException("union of nothing");
      case 1:
        return args.get(0);
    }
    final Type listType = args.get(0).type;
    final ListType listListType = typeSystem.listType(listType);

    // Make a call 'list (arg0, arg1, ...)'
    final Core.Literal listFnLiteral =
        functionLiteral(typeSystem, BuiltIn.Z_LIST);
    Core.Tuple tuple = core.tuple(typeSystem, null, args);
    Core.Apply arg = apply(Pos.ZERO, listListType, listFnLiteral, tuple);

    // Make a call 'List.concat [arg0, arg1, ...]'
    final Core.Literal concatFnLiteral =
        functionLiteral(typeSystem, BuiltIn.LIST_CONCAT);
    return apply(Pos.ZERO, listType, concatFnLiteral, arg);
  }

  /**
   * Creates a list intersection expression.
   *
   * <p>For example, {@code intersect([1, 2], [3, 4])} becomes {@code
   * List.intersect [[1, 2], [3, 4]]}. Note that this has a single argument, and
   * that argument is a call to {@link BuiltIn#Z_LIST}.
   */
  public Core.Exp listIntersect(TypeSystem typeSystem, List<Core.Exp> args) {
    switch (args.size()) {
      case 0:
        throw new IllegalArgumentException("intersection of nothing");
      case 1:
        return args.get(0);
    }
    final Type listType = args.get(0).type;
    final ListType listListType = typeSystem.listType(listType);

    // Make a call 'list (arg0, arg1, ...)'
    final Core.Literal listFnLiteral =
        functionLiteral(typeSystem, BuiltIn.Z_LIST);
    Core.Tuple tuple = core.tuple(typeSystem, null, args);
    Core.Apply arg = apply(Pos.ZERO, listListType, listFnLiteral, tuple);

    // Make a call 'List.concat [arg0, arg1, ...]'
    final Core.Literal concatFnLiteral =
        functionLiteral(typeSystem, BuiltIn.LIST_CONCAT);
    return apply(Pos.ZERO, listType, concatFnLiteral, arg);
  }

  /**
   * Decomposes an {@code andalso} expression; inverse of {@link
   * #andAlso(TypeSystem, Iterable)}.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>"p1 andalso p2" becomes "[p1, p2]" (two elements);
   *   <li>"p1 andalso p2 andalso p3" becomes "[p1, p2, p3]" (three elements);
   *   <li>"p1 orelse p2" becomes "[p1 orelse p2]" (one element);
   *   <li>"true" becomes "[]" (no elements);
   *   <li>"false" becomes "[false]" (one element).
   * </ul>
   */
  public List<Core.Exp> decomposeAnd(Core.Exp exp) {
    final ImmutableList.Builder<Core.Exp> list = ImmutableList.builder();
    flattenAnd(exp, list::add);
    return list.build();
  }

  /**
   * Decomposes an {@code orelse} expression; inverse of {@link
   * #orElse(TypeSystem, Iterable)}.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>"p1 orelse p2" becomes "[p1, p2]" (two elements);
   *   <li>"p1 orelse p2 orelse p3" becomes "[p1, p2, p3]" (three elements);
   *   <li>"p1 andalso p2" becomes "[p1 andalso p2]" (one element);
   *   <li>"false" becomes "[]" (no elements);
   *   <li>"true" becomes "[true]" (one element).
   * </ul>
   */
  public List<Core.Exp> decomposeOr(Core.Exp exp) {
    final ImmutableList.Builder<Core.Exp> list = ImmutableList.builder();
    flattenOr(exp, list::add);
    return list.build();
  }

  /** Flattens the {@code andalso}s in an expression into a consumer. */
  public void flattenAnd(Core.Exp exp, Consumer<Core.Exp> consumer) {
    //noinspection StatementWithEmptyBody
    if (exp.isBoolLiteral(true)) {
      // don't add 'true' to the list
    } else if (exp.op == Op.APPLY
        && ((Core.Apply) exp).fn.op == Op.FN_LITERAL
        && ((Core.Literal) ((Core.Apply) exp).fn).value == BuiltIn.Z_ANDALSO) {
      flattenAnds(((Core.Apply) exp).args(), consumer);
    } else {
      consumer.accept(exp);
    }
  }

  /** Flattens the {@code andalso}s in every expression into a consumer. */
  public void flattenAnds(
      Iterable<? extends Core.Exp> exps, Consumer<Core.Exp> consumer) {
    exps.forEach(arg -> flattenAnd(arg, consumer));
  }

  /** Flattens the {@code orelse}s in an expression into a consumer. */
  public void flattenOr(Core.Exp exp, Consumer<Core.Exp> consumer) {
    //noinspection StatementWithEmptyBody
    if (exp.isBoolLiteral(false)) {
      // don't add 'false' to the list
    } else if (exp.op == Op.APPLY
        && ((Core.Apply) exp).fn.op == Op.FN_LITERAL
        && ((Core.Literal) ((Core.Apply) exp).fn).value == BuiltIn.Z_ORELSE) {
      flattenOrs(((Core.Apply) exp).args(), consumer);
    } else {
      consumer.accept(exp);
    }
  }

  /** Flattens the {@code orelse}s in every expression into a consumer. */
  public void flattenOrs(
      Iterable<? extends Core.Exp> exps, Consumer<Core.Exp> consumer) {
    exps.forEach(arg -> flattenOr(arg, consumer));
  }

  /**
   * Converts an expression to the equivalent pattern.
   *
   * <p>For example, converts {@code id(x)} to {@code idPat(x)}, and {@code
   * tuple(id(x), id(y))} to {@code tuplePat(idPat(x), idPat(y))}.
   *
   * @see Op#toPat()
   */
  public Core.Pat toPat(Core.Exp exp) {
    switch (exp.op) {
      case ID:
        return ((Core.Id) exp).idPat;

      case TUPLE:
        final Core.Tuple tuple = (Core.Tuple) exp;
        if (tuple.type.op() == Op.RECORD_TYPE) {
          return recordPat(
              (RecordType) tuple.type(),
              transformEager(tuple.args, this::toPat));
        } else {
          return tuplePat(
              tuple.type(), transformEager(tuple.args, this::toPat));
        }

      case BOOL_LITERAL:
      case CHAR_LITERAL:
      case INT_LITERAL:
      case REAL_LITERAL:
      case STRING_LITERAL:
      case UNIT_LITERAL:
        final Core.Literal literal = (Core.Literal) exp;
        return literalPat(exp.op.toPat(), exp.type, literal.value);

      case APPLY:
        // Handle field access like #1 p or #x p
        // Create a fresh pattern that matches the field value
        final Core.Apply apply = (Core.Apply) exp;
        if (apply.fn.op == Op.RECORD_SELECTOR) {
          final Core.RecordSelector selector = (Core.RecordSelector) apply.fn;
          // Use field name as variable name prefix (e.g., "x" for #x, "f1" for
          // #1)
          final String fieldName = selector.fieldName();
          final String varName =
              Character.isDigit(fieldName.charAt(0))
                  ? "f" + fieldName
                  : fieldName;
          return idPat(exp.type, varName, 0);
        }
        // For other applies, create a fresh pattern
        return idPat(exp.type, "v", 0);

      default:
        throw new AssertionError("cannot convert " + exp + " to pattern");
    }
  }

  /**
   * Simplifies a tuple expression like {@code (#1 x, #2 x, ...)} to {@code x}
   * if the tuple references all fields of {@code x} in order.
   *
   * @param args the tuple arguments (already simplified)
   * @param recordLikeType the type of the tuple
   * @return the simplified expression, or null if no simplification is possible
   */
  public Core.@Nullable Exp simplifyTuple(
      List<Core.Exp> args, RecordLikeType recordLikeType) {
    if (args.isEmpty()) {
      // Cannot simplify unit, "()".
      return null;
    }
    if (args.size() != recordLikeType.argTypes().size()) {
      // Cannot simplify "(#1 x, #2 x)" to "x" if x has 3 or more fields.
      return null;
    }
    Core.Exp arg = null;
    for (int i = 0; i < args.size(); i++) {
      final Core.Exp a = args.get(i);
      if (!(a instanceof Core.Apply)) {
        return null;
      }
      final Core.Apply apply = (Core.Apply) a;
      if (!(apply.fn instanceof Core.RecordSelector)) {
        return null;
      }
      final Core.RecordSelector recordSelector = (Core.RecordSelector) apply.fn;
      if (recordSelector.slot != i) {
        // Field access is not in order, e.g. "(#2 x, #1 x)".
        return null;
      }
      if (arg == null) {
        arg = apply.arg;
      } else if (!arg.equals(apply.arg)) {
        // Arguments are not the same, e.g. "(#1 x, #2 y)".
        return null;
      }
    }
    return arg;
  }
}

// End CoreBuilder.java
