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
import static net.hydromatic.morel.type.RecordType.ORDERING;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.allMatch;
import static net.hydromatic.morel.util.Static.filterEager;
import static net.hydromatic.morel.util.Static.last;
import static net.hydromatic.morel.util.Static.plus;
import static net.hydromatic.morel.util.Static.transform;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Environment;
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
import net.hydromatic.morel.util.PairList;
import org.checkerframework.checker.nullness.qual.Nullable;

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

  /** Creates a {@code float} literal. */
  public Core.Literal realLiteral(BigDecimal value) {
    return new Core.Literal(Op.REAL_LITERAL, PrimitiveType.REAL, value);
  }

  /** Creates a {@code float} literal with a non-normal value. */
  public Core.Literal realLiteral(Float value) {
    return new Core.Literal(Op.REAL_LITERAL, PrimitiveType.REAL, value);
  }

  /** Creates a string literal. */
  public Core.Literal stringLiteral(String value) {
    return new Core.Literal(Op.STRING_LITERAL, PrimitiveType.STRING, value);
  }

  /** Creates a unit literal. */
  public Core.Literal unitLiteral() {
    return new Core.Literal(Op.UNIT_LITERAL, PrimitiveType.UNIT, Unit.INSTANCE);
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
      TypeSystem typeSystem, BuiltIn builtIn, List<Core.Exp> argList) {
    Type type = builtIn.typeFunction.apply(typeSystem);
    if (type instanceof MultiType) {
      final Type arg0Type =
          argList.size() == 1
              ? argList.get(0).type
              : typeSystem.tupleType(transform(argList, Core.Exp::type));
      final List<Type> applicableTypes =
          filterEager(((MultiType) type).types, t -> t.canCallArgOf(arg0Type));
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

  /** Creates a reference to a value. */
  public Core.Id id(Core.NamedPat idPat) {
    return new Core.Id(idPat);
  }

  /** Creates a reference to a constructor. */
  public Core.Id constructor(
      TypeSystem typeSystem, BuiltIn.Constructor constructor) {
    Type type = typeSystem.lookup(constructor.datatype);
    DataType dataType = (DataType) typeSystem.unqualified(type);
    Type x = dataType.typeConstructors(typeSystem).get(constructor.constructor);
    Core.IdPat idPat = idPat(x, constructor.constructor, 0);
    return new Core.Id(idPat);
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

  public Core.TuplePat tuplePat(TypeSystem typeSystem, List<Core.Pat> args) {
    return tuplePat(
        typeSystem.tupleType(transform(args, Core.Pat::type)), args);
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
      RecordType type, List<? extends Core.Pat> args) {
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
    return recordPat(
        (RecordType) recordType, ImmutableList.copyOf(sortedNamePats.values()));
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
      final PairList<String, Type> argNameTypes = PairList.of();
      forEach(
          type.argNames(),
          argList,
          (name, arg) -> argNameTypes.add(name, arg.type));
      tupleType = typeSystem.recordType(argNameTypes);
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

  public Core.From from(Type type, List<Core.FromStep> steps) {
    return new Core.From(type, ImmutableList.copyOf(steps));
  }

  /** Derives the result type, then calls {@link #from(Type, List)}. */
  public Core.From from(TypeSystem typeSystem, List<Core.FromStep> steps) {
    final Type elementType = fromElementType(typeSystem, steps);
    final Type collectionType;
    if (fromOrdered(steps)) {
      collectionType = typeSystem.listType(elementType);
    } else {
      collectionType = typeSystem.bagType(elementType);
    }
    return from(collectionType, steps);
  }

  /**
   * Returns the datatype of an element of a {@link Core.From} with the given
   * steps.
   */
  private Type fromElementType(
      TypeSystem typeSystem, List<Core.FromStep> steps) {
    final Core.StepEnv lastStep = lastEnv(steps);
    final PairList<String, Type> argNameTypes = PairList.of();
    lastStep.bindings.forEach(b -> argNameTypes.add(b.id.name, b.id.type));
    if (lastStep.atom) {
      checkArgument(argNameTypes.size() == 1);
      return argNameTypes.right(0);
    }
    return typeSystem.recordType(argNameTypes);
  }

  /**
   * Returns whether the output of the last of a sequence of steps is ordered.
   */
  static boolean fromOrdered(List<Core.FromStep> steps) {
    boolean ordered = true;
    for (Core.FromStep step : steps) {
      ordered = step.isOrdered(ordered);
      checkArgument(
          ordered == step.env.ordered,
          "unexpected ordered [%s] in step [%s]",
          step.env.ordered,
          step);
    }
    return ordered;
  }

  /**
   * Returns what would be the yield expression if we created a {@link
   * Core.From} from the given steps.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code implicitYieldExp(steps=[scan(a=E:t)])} is {@code a} (a {@link
   *       Core.Id});
   *   <li>{@code implicitYieldExp(steps=[scan(a=E:t), scan(b=E2:t2)])} is
   *       {@code {a = a, b = b}} (a record).
   * </ul>
   */
  public Core.Exp implicitYieldExp(
      TypeSystem typeSystem, List<Core.FromStep> steps) {
    final Core.StepEnv lastEnv = lastEnv(steps);
    if (lastEnv.bindings.size() == 1) {
      return id(lastEnv.bindings.get(0).id);
    } else {
      final SortedMap<Core.NamedPat, Core.Exp> map = new TreeMap<>();
      final PairList<String, Type> argNameTypes = PairList.of();
      lastEnv.bindings.forEach(
          b -> {
            map.put(b.id, id(b.id));
            argNameTypes.add(b.id.name, b.id.type);
          });
      return tuple(typeSystem.recordType(argNameTypes), map.values());
    }
  }

  public Core.StepEnv lastEnv(List<? extends Core.FromStep> steps) {
    return steps.isEmpty() ? Core.StepEnv.EMPTY : last(steps).env;
  }

  /** Creates a builder that will create a {@link Core.From}. */
  public FromBuilder fromBuilder(
      TypeSystem typeSystem, @Nullable Supplier<Environment> envSupplier) {
    return new FromBuilder(typeSystem, envSupplier);
  }

  /**
   * Creates a builder that will create a {@link Core.From} and validates if
   * {@code env} is not null.
   */
  public FromBuilder fromBuilder(
      TypeSystem typeSystem, @Nullable Environment env) {
    final Supplier<Environment> envSupplier = env == null ? null : () -> env;
    return fromBuilder(typeSystem, envSupplier);
  }

  /**
   * Creates a builder that will create a {@link Core.From} but does not
   * validate.
   */
  public FromBuilder fromBuilder(TypeSystem typeSystem) {
    return fromBuilder(typeSystem, (Supplier<Environment>) null);
  }

  public Core.Fn fn(FnType type, Core.IdPat idPat, Core.Exp exp) {
    return new Core.Fn(type, idPat, exp);
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
        return fn(type, (Core.IdPat) match.pat, match.exp);
      }
      if (match.pat instanceof Core.TuplePat
          && ((Core.TuplePat) match.pat).args.isEmpty()) {
        // Simple function with unit arg, "fn () => exp";
        // needs a new variable, but doesn't need case, "fn (v0: unit) => exp"
        final Core.IdPat idPat = idPat(type.paramType, "v", nameGenerator);
        return fn(type, idPat, match.exp);
      }
    }
    // Complex function, "fn (x, y) => exp";
    // needs intermediate variable and case, "fn v => case v of (x, y) => exp"
    final Core.IdPat idPat = idPat(type.paramType, "v", nameGenerator);
    final Core.Id id = id(idPat);
    return fn(type, idPat, caseOf(pos, type.resultType, id, matchList));
  }

  /** Creates a {@link Core.Apply}. */
  public Core.Apply apply(Pos pos, Type type, Core.Exp fn, Core.Exp arg) {
    return new Core.Apply(pos, type, fn, arg);
  }

  /**
   * Creates a {@link Core.Apply} with two or more arguments, packing the
   * arguments into a tuple.
   */
  public Core.Apply apply(
      Pos pos,
      TypeSystem typeSystem,
      BuiltIn builtIn,
      Core.Exp arg0,
      Core.Exp arg1,
      Core.Exp... args) {
    final Core.Literal fn = functionLiteral(typeSystem, builtIn);
    FnType fnType = (FnType) fn.type;
    TupleType tupleType = (TupleType) fnType.paramType;
    return apply(
        pos,
        fnType.resultType,
        fn,
        tuple(tupleType, Lists.asList(arg0, arg1, args)));
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

  public Core.OverDecl overDecl(Core.IdPat pat) {
    return new Core.OverDecl(pat);
  }

  public Core.TypeDecl typeDecl(Iterable<AliasType> types) {
    return new Core.TypeDecl(ImmutableList.copyOf(types));
  }

  public Core.DatatypeDecl datatypeDecl(Iterable<DataType> dataTypes) {
    return new Core.DatatypeDecl(ImmutableList.copyOf(dataTypes));
  }

  public Core.Scan scan(
      Core.StepEnv env, Core.Pat pat, Core.Exp exp, Core.Exp condition) {
    env = env.withOrdered(env.ordered && exp.type instanceof ListType);
    return new Core.Scan(env, pat, exp, condition);
  }

  public Core.Aggregate aggregate(
      Type type, Core.Exp aggregate, Core.@Nullable Exp argument) {
    return new Core.Aggregate(type, aggregate, argument);
  }

  public Core.Order order(Core.StepEnv env, Core.Exp exp) {
    return new Core.Order(env.withOrdered(true), exp);
  }

  public Core.Group group(
      boolean atom,
      boolean ordered,
      SortedMap<Core.IdPat, Core.Exp> groupExps,
      SortedMap<Core.IdPat, Core.Aggregate> aggregates) {
    final List<Binding> bindings = new ArrayList<>();
    groupExps.keySet().forEach(id -> bindings.add(Binding.of(id)));
    aggregates.keySet().forEach(id -> bindings.add(Binding.of(id)));
    checkArgument(
        !atom || bindings.size() == 1,
        "atom with %s bindings %s",
        bindings.size(),
        bindings);
    return new Core.Group(
        Core.StepEnv.of(bindings, atom, ordered),
        ImmutableSortedMap.copyOfSorted(groupExps),
        ImmutableSortedMap.copyOfSorted(aggregates));
  }

  public Core.Where where(Core.StepEnv env, Core.Exp exp) {
    return new Core.Where(env, exp);
  }

  public Core.Skip skip(Core.StepEnv env, Core.Exp exp) {
    return new Core.Skip(env, exp);
  }

  public Core.Take take(Core.StepEnv env, Core.Exp exp) {
    return new Core.Take(env, exp);
  }

  public Core.Except except(
      Core.StepEnv env, boolean distinct, Iterable<? extends Core.Exp> args) {
    return new Core.Except(env, distinct, ImmutableList.copyOf(args));
  }

  public Core.Intersect intersect(
      Core.StepEnv env, boolean distinct, Iterable<? extends Core.Exp> args) {
    return new Core.Intersect(env, distinct, ImmutableList.copyOf(args));
  }

  public Core.Union union(
      Core.StepEnv env, boolean distinct, Iterable<? extends Core.Exp> args) {
    return new Core.Union(env, distinct, ImmutableList.copyOf(args));
  }

  public Core.Unorder unorder(Core.StepEnv env) {
    return new Core.Unorder(env.withOrdered(false));
  }

  public Core.Yield yield_(Core.StepEnv env, Core.Exp exp) {
    return new Core.Yield(env, exp);
  }

  /** Derives bindings, then calls {@link #yield_(Core.StepEnv, Core.Exp)}. */
  public Core.Yield yield_(
      TypeSystem typeSystem, Core.Exp exp, boolean atom, boolean ordered) {
    final List<Core.NamedPat> idPats = new ArrayList<>();
    if (atom) {
      idPats.add(getIdPat(typeSystem, exp, null));
    } else if (exp.op == Op.TUPLE) {
      forEach(
          ((RecordLikeType) exp.type).argNames(),
          ((Core.Tuple) exp).args,
          (name, arg) -> idPats.add(getIdPat(typeSystem, arg, name)));
    } else {
      ((RecordLikeType) exp.type)
          .argNameTypes()
          .forEach(
              (name, type) ->
                  idPats.add(idPat(type, name, typeSystem.nameGenerator::inc)));
    }
    return yield_(
        Core.StepEnv.of(transform(idPats, Binding::of), atom, ordered), exp);
  }

  private Core.NamedPat getIdPat(
      TypeSystem typeSystem, Core.Exp exp, @Nullable String name) {
    if (exp instanceof Core.Id) {
      Core.Id id = (Core.Id) exp;
      if (name == null) {
        // There is no preferred name, so this id will do.
        return id.idPat;
      }
      if (id.idPat.name.equals(name)) {
        // Name is specified, which means that we are trying to generate an
        // IdPat from an assignment in a record constructor. If the left-hand
        // side matches the name (e.g. '{x = x}') we can use the IdPat from the
        // right side; but if it does not (e.g. '{y = x}') we cannot.
        //
        // It is better to use an existing IdPat, rather than generating a new
        // IdPat with a different sequence number. (The underlying problem,
        // which we should solve someday, is that the fields of record types
        // have only names, no sequence numbers.)
        return id.idPat;
      }
    }

    // If the expression is "#deptno e" (also written as "e.deptno"), use
    // "deptno" as the name.
    if (name == null && exp instanceof Core.Apply) {
      Core.Apply apply = (Core.Apply) exp;
      if (apply.fn instanceof Core.RecordSelector) {
        name = ((Core.RecordSelector) apply.fn).fieldName();
      }
    }

    if (name == null) {
      return idPat(exp.type, typeSystem.nameGenerator::get);
    } else {
      return idPat(exp.type, name, typeSystem.nameGenerator::inc);
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
      TypeSystem typeSystem, Type elementType, List<Core.Exp> args) {
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

  /**
   * Creates an extent. It returns a list of all values of a given type that
   * fall into a given range-set. The range-set might consist of just {@link
   * Range#all()}, in which case, the list returns all values of the type.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public Core.Exp extent(TypeSystem typeSystem, Type type, RangeSet rangeSet) {
    final Map<String, ImmutableRangeSet> map;
    if (rangeSet.complement().isEmpty()) {
      map = ImmutableMap.of();
    } else {
      map = ImmutableMap.of("/", ImmutableRangeSet.copyOf(rangeSet));
    }
    return extent(typeSystem, type, map);
  }

  @SuppressWarnings("rawtypes")
  public Core.Exp extent(
      TypeSystem typeSystem,
      Type type,
      Map<String, ImmutableRangeSet> rangeSetMap) {
    final Type bagType = typeSystem.bagType(type);
    // Store an ImmutableRangeSet value inside a literal of type 'unit'.
    // The value of such literals is usually Unit.INSTANCE, but we cheat.
    return core.apply(
        Pos.ZERO,
        bagType,
        core.functionLiteral(bagType, BuiltIn.Z_EXTENT),
        core.internalLiteral(new RangeExtent(typeSystem, type, rangeSetMap)));
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
          if (exp.isCallTo(BuiltIn.Z_EXTENT)) {
            final Core.Literal argLiteral =
                (Core.Literal) ((Core.Apply) exp).arg;
            final RangeExtent list = argLiteral.unwrap(RangeExtent.class);
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
                        typeSystem, listType.elementType(), rangeSetMap),
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
          if (exp.isCallTo(BuiltIn.Z_EXTENT)) {
            final Core.Literal argLiteral =
                (Core.Literal) ((Core.Apply) exp).arg;
            final Core.Wrapper wrapper = (Core.Wrapper) argLiteral.value;
            final RangeExtent list = wrapper.unwrap(RangeExtent.class);
            rangeSetMaps.add(list.rangeSetMap);
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
                        typeSystem, listType.elementType(), rangeSetMap),
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
        return tuple.copy(
            typeSystem, transform(tuple.args, e -> simplify(typeSystem, e)));

      case APPLY:
        Core.Apply apply = (Core.Apply) exp;
        final Core.Exp simplifiedArgs = simplify(typeSystem, apply.arg);
        if (!simplifiedArgs.equals(apply.arg)) {
          apply = apply.copy(apply.fn, simplifiedArgs);
        }
        if (apply.isCallTo(BuiltIn.LIST_CONCAT)
            && apply.arg.isCallTo(BuiltIn.Z_LIST)) {
          final Core.Apply apply2 = (Core.Apply) apply.arg;
          if (allMatch(
              apply2.args(), exp1 -> exp1.isCallTo(BuiltIn.Z_EXTENT))) {
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
          if (allMatch(
              apply2.args(), exp1 -> exp1.isCallTo(BuiltIn.Z_EXTENT))) {
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
          if (allMatch(
              apply2.args(), exp1 -> exp1.isCallTo(BuiltIn.Z_EXTENT))) {
            Pair<Core.Exp, List<Core.Exp>> pair =
                intersectExtents(typeSystem, apply2.args());
            if (pair.right.isEmpty()) {
              return pair.left;
            }
          }
        }
    }
    return exp;
  }

  /** Creates a record from a map of named expressions. */
  public Core.Exp record(
      TypeSystem typeSystem, Map<String, ? extends Core.Exp> nameExps) {
    return record_(
        typeSystem, ImmutableSortedMap.copyOf(nameExps, RecordType.ORDERING));
  }

  /** Creates a record from a collection of named expressions. */
  public Core.Exp record(
      TypeSystem typeSystem,
      Collection<? extends Map.Entry<String, ? extends Core.Exp>> nameExps) {
    return record_(
        typeSystem, ImmutableSortedMap.copyOf(nameExps, RecordType.ORDERING));
  }

  private Core.Tuple record_(
      TypeSystem typeSystem, ImmutableSortedMap<String, Core.Exp> nameExps) {
    final PairList<String, Type> argNameTypes = PairList.of();
    nameExps.forEach((name, exp) -> argNameTypes.add(name, exp.type));
    return tuple(
        typeSystem, typeSystem.recordType(argNameTypes), nameExps.values());
  }

  /** Calls a built-in function. */
  private Core.Apply call(
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
    final Core.Literal literal =
        functionLiteral(typeSystem, builtIn, ImmutableList.copyOf(args));
    final ForallType forallType = (ForallType) literal.type;
    final FnType fnType = (FnType) typeSystem.apply(forallType, type);
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
    Core.Literal not = functionLiteral(typeSystem, BuiltIn.NOT);
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
    final List<Core.Exp> expList = ImmutableList.copyOf(exps);
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
    final ImmutableList<Core.Exp> expList = ImmutableList.copyOf(exps);
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
   * Returns an expression substituting every given expression as true.
   *
   * <p>For example, if {@code exp} is "{@code x = 1 andalso y > 2}" and {@code
   * trueExps} is [{@code x = 1}, {@code z = 2}], returns "{@code y > 2}".
   */
  public Core.Exp subTrue(
      TypeSystem typeSystem, Core.Exp exp, List<Core.Exp> trueExps) {
    List<Core.Exp> conjunctions = decomposeAnd(exp);
    List<Core.Exp> conjunctions2 = new ArrayList<>();
    for (Core.Exp conjunction : conjunctions) {
      if (!trueExps.contains(conjunction)) {
        conjunctions2.add(conjunction);
      }
    }
    if (conjunctions.size() == conjunctions2.size()) {
      // Don't create a new expression unless we have to.
      return exp;
    }
    return andAlso(typeSystem, conjunctions2);
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
    if (exp.op == Op.BOOL_LITERAL && (boolean) ((Core.Literal) exp).value) {
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
  public void flattenAnds(List<Core.Exp> exps, Consumer<Core.Exp> consumer) {
    exps.forEach(arg -> flattenAnd(arg, consumer));
  }

  /** Flattens the {@code orelse}s in an expression into a consumer. */
  public void flattenOr(Core.Exp exp, Consumer<Core.Exp> consumer) {
    //noinspection StatementWithEmptyBody
    if (exp.op == Op.BOOL_LITERAL && !(boolean) ((Core.Literal) exp).value) {
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
  public void flattenOrs(List<Core.Exp> exps, Consumer<Core.Exp> consumer) {
    exps.forEach(arg -> flattenOr(arg, consumer));
  }
}

// End CoreBuilder.java
