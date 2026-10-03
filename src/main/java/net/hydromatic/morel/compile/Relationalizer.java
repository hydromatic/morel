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
import static net.hydromatic.morel.ast.CoreBuilder.core;

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Converts calls to higher-order functions such as {@link BuiltIn#LIST_MAP}
 * into relational trees.
 */
public class Relationalizer extends EnvShuttle {
  /** Private constructor. */
  private Relationalizer(TypeSystem typeSystem, Environment env) {
    super(typeSystem, env);
  }

  /** Creates a Relationalizer. */
  public static Relationalizer of(TypeSystem typeSystem, Environment env) {
    return new Relationalizer(typeSystem, env);
  }

  @Override
  protected Relationalizer push(Environment env) {
    return new Relationalizer(typeSystem, env);
  }

  @Override
  protected Core.Exp visit(Core.Apply apply) {
    switch (apply.fn.op) {
      case APPLY:
        final Core.Apply apply2 = (Core.Apply) apply.fn;
        switch (apply2.fn.op) {
          case FN_LITERAL:
            final Core.Literal literal = (Core.Literal) apply2.fn;
            if (literal.value == BuiltIn.LIST_MAP
                || literal.value == BuiltIn.BAG_MAP) {
              // List.map f list
              //  =>
              // project [f $0] list
              final Core.Exp f = apply2.arg;
              final FnType fnType = (FnType) f.type;
              final Core.Exp list = collection(apply.arg);
              final Core.IdPat id = elementPat(list);
              return core.project(
                  apply.pos(),
                  typeSystem,
                  id,
                  null,
                  list,
                  core.apply(apply.pos(), fnType.resultType, f, core.id(id)));
            }
            if (literal.value == BuiltIn.LIST_FILTER
                || literal.value == BuiltIn.BAG_FILTER) {
              // List.filter f list
              //  =>
              // filter [f $0] list
              final Core.Exp f = apply2.arg;
              final FnType fnType = (FnType) f.type;
              final Core.Exp list = collection(apply.arg);
              final Core.IdPat id = elementPat(list);
              return core.filter(
                  apply.pos(),
                  id,
                  null,
                  list,
                  core.apply(apply.pos(), fnType.resultType, f, core.id(id)));
            }
        }
    }
    return super.visit(apply);
  }

  private Core.Exp collection(Core.Exp exp) {
    checkArgument(
        exp.type.isCollection(), "not a collection type: %s", exp.type);
    return exp;
  }

  private Core.IdPat elementPat(Core.Exp rel) {
    return core.elementPat(
        rel.type.elementType(), typeSystem.nameGenerator::inc);
  }
}

// End Relationalizer.java
