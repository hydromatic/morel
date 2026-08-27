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
package net.hydromatic.morel.compile;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.AliasType;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.DummyType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeCon;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * Enforces the conditions of checked types, by inserting the checks that
 * enforce them where a value flows into a claim.
 *
 * <p>A type claims a condition only where it says so, and a check goes wherever
 * a value flows into such a claim: a binding, a function parameter, an
 * ascription, a conversion, a datatype constructor, and inside a composite
 * value. Everywhere else the name has reduced to the type it abbreviates, so
 * nothing is claimed, and nothing has to be checked.
 *
 * <p>Immutable, over collaborators that are not: it hands out fresh names, and
 * registers a type's compiled conditions with the type system.
 *
 * <p>One belongs to each {@link Resolver}, and is copied with it, because
 * deciding whether a condition is closed reads the environment, and compiling
 * one converts an expression in it.
 */
class Enforcer {
  private final TypeMap typeMap;
  private final NameGenerator nameGenerator;
  private final Environment env;

  /** How to convert a condition to Core; {@link Resolver#toCore(Ast.Exp)}. */
  private final Function<Ast.Fn, Core.Exp> toCore;

  Enforcer(
      TypeMap typeMap,
      NameGenerator nameGenerator,
      Environment env,
      Function<Ast.Fn, Core.Exp> toCore) {
    this.typeMap = requireNonNull(typeMap);
    this.nameGenerator = requireNonNull(nameGenerator);
    this.env = requireNonNull(env);
    this.toCore = requireNonNull(toCore);
  }

  /**
   * Wraps an expression in a check, if the type it is being bound at constrains
   * anything.
   *
   * <p>This is where a value flows into a claim: the binding says the value is
   * an {@code employee}, so every condition the type carries -- its own, and
   * those of its components -- must hold of it. Everywhere else the name has
   * reduced to the type it abbreviates, so nothing is claimed and nothing need
   * be checked.
   */
  Core.Exp withChecks(Core.Exp coreExp, Ast.Pat pat, Pos pos) {
    final Type type = claimedPatType(pat, coreExp.type);
    return type == null ? coreExp : checked(coreExp, type, pos);
  }

  /**
   * Returns the type a pattern claims, or null if it claims nothing.
   *
   * <p>A claim is an annotation the user wrote, not a type inference deduced.
   * The two differ: inference gives the meet, which for a checked type is the
   * type it abbreviates, so a deduced type has no condition left to check. They
   * differ the other way too -- {@code val h = fn (n: nat) => n} is deduced
   * {@code nat -> nat}, but the user claimed nothing there, and the function
   * checks its own parameter.
   *
   * <p>Reading the annotation is also what lets a {@code val} and a {@code let
   * val} behave alike. A deduced type reaches a bound pattern only at the top
   * level, so driving from it left a {@code let} unchecked.
   *
   * @param erasedType the type the value has, used for the parts of the pattern
   *     that claim nothing
   */
  @Nullable
  Type claimedPatType(Ast.Pat pat, Type erasedType) {
    switch (pat.op) {
      case ANNOTATED_PAT:
        final Ast.AnnotatedPat annotatedPat = (Ast.AnnotatedPat) pat;
        final Type type = claimedType(annotatedPat.type);
        // The annotation may constrain nothing and still contain a pattern
        // that claims something, as in '(x: nat, y): int * int'.
        return type != null
            ? type
            : claimedPatType(annotatedPat.pat, erasedType);

      case TUPLE_PAT:
        final Ast.TuplePat tuplePat = (Ast.TuplePat) pat;
        if (!(erasedType instanceof RecordLikeType)) {
          return null;
        }
        final List<Type> erasedArgs =
            ImmutableList.copyOf(
                ((RecordLikeType) erasedType).argNameTypes().values());
        if (erasedArgs.size() != tuplePat.args.size()) {
          return null;
        }
        final List<Type> claimedArgs = new ArrayList<>();
        boolean claims = false;
        for (int i = 0; i < erasedArgs.size(); i++) {
          final Type argType =
              claimedPatType(tuplePat.args.get(i), erasedArgs.get(i));
          claims |= argType != null;
          claimedArgs.add(argType == null ? erasedArgs.get(i) : argType);
        }
        return claims ? typeMap.typeSystem.tupleType(claimedArgs) : null;

      case RECORD_PAT:
        // A record pattern claims per field, as a tuple pattern claims per
        // component. A field the pattern does not mention -- which '...'
        // allows -- claims nothing, and keeps the type it has.
        final Ast.RecordPat recordPat = (Ast.RecordPat) pat;
        if (!(erasedType instanceof RecordLikeType)) {
          return null;
        }
        final SortedMap<String, Type> claimedFields =
            new TreeMap<>(RecordType.ORDERING);
        boolean fieldClaims = false;
        for (Map.Entry<String, Type> field :
            ((RecordLikeType) erasedType).argNameTypes().entrySet()) {
          final Ast.Pat fieldPat = recordPat.args.get(field.getKey());
          final Type fieldType =
              fieldPat == null
                  ? null
                  : claimedPatType(fieldPat, field.getValue());
          fieldClaims |= fieldType != null;
          claimedFields.put(
              field.getKey(), fieldType == null ? field.getValue() : fieldType);
        }
        return fieldClaims
            ? typeMap.typeSystem.recordType(claimedFields)
            : null;

      default:
        // A list, a cons or a constructor pattern cannot say what it claims:
        // the claim is a type, and those types name one element type for every
        // element, so an annotation on one of them would be read as a claim
        // about all. '[a: nat, b]' claims that the first element is a nat, and
        // 'nat list' claims it of both.
        return null;
    }
  }

  /**
   * Returns the type that a conversion, {@code e as t}, converts to, or null if
   * {@code t} constrains nothing.
   *
   * <p>The type is looked up by the name the user wrote, rather than deduced,
   * because inference gives the meet of the two types, which for a checked type
   * is the type it abbreviates.
   */
  @Nullable
  Type claimedType(Ast.Type type) {
    if (!isConcrete(type)) {
      return null;
    }
    compileUnnamedChecks(type);
    final Type t =
        TypeResolver.toType(
            type,
            typeMap.typeSystem,
            et -> requireNonNull(typeMap.displayedKey(et.exp)));
    // Reject before the test below: a checked function type constrains
    // nothing that can be checked, so the test would pass it over in silence.
    rejectCheckedFunction(t, t, type.pos);
    return hasCheck(t) ? t : null;
  }

  /**
   * Compiles the conditions of any type written here that carries one.
   *
   * <p>A condition is compiled where the type that carries it is written. A
   * type declaration compiles its own; a type written anywhere else, such as in
   * an annotation, is compiled here.
   */
  void compileUnnamedChecks(Ast.Type type) {
    type.accept(
        new Visitor() {
          @Override
          protected void visit(Ast.CheckedType checkedType) {
            super.visit(checkedType);
            final Type t =
                TypeResolver.toType(
                    checkedType,
                    typeMap.typeSystem,
                    et -> requireNonNull(typeMap.displayedKey(et.exp)));
            compileChecks(t, checkedType.checks, checkedType.pos);
          }
        });
  }

  /**
   * Compiles the conditions a type declaration writes, and records them against
   * {@code type}.
   *
   * <p>A condition can only be converted here, in the {@link TypeMap} that
   * resolved it; a binding that later claims the type is a separate statement,
   * with a type map that has never seen these nodes.
   *
   * <p>Records before checking, not after. The type is interned by the time we
   * get here, so a declaration that throws would otherwise leave a type that
   * has conditions but nothing to evaluate, and using it later threw a {@link
   * NullPointerException}.
   */
  void compileDeclaredChecks(
      Ast.TypeBind bind, Type type, List<Ast.Fn> checks) {
    if (checks.isEmpty()) {
      return;
    }
    final List<Core.Exp> predicates =
        transformEager(checks, f -> makeTotal(toCore.apply(f)));
    typeMap.typeSystem.setCheckPredicates(type, predicates);
    predicates.forEach(p -> checkClosed(bind, p));
  }

  /**
   * Compiles the conditions of a checked type, unless they are compiled
   * already.
   *
   * <p>A type is interned by its conditions' text, so a type that has been seen
   * before has them compiled already, and the conditions here are equal to the
   * ones that were compiled -- but they may be a different, and untyped, copy
   * of them, which is why this must not compile them again.
   */
  void compileChecks(Type type, List<Ast.Fn> checks, Pos pos) {
    if (!checks.isEmpty()
        && typeMap.typeSystem.checkPredicates(type).isEmpty()) {
      final List<Core.Exp> predicates =
          transformEager(checks, f -> makeTotal(toCore.apply(f)));
      typeMap.typeSystem.setCheckPredicates(type, predicates);
      predicates.forEach(p -> checkClosed(null, pos, p));
    }
  }

  /**
   * Returns whether a type can be built without an environment.
   *
   * <p>A type variable is not yet known, and {@code typeof e} is a type only
   * once its expression has been resolved; neither can name a checked type,
   * which must be closed and unparameterized.
   */
  private static boolean isConcrete(Ast.Type type) {
    final AtomicBoolean concrete = new AtomicBoolean(true);
    type.accept(
        new Visitor() {
          @Override
          protected void visit(Ast.TyVar tyVar) {
            concrete.set(false);
          }
        });
    return concrete.get();
  }

  /**
   * Throws if a claim would place a condition on a function.
   *
   * <p>A check on a value is made when the value is made. A function value is
   * not a value its type can be checked against: to check {@code nat -> nat} we
   * would have to check every argument it is ever given and every result it
   * ever returns, which means replacing the function with a proxy. Rather than
   * do that silently, or -- worse -- accept the claim and check nothing, the
   * claim is rejected.
   *
   * @param type the type being examined, which recursion descends into
   * @param claimed the whole type that was claimed, for the message
   */
  void rejectCheckedFunction(Type type, Type claimed, Pos pos) {
    if (type instanceof FnType) {
      final FnType fnType = (FnType) type;
      if (hasCheck(fnType.paramType) || hasCheck(fnType.resultType)) {
        throw new CompileException(
            format(
                "cannot claim a checked function type '%s'", claimed.moniker()),
            false,
            pos);
      }
      return;
    }
    if (type instanceof AliasType) {
      rejectCheckedFunction(((AliasType) type).type, claimed, pos);
      return;
    }
    if (type instanceof RecordLikeType) {
      ((RecordLikeType) type)
          .argNameTypes()
          .values()
          .forEach(t -> rejectCheckedFunction(t, claimed, pos));
      return;
    }
    if (type.isCollection()) {
      rejectCheckedFunction(type.elementType(), claimed, pos);
    }
  }

  /**
   * Returns how to call a type in a message.
   *
   * <p>A checked type that is not named has nothing to be called but "value".
   * Writing its condition instead would repeat what the message already says
   * failed, at length.
   */
  private static String typeName(Type type) {
    if (type instanceof AliasType && ((AliasType) type).name.isEmpty()) {
      return "value";
    }
    return type.moniker();
  }

  /** Returns whether a type, or any type within it, carries a condition. */
  boolean hasCheck(Type type) {
    return deepCondition(type, null, null, "", true, Pos.ZERO) != null;
  }

  /**
   * Returns a condition that holds if {@code value} satisfies every condition
   * its type carries, or null if the type carries none.
   *
   * <p>A condition on a composite type is the conjunction of the conditions of
   * its components and its own, in that order: a type's own condition may
   * assume that its components satisfy theirs.
   *
   * <p>Two types are walked in step. {@code claimedType} is the type as the
   * user wrote it, which keeps its aliases and so knows where the conditions
   * are; {@code erasedType} is the same type with its aliases expanded, and is
   * what the expressions being built are typed with, because an alias must not
   * reach Core.
   *
   * <p>{@code blame} says what the value is of -- "field empno", "component 1",
   * "element" -- and is empty at the outermost level, where the value is the
   * whole. A condition on a component raises with the blame, and quotes the
   * component; the outermost condition raises without one, and quotes the
   * whole.
   *
   * <p>{@code walk.raising} says whether a component that fails should raise
   * for itself. A claim wants that, so that the message names the component; a
   * scan over the type does not, because there the condition decides which
   * values the type has rather than judging a value that must have it.
   *
   * <p>Called with a null {@code value} to ask only whether a type constrains
   * anything, in which case the expression it returns is a placeholder.
   */
  Core.@Nullable Exp deepCondition(
      Type claimedType,
      @Nullable Type erasedType,
      Core.@Nullable Exp value,
      String blame,
      boolean raising,
      Pos pos) {
    return deepCondition(
        claimedType,
        erasedType,
        value,
        blame,
        new Walk(raising, pos, ImmutableMap.of()));
  }

  /**
   * As {@link #deepCondition(Type, Type, Core.Exp, String, boolean, Pos)}, but
   * carrying the datatypes whose walk is in progress.
   *
   * <p>A datatype may contain itself, so walking one cannot be an expansion:
   * {@code walking} maps each datatype being walked to the predicate being
   * built for it, and a datatype met again is called rather than expanded.
   */
  Core.@Nullable Exp deepCondition(
      Type claimedType,
      @Nullable Type erasedType,
      Core.@Nullable Exp value,
      String blame,
      Walk walk) {
    final TypeSystem typeSystem = typeMap.typeSystem;
    if (claimedType instanceof AliasType) {
      final AliasType aliasType = (AliasType) claimedType;
      Core.Exp condition =
          deepCondition(aliasType.type, erasedType, value, blame, walk);
      if (!aliasType.checks.isEmpty()) {
        Core.Exp own = ownCondition(aliasType, value, walk.pos);
        if (walk.raising && !blame.isEmpty() && value != null) {
          // A component raises for itself, so that the message names it and
          // quotes it. The outermost condition is left bare, for the $check
          // that wraps the whole value to report.
          own =
              core.apply(
                  walk.pos,
                  PrimitiveType.BOOL,
                  core.functionLiteral(typeSystem, BuiltIn.Z_REQUIRE),
                  core.tuple(
                      typeSystem,
                      own,
                      value,
                      core.stringLiteral(typeName(aliasType)),
                      core.stringLiteral(blame)));
        }
        condition =
            condition == null ? own : core.andAlso(typeSystem, condition, own);
      }
      return condition;
    }
    if (claimedType instanceof RecordLikeType) {
      final RecordLikeType recordType = (RecordLikeType) claimedType;
      final RecordLikeType erasedRecordType =
          erasedType == null ? null : (RecordLikeType) erasedType;
      Core.Exp condition = null;
      for (Map.Entry<String, Type> field :
          recordType.argNameTypes().entrySet()) {
        final Type erasedFieldType =
            erasedRecordType == null
                ? null
                : erasedRecordType.argNameTypes().get(field.getKey());
        final Core.Exp fieldValue =
            value == null
                ? null
                : core.apply(
                    walk.pos,
                    requireNonNull(erasedFieldType),
                    core.recordSelector(
                        typeSystem,
                        requireNonNull(erasedRecordType),
                        field.getKey()),
                    value);
        final Core.Exp fieldCondition =
            deepCondition(
                field.getValue(),
                erasedFieldType,
                fieldValue,
                append(blame, fieldBlame(recordType, field.getKey())),
                walk);
        if (fieldCondition != null) {
          condition =
              condition == null
                  ? fieldCondition
                  : core.andAlso(typeSystem, condition, fieldCondition);
        }
      }
      return condition;
    }
    if (claimedType.isCollection()) {
      // A bag is walked by Bag.all, a list by List.all.
      return elementsCondition(
          claimedType.elementType(),
          erasedType == null ? null : erasedType.elementType(),
          value,
          blame,
          claimedType instanceof ListType ? BuiltIn.LIST_ALL : BuiltIn.BAG_ALL,
          walk);
    }
    final Type vectorElementType = vectorElementType(claimedType);
    if (vectorElementType != null) {
      // A vector is not a collection -- a query cannot scan one -- but a
      // condition inside one is claimed just the same, so it must be checked
      // just the same. Nothing below would: a vector is an eqtype, so it has
      // no constructors for datatypeCondition to walk, and the answer would
      // be "nothing here is checked", which every enclosing walk believes.
      return elementsCondition(
          vectorElementType,
          erasedType == null ? null : vectorElementType(erasedType),
          value,
          blame,
          BuiltIn.VECTOR_ALL,
          walk);
    }
    if (claimedType instanceof DataType) {
      return datatypeCondition(
          (DataType) claimedType, erasedType, value, blame, walk);
    }
    return null;
  }

  /**
   * Returns the element type of a vector, or null if {@code type} is not a
   * vector. A vector is an eqtype, hence a {@link DataType} with no
   * constructors.
   */
  private static @Nullable Type vectorElementType(Type type) {
    if (type instanceof DataType
        && ((DataType) type).name.equals(BuiltIn.Eqtype.VECTOR.mlName())) {
      return ((DataType) type).arg(0);
    }
    return null;
  }

  /**
   * Returns a condition that holds if every element of {@code value} satisfies
   * {@code elementType}'s condition, or null if the element type constrains
   * nothing. A list, a bag and a vector differ only in the built-in that walks
   * them.
   */
  private Core.@Nullable Exp elementsCondition(
      Type elementType,
      @Nullable Type erasedElementType,
      Core.@Nullable Exp value,
      String blame,
      BuiltIn all,
      Walk walk) {
    final TypeSystem typeSystem = typeMap.typeSystem;
    if (deepCondition(elementType, null, null, "", walk) == null) {
      return null;
    }
    if (value == null) {
      return core.boolLiteral(true); // placeholder; only nullness is read
    }
    // Every element must satisfy the element type's condition.
    final Type elementType2 = requireNonNull(erasedElementType);
    final Core.IdPat idPat =
        core.idPat(elementType2, () -> nameGenerator.getPrefixed("e"));
    final Core.Exp elementCondition =
        requireNonNull(
            deepCondition(
                elementType,
                elementType2,
                core.id(idPat),
                appendElement(blame),
                walk));
    final Core.Fn predicate =
        core.fn(
            typeSystem.fnType(elementType2, PrimitiveType.BOOL),
            idPat,
            elementCondition);
    return core.apply(
        walk.pos,
        PrimitiveType.BOOL,
        core.call(typeSystem, all, elementType2, walk.pos, predicate),
        value);
  }

  /**
   * Returns a condition that holds if every value a datatype's constructors
   * carry satisfies the conditions its type arguments carry.
   *
   * <p>Without this, a condition under a type parameter is claimed and never
   * checked: {@code val w: nat option = SOME ~1} printed {@code nat option} and
   * held a value that is not one. Records and collections were already walked;
   * a datatype is the remaining way to reach a type.
   *
   * <p>The condition is a function rather than an expression, and is applied to
   * the value, because a datatype may contain itself. A recursive datatype
   * calls the function being built; a datatype that does not recurse builds one
   * all the same, and the inliner takes it away.
   */
  private Core.@Nullable Exp datatypeCondition(
      DataType dataType,
      @Nullable Type erasedType,
      Core.@Nullable Exp value,
      String blame,
      Walk walk) {
    final TypeSystem typeSystem = typeMap.typeSystem;
    if (walk.walking.containsKey(dataType.key())) {
      // Met again, so this datatype contains itself. Call the predicate being
      // built for it rather than expanding it a second time. Asked only
      // whether anything is checked, answer no: the recursion constrains
      // nothing that the walk does not find elsewhere. (There is no predicate
      // to call while that is being asked, which is why the value may be null.)
      final Core.IdPat walkingPat = walk.walking.get(dataType.key());
      return value == null || walkingPat == null
          ? null
          : core.apply(
              walk.pos, PrimitiveType.BOOL, core.id(walkingPat), value);
    }
    final Map<String, Type> constructors =
        dataType.typeConstructors(typeSystem);

    // Ask first whether anything is checked, so that a datatype that
    // carries no condition costs no names and no code.
    // no predicate yet; only re-entry
    final Walk probe = walk.walking(dataType.key(), null);
    boolean checks = false;
    for (Type argType : constructors.values()) {
      if (argType != DummyType.INSTANCE
          && deepCondition(argType, null, null, "", probe) != null) {
        checks = true;
        break;
      }
    }
    if (!checks) {
      return null;
    }
    if (value == null) {
      return core.boolLiteral(true); // placeholder; only nullness is read
    }
    if (!(erasedType instanceof DataType)) {
      return null;
    }

    final DataType erasedDataType = (DataType) erasedType;
    final Map<String, Type> erasedConstructors =
        erasedDataType.typeConstructors(typeSystem);
    final FnType fnType = typeSystem.fnType(erasedDataType, PrimitiveType.BOOL);
    final Core.IdPat predicatePat =
        core.idPat(fnType, () -> nameGenerator.getPrefixed("con"));
    final Walk walk2 = walk.walking(dataType.key(), predicatePat);

    final List<Core.Match> matches = new ArrayList<>();
    constructors.forEach(
        (name, argType) -> {
          if (argType == DummyType.INSTANCE) {
            // A constructor with no argument carries nothing to check.
            matches.add(
                core.match(
                    walk.pos,
                    core.con0Pat(erasedDataType, name),
                    core.boolLiteral(true)));
            return;
          }
          final Type erasedArgType =
              requireNonNull(erasedConstructors.get(name));
          final Core.IdPat argPat =
              core.idPat(erasedArgType, () -> nameGenerator.getPrefixed("a"));
          final Core.Exp argCondition =
              deepCondition(
                  argType,
                  erasedArgType,
                  core.id(argPat),
                  append(blame, name),
                  walk2);
          matches.add(
              core.match(
                  walk.pos,
                  core.conPat(erasedDataType, name, argPat),
                  argCondition == null
                      ? core.boolLiteral(true)
                      : argCondition));
        });

    final Core.IdPat valuePat =
        core.idPat(erasedDataType, () -> nameGenerator.getPrefixed("v"));
    final Core.Fn predicate =
        core.fn(
            fnType,
            valuePat,
            core.caseOf(
                walk.pos, PrimitiveType.BOOL, core.id(valuePat), matches));
    return core.let(
        core.recValDecl(
            ImmutableList.of(
                core.nonRecValDecl(walk.pos, predicatePat, null, predicate))),
        core.apply(walk.pos, PrimitiveType.BOOL, core.id(predicatePat), value));
  }

  /**
   * Returns how to describe a field of a record or a tuple.
   *
   * <p>A tuple's fields are named "1", "2", so "component 1" names the same
   * component that {@code #1} selects. (The plan says "component 0"; matching
   * the selector seems less surprising.)
   */
  private static String fieldBlame(RecordLikeType recordType, String field) {
    return (recordType instanceof TupleType ? "component " : "field ") + field;
  }

  /**
   * Appends a collection element to a blame path.
   *
   * <p>An element is written {@code [_]}, as a subscript with nothing known
   * about which element it was. If a collection ever declares a key, that is
   * where the key would go.
   */
  private static String appendElement(String blame) {
    return blame + "[_]";
  }

  /** Appends a segment to a blame path. */
  private static String append(String blame, String segment) {
    if (blame.isEmpty()) {
      return segment;
    }
    // Within an outer path only the name is added, e.g. "field lead.empno".
    final int space = segment.indexOf(' ');
    return blame + "." + (space < 0 ? segment : segment.substring(space + 1));
  }

  /**
   * Returns an expression that evaluates to the value of {@code coreExp} if its
   * type's conditions hold of it, and otherwise raises {@code Constraint}.
   *
   * <blockquote>
   *
   * <pre>let val v = e in $check (c1 v andalso c2 v, v, "nat") end</pre>
   *
   * </blockquote>
   */
  Core.Exp checked(Core.Exp coreExp, Type type, Pos pos) {
    return checked(coreExp, type, "", pos);
  }

  /**
   * As {@link #checked(Core.Exp, Type, Pos)}, but says what the value is of,
   * for a value that is a component of something -- the argument of a
   * constructor, say.
   */
  Core.Exp checked(Core.Exp coreExp, Type type, String blame, Pos pos) {
    rejectCheckedFunction(type, type, pos);
    if (!hasCheck(type)) {
      return coreExp;
    }
    final TypeSystem typeSystem = typeMap.typeSystem;
    return letValue(
        coreExp,
        pos,
        id ->
            core.apply(
                pos,
                coreExp.type,
                core.functionLiteral(typeSystem, BuiltIn.Z_CHECK),
                core.tuple(
                    typeSystem,
                    requireNonNull(
                        deepCondition(
                            type, coreExp.type, id, blame, true, pos)),
                    id,
                    core.stringLiteral(typeName(type)),
                    core.stringLiteral(blame))));
  }

  /**
   * Returns an expression that evaluates to {@code SOME v} if the conditions of
   * {@code type} hold of {@code coreExp}, and {@code NONE} if they do not.
   *
   * <blockquote>
   *
   * <pre>let val v = e in if c1 v andalso c2 v then SOME v else NONE end</pre>
   *
   * </blockquote>
   */
  Core.Exp checkedOpt(Core.Exp coreExp, Type type, Type optionType, Pos pos) {
    final TypeSystem typeSystem = typeMap.typeSystem;
    return letValue(
        coreExp,
        pos,
        id ->
            core.ifThenElse(
                core.apply(
                    pos,
                    PrimitiveType.BOOL,
                    core.functionLiteral(typeSystem, BuiltIn.Z_ATTEMPT),
                    core.tuple(
                        typeSystem,
                        requireNonNull(
                            deepCondition(
                                type, coreExp.type, id, "", true, pos)),
                        id,
                        core.stringLiteral(typeName(type)),
                        core.stringLiteral(""))),
                core.apply(
                    pos,
                    optionType,
                    core.constructor(
                        typeSystem, BuiltIn.Constructor.OPTION_SOME),
                    id),
                core.constructor(typeSystem, BuiltIn.Constructor.OPTION_NONE)));
  }

  /**
   * Binds an expression to a name and applies {@code body} to it.
   *
   * <p>The {@code let} is what stops the expression being evaluated twice, once
   * for a condition and once for the result.
   */
  Core.Exp letValue(
      Core.Exp coreExp, Pos pos, Function<Core.Id, Core.Exp> body) {
    if (coreExp instanceof Core.Id) {
      // Already a variable, so reading it twice costs nothing.
      return body.apply((Core.Id) coreExp);
    }
    final Core.IdPat idPat =
        core.idPat(coreExp.type, () -> nameGenerator.getPrefixed("v"));
    final Core.Exp exp = body.apply(core.id(idPat));
    return core.let(core.nonRecValDecl(pos, idPat, null, coreExp), exp);
  }

  /**
   * Returns the conjunction of a checked type's own conditions, of {@code
   * value}.
   */
  private Core.Exp ownCondition(
      AliasType aliasType, Core.@Nullable Exp value, Pos pos) {
    final TypeSystem typeSystem = typeMap.typeSystem;
    final List<Core.Exp> predicates = typeSystem.checkPredicates(aliasType);
    if (predicates.isEmpty()) {
      // A type is interned when it is declared, but its conditions are
      // compiled afterwards, so a declaration that failed leaves a type that
      // has conditions and no way to evaluate them. Using such a type is an
      // error; before this it dereferenced null.
      throw new CompileException(
          format(
              "checked type '%s' was not declared successfully",
              aliasType.name),
          false,
          pos);
    }
    Core.Exp condition = null;
    for (Core.Exp predicate : predicates) {
      if (value == null) {
        return core.boolLiteral(true); // placeholder; only nullness is read
      }
      final Core.Exp applied =
          core.apply(pos, PrimitiveType.BOOL, predicate, value);
      condition =
          condition == null
              ? applied
              : core.andAlso(typeSystem, condition, applied);
    }
    return requireNonNull(condition);
  }

  /**
   * Throws if a condition refers to anything but the value it is given and the
   * standard basis.
   *
   * <p>A condition must be closed. That is what lets a checked type be interned
   * like any other type: two checked types are the same type when their
   * conditions are textually equal, which would not follow if a condition could
   * also depend on an environment. It also settles what a condition means when
   * the values it used are re-bound, by making the question not arise.
   *
   * <p>A reference to the basis is closed enough: a built-in is not
   * re-bindable, so the condition cannot change under it. The binding decides
   * this, not the name: a user who shadows a basis name has declared a value of
   * their own, and a condition that referred to it would take its meaning from
   * the environment.
   *
   * <p>So {@code check i => i >= 1 andalso i <= 12} is closed, and {@code check
   * i => lessThanDozen i} is not, and must be written out.
   */
  void checkClosed(Ast.TypeBind bind, Core.Exp predicate) {
    checkClosed(bind.name.name, bind.pos, predicate);
  }

  /**
   * As {@link #checkClosed(Ast.TypeBind, Core.Exp)}, for a type that may have
   * no name.
   */
  void checkClosed(@Nullable String name, Pos pos, Core.Exp predicate) {
    final Set<Core.NamedPat> bound = new LinkedHashSet<>();
    final List<Core.Id> ids = new ArrayList<>();
    predicate.accept(
        new Visitor() {
          @Override
          protected void visit(Core.IdPat idPat) {
            bound.add(idPat);
            super.visit(idPat);
          }

          @Override
          protected void visit(Core.Id id) {
            ids.add(id);
            super.visit(id);
          }
        });
    for (Core.Id id : ids) {
      if (bound.contains(id.idPat)) {
        continue;
      }
      // Ask the binding, not the name: the user may shadow a basis name, and
      // then a reference to it is to their value, not the basis one.
      final Binding binding = env.getOpt(id.idPat);
      if (binding == null) {
        // Not in the environment, so bound within the condition itself -- by a
        // query step, say, which binds names the visitor above does not see as
        // patterns. Nothing outside is referred to.
        continue;
      }
      if (!binding.builtIn) {
        throw new CompileException(
            name == null
                ? format(
                    "condition is not closed; it refers to '%s'", id.idPat.name)
                : format(
                    "condition of checked type '%s' is not closed; "
                        + "it refers to '%s'",
                    name, id.idPat.name),
            false,
            pos);
      }
    }
  }

  /**
   * Makes a condition total, by appending {@code _ => false} if it does not
   * already match every value.
   *
   * <p>A condition need not be exhaustive: {@code type z = int check 0 => true}
   * says that zero is the only value of the type, and reads better than
   * spelling out the other case. Without this the condition would raise {@code
   * Match} on any other value, rather than rejecting it.
   *
   * <p>This runs before the general coverage pass, so the condition never looks
   * non-exhaustive to it.
   */
  Core.Exp makeTotal(Core.Exp predicate) {
    if (!(predicate instanceof Core.Fn)) {
      return predicate;
    }
    final Core.Fn fn = (Core.Fn) predicate;
    if (!(fn.exp instanceof Core.Case)) {
      return predicate;
    }
    final Core.Case case_ = (Core.Case) fn.exp;
    final TypeSystem typeSystem = typeMap.typeSystem;
    final List<Core.Pat> pats = transformEager(case_.matchList, m -> m.pat);
    if (PatternCoverageChecker.isExhaustive(typeSystem, pats)) {
      return predicate;
    }
    final List<Core.Match> matchList = new ArrayList<>(case_.matchList);
    matchList.add(
        core.match(
            case_.pos,
            core.wildcardPat(case_.exp.type),
            core.boolLiteral(false)));
    return core.fn(
        (FnType) fn.type,
        fn.idPat,
        core.caseOf(case_.pos, case_.type, case_.exp, matchList));
  }

  /**
   * Wraps the argument of a datatype constructor in a check, if the constructor
   * declares a checked type for it.
   *
   * <p>Applying a constructor is a construction site, like a binding: {@code
   * Box ~1} claims that {@code ~1} is a {@code nat}, because that is what
   * {@code Box} was declared to hold.
   */
  Core.Exp withConstructorCheck(Ast.Exp fn, Core.Exp coreArg) {
    if (fn.op != Op.ID) {
      return coreArg;
    }
    final String name = ((Ast.Id) fn).name;
    final TypeCon tyCon = typeMap.typeSystem.lookupTyCon(name);
    if (tyCon == null) {
      return coreArg;
    }
    final Type argType = tyCon.argTypeKey.toType(typeMap.typeSystem);
    return checked(coreArg, argType, "argument of " + name, fn.pos);
  }

  /**
   * Returns the checked type of a function's parameter, or null if the
   * parameter is not annotated with one.
   *
   * <p>The annotation is read rather than deduced, because the body may weaken
   * it: {@code fun f (n: nat) = n - 1} has type {@code int -> int}, since
   * arithmetic drops the constraint, and asking inference would silently drop
   * the check with it.
   *
   * <p>Only a function of a single match is considered. A function of several
   * matches may annotate each differently, and which of them the parameter
   * claims is a question composite values raise more generally.
   */
  @Nullable
  Type parameterType(Ast.Fn fn, Type erasedType) {
    if (fn.matchList.size() != 1) {
      return null;
    }
    final Ast.Pat pat = fn.matchList.get(0).pat;
    if (pat.op == Op.ANNOTATED_PAT
        && ((Ast.AnnotatedPat) pat).pat.op == Op.ID_PAT) {
      // The branch checks this one for itself; see toCore(Ast.Match). Doing it
      // here as well would check every argument twice.
      return null;
    }
    return claimedPatType(pat, erasedType);
  }

  /**
   * What stays the same while a claim is walked: whether a failure raises, the
   * position to blame it at, and the datatypes whose walk is in progress.
   *
   * <p>A datatype may contain itself, so walking one cannot be an expansion:
   * {@link #walking} maps each datatype being walked to the predicate being
   * built for it, and a datatype met again is called rather than expanded.
   */
  private static class Walk {
    /** Whether a value that fails the condition raises, or answers false. */
    final boolean raising;

    /** Where to blame a failure. */
    final Pos pos;

    /** The datatypes being walked, and the predicate being built for each. */
    final Map<Type.Key, Core.@Nullable IdPat> walking;

    Walk(
        boolean raising, Pos pos, Map<Type.Key, Core.@Nullable IdPat> walking) {
      this.raising = raising;
      this.pos = pos;
      this.walking = walking;
    }

    /** Returns a copy that is also walking {@code dataType}. */
    Walk walking(Type.Key dataType, Core.@Nullable IdPat predicate) {
      final Map<Type.Key, Core.@Nullable IdPat> map = new HashMap<>(walking);
      map.put(dataType, predicate);
      return new Walk(raising, pos, map);
    }
  }
}

// End Enforcer.java
