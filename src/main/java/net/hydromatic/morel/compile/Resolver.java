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
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.AstBuilder.ast;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.anyMatch;
import static net.hydromatic.morel.util.Static.last;
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.skipLast;
import static net.hydromatic.morel.util.Static.transform;
import static net.hydromatic.morel.util.Static.transformEager;
import static org.apache.calcite.util.Util.first;
import static org.apache.calcite.util.Util.intersects;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Range;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.CoreBuilder;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.AliasType;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.QualifiedType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeShuttle;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;
import net.hydromatic.morel.type.TypeVisitor;
import net.hydromatic.morel.type.TypedValue;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.PairList;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Converts AST expressions to Core expressions. */
public class Resolver {
  final TypeMap typeMap;
  final NameGenerator nameGenerator;
  final Environment env;
  final @Nullable Session session;
  final Core.@Nullable Exp current;

  /**
   * The field that holds the ordinal of the current row, if the step being
   * resolved reads {@code ordinal}.
   *
   * <p>A step cannot compute its own ordinal &mdash; only a "yield" evaluates
   * an expression exactly once per input row &mdash; so the step is preceded by
   * a "yield" that materializes the ordinal as a field, and references to
   * {@code ordinal} resolve to this pattern. Null if the step does not read
   * {@code ordinal}.
   *
   * <p>Propagates into sub-resolvers, and therefore into a nested query. That
   * is deliberate: in
   *
   * <pre>{@code
   * from i in [10,20]
   *   yield {i, js = (from j in [i + ordinal])}
   * }</pre>
   *
   * <p>the nested query's scan expression is evaluated once per row of the
   * enclosing query, so its {@code ordinal} is the enclosing row's.
   */
  final Core.@Nullable IdPat ordinalPat;

  final AggregateResolver aggregateResolver;
  final Map<String, Pair<Core.IdPat, List<Core.IdPat>>> resolvedOverloads;

  /**
   * Dictionary parameters in scope while compiling the body of a qualified
   * (overload-constrained) value. Maps an overloaded name to the {@link
   * Core.IdPat} of the dictionary parameter that supplies its instance. An
   * overloaded application whose argument type is not concrete compiles to a
   * reference to this parameter rather than to a specific instance
   * (hydromatic/morel#426 milestone 2, dictionary passing). Shared across
   * sub-resolvers so that nested lambdas see the parameters.
   */
  final Map<String, Core.IdPat> dictionaryParams;

  /**
   * Contains variable declarations whose type at the point they are used is
   * different (more specific) than in their declaration.
   *
   * <p>For example, the infix operator "op +" has type "&alpha; * &alpha;
   * &rarr;" in the base environment, but at point of use might instead be "int
   * * int &rarr; int". This map will contain a new {@link Core.IdPat} for all
   * points that use it with that second type. Effectively, it is a phantom
   * declaration, in a {@code let} that doesn't exist. Without this shared
   * declaration, all points have their own distinct {@link Core.IdPat}, which
   * the {@link Analyzer} will think is used just once.
   */
  private final Map<Pair<Core.NamedPat, Type>, Core.NamedPat> variantIdMap;

  private Resolver(
      TypeMap typeMap,
      NameGenerator nameGenerator,
      Map<Pair<Core.NamedPat, Type>, Core.NamedPat> variantIdMap,
      Map<String, Pair<Core.IdPat, List<Core.IdPat>>> resolvedOverloads,
      Map<String, Core.IdPat> dictionaryParams,
      Environment env,
      @Nullable Session session,
      Core.@Nullable Exp current,
      Core.@Nullable IdPat ordinalPat,
      AggregateResolver aggregateResolver) {
    this.typeMap = typeMap;
    this.nameGenerator = nameGenerator;
    this.variantIdMap = variantIdMap;
    this.resolvedOverloads = resolvedOverloads;
    this.dictionaryParams = dictionaryParams;
    this.env = env;
    this.session = session;
    this.current = current;
    this.ordinalPat = ordinalPat;
    this.aggregateResolver = aggregateResolver;
  }

  /** Creates a root Resolver. */
  public static Resolver of(
      TypeMap typeMap, Environment env, @Nullable Session session) {
    NameGenerator nameGenerator =
        session == null ? new NameGenerator() : session.nameGenerator;
    return new Resolver(
        typeMap,
        nameGenerator,
        new HashMap<>(),
        new HashMap<>(),
        new HashMap<>(),
        env,
        session,
        null,
        null,
        AggregateResolver.UNSUPPORTED);
  }

  /** Binds a Resolver to a new environment. */
  public Resolver withEnv(Environment env) {
    if (env == this.env) {
      return this;
    }
    return new Resolver(
        typeMap,
        nameGenerator,
        variantIdMap,
        resolvedOverloads,
        dictionaryParams,
        env,
        session,
        current,
        ordinalPat,
        aggregateResolver);
  }

  /**
   * Binds a Resolver to an environment that consists of the current environment
   * plus some bindings.
   */
  public final Resolver withEnv(Iterable<Binding> bindings) {
    return withEnv(Environments.bind(env, bindings));
  }

  private Resolver withCurrent(Core.Exp current) {
    if (current == this.current) {
      return this;
    }
    return new Resolver(
        typeMap,
        nameGenerator,
        variantIdMap,
        resolvedOverloads,
        dictionaryParams,
        env,
        session,
        current,
        ordinalPat,
        aggregateResolver);
  }

  /**
   * Binds a Resolver to the field that holds the current row's ordinal.
   *
   * @see #ordinalPat
   */
  private Resolver withOrdinalPat(Core.@Nullable IdPat ordinalPat) {
    if (ordinalPat == this.ordinalPat) {
      return this;
    }
    return new Resolver(
        typeMap,
        nameGenerator,
        variantIdMap,
        resolvedOverloads,
        dictionaryParams,
        env,
        session,
        current,
        ordinalPat,
        aggregateResolver);
  }

  /**
   * Creates a Resolver that is able to translate a {@code compute} clause.
   *
   * <p>The challenge is to split expressions such as "{@code e0 = 1 + avg over
   * e.salary * 2.0}". It is split as follows:
   *
   * <ul>
   *   <li>{@code e.salary * 2.0} is the pre-expression, and becomes {@code p0}
   *   <li>{@code avg of p0} is the aggregate, and becomes {@code a0}
   *   <li>{@code 1 + a0} is the post-expression, and becomes {@code e0}
   * </ul>
   *
   * <p>If the pre- and post-expressions are non-trivial we end up with a {@link
   * Core.Yield} on a {@link Core.Group} on a {@link Core.Yield}.
   *
   * <p>What is the environment? If the query is "{@code from e in emps group
   * e.deptno compute sum over e.salary * 2.0}", then this resolver (used for
   * resolving the outer expressions) has an environment that includes the group
   * key, {@code deptno}. The aggregate resolver has environment that includes
   * the group key, {@code deptno}, and the input variables, in this case {@code
   * e}.
   */
  Resolver withAggregateResolver(
      Environment baseEnv,
      Core.StepEnv stepEnv,
      Collection<? extends Core.IdPat> groupKeys,
      PairList<Core.IdPat, Core.Aggregate> aggregates) {
    final Environment outerEnv =
        Environments.bind(baseEnv, transform(groupKeys, Binding::of));
    final Environment innerEnv = Environments.bind(outerEnv, stepEnv.bindings);
    final Resolver innerResolver =
        new Resolver(
            typeMap,
            nameGenerator,
            variantIdMap,
            resolvedOverloads,
            dictionaryParams,
            innerEnv,
            session,
            current,
            ordinalPat,
            AggregateResolver.UNSUPPORTED);
    final AggregateResolver aggregateResolver =
        new AggregateResolverImpl(
            groupKeys, stepEnv.ordered, innerResolver, aggregates);
    return new Resolver(
        typeMap,
        nameGenerator,
        variantIdMap,
        resolvedOverloads,
        dictionaryParams,
        outerEnv,
        session,
        current,
        ordinalPat,
        aggregateResolver);
  }

  public Core.Decl toCore(Ast.Decl node) {
    switch (node.op) {
      case OVER_DECL:
        return toCore(typeMap.typeSystem, (Ast.OverDecl) node);

      case VAL_DECL:
        return toCore((Ast.ValDecl) node);

      case TYPE_DECL:
        return toCore(typeMap.typeSystem, (Ast.TypeDecl) node);

      case DATATYPE_DECL:
        return toCore((Ast.DatatypeDecl) node);

      case SIGNATURE_DECL:
        // Signatures are interface declarations that don't compile to anything.
        // Return a no-op declaration that evaluates to unit.
        return core.nonRecValDecl(
            node.pos,
            core.idPat(PrimitiveType.UNIT, "_signature", 0),
            null,
            core.unitLiteral());

      default:
        throw new AssertionError(
            "unknown decl [" + node.op + ", " + node + "]");
    }
  }

  /** Converts an {@link Ast.OverDecl} to a Core {@link Core.OverDecl}. */
  public Core.Decl toCore(TypeSystem typeSystem, Ast.OverDecl overDecl) {
    Type overloadType = typeSystem.lookup(BuiltIn.Datatype.OVERLOAD);
    Core.IdPat idPat = core.idPat(overloadType, overDecl.pat.name, 0);
    return core.overDecl(idPat);
  }

  /** Converts an {@link Ast.TypeDecl} to a {@link Core.TypeDecl}. */
  public Core.Decl toCore(TypeSystem typeSystem, Ast.TypeDecl typeDecl) {
    return core.typeDecl(transformEager(typeDecl.binds, this::toCore));
  }

  /**
   * Converts a simple {@link Ast.ValDecl}, of the form {@code val v = e}, to a
   * Core {@link Core.ValDecl}.
   *
   * <p>Declarations such as {@code val (x, y) = (1, 2)} and {@code val emp ::
   * rest = emps} are considered complex, and are not handled by this method.
   *
   * <p>Likewise recursive declarations.
   */
  public Core.ValDecl toCore(Ast.ValDecl valDecl) {
    final List<Binding> bindings = new ArrayList<>(); // discard
    final ResolvedValDecl resolvedValDecl = resolveValDecl(valDecl, bindings);
    final Core.NonRecValDecl nonRecValDecl =
        core.nonRecValDecl(
            resolvedValDecl.patExps.get(0).pos,
            resolvedValDecl.pat,
            valDecl.inst && resolvedValDecl.pat instanceof Core.IdPat
                ? getOverload((Core.IdPat) resolvedValDecl.pat)
                : null,
            resolvedValDecl.exp);
    return resolvedValDecl.rec
        ? core.recValDecl(ImmutableList.of(nonRecValDecl))
        : nonRecValDecl;
  }

  private Core.IdPat getOverload(Core.IdPat pat) {
    for (Pair<Core.IdPat, List<Core.IdPat>> pair : resolvedOverloads.values()) {
      if (pair.right.contains(pat)) {
        return pair.left;
      }
    }
    throw new AssertionError("not found: " + pat);
  }

  public Core.DatatypeDecl toCore(Ast.DatatypeDecl datatypeDecl) {
    final List<Binding> bindings = new ArrayList<>(); // populated, never read
    final ResolvedDatatypeDecl resolvedDatatypeDecl =
        resolveDatatypeDecl(datatypeDecl, bindings);
    return resolvedDatatypeDecl.toDecl();
  }

  private ResolvedDecl resolve(Ast.Decl decl, List<Binding> bindings) {
    switch (decl.op) {
      case DATATYPE_DECL:
        return resolveDatatypeDecl((Ast.DatatypeDecl) decl, bindings);
      case OVER_DECL:
        return resolveOverDecl((Ast.OverDecl) decl, bindings);
      case VAL_DECL:
        return resolveValDecl((Ast.ValDecl) decl, bindings);
      default:
        throw new AssertionError(decl);
    }
  }

  private ResolvedDatatypeDecl resolveDatatypeDecl(
      Ast.DatatypeDecl decl, List<Binding> bindings) {
    final List<DataType> dataTypes = new ArrayList<>();
    for (Ast.DatatypeBind bind : decl.binds) {
      final DataType dataType = toCore(bind);
      dataTypes.add(dataType);
      dataType
          .typeConstructors
          .keySet()
          .forEach(
              name ->
                  bindings.add(typeMap.typeSystem.bindTyCon(dataType, name)));
    }
    return new ResolvedDatatypeDecl(ImmutableList.copyOf(dataTypes));
  }

  private static ResolvedDecl resolveOverDecl(
      Ast.OverDecl ignoredDecl, List<Binding> ignoredBindings) {
    return new ResolvedDecl() {
      @Override
      Core.Exp toExp(Core.Exp resultExp) {
        return resultExp;
      }
    };
  }

  private ResolvedValDecl resolveValDecl(
      Ast.ValDecl valDecl, List<Binding> bindings) {
    final boolean composite = valDecl.valBinds.size() > 1;
    final Map<Ast.Pat, Ast.Exp> matches = new LinkedHashMap<>();
    valDecl.valBinds.forEach(
        valBind -> flatten(matches, composite, valBind.pat, valBind.exp));

    final List<PatExp> patExps = new ArrayList<>();
    final boolean inst = valDecl.inst;
    if (valDecl.rec) {
      final List<Core.Pat> pats = new ArrayList<>();
      matches.forEach((pat, exp) -> pats.add(toCore(pat, inst)));
      pats.forEach(
          p -> Compiles.acceptBinding(typeMap.typeSystem, p, bindings));
      final Resolver r = withEnv(bindings);
      final Iterator<Core.Pat> patIter = pats.iterator();
      matches.forEach(
          (pat, exp) ->
              patExps.add(
                  new PatExp(
                      patIter.next(), r.toCore(exp), pat.pos.plus(exp.pos))));
    } else {
      matches.forEach(
          (pat, exp) -> {
            Core.Pat corePat = toCore(pat, inst);
            if (corePat instanceof Core.NamedPat) {
              final Type realType = typeMap.getRealType(pat);
              if (realType != null) {
                corePat = ((Core.NamedPat) corePat).withType(realType);
              }
            }
            // If this binding is qualified (uses an overloaded name at an
            // abstract type), compile its value with dictionary parameters and
            // give the pattern a qualified type.
            final List<QualifiedType.Predicate> matching =
                !inst && corePat instanceof Core.NamedPat
                    ? matchingPredicates(corePat.type)
                    : ImmutableList.of();
            final Core.Exp coreExp;
            if (matching.isEmpty()) {
              coreExp = toCore(exp);
            } else {
              // A bare reference to another qualified value is already
              // dictionary-abstracted, so alias it rather than wrapping it
              // again (dictionaries are supplied at this binding's use sites).
              coreExp =
                  exp.op == Op.ID
                      ? toCore(exp)
                      : toCoreWithDictionaries(matching, exp);
              corePat =
                  ((Core.NamedPat) corePat)
                      .withType(
                          typeMap.typeSystem.qualifiedType(
                              matching, corePat.type));
            }
            patExps.add(new PatExp(corePat, coreExp, pat.pos.plus(exp.pos)));
          });
      patExps.forEach(
          x -> Compiles.acceptBinding(typeMap.typeSystem, x.pat, bindings));
    }

    // Convert recursive to non-recursive if the bound variable is not
    // referenced in its definition. For example,
    //   val rec inc = fn i => i + 1
    // can be converted to
    //   val inc = fn i => i + 1
    // because "i + 1" does not reference "inc".
    boolean rec = valDecl.rec && references(patExps);
    // Transform "let val v1 = E1 and v2 = E2 in E end"
    // to "let val v = (v1, v2) in case v of (E1, E2) => E end"
    final Core.Pat pat0;
    final Core.Exp exp;
    if (composite) {
      final List<Core.Pat> pats = transform(patExps, x -> x.pat);
      final List<Core.Exp> exps = transform(patExps, x -> x.exp);
      pat0 = core.tuplePat(typeMap.typeSystem, pats);
      exp = core.tuple((RecordLikeType) pat0.type, exps);
    } else {
      final PatExp patExp = patExps.get(0);
      pat0 = patExp.pat;
      exp = patExp.exp;
    }
    final Core.NamedPat pat0Named;
    if (pat0 instanceof Core.NamedPat) {
      pat0Named = (Core.NamedPat) pat0;
    } else {
      pat0Named = core.asPat(exp.type, "it", nameGenerator, pat0);
    }
    final Core.NamedPat pat = qualify(pat0Named);

    return new ResolvedValDecl(rec, ImmutableList.copyOf(patExps), pat, exp);
  }

  /**
   * If the type resolver deduced overload predicates for this declaration, and
   * they constrain the type variables of {@code pat}, wraps {@code pat}'s type
   * in a {@link QualifiedType}. This is what makes an echoed binding print with
   * a qualified type, e.g. {@code val demo = fn : {foo : 'a -> 'b} => 'a ->
   * 'b}.
   */
  private Core.NamedPat qualify(Core.NamedPat pat) {
    final List<QualifiedType.Predicate> matching = matchingPredicates(pat.type);
    if (matching.isEmpty()) {
      return pat;
    }
    return pat.withType(typeMap.typeSystem.qualifiedType(matching, pat.type));
  }

  /**
   * Returns the deduced overload predicates whose type variables occur in
   * {@code patType} (so they belong on this binding), or empty if there are
   * none or {@code patType} is already qualified.
   */
  private List<QualifiedType.Predicate> matchingPredicates(Type patType) {
    final List<QualifiedType.Predicate> predicates = typeMap.getPredicates();
    if (predicates.isEmpty() || patType instanceof QualifiedType) {
      return ImmutableList.of();
    }
    final Set<Integer> patVars = typeVarOrdinals(patType);
    if (patVars.isEmpty()) {
      return ImmutableList.of();
    }
    final List<QualifiedType.Predicate> matching = new ArrayList<>();
    for (QualifiedType.Predicate predicate : predicates) {
      if (!disjoint(typeVarOrdinals(predicate.type), patVars)) {
        matching.add(predicate);
      }
    }
    return matching;
  }

  /**
   * Compiles the value of a qualified binding, introducing one dictionary
   * parameter per predicate. Inside {@code exp}, an overloaded name used at an
   * abstract type compiles to a reference to its dictionary parameter (see
   * {@link #fnToCore}); the compiled value is wrapped in one curried lambda per
   * predicate, so at a use site the caller supplies the instances as ordinary
   * arguments (see {@link #dictionaryArgsForUse}).
   */
  private Core.Exp toCoreWithDictionaries(
      List<QualifiedType.Predicate> predicates, Ast.Exp exp) {
    final List<Core.IdPat> dictPats = new ArrayList<>();
    for (QualifiedType.Predicate p : predicates) {
      final Core.IdPat dictPat =
          core.idPat(p.type, () -> nameGenerator.getPrefixed("dict"));
      dictPats.add(dictPat);
      dictionaryParams.put(p.name, dictPat);
    }
    Core.Exp coreExp = toCore(exp);
    for (QualifiedType.Predicate p : predicates) {
      dictionaryParams.remove(p.name);
    }
    // Wrap in one curried lambda per predicate; the first predicate is the
    // outermost parameter, matching the argument order at the use site.
    for (int i = dictPats.size() - 1; i >= 0; i--) {
      final Core.IdPat dictPat = dictPats.get(i);
      final FnType fnType =
          typeMap.typeSystem.fnType(dictPat.type, coreExp.type);
      coreExp = core.fn(fnType, dictPat, coreExp);
    }
    return coreExp;
  }

  /** Returns the ordinals of the type variables that occur in a type. */
  private static Set<Integer> typeVarOrdinals(Type type) {
    final Set<Integer> ordinals = new HashSet<>();
    type.accept(
        new TypeVisitor<Void>() {
          @Override
          public Void visit(TypeVar typeVar) {
            ordinals.add(typeVar.ordinal);
            return null;
          }
        });
    return ordinals;
  }

  /**
   * Returns the pattern that an unbounded scan, "{@code from p}", should scan
   * the extent of.
   *
   * <p>Such a scan yields the distinct values of the variables that {@code p}
   * binds, so the shape of {@code p} does not matter; only its variables do.
   * The extent to scan is therefore the product of their types, and the
   * constructors, literals and wildcards in between fall away:
   *
   * <ul>
   *   <li>"{@code from SOME (b: bool)}" scans the extent of {@code bool}
   *   <li>"{@code from (b: bool, _)}" scans the extent of {@code bool}, since a
   *       wildcard binds nothing and so must not multiply the rows
   *   <li>"{@code from b1 :: b2 :: nil}" scans the extent of {@code bool *
   *       bool}, one value per element of the list
   *   <li>"{@code from SOME 1}" binds nothing, and scans the extent of {@code
   *       unit}: exactly one row, which is what a 'from' with no scans means
   * </ul>
   *
   * <p>An "as" pattern is left alone. Its variable names the whole value and is
   * determined by the variables within it, so the two are not independent and
   * their product would be wrong.
   */
  private static Core.Pat extentPat(TypeSystem typeSystem, Core.Pat pat) {
    final List<Core.NamedPat> vars = pat.expand();
    for (Core.NamedPat var : vars) {
      if (var instanceof Core.AsPat) {
        return pat;
      }
    }
    switch (vars.size()) {
      case 0:
        return core.wildcardPat(PrimitiveType.UNIT);
      case 1:
        return vars.get(0);
      default:
        return core.tuplePat(typeSystem, vars);
    }
  }

  private static boolean disjoint(Set<Integer> a, Set<Integer> b) {
    for (Integer i : a) {
      if (b.contains(i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns whether any of the expressions in {@code exps} references and of
   * the variables defined in {@code pats}.
   *
   * <p>This method is used to decide whether it is safe to convert a recursive
   * declaration into a non-recursive one.
   */
  private boolean references(List<PatExp> patExps) {
    final Set<Core.NamedPat> refSet = new HashSet<>();
    final ReferenceFinder finder =
        new ReferenceFinder(
            typeMap.typeSystem,
            Environments.empty(),
            refSet,
            new ArrayDeque<>());
    patExps.forEach(x -> x.exp.accept(finder));

    final Set<Core.NamedPat> defSet = new HashSet<>();
    final Visitor v =
        new Visitor() {
          @Override
          protected void visit(Core.IdPat idPat) {
            defSet.add(idPat);
          }
        };
    patExps.forEach(x -> x.pat.accept(v));

    return intersects(refSet, defSet);
  }

  private AliasType toCore(Ast.TypeBind bind) {
    return (AliasType) typeMap.typeSystem.lookup(bind.name.name);
  }

  private DataType toCore(Ast.DatatypeBind bind) {
    final Type type = typeMap.typeSystem.lookup(bind.name.name);
    return type instanceof ForallType
        ? (DataType) ((ForallType) type).type
        : (DataType) type;
  }

  /**
   * Visitor that finds all references to unbound variables in an expression.
   */
  static class ReferenceFinder extends EnvVisitor {
    final Set<Core.NamedPat> set;

    protected ReferenceFinder(
        TypeSystem typeSystem,
        Environment env,
        Set<Core.NamedPat> set,
        Deque<FromContext> fromStack) {
      super(typeSystem, env, fromStack);
      this.set = set;
    }

    @Override
    protected ReferenceFinder push(Environment env) {
      return new ReferenceFinder(typeSystem, env, set, fromStack);
    }

    @Override
    protected void visit(Core.Id id) {
      if (env.getOpt(id.idPat) == null) {
        set.add(id.idPat);
      }
      super.visit(id);
    }
  }

  Core.Exp toCore(Ast.Exp exp) {
    return toCore(exp, null);
  }

  Core.Exp toCore(Ast.Exp exp, Ast.@Nullable Id id) {
    switch (exp.op) {
      case BOOL_LITERAL:
        return core.boolLiteral((Boolean) ((Ast.Literal) exp).value);
      case CHAR_LITERAL:
        return core.charLiteral((Character) ((Ast.Literal) exp).value);
      case INT_LITERAL:
        return core.intLiteral((BigDecimal) ((Ast.Literal) exp).value);
      case REAL_LITERAL:
        return ((Ast.Literal) exp).value instanceof BigDecimal
            ? core.realLiteral((BigDecimal) ((Ast.Literal) exp).value)
            : core.realLiteral((Float) ((Ast.Literal) exp).value);
      case STRING_LITERAL:
        return core.stringLiteral((String) ((Ast.Literal) exp).value);
      case UNIT_LITERAL:
        return core.unitLiteral();
      case WORD_LITERAL:
        return core.wordLiteral((BigDecimal) ((Ast.Literal) exp).value);
      case ANNOTATED_EXP:
        return toCore(((Ast.AnnotatedExp) exp).exp);
      case ID:
        return toCore((Ast.Id) exp);
      case OP_SECTION:
        return toCore((Ast.OpSection) exp);
      case CURRENT:
        return toCore((Ast.Current) exp);
      case TYPE_STRING:
        return toCore((Ast.TypeString) exp);
      case ELEMENTS:
        return toCore((Ast.Elements) exp);
      case ORDINAL:
        return toCore((Ast.Ordinal) exp);
      case ANDALSO:
      case ORELSE:
        return toCore((Ast.InfixCall) exp);
      case IMPLIES:
        return toCoreImplies(
            ((Ast.InfixCall) exp).a0, ((Ast.InfixCall) exp).a1);
      case APPLY:
        return toCore((Ast.Apply) exp);
      case AGGREGATE:
        return toCore((Ast.Aggregate) exp, id);
      case FN:
        return toCore((Ast.Fn) exp);
      case IF:
        return toCore((Ast.If) exp);
      case RAISE:
        return toCore((Ast.Raise) exp);
      case CASE:
        return toCore((Ast.Case) exp);
      case LET:
        return toCore((Ast.Let) exp);
      case FROM:
      case EXISTS:
      case FORALL:
        return toCore((Ast.Query) exp);
      case TUPLE:
        return toCore((Ast.Tuple) exp);
      case RECORD:
        return toCore((Ast.Record) exp);
      case RECORD_SELECTOR:
        return toCore((Ast.RecordSelector) exp);
      case LIST:
        return toCore((Ast.ListExp) exp);
      case FROM_EQ:
        return toCoreFromEq(((Ast.PrefixCall) exp).a);
      default:
        throw new AssertionError("unknown exp " + exp.op);
    }
  }

  private Core.Id toCore(Ast.Id id) {
    final Binding binding = env.getOpt(id.name);
    checkNotNull(binding, "not found", id);
    final Core.NamedPat idPat = getIdPat(id, binding.id);
    return core.id(id.pos, idPat);
  }

  private Core.Exp toCore(Ast.OpSection opSection) {
    final Binding binding = env.getOpt("op " + opSection.name);
    checkNotNull(binding, "not found", opSection);

    // Just return a reference to the operator binding
    // The operator is already defined as a function value
    final Core.NamedPat idPat = getIdPat(opSection, binding.id);
    return core.id(opSection.pos, idPat);
  }

  private Core.Exp toCore(Ast.Current ignoredCurrent) {
    return requireNonNull(this.current);
  }

  private Core.Exp toCore(Ast.TypeString typeString) {
    // Render the operand's inferred type to a string. The operand is not
    // converted to Core, so it is never evaluated.
    final Type type = typeMap.getType(typeString.exp);
    return core.stringLiteral(type.moniker());
  }

  private Core.Exp toCore(Ast.Ordinal ordinal) {
    if (ordinalPat != null) {
      // The step is preceded by a "yield" that materialized the ordinal as a
      // field; read that field.
      return core.id(ordinalPat);
    }
    // The step is itself a "yield", and can hold the call.
    Core.Literal fn =
        core.functionLiteral(typeMap.typeSystem, BuiltIn.Z_ORDINAL);
    Core.Tuple arg = core.tuple(typeMap.typeSystem);
    return core.apply(ordinal.pos, PrimitiveType.INT, fn, arg);
  }

  /** Converts an id in a declaration to Core. */
  private Core.IdPat toCorePat(Ast.Id id) {
    final Type type = typeMap.getType(id);
    return core.idPat(type, id.name, nameGenerator::inc);
  }

  /**
   * Converts an Id that is a reference to a variable into an IdPat that
   * represents its declaration.
   */
  private Core.NamedPat getIdPat(AstNode id, Core.NamedPat coreId) {
    final Type type = typeMap.getType(id);
    if (type == coreId.type) {
      return coreId;
    }
    // The required type is different from the binding type, presumably more
    // specific. Create a new IdPat, reusing an existing IdPat if there was
    // one for the same type.
    return variantIdMap.computeIfAbsent(
        Pair.of(coreId, type), k -> k.left.withType(k.right));
  }

  private Core.Tuple toCore(Ast.Tuple tuple) {
    return core.tuple(
        (RecordLikeType) typeMap.getType(tuple),
        transformEager(tuple.args, this::toCore));
  }

  /**
   * Converts a record expression. It has no modifiers; {@link TypeResolver}
   * replaces a record that has them with the {@code let}s they desugar to.
   */
  private Core.Tuple toCore(Ast.Record record) {
    final RecordLikeType type = (RecordLikeType) typeMap.getType(record);
    return core.tuple(type, transformEager(record.args(), this::toCore));
  }

  private Core.Exp toCore(Ast.ListExp list) {
    final ListType type = (ListType) typeMap.getType(list);
    return core.apply(
        list.pos,
        type,
        core.functionLiteral(type, BuiltIn.Z_LIST),
        core.tuple(
            typeMap.typeSystem, null, transformEager(list.args, this::toCore)));
  }

  /**
   * Translates "x" in "from e = x". Desugar to the same as if they had written
   * "from e in [x]".
   */
  private Core.Exp toCoreFromEq(Ast.Exp exp) {
    final Type type = typeMap.getType(exp);
    final ListType listType = typeMap.typeSystem.listType(type);
    return core.apply(
        exp.pos,
        listType,
        core.functionLiteral(typeMap.typeSystem, BuiltIn.Z_LIST),
        core.tuple(typeMap.typeSystem, toCore(exp)));
  }

  private Core.Apply toCore(Ast.Apply apply) {
    final Core.Exp coreArg = toCore(apply.arg);
    Type type = typeMap.getType(apply);
    final Core.Exp coreFn;
    if (apply.fn.op == Op.RECORD_SELECTOR) {
      final Ast.RecordSelector recordSelector = (Ast.RecordSelector) apply.fn;
      if (recordSelector.safe) {
        return toCoreSafeNav(apply, recordSelector, coreArg, type);
      }
      RecordLikeType recordType = (RecordLikeType) coreArg.type;
      if (coreArg.type.isProgressive()) {
        Object o = valueOf(env, coreArg);
        if (o instanceof TypedValue) {
          final TypedValue typedValue = (TypedValue) o;
          TypedValue typedValue2 =
              typedValue.discoverField(typeMap.typeSystem, recordSelector.name);
          recordType =
              (RecordLikeType) typedValue2.typeKey().toType(typeMap.typeSystem);
        }
      }
      coreFn =
          core.recordSelector(
              typeMap.typeSystem, recordType, recordSelector.name);
      if (type.op() == Op.TY_VAR && coreFn.type.op() == Op.FUNCTION_TYPE
          || type.isProgressive()
          || type instanceof ListType && type.elementType().isProgressive()) {
        // If we are dereferencing a field in a progressive type, the type
        // available now may be more precise than the deduced type.
        type = ((FnType) coreFn.type).resultType;
      }
    } else {
      Core.Exp fn = fnToCore(apply.fn, typeMap.getType(apply.arg));
      // If the function is a qualified-typed binding, supply its dictionaries
      // (the resolved instances) before the real argument (milestone 2).
      final List<Core.Exp> dicts = dictionaryArgsForUse(apply.fn);
      if (dicts != null) {
        for (Core.Exp dict : dicts) {
          fn = core.apply(apply.pos, type, fn, dict);
        }
      }
      coreFn = fn;
    }
    return core.apply(apply.pos, type, coreFn, coreArg);
  }

  /**
   * If {@code fn} names a qualified-typed binding, returns the dictionary
   * arguments to pass at this use site — one per predicate, each the instance
   * selected by the (now concrete) type — in predicate order. Returns null if
   * {@code fn} is not qualified or an instance cannot be selected (in which
   * case the value keeps its milestone-1 placeholder behavior).
   */
  private @Nullable List<Core.Exp> dictionaryArgsForUse(Ast.Exp fn) {
    if (!(fn instanceof Ast.Id)) {
      return null;
    }
    final Binding top = env.getTop(((Ast.Id) fn).name);
    if (top == null) {
      return null;
    }
    Type schemeType = top.id.type;
    while (schemeType instanceof ForallType) {
      schemeType = ((ForallType) schemeType).type;
    }
    if (!(schemeType instanceof QualifiedType)) {
      return null;
    }
    final QualifiedType qType = (QualifiedType) schemeType;
    final Type useType = typeMap.getType(fn);
    final Map<Integer, Type> subst = qType.type.unifyWith(useType);
    if (subst == null) {
      return null;
    }
    final List<Core.Exp> dicts = new ArrayList<>();
    for (QualifiedType.Predicate p : qType.predicates) {
      final Type predArgType = substitute(((FnType) p.type).paramType, subst);
      final Core.IdPat instId = selectInstanceId(p.name, predArgType);
      if (instId != null) {
        dicts.add(core.id(instId));
      } else {
        // The instance is not concrete here (we are inside another qualified
        // value); forward the enclosing dictionary parameter for this name.
        final Core.IdPat dictParam = dictionaryParams.get(p.name);
        if (dictParam == null) {
          return null;
        }
        dicts.add(core.id(dictParam));
      }
    }
    return dicts;
  }

  /**
   * Selects the unique overload instance of {@code name} callable with an
   * argument of {@code argType}, or null if not exactly one matches.
   */
  private Core.@Nullable IdPat selectInstanceId(String name, Type argType) {
    // Instances of an overload declared in the current compilation unit are in
    // 'resolvedOverloads'; instances from an enclosing environment are reached
    // via the overload id. (Mirrors the two branches of fnToCore.)
    final List<Core.IdPat> instances;
    if (resolvedOverloads.containsKey(name)) {
      instances = requireNonNull(resolvedOverloads.get(name).right);
    } else {
      final Binding top = env.getTop(name);
      if (top == null || top.overloadId == null) {
        return null;
      }
      instances = env.getOverloads(top.overloadId);
    }
    final List<Core.IdPat> matching = new ArrayList<>();
    for (Core.IdPat idPat : instances) {
      if (idPat.type.canCallArgOf(argType)) {
        matching.add(idPat);
      }
    }
    return matching.size() == 1 ? matching.get(0) : null;
  }

  /** Applies a type-variable substitution to a type. */
  private Type substitute(Type type, Map<Integer, Type> subst) {
    return type.accept(
        new TypeShuttle(typeMap.typeSystem) {
          @Override
          public Type visit(TypeVar typeVar) {
            final Type t = subst.get(typeVar.ordinal);
            return t != null ? t : typeVar;
          }
        });
  }

  /**
   * Lowers safe navigation {@code e?.f} by tunneling through the receiver's
   * functor layers (option, list): {@code F1.map (F2.map (... (Fn.map #f))) e}.
   * The field's own type is preserved (no flattening).
   */
  private Core.Apply toCoreSafeNav(
      Ast.Apply apply,
      Ast.RecordSelector recordSelector,
      Core.Exp coreArg,
      Type type) {
    final TypeSystem ts = typeMap.typeSystem;
    // Peel the functor layers (outermost first) down to the record.
    final List<BuiltIn> maps = new ArrayList<>();
    Type t = coreArg.type;
    while (true) {
      if (t instanceof ListType) {
        maps.add(BuiltIn.LIST_MAP);
        t = t.elementType();
      } else if (t.op() == Op.DATA_TYPE
          && functorMap(((DataType) t).name()) != null) {
        maps.add(functorMap(((DataType) t).name()));
        t = ((DataType) t).arguments.get(0);
      } else {
        break;
      }
    }

    final Core.RecordSelector selector =
        core.recordSelector(ts, (RecordLikeType) t, recordSelector.name);
    // Build "F1.map (F2.map (... (Fn.map #f)))", innermost layer first, then
    // apply to the receiver.
    Core.Exp fn = selector;
    Type inType = t; // record type
    Type outType = ((FnType) selector.type).resultType; // field type
    for (int i = maps.size() - 1; i >= 0; i--) {
      final BuiltIn mapBuiltIn = maps.get(i);
      final Type fInType = wrapFunctor(ts, mapBuiltIn, inType);
      final Type fOutType = wrapFunctor(ts, mapBuiltIn, outType);
      fn =
          core.apply(
              apply.pos,
              ts.fnType(fInType, fOutType),
              core.functionLiteral(ts, mapBuiltIn),
              fn);
      inType = fInType;
      outType = fOutType;
    }
    return core.apply(apply.pos, type, fn, coreArg);
  }

  /**
   * Returns the {@code map} built-in for a safe-navigation functor (option,
   * bag, vector) named {@code name}, or null if {@code name} is not such a
   * functor. (List is handled separately, as it is a {@link ListType} rather
   * than a {@link DataType}.)
   */
  private static @Nullable BuiltIn functorMap(String name) {
    switch (name) {
      case "option":
        return BuiltIn.OPTION_MAP;
      case "bag":
        return BuiltIn.BAG_MAP;
      case "vector":
        return BuiltIn.VECTOR_MAP;
      default:
        return null;
    }
  }

  /**
   * Builds {@code F elementType}, where {@code F} is the functor of {@code
   * mapBuiltIn} (LIST_MAP, OPTION_MAP, BAG_MAP or VECTOR_MAP).
   */
  private static Type wrapFunctor(
      TypeSystem ts, BuiltIn mapBuiltIn, Type elementType) {
    switch (mapBuiltIn) {
      case LIST_MAP:
        return ts.listType(elementType);
      case BAG_MAP:
        return ts.bagType(elementType);
      case VECTOR_MAP:
        return ts.vector(elementType);
      default:
        return ts.option(elementType);
    }
  }

  /**
   * Converts a function (inside an {@link Ast.Apply} or {@link Ast.Aggregate})
   * to a core expression, dealing with overloads (if necessary) based on
   * argument type.
   */
  private Core.Exp fnToCore(Ast.Exp fn, Type argType) {
    // Inside the body of a qualified value, an overloaded name resolves to the
    // dictionary parameter that carries its instance (milestone 2).
    if (fn instanceof Ast.Id) {
      final Core.IdPat dictPat = dictionaryParams.get(((Ast.Id) fn).name);
      if (dictPat != null) {
        return core.id(dictPat);
      }
    }
    // The comparison operators '<', '<=', '>', '>=' are polymorphic, but word
    // comparison must be unsigned, so route word operands to the Word structure
    // members (which use Long.compareUnsigned).
    if (fn.op == Op.ID
        && argType instanceof TupleType
        && !((TupleType) argType).argTypes.isEmpty()
        && ((TupleType) argType).argType(0) == PrimitiveType.WORD) {
      final BuiltIn op = BuiltIn.BY_ML_NAME.get(((Ast.Id) fn).name);
      if (op != null && op.toWord() != op) {
        return core.functionLiteral(typeMap.typeSystem, op.toWord());
      }
    }
    @Nullable
    Binding top = fn.op == Op.ID ? env.getTop(((Ast.Id) fn).name) : null;
    if (fn.op == Op.ID // TODO: change to 'top != null'
        && resolvedOverloads.containsKey(((Ast.Id) fn).name)) {
      final List<Core.IdPat> matchingBindings = new ArrayList<>();
      Pair<Core.IdPat, List<Core.IdPat>> pair =
          resolvedOverloads.get(((Ast.Id) fn).name);
      for (Core.IdPat idPat : requireNonNull(pair.right)) {
        if (idPat.type.canCallArgOf(argType)) {
          matchingBindings.add(idPat);
        }
      }
      if (matchingBindings.size() != 1) {
        // The argument type is not concrete, so we cannot select an instance:
        // this is an overloaded application inside a qualified-typed value.
        // Milestone 2 will pass the instance as a dictionary; for now emit a
        // placeholder that fails only if the value is actually applied.
        return unresolvedOverload(fn, argType);
      }
      return CoreBuilder.core.id(matchingBindings.get(0));
    } else if (top != null && top.isInst()) {
      requireNonNull(top.overloadId);
      final List<Core.IdPat> matchingIds = new ArrayList<>();
      for (Core.IdPat idPat : env.getOverloads(top.overloadId)) {
        if (idPat.type.canCallArgOf(argType)) {
          matchingIds.add(idPat);
        }
      }
      if (matchingIds.size() != 1) {
        return unresolvedOverload(fn, argType);
      }
      return core.id(getIdPat(fn, matchingIds.get(0)));
    } else {
      return toCore(fn);
    }
  }

  /**
   * Builds a placeholder for an overloaded application whose argument type is
   * not concrete (so no instance can be selected statically). The placeholder
   * is a function {@code fn v => raise (Fail "...")} of the same type as the
   * overloaded function at this site; it type-checks and compiles, but raises
   * if it is ever applied.
   *
   * <p>This supports Milestone 1 of hydromatic/morel#426, where a value with a
   * qualified type can be declared and its type echoed, but not yet evaluated.
   * Milestone 2 will replace this with dictionary passing.
   */
  private Core.Exp unresolvedOverload(Ast.Exp fn, Type argType) {
    final TypeSystem typeSystem = typeMap.typeSystem;
    final Type fnType0 = typeMap.getType(fn);
    final FnType fnType =
        fnType0 instanceof FnType
            ? (FnType) fnType0
            : typeSystem.fnType(argType, typeMap.getType(fn));
    final String name = fn instanceof Ast.Id ? ((Ast.Id) fn).name : "?";
    // Reference the 'Fail of string' constructor at its *function* type,
    // 'string -> exn'. (A constructor Core.Id is matched by name and ordinal,
    // not type, so this resolves to the same runtime value; but the function
    // type keeps the Inliner from mistaking it for a string value.)
    final Type exnType =
        typeSystem.lookup(BuiltIn.Constructor.EXN_FAIL.datatype);
    final Core.Id failCon =
        core.id(
            core.idPat(
                typeSystem.fnType(PrimitiveType.STRING, exnType),
                BuiltIn.Constructor.EXN_FAIL.constructor,
                0));
    final String msg =
        "overloaded '%s' cannot yet be applied at an abstract type";
    final Core.Exp failExn =
        core.apply(
            Pos.ZERO, exnType, failCon, core.stringLiteral(format(msg, name)));
    final Core.Exp raiseExp = core.raise(Pos.ZERO, fnType.resultType, failExn);
    final Core.IdPat param =
        core.idPat(fnType.paramType, () -> nameGenerator.getPrefixed("v"));
    return core.fn(fnType, param, raiseExp);
  }

  static @Nullable Object valueOf(Environment env, Core.Exp exp) {
    if (exp instanceof Core.Literal) {
      return ((Core.Literal) exp).value;
    }
    if (exp.op == Op.ID) {
      final Core.Id id = (Core.Id) exp;
      Binding binding = env.getOpt(id.idPat);
      if (binding != null) {
        return binding.value;
      }
    }
    if (exp.op == Op.APPLY) {
      final Core.Apply apply = (Core.Apply) exp;
      if (apply.fn.op == Op.RECORD_SELECTOR) {
        final Core.RecordSelector recordSelector =
            (Core.RecordSelector) apply.fn;
        final Object o = valueOf(env, apply.arg);
        if (o instanceof TypedValue) {
          return ((TypedValue) o)
              .fieldValueAs(recordSelector.slot, Object.class);
        } else if (o instanceof List) {
          @SuppressWarnings("unchecked")
          List<Object> list = (List<Object>) o;
          return list.get(recordSelector.slot);
        }
      }
    }
    return null; // not constant
  }

  private Core.Exp toCore(Ast.Aggregate aggregate, Ast.@Nullable Id id) {
    final FnType fnType = (FnType) typeMap.getType(aggregate.aggregate);
    final boolean orderedAgg = fnType.paramType instanceof ListType;
    return aggregateResolver.toCore(aggregate, orderedAgg, this, id);
  }

  private Core.Exp toCore(Ast.Elements elements) {
    // Translate "elements" as if it were an aggregate "Fn.id over current",
    // which contains all rows in the current group.
    return aggregateResolver.toCore(elements, this);
  }

  private Core.RecordSelector toCore(Ast.RecordSelector recordSelector) {
    final FnType fnType = (FnType) typeMap.getType(recordSelector);
    return core.recordSelector(
        typeMap.typeSystem,
        (RecordLikeType) fnType.paramType,
        recordSelector.name);
  }

  private Core.Apply toCore(Ast.InfixCall call) {
    Core.Exp core0 = toCore(call.a0);
    Core.Exp core1 = toCore(call.a1);
    // The comparison operators '<', '<=', '>', '>=' are polymorphic, but word
    // comparison must be unsigned, so route word operands to the Word structure
    // members (which use Long.compareUnsigned).
    BuiltIn builtIn = toBuiltIn(call.op);
    if (core0.type == PrimitiveType.WORD) {
      builtIn = builtIn.toWord();
    }
    return core.apply(
        call.pos,
        typeMap.getType(call),
        core.functionLiteral(typeMap.typeSystem, builtIn),
        core.tuple(typeMap.typeSystem, core0, core1));
  }

  /** Translate "p implies q" as "(not p) orelse q". */
  private Core.Exp toCoreImplies(Ast.Exp a0, Ast.Exp a1) {
    Core.Exp core0 = toCore(a0);
    Core.Exp core1 = toCore(a1);
    return core.orElse(
        typeMap.typeSystem, core.not(typeMap.typeSystem, core0), core1);
  }

  /** Returns the built-in function that an infix operator resolves to. */
  private BuiltIn toBuiltIn(Op op) {
    switch (op) {
      case AT:
        return BuiltIn.LIST_AT;
      case CONS:
        return BuiltIn.OP_CONS;
      case EQ:
        return BuiltIn.OP_EQ;
      case GE:
        return BuiltIn.OP_GE;
      case GT:
        return BuiltIn.OP_GT;
      case LE:
        return BuiltIn.OP_LE;
      case LT:
        return BuiltIn.OP_LT;
      case NE:
        return BuiltIn.OP_NE;
      case ANDALSO:
        return BuiltIn.Z_ANDALSO;
      case ORELSE:
        return BuiltIn.Z_ORELSE;
      case PLUS:
        return BuiltIn.REAL_OP_PLUS;
      default:
        throw new AssertionError(op);
    }
  }

  /**
   * Returns the infix operator that a built-in function renders as, or null if
   * it has no infix form. The reverse of {@link #toBuiltIn}; used to convert an
   * optimized expression back to human-readable Morel code (see {@link
   * Core.Apply#unparse}).
   */
  public static @Nullable Op toOp(BuiltIn builtIn) {
    switch (builtIn) {
      case LIST_AT:
        return Op.AT;
      case OP_CONS:
        return Op.CONS;
      case OP_EQ:
        return Op.EQ;
      case OP_GE:
        return Op.GE;
      case OP_GT:
        return Op.GT;
      case OP_LE:
        return Op.LE;
      case OP_LT:
        return Op.LT;
      case OP_NE:
        return Op.NE;
      case Z_ANDALSO:
        return Op.ANDALSO;
      case Z_ORELSE:
        return Op.ORELSE;
      case INT_OP_PLUS:
      case REAL_OP_PLUS:
        return Op.PLUS;
      default:
        return null;
    }
  }

  private Core.Fn toCore(Ast.Fn fn) {
    final FnType type = (FnType) typeMap.getType(fn);
    final List<Core.Match> matchList =
        transformEager(fn.matchList, this::toCore);
    return core.fn(fn.pos, type, matchList, nameGenerator::inc);
  }

  private Core.Case toCore(Ast.If if_) {
    return core.ifThenElse(
        toCore(if_.condition), toCore(if_.ifTrue), toCore(if_.ifFalse));
  }

  private Core.Raise toCore(Ast.Raise raise) {
    return core.raise(raise.pos, typeMap.getType(raise), toCore(raise.exp));
  }

  private Core.Case toCore(Ast.Case case_) {
    return core.caseOf(
        case_.pos,
        typeMap.getType(case_),
        toCore(case_.exp),
        transformEager(case_.matchList, this::toCore));
  }

  private Core.Exp toCore(Ast.Let let) {
    return flattenLet(let.decls, let.exp);
  }

  private Core.Exp flattenLet(List<Ast.Decl> decls, Ast.Exp exp) {
    //   flattenLet(val x :: xs = [1, 2, 3] and (y, z) = (2, 4), x + y)
    // becomes
    //   let v = ([1, 2, 3], (2, 4)) in case v of (x :: xs, (y, z)) => x + y end
    if (decls.isEmpty()) {
      return toCore(exp);
    }
    final Ast.Decl decl = decls.get(0);
    final List<Binding> bindings = new ArrayList<>();
    final ResolvedDecl resolvedDecl = resolve(decl, bindings);
    final Core.Exp e2 = withEnv(bindings).flattenLet(skip(decls), exp);
    return resolvedDecl.toExp(e2);
  }

  static void flatten(
      Map<Ast.Pat, Ast.Exp> matches,
      boolean flatten,
      Ast.Pat pat,
      Ast.Exp exp) {
    if (flatten && pat.op == Op.TUPLE_PAT && exp.op == Op.TUPLE) {
      forEach(
          ((Ast.TuplePat) pat).args,
          ((Ast.Tuple) exp).args,
          (p, e) -> flatten(matches, true, p, e));
    } else {
      matches.put(pat, exp);
    }
  }

  private Core.Pat toCore(Ast.Pat pat) {
    return toCore(pat, false);
  }

  /**
   * Converts a pattern to Core, reusing an existing {@link Core.IdPat} if
   * {@code inst}.
   */
  private Core.Pat toCore(Ast.Pat pat, boolean inst) {
    final Type type = typeMap.getType(pat);
    if (inst && pat.op == Op.ID_PAT) {
      Ast.IdPat idPat = (Ast.IdPat) pat;
      // This identifier is overloaded. Generate a new name for every
      // occurrence.
      Pair<Core.IdPat, List<Core.IdPat>> pair =
          resolvedOverloads.computeIfAbsent(
              idPat.name,
              name -> {
                final Binding top = env.getTop(idPat.name);
                final List<Core.IdPat> coreIds = new ArrayList<>();
                Core.IdPat coreOverloadId;
                if (top != null) {
                  coreOverloadId = requireNonNull(top.overloadId);
                  env.collect(
                      top.overloadId, b -> coreIds.add((Core.IdPat) b.id));
                } else {
                  coreOverloadId = core.idPat(type, name, nameGenerator::inc);
                }
                return Pair.of(coreOverloadId, coreIds);
              });
      Core.IdPat corePat =
          core.idPat(type, () -> nameGenerator.getPrefixed(idPat.name));
      pair.right.add(corePat);
      return corePat;
    }
    return toCore(pat, type, type);
  }

  private Core.Pat toCore(Ast.Pat pat, Type targetType) {
    final Type type = typeMap.getType(pat);
    return toCore(pat, type, targetType);
  }

  /**
   * Converts a pattern to Core.
   *
   * <p>Expands a pattern if it is a record pattern that has an ellipsis or if
   * the arguments are not in the same order as the labels in the type.
   */
  private Core.Pat toCore(Ast.Pat pat, Type type, Type targetType) {
    final TupleType tupleType;
    switch (pat.op) {
      case BOOL_LITERAL_PAT:
      case CHAR_LITERAL_PAT:
      case INT_LITERAL_PAT:
      case REAL_LITERAL_PAT:
      case STRING_LITERAL_PAT:
      case WORD_LITERAL_PAT:
        return core.literalPat(pat.op, type, ((Ast.LiteralPat) pat).value);

      case WILDCARD_PAT:
        return core.wildcardPat(type);

      case ID_PAT:
        final Ast.IdPat idPat = (Ast.IdPat) pat;
        if (type.op() == Op.DATA_TYPE
            && ((DataType) type).typeConstructors.containsKey(idPat.name)) {
          return core.con0Pat((DataType) type, idPat.name);
        }
        return core.idPat(type, idPat.name, nameGenerator::inc);

      case AS_PAT:
        final Ast.AsPat asPat = (Ast.AsPat) pat;
        return core.asPat(
            type, asPat.id.name, nameGenerator, toCore(asPat.pat));

      case ANNOTATED_PAT:
        // There is no annotated pat in core, because all patterns have types.
        final Ast.AnnotatedPat annotatedPat = (Ast.AnnotatedPat) pat;
        return toCore(annotatedPat.pat);

      case CON_PAT:
        final Ast.ConPat conPat = (Ast.ConPat) pat;
        return core.conPat(type, conPat.tyCon.name, toCore(conPat.pat));

      case CON0_PAT:
        final Ast.Con0Pat con0Pat = (Ast.Con0Pat) pat;
        return core.con0Pat((DataType) type, con0Pat.tyCon.name);

      case CONS_PAT:
        // Cons "::" is an infix operator in Ast, a type constructor in Core, so
        // Ast.InfixPat becomes Core.ConPat.
        final Ast.InfixPat infixPat = (Ast.InfixPat) pat;
        final Type type0 = typeMap.getType(infixPat.p0);
        final Type type1 = typeMap.getType(infixPat.p1);
        tupleType = typeMap.typeSystem.tupleType(type0, type1);
        return core.consPat(
            type,
            BuiltIn.OP_CONS.mlName,
            core.tuplePat(tupleType, toCore(infixPat.p0), toCore(infixPat.p1)));

      case LIST_PAT:
        final Ast.ListPat listPat = (Ast.ListPat) pat;
        return core.listPat(type, transformEager(listPat.args, this::toCore));

      case RECORD_PAT:
        final Ast.RecordPat recordPat = (Ast.RecordPat) pat;
        if (targetType == PrimitiveType.UNIT) {
          // Unit record is a special case, it has no fields.
          // Its type is not RecordType, but RecordLikeType.
          return core.wildcardPat(targetType);
        }
        // The target may be a tuple type; a tuple is a record whose labels
        // are ordinals, and "{1 = x, 2 = y}" is a valid pattern for it.
        final RecordLikeType recordLikeType = (RecordLikeType) targetType;
        final ImmutableList.Builder<Core.Pat> args = ImmutableList.builder();
        recordLikeType
            .argNameTypes()
            .forEach(
                (label, argType) -> {
                  final Ast.Pat argPat = recordPat.args.get(label);
                  final Core.Pat corePat =
                      argPat != null
                          ? toCore(argPat)
                          : core.wildcardPat(argType);
                  args.add(corePat);
                });
        return recordLikeType instanceof RecordType
            ? core.recordPat((RecordType) recordLikeType, args.build())
            : core.tuplePat(recordLikeType, args.build());

      case TUPLE_PAT:
        final Ast.TuplePat tuplePat = (Ast.TuplePat) pat;
        final List<Core.Pat> argList =
            transformEager(tuplePat.args, this::toCore);
        return core.tuplePat((RecordLikeType) type, argList);

      default:
        throw new AssertionError("unknown pat " + pat.op);
    }
  }

  private Core.Match toCore(Ast.Match match) {
    final Core.Pat pat = toCore(match.pat);
    final List<Binding> bindings = new ArrayList<>();
    Compiles.acceptBinding(typeMap.typeSystem, pat, bindings);
    final Core.Exp exp = withEnv(bindings).toCore(match.exp);
    return core.match(match.pos, pat, exp);
  }

  Core.Exp toCore(Ast.Query query) {
    final Type type = typeMap.getType(query);
    final Core.Exp coreFrom = new FromResolver().run(query);
    checkArgument(
        subsumes(type, coreFrom.type()),
        "Conversion to core did not preserve type: expected [%s] "
            + "actual [%s] from [%s]",
        type,
        coreFrom.type,
        coreFrom);
    return coreFrom;
  }

  /**
   * An actual type subsumes an expected type if it is equal or if progressive
   * record types have been expanded.
   */
  public static boolean subsumes(Type actualType, Type expectedType) {
    switch (actualType.op()) {
      case LIST:
        if (expectedType.op() != Op.LIST) {
          return false;
        }
        return subsumes(actualType.elementType(), expectedType.elementType());
      case RECORD_TYPE:
        if (expectedType.op() != Op.RECORD_TYPE) {
          return false;
        }
        if (actualType.isProgressive()) {
          return true;
        }
        final SortedMap<String, Type> actualMap =
            ((RecordType) actualType).argNameTypes();
        final SortedMap<String, Type> expectedMap =
            ((RecordType) expectedType).argNameTypes();
        if (actualMap.size() != expectedMap.size()) {
          return false;
        }
        for (Pair<Map.Entry<String, Type>, Map.Entry<String, Type>> pair :
            Pair.zip(actualMap.entrySet(), expectedMap.entrySet())) {
          final Map.Entry<String, Type> actual = pair.left;
          final Map.Entry<String, Type> expected = pair.right;
          if (!actual.getKey().equals(expected.getKey())) {
            return false;
          }
          if (!subsumes(actual.getValue(), expected.getValue())) {
            return false;
          }
        }
        // fall through
      default:
        return actualType.equals(expectedType);
    }
  }

  /**
   * Resolved declaration. It can be converted to an expression given a result
   * expression; depending on sub-type, that expression will either be a {@code
   * let} (for a {@link Ast.ValDecl} or a {@code local} (for a {@link
   * Ast.DatatypeDecl}.
   */
  public abstract static class ResolvedDecl {
    /** Converts the declaration to a {@code let} or a {@code local}. */
    abstract Core.Exp toExp(Core.Exp resultExp);
  }

  /** Resolved value declaration. */
  class ResolvedValDecl extends ResolvedDecl {
    final boolean rec;
    final boolean composite;
    final ImmutableList<PatExp> patExps;
    final Core.NamedPat pat;
    final Core.Exp exp;

    ResolvedValDecl(
        boolean rec,
        ImmutableList<PatExp> patExps,
        Core.NamedPat pat,
        Core.Exp exp) {
      this.rec = rec;
      this.composite = patExps.size() > 1;
      this.patExps = patExps;
      this.pat = pat;
      this.exp = exp;
    }

    @Override
    Core.Let toExp(Core.Exp resultExp) {
      if (rec) {
        final List<Core.NonRecValDecl> valDecls = new ArrayList<>();
        patExps.forEach(
            x ->
                valDecls.add(
                    core.nonRecValDecl(
                        x.pos, (Core.IdPat) x.pat, null, x.exp)));
        return core.let(core.recValDecl(valDecls), resultExp);
      }
      if (!composite && patExps.get(0).pat instanceof Core.IdPat) {
        final PatExp x = patExps.get(0);
        Core.NonRecValDecl valDecl =
            core.nonRecValDecl(x.pos, (Core.IdPat) x.pat, null, x.exp);
        return core.let(valDecl, resultExp);
      } else {
        // This is a complex pattern. Allocate an intermediate variable.
        final String name = nameGenerator.get();
        final Core.IdPat idPat = core.idPat(pat.type, name, nameGenerator::inc);
        final Core.Id id = core.id(idPat);
        final Pos pos = patExps.get(0).pos;
        return core.let(
            core.nonRecValDecl(pos, idPat, null, exp),
            core.caseOf(
                pos,
                resultExp.type,
                id,
                ImmutableList.of(core.match(pos, pat, resultExp))));
      }
    }
  }

  /** Pattern and expression. */
  static class PatExp {
    final Core.Pat pat;
    final Core.Exp exp;
    final Pos pos;

    PatExp(Core.Pat pat, Core.Exp exp, Pos pos) {
      this.pat = pat;
      this.exp = exp;
      this.pos = pos;
    }

    @Override
    public String toString() {
      return "[pat: " + pat + ", exp: " + exp + ", pos: " + pos + "]";
    }
  }

  /** Resolved datatype declaration. */
  static class ResolvedDatatypeDecl extends ResolvedDecl {
    private final ImmutableList<DataType> dataTypes;

    ResolvedDatatypeDecl(ImmutableList<DataType> dataTypes) {
      this.dataTypes = dataTypes;
    }

    @Override
    Core.Exp toExp(Core.Exp resultExp) {
      return toExp(dataTypes, resultExp);
    }

    private Core.Exp toExp(List<DataType> dataTypes, Core.Exp resultExp) {
      if (dataTypes.isEmpty()) {
        return resultExp;
      } else {
        return core.local(dataTypes.get(0), toExp(skip(dataTypes), resultExp));
      }
    }

    /**
     * Creates a datatype declaration that may have multiple datatypes.
     *
     * <p>Only the REPL needs this. Because datatypes are not recursive, a
     * composite declaration
     *
     * <pre>{@code
     * datatype d1 ... and d2 ...
     * }</pre>
     *
     * <p>can always be converted to a chained local,
     *
     * <pre>{@code
     * local datatype d1 ... in local datatype d2 ... end end
     * }</pre>
     */
    public Core.DatatypeDecl toDecl() {
      return core.datatypeDecl(dataTypes);
    }
  }

  /**
   * Returns the position of the first {@code max} or {@code min} aggregate call
   * in a {@code compute} clause, or the clause's own position if there is none.
   *
   * <p>Used to position the {@code only} that wraps a scalar {@code compute}:
   * an empty {@code max}/{@code min} raises {@code Empty} there, so that the
   * Calcite path (whose {@code only} does not know which field of a record
   * {@code compute} is empty) agrees with the local path (where the aggregate
   * function raises the exception). A heuristic: we assume the empty aggregate
   * is the first {@code max} or {@code min}.
   */
  private static Pos firstMinMaxPos(Ast.Exp compute) {
    final Pos[] pos = {null};
    compute.accept(
        new Visitor() {
          @Override
          protected void visit(Ast.Aggregate aggregate) {
            if (pos[0] == null && aggregate.aggregate.op == Op.ID) {
              final String name = ((Ast.Id) aggregate.aggregate).name;
              if (name.equals("max") || name.equals("min")) {
                pos[0] = aggregate.pos;
              }
            }
            super.visit(aggregate);
          }
        });
    return pos[0] != null ? pos[0] : compute.pos;
  }

  /**
   * Visitor that converts a {@link Ast.From}, {@link Ast.Exists} or {@link
   * Ast.Forall} to {@link Core.From} by handling each subtype of {@link
   * Ast.FromStep} calling {@link FromBuilder} appropriately.
   */
  private class FromResolver extends Visitor {
    final FromBuilder fromBuilder;

    /**
     * The step environment before {@link FromBuilder#materializeOrdinal()}
     * added the ordinal field; null unless the step being converted reads
     * {@code ordinal}.
     *
     * <p>{@code current} is built from this environment, not from the one that
     * contains the ordinal field. The field is an implementation detail, and
     * must not change the type of the row that the user sees.
     */
    private final Core.@Nullable StepEnv stepPriorEnv;

    FromResolver() {
      this(
          core.fromBuilder(
              typeMap.typeSystem,
              () -> env.bindAll(aggregateResolver.bindings())),
          null);
    }

    private FromResolver(
        FromBuilder fromBuilder, Core.@Nullable StepEnv stepPriorEnv) {
      this.fromBuilder = fromBuilder;
      this.stepPriorEnv = stepPriorEnv;
    }

    /**
     * Returns a resolver for a step that reads {@code ordinal}, sharing this
     * resolver's {@link FromBuilder}. Steps are converted through {@link
     * Visitor#accept}, whose signature has no room for the extra context, so it
     * travels in a resolver rather than in a parameter.
     *
     * <p>The field itself travels in the enclosing {@link Resolver}, which is
     * where a nested query will look for it (see {@link Resolver#ordinalPat}).
     */
    private FromResolver withOrdinal(
        Core.IdPat ordinalPat, Core.StepEnv priorEnv) {
      return withOrdinalPat(ordinalPat).new FromResolver(fromBuilder, priorEnv);
    }

    Core.Exp run(Ast.Query query) {
      if (query.isInto()) {
        // Translate "from ... into f" as if they had written "f (from ...)"
        Core.Exp coreFrom = run(skipLast(query.steps));
        final Ast.Into into = (Ast.Into) last(query.steps);
        // Use fnToCore to resolve overloaded functions based on arg type.
        final Core.Exp exp = fnToCore(into.exp, coreFrom.type);
        // If the function's parameter collection kind differs from
        // the input (e.g. sum expects bag, input is list), wrap the
        // input with a converter.
        final boolean inputOrdered = coreFrom.type instanceof ListType;
        Type expType = exp.type;
        if (expType instanceof ForallType) {
          expType = ((ForallType) expType).type;
        }
        if (expType instanceof FnType) {
          final Type paramType = ((FnType) expType).paramType;
          final boolean fnOrdered = paramType instanceof ListType;
          if (fnOrdered != inputOrdered) {
            final BuiltIn converter =
                inputOrdered ? BuiltIn.BAG_FROM_LIST : BuiltIn.BAG_TO_LIST;
            final Core.Exp converterLit =
                core.functionLiteral(typeMap.typeSystem, converter);
            coreFrom = core.apply(Pos.ZERO, paramType, converterLit, coreFrom);
          }
        }
        return core.apply(exp.pos, typeMap.getType(query), exp, coreFrom);
      }

      final Core.Exp coreFrom = run(query.steps);
      if (query.op == Op.EXISTS) {
        // Translate "exists ..." as if they had written
        // "Relational.nonEmpty (from ...)"
        return core.nonEmpty(typeMap.typeSystem, query.pos, coreFrom);
      } else if (query.op == Op.FORALL) {
        // Translate "forall ... require e" as if they had written
        // "not exists (from ... where not e)".
        //
        // We assume that the last step is 'require e', and we know that
        // 'require e' will have been translated to the same as 'where not e'.
        checkArgument(last(query.steps).op == Op.REQUIRE);
        return core.empty(typeMap.typeSystem, query.pos, coreFrom);
      } else if (query.isCompute()) {
        // Position the 'only' at the first 'max' or 'min' aggregate in the
        // 'compute' clause (or the whole clause if there is none), so that an
        // empty aggregate raises 'Empty' at the same position as the local
        // path (where the aggregate function raises it). It is a heuristic:
        // Calcite's 'only' does not know which field of a record 'compute' is
        // empty, so we assume it is the first 'max' or 'min'.
        final Ast.Compute compute = (Ast.Compute) last(query.steps);
        final Pos aggPos = firstMinMaxPos(requireNonNull(compute.aggregate));
        return core.only(typeMap.typeSystem, aggPos, coreFrom);
      } else {
        return coreFrom;
      }
    }

    private Core.Exp run(List<Ast.FromStep> steps) {
      forEachIndexed(steps, this::acceptStep);
      return fromBuilder.buildSimplify();
    }

    /**
     * Converts one step, materializing the ordinal as a field first if the step
     * reads {@code ordinal}.
     *
     * <p>The first step is never a reader: it has no input rows of its own, so
     * an {@code ordinal} in it either belongs to an enclosing query (see {@link
     * Resolver#ordinalPat}) or has already been rejected by the type resolver.
     */
    private void acceptStep(Ast.FromStep step, int i) {
      if (i == 0 || !usesOrdinal(step)) {
        accept(step);
        return;
      }
      if (step instanceof Ast.Yield) {
        // A "yield" is evaluated once per input row, so it can hold the call
        // itself and needs no field. This is the common case -
        // 'yield {ordinal, e.name}' - and it costs no extra step.
        accept(step);
        return;
      }
      final Core.StepEnv priorEnv = fromBuilder.stepEnv();
      final Core.IdPat ordinalPat = fromBuilder.materializeOrdinal();
      withOrdinal(ordinalPat, priorEnv).accept(step);
      fromBuilder.dropOrdinal(ordinalPat, priorEnv);
    }

    /** Creates a new resolver, adding the bindings from the current step. */
    private Resolver withStepEnv(Core.StepEnv stepEnv) {
      // 'current' is the row as the user sees it, which excludes a
      // materialized ordinal field; but that field must still be in the
      // environment, so that references to it resolve.
      final Core.StepEnv rowEnv = stepPriorEnv == null ? stepEnv : stepPriorEnv;
      Core.Exp f;
      if (rowEnv.atom) {
        f = core.id(rowEnv.bindings.get(0).id);
      } else {
        f = core.record(typeMap.typeSystem, rowEnv.bindings);
      }
      return withEnv(stepEnv.bindings).withCurrent(f);
    }

    @Override
    protected void visit(Ast.From from) {
      // Do not traverse into the sub-"from".
    }

    /**
     * Returns whether a step reads {@code ordinal}.
     *
     * <p>A nested query is evaluated once per row of the enclosing step, so an
     * {@code ordinal} in one of the expressions that the nested query evaluates
     * before its first row belongs to the enclosing step and counts here. An
     * {@code ordinal} anywhere else in the nested query belongs to a step of
     * that query, and does not.
     *
     * <p>By the same rule, a {@code take}, {@code skip}, {@code union}, {@code
     * except}, {@code intersect}, {@code through} or {@code into} step is
     * answered no whatever it contains: its expressions are evaluated before
     * <i>this</i> query's first row, so an {@code ordinal} in them belongs to
     * the step enclosing this query, which finds it through its own lookahead.
     *
     * <p>A step that reads {@code ordinal} several times needs one field, not
     * several, so the answer is yes or no rather than a count.
     *
     * <p>The compiler applies the same rule: only a "yield" installs a
     * row-ordinal counter, so a call compiled anywhere else has nothing to
     * read, and throws. The two must agree.
     */
    private boolean usesOrdinal(Ast.FromStep step) {
      if (isRootStep(step)) {
        return false;
      }
      final AtomicBoolean b = new AtomicBoolean();
      // A scan's condition has its own counter (see visit(Ast.Scan)), so only
      // the extent can make the scan a reader.
      final AstNode node =
          step instanceof Ast.Scan && ((Ast.Scan) step).exp != null
              ? ((Ast.Scan) step).exp
              : step;
      node.accept(
          new Visitor() {
            @Override
            protected void visit(Ast.Ordinal ordinal) {
              b.set(true);
            }

            @Override
            protected void visit(Ast.From from) {
              visitQuery(from.steps);
            }

            @Override
            protected void visit(Ast.Exists exists) {
              visitQuery(exists.steps);
            }

            @Override
            protected void visit(Ast.Forall forall) {
              visitQuery(forall.steps);
            }

            /**
             * Visits the expressions that a nested query evaluates before its
             * first row, and nothing else.
             */
            private void visitQuery(List<Ast.FromStep> steps) {
              forEachIndexed(
                  steps,
                  (s, i) -> {
                    if (i == 0 && s instanceof Ast.Scan) {
                      final Ast.Scan scan = (Ast.Scan) s;
                      if (scan.exp != null) {
                        scan.exp.accept(this);
                      }
                    } else if (isRootStep(s)) {
                      s.accept(this);
                    }
                  });
            }
          });
      return b.get();
    }

    /**
     * Returns whether every expression of a step is evaluated before its
     * query's first row.
     *
     * @see #usesOrdinal(Ast.FromStep)
     */
    private boolean isRootStep(Ast.FromStep step) {
      return step instanceof Ast.Skip
          || step instanceof Ast.Take
          || step instanceof Ast.SetStep
          || step instanceof Ast.Through
          || step instanceof Ast.Into;
    }

    @Override
    protected void visit(Ast.Scan scan) {
      final Resolver r = withStepEnv(fromBuilder.stepEnv());
      final Core.Exp coreExp;
      final Core.Pat corePat;
      if (scan.exp == null) {
        corePat = extentPat(typeMap.typeSystem, r.toCore(scan.pat));
        coreExp =
            core.extent(
                scan.pat.pos,
                typeMap.typeSystem,
                corePat.type,
                ImmutableRangeSet.of(Range.all()));
      } else {
        // The first step's extent is evaluated before the query's first row,
        // so 'current' in it is the enclosing query's row, not this query's
        // (which has no rows yet). The type resolver read it that way too, in
        // the root environment.
        final Resolver rExp =
            fromBuilder.stepEnv().bindings.isEmpty() ? Resolver.this : r;
        coreExp = rExp.toCore(scan.exp);
        final Type elementType = coreExp.type.elementType();
        corePat = r.toCore(scan.pat, elementType);
      }
      final List<Binding> bindings2 =
          new ArrayList<>(fromBuilder.stepEnv().bindings);
      Compiles.acceptBinding(typeMap.typeSystem, corePat, bindings2);
      // An 'ordinal' in the condition counts candidate pairs, which do not
      // exist until the scan runs, so no preceding step can materialize it as
      // a field. Clear the field: the call stays a call, and the compiler
      // binds it to a counter that the scan itself advances.
      Core.Exp coreCondition =
          scan.condition == null
              ? core.boolLiteral(true)
              : r.withEnv(bindings2)
                  .withOrdinalPat(null)
                  .toCore(scan.condition);
      fromBuilder.scan(scan.op, corePat, coreExp, coreCondition);
    }

    @Override
    protected void visit(Ast.Where where) {
      final Resolver r = withStepEnv(fromBuilder.stepEnv());
      fromBuilder.where(r.toCore(where.exp));
    }

    @Override
    protected void visit(Ast.Require require) {
      // 'require e' translates to the same as 'where not e'
      final Resolver r = withStepEnv(fromBuilder.stepEnv());
      final Core.Exp coreRequire = r.toCore(require.exp);
      final Core.Exp coreNot = core.not(typeMap.typeSystem, coreRequire);
      fromBuilder.where(coreNot);
    }

    @Override
    protected void visit(Ast.Skip skip) {
      final Resolver r = withEnv(env); // do not use 'from' bindings
      fromBuilder.skip(r.toCore(skip.exp));
    }

    @Override
    protected void visit(Ast.Take take) {
      final Resolver r = withEnv(env); // do not use 'from' bindings
      fromBuilder.take(r.toCore(take.exp));
    }

    @Override
    protected void visit(Ast.Except except) {
      fromBuilder.except(
          except.distinct, transformEager(except.args, Resolver.this::toCore));
    }

    @Override
    protected void visit(Ast.Intersect intersect) {
      fromBuilder.intersect(
          intersect.distinct,
          transformEager(intersect.args, Resolver.this::toCore));
    }

    @Override
    protected void visit(Ast.Union union) {
      fromBuilder.union(
          union.distinct, transformEager(union.args, Resolver.this::toCore));
    }

    @Override
    protected void visit(Ast.Unorder unorder) {
      fromBuilder.unorder();
    }

    @Override
    protected void visit(Ast.Yield yield) {
      final Resolver r = withStepEnv(fromBuilder.stepEnv());
      final Core.Exp exp = r.toCore(yield.exp);
      final String binder = yield.binder == null ? null : yield.binder.name;
      // The step binds the fields of the record it yields. The record may be
      // wrapped in 'let's -- 'TypeResolver.desugarModifiers' puts it there --
      // and 'exp' is then a 'let' or 'case', so ask the Ast, as TypeResolver
      // did when it deduced the bindings.
      final boolean record = TypeResolver.letBody(yield.exp).op == Op.RECORD;
      fromBuilder.yield_(binder, exp, record);
    }

    @Override
    protected void visit(Ast.Order order) {
      final Resolver r = withStepEnv(fromBuilder.stepEnv());
      fromBuilder.order(r.toCore(order.exp));
    }

    @Override
    protected void visit(Ast.Through through) {
      // Translate "from ... through p in f"
      // as if they wrote "from p in f (from ...)"
      final Core.From from = fromBuilder.build();
      fromBuilder.clear();
      final Core.Exp exp = toCore(through.exp);
      final Core.Pat pat = toCore(through.pat);
      final Type type = typeMap.getType(through);
      fromBuilder.scan(pat, core.apply(through.pos, type, exp, from));
    }

    @Override
    protected void visit(Ast.YieldAll yieldAll) {
      // Lower "yieldAll e" to a scan over the collection-valued expression "e"
      // followed by a yield of the freshly-bound element. For example,
      //
      //   from r in orders
      //     yieldAll r.items
      //
      // becomes
      //
      //   from r in orders,
      //       i in r.items
      //     yield i
      //
      // The scan multiplies each input row by the elements of "e" ("r.items"),
      // then the yield drops the input bindings ("r"), keeping only the element
      // ("i").
      final Resolver r = withStepEnv(fromBuilder.stepEnv());
      final Core.Exp coreExp = r.toCore(yieldAll.exp);
      final Type elementType = coreExp.type.elementType();
      final Core.IdPat pat;
      if (yieldAll.binder == null) {
        pat = core.idPat(elementType, typeMap.typeSystem.nameGenerator::get);
      } else {
        pat =
            core.idPat(
                elementType,
                yieldAll.binder.name,
                typeMap.typeSystem.nameGenerator::inc);
      }
      fromBuilder.scan(pat, coreExp);
      fromBuilder.yield_(core.id(pat));
    }

    @Override
    protected void visit(Ast.Compute compute) {
      visit((Ast.Group) compute);
    }

    @Override
    protected void visit(Ast.Group group) {
      final boolean atom = group.isAtom();
      final Resolver r = withStepEnv(fromBuilder.stepEnv());
      final PairList<Core.IdPat, Core.Exp> groupExps = PairList.of();
      final Resolver aggregateResolver;
      final PairList<Core.IdPat, Core.Aggregate> aggregates = PairList.of();
      final PairList<String, Core.Exp> postExps = PairList.of();
      if (atom) {
        aggregateResolver =
            r.withAggregateResolver(
                env, fromBuilder.stepEnv(), ImmutableList.of(), aggregates);
        final boolean emptyKey =
            group.group instanceof Ast.Record
                && ((Ast.Record) group.group).args.isEmpty();
        final Core.Exp exp;
        final String label;
        if (emptyKey) {
          // No group keys. Since this is atom, compute must be a singleton.
          requireNonNull(group.aggregate);
          exp = aggregateResolver.toCore(group.aggregate, null);
          label = ast.implicitLabelOpt(group.aggregate);
        } else {
          // One group key. Since this is an atom, compute must be empty.
          requireNonNull(group.group);
          exp = r.toCore(group.group);
          label = ast.implicitLabelOpt(group.group);
        }
        Core.Id id;
        Core.IdPat idPat;
        if (exp instanceof Core.Id) {
          id = (Core.Id) exp;
          idPat = (Core.IdPat) id.idPat;
        } else if (label != null) {
          idPat = core.idPat(exp.type, label, 0);
          id = core.id(idPat);
        } else {
          idPat = core.idPat(exp.type, typeMap.typeSystem.nameGenerator::get);
          id = core.id(idPat);
        }
        if (emptyKey) {
          postExps.add(idPat.name, exp);
        } else {
          groupExps.add(idPat, exp);
          postExps.add(idPat.name, id);
        }
      } else {
        group
            .key()
            .args
            .forEach((id, exp) -> groupExps.add(toCorePat(id), r.toCore(exp)));

        aggregateResolver =
            r.withAggregateResolver(
                env, fromBuilder.stepEnv(), groupExps.leftList(), aggregates);
        groupExps.forEach((id, exp) -> postExps.add(id.name, core.id(id)));
        group
            .compute()
            .args
            .forEach(
                (id, exp) ->
                    postExps.add(id.name, aggregateResolver.toCore(exp, id)));
      }
      final SortedMap<Core.IdPat, Core.Exp> groupMap =
          groupExps.toImmutableSortedMap();
      final SortedMap<Core.IdPat, Core.Aggregate> aggregateMap =
          aggregates.toImmutableSortedMap();
      int count = groupMap.size() + aggregateMap.size();
      fromBuilder.group(atom && count == 1, groupMap, aggregateMap);

      final Core.Exp yieldExp;
      if (atom) {
        yieldExp = postExps.right(0);
      } else {
        yieldExp = core.record(typeMap.typeSystem, postExps);
      }
      final String binder = group.binder == null ? null : group.binder.name;
      fromBuilder.yield_(binder, yieldExp);
    }

    @Override
    protected void visit(Ast.Distinct distinct) {
      fromBuilder.distinct();
    }
  }

  /**
   * Converts an {@link Ast.Aggregate} to a core expression.
   *
   * <p>The main implementation, {@link AggregateResolverImpl}, creates a {@link
   * Core.Aggregate} and returns its {@link Core.Id}.
   */
  private interface AggregateResolver {
    /**
     * Converts an {@link Ast.Aggregate} to a core expression.
     *
     * <p>If the value of {@code orderedAgg} is not the same as {@link
     * AggregateResolverImpl#ordered} (e.g. if the aggregate function expects a
     * bag, but previous step in the query produced a list) then conversion will
     * be required.
     *
     * @param aggregate Aggregate (function plus argument)
     * @param orderedAgg Whether the aggregate function expects a list (as
     *     opposed to a bag)
     * @param outerResolver Resolver with which to translate the aggregate
     *     function (evaluated in the context of a group, and therefore
     *     containing the group key but not individual input rows)
     * @param id Name for the aggregate; if specified, can generate a more
     *     meaningful field name in the resulting record.
     */
    default Core.Exp toCore(
        Ast.Aggregate aggregate,
        boolean orderedAgg,
        Resolver outerResolver,
        Ast.@Nullable Id id) {
      throw new UnsupportedOperationException(
          "Aggregate expressions are not supported in this context: "
              + aggregate);
    }

    default Core.Exp toCore(Ast.Elements elements, Resolver outerResolver) {
      throw new UnsupportedOperationException(
          "Aggregate expressions are not supported in this context: "
              + elements);
    }

    /** Returns the additional bindings created by this resolver. */
    default List<Binding> bindings() {
      return ImmutableList.of();
    }

    AggregateResolver UNSUPPORTED = new AggregateResolver() {};
  }

  /**
   * Implementation of {@link AggregateResolver} that is used inside a {@code
   * compute} clause.
   *
   * <p>If an aggregate ({@code over}) is encountered, it is added to the {@link
   * #aggregates} field with a generated name.
   */
  private static class AggregateResolverImpl implements AggregateResolver {
    private final ImmutableList<Core.IdPat> groupKeys;
    private final Resolver inputResolver;
    private final PairList<Core.IdPat, Core.Aggregate> aggregates;
    private final boolean ordered;

    private AggregateResolverImpl(
        Collection<? extends Core.IdPat> groupKeys,
        boolean ordered,
        Resolver inputResolver,
        PairList<Core.IdPat, Core.Aggregate> aggregates) {
      this.groupKeys = ImmutableList.copyOf(groupKeys);
      this.ordered = ordered;
      this.inputResolver = inputResolver;
      this.aggregates = aggregates;
    }

    @Override
    public List<Binding> bindings() {
      return aggregates.transformEager(
          (id, agg) -> Binding.of(id, Unit.INSTANCE));
    }

    @Override
    public Core.Exp toCore(
        Ast.Aggregate aggregate,
        boolean orderedAgg,
        Resolver outerResolver,
        Ast.@Nullable Id id) {
      final TypeMap typeMap = outerResolver.typeMap;
      final Type argElementType = typeMap.getType(aggregate.argument);
      final Type argType =
          orderedAgg
              ? typeMap.typeSystem.listType(argElementType)
              : typeMap.typeSystem.bagType(argElementType);
      Core.Exp aggFn = outerResolver.fnToCore(aggregate.aggregate, argType);
      if (orderedAgg != ordered) {
        // The aggregate function's collection kind differs from the input.
        // Compose a converter with the aggregate function:
        //   fn $col => aggFn(converter($col))
        final BuiltIn converter =
            ordered
                ? BuiltIn.BAG_FROM_LIST // input is list, fn expects bag
                : BuiltIn.BAG_TO_LIST; // input is bag, fn expects list
        final Type inputCollType =
            ordered
                ? typeMap.typeSystem.listType(argElementType)
                : typeMap.typeSystem.bagType(argElementType);
        final Core.IdPat param =
            core.idPat(
                inputCollType, "$col", typeMap.typeSystem.nameGenerator::inc);
        final Core.Exp paramRef = core.id(param);
        final Core.Exp converterLit =
            core.functionLiteral(typeMap.typeSystem, converter);
        final Core.Exp converted =
            core.apply(Pos.ZERO, argType, converterLit, paramRef);
        final Core.Exp applied =
            core.apply(
                aggregate.pos, typeMap.getType(aggregate), aggFn, converted);
        final FnType wrappedType =
            typeMap.typeSystem.fnType(
                inputCollType, typeMap.getType(aggregate));
        aggFn = core.fn(wrappedType, param, applied);
      }
      final Core.Aggregate coreAggregate =
          core.aggregate(
              aggregate.pos,
              typeMap.getType(aggregate),
              aggFn,
              inputResolver.toCore(aggregate.argument));
      final String base =
          id != null
              ? id.name
              : first(ast.implicitLabelOpt(aggregate), "aggregate");
      final String name = generateName(base, this::nameIsUnavailable);
      final Core.IdPat idPat = core.idPat(coreAggregate.type, name, 0);
      aggregates.add(idPat, coreAggregate);
      return core.id(idPat);
    }

    @Override
    public Core.Exp toCore(Ast.Elements elements, Resolver outerResolver) {
      final TypeMap typeMap = outerResolver.typeMap;
      Type type = typeMap.getType(elements);
      // elements has the same collection type as the input, so the
      // aggregate function is the identity with concrete type
      // (e.g. int list -> int list), not the polymorphic ForallType.
      final FnType fnType = typeMap.typeSystem.fnType(type, type);
      Core.Aggregate coreAggregate =
          core.aggregate(
              elements.pos,
              type,
              core.functionLiteral(fnType, BuiltIn.FN_ID),
              inputResolver.current);
      String base = Op.ELEMENTS.lowerName();
      final String name = generateName(base, this::nameIsUnavailable);
      final Core.IdPat idPat = core.idPat(coreAggregate.type, name, 0);
      aggregates.add(idPat, coreAggregate);
      return core.id(idPat);
    }

    /**
     * Generates "base", "base1", "base2", ... until we find a name where {@code
     * predicate} returns false.
     */
    static String generateName(String base, Predicate<String> predicate) {
      String name = base;
      int i = 0;
      while (predicate.test(name)) {
        name = base + ++i;
      }
      return name;
    }

    boolean nameIsUnavailable(String n) {
      return aggregates.anyMatch((id, exp) -> id.name.equals(n))
          || anyMatch(groupKeys, k -> k.name.equals(n));
    }
  }
}

// End Resolver.java
