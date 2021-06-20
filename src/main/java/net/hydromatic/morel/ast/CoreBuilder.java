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
import net.hydromatic.morel.compile.NameGenerator;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import org.apache.calcite.util.Util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.annotation.Nullable;

import static net.hydromatic.morel.type.RecordType.ORDERING;

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
      return realLiteral(value instanceof BigDecimal ? (BigDecimal) value
          : BigDecimal.valueOf(((Number) value).doubleValue()));
    case STRING:
      return stringLiteral((String) value);
    default:
      throw new AssertionError("unexpected " + type);
    }
  }

  /** Creates a {@code boolean} literal. */
  public Core.Literal boolLiteral(boolean b) {
    return new Core.Literal(Op.BOOL_LITERAL, PrimitiveType.BOOL, b);
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
  public Core.Id id(Core.IdPat idPat) {
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
    assert name.length() > 0;
    return idPat(type, name, nameGenerator.inc(name));
  }

  @SuppressWarnings("rawtypes")
  public Core.LiteralPat literalPat(Op op, Type type, Comparable value) {
    return new Core.LiteralPat(op, type, value);
  }

  public Core.WildcardPat wildcardPat(Type type) {
    return new Core.WildcardPat(type);
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
    Pair.forEach(argNames, args, (argName, arg) ->
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
      Pair.forEach(type.argNameTypes().keySet(), argList, (name, arg) ->
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

  public Core.ValDecl valDecl(boolean rec, Core.IdPat pat, Core.Exp exp) {
    return new Core.ValDecl(rec, pat, exp);
  }

  public Core.Match match(Core.Pat pat, Core.Exp exp) {
    return new Core.Match(pat, exp);
  }

  public Core.Case caseOf(Type type, Core.Exp exp,
      Iterable<? extends Core.Match> matchList) {
    return new Core.Case(type, exp, ImmutableList.copyOf(matchList));
  }

  public Core.From from(ListType type, Map<Core.Pat, Core.Exp> sources,
      Iterable<? extends Binding> bindings, List<Core.FromStep> steps) {
    return new Core.From(type, ImmutableMap.copyOf(sources),
        ImmutableList.copyOf(bindings), ImmutableList.copyOf(steps));
  }

  /** Derives the result type, then calls
   * {@link #from(ListType, Map, Iterable, List)}. */
  public Core.From from(TypeSystem typeSystem, Map<Core.Pat, Core.Exp> sources,
      Iterable<? extends Binding> bindings, List<Core.FromStep> steps) {
    final Type elementType;
    if (!steps.isEmpty() && Iterables.getLast(steps) instanceof Core.Yield) {
      elementType = ((Core.Yield) Iterables.getLast(steps)).exp.type;
    } else {
      final List<Binding> lastBindings = core.lastBindings(bindings, steps);
      if (lastBindings.size() == 1) {
        elementType = lastBindings.get(0).id.type;
      } else {
        final SortedMap<String, Type> argNameTypes = new TreeMap<>(ORDERING);
        lastBindings.forEach(b -> argNameTypes.put(b.id.name, b.id.type));
        elementType = typeSystem.recordType(argNameTypes);
      }
    }
    final ListType type = typeSystem.listType(elementType);
    return from(type, ImmutableMap.copyOf(sources),
        ImmutableList.copyOf(bindings), ImmutableList.copyOf(steps));
  }

  /** Returns what would be the yield expression if we created a
   * {@link Core.From} from the given sources and steps.
   *
   * <p>Examples:
   * <ul>
   * <li>{@code defaultYieldExp(sources=(a=E:t), steps=[])}
   *     is {@code a} (a {@link Core.Id});
   * <li>{@code defaultYieldExp(sources=(a=E:t, b=E2:t2), steps=[])}
   *     is {@code {a = a, b = b}} (a record).
   * </ul> */
  public Core.Exp defaultYieldExp(TypeSystem typeSystem,
      List<Binding> initialBindings, List<Core.FromStep> steps) {
    final List<Binding> bindings = lastBindings(initialBindings, steps);
    if (bindings.size() == 1) {
      return core.id(Iterables.getOnlyElement(bindings).id);
    } else {
      final SortedMap<Core.IdPat, Core.Exp> map = new TreeMap<>();
      final SortedMap<String, Type> argNameTypes = new TreeMap<>(ORDERING);
      bindings.forEach(b -> {
        map.put(b.id, core.id(b.id));
        argNameTypes.put(b.id.name, b.id.type);
      });
      return core.tuple(typeSystem.recordType(argNameTypes), map.values());
    }
  }

  public ImmutableList<Binding> lastBindings(
      Iterable<? extends Binding> initialBindings,
      List<? extends Core.FromStep> steps) {
    return steps.isEmpty()
        ? ImmutableList.copyOf(initialBindings)
        : Iterables.getLast(steps).bindings;
  }

  public Core.Fn fn(FnType type, Core.IdPat idPat, Core.Exp exp) {
    return new Core.Fn(type, idPat, exp);
  }

  public Core.Apply apply(Type type, Core.Exp fn, Core.Exp arg) {
    return new Core.Apply(type, fn, arg);
  }

  public Core.Case ifThenElse(Core.Exp condition, Core.Exp ifTrue,
      Core.Exp ifFalse) {
    // Translate "if c then a else b"
    // as if user had written "case c of true => a | _ => b"
    return new Core.Case(ifTrue.type, condition,
        ImmutableList.of(match(truePat, ifTrue),
            match(boolWildcardPat, ifFalse)));
  }

  public Core.DatatypeDecl datatypeDecl(Iterable<DataType> dataTypes) {
    return new Core.DatatypeDecl(ImmutableList.copyOf(dataTypes));
  }

  public Core.Aggregate aggregate(Type type, Core.Exp aggregate,
      @Nullable Core.Exp argument) {
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

}

// End CoreBuilder.java
