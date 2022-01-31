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

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

import static net.hydromatic.morel.ast.AstBuilder.ast;

/** Helpers for {@link Compiler} and {@link TypeResolver}. */
public abstract class Compiles {
  /** Validates an expression or declaration, deducing its type and perhaps
   * rewriting the expression to a form that can more easily be compiled.
   *
   * <p>Used for testing. */
  public static TypeResolver.Resolved validateExpression(AstNode statement,
      Map<String, ForeignValue> valueMap) {
    final TypeSystem typeSystem = new TypeSystem();
    final Environment env = Environments.env(typeSystem, valueMap);
    return TypeResolver.deduceType(env, toDecl(statement), typeSystem);
  }

  /**
   * Validates and compiles a statement (expression or declaration), and
   * compiles it to code that can be evaluated by the interpreter.
   */
  public static CompiledStatement prepareStatement(TypeSystem typeSystem,
      Session session, Environment env, AstNode statement,
      @Nullable Calcite calcite) {
    Ast.Decl decl;
    if (statement instanceof Ast.Exp) {
      decl = toValDecl((Ast.Exp) statement);
    } else {
      decl = (Ast.Decl) statement;
    }
    return prepareDecl(typeSystem, session, env, calcite, decl,
        decl == statement);
  }

  /**
   * Validates and compiles a declaration, and compiles it to
   * code that can be evaluated by the interpreter.
   */
  private static CompiledStatement prepareDecl(TypeSystem typeSystem,
      Session session, Environment env, @Nullable Calcite calcite,
      Ast.Decl decl, boolean isDecl) {
    final TypeResolver.Resolved resolved =
        TypeResolver.deduceType(env, decl, typeSystem);
    final boolean hybrid = Prop.HYBRID.booleanValue(session.map);
    final int inlinePassCount =
        Math.max(Prop.INLINE_PASS_COUNT.intValue(session.map), 0);
    final Resolver resolver = Resolver.of(resolved.typeMap, env);
    final Core.Decl coreDecl0 = resolver.toCore(resolved.node);

    Core.Decl coreDecl;
    if (inlinePassCount == 0) {
      // Inlining is disabled. Use the Inliner in a limited mode.
      final Inliner inliner = Inliner.of(typeSystem, env, null);
      coreDecl = coreDecl0.accept(inliner);
    } else {
      // Inline few times, or until we reach fixed point, whichever is sooner.
      coreDecl = coreDecl0;
      for (int i = 0; i < inlinePassCount; i++) {
        final Analyzer.Analysis analysis =
            Analyzer.analyze(typeSystem, env, coreDecl);
        final Inliner inliner = Inliner.of(typeSystem, env, analysis);
        final Core.Decl coreDecl1 = coreDecl;
        coreDecl = coreDecl1.accept(inliner);
        if (coreDecl == coreDecl1) {
          break;
        }
      }
    }
    final Compiler compiler;
    if (hybrid) {
      if (calcite == null) {
        calcite = Calcite.withDataSets(ImmutableMap.of());
      }
      compiler = new CalciteCompiler(typeSystem, calcite);
    } else {
      compiler = new Compiler(typeSystem);
    }
    return compiler.compileStatement(env, coreDecl, isDecl);
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
    valDecl.forEachBinding((pat, exp) -> {
      if (pat instanceof Core.IdPat) {
        bindings.add(Binding.of((Core.IdPat) pat, exp));
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

    @Override public void visit(Core.IdPat idPat) {
      bindPattern(typeSystem, bindings, idPat);
    }

    @Override public void visit(Core.AsPat asPat) {
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
