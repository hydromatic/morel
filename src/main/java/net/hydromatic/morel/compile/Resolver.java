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
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.AstBuilder.ast;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.PairList.zip;
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
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Range;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.AstWriter;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.CoreBuilder;
import net.hydromatic.morel.ast.JoinType;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.RelBuilder;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Simplification;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Decimals;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.AliasType;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.Keys;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.QualifiedType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeCon;
import net.hydromatic.morel.type.TypeShuttle;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;
import net.hydromatic.morel.type.TypeVisitor;
import net.hydromatic.morel.type.TypedValue;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/** Converts AST expressions to Core expressions. */
public class Resolver {
  final TypeMap typeMap;
  final Enforcer enforcer;
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
    this.enforcer = new Enforcer(typeMap, nameGenerator, env, this::toCore);
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
   * <p>If the pre- and post-expressions are non-trivial we end up with a
   * projection on a group on a projection.
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
      List<Binding> bindings,
      boolean ordered,
      Collection<? extends Core.IdPat> groupKeys,
      PairList<Core.IdPat, Core.Aggregate> aggregates) {
    final Environment outerEnv =
        Environments.bind(baseEnv, transform(groupKeys, Binding::of));
    final Environment innerEnv = Environments.bind(outerEnv, bindings);
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
            groupKeys, ordered, innerResolver, aggregates);
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
            node.pos(),
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

  /**
   * Gives a bound pattern the type to display for it, which keeps any type
   * alias.
   *
   * <p>Everywhere else a type has its aliases expanded, so that no part of the
   * compiler that examines a type structurally has to know that an alias
   * exists; a binding is where the name the user wrote is worth showing.
   */
  private Core.Pat withDisplayType(Core.Pat corePat, Ast.Pat pat) {
    if (corePat instanceof Core.NamedPat) {
      Type displayType = typeMap.getRealType(pat);
      if (displayType == null) {
        displayType = typeMap.getAliasedType(pat);
      }
      if (displayType != null) {
        return ((Core.NamedPat) corePat).withType(displayType);
      }
    }
    return corePat;
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
      matches.forEach(
          (pat, exp) ->
              pats.add(
                  withDisplayType(
                      toCore(pat, inst, typeMap.getType(exp)), pat)));
      pats.forEach(
          p -> Compiles.acceptBinding(typeMap.typeSystem, p, bindings));
      final Resolver r = withEnv(bindings);
      final Iterator<Core.Pat> patIter = pats.iterator();
      matches.forEach(
          (pat, exp) ->
              patExps.add(
                  new PatExp(
                      patIter.next(),
                      r.toCore(exp),
                      pat.pos().plus(exp.pos()))));
    } else {
      matches.forEach(
          (pat, exp) -> {
            Core.Pat corePat =
                withDisplayType(toCore(pat, inst, typeMap.getType(exp)), pat);
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
            patExps.add(
                new PatExp(
                    corePat,
                    enforcer.withChecks(
                        coreExp, pat, pat.pos().plus(exp.pos())),
                    pat.pos().plus(exp.pos())));
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

  /**
   * Returns the name a pattern binds, if it binds exactly one and binds it
   * directly: {@code x} or {@code x : t}. Null otherwise.
   */
  private static Ast.@Nullable IdPat bareId(Ast.Pat pat) {
    if (pat instanceof Ast.AnnotatedPat) {
      return bareId(((Ast.AnnotatedPat) pat).pat);
    }
    return pat instanceof Ast.IdPat ? (Ast.IdPat) pat : null;
  }

  /**
   * Returns a pattern as a tuple of two or more plain names, or null if it is
   * not one. Such a pattern's binders line up with the components of what it
   * matches, one apiece.
   */
  private static Ast.@Nullable TuplePat flatTuple(Ast.Pat pat) {
    if (pat instanceof Ast.AnnotatedPat) {
      return flatTuple(((Ast.AnnotatedPat) pat).pat);
    }
    if (!(pat instanceof Ast.TuplePat)) {
      return null;
    }
    final Ast.TuplePat tuplePat = (Ast.TuplePat) pat;
    if (tuplePat.args.size() < 2) {
      return null;
    }
    for (Ast.Pat arg : tuplePat.args) {
      if (bareId(arg) == null) {
        return null;
      }
    }
    return tuplePat;
  }

  /**
   * Returns whether a pattern names its type's values directly: a name, or a
   * tuple of names.
   *
   * <p>Asked of an unbounded scan, whose pattern {@link #extentPat} flattens:
   * for these two the flattening changes nothing, and for the rest the
   * collection's element is a tuple where the pattern says a record.
   */
  private static boolean flatNames(Ast.Pat pat) {
    if (pat instanceof Ast.AnnotatedPat) {
      return flatNames(((Ast.AnnotatedPat) pat).pat);
    }
    if (pat instanceof Ast.IdPat) {
      return true;
    }
    if (pat instanceof Ast.TuplePat) {
      for (Ast.Pat arg : ((Ast.TuplePat) pat).args) {
        if (!(arg instanceof Ast.IdPat)) {
          return false;
        }
      }
      return true;
    }
    return false;
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
        new ReferenceFinder(typeMap.typeSystem, Environments.empty(), refSet);
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
    final AliasType aliasType =
        (AliasType) typeMap.typeSystem.lookup(bind.name.name);
    enforcer.compileDeclaredChecks(bind, aliasType, bind.checks);
    // A checked type written inside the body has no name of its own, so
    // its conditions are compiled here too, against the type its key builds.
    bind.type.accept(
        new Visitor() {
          @Override
          protected void visit(Ast.CheckedType checkedType) {
            super.visit(checkedType);
            enforcer.compileDeclaredChecks(
                bind,
                TypeResolver.toType(checkedType, typeMap.typeSystem),
                checkedType.checks);
          }
        });
    return aliasType;
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
        TypeSystem typeSystem, Environment env, Set<Core.NamedPat> set) {
      super(typeSystem, env);
      this.set = set;
    }

    @Override
    protected ReferenceFinder push(Environment env) {
      return new ReferenceFinder(typeSystem, env, set);
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
        // An ascription is a claim, like a binding: '(e : nat)' says that e is
        // a nat, so the condition must hold of it. A 'fun' declaration's
        // result annotation reaches here as one.
        final Ast.AnnotatedExp annotatedExp = (Ast.AnnotatedExp) exp;
        final Core.Exp annotatedCore = toCore(annotatedExp.exp);
        final Type annotatedType = enforcer.claimedType(annotatedExp.type);
        return annotatedType == null
            ? annotatedCore
            : enforcer.checked(annotatedCore, annotatedType, exp.pos());

      case CHECK_EXP:
        // The type is not written anywhere, so build it here, where the
        // expression's type is known: the base is what was deduced, and the
        // conditions are the ones written.
        //
        // The base is the type displayed for the expression, conditions and
        // all, rather than the type it reduces to. A condition is added to
        // what the expression already claimed, so 'n check m' where 'n' is a
        // 'nat' must check both, and the deep walk finds each condition on the
        // way down.
        final Ast.CheckExp checkExp = (Ast.CheckExp) exp;
        final Core.Exp checkCore = toCore(checkExp.exp);
        final Type.@Nullable Key checkBaseKey =
            typeMap.displayedKey(checkExp.exp);
        final Type checkType =
            Keys.alias(
                    "",
                    checkBaseKey == null ? checkCore.type.key() : checkBaseKey,
                    ImmutableList.of(),
                    checkExp.checks)
                .toType(typeMap.typeSystem);
        enforcer.compileChecks(checkType, checkExp.checks, checkExp.pos());
        return enforcer.checked(checkCore, checkType, exp.pos());

      case AS:
        final Ast.Cast cast = (Ast.Cast) exp;
        final Core.Exp castExp = toCore(cast.exp);
        final Type castType = enforcer.claimedType(cast.type);
        // Converting to a type that constrains nothing claims nothing, so it
        // is erased, as an annotation is.
        return castType == null
            ? castExp
            : enforcer.checked(castExp, castType, exp.pos());

      case AS_OPT:
        final Ast.Cast castOpt = (Ast.Cast) exp;
        final Core.Exp castOptExp = toCore(castOpt.exp);
        final Type optionType = typeMap.getType(exp);
        final Type castOptType = enforcer.claimedType(castOpt.type);
        if (castOptType == null) {
          // Converting to an unchecked type cannot fail.
          return core.apply(
              exp.pos(),
              optionType,
              core.constructor(
                  typeMap.typeSystem, BuiltIn.Constructor.OPTION_SOME),
              castOptExp);
        }
        return enforcer.checkedOpt(
            castOptExp, castOptType, optionType, exp.pos());
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
    return core.id(id.pos(), idPat);
  }

  private Core.Exp toCore(Ast.OpSection opSection) {
    final Binding binding = env.getOpt("op " + opSection.name);
    checkNotNull(binding, "not found", opSection);

    // Just return a reference to the operator binding
    // The operator is already defined as a function value
    final Core.NamedPat idPat = getIdPat(opSection, binding.id);
    return core.id(opSection.pos(), idPat);
  }

  private Core.Exp toCore(Ast.Current ignoredCurrent) {
    return requireNonNull(this.current);
  }

  private Core.Exp toCore(Ast.TypeString typeString) {
    // Render the operand's inferred type to a string. The operand is not
    // converted to Core, so it is never evaluated.
    //
    // Prefer the type as it would be displayed, which keeps an alias and any
    // condition; 'getType' expands them, and would say "int" for a value the
    // shell prints as 'foo'.
    final Type aliased = typeMap.getAliasedType(typeString.exp);
    final Type type =
        aliased != null ? aliased : typeMap.getType(typeString.exp);
    return core.stringLiteral(type.moniker());
  }

  private Core.Exp toCore(Ast.Ordinal ordinal) {
    // The type checker admits `ordinal` only in a query, and the query's node
    // binds the pattern that names it.
    return core.id(
        requireNonNull(ordinalPat, () -> "ordinal outside a node: " + ordinal));
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

  private Core.Exp toCore(Ast.Record record) {
    if (record.base != null) {
      return toCoreModified(record);
    }
    final RecordLikeType type = (RecordLikeType) typeMap.getType(record);
    return core.tuple(type, transformEager(record.args(), this::toCore));
  }

  /**
   * Converts a record that has modifiers into the {@code let}s it means: one
   * per modifier, each binding the fields of the record the modifier before it
   * produced, and ending in a record built from those fields.
   *
   * <p>{@link TypeResolver} types the record as it is written, and leaves it to
   * be built here, where the types are settled. Building it earlier would lose
   * the type of the record being modified.
   */
  private Core.Exp toCoreModified(Ast.Record record) {
    // The base, and the argument of each 'all' modifier, are evaluated outside
    // the modifiers -- in this environment, which does not have their fields
    // -- and in the order they are written.
    final List<Ast.Exp> operandExps = new ArrayList<>();
    operandExps.add(requireNonNull(record.base));
    record.modifiers.forEach(
        modifier -> {
          if (modifier instanceof Ast.AllModifier) {
            operandExps.add(((Ast.AllModifier) modifier).exp);
          }
        });
    return bindOperands(record, operandExps, 0, new IdentityHashMap<>());
  }

  /** Binds the operands of a modified record, then applies its modifiers. */
  private Core.Exp bindOperands(
      Ast.Record record,
      List<Ast.Exp> exps,
      int i,
      Map<Ast.Exp, Core.Id> operands) {
    if (i == exps.size()) {
      return modify(
          record,
          0,
          requireNonNull(operands.get(record.base)),
          operands,
          false);
    }
    final Ast.Exp exp = exps.get(i);
    return enforcer.letValue(
        toCore(exp),
        exp.pos(),
        id -> {
          operands.put(exp, id);
          return bindOperands(record, exps, i + 1, operands);
        });
  }

  /**
   * Applies the modifiers of a record, from {@code i} onwards.
   *
   * <p>{@code claimed} says whether a modifier already applied put a value into
   * a field that has a declared type. If one did, the record claims that the
   * value has that type, and the claim is checked here -- at the modifier, not
   * where its result is bound, because nobody else wrote the type down. A
   * modifier that only adds, removes or renames a field claims nothing new:
   * every value it carries over was checked when it was put there.
   */
  private Core.Exp modify(
      Ast.Record record,
      int i,
      Core.Exp value,
      Map<Ast.Exp, Core.Id> operands,
      boolean claimed) {
    final List<Ast.Modifier> modifiers = record.modifiers;
    final Pos pos = record.pos();
    if (i == modifiers.size()) {
      final Type type = typeMap.getAliasedType(record);
      if (type instanceof AliasType) {
        // A modifier that changed the record's shape may have carried
        // conditions over into a type that has no name, and so was never
        // declared. Compile them, so that the type can be used.
        enforcer.compileChecks(type, ((AliasType) type).checks, pos);
      }
      if (!claimed) {
        // Nothing was assigned, so nothing is claimed that has not been shown:
        // every value the result carries was checked when it was put there.
        return value;
      }
      return type == null ? value : enforcer.checked(value, type, pos);
    }
    final Ast.Modifier modifier = modifiers.get(i);
    final Core.Id allId =
        modifier instanceof Ast.AllModifier
            ? requireNonNull(operands.get(((Ast.AllModifier) modifier).exp))
            : null;
    return enforcer.letValue(
        value,
        pos,
        id -> {
          final RecordLikeType recordType = (RecordLikeType) id.type;
          final PairList<String, RecordModifiers.Source> sources =
              RecordModifiers.apply(
                  modifier,
                  ImmutableList.copyOf(recordType.argNameTypes().keySet()),
                  allId == null
                      ? null
                      : ImmutableList.copyOf(
                          ((RecordLikeType) allId.type)
                              .argNameTypes()
                              .keySet()));
          // Only an assignment has expressions, and only they can refer to the
          // fields by name.
          final List<String> bound =
              modifier.op == Op.ASSIGN_MODIFIER
                  ? ImmutableList.copyOf(recordType.argNameTypes().keySet())
                  : ImmutableList.of();
          return bindFields(
              id,
              bound,
              0,
              pos,
              r ->
                  r.modify(
                      record,
                      i + 1,
                      r.build(sources, id, allId, pos),
                      operands,
                      claimed || RecordModifiers.claims(sources)));
        });
  }

  /**
   * Binds each field of a record to its own name, so that the expressions a
   * modifier assigns can refer to it, and applies {@code body} in the
   * environment that results.
   *
   * <p>The names shadow the enclosing environment, which is what lets {@code {r
   * replace i = j, j = i}} mean what it looks like.
   */
  private Core.Exp bindFields(
      Core.Id record,
      List<String> fields,
      int i,
      Pos pos,
      Function<Resolver, Core.Exp> body) {
    if (i == fields.size()) {
      return body.apply(this);
    }
    final String field = fields.get(i);
    final Core.Exp value = select(record, field, pos);
    final Core.IdPat idPat = core.idPat(value.type, field, nameGenerator::inc);
    final Core.Exp exp =
        withEnv(ImmutableList.of(Binding.of(idPat)))
            .bindFields(record, fields, i + 1, pos, body);
    return core.let(core.nonRecValDecl(pos, idPat, null, value), exp);
  }

  /**
   * Builds the record that a modifier produces.
   *
   * <p>Its type is derived from the fields, rather than read from the type map,
   * because only the last modifier has an entry there, and because that entry
   * is the type as the user would see it, whereas Core needs the type erased.
   */
  private Core.Exp build(
      PairList<String, RecordModifiers.Source> sources,
      Core.Id record,
      Core.@Nullable Id allRecord,
      Pos pos) {
    final PairList<String, Core.Exp> args = PairList.of();
    sources.forEach(
        (label, source) -> {
          if (source instanceof RecordModifiers.Kept) {
            args.add(
                label,
                select(record, ((RecordModifiers.Kept) source).field, pos));
          } else if (source instanceof RecordModifiers.Taken) {
            args.add(
                label,
                select(
                    requireNonNull(allRecord),
                    ((RecordModifiers.Taken) source).field,
                    pos));
          } else {
            args.add(label, toCore(((RecordModifiers.Assigned) source).exp));
          }
        });
    final SortedMap<String, Core.Exp> sortedArgs =
        new TreeMap<>(RecordType.ORDERING);
    args.forEach(sortedArgs::put);
    final SortedMap<String, Type> argTypes = new TreeMap<>(RecordType.ORDERING);
    sortedArgs.forEach((label, exp) -> argTypes.put(label, exp.type));
    return core.tuple(
        (RecordLikeType) typeMap.typeSystem.recordType(argTypes),
        ImmutableList.copyOf(sortedArgs.values()));
  }

  /** Returns an expression that selects a field of a record. */
  private Core.Exp select(Core.Id record, String field, Pos pos) {
    final RecordLikeType recordType = (RecordLikeType) record.type;
    return core.apply(
        pos,
        requireNonNull(recordType.argNameTypes().get(field)),
        core.recordSelector(typeMap.typeSystem, recordType, field),
        record);
  }

  private Core.Exp toCore(Ast.ListExp list) {
    final ListType type = (ListType) typeMap.getType(list);
    return core.apply(
        list.pos(),
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
        exp.pos(),
        listType,
        core.functionLiteral(typeMap.typeSystem, BuiltIn.Z_LIST),
        core.tuple(typeMap.typeSystem, toCore(exp)));
  }

  private Core.Exp toCore(Ast.Apply apply) {
    final Core.Exp coreArg =
        enforcer.withConstructorCheck(apply.fn, toCore(apply.arg));
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
          fn = core.apply(apply.pos(), type, fn, dict);
        }
      }
      coreFn = fn;
    }
    checkDecimalLiteral(apply, type, coreFn);
    if (isBuiltIn(coreFn, BuiltIn.SYS_PLAN_OF)) {
      // `Sys.planOf e` is the plan of `e`, and `e` is not evaluated. Expanded
      // here, where the argument's Core is to hand and is the tree the
      // resolver built. A macro cannot do this -- `Macro.expand` is given the
      // argument's *type* and is called at the `Id`, not the `Apply` -- and
      // expanding later would be worse than inconvenient: the rewrite passes
      // run to a fixed point, so the answer would depend on when the inliner
      // reached the call. A plan's text must depend on the query and
      // nothing else.
      final int width =
          session == null
              ? AstWriter.DEFAULT_WIDTH
              : Prop.LINE_WIDTH.optionalIntValue(
                  session.map, Integer.MAX_VALUE);
      // A query's plan is a tree, and prints as one even when the tree has
      // no node in it; anything else is just the expression. The argument's
      // *AST* says which, because by the time it is Core a query has become
      // the tree and a node-free tree is indistinguishable from an ordinary
      // expression -- which was the whole trouble.
      return core.stringLiteral(
          apply.arg.op == Op.FROM
              ? coreArg.unparsePlan(typeMap.typeSystem, width)
              : coreArg.unparseRenumbered(typeMap.typeSystem, width, true));
    }
    return core.apply(apply.pos(), type, coreFn, coreArg);
  }

  /**
   * Checks that, if {@code apply} is a call to the {@code decimal} function
   * with a string literal argument, as in {@code decimal "12.3"}, the literal
   * is a valid decimal. An invalid literal is a compile error, whereas an
   * invalid string computed at run time raises {@code Domain}.
   */
  private void checkDecimalLiteral(
      Ast.Apply apply, Type type, Core.Exp coreFn) {
    if (apply.arg.op == Op.STRING_LITERAL
        && type instanceof DataType
        && ((DataType) type).name.equals("decimal")) {
      final Object o = valueOf(env, coreFn);
      if (o instanceof Applicable
          && Codes.BUILT_IN_MAP.get(o) == BuiltIn.DECIMAL_DECIMAL) {
        final String s = (String) ((Ast.Literal) apply.arg).value;
        if (Decimals.parseExact(s) == null) {
          throw new CompileException(
              format("invalid decimal literal '%s'", s), false, apply.pos());
        }
      }
    }
  }

  /** Returns whether an expression is the function literal of a built-in. */
  private boolean isBuiltIn(Core.Exp exp, BuiltIn builtIn) {
    if (exp.op == Op.FN_LITERAL && ((Core.Literal) exp).value == builtIn) {
      return true;
    }
    // A built-in reached through a structure is not a function literal yet:
    // `Sys.planOf` resolves to `#planOf Sys`, an application of a record
    // selector, and the inliner folds it later. So ask the environment what
    // the expression's value is, and let the value say what it implements.
    //
    // Ask only where the answer could be yes. `valueOf` walks into a record
    // to read a field, and a query's binder has no value to read: `intFn.f 1`
    // is a selector application too, and looking that one up walks off the
    // end of a placeholder row. The result type is enough of a filter,
    // because the built-ins reached this way return a string.
    if (!(exp.type instanceof FnType)
        || ((FnType) exp.type).resultType != PrimitiveType.STRING) {
      return false;
    }
    final @Nullable Object o = valueOf(env, exp);
    return o instanceof Applicable && ((Applicable) o).builtIn() == builtIn;
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
              apply.pos(),
              ts.fnType(fInType, fOutType),
              core.functionLiteral(ts, mapBuiltIn),
              fn);
      inType = fInType;
      outType = fOutType;
    }
    return core.apply(apply.pos(), type, fn, coreArg);
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
          if (recordSelector.slot >= list.size()) {
            // Not a record we can read: a query's binder stands for a row
            // that does not exist yet, and its placeholder is shorter than
            // the type says. Not constant, rather than an exception.
            return null;
          }
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
        call.pos(),
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
        transformEager(fn.matchList, m -> toCore(m, type.paramType));
    final Core.Fn coreFn =
        core.fn(fn.pos(), type, matchList, nameGenerator::inc);
    final Type paramType = enforcer.parameterType(fn, coreFn.idPat.type);
    if (paramType == null) {
      return coreFn;
    }
    // The check goes inside the function, so it travels with the function
    // value and fires however the function is called -- including from
    // polymorphic code that knows nothing of the checked type.
    //
    //   fn (n: nat) => e
    //
    // becomes
    //
    //   fn v => let val n = $check (c v, v, "nat") in e end
    //
    // rather than checking and discarding, which an optimizer would be
    // entitled to remove: the body reads the name the check binds, so the
    // check cannot be dropped.
    final Core.IdPat paramPat =
        core.idPat(coreFn.idPat.type, () -> nameGenerator.getPrefixed("v"));
    return core.fn(
        (FnType) coreFn.type,
        paramPat,
        core.let(
            core.nonRecValDecl(
                fn.pos(),
                coreFn.idPat,
                null,
                enforcer.checked(core.id(paramPat), paramType, fn.pos())),
            coreFn.exp));
  }

  private Core.Case toCore(Ast.If if_) {
    return core.ifThenElse(
        toCore(if_.condition), toCore(if_.ifTrue), toCore(if_.ifFalse));
  }

  private Core.Raise toCore(Ast.Raise raise) {
    return core.raise(raise.pos(), typeMap.getType(raise), toCore(raise.exp));
  }

  private Core.Case toCore(Ast.Case case_) {
    final Type argType = typeMap.getType(case_.exp);
    return core.caseOf(
        case_.pos(),
        typeMap.getType(case_),
        toCore(case_.exp),
        transformEager(case_.matchList, m -> toCore(m, argType)));
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
    return toCore(pat, inst, null);
  }

  /**
   * Converts a pattern that a declaration binds, given the type of the value it
   * is matched against.
   *
   * <p>The value's type is what a record pattern with an ellipsis needs: the
   * pattern names some of the fields and its own type has only those, so it is
   * the value's type that says which slots they are.
   */
  private Core.Pat toCore(Ast.Pat pat, boolean inst, @Nullable Type valueType) {
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
    return toCore(pat, type, valueType == null ? type : valueType);
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
            type, asPat.id.name, nameGenerator, toCore(asPat.pat, targetType));

      case ANNOTATED_PAT:
        // There is no annotated pat in core, because all patterns have types.
        final Ast.AnnotatedPat annotatedPat = (Ast.AnnotatedPat) pat;
        return toCore(annotatedPat.pat);

      case CON_PAT:
        final Ast.ConPat conPat = (Ast.ConPat) pat;
        // The argument's target is the constructor's argument type, which has
        // every field that a record pattern with an ellipsis leaves out; the
        // argument pattern's own type has only the fields it wrote.
        return core.conPat(
            type,
            conPat.tyCon.name,
            toCore(
                conPat.pat, conArgType(type, conPat.tyCon.name, conPat.pat)));

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
            core.tuplePat(
                tupleType,
                toCore(infixPat.p0, type.elementType()),
                toCore(infixPat.p1, type)));

      case LIST_PAT:
        final Ast.ListPat listPat = (Ast.ListPat) pat;
        return core.listPat(
            type,
            transformEager(
                listPat.args, arg -> toCore(arg, type.elementType())));

      case RECORD_PAT:
        final Ast.RecordPat recordPat = (Ast.RecordPat) pat;
        if (targetType == PrimitiveType.UNIT) {
          // Unit record is a special case, it has no fields.
          // Its type is not RecordType, but RecordLikeType.
          return core.wildcardPat(targetType);
        }
        // The target may be a tuple type; a tuple is a record whose labels
        // are ordinals, and "{1 = x, 2 = y}" is a valid pattern for it. A
        // target that is not a record -- a constructor whose argument is a
        // type variable -- tells nothing, and the pattern's own type serves.
        final RecordLikeType recordLikeType =
            (RecordLikeType)
                (targetType instanceof RecordLikeType ? targetType : type);
        final ImmutableList.Builder<Core.Pat> args = ImmutableList.builder();
        recordLikeType
            .argNameTypes()
            .forEach(
                (label, argType) -> {
                  final Ast.Pat argPat = recordPat.args.get(label);
                  // The field's type is the target for the field's pattern,
                  // so that a nested record pattern with an ellipsis is
                  // converted against the value's fields, not its own.
                  final Core.Pat corePat =
                      argPat != null
                          ? toCore(argPat, argType)
                          : core.wildcardPat(argType);
                  args.add(corePat);
                });
        return recordLikeType instanceof RecordType
            ? core.recordPat((RecordType) recordLikeType, args.build())
            : core.tuplePat(recordLikeType, args.build());

      case TUPLE_PAT:
        final Ast.TuplePat tuplePat = (Ast.TuplePat) pat;
        final RecordLikeType tupleType2 = (RecordLikeType) type;
        final List<Core.Pat> argList =
            zip(tuplePat.args, tupleType2.argTypes()).transform(this::toCore);
        return core.tuplePat(tupleType2, argList);

      default:
        throw new AssertionError("unknown pat " + pat.op);
    }
  }

  /**
   * Returns the type of a constructor's argument, given the datatype it makes;
   * the argument pattern's own type if the datatype does not say.
   */
  private Type conArgType(Type type, String tyCon, Ast.Pat argPat) {
    if (type instanceof DataType) {
      final DataType dataType = (DataType) type;
      if (dataType.arguments.stream().noneMatch(TypeSystem::hasTypeVar)) {
        final @Nullable Type argType =
            dataType.typeConstructors(typeMap.typeSystem).get(tyCon);
        if (argType != null) {
          return argType;
        }
      }
      // A datatype whose arguments still hold type variables -- "SOME (c, s)"
      // over "(char * 'b) option" -- cannot have them substituted into its
      // constructors' types. The constructor's declared argument type has
      // every field name, which is what a record pattern with an ellipsis
      // needs; where a field's type is a type variable, the field pattern's
      // own type serves.
      final @Nullable TypeCon typeCon = typeMap.typeSystem.lookupTyCon(tyCon);
      if (typeCon != null) {
        return typeCon.argTypeKey.toType(typeMap.typeSystem);
      }
    }
    return typeMap.getType(argPat);
  }

  /**
   * Converts a branch, given the type of the value its pattern is matched
   * against; see {@link #toCore(Ast.Pat, boolean, Type)}.
   */
  private Core.Match toCore(Ast.Match match, Type argType) {
    final Core.Pat pat = toCore(match.pat, false, argType);
    final List<Binding> bindings = new ArrayList<>();
    Compiles.acceptBinding(typeMap.typeSystem, pat, bindings);
    final Core.Exp exp = withEnv(bindings).toCore(match.exp);
    final Type claimed = enforcer.claimedPatType(match.pat, pat.type);
    if (claimed != null) {
      // Entering a branch whose pattern claims a type is where a value flows
      // into the claim, so that is where the check goes. A branch is what a
      // function's parameter and a 'case' have in common, so both are checked
      // here, and a function of several branches is checked in whichever
      // branch claims -- the parameter of the function as a whole claims
      // nothing, because another branch may match instead.
      //
      //   (n: nat) => e
      //
      // becomes
      //
      //   v => let val n = $check (c v, v, "nat", "") in e end
      //
      // rather than checking and discarding, which an optimizer would be
      // entitled to remove: the body reads the name the check binds.
      final Core.IdPat rawPat =
          core.idPat(pat.type, () -> nameGenerator.getPrefixed("v"));
      final Core.Exp checked =
          enforcer.checked(core.id(rawPat), claimed, match.pos());
      if (pat instanceof Core.NamedPat) {
        return core.match(
            match.pos(),
            rawPat,
            core.let(
                core.nonRecValDecl(
                    match.pos(), (Core.NamedPat) pat, null, checked),
                exp));
      }
      // A pattern that destructures binds no one name that covers the
      // whole value, so the check goes on the value and the pattern is
      // matched against what it gives back. The pattern is the one that
      // was written, so it decides as it did before; it is only reached
      // through the check.
      return core.match(
          match.pos(),
          rawPat,
          core.caseOf(
              match.pos(),
              exp.type,
              checked,
              ImmutableList.of(core.match(match.pos(), pat, exp))));
    }
    return core.match(match.pos(), pat, exp);
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
                pos[0] = aggregate.pos();
              }
            }
            super.visit(aggregate);
          }
        });
    return pos[0] != null ? pos[0] : compute.pos();
  }

  /**
   * Converts the steps of a query into a relational tree, one step at a time,
   * through a {@link RelBuilder}.
   *
   * <p>A step is converted in the scope its predecessors built: a scan pushes a
   * leaf and erases its pattern into paths that read the element, and the steps
   * after it write their expressions over the builder's row. The names a step
   * may use come from the builder's map, which stores the path to each name
   * rather than a flag saying whether the name is the whole element.
   *
   * <p>What is true of the query as a whole -- {@code into}, {@code exists},
   * {@code forall}, {@code compute}, and whether a step reads {@code ordinal}
   * -- is {@link FromResolver}'s, which creates one of these per step list.
   */
  private class StepResolver {
    // Every simplification but FILTER_MERGE, which would turn the `where`
    // steps the user wrote into one `andalso`. A tree is entitled to say it
    // either way; a plan the user reads is not.
    final RelBuilder b =
        RelBuilder.create(
            typeMap.typeSystem,
            EnumSet.complementOf(EnumSet.of(Simplification.FILTER_MERGE)));

    /**
     * Names that this query's steps bind.
     *
     * <p>Fewer than the builder knows: the builder also names the element's
     * fields, and a scan {@code e in emps} binds {@code e} and nothing else. To
     * bind {@code deptno} as well would shadow an enclosing {@code deptno} that
     * the query is entitled to read -- a function's parameter, say. The builder
     * says where each of these lives; this says which of them exist.
     */
    final Set<String> binders = new LinkedHashSet<>();

    /**
     * Whether the row is the one thing a single binder names, rather than a
     * record of what the binders name.
     *
     * <p>The distinction is not the number of binders: {@code yield {j = i +
     * 1}} binds one name and the row is still a record, so {@code current} is a
     * record too.
     */
    boolean atom = true;

    /**
     * Whether the element is the row the user sees.
     *
     * <p>True everywhere except after a join, which leaves the element as its
     * inputs' components concatenated. Where it is true, {@code current} is the
     * element itself rather than a record rebuilt out of paths into it -- and
     * rebuilding one would not merely be longer, it would be a projection that
     * survives into the plan.
     */
    boolean rowIsElement = true;

    /**
     * Ordinal for the next binder that a {@link Scope} makes, counting down.
     *
     * <p>Negative, so that it cannot be an ordinal {@link NameGenerator#inc}
     * hands out, and taking one from the generator instead would suffix every
     * binder in every plan -- {@code from p_1 in ...} where the user wrote
     * {@code p}. These patterns are substituted away before anything sees them;
     * uniqueness is all their ordinals owe.
     */
    int scopeOrdinal = 0;

    Core.Exp run(List<Ast.FromStep> steps) {
      if (steps.isEmpty() || !(steps.get(0) instanceof Ast.Scan)) {
        // A query with no scan -- `from`, `from where p`, `from yield e` --
        // iterates over one row, which is unit.
        b.push(
            core.list(
                typeMap.typeSystem,
                PrimitiveType.UNIT,
                ImmutableList.of(core.unitLiteral())));
      }
      steps.forEach(this::step);
      if (steps.isEmpty() || !(last(steps) instanceof Ast.Yield)) {
        finish();
      }
      return b.build();
    }

    /**
     * Projects the record that the query's binders denote, which is what a
     * query with no trailing yield returns.
     *
     * <p>One binder is the row itself, and the tree already has it. Several are
     * a record of them, which the tree does not have: a join leaves its inputs'
     * components concatenated, and naming them is this projection's job.
     */
    private void finish() {
      if (rowIsElement) {
        return;
      }
      final Map<String, Core.Exp> paths = new LinkedHashMap<>();
      binders.forEach(name -> paths.put(name, b.name(name)));
      final Core.Exp exp = natural(paths, b.input(0));
      if (atom && binders.size() == 1) {
        // A projection takes its names from the element's fields, and an atom
        // row has none, so the binder is named explicitly or the steps after
        // this one cannot find it.
        b.project(requireNonNull(getOnlyElement(binders)), exp);
      } else {
        b.project(exp);
      }
      rowIsElement = true;
    }

    /**
     * Returns the row as the user sees it: the one thing a single binder names,
     * or a record of what several name.
     *
     * <p>Not the tree's element, which a join leaves as its inputs' components
     * concatenated. {@code current} means this, and so does a query that ends
     * without a yield.
     */
    private Core.Exp natural(Map<String, Core.Exp> paths, Core.Exp element) {
      if (rowIsElement) {
        return element;
      }
      if (paths.isEmpty()) {
        // Nothing is bound, as after `from _ in xs`, and the row is unit.
        return core.unitLiteral();
      }
      if (atom) {
        return requireNonNull(getOnlyElement(paths.values()));
      }
      final PairList<String, Core.Exp> nameExps = PairList.of();
      paths.forEach(nameExps::add);
      return core.record(typeMap.typeSystem, nameExps);
    }

    private void step(Ast.FromStep step) {
      b.at(step.pos());
      if (step instanceof Ast.Scan) {
        scan((Ast.Scan) step);
      } else if (step instanceof Ast.Where) {
        b.filter(toCore(((Ast.Where) step).exp));
      } else if (step instanceof Ast.Order) {
        b.sort(toCore(((Ast.Order) step).exp));
      } else if (step instanceof Ast.Unorder) {
        b.unorder();
      } else if (step instanceof Ast.Skip) {
        // A count is evaluated before the query has a row, so it reads the
        // enclosing scope; `toCore(exp, null)` is that scope.
        b.skip(toCore(((Ast.Skip) step).exp, null));
      } else if (step instanceof Ast.Take) {
        b.take(toCore(((Ast.Take) step).exp, null));
      } else if (step instanceof Ast.Group) {
        group_((Ast.Group) step);
      } else if (step instanceof Ast.SetStep) {
        setStep((Ast.SetStep) step);
      } else if (step instanceof Ast.Require) {
        // `require e` is `where not e`.
        b.filter(
            core.not(typeMap.typeSystem, toCore(((Ast.Require) step).exp)));
      } else if (step instanceof Ast.Distinct) {
        distinct();
      } else if (step instanceof Ast.YieldAll) {
        yieldAll((Ast.YieldAll) step);
      } else if (step instanceof Ast.Through) {
        through((Ast.Through) step);
      } else {
        yield_((Ast.Yield) step);
      }
    }

    /**
     * Scans a collection: the query's first, or a join onto what it has.
     *
     * <p>A pattern is erased -- the tree has paths, not patterns -- so what
     * survives is one binder per name it binds, and none at all for {@code from
     * _ in xs}, whose rows are {@code unit}.
     */
    private void scan(Ast.Scan scan) {
      final Ast.@Nullable Exp scanExp = scan.exp;
      if (b.size() == 0) {
        binders.addAll(
            scanExp == null
                ? pushExtent(scan)
                : push(scan.pat, toCore(scanExp)));
        // Only a bare name leaves the element as the row; a pattern is erased,
        // and what it bound is read back out by paths.
        rowIsElement = bareId(scan.pat) != null;
        typeCondition(scan, binders);
      } else {
        // The right input is a tree of its own, so it cannot say `$0` and
        // mean the row so far. A binder crosses that boundary by ordinary
        // lexical scoping, and the builder drops it again if the collection
        // turns out to read nothing of the left -- which is the common case,
        // and an independent join is far the better one.
        final Core.IdPat binder = b.binder();
        final List<String> names;
        if (scanExp == null) {
          names = pushExtent(scan);
        } else {
          names = push(scan.pat, toCoreSource(scanExp, core.id(binder)));
        }
        b.pair();
        final Core.Exp condition =
            scan.condition == null
                ? core.boolLiteral(true)
                : on(scan.condition, names);
        b.join(joinType(scan.op), condition);
        binders.addAll(names);
        rowIsElement = false;
        typeCondition(scan, names);
      }
      // About how many names are bound rather than how many the scan added:
      // `from a in [1], _ in [true]` binds one, so its rows are ints and not
      // records of one field.
      atom = binders.size() == 1;
    }

    /**
     * Returns the collection that an unbounded scan -- {@code from i} -- scans:
     * every value of the pattern's type. Grounding replaces it with something
     * finite, or says that it cannot.
     */
    private List<String> pushExtent(Ast.Scan scan) {
      final Ast.@Nullable TuplePat tuplePat = flatTuple(scan.pat);
      if (tuplePat != null) {
        // `from (b, i)` names each component, and each is a value the scan
        // generates. Scanning under a pattern of those names keeps them, where
        // scanning under one name and reading the components out would lose
        // them -- and grounding quotes them in what it says about a leaf it
        // cannot bound. The patterns here are throwaway: the tree erases them
        // to paths, so taking the names from the Ast rather than converting
        // it leaves the generator's ordinals for the patterns the plan has.
        final Type type = typeMap.getType(scan.pat);
        final List<String> names =
            transform(tuplePat.args, arg -> requireNonNull(bareId(arg)).name);
        final List<Core.Pat> pats =
            zip(names, ((RecordLikeType) type).argTypes())
                .transform((name, argType) -> core.idPat(argType, name, 0));
        b.push(
            core.tuplePat(typeMap.typeSystem, pats),
            extent(scan.pat.pos(), type, names));
        return names;
      }
      final Ast.@Nullable IdPat id = bareId(scan.pat);
      if (id != null) {
        // The pattern's type, from the type map, rather than the pattern
        // converted: converting it would take the name from the generator, and
        // the tree has no use for the pattern anyway -- `push` erases it to
        // paths -- so only the type is wanted.
        final Type type = typeMap.getType(scan.pat);
        return push(
            scan.pat, extent(scan.pat.pos(), type, ImmutableList.of(id.name)));
      }
      // A pattern that is not a name or a tuple of names is flattened: `from
      // {b, i}` scans `bool * int`, and the names are read out of the tuple
      // rather than out of a record. The tree keeps paths either way, so
      // which it is makes no difference above the scan.
      final Core.Pat flat =
          extentPat(
              typeMap.typeSystem,
              Resolver.this.toCore(scan.pat, typeMap.getType(scan.pat)));
      final List<String> names = transform(flat.expand(), pat -> pat.name);
      return push(flat, extent(scan.pat.pos(), flat.type, names));
    }

    /**
     * Filters by the condition of the checked type a scan is over, if it is
     * over one.
     *
     * <p>A scan over a checked type enumerates the values of that type, so the
     * type's condition belongs in the query, where grounding can use it to
     * generate the values rather than generate and reject them. A filter of its
     * own, and not part of a join's condition, which only a join reads.
     */
    private void typeCondition(Ast.Scan scan, Collection<String> names) {
      if (scan.pat.op != Op.ANNOTATED_PAT) {
        return;
      }
      final @Nullable Type type =
          enforcer.claimedType(((Ast.AnnotatedPat) scan.pat).type);
      if (type == null) {
        return;
      }
      final Core.@Nullable Exp value = rowValue(names, type.unalias());
      if (value == null) {
        return;
      }
      final Core.@Nullable Exp condition =
          enforcer.deepCondition(
              type, value.type, value, "", false, scan.pat.pos());
      if (condition != null) {
        b.filter(condition);
      }
    }

    /**
     * Returns the value that a scan's names denote, for the type's condition to
     * be asked of.
     *
     * <p>The names are read out of the builder rather than out of a pattern,
     * because the tree has none. The field names come from the type the user
     * wrote, because a record pattern reaches the tree as a tuple, whose fields
     * are named 1, 2.
     */
    private Core.@Nullable Exp rowValue(
        Collection<String> names, Type erasedType) {
      if (names.size() == 1) {
        return b.name(requireNonNull(getOnlyElement(names)));
      }
      if (!(erasedType instanceof RecordLikeType)) {
        return null;
      }
      final Set<String> fields =
          ((RecordLikeType) erasedType).argNameTypes().keySet();
      if (fields.size() != names.size()) {
        return null;
      }
      return core.record(
          typeMap.typeSystem,
          zip(ImmutableList.copyOf(fields), transformEager(names, b::name)));
    }

    /**
     * Scans a collection under a pattern that filters but has no total test: a
     * user datatype's constructor, which needs a {@code case} both to ask
     * whether a value matches and to reach what it holds.
     *
     * <p>The {@code case} yields a collection of nought or one row, and a
     * dependent join over it is the flat-map that keeps the rows that match --
     * the same shape {@code FromBuilder} builds for such a scan. The join's
     * left component is the value that was matched, which nothing above wants,
     * so a projection drops it.
     */
    private List<String> pushMatching(Core.Pat pat, Core.Exp collection) {
      final List<Core.NamedPat> bound = pat.expand();
      final Core.Exp element = core.recordOrAtom(typeMap.typeSystem, bound);
      final Type elementType = collection.type.elementType();
      b.push(collection);
      final Core.IdPat binder = b.binder();
      final Core.Exp body =
          core.caseOf(
              Pos.ZERO,
              typeMap.typeSystem.listType(element.type),
              core.id(binder),
              ImmutableList.of(
                  core.match(
                      Pos.ZERO,
                      pat,
                      core.list(
                          typeMap.typeSystem,
                          element.type,
                          ImmutableList.of(element))),
                  core.match(
                      Pos.ZERO,
                      core.wildcardPat(elementType),
                      core.list(
                          typeMap.typeSystem,
                          element.type,
                          ImmutableList.of()))));
      b.push(body);
      b.pair();
      b.join(JoinType.INNER, core.boolLiteral(true));
      final Core.Exp matched = core.field(typeMap.typeSystem, b.input(0), 1);
      final List<String> names = new ArrayList<>();
      bound.forEach(p -> names.add(p.name));
      if (names.size() == 1) {
        // One binder names the row, so the projection has to say the name; a
        // record's fields the builder names for us.
        b.project(names.get(0), matched);
      } else {
        b.project(matched);
      }
      // The pattern is gone, and what it bound is read back out by paths.
      return names;
    }

    /**
     * Returns the collection of every value of a type, carrying the names the
     * scan bound so that grounding can quote them.
     */
    private Core.Exp extent(Pos pos, Type type, List<String> names) {
      return core.extent(
          pos,
          typeMap.typeSystem,
          type,
          ImmutableRangeSet.of(Range.all()),
          names);
    }

    /** Returns the kind of join a scan's keyword asks for. */
    private JoinType joinType(Op op) {
      switch (op) {
        case LEFT_JOIN:
          return JoinType.LEFT;
        case RIGHT_JOIN:
          return JoinType.RIGHT;
        case FULL_JOIN:
          return JoinType.FULL;
        default:
          return JoinType.INNER;
      }
    }

    /**
     * Pushes a collection under a pattern, and returns the names the pattern
     * binds.
     */
    private List<String> push(Ast.Pat pat, Core.Exp collection) {
      final Ast.@Nullable IdPat id = bareId(pat);
      if (id != null) {
        final String name = id.name;
        b.push(name, collection);
        return ImmutableList.of(name);
      }
      return push(
          Resolver.this.toCore(pat, collection.type.elementType()), collection);
    }

    /** As {@link #push(Ast.Pat, Core.Exp)}, for a pattern already converted. */
    private List<String> push(Core.Pat corePat, Core.Exp collection) {
      if (!RelBuilder.destructurable(corePat)
          && !RelBuilder.testable(corePat)) {
        return pushMatching(corePat, collection);
      }
      b.push(corePat, collection);
      final List<String> names = new ArrayList<>();
      corePat.accept(
          new Visitor() {
            @Override
            protected void visit(Core.IdPat idPat) {
              names.add(idPat.name);
            }

            @Override
            protected void visit(Core.AsPat asPat) {
              // Both halves: `p as (a, b)` binds `p` and what it wraps binds,
              // and each is a name the tree has a path for.
              names.add(asPat.name);
              super.visit(asPat);
            }
          });
      return names;
    }

    /**
     * Passes the query so far through a function, and scans what comes back.
     *
     * <p>{@code from ... through p in f} is {@code from p in f (from ...)}, so
     * the tree built so far is finished here rather than at the end, and the
     * builder starts again from the collection the function returns.
     */
    private void through(Ast.Through through) {
      finish();
      // The query so far is an expression, so it can be the function's
      // argument as it stands.
      final Core.Exp inner = b.build();
      // The function is evaluated once, on the whole collection, so it reads
      // the enclosing scope and not this query's row.
      final Core.Exp fn = toCore(through.exp, null);
      final Core.Exp collection =
          core.apply(through.pos(), typeMap.getType(through), fn, inner);
      binders.clear();
      binders.addAll(push(through.pat, collection));
      atom = binders.size() == 1;
      rowIsElement = through.pat instanceof Ast.IdPat;
    }

    /**
     * Multiplies each row by the elements of a collection, and keeps only
     * those.
     *
     * <p>A dependent join and a projection: the join's binder is how the right
     * input names the current row of the left, and the projection drops the
     * left again, since {@code yieldAll} yields only the elements.
     */
    private void yieldAll(Ast.YieldAll yieldAll) {
      final Core.IdPat joinBinder = b.binder();
      final Core.Exp collection =
          toCoreSource(yieldAll.exp, core.id(joinBinder));
      final String name =
          yieldAll.binder == null
              ? typeMap.typeSystem.nameGenerator.get()
              : yieldAll.binder.name;
      b.push(name, collection).pair();
      b.join(JoinType.INNER, core.boolLiteral(true));
      b.project(name, b.name(name));
      binders.clear();
      if (yieldAll.binder != null) {
        binders.add(name);
      }
      atom = true;
      rowIsElement = true;
    }

    /**
     * Keeps one row of each distinct value, by grouping on every binder.
     *
     * <p>A row of {@code unit} is the exception: {@code group {}} always
     * returns one row, so an empty input would gain one, and {@code take 1} is
     * what is meant.
     */
    private void distinct() {
      finish();
      final SortedMap<String, Core.Exp> keys = new TreeMap<>();
      if (binders.isEmpty() || atom) {
        if (b.input(0).type == PrimitiveType.UNIT) {
          b.take(core.intLiteral(BigDecimal.ONE));
          return;
        }
        // The row is one value, so group by it and read it back out of the
        // record the group makes.
        final String name =
            binders.isEmpty()
                ? typeMap.typeSystem.nameGenerator.get()
                : requireNonNull(getOnlyElement(binders));
        keys.put(name, b.input(0));
        b.group(keys, ImmutableSortedMap.of());
        b.project(name, b.name(name));
      } else {
        binders.forEach(name -> keys.put(name, b.name(name)));
        b.group(keys, ImmutableSortedMap.of());
      }
    }

    /**
     * Combines the query so far with one or more collections.
     *
     * <p>A set operator compares rows, so the row has to be built first: after
     * a join the element is the inputs' components, which is not what the
     * query's binders name. The arguments are whole collections, evaluated
     * once, so they are read in the enclosing scope as a count is.
     */
    private void setStep(Ast.SetStep set) {
      finish();
      set.args.forEach(arg -> b.push(toCore(arg, null)));
      final int n = set.args.size() + 1;
      switch (set.op) {
        case UNION:
          b.union(n, set.distinct);
          break;
        case INTERSECT:
          b.intersect(n, set.distinct);
          break;
        default:
          b.except(n, set.distinct);
          break;
      }
    }

    /**
     * Groups, and then names what the group produced.
     *
     * <p>A tree's group builds a record whether it has one label or many, so an
     * atomizing group -- {@code group e.deptno}, whose rows are bare ints -- is
     * that record and a projection that reads its one field. A group with
     * expressions over its labels ({@code compute {n = count() * 2}}) is the
     * same record and a projection that computes them.
     */
    private void group_(Ast.Group group) {
      final boolean groupIsAtom = group.isAtom();
      final Map<String, Core.Exp> paths = new LinkedHashMap<>();
      for (String binder : binders) {
        paths.put(binder, b.name(binder));
      }
      final Scope scope =
          new Scope(paths, natural(paths, b.input(0)), ordinalPath());
      final boolean ordered = b.peek().type instanceof ListType;

      // Group keys and aggregate arguments read the row before the group, and
      // the expressions that name the result read the labels the group made.
      final PairList<Core.IdPat, Core.Exp> groupExps = PairList.of();
      final PairList<Core.IdPat, Core.Aggregate> aggregates = PairList.of();
      final PairList<String, Core.Exp> postExps = PairList.of();
      if (groupIsAtom) {
        final Resolver aggregateResolver =
            scope
                .resolver()
                .withAggregateResolver(
                    env,
                    scope.bindings,
                    ordered,
                    ImmutableList.of(),
                    aggregates);
        final boolean emptyKey =
            group.group instanceof Ast.Record
                && ((Ast.Record) group.group).args.isEmpty();
        final Core.Exp exp;
        final @Nullable String label;
        if (emptyKey) {
          // No group keys, so compute is a singleton.
          requireNonNull(group.aggregate);
          exp = aggregateResolver.toCore(group.aggregate, null);
          label = ast.implicitLabelOpt(group.aggregate);
        } else {
          // One group key, so compute is empty.
          requireNonNull(group.group);
          exp = scope.toCore(group.group);
          label = ast.implicitLabelOpt(group.group);
        }
        // A reference to a binder has become a path by now, so the label is
        // all there is.
        final Core.IdPat idPat =
            label != null
                ? core.idPat(exp.type, label, 0)
                : core.idPat(exp.type, typeMap.typeSystem.nameGenerator::get);
        if (emptyKey) {
          postExps.add(idPat.name, exp);
        } else {
          groupExps.add(idPat, exp);
          postExps.add(idPat.name, core.id(idPat));
        }
      } else {
        group
            .key()
            .args
            .forEach(
                (id, exp) -> groupExps.add(toCorePat(id), scope.toCore(exp)));
        final Resolver aggregateResolver =
            scope
                .resolver()
                .withAggregateResolver(
                    env,
                    scope.bindings,
                    ordered,
                    groupExps.leftList(),
                    aggregates);
        groupExps.forEach((id, exp) -> postExps.add(id.name, core.id(id)));
        group
            .compute()
            .args
            .forEach(
                (id, exp) ->
                    postExps.add(id.name, aggregateResolver.toCore(exp, id)));
      }

      final SortedMap<String, Core.Exp> keys = new TreeMap<>();
      groupExps.forEach((pat, exp) -> keys.put(pat.name, exp));
      final SortedMap<String, Core.Aggregate> aggs = new TreeMap<>();
      aggregates.forEach(
          (pat, aggregate) ->
              aggs.put(
                  pat.name,
                  aggregate.copy(
                      aggregate.type,
                      aggregate.aggregate,
                      aggregate.argument == null
                          ? null
                          // An argument reads the row before the group.
                          : scope.substitute(aggregate.argument))));
      b.group(keys, aggs);

      // The group's element is a record of its labels, so the expressions
      // that name the result read them off its fields.
      final Map<String, Core.Exp> labels = new LinkedHashMap<>();
      keys.keySet().forEach(name -> labels.put(name, b.name(name)));
      aggs.keySet().forEach(name -> labels.put(name, b.name(name)));
      final Scope after = new Scope(labels, b.input(0), null);
      groupExps.forEach((pat, exp) -> after.alias(pat, b.name(pat.name)));
      aggregates.forEach((pat, agg) -> after.alias(pat, b.name(pat.name)));
      binders.clear();
      if (group.binder != null) {
        // `group g = {...}` names the whole result `g`, as `yield g = ...`
        // does: one name, and not the labels the group made.
        final PairList<String, Core.Exp> nameExps = PairList.of();
        postExps.forEach(
            (name, exp) -> nameExps.add(name, after.substitute(exp)));
        b.project(
            group.binder.name,
            groupIsAtom
                ? nameExps.right(0)
                : core.record(typeMap.typeSystem, nameExps));
        binders.add(group.binder.name);
        atom = true;
        rowIsElement = true;
        return;
      }
      if (groupIsAtom) {
        final String name = postExps.left(0);
        b.project(name, after.substitute(postExps.right(0)));
        binders.add(name);
        atom = true;
      } else {
        postExps.forEach((name, exp) -> binders.add(name));
        if (!isIdentity(postExps, labels.keySet())) {
          // Only where the group's labels are not already what the query
          // calls them. An identity projection here would be a second
          // projection under the query's own yield, and merging the two
          // binds the row to a variable -- which is right, but a `let` is
          // something Calcite cannot push down.
          final PairList<String, Core.Exp> nameExps = PairList.of();
          postExps.forEach(
              (name, exp) -> nameExps.add(name, after.substitute(exp)));
          b.project(core.record(typeMap.typeSystem, nameExps));
        }
        atom = false;
      }
      rowIsElement = true;
    }

    /**
     * Returns whether each expression is a reference to the group's label of
     * its own name, so that a projection of them would leave the row as it is.
     *
     * <p>Being a label matters and not merely sharing a name: {@code compute
     * sum}, with nothing to sum, reads the built-in {@code sum} under that
     * name, and a row that dropped it would be a row short of a field.
     */
    private boolean isIdentity(
        PairList<String, Core.Exp> nameExps, Set<String> labels) {
      return nameExps.allMatch(
          (name, exp) ->
              labels.contains(name)
                  && exp instanceof Core.Id
                  && ((Core.Id) exp).idPat.name.equals(name));
    }

    /**
     * Projects, and renames what the query binds, because the yield has
     * replaced the row.
     *
     * <p>Follows the rule by which the type resolver has already decided which
     * names the steps after this one may use: a record yield binds its fields,
     * and any other yield binds the row under one name, if it has one to offer.
     */
    private void yield_(Ast.Yield yield) {
      final Core.Exp exp = toCore(yield.exp);
      binders.clear();
      if (yield.binder != null) {
        // `yield r = e` names the whole row `r`, whatever `e` is: a record
        // yielded this way binds one name and not its fields.
        b.project(yield.binder.name, exp);
        binders.add(yield.binder.name);
        atom = true;
        rowIsElement = true;
        return;
      }
      final @Nullable String name = atomName(yield.exp);
      // 'record' is what the user wrote, not what the expression turned out to
      // be: a record with modifiers is a record, and yet it is a 'let' by the
      // time it gets here, so only the Ast can say.
      if (TypeResolver.letBody(yield.exp).op == Op.RECORD
          && exp.type.op() == Op.RECORD_TYPE) {
        // The builder names an element's fields for us.
        b.project(exp);
        binders.addAll(((RecordLikeType) exp.type).argNameTypes().keySet());
        atom = false;
        rowIsElement = true;
        return;
      }
      atom = true;
      rowIsElement = true;
      if (name == null) {
        b.project(exp);
      } else {
        b.project(name, exp);
        binders.add(name);
      }
    }

    /**
     * Returns the name that an atomizing yield binds its row under, or null if
     * it offers none, in which case only {@code current} reads the row.
     *
     * <p>The same rule as {@link CoreBuilder}'s {@code getIdPat} -- a reference
     * keeps its name, {@code e.deptno} gives {@code deptno}, and anything else
     * is anonymous -- but read off the {@link Ast}, because by the time the
     * expression is converted a reference to a binder has become a path into
     * the element and no longer looks like one.
     */
    private @Nullable String atomName(Ast.Exp exp) {
      switch (exp.op) {
        case ID:
          return ((Ast.Id) exp).name;

        case CURRENT:
          // The row has a name only where one binder is the whole of it.
          return atom && binders.size() == 1 ? getOnlyElement(binders) : null;

        case APPLY:
          final Ast.Apply apply = (Ast.Apply) exp;
          return apply.fn instanceof Ast.RecordSelector
              ? ((Ast.RecordSelector) apply.fn).name
              : null;

        default:
          return null;
      }
    }

    /**
     * Rewrites a path the builder gave -- which reads the element as {@code $0}
     * -- to read it as {@code element} instead.
     */
    private Core.Exp rootAt(Core.Exp path, Core.Exp element) {
      final Core.IdPat row = (Core.IdPat) ((Core.Id) b.input(0)).idPat;
      if (element instanceof Core.Id && ((Core.Id) element).idPat.equals(row)) {
        return path;
      }
      return path.accept(
          new Shuttle(typeMap.typeSystem) {
            @Override
            protected Core.Exp visit(Core.Id id) {
              return id.idPat.equals(row) ? core.at(element, id.pos()) : id;
            }
          });
    }

    /**
     * Converts an expression, resolving each name to the path that reads it out
     * of the element.
     *
     * <p>The resolver gives a name as a reference to its binder; the builder
     * says where that binder lives in the element, and the two are joined by
     * substitution.
     */
    private Core.Exp toCore(Ast.Exp exp) {
      return toCore(exp, b.size() == 0 ? null : b.input(0));
    }

    /**
     * Converts an expression that reads the row through {@code element}, which
     * is {@code $0} in a node's own expressions and a join's binder in its
     * right input.
     */
    private Core.Exp toCore(Ast.Exp exp, Core.@Nullable Exp element) {
      if (element == null) {
        // The first scan's collection is evaluated before the query has a
        // row, so it sees the enclosing scope and not this query's.
        return Resolver.this.toCore(exp);
      }
      final Map<String, Core.Exp> paths = new LinkedHashMap<>();
      for (String binder : binders) {
        paths.put(binder, rootAt(b.name(binder), element));
      }
      return toCore(exp, paths, natural(paths, element), ordinalPath());
    }

    /**
     * Converts the source of a dependent scan, which reads the left element
     * through the join's left row.
     *
     * <p>Unlike an expression of a node, a source is an input, and a tree
     * nested in it reads the join's patterns by ordinary scoping: the left row
     * is in scope in the right input, and so is the join's ordinal. So nothing
     * is bound with a {@code let} on the way in.
     */
    private Core.Exp toCoreSource(Ast.Exp exp, Core.Exp element) {
      final Map<String, Core.Exp> paths = new LinkedHashMap<>();
      for (String binder : binders) {
        paths.put(binder, rootAt(b.name(binder), element));
      }
      final Scope scope =
          new Scope(paths, natural(paths, element), ordinalPath());
      scope.bindNested = false;
      return scope.toCore(exp);
    }

    /**
     * Returns the path that reads the ordinal field, rooted where its reader
     * reads it, or null if no field was projected.
     */
    private Core.Exp ordinalPath() {
      return b.ordinal();
    }

    /**
     * Converts a join's condition, which reads the left input as {@code $0} and
     * the right as {@code $1}.
     *
     * <p>{@code current} is the row so far, which is the left's: the right's
     * binder is in scope by name, but the condition is asked of a row the join
     * has not made yet.
     */
    private Core.Exp on(Ast.Exp exp, List<String> rightBinders) {
      final Map<String, Core.Exp> left = new LinkedHashMap<>();
      for (String binder : binders) {
        left.put(binder, b.name(0, binder));
      }
      final Map<String, Core.Exp> paths = new LinkedHashMap<>(left);
      rightBinders.forEach(name -> paths.put(name, b.name(1, name)));
      return toCore(exp, paths, natural(left, b.input(0)), b.ordinal());
    }

    /** Converts an expression, given where each name it may use is found. */
    private Core.Exp toCore(
        Ast.Exp exp,
        Map<String, Core.Exp> paths,
        Core.Exp current,
        Core.@Nullable Exp ordinalPath) {
      return new Scope(paths, current, ordinalPath).toCore(exp);
    }

    /**
     * What names mean at one point in the build: where each is found in the
     * element, and what {@code current} denotes.
     *
     * <p>The resolver gives a name as a reference to its binder; the builder
     * says where that binder lives; and the two are joined by substitution.
     */
    private class Scope {
      final Core.Exp current;
      final List<Binding> bindings = new ArrayList<>();

      /**
       * Path to each binder, by the pattern that binds it rather than by its
       * name.
       *
       * <p>By the pattern, because a name is not unique: {@code forall p in
       * s.pictures require ... exists p in s.products where p.sku = sku}
       * rebinds {@code p}, and the inner query says {@code p} again.
       * Substituting by name would give the inner query the outer row. A fresh
       * ordinal for each binder is what keeps them apart, and the resolver
       * hands back the very pattern it was given.
       */
      private final Map<Core.NamedPat, Core.Exp> byPat = new LinkedHashMap<>();

      /** The pattern that {@code current} resolves to; see the constructor. */
      private final Core.IdPat currentPat;

      /** The pattern that {@code ordinal} resolves to, or null. */
      private final Core.@Nullable IdPat ordinalPat;

      /**
       * The node patterns this scope's paths read: the row, a join's right row,
       * and the ordinal.
       */
      private final Set<Core.NamedPat> inputPats = new LinkedHashSet<>();

      /**
       * Whether a tree nested in the expression reads the node's patterns
       * through a {@code let} that binds them first; false for the source of a
       * dependent scan, which reads the join's patterns directly.
       */
      boolean bindNested = true;

      Scope(
          Map<String, Core.Exp> paths,
          Core.Exp current,
          Core.@Nullable Exp ordinalPath) {
        this.current = current;
        paths.forEach(
            (name, path) -> {
              final Core.IdPat pat =
                  core.idPat(path.type, name, --scopeOrdinal);
              byPat.put(pat, path);
              bindings.add(Binding.of(pat));
            });
        // A path, and `current`, read an input of the tree, `$0` or `$1`, and
        // a nested query is resolved against this environment, so the inputs
        // must be visible in it. Substitution replaces them afterwards.
        final Map<String, Core.NamedPat> inputs = new LinkedHashMap<>();
        final Visitor inputBinder =
            new Visitor() {
              @Override
              protected void visit(Core.Id id) {
                inputs.put(id.idPat.name, id.idPat);
              }
            };
        paths.values().forEach(path -> path.accept(inputBinder));
        current.accept(inputBinder);
        inputs.values().forEach(pat -> bindings.add(Binding.of(pat)));
        inputPats.addAll(inputs.values());
        // `ordinal` resolves to the ordinal pattern the node binds; a nested
        // tree that reads it is caught by the same rule as one reading the
        // row, because the pattern's name says it is a node's.
        ordinalPat =
            ordinalPath == null
                ? null
                : (Core.IdPat) ((Core.Id) ordinalPath).idPat;
        if (ordinalPat != null) {
          // As a path, so that a nested tree reading it is caught by the same
          // rule as one reading the row, and binds it with a `let` first.
          byPat.put(ordinalPat, core.id(ordinalPat));
          inputPats.add(ordinalPat);
        }
        // `current` is a path like any other, so that a nested tree that reads
        // the row is caught by the same rule that catches a nested tree
        // reading a name. The pattern never survives substitution: outside a
        // tree it becomes `current`, inside one the binder that `toCore` then
        // binds.
        currentPat = core.idPat(current.type, "current", --scopeOrdinal);
        byPat.put(currentPat, current);
      }

      /**
       * Adds a path for a pattern the caller already has.
       *
       * <p>The constructor invents a pattern per name, with an ordinal of its
       * own, because in general it has only names. A {@code group}'s keys and
       * aggregates are the exception: the resolver made those patterns and the
       * expressions it is about to substitute refer to *them*, so the paths
       * have to be filed under the patterns themselves or the substitution
       * misses and a bare reference survives into the tree.
       */
      void alias(Core.NamedPat pat, Core.Exp path) {
        byPat.put(pat, path);
      }

      /** Returns a resolver that reads this scope's names. */
      Resolver resolver() {
        final Resolver r =
            Resolver.this.withEnv(bindings).withCurrent(core.id(currentPat));
        return ordinalPat == null ? r : r.withOrdinalPat(ordinalPat);
      }

      /**
       * Binder for each input that a nested tree reads, invented on demand.
       *
       * <p>A path reads the element as {@code $i}, and inside a tree nested in
       * the expression that means the nested tree's own element, not this one.
       * The remedy is to bind the element first, so a path planted inside a
       * nested tree reads the binder instead, and {@link #toCore} wraps the
       * expression in the {@code let} that binds it.
       */
      private final Map<Core.IdPat, Core.IdPat> rowPats = new LinkedHashMap<>();

      /**
       * Replaces each reference to a name with the path that reads it, and
       * binds the element where a nested tree reads it.
       */
      Core.Exp substitute(Core.Exp exp) {
        // Per call, not per scope: a scope converts several expressions, and
        // only the ones with a nested tree that reads the element get a let.
        rowPats.clear();
        Core.Exp e = substitute(exp, false);
        for (Map.Entry<Core.IdPat, Core.IdPat> entry : rowPats.entrySet()) {
          e =
              core.let(
                  core.nonRecValDecl(
                      Pos.ZERO,
                      entry.getValue(),
                      null,
                      core.id(entry.getKey())),
                  e);
        }
        rowPats.clear();
        return e;
      }

      private Core.Exp substitute(Core.Exp exp, boolean inRel) {
        return exp.accept(
            new Shuttle(typeMap.typeSystem) {
              @Override
              protected Core.@Nullable Exp visitRel(Core.Rel rel) {
                // Once inside a nested tree we stay inside: every path planted
                // below here reads the binder rather than the input.
                return inRel ? null : substitute(rel, true);
              }

              @Override
              protected Core.Exp visit(Core.Id id) {
                final Core.@Nullable Exp path = byPat.get(id.idPat);
                if (path == null) {
                  return id;
                }
                return core.at(
                    inRel && bindNested ? bindInputs(path) : path, id.pos());
              }
            });
      }

      /** Replaces each input reference in a path with a binder for it. */
      private Core.Exp bindInputs(Core.Exp path) {
        return path.accept(
            new Shuttle(typeMap.typeSystem) {
              @Override
              protected Core.Exp visit(Core.Id id) {
                // Only this scope's own patterns: the row, a join's right
                // row, the ordinal. A pattern of an enclosing node, such as
                // a join's left row read by its right input, is an ordinary
                // name here and needs no binding.
                if (!inputPats.contains(id.idPat)) {
                  return id;
                }
                return core.id(
                    rowPats.computeIfAbsent(
                        (Core.IdPat) id.idPat,
                        p ->
                            core.idPat(
                                p.type, () -> nameGenerator.getPrefixed("v"))));
              }
            });
      }

      Core.Exp toCore(Ast.Exp exp) {
        return substitute(resolver().toCore(exp));
      }
    }
  }

  /**
   * Converts a {@link Ast.From}, {@link Ast.Exists} or {@link Ast.Forall} to
   * Core.
   *
   * <p>The steps themselves are {@link StepResolver}'s: this holds what is true
   * of the query as a whole -- what {@code into}, {@code exists}, {@code
   * forall} and {@code compute} wrap it in -- and answers the questions the
   * step conversion asks about {@code ordinal}.
   */
  private class FromResolver {
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
        return core.apply(exp.pos(), typeMap.getType(query), exp, coreFrom);
      }

      final Core.Exp coreFrom = run(query.steps);
      if (query.op == Op.EXISTS) {
        // Translate "exists ..." as if they had written
        // "Relational.nonEmpty (from ...)"
        return core.nonEmpty(typeMap.typeSystem, query.pos(), coreFrom);
      } else if (query.op == Op.FORALL) {
        // Translate "forall ... require e" as if they had written
        // "not exists (from ... where not e)".
        //
        // We assume that the last step is 'require e', and we know that
        // 'require e' will have been translated to the same as 'where not e'.
        checkArgument(last(query.steps).op == Op.REQUIRE);
        return core.empty(typeMap.typeSystem, query.pos(), coreFrom);
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
      return new StepResolver().run(steps);
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
        //   fn col$0 => aggFn(converter(col$0))
        // The parameter takes a generated name, not one starting with "$":
        // a "$" name is a node's pattern, and the plan printer treats it as
        // one.
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
                inputCollType,
                typeMap.typeSystem.nameGenerator.getPrefixed("col"),
                0);
        final Core.Exp paramRef = core.id(param);
        final Core.Exp converterLit =
            core.functionLiteral(typeMap.typeSystem, converter);
        final Core.Exp converted =
            core.apply(Pos.ZERO, argType, converterLit, paramRef);
        final Core.Exp applied =
            core.apply(
                aggregate.pos(), typeMap.getType(aggregate), aggFn, converted);
        final FnType wrappedType =
            typeMap.typeSystem.fnType(
                inputCollType, typeMap.getType(aggregate));
        aggFn = core.fn(wrappedType, param, applied);
      }
      final Core.Aggregate coreAggregate =
          core.aggregate(
              aggregate.pos(),
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
              elements.pos(),
              type,
              core.functionLiteral(fnType, BuiltIn.FN_ID),
              inputResolver.current);
      String base = Op.ELEMENTS.lowerName;
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
