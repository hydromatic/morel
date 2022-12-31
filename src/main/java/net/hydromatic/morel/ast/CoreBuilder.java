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
import net.hydromatic.morel.compile.NameGenerator;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.apache.calcite.util.Util;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static net.hydromatic.morel.type.RecordType.ORDERING;
import static net.hydromatic.morel.util.Pair.forEach;

import static com.google.common.collect.Iterables.getOnlyElement;

/** Builds parse tree nodes. */
public enum CoreBuilder {
  /** The singleton instance of the CORE builder.
   * The short name is convenient for use via 'import static',
   * but checkstyle does not approve. */
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
      return intLiteral(value instanceof BigDecimal ? (BigDecimal) value
          : BigDecimal.valueOf(((Number) value).longValue()));
    case REAL:
      if (value instanceof Float
          && ((Float) value).isNaN()) {
        return new Core.Literal(Op.REAL_LITERAL, PrimitiveType.REAL, (Float) value);
      }
      return realLiteral(value instanceof BigDecimal ? (BigDecimal) value
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
  public Core.Literal functionLiteral(TypeSystem typeSystem, BuiltIn builtIn) {
    final Type type = builtIn.typeFunction.apply(typeSystem);
    return new Core.Literal(Op.FN_LITERAL, type, builtIn);
  }

  /** Creates a value literal. */
  public Core.Literal valueLiteral(Core.Exp exp, Object value) {
    return new Core.Literal(Op.VALUE_LITERAL, exp.type,
        Core.Literal.wrap(exp, value));
  }

  /** Creates a reference to a value. */
  public Core.Id id(Core.NamedPat idPat) {
    return new Core.Id(idPat);
  }

  public Core.RecordSelector recordSelector(TypeSystem typeSystem,
      RecordLikeType recordType, String fieldName) {
    int slot = 0;
    for (Map.Entry<String, Type> pair : recordType.argNameTypes().entrySet()) {
      if (pair.getKey().equals(fieldName)) {
        final Type fieldType = pair.getValue();
        final FnType fnType = typeSystem.fnType(recordType, fieldType);
        return recordSelector(fnType, slot);
      }
      ++slot;
    }
    throw new IllegalArgumentException("no field '" + fieldName + "' in type '"
        + recordType + "'");
  }

  public Core.RecordSelector recordSelector(TypeSystem typeSystem,
      RecordLikeType recordType, int slot) {
    final Type fieldType = recordType.argType(slot);
    final FnType fnType = typeSystem.fnType(recordType, fieldType);
    return recordSelector(fnType, slot);
  }

  public Core.RecordSelector recordSelector(FnType fnType, int slot) {
    return new Core.RecordSelector(fnType, slot);
  }

  /** Creates an IdPat with a given name and ordinal. You must ensure that the
   * ordinal is unique for this name in this program. */
  public Core.IdPat idPat(Type type, String name, int i) {
    return new Core.IdPat(type, name, i);
  }

  /** Creates an IdPat with a system-generated unique name. */
  public Core.IdPat idPat(Type type, NameGenerator nameGenerator) {
    return idPat(type, nameGenerator.get(), 0);
  }

  /** Creates an IdPat with a given name, generating an ordinal to distinguish
   * it from other declarations with the same name elsewhere in the program. */
  public Core.IdPat idPat(Type type, String name, NameGenerator nameGenerator) {
    return idPat(type, name, nameGenerator.inc(name));
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

  /** Creates an AsPat with a given name, generating an ordinal to distinguish
   * it from other declarations with the same name elsewhere in the program. */
  public Core.AsPat asPat(Type type, String name, NameGenerator nameGenerator,
      Core.Pat pat) {
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

  public Core.TuplePat tuplePat(Type type, Iterable<? extends Core.Pat> args) {
    return new Core.TuplePat(type, ImmutableList.copyOf(args));
  }

  public Core.TuplePat tuplePat(Type type, Core.Pat... args) {
    return new Core.TuplePat(type, ImmutableList.copyOf(args));
  }

  public Core.TuplePat tuplePat(TypeSystem typeSystem, List<Core.Pat> args) {
    return tuplePat(typeSystem.tupleType(Util.transform(args, Core.Pat::type)),
        args);
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

  public Core.RecordPat recordPat(RecordType type,
      List<? extends Core.Pat> args) {
    return new Core.RecordPat(type, ImmutableList.copyOf(args));
  }

  public Core.Pat recordPat(TypeSystem typeSystem, Set<String> argNames,
      List<Core.Pat> args) {
    final ImmutableSortedMap.Builder<String, Type> argNameTypes =
        ImmutableSortedMap.orderedBy(ORDERING);
    forEach(argNames, args, (argName, arg) ->
        argNameTypes.put(argName, arg.type));
    return recordPat((RecordType) typeSystem.recordType(argNameTypes.build()),
        args);
  }

  public Core.Tuple tuple(RecordLikeType type, Iterable<? extends Core.Exp> args) {
    return new Core.Tuple(type, ImmutableList.copyOf(args));
  }

  public Core.Tuple tuple(RecordLikeType type, Core.Exp... args) {
    return new Core.Tuple(type, ImmutableList.copyOf(args));
  }

  public Core.Tuple tuple(TypeSystem typeSystem, Core.Exp... args) {
    return tuple(typeSystem, null, ImmutableList.copyOf(args));
  }

  /** As {@link #tuple(RecordLikeType, Iterable)}, but derives type.
   *
   * <p>If present, {@code type} serves as a template, dictating whether to
   * produce a record or a tuple type, and if a record type, the field names.
   * If not present, the result is a tuple type. */
  public Core.Tuple tuple(TypeSystem typeSystem, @Nullable RecordLikeType type,
      Iterable<? extends Core.Exp> args) {
    final ImmutableList<Core.Exp> argList = ImmutableList.copyOf(args);
    final RecordLikeType tupleType;
    if (type instanceof RecordType) {
      final SortedMap<String, Type> argNameTypes =
          new TreeMap<>(ORDERING);
      forEach(type.argNameTypes().keySet(), argList, (name, arg) ->
          argNameTypes.put(name, arg.type));
      tupleType = typeSystem.recordType(argNameTypes);
    } else {
      tupleType = typeSystem.tupleType(Util.transform(argList, Core.Exp::type));
    }
    return new Core.Tuple(tupleType, argList);
  }

  public Core.Let let(Core.ValDecl decl, Core.Exp exp) {
    return new Core.Let(decl, exp);
  }

  public Core.Local local(DataType dataType, Core.Exp exp) {
    return new Core.Local(dataType, exp);
  }

  public Core.NonRecValDecl nonRecValDecl(Pos pos, Core.NamedPat pat,
      Core.Exp exp) {
    return new Core.NonRecValDecl(pat, exp, pos);
  }

  public Core.RecValDecl recValDecl(
      Iterable<? extends Core.NonRecValDecl> list) {
    return new Core.RecValDecl(ImmutableList.copyOf(list));
  }

  public Core.Match match(Pos pos, Core.Pat pat, Core.Exp exp) {
    return new Core.Match(pos, pat, exp);
  }

  public Core.Case caseOf(Pos pos, Type type, Core.Exp exp,
      Iterable<? extends Core.Match> matchList) {
    return new Core.Case(pos, type, exp, ImmutableList.copyOf(matchList));
  }

  public Core.From from(ListType type, List<Core.FromStep> steps) {
    return new Core.From(type, ImmutableList.copyOf(steps));
  }

  /** Derives the result type, then calls
   * {@link #from(ListType, List)}. */
  public Core.From from(TypeSystem typeSystem, List<Core.FromStep> steps) {
    final Type elementType = fromElementType(typeSystem, steps);
    return from(typeSystem.listType(elementType), steps);
  }

  /** Returns the element type of a {@link Core.From} with the given steps. */
  static Type fromElementType(TypeSystem typeSystem,
      List<Core.FromStep> steps) {
    if (!steps.isEmpty()
        && Iterables.getLast(steps) instanceof Core.Yield) {
      return ((Core.Yield) Iterables.getLast(steps)).exp.type;
    } else {
      final List<Binding> lastBindings = core.lastBindings(steps);
      if (lastBindings.size() == 1) {
        return lastBindings.get(0).id.type;
      }
      final SortedMap<String, Type> argNameTypes = new TreeMap<>(ORDERING);
      lastBindings
          .forEach(b -> argNameTypes.put(b.id.name, b.id.type));
      return typeSystem.recordType(argNameTypes);
    }
  }

  /** Returns what would be the yield expression if we created a
   * {@link Core.From} from the given steps.
   *
   * <p>Examples:
   * <ul>
   * <li>{@code implicitYieldExp(steps=[scan(a=E:t)])}
   *     is {@code a} (a {@link Core.Id});
   * <li>{@code implicitYieldExp(steps=[scan(a=E:t), scan(b=E2:t2)])}
   *     is {@code {a = a, b = b}} (a record).
   * </ul> */
  public Core.Exp implicitYieldExp(TypeSystem typeSystem,
      List<Core.FromStep> steps) {
    final List<Binding> bindings = lastBindings(steps);
    if (bindings.size() == 1) {
      return core.id(getOnlyElement(bindings).id);
    } else {
      final SortedMap<Core.NamedPat, Core.Exp> map = new TreeMap<>();
      final SortedMap<String, Type> argNameTypes = new TreeMap<>(ORDERING);
      bindings.forEach(b -> {
        map.put(b.id, core.id(b.id));
        argNameTypes.put(b.id.name, b.id.type);
      });
      return core.tuple(typeSystem.recordType(argNameTypes), map.values());
    }
  }

  public List<Binding> lastBindings(List<? extends Core.FromStep> steps) {
    return steps.isEmpty()
        ? ImmutableList.of()
        : Iterables.getLast(steps).bindings;
  }

  /** Creates a builder that will create a {@link Core.From}. */
  public FromBuilder fromBuilder(TypeSystem typeSystem,
      @Nullable Environment env) {
    return new FromBuilder(typeSystem, env);
  }

  /** Creates a builder that will create a {@link Core.From} but does not
   * validate. */
  public FromBuilder fromBuilder(TypeSystem typeSystem) {
    return fromBuilder(typeSystem, null);
  }

  public Core.Fn fn(FnType type, Core.IdPat idPat, Core.Exp exp) {
    return new Core.Fn(type, idPat, exp);
  }

  /** Creates a {@link Core.Apply}. */
  public Core.Apply apply(Pos pos, Type type, Core.Exp fn, Core.Exp arg) {
    return new Core.Apply(pos, type, fn, arg);
  }

  /** Creates a {@link Core.Apply} with two or more arguments, packing the
   * arguments into a tuple. */
  public Core.Apply apply(Pos pos, TypeSystem typeSystem, BuiltIn builtIn,
      Core.Exp arg0, Core.Exp arg1, Core.Exp... args) {
    final Core.Literal fn = functionLiteral(typeSystem, builtIn);
    FnType fnType = (FnType) fn.type;
    TupleType tupleType = (TupleType) fnType.paramType;
    return apply(pos, fnType.resultType, fn,
        tuple(tupleType, Lists.asList(arg0, arg1, args)));
  }

  public Core.Case ifThenElse(Core.Exp condition, Core.Exp ifTrue,
      Core.Exp ifFalse) {
    // Translate "if c then a else b"
    // as if user had written "case c of true => a | _ => b".
    // Pos.ZERO is ok because match failure is impossible.
    final Pos pos = Pos.ZERO;
    return new Core.Case(pos, ifTrue.type, condition,
        ImmutableList.of(match(pos, truePat, ifTrue),
            match(pos, boolWildcardPat, ifFalse)));
  }

  public Core.DatatypeDecl datatypeDecl(Iterable<DataType> dataTypes) {
    return new Core.DatatypeDecl(ImmutableList.copyOf(dataTypes));
  }

  public Core.Scan scan(Op op, List<Binding> bindings, Core.Pat pat,
      Core.Exp exp, Core.Exp condition) {
    return new Core.Scan(op, ImmutableList.copyOf(bindings), pat, exp,
        condition);
  }

  public Core.Aggregate aggregate(Type type, Core.Exp aggregate,
      Core.@Nullable Exp argument) {
    return new Core.Aggregate(type, aggregate, argument);
  }

  public Core.Order order(List<Binding> bindings,
      Iterable<Core.OrderItem> orderItems) {
    return new Core.Order(ImmutableList.copyOf(bindings),
        ImmutableList.copyOf(orderItems));
  }

  public Core.OrderItem orderItem(Core.Exp exp, Ast.Direction direction) {
    return new Core.OrderItem(exp, direction);
  }

  public Core.Group group(SortedMap<Core.IdPat, Core.Exp> groupExps,
      SortedMap<Core.IdPat, Core.Aggregate> aggregates) {
    final List<Binding> bindings = new ArrayList<>();
    groupExps.keySet().forEach(id -> bindings.add(Binding.of(id)));
    aggregates.keySet().forEach(id -> bindings.add(Binding.of(id)));
    return new Core.Group(ImmutableList.copyOf(bindings),
        ImmutableSortedMap.copyOfSorted(groupExps),
        ImmutableSortedMap.copyOfSorted(aggregates));
  }

  public Core.Where where(List<Binding> bindings, Core.Exp exp) {
    return new Core.Where(ImmutableList.copyOf(bindings), exp);
  }

  public Core.Yield yield_(List<Binding> bindings, Core.Exp exp) {
    return new Core.Yield(ImmutableList.copyOf(bindings), exp);
  }

  /** Derives bindings, then calls {@link #yield_(List, Core.Exp)}. */
  public Core.Yield yield_(TypeSystem typeSystem, Core.Exp exp) {
    final List<Binding> bindings = new ArrayList<>();
    switch (exp.type.op()) {
    case RECORD_TYPE:
    case TUPLE_TYPE:
      ((RecordLikeType) exp.type).argNameTypes().forEach((name, type) ->
          bindings.add(
              Binding.of(core.idPat(type, name, typeSystem.nameGenerator))));
      break;
    default:
      bindings.add(Binding.of(core.idPat(exp.type, typeSystem.nameGenerator)));
    }
    return yield_(bindings, exp);
  }

  // Short-hands

  /** Creates a reference to the {@code slot}th field of an expression,
   * "{@code #slot exp}". The expression's type must be record or tuple. */
  public Core.Exp field(TypeSystem typeSystem, Core.Exp exp, int slot) {
    final Core.RecordSelector selector =
        recordSelector(typeSystem, (RecordLikeType) exp.type, slot);
    return apply(exp.pos, selector.type().resultType, selector, exp);
  }

  /** Creates a list. */
  public Core.Exp list(TypeSystem typeSystem, Type elementType,
      List<Core.Exp> args) {
    final Core.Literal literal = functionLiteral(typeSystem, BuiltIn.Z_LIST);
    final ListType listType = typeSystem.listType(elementType);
    return apply(Pos.ZERO, listType, literal,
        core.tuple(typeSystem, null, args));
  }

  /** Creates a list with one or more elements. */
  public Core.Exp list(TypeSystem typeSystem, Core.Exp arg0, Core.Exp... args) {
    return list(typeSystem, arg0.type, Lists.asList(arg0, args));
  }

  /** Creates a record. */
  public Core.Exp record(TypeSystem typeSystem,
      Map<String, ? extends Core.Exp> nameExps) {
    final ImmutableSortedMap<String, Core.Exp> sortedNameExps =
        ImmutableSortedMap.<String, Core.Exp>orderedBy(RecordType.ORDERING)
            .putAll(nameExps)
            .build();
    final SortedMap<String, Type> argNameTypes =
        new TreeMap<>(RecordType.ORDERING);
    sortedNameExps.forEach((name, exp) -> argNameTypes.put(name, exp.type));
    return tuple(typeSystem, typeSystem.recordType(argNameTypes),
        sortedNameExps.values());
  }

  /** Calls a built-in function. */
  private Core.Apply call(TypeSystem typeSystem, BuiltIn builtIn,
      Core.Exp... args) {
    final Core.Literal literal = functionLiteral(typeSystem, builtIn);
    final FnType fnType = (FnType) literal.type;
    return apply(Pos.ZERO, fnType.resultType, literal,
        args(fnType.paramType, args));
  }

  /** Calls a built-in function with one type parameter. */
  private Core.Apply call(TypeSystem typeSystem, BuiltIn builtIn, Type type,
      Pos pos, Core.Exp... args) {
    final Core.Literal literal = functionLiteral(typeSystem, builtIn);
    final ForallType forallType = (ForallType) literal.type;
    final FnType fnType = (FnType) typeSystem.apply(forallType, type);
    return apply(pos, fnType.resultType, literal,
        args(fnType.paramType, args));
  }

  private Core.Exp args(Type paramType, Core.Exp[] args) {
    return args.length == 1
        ? args[0]
        : tuple((TupleType) paramType, args);
  }

  public Core.Exp equal(TypeSystem typeSystem, Core.Exp a0, Core.Exp a1) {
    return call(typeSystem, BuiltIn.OP_EQ, a0.type, Pos.ZERO, a0, a1);
  }

  public Core.Exp lessThan(TypeSystem typeSystem, Core.Exp a0, Core.Exp a1) {
    return call(typeSystem, BuiltIn.OP_LT, a0.type, Pos.ZERO, a0, a1);
  }

  public Core.Exp greaterThan(TypeSystem typeSystem, Core.Exp a0, Core.Exp a1) {
    return call(typeSystem, BuiltIn.OP_GT, a0.type, Pos.ZERO, a0, a1);
  }

  public Core.Exp elem(TypeSystem typeSystem, Core.Exp a0, Core.Exp a1) {
    if (a1.op == Op.APPLY
        && ((Core.Apply) a1).fn.op == Op.FN_LITERAL
        && ((Core.Literal) ((Core.Apply) a1).fn).value == BuiltIn.Z_LIST
        && ((Core.Apply) a1).args().size() == 1) {
      // If "a1 = [x]", rather than "a0 elem [x]", generate "a0 = x"
      return equal(typeSystem, a0, ((Core.Apply) a1).args().get(0));
    }
    return call(typeSystem, BuiltIn.OP_ELEM, a0.type, Pos.ZERO, a0, a1);
  }

  public Core.Exp andAlso(TypeSystem typeSystem, Core.Exp a0, Core.Exp a1) {
    return call(typeSystem, BuiltIn.Z_ANDALSO, a0, a1);
  }

  public Core.Exp orElse(TypeSystem typeSystem, Core.Exp a0, Core.Exp a1) {
    return call(typeSystem, BuiltIn.Z_ORELSE, a0, a1);
  }

  public Core.Exp only(TypeSystem typeSystem, Pos pos, Core.Exp a0) {
    return call(typeSystem, BuiltIn.RELATIONAL_ONLY,
        ((ListType) a0.type).elementType, pos, a0);
  }

}

// End CoreBuilder.java
