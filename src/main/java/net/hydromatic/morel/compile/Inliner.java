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

import java.util.List;

import static net.hydromatic.morel.ast.CoreBuilder.core;

/**
 * Shuttle that inlines constant values.
 */
public class Inliner extends EnvShuttle {
  /** Private constructor. */
  private Inliner(TypeSystem typeSystem, Environment env) {
    super(typeSystem, env);
  }

  /** Creates an Inliner. */
  public static Inliner of(TypeSystem typeSystem, Environment env) {
    return new Inliner(typeSystem, env);
  }

  @Override protected Inliner bind(List<Binding> bindingList) {
    // The "env2 != env" check is an optimization. If you remove it, this method
    // will have the same effect, just slower.
    final Environment env2 = env.bindAll(bindingList);
    if (env2 != env) {
      return new Inliner(typeSystem, env2);
    }
    return this;
  }

  @Override public Core.Exp visit(Core.Id id) {
    Binding binding = env.getOpt(id.name);
    if (binding != null
        && !binding.parameter
        && binding.value != Unit.INSTANCE) {
      Object v = binding.value;
      if (v instanceof Macro) {
        final Macro macro = (Macro) binding.value;
        final Core.Exp x =
            macro.expand(typeSystem, env, ((FnType) id.type).paramType);
        if (x instanceof Core.Literal) {
          return x;
        }
      }
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
    return super.visit(id);
  }

  @Override protected Core.Exp visit(Core.Apply apply) {
    final Core.Apply apply2 = (Core.Apply) super.visit(apply);
    if (apply2.fn.op == Op.RECORD_SELECTOR
        && apply2.arg.op == Op.VALUE_LITERAL) {
      final Core.RecordSelector selector = (Core.RecordSelector) apply2.fn;
      final List list = (List) ((Core.Literal) apply2.arg).unwrap();
      final Object o = list.get(selector.slot);
      return core.valueLiteral(apply2, o);
    }
    return apply2;
  }
}

// End Inliner.java
