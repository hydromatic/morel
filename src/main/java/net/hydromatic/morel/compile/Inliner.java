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
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.allMatch;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Closure;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.EvalEnvs;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;
import net.hydromatic.morel.type.TypeVisitor;
import net.hydromatic.morel.util.PairList;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Shuttle that inlines constant values. */
public class Inliner extends EnvShuttle {
  private final Analyzer.@Nullable Analysis analysis;

  /** Private constructor. */
  private Inliner(
      TypeSystem typeSystem, Environment env, Analyzer.Analysis analysis) {
    super(typeSystem, env);
    this.analysis = analysis;
  }

  /**
   * Creates an Inliner.
   *
   * <p>If {@code analysis} is null, no variables are inlined.
   */
  public static Inliner of(
      TypeSystem typeSystem,
      Environment env,
      Analyzer.@Nullable Analysis analysis) {
    return new Inliner(typeSystem, env, analysis);
  }

  @Override
  protected Inliner push(Environment env) {
    return new Inliner(typeSystem, env, analysis);
  }

  @Override
  protected Core.Exp visit(Core.Id id) {
    final Binding binding = env.getOpt(id.idPat);
    if (binding != null && !binding.parameter) {
      if (binding.exp != null) {
        // For bindings from previous compile units (identified by having both
        // exp and a non-Unit value), we can inline if:
        // 1. The expression is atomic (literals, IDs), or
        // 2. The expression is a non-recursive function
        // Recursive functions would cause infinite expansion.
        final boolean isEvalTimeBinding = binding.value != Unit.INSTANCE;
        if (isEvalTimeBinding) {
          // For cross-unit inlining, we can inline:
          // 1. Atomic expressions (literals, IDs)
          // 2. Non-recursive functions that:
          //    - don't contain nested recursive definitions
          //    - don't have free variables (other than the parameter)
          // Polymorphic functions are inlined with type unification.
          if (isAtomic(binding.exp)) {
            return binding.exp.accept(this);
          }
          if (binding.exp.op == Op.FN
              && !containsReference(binding.exp, binding.id)
              && !containsTypeVar(binding.exp.type)
              && !containsRecursiveDecl(binding.exp)
              && !hasFreeVariables((Core.Fn) binding.exp, env)) {
            return binding.exp.accept(this);
          }
        } else {
          final Analyzer.Use use =
              analysis == null
                  ? Analyzer.Use.MULTI_UNSAFE
                  : requireNonNull(analysis.map.get(id.idPat));
          switch (use) {
            case ATOMIC:
            case ONCE_SAFE:
              return binding.exp.accept(this);
          }
        }
      }
      Object v = binding.value;
      if (v instanceof Macro) {
        final Type paramType = ((FnType) id.type).paramType;
        if (!(paramType instanceof TypeVar)) {
          final Macro macro = (Macro) binding.value;
          final Core.Exp x = macro.expand(typeSystem, env, paramType);
          if (x instanceof Core.Literal) {
            return x;
          }
        }
      }
      if (v != Unit.INSTANCE) {
        // Trim "forall", so that "forall b. b list -> int" becomes
        // "a list -> int" and is clearly a function type.
        final Type type = typeSystem.unqualified(id.type);
        switch (type.op()) {
          case ID:
            assert id.type instanceof PrimitiveType;
            return core.literal((PrimitiveType) id.type, v);

          case FUNCTION_TYPE:
            assert v instanceof Applicable
                    || v instanceof Applicable1
                    || v instanceof Macro
                : v;
            final BuiltIn builtIn = Codes.BUILT_IN_MAP.get(v);
            if (builtIn != null) {
              return core.functionLiteral(id.type, builtIn);
            }
            // Applicable (including Closure) that does not map to a BuiltIn
            // is not considered 'constant', mainly because it creates messy
            // plans.
            break;

          default:
            if (v instanceof Code) {
              v = ((Code) v).eval(Compiler.EMPTY_ENV);
              if (v == null) {
                // Cannot inline SYS_FILE; it requires a session.
                break;
              }
            }
            return core.valueLiteral(id, v);
        }
      }
    }
    return super.visit(id);
  }

  @Override
  protected Core.Exp visit(Core.Apply apply) {
    final Core.Apply apply2 = (Core.Apply) super.visit(apply);
    if (apply2.fn.op == Op.RECORD_SELECTOR
        && apply2.arg.op == Op.VALUE_LITERAL) {
      final Core.RecordSelector selector = (Core.RecordSelector) apply2.fn;
      @SuppressWarnings("rawtypes")
      final List list = ((Core.Literal) apply2.arg).unwrap(List.class);
      final Object o = list.get(selector.slot);
      if (o instanceof Applicable || o instanceof Macro) {
        // E.g. apply is '#filter List', o is Codes.LIST_FILTER,
        // builtIn is BuiltIn.LIST_FILTER.
        final BuiltIn builtIn = Codes.BUILT_IN_MAP.get(o);
        if (builtIn != null) {
          return core.functionLiteral(apply2.type, builtIn);
        }
      }
      return core.valueLiteral(apply2, o);
    }
    if (apply2.fn.op == Op.FN) {
      // Beta-reduction:
      //   (fn x => E) A
      // becomes
      //   let x = A in E end
      final Core.Fn fn = (Core.Fn) apply2.fn;
      return core.let(
          core.nonRecValDecl(apply2.pos, fn.idPat, null, apply2.arg), fn.exp);
    }
    return apply2;
  }

  @Override
  protected Core.Exp visit(Core.Case caseOf) {
    final Core.Exp exp = caseOf.exp.accept(this);
    final List<Core.Match> matchList = visitList(caseOf.matchList);
    if (matchList.size() == 1) {
      // This is a singleton "case". Inline if possible. For example,
      //   fn x => case x of y => y + 1
      // becomes
      //   fn x => x + 1
      final Core.Match match = matchList.get(0);
      final Map<Core.NamedPat, Core.Exp> substitution = getSub(exp, match);
      if (substitution != null) {
        return Replacer.substitute(typeSystem, env, substitution, match.exp);
      }
    }

    // If exp is a literal (simple or nullary constructor), find the matching
    // branch and return it directly. For example,
    //   case 2 of 1 => "one" | 2 => "two" | _ => "large"
    // becomes
    //   "two"
    // Only do this when analysis is available (full inlining mode),
    // and when there is more than one branch.
    if (analysis != null && matchList.size() > 1) {
      final @Nullable Object value = expToValue(exp);
      if (value != null) {
        for (Core.Match match : matchList) {
          final PairList<Core.NamedPat, Object> binds = PairList.of();
          if (Closure.bindRecurse(match.pat, value, binds::add)) {
            final AtomicReference<Core.Exp> r =
                new AtomicReference<>(match.exp);
            binds.forEach(
                (pat, v) -> {
                  // Pattern like "x => x + 1" where x binds to the literal.
                  // Convert to: let x = <literal> in <match.exp>
                  final Core.Exp e = valueToExp(typeSystem, pat.type, v);
                  r.set(
                      core.let(
                          core.nonRecValDecl(caseOf.pos, pat, null, e),
                          r.get()));
                });
            return r.get();
          }
          // Unknown pattern type; try next pattern
        }
      }
    }

    if (exp.type != caseOf.exp.type) {
      // Type has become less general. For example,
      //   case x of NONE => [] | SOME y => [y]
      // has type 'alpha list' but when we substitute 'SOME 1' for x, it becomes
      //   case SOME 1 of NONE => [] | SOME y => [y]
      // with type 'int list'
      @Nullable Map<Integer, Type> sub = caseOf.exp.type.unifyWith(exp.type);
      if (sub == null) {
        throw new AssertionError(
            format("cannot unify %s with %s", exp.type, caseOf.exp.type));
      }
      final Type type =
          caseOf.type.substitute(
              typeSystem, ImmutableList.copyOf(sub.values()));
      return core.caseOf(caseOf.pos, type, exp, matchList);
    }
    return caseOf.copy(exp, matchList);
  }

  private @Nullable Map<Core.NamedPat, Core.Exp> getSub(
      Core.Exp exp, Core.Match match) {
    if (match.pat.op == Op.ID_PAT && isAtomic(exp)) {
      return ImmutableMap.of((Core.IdPat) match.pat, exp);
    }
    if (exp.op == Op.TUPLE && match.pat.op == Op.TUPLE_PAT) {
      final Core.Tuple tuple = (Core.Tuple) exp;
      final Core.TuplePat tuplePat = (Core.TuplePat) match.pat;
      if (allMatch(tuple.args, Inliner::isAtomic)
          && allMatch(tuplePat.args, arg -> arg.op == Op.ID_PAT)) {
        final ImmutableMap.Builder<Core.NamedPat, Core.Exp> builder =
            ImmutableMap.builder();
        forEach(
            tuple.args,
            tuplePat.args,
            (arg, pat) -> builder.put((Core.IdPat) pat, arg));
        return builder.build();
      }
    }
    return null;
  }

  /** Returns whether an expression can be inlined without expansion. */
  static boolean isAtomic(Core.Exp exp) {
    return exp instanceof Core.Literal || exp instanceof Core.Id;
  }

  /**
   * Returns whether an expression contains a reference to the given pattern.
   * Used to detect recursive functions that should not be inlined.
   */
  private static boolean containsReference(Core.Exp exp, Core.NamedPat pat) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Id id) {
            if (id.idPat.equals(pat)) {
              found[0] = true;
            }
          }
        });
    return found[0];
  }

  /**
   * Returns whether a type contains type variables. Used to detect polymorphic
   * functions that require type unification.
   */
  private static boolean containsTypeVar(Type type) {
    final boolean[] found = {false};
    type.accept(
        new TypeVisitor<Void>() {
          @Override
          public Void visit(TypeVar typeVar) {
            found[0] = true;
            return null;
          }
        });
    return found[0];
  }

  /**
   * Returns whether an expression contains a recursive declaration. Used to
   * detect functions with nested recursive definitions that should not be
   * inlined (because inlining would cause issues with unbound references).
   */
  private static boolean containsRecursiveDecl(Core.Exp exp) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.RecValDecl recValDecl) {
            found[0] = true;
          }
        });
    return found[0];
  }

  /**
   * Returns whether a function has free variables (references to identifiers
   * other than its parameter that are not available in the given environment).
   * Used to detect functions that capture variables from their definition
   * environment, which cannot be safely inlined across compile units.
   */
  private static boolean hasFreeVariables(Core.Fn fn, Environment env) {
    final boolean[] found = {false};
    fn.exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Id id) {
            // Skip references to the function's own parameter
            if (id.idPat.equals(fn.idPat)) {
              return;
            }
            // Check if this reference is available in the inlining environment
            final Binding binding = env.getOpt(id.idPat);
            if (binding == null) {
              // This is a free variable not available in the current env
              found[0] = true;
            }
          }
        });
    return found[0];
  }

  /**
   * Returns the runtime value of a constant expression, or null if it is not
   * constant. Examples of constant expressions include literals {@code 1},
   * {@code "xyz"}, {@code true} and datatype constructors {@code NONE}, {@code
   * SOME 1}.
   *
   * @see #valueToExp(TypeSystem, Type, Object)
   */
  private static @Nullable Object expToValue(Core.Exp exp) {
    switch (exp.op) {
      case BOOL_LITERAL:
      case CHAR_LITERAL:
      case STRING_LITERAL:
      case UNIT_LITERAL:
        return ((Core.Literal) exp).value;
      case REAL_LITERAL:
        return ((Core.Literal) exp).unwrap(Double.class);
      case INT_LITERAL:
        return ((Core.Literal) exp).unwrap(Integer.class);
      case VALUE_LITERAL:
        return ((Core.Literal) exp).unwrap(List.class);
      case TUPLE:
        final Core.Tuple tuple = (Core.Tuple) exp;
        final ImmutableList.Builder<Object> args = ImmutableList.builder();
        for (Core.Exp arg : tuple.args) {
          final Object value = expToValue(arg);
          if (value == null) {
            return null;
          }
          args.add(value);
        }
        return args.build();

      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        if (apply.fn instanceof Core.Id && apply.type instanceof DataType) {
          final String conName = ((Core.Id) apply.fn).idPat.name;
          final DataType dataType = (DataType) apply.type;
          // Skip variant types because they require a session to construct
          if ("variant".equals(dataType.name)) {
            return null;
          }
          if (dataType.typeConstructors.containsKey(conName)) {
            final Applicable tyCon = Codes.tyCon(apply.type, conName);
            final Object arg = expToValue(apply.arg);
            if (arg == null) {
              return null;
            }
            return tyCon.apply(EvalEnvs.empty(), arg);
          }
        }

        // fall through
      default:
        return null;
    }
  }

  /** Converts a runtime value to constant expression (usually a literal). */
  @SuppressWarnings("unchecked")
  private static Core.Exp valueToExp(
      TypeSystem typeSystem, Type type, Object value) {
    final List<Object> list;
    switch (type.op()) {
      case ID:
        return core.literal((PrimitiveType) type, value);

      case DATA_TYPE:
        list = (List<Object>) value;
        String name = (String) list.get(0);
        final Core.IdPat idPat = core.idPat(type, name, 0);
        Core.Id id = core.id(idPat);
        if (list.size() == 1) {
          return core.valueLiteral(id, list.get(0));
        }
        Type argType = ((DataType) type).typeConstructors(typeSystem).get(name);
        Core.Exp arg = valueToExp(typeSystem, argType, list.get(1));
        return core.apply(Pos.ZERO, type, id, arg);

      case TUPLE_TYPE:
        final TupleType tupleType = (TupleType) type;
        list = (List<Object>) value;
        final ImmutableList.Builder<Core.Exp> args = ImmutableList.builder();
        forEach(
            tupleType.argTypes,
            list,
            (t, v) -> args.add(valueToExp(typeSystem, t, v)));
        return core.tuple(tupleType, args.build());

      default:
        throw new AssertionError(
            format(
                "cannot convert value [%s] of type [%s] to expression",
                value, type));
    }
  }

  @Override
  protected Core.Exp visit(Core.Let let) {
    final Analyzer.Use use =
        analysis == null
            ? Analyzer.Use.MULTI_UNSAFE
            : let.decl instanceof Core.NonRecValDecl
                ? requireNonNull(
                    analysis.map.get(((Core.NonRecValDecl) let.decl).pat))
                : Analyzer.Use.MULTI_UNSAFE;
    switch (use) {
      case DEAD:
        // This declaration has no uses; remove it
        return let.exp.accept(this);

      case ATOMIC:
      case ONCE_SAFE:
        // This declaration has one use; remove the declaration, and replace its
        // use inside the expression.
        final List<Binding> bindings = new ArrayList<>();
        Compiles.bindPattern(typeSystem, bindings, let.decl);
        return let.exp.accept(bind(bindings));
    }
    return super.visit(let);
  }
}

// End Inliner.java
