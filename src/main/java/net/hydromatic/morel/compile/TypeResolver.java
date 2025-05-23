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

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.lang.String.join;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static net.hydromatic.morel.ast.AstBuilder.ast;
import static net.hydromatic.morel.type.RecordType.mutableMap;
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.PairList.copyOf;
import static net.hydromatic.morel.util.Static.last;
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.transform;
import static net.hydromatic.morel.util.Static.transformEager;
import static org.apache.calcite.util.Util.first;
import static org.apache.calcite.util.Util.firstDuplicate;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.Binding.Kind;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.Keys;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.MultiType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;
import net.hydromatic.morel.type.TypedValue;
import net.hydromatic.morel.util.MapList;
import net.hydromatic.morel.util.MartelliUnifier;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.PairList;
import net.hydromatic.morel.util.Tracers;
import net.hydromatic.morel.util.TriConsumer;
import net.hydromatic.morel.util.Unifier;
import net.hydromatic.morel.util.Unifier.Action;
import net.hydromatic.morel.util.Unifier.Constraint;
import net.hydromatic.morel.util.Unifier.Failure;
import net.hydromatic.morel.util.Unifier.Result;
import net.hydromatic.morel.util.Unifier.Retry;
import net.hydromatic.morel.util.Unifier.Sequence;
import net.hydromatic.morel.util.Unifier.Substitution;
import net.hydromatic.morel.util.Unifier.Term;
import net.hydromatic.morel.util.Unifier.TermTerm;
import net.hydromatic.morel.util.Unifier.Variable;
import org.apache.calcite.util.Holder;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Resolves all the types within an expression. */
// @SuppressWarnings("StaticPseudoFunctionalStyleMethod")
public class TypeResolver {
  private final TypeSystem typeSystem;
  private final Unifier unifier = new MartelliUnifier();
  private final List<TermVariable> terms = new ArrayList<>();
  private final Map<AstNode, Term> map = new HashMap<>();
  private final Map<Variable, Action> actionMap = new HashMap<>();
  private final PairList<Variable, PrimitiveType> preferredTypes =
      PairList.of();
  private final List<Inst> overloads = new ArrayList<>();
  private final List<Constraint> constraints = new ArrayList<>();

  static final String BAG_TY_CON = BuiltIn.Eqtype.BAG.mlName();
  static final String TUPLE_TY_CON = "tuple";
  static final String ARG_TY_CON = "$arg";
  static final String OVERLOAD_TY_CON = BuiltIn.Datatype.OVERLOAD.mlName();
  static final String LIST_TY_CON = "list";
  static final String RECORD_TY_CON = "record";
  static final String FN_TY_CON = "fn";

  /** A field of this name indicates that a record type is progressive. */
  static final String PROGRESSIVE_LABEL = "z$dummy";

  private TypeResolver(TypeSystem typeSystem) {
    this.typeSystem = requireNonNull(typeSystem);
  }

  /** Deduces the datatype of a declaration. */
  public static Resolved deduceType(
      Environment env, Ast.Decl decl, TypeSystem typeSystem) {
    final TypeResolver typeResolver = new TypeResolver(typeSystem);
    int attempt = 0;
    for (; ; ) {
      int original = typeSystem.expandCount.get();
      final Resolved resolved = typeResolver.deduceType_(env, decl);
      if (typeSystem.expandCount.get() == original || attempt++ > 1) {
        return resolved;
      }
    }
  }

  /** Converts a type AST to a type. */
  public static Type toType(Ast.Type type, TypeSystem typeSystem) {
    return typeSystem.typeFor(toTypeKey(type));
  }

  /** Converts a type AST to a type key. */
  public static Type.Key toTypeKey(Ast.Type type) {
    return new KeyBuilder().toTypeKey(type);
  }

  private Resolved deduceType_(Environment env, Ast.Decl decl) {
    final TypeEnvHolder typeEnvs =
        new TypeEnvHolder(new EnvironmentTypeEnv(env, EmptyTypeEnv.INSTANCE));
    env.forEachType(typeSystem, typeEnvs);

    final TypeEnv typeEnv = typeEnvs.typeEnv;
    final Ast.Decl node2 = deduceDeclType(typeEnv, decl, PairList.of());
    final boolean debug = false;
    @SuppressWarnings("ConstantConditions")
    final Tracers.ConfigurableTracer tracer =
        debug ? Tracers.printTracer(System.out) : Tracers.nullTracer();

    // Deduce types. The loop will retry, just once, if there are certain kinds
    // of errors.
    tryAgain:
    for (; ; ) {
      final List<TermTerm> termPairs = new ArrayList<>();
      terms.forEach(tv -> termPairs.add(new TermTerm(tv.term, tv.variable)));
      final Result result =
          unifier.unify(termPairs, actionMap, constraints, tracer);
      if (result instanceof Retry) {
        final Retry retry = (Retry) result;
        retry.amend();
        continue;
      }
      if (result instanceof Failure) {
        final String extra =
            ";\n"
                + " term pairs:\n"
                + join("\n", transform(terms, Object::toString));
        final Failure failure = (Failure) result;
        throw new TypeException(
            "Cannot deduce type: " + failure.reason(), Pos.ZERO);
      }
      final TypeMap typeMap =
          new TypeMap(typeSystem, map, (Substitution) result);
      while (!preferredTypes.isEmpty()) {
        Map.Entry<Variable, PrimitiveType> x = preferredTypes.remove(0);
        final Type type =
            typeMap.termToType(typeMap.substitution.resultMap.get(x.getKey()));
        if (type instanceof TypeVar) {
          equiv(x.getKey(), toTerm(x.getValue()));
          continue tryAgain;
        }
      }

      final AtomicBoolean progressive = new AtomicBoolean();
      forEachUnresolvedField(
          node2,
          typeMap,
          apply -> {
            final Type type = typeMap.getType(apply.arg);
            if (type.isProgressive()) {
              progressive.set(true);
            }
          },
          apply -> {},
          apply -> {});
      if (progressive.get()) {
        node2.accept(FieldExpander.create(typeSystem, env));
      } else {
        checkNoUnresolvedFieldRefs(node2, typeMap);
      }
      return Resolved.of(env, decl, node2, typeMap);
    }
  }

  /**
   * Checks that there are no field references "x.y" or "#y x" where "x" has an
   * unresolved type. Throws if there are unresolved field references.
   */
  private static void checkNoUnresolvedFieldRefs(
      Ast.Decl decl, TypeMap typeMap) {
    forEachUnresolvedField(
        decl,
        typeMap,
        apply -> {
          throw new TypeException(
              "unresolved flex record (can't tell "
                  + "what fields there are besides "
                  + apply.fn
                  + ")",
              apply.arg.pos);
        },
        apply -> {
          throw new TypeException(
              "reference to field "
                  + ((Ast.RecordSelector) apply.fn).name
                  + " of non-record type "
                  + typeMap.getType(apply.arg),
              apply.arg.pos);
        },
        apply -> {
          throw new TypeException(
              "no field '"
                  + ((Ast.RecordSelector) apply.fn).name
                  + "' in type '"
                  + typeMap.getType(apply.arg)
                  + "'",
              apply.arg.pos);
        });
  }

  private static void forEachUnresolvedField(
      Ast.Decl decl,
      TypeMap typeMap,
      Consumer<Ast.Apply> variableConsumer,
      Consumer<Ast.Apply> notRecordTypeConsumer,
      Consumer<Ast.Apply> noFieldConsumer) {
    decl.accept(
        new Visitor() {
          @Override
          protected void visit(Ast.Apply apply) {
            if (apply.fn.op == Op.RECORD_SELECTOR) {
              final Ast.RecordSelector recordSelector =
                  (Ast.RecordSelector) apply.fn;
              if (typeMap.typeIsVariable(apply.arg)) {
                variableConsumer.accept(apply);
              } else {
                final Collection<String> fieldNames =
                    typeMap.typeFieldNames(apply.arg);
                if (fieldNames == null) {
                  notRecordTypeConsumer.accept(apply);
                } else {
                  if (!fieldNames.contains(recordSelector.name)) {
                    // "#f r" is valid if "r" is a record type with a field "f"
                    noFieldConsumer.accept(apply);
                  }
                }
              }
            }
            super.visit(apply);
          }
        });
  }

  /** Registers that an AST node maps to a type term. */
  private <E extends AstNode> E reg(E node, Term term) {
    requireNonNull(node);
    requireNonNull(term);
    map.put(node, term);
    return node;
  }

  /**
   * Registers that an AST node maps to a type term and is equivalent to a
   * variable.
   */
  private <E extends AstNode> E reg(E node, Variable variable, Term term) {
    requireNonNull(node);
    requireNonNull(variable);
    requireNonNull(term);
    equiv(variable, term);
    map.put(node, term);
    return node;
  }

  /**
   * Registers that a type variable is equivalent to at least one of a list of
   * terms.
   */
  private void constrain(
      Variable arg, Variable result, PairList<Term, Term> argResults) {
    constraints.add(unifier.constraint(arg, result, argResults));
  }

  /** Adds a constraint that {@code c} is a bag or list of {@code v}. */
  private void mayBeBagOrList(Variable c, Variable v) {
    final Sequence list = listTerm(v);
    final Sequence bag = bagTerm(v);
    PairList<Term, Constraint.Action> termActions =
        copyOf(list, Constraint.equiv(c, list), bag, Constraint.equiv(c, bag));
    constraints.add(unifier.constraint(c, termActions));
  }

  /**
   * Adds a constraint that {@code c1} is a bag or list of {@code v1}; if it is
   * a list then {@code c2} is a list of {@code v2}, otherwise {@code c2} is a
   * bag of {@code v2}.
   */
  private void isListOrBagMatchingInput(
      Variable c1, Variable v1, Variable c2, Variable v2) {
    Sequence list1 = listTerm(v1);
    Sequence bag1 = bagTerm(v1);
    Sequence list2 = listTerm(v2);
    Sequence bag2 = bagTerm(v2);
    constraints.add(
        unifier.constraint(c2, c1, copyOf(list2, list1, bag2, bag1)));
    constraints.add(
        unifier.constraint(c1, c2, copyOf(list1, list2, bag1, bag2)));
  }

  /**
   * Adds a constraint that {@code c0} is a bag or list (of {@code v0}), and
   * {@code c1} is a bag or list (of {@code v1}); if both are lists then {@code
   * c} is a list of {@code v}, otherwise {@code c} is a bag of {@code v}.
   */
  private void isListIfBothAreLists(
      Term c0, Variable v0, Term c1, Variable v1, Variable c, Variable v) {
    final Sequence list0 = listTerm(v0);
    final Sequence list1 = listTerm(v1);
    final Sequence bag0 = bagTerm(v0);
    final Sequence bag1 = bagTerm(v1);
    final Sequence listResult = listTerm(v);
    final Sequence bagResult = bagTerm(v);
    final Constraint.Action listAction = Constraint.equiv(c, listResult);
    final Constraint.Action bagAction = Constraint.equiv(c, bagResult);
    final PairList<Term, Constraint.Action> termActions = PairList.of();
    termActions.add(argTerm(list0, list1), listAction);
    termActions.add(argTerm(list0, bag1), bagAction);
    termActions.add(argTerm(bag0, list1), bagAction);
    termActions.add(argTerm(bag0, bag1), bagAction);
    constraints.add(
        unifier.constraint(toVariable(argTerm(c0, c1)), termActions));
  }

  /**
   * Adds a constraint that the terms in {@code args} are all a bag or list (of
   * {@code v}); if all are lists then {@code c} is a list of {@code v},
   * otherwise {@code c} is a bag of {@code v}.
   */
  private void isListIfAllAreLists(List<Term> args, Variable c, Variable v) {
    if (args.isEmpty()) {
      throw new IllegalArgumentException("no args");
    }
    Term arg0 = args.get(0);
    mayBeBagOrList(toVariable(arg0), v);
    mayBeBagOrList(c, v);
    for (Term arg : skip(args)) {
      mayBeBagOrList(toVariable(arg), v);
      isListIfBothAreLists(arg0, v, arg, v, c, v);
    }
  }

  /**
   * Deduces a {@code yield} expression's type.
   *
   * <p>Singleton records are treated specially. The type of {@code yield {x =
   * y}} is not a record type but the type of {@code y}. The step has the same
   * effect as {@code yield y}, except that it introduces {@code x} into the
   * namespace.
   */
  private Ast.Exp deduceYieldType(TypeEnv env, Ast.Exp node, Variable v) {
    return deduceType(env, node, v);
  }

  private Ast.Exp deduceType(TypeEnv env, Ast.Exp node, Variable v) {
    final List<Ast.Exp> args2;
    final Variable v2;
    final PairList<Ast.IdPat, Term> termMap;
    switch (node.op) {
      case BOOL_LITERAL:
        return reg(node, v, toTerm(PrimitiveType.BOOL));

      case CHAR_LITERAL:
        return reg(node, v, toTerm(PrimitiveType.CHAR));

      case INT_LITERAL:
        return reg(node, v, toTerm(PrimitiveType.INT));

      case REAL_LITERAL:
        return reg(node, v, toTerm(PrimitiveType.REAL));

      case STRING_LITERAL:
        return reg(node, v, toTerm(PrimitiveType.STRING));

      case UNIT_LITERAL:
        return reg(node, v, toTerm(PrimitiveType.UNIT));

      case ANNOTATED_EXP:
        final Ast.AnnotatedExp annotatedExp = (Ast.AnnotatedExp) node;
        final Type type = toType(annotatedExp.type, typeSystem);
        final Ast.Exp exp2 = deduceType(env, annotatedExp.exp, v);
        final Ast.AnnotatedExp annotatedExp2 =
            annotatedExp.copy(exp2, annotatedExp.type);
        return reg(annotatedExp2, v, toTerm(type, Subst.EMPTY));

      case ANDALSO:
      case ORELSE:
      case IMPLIES:
        return infix(env, (Ast.InfixCall) node, v, PrimitiveType.BOOL);

      case TUPLE:
        final Ast.Tuple tuple = (Ast.Tuple) node;
        final List<Term> types = new ArrayList<>();
        args2 = new ArrayList<>();
        for (Ast.Exp arg : tuple.args) {
          final Variable vArg = unifier.variable();
          args2.add(deduceType(env, arg, vArg));
          types.add(vArg);
        }
        return reg(tuple.copy(args2), v, tupleTerm(types));

      case LIST:
        final Ast.ListExp list = (Ast.ListExp) node;
        final Variable vArg2 = unifier.variable();
        args2 = new ArrayList<>();
        for (Ast.Exp arg : list.args) {
          args2.add(deduceType(env, arg, vArg2));
        }
        return reg(list.copy(args2), v, listTerm(vArg2));

      case RECORD:
        final Ast.Record record = (Ast.Record) node;
        final NavigableMap<String, Term> labelTypes = new TreeMap<>();
        final NavigableMap<String, Ast.Exp> map2 = new TreeMap<>();
        record.args.forEach(
            (name, exp) -> {
              final Variable vArg = unifier.variable();
              final Ast.Exp e2 = deduceType(env, exp, vArg);
              labelTypes.put(name, vArg);
              map2.put(name, e2);
            });
        if (record.with == null) {
          return reg(record.copy(null, map2), v, recordTerm(labelTypes));
        } else {
          final Ast.Exp with2 = deduceType(env, record.with, v);
          return reg(record.copy(with2, map2), v);
        }

      case LET:
        final Ast.Let let = (Ast.Let) node;
        termMap = PairList.of();
        TypeEnv env2 = env;
        final List<Ast.Decl> decls = new ArrayList<>();
        for (Ast.Decl decl : let.decls) {
          decls.add(deduceDeclType(env2, decl, termMap));
          env2 = bindAll(env2, termMap);
          termMap.clear();
        }
        final Ast.Exp e2 = deduceType(env2, let.exp, v);
        final Ast.Let let2 = let.copy(decls, e2);
        return reg(let2, v);

      case RECORD_SELECTOR:
        final Ast.RecordSelector recordSelector = (Ast.RecordSelector) node;
        throw new RuntimeException(
            "Error: unresolved flex record\n"
                + "   (can't tell what fields there are besides #"
                + recordSelector.name
                + ")");

      case IF:
        final Ast.If if_ = (Ast.If) node;
        v2 = unifier.variable();
        final Ast.Exp condition2 = deduceType(env, if_.condition, v2);
        equiv(v2, toTerm(PrimitiveType.BOOL));
        final Ast.Exp ifTrue2 = deduceType(env, if_.ifTrue, v);
        final Ast.Exp ifFalse2 = deduceType(env, if_.ifFalse, v);
        final Ast.If if2 = if_.copy(condition2, ifTrue2, ifFalse2);
        return reg(if2, v);

      case CASE:
        return deduceCaseType(env, (Ast.Case) node, v);

      case FROM:
      case EXISTS:
      case FORALL:
        // "(from exp: v50 as id: v60 [, exp: v51 as id: v61]...
        //  [where filterExp: v5] [yield yieldExp: v4]): v"
        // "(exists exp: v50 as id: v60 [, exp: v51 as id: v61]...
        //  [where filterExp: v5] [yield yieldExp: v4]): v" (v boolean)
        // "(forall exp: v50 as id: v60 [, exp: v51 as id: v61]...
        //   require requireExp: v21): v" (v boolean)
        return deduceQueryType(env, (Ast.Query) node, v);

      case ID:
        final Ast.Id id = (Ast.Id) node;
        final Term term = env.get(typeSystem, id.name, TypeEnv.unbound(id));
        return reg(id, v, term);

      case ORDINAL:
        final Ast.Ordinal ordinal = (Ast.Ordinal) node;
        final Term term3 =
            env.get(
                typeSystem,
                BuiltIn.Z_CURRENT.mlName,
                TypeEnv.onlyValidInQuery(ordinal));
        return reg(ordinal, v, toTerm(PrimitiveType.INT));

      case CURRENT:
        final Ast.Current current = (Ast.Current) node;
        final Term term2 =
            env.get(
                typeSystem,
                BuiltIn.Z_CURRENT.mlName,
                TypeEnv.onlyValidInQuery(current));
        return reg(current, v, term2);

      case FN:
        final Ast.Fn fn = (Ast.Fn) node;
        final Variable resultVariable = unifier.variable();
        final List<Ast.Match> matchList = new ArrayList<>();
        for (Ast.Match match : fn.matchList) {
          matchList.add(
              deduceMatchType(
                  env, match, (idPat, term1) -> {}, v, resultVariable));
        }
        final Ast.Fn fn2b = fn.copy(matchList);
        return reg(fn2b, v);

      case APPLY:
        return deduceApplyType(env, (Ast.Apply) node, v);

      case AT:
      case CARET:
      case COMPOSE:
      case PLUS:
      case MINUS:
      case TIMES:
      case DIVIDE:
      case DIV:
      case MOD:
      case EQ:
      case NE:
      case GE:
      case GT:
      case LE:
      case LT:
      case ELEM:
      case NOT_ELEM:
      case CONS:
        return infix(env, (Ast.InfixCall) node, v);

      case NEGATE:
        return prefix(env, (Ast.PrefixCall) node, v);

      default:
        throw new AssertionError("cannot deduce type for " + node.op);
    }
  }

  private Ast.Query deduceQueryType(TypeEnv env, Ast.Query query, Variable v) {
    final PairList<Ast.Id, Variable> fieldVars = PairList.of();
    final List<Ast.FromStep> fromSteps = new ArrayList<>();

    // An empty "from" is "unit list". Ordered.
    final Variable v11 = toVariable(recordTerm(ImmutableSortedMap.of()));
    final Sequence c11 = listTerm(v11);
    Triple p = new Triple(env, v11, toVariable(c11));
    for (Ord<Ast.FromStep> step : Ord.zip(query.steps)) {
      // Whether this is the last step. (The synthetic "yield" counts as a last
      // step.)
      final boolean lastStep = step.i == query.steps.size() - 1;
      p = deduceStepType(env, step.e, p, fieldVars, fromSteps);
      switch (step.e.op) {
        case COMPUTE:
        case INTO:
        case REQUIRE:
          if (step.e.op == Op.REQUIRE && query.op != Op.FORALL
              || step.e.op == Op.COMPUTE && query.op != Op.FROM
              || step.e.op == Op.INTO && query.op != Op.FROM) {
            String message =
                format(
                    "'%s' step must not occur in '%s'",
                    step.e.op.lowerName(), query.op.lowerName());
            throw new CompileException(message, false, step.e.pos);
          }
          if (!lastStep) {
            String message =
                format(
                    "'%s' step must be last in '%s'",
                    step.e.op.lowerName(), query.op.lowerName());
            throw new CompileException(
                message, false, query.steps.get(step.i + 1).pos);
          }
          break;
      }
    }
    if (query.op == Op.FORALL) {
      AstNode step = query.steps.isEmpty() ? query : last(query.steps);
      if (step.op != Op.REQUIRE) {
        throw new CompileException(
            "last step of 'forall' must be 'require'", false, step.pos);
      }
    }

    Term term =
        query.op == Op.EXISTS || query.op == Op.FORALL
            ? toTerm(PrimitiveType.BOOL)
            : query.isCompute() || query.isInto() ? p.v : p.c;
    return reg(query.copy(fromSteps), v, term);
  }

  private Triple deduceStepType(
      TypeEnv env,
      Ast.FromStep step,
      Triple p,
      PairList<Ast.Id, Variable> fieldVars,
      List<Ast.FromStep> fromSteps) {
    switch (step.op) {
      case SCAN:
        return deduceScanStepType((Ast.Scan) step, p, fieldVars, fromSteps);

      case WHERE:
        final Ast.Where where = (Ast.Where) step;
        final Variable v5 = unifier.variable();
        final Ast.Exp filter2 = deduceType(p.env, where.exp, v5);
        equiv(v5, toTerm(PrimitiveType.BOOL));
        fromSteps.add(where.copy(filter2));
        return p;

      case REQUIRE:
        final Ast.Require require = (Ast.Require) step;
        final Variable v21 = unifier.variable();
        final Ast.Exp filter3 = deduceType(p.env, require.exp, v21);
        equiv(v21, toTerm(PrimitiveType.BOOL));
        fromSteps.add(require.copy(filter3));
        return p;

      case DISTINCT:
        // Output of "distinct" has same ordering and element type as input.
        final Ast.Distinct distinct = (Ast.Distinct) step;
        fromSteps.add(distinct);
        return p;

      case SKIP:
        // "skip" is allowed for ordered and unordered collections (though it
        // makes less sense if the collection is unordered). The output has the
        // same type as the input. The skip expression must be an int.
        final Ast.Skip skip = (Ast.Skip) step;
        final Variable v11 = unifier.variable();
        final Ast.Exp skipCount = deduceType(env, skip.exp, v11);
        equiv(v11, toTerm(PrimitiveType.INT));
        fromSteps.add(skip.copy(skipCount));
        return p;

      case TAKE:
        // "take" is allowed for ordered and unordered collections (though it
        // makes less sense if the collection is unordered). The output has the
        // same type as the input. The take expression must be an int.
        final Ast.Take take = (Ast.Take) step;
        final Variable v12 = unifier.variable();
        final Ast.Exp takeCount = deduceType(env, take.exp, v12);
        equiv(v12, toTerm(PrimitiveType.INT));
        fromSteps.add(take.copy(takeCount));
        return p;

      case UNION:
      case EXCEPT:
      case INTERSECT:
        final Ast.SetStep setStep = (Ast.SetStep) step;
        final List<Ast.Exp> args2 = new ArrayList<>();
        final List<Term> terms = new ArrayList<>();
        terms.add(p.c);
        for (Ast.Exp arg : setStep.args) {
          final Variable v15 = unifier.variable();
          terms.add(v15);
          args2.add(deduceType(env, arg, v15));
        }
        final Variable c4 = unifier.variable();
        isListIfAllAreLists(terms, c4, p.v);
        fromSteps.add(setStep.copy(setStep.distinct, args2));
        return new Triple(p.env, p.v, c4);

      case YIELD:
        final Ast.Yield yield = (Ast.Yield) step;
        final Variable v6 = unifier.variable();
        final Ast.Exp yieldExp2 = deduceYieldType(p.env, yield.exp, v6);
        fromSteps.add(yield.copy(yieldExp2));
        final TypeEnvHolder envs = new TypeEnvHolder(env);

        // Output is ordered iff input is ordered. Yield behaves just like a
        // 'map' function with these overloaded forms:
        //   map: 'a -> 'b -> 'a list -> 'b list
        //   map: 'a -> 'b -> 'a bag -> 'b bag
        final Variable c6 = unifier.variable();
        isListOrBagMatchingInput(c6, v6, p.c, p.v);

        if (yieldExp2.op == Op.RECORD) {
          final Ast.Record record2 = (Ast.Record) yieldExp2;
          Term term = map.get(yieldExp2);
          if (record2.with != null) {
            term = map.get(record2.with);
          }
          if (term instanceof Sequence) {
            final Sequence sequence = (Sequence) term;
            fieldVars.clear();
            forEach(
                record2.args.keySet(),
                sequence.terms,
                (name, t) -> {
                  fieldVars.add(ast.id(Pos.ZERO, name), toVariable(t));
                  envs.bind(name, t);
                });
          }
        } else {
          String label =
              first(ast.implicitLabelOpt(yield.exp), Op.CURRENT.opName);
          envs.bind(label, v6);
          fieldVars.clear();
          fieldVars.add(ast.id(Pos.ZERO, label), v6);
        }
        return Triple.of(envs.typeEnv, v6, c6);

      case ORDER:
        final Ast.Order order = (Ast.Order) step;
        final List<Ast.OrderItem> orderItems = new ArrayList<>();
        for (Ast.OrderItem orderItem : order.orderItems) {
          orderItems.add(
              orderItem.copy(
                  deduceType(p.env, orderItem.exp, unifier.variable()),
                  orderItem.direction));
        }
        fromSteps.add(order.copy(orderItems));
        return Triple.of(p.env, p.v, toVariable(listTerm(p.v)));

      case GROUP:
      case COMPUTE:
        return deduceGroupStepType(
            env, (Ast.Group) step, p, fieldVars, fromSteps);

      case INTO:
        // from i in [1,2,3] into f
        //   f: int list -> string
        //   expression: string
        final Ast.Into into = (Ast.Into) step;
        final Variable v13 = unifier.variable();
        final Variable v14 = unifier.variable();
        final Ast.Exp intoExp = deduceType(p.env, into.exp, v14);
        final Sequence fnType = fnTerm(p.c, v13);
        equiv(v14, fnType);
        fromSteps.add(into.copy(intoExp));
        // Ordering is irrelevant because result is a singleton.
        return Triple.singleton(EmptyTypeEnv.INSTANCE, v13);

      case THROUGH:
        // from i in [1,2,3] through p in f
        //   f: int list -> string list
        //   expression: string list
        // p.v: int (i)
        // p.c: int list
        // v17: int list -> string list (f)
        // v18: string (p)
        // c18: string list (from i in [1,2,3] through p in f)
        final Ast.Through through = (Ast.Through) step;
        final Variable v18 = unifier.variable();
        final Variable c18 = unifier.variable();
        reg(through, c18);

        // Input collection (p.c) is either a bag of p.v or a list of p.v.
        mayBeBagOrList(p.c, p.v);

        final List<PatTerm> termMap = new ArrayList<>();
        deducePatType(env, through.pat, termMap::add, null, v18, t -> t);
        final Variable v17 = toVariable(fnTerm(p.c, c18));
        final Ast.Exp throughExp = deduceType(p.env, through.exp, v17);
        mayBeBagOrList(c18, v18);
        fromSteps.add(through.copy(through.pat, throughExp));
        TypeEnv env5 = env;
        fieldVars.clear();
        for (PatTerm e : termMap) {
          env5 = env5.bind(e.id.name, e.term);
          fieldVars.add(ast.id(Pos.ZERO, e.id.name), (Variable) e.term);
        }
        return Triple.of(env5, v18, c18);

      default:
        throw new AssertionError("unknown step type " + step.op);
    }
  }

  private Triple deduceScanStepType(
      Ast.Scan scan,
      Triple p,
      PairList<Ast.Id, Variable> fieldVars,
      List<Ast.FromStep> fromSteps) {
    final Ast.Exp scanExp3;
    final Variable v0 = unifier.variable();
    final Variable c0;
    final List<PatTerm> termMap = new ArrayList<>();
    final CollectionType containerize;
    if (scan.exp == null) {
      scanExp3 = null;
      // If we're iterating over 'all values' of the type, we'd better not
      // commit to doing it in order.
      containerize = CollectionType.BAG; // unordered
      c0 = null;
    } else if (scan.exp.op == Op.FROM_EQ) {
      final Ast.Exp scanExp = ((Ast.PrefixCall) scan.exp).a;
      final Ast.Exp scanExp2 = deduceType(p.env, scanExp, v0);
      scanExp3 = ast.fromEq(scanExp2);
      containerize = CollectionType.LIST; // ordered
      c0 = null;
      reg(scanExp, v0);
    } else {
      c0 = unifier.variable();
      scanExp3 = deduceType(p.env, scan.exp, c0);
      reg(scan.exp, c0);
      containerize = CollectionType.BOTH; // retain source collection type
    }
    deducePatType(p.env, scan.pat, termMap::add, null, v0, t -> t);
    final TypeEnvHolder typeEnvs = new TypeEnvHolder(p.env);
    for (PatTerm patTerm : termMap) {
      typeEnvs.bind(patTerm.id.name, patTerm.term);
      Ast.Id id1 = ast.id(Pos.ZERO, patTerm.id.name);
      fieldVars.add(id1, (Variable) patTerm.term);
      reg(id1, patTerm.term);
    }
    final TypeEnv env4 = typeEnvs.typeEnv;

    final Variable v = fieldVar(fieldVars);
    final Variable c;
    switch (containerize) {
      case BAG:
        c = toVariable(bagTerm(v));
        break;
      case LIST:
        c = toVariable(listTerm(v));
        break;
      default:
        c = unifier.variable();
        if (fromSteps.isEmpty()) {
          // Consider "from (i, j) in [(1, true), (2, false)]".
          // c0 is "(int * bool) list" - the collection type of the input
          // v0 is "int * bool" - the element type of the input
          // c is "{i:int, j:bool} list" - the collection type of the query
          // v is "{i:int, j:bool}" - the element type of the query
          //
          // Constraints:
          //   * c and c0 have the same collection type (list or bag),
          //   * c is either list(v) or bag(v)
          //   * c0 is either list(v0) or bag(v0)
          //   * c0 matches the type deduced for "[(1, true), (2, false)]"
          //   * v is a record type composed of the fields "{i, j}"
          isListOrBagMatchingInput(c0, v0, c, v);
        } else {
          // Consider processing the second step in
          //   "from i in [1, 2],
          //       b in [true, false]".
          // p.c is "int list" - the collection type of the input
          // p.v is "int" - the element type of the input
          // c0 is "bool list" - the collection type of the input
          // v0 is "bool" - the element type of the input
          // c is "{i:int, j:bool} list" - the collection type of the query
          // v is "{i:int, j:bool}" - the element type of the query
          //
          // Constraints:
          //   * c is either list(v) or bag(v)
          //   * c is a list if and p.c and c0 are both lists, otherwise bag
          //   * c0 matches the type deduced for "[true, false]"
          //   * v is a record type composed of the fields "{i, j}"
          isListIfBothAreLists(
              p.c, unifier.variable(), c0, unifier.variable(), c, v);
          mayBeBagOrList(c0, v0);
        }
    }

    final Ast.Exp scanCondition2;
    if (scan.condition != null) {
      final Variable v5 = unifier.variable();
      scanCondition2 = deduceType(env4, scan.condition, v5);
      equiv(v5, toTerm(PrimitiveType.BOOL));
    } else {
      scanCondition2 = null;
    }
    fromSteps.add(scan.copy(scan.pat, scanExp3, scanCondition2));
    return Triple.of(env4, v, c);
  }

  private Sequence argTerm(Term... args) {
    return unifier.apply(ARG_TY_CON, args);
  }

  private Triple deduceGroupStepType(
      TypeEnv env,
      Ast.Group group,
      Triple p,
      PairList<Ast.Id, Variable> fieldVars,
      List<Ast.FromStep> fromSteps) {
    validateGroup(group);
    TypeEnv env3 = env;
    fieldVars.clear();
    final PairList<Ast.Id, Ast.Exp> groupExps = PairList.of();
    for (Map.Entry<Ast.Id, Ast.Exp> groupExp : group.groupExps) {
      final Ast.Id id = groupExp.getKey();
      final Ast.Exp exp = groupExp.getValue();
      final Variable v7 = unifier.variable();
      final Ast.Exp exp2 = deduceType(p.env, exp, v7);
      reg(id, v7);
      env3 = env3.bind(id.name, v7);
      fieldVars.add(id, v7);
      groupExps.add(id, exp2);
    }
    final List<Ast.Aggregate> aggregates = new ArrayList<>();
    for (Ast.Aggregate aggregate : group.aggregates) {
      final Ast.Id id = aggregate.id;
      final Variable v8 = unifier.variable();
      reg(id, v8);
      final Variable v9 = unifier.variable();
      final Ast.Exp aggregateFn2;
      final Ast.Exp arg2;
      final Variable c10;
      if (aggregate.argument == null) {
        c10 = p.c;
        arg2 = null;
      } else {
        // The collection that is the input to the aggregate function is ordered
        // iff the input is ordered.
        final Variable v10 = unifier.variable();
        c10 = unifier.variable();
        isListOrBagMatchingInput(c10, v10, p.c, p.v);
        arg2 = deduceType(p.env, aggregate.argument, v10);
      }
      aggregateFn2 = deduceApplyFnType(p.env, aggregate.aggregate, v9, c10, v8);
      reg(aggregate.aggregate, v9);

      final Sequence fnType = fnTerm(c10, v8);
      equiv(v9, fnType);
      env3 = env3.bind(id.name, v8);
      fieldVars.add(id, v8);
      final Ast.Aggregate aggregate2 =
          aggregate.copy(aggregateFn2, arg2, aggregate.id);
      aggregates.add(aggregate2);
      reg(aggregate2, v8);
    }
    final Variable v2 = fieldVar(fieldVars);
    if (group.op == Op.GROUP) {
      fromSteps.add(group.copy(groupExps, aggregates));

      // Output is ordered iff input is ordered.
      final Variable c2 = unifier.variable();
      isListOrBagMatchingInput(c2, v2, p.c, p.v);
      return Triple.of(env3, v2, c2);
    } else {
      fromSteps.add(((Ast.Compute) group).copy(aggregates));
      return Triple.singleton(env3, v2);
    }
  }

  /**
   * Validates a {@code Group}. Throws if there are duplicate names among the
   * keys and aggregates.
   */
  private void validateGroup(Ast.Group group) {
    final List<String> names = new ArrayList<>();
    group.groupExps.leftList().forEach(id -> names.add(id.name));
    group.aggregates.forEach(aggregate -> names.add(aggregate.id.name));
    int duplicate = firstDuplicate(names);
    if (duplicate >= 0) {
      throw new RuntimeException(
          "Duplicate field name '" + names.get(duplicate) + "' in group");
    }
  }

  private Variable fieldVar(PairList<Ast.Id, Variable> fieldVars) {
    switch (fieldVars.size()) {
      case 0:
        return toVariable(toTerm(PrimitiveType.UNIT));
      case 1:
        return fieldVars.right(0);
      default:
        final TreeMap<String, Variable> map = new TreeMap<>();
        fieldVars.forEach((k, v) -> map.put(k.name, v));
        Term term = recordTerm(map);
        return equiv(unifier.variable(), term);
    }
  }

  private Ast.Apply deduceApplyType(TypeEnv env, Ast.Apply apply, Variable v) {
    final Variable vFn = unifier.variable();
    final Variable vArg = unifier.variable();
    Term term1 = fnTerm(vArg, v);
    equiv(vFn, term1);
    final Ast.Exp arg2;
    if (apply.arg instanceof Ast.RecordSelector) {
      // "apply" is "f #field" and has type "v";
      // "f" has type "vArg -> v" and also "vFn";
      // "#field" has type "vArg" and also "vRec -> vField".
      // When we resolve "vRec" we can then deduce "vField".
      final Variable vRec = unifier.variable();
      final Variable vField = unifier.variable();
      deduceRecordSelectorType(
          env, (Ast.RecordSelector) apply.arg, vRec, vField);
      arg2 = reg(apply.arg, vArg, fnTerm(vRec, vField));
    } else {
      arg2 = deduceType(env, apply.arg, vArg);
    }

    final Ast.Exp fn2;
    if (apply.fn instanceof Ast.RecordSelector) {
      // "apply" is "#field arg" and has type "v";
      // "#field" has type "vArg -> v";
      // "arg" has type "vArg".
      // When we resolve "vArg" we can then deduce "v".
      fn2 =
          deduceRecordSelectorType(env, (Ast.RecordSelector) apply.fn, vArg, v);
    } else {
      fn2 = deduceApplyFnType(env, apply.fn, vFn, vArg, v);
    }

    if (fn2 instanceof Ast.Id) {
      final BuiltIn builtIn = BuiltIn.BY_ML_NAME.get(((Ast.Id) fn2).name);
      if (builtIn != null) {
        builtIn.prefer(t -> preferredTypes.add(v, t));
      }
    }
    return reg(apply.copy(fn2, arg2), v);
  }

  /**
   * Deduces the datatype of a function being applied to an argument. If the
   * function is overloaded, the argument will help us resolve the overloading.
   *
   * @param env Compile-time environment
   * @param fn Function expression (often an identifier)
   * @param vFn Variable for the function type
   * @param vArg Variable for the argument type
   * @param vResult Variable for the result type
   * @return the function expression with its type deduced
   */
  private Ast.Exp deduceApplyFnType(
      TypeEnv env, Ast.Exp fn, Variable vFn, Variable vArg, Variable vResult) {
    @Nullable Type type = getType(env, fn);
    if (type instanceof MultiType) {
      final MultiType multiType = (MultiType) type;
      final PairList<Term, Term> argResults = PairList.of();
      for (Type type1 : multiType.types) {
        Subst subst = Subst.EMPTY;
        if (type1 instanceof ForallType) {
          for (int i = 0; i < ((ForallType) type1).parameterCount; i++) {
            subst = subst.plus(typeSystem.typeVariable(i), unifier.variable());
          }
          type1 = typeSystem.unqualified(type1);
        }
        FnType fnType = (FnType) type1;
        argResults.add(
            toTerm(fnType.paramType, subst), toTerm(fnType.resultType, subst));
      }
      constrain(vArg, vResult, argResults);
      return reg(fn, vFn);
    }

    if (!(fn instanceof Ast.Id)) {
      return deduceType(env, fn, vFn);
    }
    final Ast.Id id = (Ast.Id) fn;
    if (!env.hasOverloaded(id.name)) {
      return deduceType(env, fn, vFn);
    }
    // "apply" is "f arg" and has type "v";
    // "f" is an overloaded name, and has type "vArg -> v";
    // "arg" has type "vArg".
    // To resolve overloading, we gather all known overloads, and apply
    // them as constraints to "vArg".
    final List<Variable> variables = new ArrayList<>();
    env.collectInstances(
        typeSystem,
        id.name,
        term -> {
          final Variable variable = toVariable(term);
          variables.add(variable);
          if (term instanceof Sequence) {
            Sequence sequence = (Sequence) term;
            if (sequence.operator.equals(FN_TY_CON)) {
              assert sequence.terms.size() == 2;
              Term arg = sequence.terms.get(0);
              Term result = sequence.terms.get(1);
              overloads.add(
                  new Inst(
                      id.name, variable, toVariable(arg), toVariable(result)));
            }
          }
        });
    final PairList<Term, Term> argResults = PairList.of();
    for (Inst inst : overloads) {
      if (inst.name.equals(id.name) && variables.contains(inst.vFn)) {
        argResults.add(inst.vArg, inst.vResult);
      }
    }
    constrain(vArg, vResult, argResults);
    return reg(id, vFn);
  }

  private @Nullable Type getType(TypeEnv env, Ast.Exp exp) {
    switch (exp.op) {
      case BOOL_LITERAL:
        return PrimitiveType.BOOL;
      case CHAR_LITERAL:
        return PrimitiveType.CHAR;
      case INT_LITERAL:
        return PrimitiveType.INT;
      case REAL_LITERAL:
        return PrimitiveType.REAL;
      case STRING_LITERAL:
        return PrimitiveType.STRING;
      case UNIT_LITERAL:
        return PrimitiveType.UNIT;
      case ID:
        final Ast.Id id = (Ast.Id) exp;
        Type type = env.getTypeOpt(id.name);
        if (type != null) {
          reg(exp, toTerm(type, Subst.EMPTY));
        }
        return type;
      case APPLY:
        final Ast.Apply apply = (Ast.Apply) exp;
        if (apply.fn.op == Op.RECORD_SELECTOR) {
          Type argType = getType(env, apply.arg);
          if (argType instanceof RecordType) {
            final Ast.RecordSelector recordSelector =
                (Ast.RecordSelector) apply.fn;
            return ((RecordType) argType).argNameTypes.get(recordSelector.name);
          }
        }
        // fall through
      default:
        return null;
    }
  }

  private Ast.RecordSelector deduceRecordSelectorType(
      TypeEnv env,
      Ast.RecordSelector recordSelector,
      Variable vArg,
      Variable vResult) {
    final String fieldName = recordSelector.name;
    actionMap.put(
        vArg,
        (v, t, substitution, termPairs) -> {
          // We now know that the type arg, say "{a: int, b: real}".
          // So, now we can declare that the type of vResult, say "#b", is
          // "real".
          if (t instanceof Sequence) {
            final Sequence sequence = (Sequence) t;
            final List<String> fieldList = fieldList(sequence);
            if (fieldList != null) {
              int i = fieldList.indexOf(fieldName);
              if (i >= 0) {
                final Term result2 = substitution.resolve(vResult);
                final Term term = sequence.terms.get(i);
                final Term term2 = substitution.resolve(term);
                termPairs.accept(result2, term2);
              }
            }
          }
        });
    return recordSelector;
  }

  /** Inverse of {@link #recordLabel(NavigableSet)}. */
  static List<String> fieldList(final Sequence sequence) {
    if (sequence.operator.equals(RECORD_TY_CON)) {
      return ImmutableList.of();
    } else if (sequence.operator.startsWith(RECORD_TY_CON + ":")) {
      final String[] fields = sequence.operator.split(":");
      return skip(Arrays.asList(fields));
    } else if (sequence.operator.equals(TUPLE_TY_CON)) {
      final int size = sequence.terms.size();
      return TupleType.ordinalNames(size);
    } else {
      return null;
    }
  }

  /** Inverse of {@link #fieldList(Sequence)}. */
  static String recordLabel(NavigableSet<String> labels) {
    return labels.stream().collect(joining(":", RECORD_TY_CON + ":", ""));
  }

  private Ast.Match deduceMatchType(
      TypeEnv env,
      Ast.Match match,
      BiConsumer<Ast.IdPat, Term> termMap,
      Variable argVariable,
      Variable resultVariable) {
    final Variable vPat = unifier.variable();
    final PairList<Ast.IdPat, Term> termMap1 = PairList.of();
    final Consumer<PatTerm> consumer = p -> termMap1.add(p.id, p.term);
    deducePatType(env, match.pat, consumer, null, vPat, t -> t);
    termMap1.forEach(termMap);
    TypeEnv env2 = bindAll(env, termMap1);
    Ast.Exp exp2 = deduceType(env2, match.exp, resultVariable);
    Ast.Match match2 = match.copy(match.pat, exp2);
    return reg(match2, argVariable, fnTerm(vPat, resultVariable));
  }

  private List<Ast.Match> deduceMatchListType(
      TypeEnv env,
      List<Ast.Match> matchList,
      NavigableSet<String> labelNames,
      Variable argVariable,
      Variable resultVariable) {
    for (Ast.Match match : matchList) {
      if (match.pat instanceof Ast.RecordPat) {
        labelNames.addAll(((Ast.RecordPat) match.pat).args.keySet());
      }
    }
    final List<Ast.Match> matchList2 = new ArrayList<>();
    for (Ast.Match match : matchList) {
      final PairList<Ast.IdPat, Term> termMap = PairList.of();
      final Consumer<PatTerm> consumer = p -> termMap.add(p.id, p.term);
      deducePatType(env, match.pat, consumer, labelNames, argVariable, t -> t);
      final TypeEnv env2 = bindAll(env, termMap);
      final Ast.Exp exp2 = deduceType(env2, match.exp, resultVariable);
      matchList2.add(match.copy(match.pat, exp2));
    }
    return matchList2;
  }

  private Ast.Case deduceCaseType(TypeEnv env, Ast.Case case_, Variable v) {
    final Variable v2 = unifier.variable();
    final Ast.Exp e2b = deduceType(env, case_.exp, v2);
    final NavigableSet<String> labelNames = new TreeSet<>();
    final Term argType = map.get(e2b);
    if (argType instanceof Sequence) {
      final List<String> fieldList = fieldList((Sequence) argType);
      if (fieldList != null) {
        labelNames.addAll(fieldList);
      }
    }
    final List<Ast.Match> matchList2 =
        deduceMatchListType(env, case_.matchList, labelNames, v2, v);
    return reg(case_.copy(e2b, matchList2), v);
  }

  private AstNode deduceValBindType(
      TypeEnv env,
      Ast.ValBind valBind,
      PairList<Ast.IdPat, Term> termMap,
      Variable vPat) {
    final Consumer<PatTerm> consumer = p -> termMap.add(p.id, p.term);
    deducePatType(env, valBind.pat, consumer, null, vPat, t -> t);
    final Ast.Exp e2 = deduceType(env, valBind.exp, vPat);
    final Ast.ValBind valBind2 = valBind.copy(valBind.pat, e2);
    if (valBind2.pat instanceof Ast.IdPat) {
      if (env.hasOverloaded(((Ast.IdPat) valBind2.pat).name)) {
        // We are assigning to an overloaded name. Morel only allows overloads
        // for function values. So, create a function type so that we can (after
        // resolution) access the argument and result type. In
        //   over foo;
        //   val inst foo = fn NONE => [] | SOME x => [x]
        // "inst foo" has function type "'a option -> 'a list" (vPat),
        // argument "'a option" (v2), result "'a list" (v3).
        final Variable v2 = unifier.variable();
        final Variable v3 = unifier.variable();
        overloads.add(new Inst(((Ast.IdPat) valBind2.pat).name, vPat, v2, v3));
        Term term = fnTerm(v2, v3);
        equiv(vPat, term);
      }
    }
    map.put(valBind2, toTerm(PrimitiveType.UNIT));
    return valBind2;
  }

  private static TypeEnv bindAll(
      TypeEnv env, PairList<Ast.IdPat, Term> termMap) {
    for (Map.Entry<Ast.IdPat, Term> entry : termMap) {
      env = env.bind(entry.getKey().name, entry.getValue());
    }
    return env;
  }

  private Ast.Decl deduceDeclType(
      TypeEnv env, Ast.Decl node, PairList<Ast.IdPat, Term> termMap) {
    switch (node.op) {
      case OVER_DECL:
        final Ast.OverDecl overDecl = (Ast.OverDecl) node;
        return deduceOverDeclType(env, overDecl, termMap);

      case VAL_DECL:
        return deduceValDeclType(env, (Ast.ValDecl) node, termMap);

      case FUN_DECL:
        final Ast.ValDecl valDecl = toValDecl(env, (Ast.FunDecl) node);
        return deduceValDeclType(env, valDecl, termMap);

      case DATATYPE_DECL:
        final Ast.DatatypeDecl datatypeDecl = (Ast.DatatypeDecl) node;
        return deduceDataTypeDeclType(env, datatypeDecl, termMap);

      default:
        throw new AssertionError(
            "cannot deduce type for " + node.op + " [" + node + "]");
    }
  }

  private Ast.Decl deduceDataTypeDeclType(
      TypeEnv env,
      Ast.DatatypeDecl datatypeDecl,
      PairList<Ast.IdPat, Term> termMap) {
    final List<Keys.DataTypeKey> keys = new ArrayList<>();
    for (Ast.DatatypeBind bind : datatypeDecl.binds) {
      final KeyBuilder keyBuilder = new KeyBuilder();
      bind.tyVars.forEach(keyBuilder::toTypeKey);

      final SortedMap<String, Type.Key> tyCons = new TreeMap<>();
      deduceDatatypeBindType(bind, tyCons);

      keys.add(
          Keys.datatype(
              bind.name.name,
              Keys.ordinals(keyBuilder.tyVarMap.size()),
              tyCons));
    }
    final List<Type> types = typeSystem.dataTypes(keys);

    forEach(
        datatypeDecl.binds,
        types,
        (datatypeBind, type) -> {
          final DataType dataType =
              (DataType)
                  (type instanceof DataType ? type : ((ForallType) type).type);
          for (Ast.TyCon tyCon : datatypeBind.tyCons) {
            final Type tyConType;
            if (tyCon.type != null) {
              final Type.Key conKey = toTypeKey(tyCon.type);
              tyConType =
                  typeSystem.fnType(conKey.toType(typeSystem), dataType);
            } else {
              tyConType = dataType;
            }
            termMap.add(
                (Ast.IdPat) ast.idPat(tyCon.pos, tyCon.id.name),
                toTerm(tyConType, Subst.EMPTY));
            map.put(tyCon, toTerm(tyConType, Subst.EMPTY));
          }
        });

    map.put(datatypeDecl, toTerm(PrimitiveType.UNIT));
    return datatypeDecl;
  }

  private Ast.Decl deduceOverDeclType(
      TypeEnv env, Ast.OverDecl overDecl, PairList<Ast.IdPat, Term> termMap) {
    map.put(overDecl, toTerm(PrimitiveType.UNIT));
    termMap.add(
        overDecl.pat,
        toTerm(typeSystem.lookup(BuiltIn.Datatype.OVERLOAD), Subst.EMPTY));
    return overDecl;
  }

  private Ast.Decl deduceValDeclType(
      TypeEnv env, Ast.ValDecl valDecl, PairList<Ast.IdPat, Term> termMap) {
    final Holder<TypeEnv> envHolder = Holder.of(env);
    final PairList<Ast.ValBind, Supplier<Variable>> map0 = PairList.of();
    //noinspection FunctionalExpressionCanBeFolded
    valDecl.valBinds.forEach(
        b -> map0.add(b, Suppliers.memoize(unifier::variable)::get));
    map0.forEach(
        (valBind, vPatSupplier) -> {
          // If recursive, bind each value (presumably a function) to its type
          // in the environment before we try to deduce the type of the
          // expression.
          if (valDecl.rec && valBind.pat instanceof Ast.IdPat) {
            envHolder.set(
                envHolder
                    .get()
                    .bind(((Ast.IdPat) valBind.pat).name, vPatSupplier.get()));
          }
        });
    final List<Ast.ValBind> valBinds = new ArrayList<>();
    final TypeEnv env2 = envHolder.get();
    map0.forEach(
        (valBind, vPatSupplier) ->
            valBinds.add(
                (Ast.ValBind)
                    deduceValBindType(
                        env2, valBind, termMap, vPatSupplier.get())));
    Ast.Decl node2 = valDecl.copy(valBinds);
    map.put(node2, toTerm(PrimitiveType.UNIT));
    return node2;
  }

  private void deduceDatatypeBindType(
      Ast.DatatypeBind datatypeBind, SortedMap<String, Type.Key> tyCons) {
    KeyBuilder keyBuilder = new KeyBuilder();
    for (Ast.TyCon tyCon : datatypeBind.tyCons) {
      tyCons.put(
          tyCon.id.name,
          tyCon.type == null ? Keys.dummy() : keyBuilder.toTypeKey(tyCon.type));
    }
  }

  /** Workspace for converting types to keys. */
  private static class KeyBuilder {
    final Map<String, Integer> tyVarMap = new HashMap<>();

    /** Converts an AST type into a type key. */
    Type.Key toTypeKey(Ast.Type type) {
      switch (type.op) {
        case TUPLE_TYPE:
          final Ast.TupleType tupleType = (Ast.TupleType) type;
          return Keys.tuple(toTypeKeys(tupleType.types));

        case RECORD_TYPE:
          final Ast.RecordType recordType = (Ast.RecordType) type;
          final SortedMap<String, Type.Key> argNameTypes = mutableMap();
          final AtomicBoolean progressive = new AtomicBoolean(false);
          recordType.fieldTypes.forEach(
              (name, t) -> {
                if (name.equals(PROGRESSIVE_LABEL)) {
                  progressive.set(true);
                } else {
                  argNameTypes.put(name, toTypeKey(t));
                }
              });
          return progressive.get()
              ? Keys.progressiveRecord(argNameTypes)
              : Keys.record(argNameTypes);

        case FUNCTION_TYPE:
          final Ast.FunctionType functionType = (Ast.FunctionType) type;
          final Type.Key paramType = toTypeKey(functionType.paramType);
          final Type.Key resultType = toTypeKey(functionType.resultType);
          return Keys.fn(paramType, resultType);

        case NAMED_TYPE:
          final Ast.NamedType namedType = (Ast.NamedType) type;
          final List<Type.Key> typeList = toTypeKeys(namedType.types);
          if (namedType.name.equals(LIST_TY_CON) && typeList.size() == 1) {
            // TODO: make 'list' a regular generic type
            return Keys.list(typeList.get(0));
          }
          if (typeList.isEmpty()) {
            return Keys.name(namedType.name);
          } else {
            return Keys.apply(Keys.name(namedType.name), typeList);
          }

        case TY_VAR:
          final Ast.TyVar tyVar = (Ast.TyVar) type;
          return Keys.ordinal(
              tyVarMap.computeIfAbsent(tyVar.name, name -> tyVarMap.size()));

        default:
          throw new AssertionError(
              "cannot convert type " + type + " " + type.op);
      }
    }

    List<Type.Key> toTypeKeys(Iterable<? extends Ast.Type> types) {
      return transformEager(types, this::toTypeKey);
    }
  }

  /**
   * Converts a function declaration to a value declaration. In other words,
   * {@code fun} is syntactic sugar, and this is the de-sugaring machine.
   *
   * <p>For example, {@code fun inc x = x + 1} becomes {@code val rec inc = fn x
   * => x + 1}.
   *
   * <p>If there are multiple arguments, there is one {@code fn} for each
   * argument: {@code fun sum x y = x + y} becomes {@code val rec sum = fn x =>
   * fn y => x + y}.
   *
   * <p>If there are multiple clauses, we generate {@code case}:
   *
   * <pre>
   * {@code fun gcd a 0 = a | gcd a b = gcd b (a mod b)}
   * </pre>
   *
   * <p>becomes
   *
   * <pre>{@code val rec gcd = fn x => fn y =>
   * case (x, y) of
   *     (a, 0) => a
   *   | (a, b) = gcd b (a mod b)}</pre>
   */
  private Ast.ValDecl toValDecl(TypeEnv env, Ast.FunDecl funDecl) {
    final List<Ast.ValBind> valBindList = new ArrayList<>();
    for (Ast.FunBind funBind : funDecl.funBinds) {
      valBindList.add(toValBind(env, funBind));
    }
    return ast.valDecl(funDecl.pos, true, false, valBindList);
  }

  private Ast.ValBind toValBind(TypeEnv env, Ast.FunBind funBind) {
    final List<Ast.Pat> vars;
    Ast.Exp exp;
    Ast.Type returnType = null;
    if (funBind.matchList.size() == 1) {
      final Ast.FunMatch funMatch = funBind.matchList.get(0);
      exp = funMatch.exp;
      vars = funMatch.patList;
      returnType = funMatch.returnType;
    } else {
      final List<String> varNames =
          MapList.of(
              funBind.matchList.get(0).patList.size(), index -> "v" + index);
      vars = transformEager(varNames, v -> ast.idPat(Pos.ZERO, v));
      final List<Ast.Match> matchList = new ArrayList<>();
      Pos prevReturnTypePos = null;
      for (Ast.FunMatch funMatch : funBind.matchList) {
        matchList.add(
            ast.match(
                funMatch.pos, patTuple(env, funMatch.patList), funMatch.exp));
        if (funMatch.returnType != null) {
          if (returnType != null && !returnType.equals(funMatch.returnType)) {
            throw new CompileException(
                "parameter or result constraints of "
                    + "clauses don't agree [tycon mismatch]",
                false,
                prevReturnTypePos.plus(funMatch.pos));
          }
          returnType = funMatch.returnType;
          prevReturnTypePos = funMatch.pos;
        }
      }
      exp = ast.caseOf(Pos.ZERO, idTuple(varNames), matchList);
    }
    if (returnType != null) {
      exp = ast.annotatedExp(exp.pos, exp, returnType);
    }
    final Pos pos = funBind.pos;
    for (Ast.Pat var : Lists.reverse(vars)) {
      exp = ast.fn(pos, ast.match(pos, var, exp));
    }
    return ast.valBind(pos, ast.idPat(pos, funBind.name), exp);
  }

  /**
   * Converts a list of variable names to a variable or tuple.
   *
   * <p>For example, ["x"] becomes "{@code x}" (an {@link Ast.Id}), and ["x",
   * "y"] becomes "{@code (x, y)}" (a {@link Ast.Tuple} of {@link Ast.Id Ids}).
   */
  private static Ast.Exp idTuple(List<String> vars) {
    final List<Ast.Id> idList = Lists.transform(vars, v -> ast.id(Pos.ZERO, v));
    if (idList.size() == 1) {
      return idList.get(0);
    }
    return ast.tuple(Pos.ZERO, idList);
  }

  /** Converts a list of patterns to a singleton pattern or tuple pattern. */
  @SuppressWarnings("SwitchStatementWithTooFewBranches")
  private Ast.Pat patTuple(TypeEnv env, List<Ast.Pat> patList) {
    final List<Ast.Pat> list2 = new ArrayList<>();
    for (int i = 0; i < patList.size(); i++) {
      final Ast.Pat pat = patList.get(i);
      switch (pat.op) {
        case ID_PAT:
          final Ast.IdPat idPat = (Ast.IdPat) pat;
          if (env.has(idPat.name)
              && typeSystem.lookupTyCon(idPat.name) != null) {
            final Term term =
                env.get(typeSystem, idPat.name, TypeEnv.oops(idPat));
            if (term instanceof Sequence
                && ((Sequence) term).operator.equals(FN_TY_CON)) {
              list2.add(
                  ast.conPat(
                      idPat.pos,
                      ast.id(idPat.pos, idPat.name),
                      patList.get(++i)));
            } else {
              list2.add(ast.con0Pat(idPat.pos, ast.id(idPat.pos, idPat.name)));
            }
            break;
          }
          // fall through
        default:
          list2.add(pat);
      }
    }
    if (list2.size() == 1) {
      return list2.get(0);
    } else {
      return ast.tuplePat(Pos.sum(list2), list2);
    }
  }

  /**
   * Derives a type term for a pattern, collecting the names of pattern
   * variables.
   *
   * <p>Unlike {@link #deduceType(TypeEnv, Ast.Exp, Variable)}, return is {@code
   * void}, because the pattern is never modified.
   *
   * @param env Compile-time environment
   * @param pat Pattern AST
   * @param termMap Map from names to bound terms, populated by this method
   * @param labelNames List of names of labels in this pattern and sibling
   *     patterns in a {@code |} match, or null if not a record pattern
   * @param v Type variable that this method should equate the type term that it
   *     derives for this pattern
   * @param accessor Function that, given the term for the type of the
   *     expression that this pattern is applied to, returns the type of this
   *     sub-pattern
   */
  private void deducePatType(
      TypeEnv env,
      Ast.Pat pat,
      Consumer<PatTerm> termMap,
      @Nullable NavigableSet<String> labelNames,
      Variable v,
      UnaryOperator<Term> accessor) {
    switch (pat.op) {
      case BOOL_LITERAL_PAT:
        reg(pat, v, toTerm(PrimitiveType.BOOL));
        return;

      case CHAR_LITERAL_PAT:
        reg(pat, v, toTerm(PrimitiveType.CHAR));
        return;

      case INT_LITERAL_PAT:
        reg(pat, v, toTerm(PrimitiveType.INT));
        return;

      case REAL_LITERAL_PAT:
        reg(pat, v, toTerm(PrimitiveType.REAL));
        return;

      case STRING_LITERAL_PAT:
        reg(pat, v, toTerm(PrimitiveType.STRING));
        return;

      case ID_PAT:
        final Ast.IdPat idPat = (Ast.IdPat) pat;
        final Pair<DataType, Type.Key> pair1 =
            typeSystem.lookupTyCon(idPat.name);
        if (pair1 != null) {
          // It is a zero argument constructor, e.g. the LESS constructor of the
          // 'order' type.
          final DataType dataType0 = pair1.left;
          reg(pat, v, toTerm(dataType0, Subst.EMPTY));
          return;
        }
        termMap.accept(new PatTerm(idPat, v, accessor));
        // fall through

      case WILDCARD_PAT:
        reg(pat, v);
        return;

      case AS_PAT:
        final Ast.AsPat asPat = (Ast.AsPat) pat;
        termMap.accept(new PatTerm(asPat.id, v, accessor));
        deducePatType(env, asPat.pat, termMap, null, v, accessor);
        reg(pat, v);
        return;

      case ANNOTATED_PAT:
        final Ast.AnnotatedPat annotatedPat = (Ast.AnnotatedPat) pat;
        final Type type = toType(annotatedPat.type, typeSystem);
        deducePatType(env, annotatedPat.pat, termMap, null, v, accessor);
        reg(pat, v, toTerm(type, Subst.EMPTY));
        return;

      case TUPLE_PAT:
        final List<Term> typeTerms = new ArrayList<>();
        final Ast.TuplePat tuple = (Ast.TuplePat) pat;
        forEachIndexed(
            tuple.args,
            (arg, i) -> {
              final Variable vArg = unifier.variable();
              final UnaryOperator<Term> accessor2 =
                  term -> ((Sequence) accessor.apply(term)).terms.get(i);
              deducePatType(env, arg, termMap, null, vArg, accessor2);
              typeTerms.add(vArg);
            });
        reg(pat, v, tupleTerm(typeTerms));
        return;

      case RECORD_PAT:
        // First, determine the set of field names.
        //
        // If the pattern is in a 'case', we know the field names from the
        // argument. But if we are in a function, we require at least one of the
        // patterns to not be a wildcard and not have an ellipsis. For example,
        // in
        //
        //  fun f {a=1,...} = 1 | f {b=2,...} = 2
        //
        // we cannot deduce whether a 'c' field is allowed.
        final Ast.RecordPat recordPat = (Ast.RecordPat) pat;
        final NavigableMap<String, Term> labelTerms = mutableMap();
        if (labelNames == null) {
          labelNames = new TreeSet<>(recordPat.args.keySet());
        }
        final SortedMap<String, Ast.Pat> args = mutableMap();
        forEachIndexed(
            labelNames,
            (labelName, i) -> {
              final Variable vArg = unifier.variable();
              labelTerms.put(labelName, vArg);
              final Ast.Pat argPat = recordPat.args.get(labelName);
              if (argPat != null) {
                final UnaryOperator<Term> accessor2 =
                    term -> ((Sequence) accessor.apply(term)).terms.get(i);
                deducePatType(env, argPat, termMap, null, vArg, accessor2);
                args.put(labelName, argPat);
              }
            });
        final Term record = recordTerm(labelTerms);
        if (!recordPat.ellipsis) {
          reg(recordPat, v, record);
          return;
        }
        final Variable v2 = unifier.variable();
        equiv(v2, record);
        actionMap.put(
            v,
            (v3, t, substitution, termPairs) -> {
              // We now know the type of the source record, say
              // "{a: int, b: real}". So, now we can fill out the ellipsis.
              assert v == v3;
              if (t instanceof Sequence) {
                final Sequence sequence = (Sequence) t;
                final List<String> fieldList = fieldList(sequence);
                if (fieldList != null) {
                  final NavigableMap<String, Term> labelTerms2 = mutableMap();
                  forEachIndexed(
                      fieldList,
                      (fieldName, i) -> {
                        if (labelTerms.containsKey(fieldName)) {
                          labelTerms2.put(fieldName, sequence.terms.get(i));
                        }
                      });
                  final Term result2 = substitution.resolve(v2);
                  final Term term2 = recordTerm(labelTerms2);
                  termPairs.accept(result2, term2);
                }
              }
            });
        reg(recordPat, record);
        return;

      case CON_PAT:
        final Ast.ConPat conPat = (Ast.ConPat) pat;
        // e.g. "SOME x" has type "int option"; "x" has type "int".
        final Pair<DataType, Type.Key> pair =
            typeSystem.lookupTyCon(conPat.tyCon.name);
        if (pair == null) {
          throw new AssertionError("not found: " + conPat.tyCon.name);
        }
        final DataType dataType = pair.left;
        final Type argType = pair.right.toType(typeSystem);
        final Variable vArg = unifier.variable();
        deducePatType(env, conPat.pat, termMap, null, vArg, t -> vArg);
        final Term argTerm = toTerm(argType, Subst.EMPTY);
        equiv(vArg, argTerm);
        final Term term = toTerm(dataType, Subst.EMPTY);
        if (argType instanceof TypeVar) {
          // E.g. Suppose arg is "NODE 'b"
          // (therefore argType is "'b", argTerm is "T7"),
          // datatype is "('a,'b) tree"
          // (therefore term is "tree(T8,T9)").
          // We can say that argTerm (T7) is equivalent to
          // the second type parameter (T9).
          // It is sufficient to make vArg equivalent to T9.
          //
          // TODO: handle more complex types, e.g. "NODE (int * 'b)"
          final Sequence sequence = (Sequence) term;
          final TypeVar typeVar = (TypeVar) argType;
          equiv(vArg, sequence.terms.get(typeVar.ordinal));
        }
        reg(pat, v, term);
        return;

      case CON0_PAT:
        final Ast.Con0Pat con0Pat = (Ast.Con0Pat) pat;
        final Pair<DataType, Type.Key> pair0 =
            typeSystem.lookupTyCon(con0Pat.tyCon.name);
        if (pair0 == null) {
          throw new AssertionError();
        }
        final DataType dataType0 = pair0.left;
        reg(pat, v, toTerm(dataType0, Subst.EMPTY));
        return;

      case LIST_PAT:
        final Ast.ListPat list = (Ast.ListPat) pat;
        final Variable vArg2 = unifier.variable();
        for (Ast.Pat arg : list.args) {
          deducePatType(env, arg, termMap, null, vArg2, t -> vArg2);
        }
        reg(list, v, listTerm(vArg2));
        return;

      case CONS_PAT:
        final Variable elementType = unifier.variable();
        final Ast.InfixPat call = (Ast.InfixPat) pat;
        deducePatType(
            env, call.p0, termMap, null, elementType, t -> elementType);
        deducePatType(env, call.p1, termMap, null, v, accessor);
        reg(call, v, listTerm(elementType));
        return;

      default:
        throw new AssertionError("cannot deduce type for pattern " + pat.op);
    }
  }

  /** Registers an infix operator whose type is a given type. */
  private Ast.Exp infix(
      TypeEnv env, Ast.InfixCall call, Variable v, Type type) {
    final Term term = toTerm(type, Subst.EMPTY);
    final Ast.Exp a0 = deduceType(env, call.a0, v);
    final Ast.Exp a1 = deduceType(env, call.a1, v);
    return reg(call.copy(a0, a1), v, term);
  }

  /** Registers an infix operator. */
  private Ast.Exp infix(TypeEnv env, Ast.InfixCall call, Variable v) {
    return deduceType(
        env,
        ast.apply(
            ast.id(Pos.ZERO, call.op.opName),
            ast.tuple(Pos.ZERO, ImmutableList.of(call.a0, call.a1))),
        v);
  }

  /** Registers a prefix operator. */
  private Ast.Exp prefix(TypeEnv env, Ast.PrefixCall call, Variable v) {
    return deduceType(
        env, ast.apply(ast.id(Pos.ZERO, call.op.opName), call.a), v);
  }

  /** Converts a term to a variable. */
  private Variable toVariable(Term term) {
    if (term instanceof Variable) {
      return (Variable) term;
    }
    return equiv(unifier.variable(), term);
  }

  /** Declares that a term is equivalent to a variable. */
  private Variable equiv(Variable v, Term term) {
    if (!v.equals(term)) {
      terms.add(new TermVariable(term, v));
    }
    return v;
  }

  private Sequence listTerm(Term term) {
    return unifier.apply(LIST_TY_CON, term);
  }

  private Sequence bagTerm(Term term) {
    return unifier.apply(BAG_TY_CON, term);
  }

  private Sequence fnTerm(Term arg, Term result) {
    return unifier.apply(FN_TY_CON, arg, result);
  }

  private Term recordTerm(NavigableMap<String, ? extends Term> labelTypes) {
    final NavigableSet<String> labels = labelTypes.navigableKeySet();
    if (TypeSystem.areContiguousIntegers(labels) && labelTypes.size() != 1) {
      return tupleTerm(labelTypes.values());
    }
    return unifier.apply(recordLabel(labels), labelTypes.values());
  }

  private Term tupleTerm(Collection<? extends Term> types) {
    if (types.isEmpty()) {
      return toTerm(PrimitiveType.UNIT);
    }
    return unifier.apply(TUPLE_TY_CON, types);
  }

  private List<Term> toTerms(List<? extends Type> types, Subst subst) {
    return transformEager(types, type -> toTerm(type, subst));
  }

  private List<Term> toTerms(Collection<? extends Type> types, Subst subst) {
    return transformEager(types, type -> toTerm(type, subst));
  }

  private Term toTerm(PrimitiveType type) {
    return unifier.atom(type.moniker);
  }

  private Term toTerm(Type type, Subst subst) {
    switch (type.op()) {
      case ID:
        return toTerm((PrimitiveType) type);
      case TY_VAR:
        final Variable variable = subst.get((TypeVar) type);
        return variable != null ? variable : unifier.variable();
      case DATA_TYPE:
        final DataType dataType = (DataType) type;
        if (dataType.name.equals(BAG_TY_CON)) {
          assert dataType.arguments.size() == 1;
          return bagTerm(toTerm(dataType.arg(0), subst));
        }
        return unifier.apply(
            dataType.name(), toTerms(dataType.arguments, subst));
      case FUNCTION_TYPE:
        final FnType fnType = (FnType) type;
        return fnTerm(
            toTerm(fnType.paramType, subst), toTerm(fnType.resultType, subst));
      case TUPLE_TYPE:
        final TupleType tupleType = (TupleType) type;
        return tupleTerm(transform(tupleType.argTypes, t -> toTerm(t, subst)));
      case RECORD_TYPE:
        final RecordType recordType = (RecordType) type;
        SortedMap<String, Type> argNameTypes = recordType.argNameTypes;
        if (recordType.isProgressive()) {
          argNameTypes = new TreeMap<>(argNameTypes);
          argNameTypes.put(PROGRESSIVE_LABEL, PrimitiveType.UNIT);
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        final NavigableSet<String> labels =
            (NavigableSet) argNameTypes.keySet();
        final String result;
        if (labels.isEmpty()) {
          result = PrimitiveType.UNIT.name();
        } else if (TypeSystem.areContiguousIntegers(labels)) {
          result = TUPLE_TY_CON;
        } else {
          result = recordLabel(labels);
        }
        final List<Term> args = toTerms(argNameTypes.values(), subst);
        return unifier.apply(result, args);
      case LIST:
        final ListType listType = (ListType) type;
        return listTerm(toTerm(listType.elementType, subst));
      case FORALL_TYPE:
        final ForallType forallType = (ForallType) type;
        Subst subst2 = subst;
        for (int i = 0; i < forallType.parameterCount; i++) {
          subst2 = subst2.plus(typeSystem.typeVariable(i), unifier.variable());
        }
        return toTerm(forallType.type, subst2);
      case MULTI_TYPE:
        // We cannot convert an overloaded type into a term; it would have to
        // be a term plus constraint(s). Luckily, this method is called only
        // to generate a plausible type for a record such as the Relational
        // structure, so it works if we just return the first type.
        final MultiType multiType = (MultiType) type;
        return toTerm(multiType.types.get(0), subst);
      default:
        throw new AssertionError("unknown type: " + type.moniker());
    }
  }

  /** Empty type environment. */
  enum EmptyTypeEnv implements TypeEnv {
    INSTANCE;

    @Override
    public Term get(
        TypeSystem typeSystem,
        String name,
        Function<String, RuntimeException> exceptionFactory) {
      throw exceptionFactory.apply(name);
    }

    @Override
    public boolean has(String name) {
      return false;
    }

    @Override
    public @Nullable Type getTypeOpt(String name) {
      return null;
    }

    @Override
    public String toString() {
      return "[]";
    }
  }

  /** Type environment. */
  interface TypeEnv {
    /**
     * Returns a term for the variable with a given name, creating if necessary.
     */
    Term get(
        TypeSystem typeSystem,
        String name,
        Function<String, RuntimeException> exceptionFactory);

    /**
     * Returns the datatype of a variable, or null if no type is known.
     *
     * <p>Generally, type is known for built-in values but not for values in
     * this compilation unit.
     */
    @Nullable
    Type getTypeOpt(String name);

    /** Returns the number of times a name is bound. */
    default int count(String name) {
      return 0;
    }

    /** Collects terms that are instance of {@code name}. */
    default void collectInstances(
        TypeSystem typeSystem, String name, Consumer<Term> consumer) {}

    /**
     * Returns whether a variable of the given name is defined in this
     * environment.
     */
    boolean has(String name);

    /** Returns whether a given name is overloaded in this environment. */
    default boolean hasOverloaded(String name) {
      return false;
    }

    default TypeEnv bind(
        String name, Kind kind, Function<TypeSystem, Term> termFactory) {
      return new BindTypeEnv(name, kind, termFactory, this);
    }

    default TypeEnv bind(String name, Kind kind, Term term) {
      return bind(name, kind, new SimpleTermFactory(term));
    }

    default TypeEnv bind(String name, Term term) {
      return bind(name, getKind(name, term), new SimpleTermFactory(term));
    }

    default Kind getKind(String name, Term term) {
      if (term instanceof Sequence
          && ((Sequence) term).operator.equals(OVERLOAD_TY_CON)) {
        return Kind.OVER;
      } else if (hasOverloaded(name)) {
        return Kind.INST;
      } else {
        return Kind.VAL;
      }
    }

    /** Exception factory where a missing symbol is an internal error. */
    static Function<String, RuntimeException> oops(AstNode node) {
      return name -> new RuntimeException("oops, should have " + node);
    }

    /**
     * Exception factory where a missing symbol is because we are not in a
     * query.
     */
    static Function<String, RuntimeException> onlyValidInQuery(AstNode node) {
      return name ->
          new CompileException(
              "'" + node + "' is only valid in a query", false, node.pos);
    }

    /** Exception factory where a missing symbol is a user error. */
    static Function<String, RuntimeException> unbound(Ast.Id id) {
      return name ->
          new CompileException(
              "unbound variable or constructor: " + name, false, id.pos);
    }
  }

  /** Type environment based on an {@link Environment}. */
  private class EnvironmentTypeEnv implements TypeEnv {
    private final Environment env;
    private final TypeEnv parent;

    EnvironmentTypeEnv(Environment env, TypeEnv parent) {
      this.env = requireNonNull(env);
      this.parent = requireNonNull(parent);
    }

    @Override
    public Term get(
        TypeSystem typeSystem,
        String name,
        Function<String, RuntimeException> exceptionFactory) {
      Binding binding = env.getOpt(name);
      if (binding != null) {
        return toTerm(binding.id.type, Subst.EMPTY);
      }
      return parent.get(typeSystem, name, exceptionFactory);
    }

    @Override
    public boolean has(String name) {
      return env.getOpt(name) != null || parent.has(name);
    }

    @Override
    public boolean hasOverloaded(String name) {
      Binding binding = env.getOpt(name);
      if (binding != null) {
        return binding.kind != Kind.VAL;
      }
      return parent.hasOverloaded(name);
    }

    @Override
    public @Nullable Type getTypeOpt(String name) {
      Binding binding = env.getTop(name);
      if (binding != null) {
        if (binding.kind == Kind.VAL) {
          return binding.id.type;
        }
        return typeSystem.multi(
            transformEager(
                env.getOverloads(binding.overloadId), Core.Pat::type));
      }
      return parent.getTypeOpt(name);
    }
  }

  /** Factory that always returns a given {@link Term}. */
  static class SimpleTermFactory implements Function<TypeSystem, Term> {
    private final Term term;

    SimpleTermFactory(Term term) {
      this.term = term;
    }

    @Override
    public Term apply(TypeSystem typeSystem) {
      return term;
    }

    @Override
    public String toString() {
      return term.toString();
    }
  }

  /** Pair consisting of a term and a variable. */
  private static class TermVariable {
    final Term term;
    final Variable variable;

    private TermVariable(Term term, Variable variable) {
      this.term = term;
      this.variable = variable;
    }

    @Override
    public String toString() {
      return term + " = " + variable;
    }
  }

  /**
   * A type environment that consists of a type environment plus one binding.
   */
  private static class BindTypeEnv implements TypeEnv {
    private final String definedName;
    private final Kind kind;
    private final Function<TypeSystem, Term> termFactory;
    private final TypeEnv parent;

    BindTypeEnv(
        String definedName,
        Kind kind,
        Function<TypeSystem, Term> termFactory,
        TypeEnv parent) {
      this.definedName = requireNonNull(definedName);
      this.kind = kind;
      this.termFactory = requireNonNull(termFactory);
      this.parent = requireNonNull(parent);
    }

    @Override
    public int count(String name) {
      int count = 0;
      for (BindTypeEnv e = this; ; e = (BindTypeEnv) e.parent) {
        if (e.definedName.equals(name)) {
          ++count;
        }
        if (!(e.parent instanceof BindTypeEnv)) {
          return count + e.parent.count(name);
        }
      }
    }

    @Override
    public Term get(
        TypeSystem typeSystem,
        String name,
        Function<String, RuntimeException> exceptionFactory) {
      for (BindTypeEnv e = this; ; e = (BindTypeEnv) e.parent) {
        if (e.definedName.equals(name)) {
          return e.termFactory.apply(typeSystem);
        }
        if (!(e.parent instanceof BindTypeEnv)) {
          return e.parent.get(typeSystem, name, exceptionFactory);
        }
      }
    }

    @Override
    public @Nullable Type getTypeOpt(String name) {
      return getAncestor().getTypeOpt(name);
    }

    /**
     * Returns the nearest ancestor of this environment that is not a {@link
     * BindTypeEnv}.
     */
    private TypeEnv getAncestor() {
      BindTypeEnv e = this;
      while (e.parent instanceof BindTypeEnv) {
        e = (BindTypeEnv) e.parent;
      }
      return e.parent;
    }

    @Override
    public boolean has(String name) {
      for (BindTypeEnv e = this; ; e = (BindTypeEnv) e.parent) {
        if (e.definedName.equals(name)) {
          return true;
        }
        if (!(e.parent instanceof BindTypeEnv)) {
          return e.parent.has(name);
        }
      }
    }

    @Override
    public boolean hasOverloaded(String name) {
      for (BindTypeEnv e = this; ; e = (BindTypeEnv) e.parent) {
        // A variable is overloaded if any of its declarations are overloaded.
        // So, if this declaration matches name but is not overloaded, carry on
        // looking further up the stack.
        if (e.definedName.equals(name) && e.kind != Kind.VAL) {
          return true;
        }
        if (!(e.parent instanceof BindTypeEnv)) {
          return e.parent.hasOverloaded(name);
        }
      }
    }

    @Override
    public void collectInstances(
        TypeSystem typeSystem, String name, Consumer<Term> consumer) {
      for (BindTypeEnv e = this; ; e = (BindTypeEnv) e.parent) {
        if (e.definedName.equals(name)) {
          if (e.kind == Kind.OVER) {
            // We have reached the 'over <name>' declaration, and therefore
            // there are no more instances of the overload to see.
            return;
          }
          consumer.accept(e.termFactory.apply(typeSystem));
        }
        if (!(e.parent instanceof BindTypeEnv)) {
          // If we're at this point it's probably an error. We should have seen
          // 'over <name>'.
          e.parent.collectInstances(typeSystem, name, consumer);
          break;
        }
      }
    }

    @Override
    public String toString() {
      final Map<String, String> map = new LinkedHashMap<>();
      for (BindTypeEnv e = this; ; ) {
        map.putIfAbsent(e.definedName, e.termFactory.toString());
        if (e.parent instanceof BindTypeEnv) {
          e = (BindTypeEnv) e.parent;
        } else {
          return map.toString();
        }
      }
    }
  }

  /**
   * Contains a {@link TypeEnv} and adds to it by calling {@link
   * TypeEnv#bind(String, Kind, Function)}.
   */
  private class TypeEnvHolder implements TriConsumer<String, Kind, Type> {
    private TypeEnv typeEnv;

    TypeEnvHolder(TypeEnv typeEnv) {
      this.typeEnv = requireNonNull(typeEnv);
    }

    @Override
    public void accept(String name, Kind kind, Type type) {
      if (kind == Kind.INST && !typeEnv.hasOverloaded(name)) {
        // If we're about to push a 'val inst', push an 'over' first.
        Type overload = typeSystem.lookup(BuiltIn.Datatype.OVERLOAD);
        typeEnv = typeEnv.bind(name, Kind.OVER, typeToTerm(overload));
      }
      typeEnv = typeEnv.bind(name, kind, typeToTerm(type));
    }

    private Function<TypeSystem, Term> typeToTerm(Type type) {
      return new Function<TypeSystem, Term>() {
        @Override
        public Term apply(TypeSystem typeSystem_) {
          return TypeResolver.this.toTerm(type, Subst.EMPTY);
        }

        @Override
        public String toString() {
          return type.moniker();
        }
      };
    }

    public void bind(String name, Term term) {
      typeEnv = typeEnv.bind(name, term);
    }
  }

  /** Result of validating a declaration. */
  public static class Resolved {
    public final Environment env;
    public final Ast.Decl originalNode;
    public final Ast.Decl node;
    public final TypeMap typeMap;

    private Resolved(
        Environment env,
        Ast.Decl originalNode,
        Ast.Decl node,
        TypeMap typeMap) {
      this.env = env;
      this.originalNode = requireNonNull(originalNode);
      this.node = requireNonNull(node);
      this.typeMap = requireNonNull(typeMap);
      checkArgument(
          originalNode instanceof Ast.FunDecl
              ? node instanceof Ast.ValDecl
              : originalNode.getClass() == node.getClass());
    }

    static Resolved of(
        Environment env,
        Ast.Decl originalNode,
        Ast.Decl node,
        TypeMap typeMap) {
      return new Resolved(env, originalNode, node, typeMap);
    }

    public Ast.Exp exp() {
      if (node instanceof Ast.ValDecl) {
        final Ast.ValDecl valDecl = (Ast.ValDecl) node;
        if (valDecl.valBinds.size() == 1) {
          final Ast.ValBind valBind = valDecl.valBinds.get(0);
          return valBind.exp;
        }
      }
      throw new AssertionError("not an expression: " + node);
    }
  }

  /** Substitution. */
  private abstract static class Subst {
    static final Subst EMPTY = new EmptySubst();

    Subst plus(TypeVar typeVar, Variable variable) {
      return new PlusSubst(this, typeVar, variable);
    }

    abstract Variable get(TypeVar typeVar);
  }

  /** Empty substitution. */
  private static class EmptySubst extends Subst {
    @Override
    public String toString() {
      return "[]";
    }

    @Override
    Variable get(TypeVar typeVar) {
      return null;
    }
  }

  /**
   * Substitution that adds one (type, variable) assignment to a parent
   * substitution.
   */
  private static class PlusSubst extends Subst {
    final Subst parent;
    final TypeVar typeVar;
    final Variable variable;

    PlusSubst(Subst parent, TypeVar typeVar, Variable variable) {
      this.parent = parent;
      this.typeVar = typeVar;
      this.variable = variable;
    }

    @Override
    Variable get(TypeVar typeVar) {
      return typeVar.equals(this.typeVar) ? variable : parent.get(typeVar);
    }

    @Override
    public String toString() {
      final Map<TypeVar, Term> map = new LinkedHashMap<>();
      for (PlusSubst e = this; ; ) {
        map.putIfAbsent(e.typeVar, e.variable);
        if (e.parent instanceof PlusSubst) {
          e = (PlusSubst) e.parent;
        } else {
          return map.toString();
        }
      }
    }
  }

  /** Error while deducing type. */
  public static class TypeException extends CompileException {
    public TypeException(String message, Pos pos) {
      super(message, false, pos);
    }
  }

  /**
   * Visitor that expands progressive types if they are used in field
   * references.
   */
  static class FieldExpander extends EnvVisitor {
    static FieldExpander create(TypeSystem typeSystem, Environment env) {
      return new FieldExpander(typeSystem, env, new ArrayDeque<>());
    }

    private FieldExpander(
        TypeSystem typeSystem, Environment env, Deque<FromContext> fromStack) {
      super(typeSystem, env, fromStack);
    }

    @Override
    protected EnvVisitor push(Environment env) {
      return new FieldExpander(typeSystem, env, fromStack);
    }

    @Override
    protected void visit(Ast.Apply apply) {
      super.visit(apply);
      expandField(env, apply);
    }

    @Override
    protected void visit(Ast.Id id) {
      super.visit(id);
      expandField(env, id);
    }

    private @Nullable TypedValue expandField(Environment env, Ast.Exp exp) {
      switch (exp.op) {
        case APPLY:
          final Ast.Apply apply = (Ast.Apply) exp;
          if (apply.fn.op == Op.RECORD_SELECTOR) {
            final Ast.RecordSelector selector = (Ast.RecordSelector) apply.fn;
            final TypedValue typedValue = expandField(env, apply.arg);
            if (typedValue != null) {
              typedValue.discoverField(typeSystem, selector.name);
              return typedValue.fieldValueAs(selector.name, TypedValue.class);
            }
          }
          return null;

        case ID:
          final Binding binding = env.getOpt(((Ast.Id) exp).name);
          if (binding != null && binding.value instanceof TypedValue) {
            return (TypedValue) binding.value;
          }
          // fall through

        default:
          return null;
      }
    }
  }

  /**
   * Output of the type resolution of a {@code from} step, and input to the next
   * step.
   */
  private static class Triple {
    final TypeEnv env;
    final Variable v;
    /** Collection (list or bag) type; null if not a collection. */
    final @Nullable Variable c;

    private Triple(TypeEnv env, Variable v, Variable c) {
      this.env = requireNonNull(env);
      this.v = requireNonNull(v);
      this.c = c;
    }

    /** Represents a singleton, not a collection. */
    static Triple singleton(TypeEnv env, Variable v) {
      return new Triple(env, v, null);
    }

    static Triple of(TypeEnv env, Variable v, Variable c) {
      return new Triple(env.bind(BuiltIn.Z_CURRENT.mlName, v), v, c);
    }

    Triple withV(Variable v) {
      return v == this.v ? this : new Triple(env, v, c);
    }

    Triple withEnv(TypeEnv env) {
      return env == this.env ? this : new Triple(env, v, c);
    }
  }

  /** Instance of an overloaded function. */
  private static class Inst {
    private final String name;
    private final Variable vFn;
    private final Variable vArg;
    private final Variable vResult;

    Inst(String name, Variable vFn, Variable vArg, Variable vResult) {
      this.name = name;
      this.vFn = vFn;
      this.vArg = vArg;
      this.vResult = vResult;
    }

    @Override
    public String toString() {
      return format("overload '%s' %s = %s -> %s", name, vFn, vArg, vResult);
    }
  }

  private enum CollectionType {
    BAG,
    LIST,
    BOTH
  }

  private static class PatTerm {
    final Ast.IdPat id;
    final Term term;
    final UnaryOperator<Term> accessor;

    private PatTerm(Ast.IdPat id, Term term, UnaryOperator<Term> accessor) {
      this.id = requireNonNull(id);
      this.term = requireNonNull(term);
      this.accessor = requireNonNull(accessor);
    }

    @Override
    public String toString() {
      return format("id %s term %s", id, term);
    }
  }
}

// End TypeResolver.java
