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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

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
import net.hydromatic.morel.type.TypeSystem;

import java.util.List;
import java.util.Map;

import static net.hydromatic.morel.ast.AstBuilder.ast;

/** Helpers for {@link Compiler} and {@link TypeResolver}. */
public abstract class Compiles {
  /** Validates an expression, deducing its type and perhaps rewriting the
   * expression to a form that can more easily be compiled.
   *
   * <p>Used for testing. */
  public static TypeResolver.Resolved validateExpression(Ast.Exp exp,
      Map<String, ForeignValue> valueMap) {
    final TypeSystem typeSystem = new TypeSystem();
    final Environment env = Environments.env(typeSystem, valueMap);
    return TypeResolver.deduceType(env, toValDecl(exp), typeSystem);
  }

  /**
   * Validates and compiles a statement (expression or declaration), and
   * compiles it to code that can be evaluated by the interpreter.
   */
  public static CompiledStatement prepareStatement(TypeSystem typeSystem,
      Session session, Environment env, AstNode statement) {
    Ast.Decl decl;
    if (statement instanceof Ast.Exp) {
      decl = toValDecl((Ast.Exp) statement);
    } else {
      decl = (Ast.Decl) statement;
    }
    return prepareDecl(typeSystem, session, env, decl);
  }

  /**
   * Validates and compiles a declaration, and compiles it to
   * code that can be evaluated by the interpreter.
   */
  private static CompiledStatement prepareDecl(TypeSystem typeSystem,
      Session session, Environment env, Ast.Decl decl) {
    final TypeResolver.Resolved resolved =
        TypeResolver.deduceType(env, decl, typeSystem);
    final boolean hybrid = Prop.HYBRID.booleanValue(session.map);
    final Resolver resolver = new Resolver(resolved.typeMap);
    final Core.Decl coreDecl = resolver.toCore(resolved.node);
    final Inliner inliner = Inliner.of(typeSystem, env);
    final Core.Decl coreDecl2 = coreDecl.accept(inliner);
    final Compiler compiler;
    if (hybrid) {
      final Calcite calcite = Calcite.withDataSets(ImmutableMap.of());
      compiler = new CalciteCompiler(typeSystem, calcite);
    } else {
      compiler = new Compiler(typeSystem);
    }
    return compiler.compileStatement(env, coreDecl2);
  }

  /** Converts {@code e} to {@code val = e}. */
  public static Ast.ValDecl toValDecl(Ast.Exp statement) {
    final Pos pos = statement.pos;
    return ast.valDecl(pos,
        ImmutableList.of(
            ast.valBind(pos, false, ast.idPat(pos, "it"), statement)));
  }

  /** Converts {@code val = e} to {@code e};
   * the converse of {@link #toValDecl(Ast.Exp)}. */
  public static Core.Exp toExp(Core.ValDecl decl) {
    return decl.e;
  }

  static PatternBinder binding(TypeSystem typeSystem, List<Binding> bindings) {
    return new PatternBinder(typeSystem, bindings);
  }

  /** Visitor that adds a {@link Binding} each time it see an
   * {@link Core.IdPat}. */
  private static class PatternBinder extends Visitor {
    private final TypeSystem typeSystem;
    private final List<Binding> bindings;

    PatternBinder(TypeSystem typeSystem, List<Binding> bindings) {
      this.typeSystem = typeSystem;
      this.bindings = bindings;
    }

    @Override public void visit(Core.IdPat idPat) {
      bindings.add(Binding.of(idPat.name, idPat.type));
    }

    @Override protected void visit(Core.ValDecl valBind) {
      // The super method visits valBind.e; we do not
      valBind.pat.accept(this);
    }

    @Override protected void visit(Core.DatatypeDecl datatypeDecl) {
      datatypeDecl.dataTypes.forEach(dataType ->
          dataType.typeConstructors.keySet().forEach(name ->
              bindings.add(typeSystem.bindTyCon(dataType, name))));
    }
  }
}

// End Compiles.java
