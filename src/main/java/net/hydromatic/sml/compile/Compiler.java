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
package net.hydromatic.sml.compile;

import com.google.common.collect.ImmutableMap;

import net.hydromatic.sml.ast.Ast;
import net.hydromatic.sml.ast.AstBuilder;
import net.hydromatic.sml.ast.AstNode;
import net.hydromatic.sml.ast.Pos;
import net.hydromatic.sml.eval.Code;
import net.hydromatic.sml.eval.Codes;
import net.hydromatic.sml.eval.Environment;
import net.hydromatic.sml.eval.Environments;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles an expression to code that can be evaluated. */
public class Compiler {
  final AstBuilder ast = AstBuilder.INSTANCE;

  public CompiledStatement compileStatement(AstNode statement) {
    final Ast.VarDecl decl;
    if (statement instanceof Ast.Exp) {
      decl = ast.varDecl(Pos.ZERO,
          ImmutableMap.of(ast.namedPat(Pos.ZERO, "it"), (Ast.Exp) statement));
    } else {
      decl = (Ast.VarDecl) statement;
    }
    final Map<String, Code> varCodes = new LinkedHashMap<>();
    for (Map.Entry<Ast.PatNode, Ast.Exp> e : decl.patExps.entrySet()) {
      final Ast.PatNode pat = e.getKey();
      final String name = ((Ast.NamedPat) pat).name;
      final Ast.Exp exp = e.getValue();
      final Code code = compile(exp);
      varCodes.put(name, code);
    }
    return (env, output) -> {
      Environment resultEnvironment = env;
      for (Map.Entry<String, Code> entry : varCodes.entrySet()) {
        final String name = entry.getKey();
        final Code code = entry.getValue();
        final Object value = code.eval(env);
        resultEnvironment =
            Environments.add(resultEnvironment, name, value);
        output.add("val " + name + " = " + value + " : int");
      }
      return resultEnvironment;
    };
  }

  public Code compile(Ast.Exp expression) {
    final Ast.Literal literal;
    final Ast.InfixCall call;
    final Code code0;
    final Code code1;
    switch (expression.op) {
    case INT_LITERAL:
      literal = (Ast.Literal) expression;
      return Codes.constant(((BigDecimal) literal.value).intValue());
    case FLOAT_LITERAL:
      literal = (Ast.Literal) expression;
      return Codes.constant(((BigDecimal) literal.value).floatValue());
    case BOOL_LITERAL:
      literal = (Ast.Literal) expression;
      final Boolean boolValue = (Boolean) literal.value;
      return Codes.constant(boolValue);
    case STRING_LITERAL:
      literal = (Ast.Literal) expression;
      final String stringValue = (String) literal.value;
      return Codes.constant(stringValue);
    case ID:
      final Ast.Id id = (Ast.Id) expression;
      return Codes.get(id.name);
    case LET:
      final Ast.LetExp let = (Ast.LetExp) expression;
      final Map<String, Code> varCodes = new LinkedHashMap<>();
      for (Map.Entry<Ast.PatNode, Ast.Exp> e : let.decl.patExps.entrySet()) {
        final Ast.PatNode pat = e.getKey();
        final String name;
        switch (pat.op) {
        case NAMED_PAT:
          name = ((Ast.NamedPat) pat).name;
          break;
        default:
          throw new AssertionError("TODO:");
        }
        final Code initCode = compile(e.getValue());
        varCodes.put(name, initCode);
      }
      final Code resultCode = compile(let.e);
      return Codes.let(varCodes, resultCode);
    case PLUS:
      call = (Ast.InfixCall) expression;
      code0 = compile(call.a0);
      code1 = compile(call.a1);
      return Codes.plus(code0, code1);
    case MINUS:
      call = (Ast.InfixCall) expression;
      code0 = compile(call.a0);
      code1 = compile(call.a1);
      return Codes.minus(code0, code1);
    case TIMES:
      call = (Ast.InfixCall) expression;
      code0 = compile(call.a0);
      code1 = compile(call.a1);
      return Codes.times(code0, code1);
    case DIVIDE:
      call = (Ast.InfixCall) expression;
      code0 = compile(call.a0);
      code1 = compile(call.a1);
      return Codes.divide(code0, code1);
    case CARET:
      call = (Ast.InfixCall) expression;
      code0 = compile(call.a0);
      code1 = compile(call.a1);
      return Codes.power(code0, code1);
    default:
      throw new AssertionError("op not handled: " + expression.op);
    }
  }

  /** Statement that has been compiled and is ready to be run from the
   * REPL. If a declaration, it evaluates an expression and also
   * creates a new environment (with new variables bound) and
   * generates a line or two of output for the REPL. */
  public interface CompiledStatement {
    Environment eval(Environment environment, List<String> output);
  }
}

// End Compiler.java
