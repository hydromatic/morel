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
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.last;
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.skipLast;
import static net.hydromatic.morel.util.Static.transform;
import static net.hydromatic.morel.util.Static.transformEager;
import static org.apache.calcite.util.Util.intersects;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.ImmutableSortedMap;
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
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;
import net.hydromatic.morel.util.Pair;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Converts AST expressions to Core expressions. */
public class Resolver {
  /** Map from {@link Op} to {@link BuiltIn}. */
  public static final ImmutableMap<Op, BuiltIn> OP_BUILT_IN_MAP =
      Init.INSTANCE.opBuiltInMap;

  /**
   * Map from {@link BuiltIn}, to {@link Op}; the reverse of {@link
   * #OP_BUILT_IN_MAP}, and needed when we convert an optimized expression back
   * to human-readable Morel code.
   */
  public static final ImmutableMap<BuiltIn, Op> BUILT_IN_OP_MAP =
      Init.INSTANCE.builtInOpMap;

  final TypeMap typeMap;
  private final NameGenerator nameGenerator;
  private final Environment env;
  private final @Nullable Session session;

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
      Environment env,
      @Nullable Session session) {
    this.typeMap = typeMap;
    this.nameGenerator = nameGenerator;
    this.variantIdMap = variantIdMap;
    this.env = env;
    this.session = session;
  }

  /** Creates a root Resolver. */
  public static Resolver of(
      TypeMap typeMap, Environment env, @Nullable Session session) {
    NameGenerator nameGenerator =
        session == null ? new NameGenerator() : session.nameGenerator;
    return new Resolver(typeMap, nameGenerator, new HashMap<>(), env, session);
  }

  /** Binds a Resolver to a new environment. */
  public Resolver withEnv(Environment env) {
    return env == this.env
        ? this
        : new Resolver(typeMap, nameGenerator, variantIdMap, env, session);
  }

  /**
   * Binds a Resolver to an environment that consists of the current environment
   * plus some bindings.
   */
  public final Resolver withEnv(Iterable<Binding> bindings) {
    return withEnv(Environments.bind(env, bindings));
  }

  public Core.Decl toCore(Ast.Decl node) {
    switch (node.op) {
      case VAL_DECL:
        return toCore((Ast.ValDecl) node);

      case DATATYPE_DECL:
        return toCore((Ast.DatatypeDecl) node);

      default:
        throw new AssertionError(
            "unknown decl [" + node.op + ", " + node + "]");
    }
  }

  /**
   * Converts a simple {@link net.hydromatic.morel.ast.Ast.ValDecl}, of the form
   * {@code val v = e}, to a Core {@link net.hydromatic.morel.ast.Core.ValDecl}.
   *
   * <p>Declarations such as {@code val (x, y) = (1, 2)} and {@code val emp ::
   * rest = emps} are considered complex, and are not handled by this method.
   *
   * <p>Likewise recursive declarations.
   */
  public Core.ValDecl toCore(Ast.ValDecl valDecl) {
    final List<Binding> bindings = new ArrayList<>(); // discard
    final ResolvedValDecl resolvedValDecl = resolveValDecl(valDecl, bindings);
    Core.NonRecValDecl nonRecValDecl =
        core.nonRecValDecl(
            resolvedValDecl.patExps.get(0).pos,
            resolvedValDecl.pat,
            resolvedValDecl.exp);
    return resolvedValDecl.rec
        ? core.recValDecl(ImmutableList.of(nonRecValDecl))
        : nonRecValDecl;
  }

  public Core.DatatypeDecl toCore(Ast.DatatypeDecl datatypeDecl) {
    final List<Binding> bindings = new ArrayList<>(); // populated, never read
    final ResolvedDatatypeDecl resolvedDatatypeDecl =
        resolveDatatypeDecl(datatypeDecl, bindings);
    return resolvedDatatypeDecl.toDecl();
  }

  private ResolvedDecl resolve(Ast.Decl decl, List<Binding> bindings) {
    if (decl instanceof Ast.DatatypeDecl) {
      return resolveDatatypeDecl((Ast.DatatypeDecl) decl, bindings);
    } else {
      return resolveValDecl((Ast.ValDecl) decl, bindings);
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

  private ResolvedValDecl resolveValDecl(
      Ast.ValDecl valDecl, List<Binding> bindings) {
    final boolean composite = valDecl.valBinds.size() > 1;
    final Map<Ast.Pat, Ast.Exp> matches = new LinkedHashMap<>();
    valDecl.valBinds.forEach(
        valBind -> flatten(matches, composite, valBind.pat, valBind.exp));

    final List<PatExp> patExps = new ArrayList<>();
    if (valDecl.rec) {
      final List<Core.Pat> pats = new ArrayList<>();
      matches.forEach((pat, exp) -> pats.add(toCore(pat)));
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
          (pat, exp) ->
              patExps.add(
                  new PatExp(toCore(pat), toCore(exp), pat.pos.plus(exp.pos))));
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
    final Core.NamedPat pat;
    if (pat0 instanceof Core.NamedPat) {
      pat = (Core.NamedPat) pat0;
    } else {
      pat = core.asPat(exp.type, "it", nameGenerator, pat0);
    }

    return new ResolvedValDecl(rec, ImmutableList.copyOf(patExps), pat, exp);
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

  private Core.Exp toCore(Ast.Exp exp) {
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
      case ANNOTATED_EXP:
        return toCore(((Ast.AnnotatedExp) exp).exp);
      case ID:
        return toCore((Ast.Id) exp);
      case ANDALSO:
      case ORELSE:
        return toCore((Ast.InfixCall) exp);
      case IMPLIES:
        return toCoreImplies(
            ((Ast.InfixCall) exp).a0, ((Ast.InfixCall) exp).a1);
      case APPLY:
        return toCore((Ast.Apply) exp);
      case FN:
        return toCore((Ast.Fn) exp);
      case IF:
        return toCore((Ast.If) exp);
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
    final Core.NamedPat idPat = getIdPat(id, binding);
    return core.id(idPat);
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
  private Core.NamedPat getIdPat(Ast.Id id, Binding binding) {
    final Type type = typeMap.getType(id);
    if (type == binding.id.type) {
      return binding.id;
    }
    // The required type is different from the binding type, presumably more
    // specific. Create a new IdPat, reusing an existing IdPat if there was
    // one for the same type.
    return variantIdMap.computeIfAbsent(
        Pair.of(binding.id, type), k -> k.left.withType(k.right));
  }

  private Core.Tuple toCore(Ast.Tuple tuple) {
    return core.tuple(
        (RecordLikeType) typeMap.getType(tuple),
        transformEager(tuple.args, this::toCore));
  }

  private Core.Tuple toCore(Ast.Record record) {
    RecordLikeType type = (RecordLikeType) typeMap.getType(record);
    List<Core.Exp> args;
    if (record.with != null) {
      args = new ArrayList<>();
      final Core.Exp coreWith = toCore(record.with);
      forEachIndexed(
          type.argNameTypes().keySet(),
          (field, i) -> {
            Ast.Exp exp = record.args.get(field);
            if (exp != null) {
              args.add(toCore(exp));
            } else {
              args.add(core.field(typeMap.typeSystem, coreWith, i));
            }
          });
    } else {
      args = transformEager(record.args(), this::toCore);
    }
    return core.tuple(type, args);
  }

  private Core.Exp toCore(Ast.ListExp list) {
    final ListType type = (ListType) typeMap.getType(list);
    return core.apply(
        list.pos,
        type,
        core.functionLiteral(typeMap.typeSystem, BuiltIn.Z_LIST),
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
    Core.Exp coreArg = toCore(apply.arg);
    Type type = typeMap.getType(apply);
    Core.Exp coreFn;
    if (apply.fn.op == Op.RECORD_SELECTOR) {
      final Ast.RecordSelector recordSelector = (Ast.RecordSelector) apply.fn;
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
          || type instanceof ListType
              && ((ListType) type).elementType.isProgressive()) {
        // If we are dereferencing a field in a progressive type, the type
        // available now may be more precise than the deduced type.
        type = ((FnType) coreFn.type).resultType;
      }
    } else {
      coreFn = toCore(apply.fn);
    }
    return core.apply(apply.pos, type, coreFn, coreArg);
  }

  static Object valueOf(Environment env, Core.Exp exp) {
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
    final BuiltIn builtIn = toBuiltIn(call.op);
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

  private BuiltIn toBuiltIn(Op op) {
    return OP_BUILT_IN_MAP.get(op);
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
    final Type type = typeMap.getType(pat);
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
        final RecordType recordType = (RecordType) targetType;
        final Ast.RecordPat recordPat = (Ast.RecordPat) pat;
        final ImmutableList.Builder<Core.Pat> args = ImmutableList.builder();
        recordType.argNameTypes.forEach(
            (label, argType) -> {
              final Ast.Pat argPat = recordPat.args.get(label);
              final Core.Pat corePat =
                  argPat != null ? toCore(argPat) : core.wildcardPat(argType);
              args.add(corePat);
            });
        return core.recordPat(recordType, args.build());

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
  private static boolean subsumes(Type actualType, Type expectedType) {
    switch (actualType.op()) {
      case LIST:
        if (expectedType.op() != Op.LIST) {
          return false;
        }
        return subsumes(
            ((ListType) actualType).elementType,
            ((ListType) expectedType).elementType);
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
        final Iterator<Map.Entry<String, Type>> actualIterator =
            actualMap.entrySet().iterator();
        final Iterator<Map.Entry<String, Type>> expectedIterator =
            expectedMap.entrySet().iterator();
        for (; ; ) {
          if (actualIterator.hasNext()) {
            if (!expectedIterator.hasNext()) {
              // expected had fewer entries than actual
              return false;
            }
          } else {
            if (!expectedIterator.hasNext()) {
              // expected and actual had same number of entries
              return true;
            }
          }
          final Map.Entry<String, Type> actual = actualIterator.next();
          final Map.Entry<String, Type> expected = expectedIterator.next();
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

  private Core.Aggregate toCore(
      Ast.Aggregate aggregate, Collection<? extends Core.IdPat> groupKeys) {
    final Resolver resolver = withEnv(transform(groupKeys, Binding::of));
    return core.aggregate(
        typeMap.getType(aggregate),
        resolver.toCore(aggregate.aggregate),
        aggregate.argument == null ? null : toCore(aggregate.argument));
  }

  private Core.OrderItem toCore(Ast.OrderItem orderItem) {
    return core.orderItem(toCore(orderItem.exp), orderItem.direction);
  }

  /** Helper for initialization. */
  private enum Init {
    INSTANCE;

    final ImmutableMap<Op, BuiltIn> opBuiltInMap;
    final ImmutableMap<BuiltIn, Op> builtInOpMap;

    Init() {
      Object[] values = {
        BuiltIn.LIST_OP_AT, Op.AT,
        BuiltIn.OP_CONS, Op.CONS,
        BuiltIn.OP_EQ, Op.EQ,
        BuiltIn.OP_EXCEPT, Op.EXCEPT,
        BuiltIn.OP_GE, Op.GE,
        BuiltIn.OP_GT, Op.GT,
        BuiltIn.OP_INTERSECT, Op.INTERSECT,
        BuiltIn.OP_LE, Op.LE,
        BuiltIn.OP_LT, Op.LT,
        BuiltIn.OP_NE, Op.NE,
        BuiltIn.OP_UNION, Op.UNION,
        BuiltIn.Z_ANDALSO, Op.ANDALSO,
        BuiltIn.Z_ORELSE, Op.ORELSE,
        BuiltIn.Z_PLUS_INT, Op.PLUS,
        BuiltIn.Z_PLUS_REAL, Op.PLUS,
      };
      final ImmutableMap.Builder<BuiltIn, Op> b2o = ImmutableMap.builder();
      final Map<Op, BuiltIn> o2b = new HashMap<>();
      for (int i = 0; i < values.length / 2; i++) {
        BuiltIn builtIn = (BuiltIn) values[i * 2];
        Op op = (Op) values[i * 2 + 1];
        b2o.put(builtIn, op);
        o2b.put(op, builtIn);
      }
      builtInOpMap = b2o.build();
      opBuiltInMap = ImmutableMap.copyOf(o2b);
    }
  }

  /**
   * Resolved declaration. It can be converted to an expression given a result
   * expression; depending on sub-type, that expression will either be a {@code
   * let} (for a {@link net.hydromatic.morel.ast.Ast.ValDecl} or a {@code local}
   * (for a {@link net.hydromatic.morel.ast.Ast.DatatypeDecl}.
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
                    core.nonRecValDecl(x.pos, (Core.IdPat) x.pat, x.exp)));
        return core.let(core.recValDecl(valDecls), resultExp);
      }
      if (!composite && patExps.get(0).pat instanceof Core.IdPat) {
        final PatExp x = patExps.get(0);
        Core.NonRecValDecl valDecl =
            core.nonRecValDecl(x.pos, (Core.IdPat) x.pat, x.exp);
        return core.let(valDecl, resultExp);
      } else {
        // This is a complex pattern. Allocate an intermediate variable.
        final String name = nameGenerator.get();
        final Core.IdPat idPat = core.idPat(pat.type, name, nameGenerator::inc);
        final Core.Id id = core.id(idPat);
        final Pos pos = patExps.get(0).pos;
        return core.let(
            core.nonRecValDecl(pos, idPat, exp),
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
   * Visitor that converts a {@link Ast.From}, {@link Ast.Exists} or {@link
   * Ast.Forall} to {@link Core.From} by handling each subtype of {@link
   * Ast.FromStep} calling {@link FromBuilder} appropriately.
   */
  private class FromResolver extends Visitor {
    final FromBuilder fromBuilder = core.fromBuilder(typeMap.typeSystem, env);

    Core.Exp run(Ast.Query query) {
      if (query.isInto()) {
        // Translate "from ... into f" as if they had written "f (from ...)"
        final Core.Exp coreFrom = run(skipLast(query.steps));
        final Ast.Into into = (Ast.Into) last(query.steps);
        final Core.Exp exp = toCore(into.exp);
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
        return core.only(typeMap.typeSystem, query.pos, coreFrom);
      } else {
        return coreFrom;
      }
    }

    private Core.Exp run(List<Ast.FromStep> steps) {
      steps.forEach(this::accept);
      return fromBuilder.buildSimplify();
    }

    @Override
    protected void visit(Ast.From from) {
      // Do not traverse into the sub-"from".
    }

    @Override
    protected void visit(Ast.Scan scan) {
      final Resolver r = withEnv(fromBuilder.bindings());
      final Core.Exp coreExp;
      final Core.Pat corePat;
      if (scan.exp == null) {
        corePat = r.toCore(scan.pat);
        coreExp =
            core.extent(
                typeMap.typeSystem,
                corePat.type,
                ImmutableRangeSet.of(Range.all()));
      } else {
        coreExp = r.toCore(scan.exp);
        final ListType listType = (ListType) coreExp.type;
        corePat = r.toCore(scan.pat, listType.elementType);
      }
      final List<Binding> bindings2 = new ArrayList<>(fromBuilder.bindings());
      Compiles.acceptBinding(typeMap.typeSystem, corePat, bindings2);
      Core.Exp coreCondition =
          scan.condition == null
              ? core.boolLiteral(true)
              : r.withEnv(bindings2).toCore(scan.condition);
      fromBuilder.scan(corePat, coreExp, coreCondition);
    }

    @Override
    protected void visit(Ast.Where where) {
      final Resolver r = withEnv(fromBuilder.bindings());
      fromBuilder.where(r.toCore(where.exp));
    }

    @Override
    protected void visit(Ast.Require require) {
      // 'require e' translates to the same as 'where not e'
      final Resolver r = withEnv(fromBuilder.bindings());
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
    protected void visit(Ast.Yield yield) {
      final Resolver r = withEnv(fromBuilder.bindings());
      fromBuilder.yield_(r.toCore(yield.exp));
    }

    @Override
    protected void visit(Ast.Order order) {
      final Resolver r = withEnv(fromBuilder.bindings());
      fromBuilder.order(transformEager(order.orderItems, r::toCore));
    }

    @Override
    protected void visit(Ast.Through through) {
      // Translate "from ... through p in f"
      // as if they wrote "from p in f (from ...)"
      final Core.From from = fromBuilder.build();
      fromBuilder.clear();
      final Core.Exp exp = toCore(through.exp);
      final Core.Pat pat = toCore(through.pat);
      final Type type = typeMap.typeSystem.listType(pat.type);
      fromBuilder.scan(pat, core.apply(through.pos, type, exp, from));
    }

    @Override
    protected void visit(Ast.Compute compute) {
      visit((Ast.Group) compute);
    }

    @Override
    protected void visit(Ast.Group group) {
      final Resolver r = withEnv(fromBuilder.bindings());
      final ImmutableSortedMap.Builder<Core.IdPat, Core.Exp> groupExpsB =
          ImmutableSortedMap.naturalOrder();
      final ImmutableSortedMap.Builder<Core.IdPat, Core.Aggregate> aggregates =
          ImmutableSortedMap.naturalOrder();
      forEach(
          group.groupExps,
          (id, exp) -> groupExpsB.put(toCorePat(id), r.toCore(exp)));
      final SortedMap<Core.IdPat, Core.Exp> groupExps = groupExpsB.build();
      group.aggregates.forEach(
          aggregate ->
              aggregates.put(
                  toCorePat(aggregate.id),
                  r.toCore(aggregate, groupExps.keySet())));
      fromBuilder.group(groupExps, aggregates.build());
    }

    @Override
    protected void visit(Ast.Distinct distinct) {
      fromBuilder.distinct();
    }
  }
}

// End Resolver.java
