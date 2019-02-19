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

import com.google.common.collect.ImmutableList;

import net.hydromatic.sml.ast.Ast;
import net.hydromatic.sml.ast.AstBuilder;
import net.hydromatic.sml.ast.AstNode;
import net.hydromatic.sml.ast.Op;
import net.hydromatic.sml.ast.Pos;
import net.hydromatic.sml.eval.Closure;
import net.hydromatic.sml.eval.Code;
import net.hydromatic.sml.eval.Codes;
import net.hydromatic.sml.eval.EvalEnv;
import net.hydromatic.sml.eval.Unit;
import net.hydromatic.sml.type.Type;
import net.hydromatic.sml.util.Pair;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles an expression to code that can be evaluated. */
public class Compiler {
  private final TypeResolver.TypeMap typeMap;
  private final AstBuilder ast = AstBuilder.INSTANCE;

  public Compiler(TypeResolver.TypeMap typeMap) {
    this.typeMap = typeMap;
  }

  /** Validates an expression, deducing its type. Used for testing. */
  public static TypeResolver.TypeMap validateExpression(AstNode exp) {
    final TypeResolver.TypeSystem typeSystem = new TypeResolver.TypeSystem();
    final Environment env = Environments.empty();
    return TypeResolver.deduceType(env, exp, typeSystem);
  }

  /**
   * Validates and compiles an expression or statement, and compiles it to
   * code that can be evaluated by the interpreter.
   */
  public static CompiledStatement prepareStatement(Environment env,
      AstNode statement) {
    final AstBuilder ast = AstBuilder.INSTANCE;
    final Ast.VarDecl decl;
    if (statement instanceof Ast.Exp) {
      decl = ast.varDecl(Pos.ZERO,
          ImmutableList.of(
              ast.valBind(Pos.ZERO, ast.idPat(Pos.ZERO, "it"),
                  (Ast.Exp) statement)));
    } else {
      decl = (Ast.VarDecl) statement;
    }
    final TypeResolver.TypeSystem typeSystem = new TypeResolver.TypeSystem();
    final TypeResolver.TypeMap typeMap =
        TypeResolver.deduceType(env, decl, typeSystem);
    final Compiler compiler = new Compiler(typeMap);
    return compiler.compileStatement(env, decl);
  }

  public CompiledStatement compileStatement(Environment env, Ast.VarDecl decl) {
    final Map<String, TypeAndCode> varCodes = new LinkedHashMap<>();
    for (Ast.ValBind valBind : decl.valBinds) {
      final String name = ((Ast.IdPat) valBind.pat).name;
      final Type type = typeMap.getType(valBind.e);
      final Code code = compile(env, valBind.e);
      varCodes.put(name, new TypeAndCode(type, code));
    }
    final Type type = typeMap.getType(decl);

    return new CompiledStatement() {
      public Type getType() {
        return type;
      }

      public Environment eval(Environment env, List<String> output) {
        Environment resultEnv = env;
        final EvalEnv[] evalEnvs = {Codes.emptyEnv()};
        env.forEachValue((name, value) ->
            evalEnvs[0] = Codes.add(evalEnvs[0], name, value));
        final EvalEnv evalEnv = evalEnvs[0];
        for (Map.Entry<String, TypeAndCode> entry : varCodes.entrySet()) {
          final String name = entry.getKey();
          final Code code = entry.getValue().code;
          final Type type = entry.getValue().type;
          final Object value = code.eval(evalEnv);
          resultEnv = Environments.add(resultEnv, name, type, value);
          output.add("val " + name + " = " + value + " : "
              + type.description());
        }
        return resultEnv;
      }
    };
  }

  public Code compile(Environment env, Ast.Exp expression) {
    final Ast.Literal literal;
    final Code argCode;
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

    case UNIT_LITERAL:
      return Codes.constant(Unit.INSTANCE);

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
      if (let.decl.valBinds.size() > 1) {
        // Transform "let v1 = e1 and v2 = e2 in e"
        // to "let (v1, v2) = (e1, e2) in e"
        final Map<Ast.Pat, Ast.Exp> matches = new LinkedHashMap<>();
        for (Ast.ValBind valBind : let.decl.valBinds) {
          flatten(matches, valBind.pat, valBind.e);
        }
        final Ast.Pat pat = ast.tuplePat(let.pos, matches.keySet());
        final Ast.Exp e = ast.tuple(let.pos, matches.values());
        final Ast.VarDecl decl =
            ast.varDecl(let.decl.pos, ast.valBind(let.pos, pat, e));
        return compile(env, ast.let(let.pos, decl, let.e));
      }
      final List<Code> varCodes = new ArrayList<>();
      for (Ast.ValBind valBind : let.decl.valBinds) {
        varCodes.add(compileValBind(env, valBind));
      }
      final Code resultCode = compile(env, let.e);
      return Codes.let(varCodes, resultCode);

    case FN:
      final Ast.Fn fn = (Ast.Fn) expression;
//      final TypeResolver.FnType fnType =
//          (TypeResolver.FnType) typeMap.getType(fn);
      return compileMatchList(env, fn.matchList/*, fnType.paramType */);

    case CASE:
      final Ast.Case case_ = (Ast.Case) expression;
      final Code matchCode = compileMatchList(env, case_.matchList);
      argCode = compile(env, case_.exp);
      return Codes.apply(matchCode, argCode);

    case RECORD_SELECTOR:
      final Ast.RecordSelector recordSelector = (Ast.RecordSelector) expression;
      return Codes.nth(recordSelector.slot);

    case APPLY:
      final Ast.Apply apply = (Ast.Apply) expression;
      final Code fnCode = compile(env, apply.fn);
      argCode = compile(env, apply.arg);
      return Codes.apply(fnCode, argCode);

    case TUPLE:
      final Ast.Tuple tuple = (Ast.Tuple) expression;
      final List<Code> codes = new ArrayList<>();
      for (Ast.Exp arg : tuple.args) {
        codes.add(compile(env, arg));
      }
      return Codes.tuple(codes);

    case RECORD:
      final Ast.Record record = (Ast.Record) expression;
      return compile(env, ast.tuple(record.pos, record.args.values()));

    case ANDALSO:
    case ORELSE:
    case PLUS:
    case MINUS:
    case TIMES:
    case DIVIDE:
    case DIV:
    case MOD:
    case CARET:
    case EQ:
    case NE:
    case LT:
    case GT:
    case LE:
    case GE:
      return compileInfix(env, (Ast.InfixCall) expression);

    default:
      throw new AssertionError("op not handled: " + expression.op);
    }
  }

  private Code compileInfix(Environment env, Ast.InfixCall call) {
    final Code code0 = compile(env, call.a0);
    final Code code1 = compile(env, call.a1);
    switch (call.op) {
    case EQ:
      return Codes.eq(code0, code1);
    case NE:
      return Codes.ne(code0, code1);
    case LT:
      return Codes.lt(code0, code1);
    case GT:
      return Codes.gt(code0, code1);
    case LE:
      return Codes.le(code0, code1);
    case GE:
      return Codes.ge(code0, code1);
    case ANDALSO:
      return Codes.andAlso(code0, code1);
    case ORELSE:
      return Codes.orElse(code0, code1);
    case PLUS:
      return Codes.plus(code0, code1);
    case MINUS:
      return Codes.minus(code0, code1);
    case TIMES:
      return Codes.times(code0, code1);
    case DIVIDE:
      return Codes.divide(code0, code1);
    case DIV:
      return Codes.div(code0, code1);
    case MOD:
      return Codes.mod(code0, code1);
    case CARET:
      return Codes.caret(code0, code1);
    default:
      throw new AssertionError("unknown op " + call.op);
    }
  }

  private void flatten(Map<Ast.Pat, Ast.Exp> matches,
      Ast.Pat pat, Ast.Exp exp) {
    switch (pat.op) {
    case TUPLE_PAT:
      final Ast.TuplePat tuplePat = (Ast.TuplePat) pat;
      if (exp.op == Op.TUPLE) {
        final Ast.Tuple tuple = (Ast.Tuple) exp;
        Pair.forEach(tuplePat.args, tuple.args,
            (p, e) -> flatten(matches, p, e));
        break;
      }
      // fall through
    default:
      matches.put(pat, exp);
    }
  }

  /** Compiles a {@code match} expression.
   *
   * @param env Compile environment
   * @param matchList List of Match
   * @return Code for match
   */
  private Code compileMatchList(Environment env,
      Iterable<Ast.Match> matchList) {
    final ImmutableList.Builder<Pair<Ast.Pat, Code>> patCodeBuilder =
        ImmutableList.builder();
    for (Ast.Match match : matchList) {
      final Environment[] envHolder = {env};
      match.pat.visit(pat -> {
        if (pat instanceof Ast.IdPat) {
          final Type paramType = typeMap.getType(pat);
          envHolder[0] = Environments.add(envHolder[0], ((Ast.IdPat) pat).name,
              paramType, Unit.INSTANCE);
        }
      });
      final Code code = compile(envHolder[0], match.e);
      patCodeBuilder.add(Pair.of(match.pat, code));
    }
    final ImmutableList<Pair<Ast.Pat, Code>> patCodes = patCodeBuilder.build();
    return evalEnv -> new Closure(evalEnv, patCodes);
  }

  private Code compileValBind(Environment env, Ast.ValBind valBind) {
    final Environment[] envHolder = {env};
    valBind.pat.visit(pat -> {
      if (pat instanceof Ast.IdPat) {
        final Type paramType = typeMap.getType(pat);
        envHolder[0] = Environments.add(envHolder[0], ((Ast.IdPat) pat).name,
            paramType, Unit.INSTANCE);
      }
    });
    final Code code = compile(envHolder[0], valBind.e);
    final ImmutableList<Pair<Ast.Pat, Code>> patCodes =
        ImmutableList.of(Pair.of(valBind.pat, code));
    return evalEnv -> new Closure(evalEnv, patCodes);
  }

  /**
   * Statement that has been compiled and is ready to be run from the
   * REPL. If a declaration, it evaluates an expression and also
   * creates a new environment (with new variables bound) and
   * generates a line or two of output for the REPL.
   */
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
