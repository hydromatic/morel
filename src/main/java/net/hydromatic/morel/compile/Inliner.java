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

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

import static net.hydromatic.morel.ast.CoreBuilder.core;

/**
 * Shuttle that inlines constant values.
 */
public class Inliner extends EnvShuttle {
  private final @Nullable Analyzer.Analysis analysis;

  /** Private constructor. */
  private Inliner(TypeSystem typeSystem, Environment env,
      Analyzer.Analysis analysis) {
    super(typeSystem, env);
    this.analysis = analysis;
  }

  /** Creates an Inliner.
   *
   * <p>If {@code analysis} is null, no variables are inlined. */
  public static Inliner of(TypeSystem typeSystem, Environment env,
      @Nullable Analyzer.Analysis analysis) {
    return new Inliner(typeSystem, env, analysis);
  }

  @Override protected EnvShuttle bind(Binding binding) {
    return new Inliner(typeSystem, env.bind(binding), analysis);
  }

  @Override protected Inliner bind(List<Binding> bindingList) {
    // The "env2 != env" check is an optimization. If you remove it, this method
    // will have the same effect, just slower.
    final Environment env2 = env.bindAll(bindingList);
    if (env2 != env) {
      return new Inliner(typeSystem, env2, analysis);
    }
    return this;
  }

  @Override public Core.Exp visit(Core.Id id) {
    final Binding binding = env.getOpt(id.idPat);
    if (binding != null
        && !binding.parameter) {
      if (binding.exp != null) {
        final Analyzer.Use use =
            analysis == null ? Analyzer.Use.MULTI_UNSAFE
                : analysis.map.get(id.idPat);
        switch (use) {
        case ATOMIC:
        case ONCE_SAFE:
          return binding.exp.accept(this);
        }
      }
      Object v = binding.value;
      if (v instanceof Macro) {
        final Macro macro = (Macro) binding.value;
        final Core.Exp x =
            macro.expand(typeSystem, env, ((FnType) id.type).paramType);
        if (x instanceof Core.Literal) {
          return x;
        }
      }
      if (v != Unit.INSTANCE) {
        switch (id.type.op()) {
        case ID:
          assert id.type instanceof PrimitiveType;
          return core.literal((PrimitiveType) id.type, v);

        case FUNCTION_TYPE:
          assert v instanceof Applicable || v instanceof Macro : v;
          final BuiltIn builtIn = Codes.BUILT_IN_MAP.get(v);
          if (builtIn != null) {
            return core.functionLiteral(typeSystem, builtIn);
          }
          // Applicable (including Closure) that does not map to a BuiltIn
          // is not considered 'constant', mainly because it creates messy plans
          break;

        default:
          if (v instanceof Code) {
            v = ((Code) v).eval(Compiler.EMPTY_ENV);
          }
          return core.valueLiteral(id, v);
        }
      }
    }
    return super.visit(id);
  }

  @Override protected Core.Exp visit(Core.Apply apply) {
    final Core.Apply apply2 = (Core.Apply) super.visit(apply);
    if (apply2.fn.op == Op.RECORD_SELECTOR
        && apply2.arg.op == Op.VALUE_LITERAL) {
      final Core.RecordSelector selector = (Core.RecordSelector) apply2.fn;
      final List list = (List) ((Core.Literal) apply2.arg).unwrap();
      final Object o = list.get(selector.slot);
      if (o instanceof Applicable || o instanceof Macro) {
        // E.g. apply is '#filter List', o is Codes.LIST_FILTER,
        // builtIn is BuiltIn.LIST_FILTER.
        final BuiltIn builtIn = Codes.BUILT_IN_MAP.get(o);
        if (builtIn != null) {
          return core.functionLiteral(typeSystem, builtIn);
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
          core.nonRecValDecl(fn.idPat, apply2.arg, apply2.pos), fn.exp);
    }
    return apply2;
  }

  @Override public Core.Exp visit(Core.Let let) {
    final Analyzer.Use use =
        analysis == null
            ? Analyzer.Use.MULTI_UNSAFE
            : let.decl instanceof Core.NonRecValDecl
                ? analysis.map.get(((Core.NonRecValDecl) let.decl).pat)
                : Analyzer.Use.MULTI_UNSAFE;
    switch (use) {
    case DEAD:
      // This declaration has no uses; remove it
      return let.exp;

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
