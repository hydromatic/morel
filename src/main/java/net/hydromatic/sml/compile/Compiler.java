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
import net.hydromatic.sml.eval.Unit;
import net.hydromatic.sml.type.Type;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles an expression to code that can be evaluated. */
public class Compiler {
  private final AstBuilder ast = AstBuilder.INSTANCE;
  private TypeResolver.TypeMap typeMap;

  public Compiler(TypeResolver.TypeMap typeMap) {
    this.typeMap = typeMap;
  }

  /** Validates an expression, deducing its type. Used for testing. */
  public static TypeResolver.TypeMap validateExpression(AstNode exp) {
    final TypeResolver.TypeSystem typeSystem =
        new TypeResolver.TypeSystem();
    final Environment env = Environments.empty();
    return TypeResolver.deduceType(env, exp, typeSystem);
  }

  /** Validates and compiles an expression or statement, and compiles it to
   * code that can be evaluated by the interpreter. */
  public static CompiledStatement prepareStatement(Environment env,
      AstNode statement) {
    final AstBuilder ast = AstBuilder.INSTANCE;
    final Ast.VarDecl decl;
    if (statement instanceof Ast.Exp) {
      decl = ast.varDecl(Pos.ZERO,
          ImmutableMap.of(ast.namedPat(Pos.ZERO, "it"), (Ast.Exp) statement));
    } else {
      decl = (Ast.VarDecl) statement;
    }
    final TypeResolver.TypeSystem typeSystem =
        new TypeResolver.TypeSystem();
    final TypeResolver.TypeMap typeMap =
        TypeResolver.deduceType(env, decl, typeSystem);
    final Compiler compiler = new Compiler(typeMap);
    return compiler.compileStatement(env, decl);
  }

  public CompiledStatement compileStatement(Environment env, Ast.VarDecl decl) {
    final Map<String, TypeAndCode> varCodes = new LinkedHashMap<>();
    for (Map.Entry<Ast.Pat, Ast.Exp> e : decl.patExps.entrySet()) {
      final Ast.Pat pat = e.getKey();
      final String name = ((Ast.NamedPat) pat).name;
      final Ast.Exp exp = e.getValue();
      final Type type = typeMap.getType(exp);
      final Code code = compile(env, exp);
      varCodes.put(name, new TypeAndCode(type, code));
    }
    final Type type = typeMap.getType(decl);

    return new CompiledStatement() {
      public Type getType() {
        return type;
      }

      public Environment eval(Environment env, List<String> output) {
        Environment resultEnvironment = env;
        for (Map.Entry<String, TypeAndCode> entry : varCodes.entrySet()) {
          final String name = entry.getKey();
          final Code code = entry.getValue().code;
          final Type type = entry.getValue().type;
          final Object value = code.eval(env);
          resultEnvironment =
              Environments.add(resultEnvironment, name, type, value);
          output.add("val " + name + " = " + value + " : int");
        }
        return resultEnvironment;
      }
    };
  }

  public Code compile(Environment env, Ast.Exp expression) {
    final Ast.Literal literal;
    final Ast.InfixCall call;
    final Code code0;
    final Code code1;
    switch (expression.op) {
    case INT_LITERAL:
      literal = (Ast.Literal) expression;
      return Codes.constant(((BigDecimal) literal.value).intValue());
    case REAL_LITERAL:
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
    case IF:
      final Ast.If if_ = (Ast.If) expression;
      final Code conditionCode = compile(env, if_.condition);
      final Code trueCode = compile(env, if_.ifTrue);
      final Code falseCode = compile(env, if_.ifFalse);
      return Codes.ifThenElse(conditionCode, trueCode, falseCode);
    case LET:
      final Ast.LetExp let = (Ast.LetExp) expression;
      final List<Codes.NameTypeCode> varCodes = new ArrayList<>();
      for (Map.Entry<Ast.Pat, Ast.Exp> e : let.decl.patExps.entrySet()) {
        final Ast.Pat pat = e.getKey();
        final String name;
        switch (pat.op) {
        case NAMED_PAT:
          name = ((Ast.NamedPat) pat).name;
          break;
        default:
          throw new AssertionError("TODO:");
        }
        final Ast.Exp exp = e.getValue();
        final Type type = typeMap.getType(exp);
        final Code initCode = compile(env, exp);
        varCodes.add(new Codes.NameTypeCode(name, type, initCode));
      }
      final Code resultCode = compile(env, let.e);
      return Codes.let(varCodes, resultCode);
    case FN:
      final Ast.Fn fn = (Ast.Fn) expression;
      final String paramName = ((Ast.NamedPat) fn.match.pat).name;
      final TypeResolver.FnType fnType =
          (TypeResolver.FnType) typeMap.getType(fn);
      final Environment env2 = Environments.add(env, paramName,
          fnType.paramType, Unit.INSTANCE);
      code0 = compile(env2, fn.match.e);
      return Codes.fn(paramName, fnType.paramType, code0);
    case APPLY:
      final Ast.Apply apply = (Ast.Apply) expression;
      final Code fnCode = compile(env, apply.fn);
      final Code argCode = compile(env, apply.arg);
      return Codes.apply(fnCode, argCode);
    case ANDALSO:
      call = (Ast.InfixCall) expression;
      code0 = compile(env, call.a0);
      code1 = compile(env, call.a1);
      return Codes.andAlso(code0, code1);
    case ORELSE:
      call = (Ast.InfixCall) expression;
      code0 = compile(env, call.a0);
      code1 = compile(env, call.a1);
      return Codes.orElse(code0, code1);
    case PLUS:
      call = (Ast.InfixCall) expression;
      code0 = compile(env, call.a0);
      code1 = compile(env, call.a1);
      return Codes.plus(code0, code1);
    case MINUS:
      call = (Ast.InfixCall) expression;
      code0 = compile(env, call.a0);
      code1 = compile(env, call.a1);
      return Codes.minus(code0, code1);
    case TIMES:
      call = (Ast.InfixCall) expression;
      code0 = compile(env, call.a0);
      code1 = compile(env, call.a1);
      return Codes.times(code0, code1);
    case DIVIDE:
      call = (Ast.InfixCall) expression;
      code0 = compile(env, call.a0);
      code1 = compile(env, call.a1);
      return Codes.divide(code0, code1);
    case CARET:
      call = (Ast.InfixCall) expression;
      code0 = compile(env, call.a0);
      code1 = compile(env, call.a1);
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

    Type getType();
  }

  /** A (type, code) pair. */
  public static class TypeAndCode {
    public final Type type;
    public final Code code;

    private TypeAndCode(Type type, Code code) {
      this.type = type;
      this.code = code;
    }
  }

}

// End Compiler.java
