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

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.TypeSystem;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static net.hydromatic.morel.ast.AstBuilder.ast;

/** Helpers for {@link Compiler} and {@link TypeResolver}. */
public abstract class Compiles {
  /** Validates an expression or declaration, deducing its type and perhaps
   * rewriting the expression to a form that can more easily be compiled.
   *
   * <p>Used for testing. */
  public static TypeResolver.Resolved validateExpression(AstNode statement,
      Map<Prop, Object> propMap, Map<String, ForeignValue> valueMap) {
    final TypeSystem typeSystem = new TypeSystem();
    final Session session = new Session(propMap);
    final Environment env = Environments.env(typeSystem, session, valueMap);
    return TypeResolver.deduceType(env, toDecl(statement), typeSystem);
  }

  /**
   * Validates and compiles a statement (expression or declaration), and
   * compiles it to code that can be evaluated by the interpreter.
   */
  public static CompiledStatement prepareStatement(TypeSystem typeSystem,
      Session session, Environment env, AstNode statement,
      @Nullable Calcite calcite, Consumer<CompileException> warningConsumer,
      Tracer tracer) {
    Ast.Decl decl;
    if (statement instanceof Ast.Exp) {
      decl = toValDecl((Ast.Exp) statement);
    } else {
      decl = (Ast.Decl) statement;
    }
    return prepareDecl(typeSystem, session, env, calcite, decl,
        warningConsumer, tracer);
  }

  /**
   * Validates and compiles a declaration, and compiles it to
   * code that can be evaluated by the interpreter.
   */
  private static CompiledStatement prepareDecl(TypeSystem typeSystem,
      Session session, Environment env, @Nullable Calcite calcite,
      Ast.Decl decl,
      Consumer<CompileException> warningConsumer, Tracer tracer) {
    final TypeResolver.Resolved resolved =
        TypeResolver.deduceType(env, decl, typeSystem);
    final boolean hybrid = Prop.HYBRID.booleanValue(session.map);
    final int inlinePassCount =
        Math.max(Prop.INLINE_PASS_COUNT.intValue(session.map), 0);
    final boolean relationalize =
        Prop.RELATIONALIZE.booleanValue(session.map);

    final Resolver resolver = Resolver.of(resolved.typeMap, env, session);
    final Core.Decl coreDecl0 = resolver.toCore(resolved.node);
    tracer.onCore(0, coreDecl0);

    // Should we skip printing the root pattern?
    // Yes, if they wrote 'val x = 1 and y = 2' and
    // core became 'val it as (x, y) = (1, 2)'.
    // No, if they actually wrote 'val (x, y) = (1, 2)'.
    final Core.NamedPat skipPat = getSkipPat(resolved.node, coreDecl0);

    // Check for exhaustive and redundant patterns, and throw errors or
    // warnings.
    final boolean matchCoverageEnabled =
        Prop.MATCH_COVERAGE_ENABLED.booleanValue(session.map);
    if (matchCoverageEnabled) {
      checkPatternCoverage(typeSystem, coreDecl0, warningConsumer);
    }

    // Ensures that once we discover that there are no unbounded variables,
    // we stop looking; makes things a bit more efficient.
    boolean mayContainUnbounded = true;

    Core.Decl coreDecl;
    tracer.onCore(1, coreDecl0);
    if (inlinePassCount == 0) {
      // Inlining is disabled. Use the Inliner in a limited mode.
      final Inliner inliner = Inliner.of(typeSystem, env, null);
      coreDecl = coreDecl0.accept(inliner);
    } else {
      final @Nullable Relationalizer relationalizer =
          relationalize ? Relationalizer.of(typeSystem, env)
              : null;

      // Inline few times, or until we reach fixed point, whichever is sooner.
      coreDecl = coreDecl0;
      for (int i = 0; i < inlinePassCount; i++) {
        final Analyzer.Analysis analysis =
            Analyzer.analyze(typeSystem, env, coreDecl);
        final Inliner inliner = Inliner.of(typeSystem, env, analysis);
        final Core.Decl coreDecl2 = coreDecl;
        coreDecl = coreDecl2.accept(inliner);
        if (relationalizer != null) {
          coreDecl = coreDecl.accept(relationalizer);
        }
        if (coreDecl == coreDecl2) {
          break;
        }
        tracer.onCore(i + 2, coreDecl);
      }
      for (int i = 0; i < inlinePassCount; i++) {
        final Core.Decl coreDecl2 = coreDecl;
        if (mayContainUnbounded) {
          if (SuchThatShuttle.containsUnbounded(coreDecl)) {
            coreDecl = coreDecl.accept(new SuchThatShuttle(typeSystem, env));
          } else {
            mayContainUnbounded = false;
          }
        }
        coreDecl = Extents.infinitePats(typeSystem, coreDecl);
        if (coreDecl == coreDecl2) {
          break;
        }
        tracer.onCore(i + 2, coreDecl);
      }
    }
    tracer.onCore(-1, coreDecl);
    final Compiler compiler;
    if (hybrid) {
      if (calcite == null) {
        calcite = Calcite.withDataSets(ImmutableMap.of());
      }
      compiler = new CalciteCompiler(typeSystem, calcite);
    } else {
      compiler = new Compiler(typeSystem);
    }

    // If the user wrote "scott.depts" we will print "<relation>";
    // but if the user wrote "from d in scott.depts", they would like to see
    // the full contents. Those two expressions may have been simplified to the
    // same Core.Exp, but in the latter case we will 'wrap' the RelList value
    // as a regular List so that it is printed in full.
    final ImmutableSet.Builder<Core.Exp> queriesToWrap = ImmutableSet.builder();
    if (resolved.originalNode instanceof Ast.ValDecl
        && coreDecl instanceof Core.NonRecValDecl) {
      final Ast.ValDecl valDecl = (Ast.ValDecl) resolved.originalNode;
      final Ast.ValBind valBind = valDecl.valBinds.get(0);
      final Core.NonRecValDecl nonRecValDecl = (Core.NonRecValDecl) coreDecl;
      if (valBind.exp.op == Op.FROM) {
        queriesToWrap.add(nonRecValDecl.exp);
      }
    }

    return compiler.compileStatement(env, coreDecl, skipPat,
        queriesToWrap.build());
  }

  /** Returns a pattern that should not be printed, or null.
   *
   * <p>Consider the two declarations:
   *
   * <blockquote><pre>{@code
   *   val it as (x, y) = (5, 6);
   *   val (x, y) = (5, 6);
   * }</pre></blockquote>
   *
   * <p>{@code coreDecl} is the same for both. For the first, we should print
   *
   * <blockquote><pre>{@code
   *   val it = (5,6) : int * int
   *   val x = 5 : int
   *   val x = 6 : int
   * }</pre></blockquote>
   *
   * <p>but for the second we should skip {@code it}, as follows:
   *
   * <blockquote><pre>{@code
   *   val x = 5 : int
   *   val x = 6 : int
   * }</pre></blockquote>
   */
  private static Core.@Nullable NamedPat getSkipPat(Ast.Decl decl,
      Core.Decl coreDecl) {
    if (coreDecl instanceof Core.NonRecValDecl
        && decl instanceof Ast.ValDecl) {
      final Core.NonRecValDecl nonRecValDecl = (Core.NonRecValDecl) coreDecl;
      final Ast.ValDecl valDecl = (Ast.ValDecl) decl;
      if (nonRecValDecl.pat.name.equals("it")) {
        if (valDecl.valBinds.size() == 1) {
          final Ast.Pat pat = valDecl.valBinds.get(0).pat;
          if (pat instanceof Ast.AsPat
              && ((Ast.AsPat) pat).id.name.equals("it")) {
            return null;
          }
          if (pat instanceof Ast.IdPat
              && ((Ast.IdPat) pat).name.equals("it")) {
            return null;
          }
        }
        return nonRecValDecl.pat;
      }
    }
    return null;
  }

  /** Checks for exhaustive and redundant patterns, and throws if there are
   * errors/warnings. */
  private static void checkPatternCoverage(TypeSystem typeSystem,
      Core.Decl decl, final Consumer<CompileException> warningConsumer) {
    final List<CompileException> errorList = new ArrayList<>();
    decl.accept(new Visitor() {
      @Override protected void visit(Core.Case kase) {
        super.visit(kase);
        checkPatternCoverage(typeSystem, kase, errorList::add,
            warningConsumer);
      }
    });
    if (!errorList.isEmpty()) {
      throw errorList.get(0);
    }
  }

  private static void checkPatternCoverage(TypeSystem typeSystem,
      Core.Case kase, Consumer<CompileException> errorConsumer,
      Consumer<CompileException> warningConsumer) {
    final List<Core.Pat> prevPatList = new ArrayList<>();
    final List<Core.Match> redundantMatchList = new ArrayList<>();
    for (Core.Match match : kase.matchList) {
      if (PatternCoverageChecker.isCoveredBy(typeSystem, prevPatList,
          match.pat)) {
        redundantMatchList.add(match);
      }
      prevPatList.add(match.pat);
    }
    final boolean exhaustive =
        PatternCoverageChecker.isExhaustive(typeSystem, prevPatList);
    if (!redundantMatchList.isEmpty()) {
      final String message = exhaustive
          ? "match redundant"
          : "match redundant and nonexhaustive";
      errorConsumer.accept(
          new CompileException(message, false,
              redundantMatchList.get(0).pos));
    } else if (!exhaustive) {
      warningConsumer.accept(
          new CompileException("match nonexhaustive", true, kase.pos));
    }
  }

  /** Converts {@code e} to {@code val = e}. */
  public static Ast.ValDecl toValDecl(Ast.Exp statement) {
    final Pos pos = statement.pos;
    return ast.valDecl(pos, false,
        ImmutableList.of(ast.valBind(pos, ast.idPat(pos, "it"), statement)));
  }

  /** Converts an expression or value declaration to a value declaration. */
  public static Ast.ValDecl toValDecl(AstNode statement) {
    return statement instanceof Ast.ValDecl ? (Ast.ValDecl) statement
        : toValDecl((Ast.Exp) statement);
  }

  /** Converts an expression or declaration to a declaration. */
  public static Ast.Decl toDecl(AstNode statement) {
    return statement instanceof Ast.Decl ? (Ast.Decl) statement
        : toValDecl((Ast.Exp) statement);
  }

  /** Converts {@code val = e} to {@code e};
   * the converse of {@link #toValDecl(Ast.Exp)}. */
  public static Core.Exp toExp(Core.NonRecValDecl decl) {
    return decl.exp;
  }

  static void bindPattern(TypeSystem typeSystem, List<Binding> bindings,
      Core.DatatypeDecl datatypeDecl) {
    datatypeDecl.accept(binding(typeSystem, bindings));
  }

  /** Richer than {@link #bindPattern(TypeSystem, List, Core.Pat)} because
   * we have the expression. */
  static void bindPattern(TypeSystem typeSystem, List<Binding> bindings,
      Core.ValDecl valDecl) {
    valDecl.forEachBinding((pat, exp, pos) -> {
      if (pat instanceof Core.IdPat) {
        bindings.add(Binding.of(pat, exp));
      }
    });
  }

  static void bindPattern(TypeSystem typeSystem, List<Binding> bindings,
      Core.Pat pat) {
    pat.accept(binding(typeSystem, bindings));
  }

  static void bindPattern(TypeSystem typeSystem, List<Binding> bindings,
      Core.NamedPat namedPat) {
    bindings.add(Binding.of(namedPat));
  }

  public static void bindDataType(TypeSystem typeSystem, List<Binding> bindings,
      DataType dataType) {
    dataType.typeConstructors.keySet().forEach(name ->
        bindings.add(typeSystem.bindTyCon(dataType, name)));
  }

  static PatternBinder binding(TypeSystem typeSystem, List<Binding> bindings) {
    return new PatternBinder(typeSystem, bindings);
  }

  /** Visits a pattern, adding bindings to a list.
   *
   * <p>If the pattern is an {@link net.hydromatic.morel.ast.Core.IdPat},
   * don't use this method: just bind directly. */
  public static void acceptBinding(TypeSystem typeSystem, Core.Pat pat,
      List<Binding> bindings) {
    pat.accept(binding(typeSystem, bindings));
  }

  /** Visitor that adds a {@link Binding} each time it see an
   * {@link Core.IdPat} or {@link Core.AsPat}. */
  private static class PatternBinder extends Visitor {
    private final TypeSystem typeSystem;
    private final List<Binding> bindings;

    PatternBinder(TypeSystem typeSystem, List<Binding> bindings) {
      this.typeSystem = typeSystem;
      this.bindings = bindings;
    }

    @Override protected void visit(Core.IdPat idPat) {
      bindPattern(typeSystem, bindings, idPat);
    }

    @Override protected void visit(Core.AsPat asPat) {
      bindPattern(typeSystem, bindings, asPat);
      super.visit(asPat);
    }

    @Override protected void visit(Core.NonRecValDecl valBind) {
      // The super method visits valBind.e; we do not
      valBind.pat.accept(this);
    }

    @Override protected void visit(Core.DatatypeDecl datatypeDecl) {
      datatypeDecl.dataTypes.forEach(dataType ->
          bindDataType(typeSystem, bindings, dataType));
    }

    @Override protected void visit(Core.Local local) {
      bindDataType(typeSystem, bindings, local.dataType);
    }
  }

}

// End Compiles.java
