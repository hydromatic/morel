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
import com.google.common.collect.Iterables;

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Closure;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.EvalEnv;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.TailList;

import java.math.BigDecimal;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;

import static net.hydromatic.morel.ast.Ast.Direction.DESC;
import static net.hydromatic.morel.ast.AstBuilder.ast;
import static net.hydromatic.morel.util.Static.toImmutableList;

/** Compiles an expression to code that can be evaluated. */
public class Compiler {
  private static final EvalEnv EMPTY_ENV = Codes.emptyEnv();

  private final TypeMap typeMap;

  public Compiler(TypeMap typeMap) {
    this.typeMap = typeMap;
  }

  CompiledStatement compileStatement(Environment env, Ast.Decl decl) {
    final List<Code> varCodes = new ArrayList<>();
    final List<Binding> bindings = new ArrayList<>();
    final List<Action> actions = new ArrayList<>();
    compileDecl(env, decl, varCodes, bindings, actions);
    final Type type = typeMap.getType(decl);

    return new CompiledStatement() {
      public Type getType() {
        return type;
      }

      public void eval(Environment env, List<String> output,
          List<Binding> bindings) {
        final EvalEnv evalEnv = Codes.emptyEnvWith(env);
        for (Action entry : actions) {
          entry.apply(output, bindings, evalEnv);
        }
      }
    };
  }

  /** Something that needs to happen when a declaration is evaluated.
   *
   * <p>Usually involves placing a type or value into the bindings that will
   * make up the environment in which the next statement will be executed, and
   * printing some text on the screen. */
  private interface Action {
    void apply(List<String> output, List<Binding> bindings, EvalEnv evalEnv);
  }

  public Code compile(Environment env, Ast.Exp expression) {
    final Ast.Literal literal;
    final Code argCode;
    final List<Code> codes;
    switch (expression.op) {
    case BOOL_LITERAL:
      literal = (Ast.Literal) expression;
      final Boolean boolValue = (Boolean) literal.value;
      return Codes.constant(boolValue);

    case CHAR_LITERAL:
      literal = (Ast.Literal) expression;
      final Character charValue = (Character) literal.value;
      return Codes.constant(charValue);

    case INT_LITERAL:
      literal = (Ast.Literal) expression;
      return Codes.constant(((BigDecimal) literal.value).intValue());

    case REAL_LITERAL:
      literal = (Ast.Literal) expression;
      return Codes.constant(((BigDecimal) literal.value).floatValue());

    case STRING_LITERAL:
      literal = (Ast.Literal) expression;
      final String stringValue = (String) literal.value;
      return Codes.constant(stringValue);

    case UNIT_LITERAL:
      return Codes.constant(Unit.INSTANCE);

    case IF:
      final Ast.If if_ = (Ast.If) expression;
      final Code conditionCode = compile(env, if_.condition);
      final Code trueCode = compile(env, if_.ifTrue);
      final Code falseCode = compile(env, if_.ifFalse);
      return Codes.ifThenElse(conditionCode, trueCode, falseCode);

    case LET:
      final Ast.LetExp let = (Ast.LetExp) expression;
      return compileLet(env, let.decls, let.e);

    case FN:
      final Ast.Fn fn = (Ast.Fn) expression;
      return compileMatchList(env, fn.matchList);

    case CASE:
      final Ast.Case case_ = (Ast.Case) expression;
      final Code matchCode = compileMatchList(env, case_.matchList);
      argCode = compile(env, case_.e);
      return Codes.apply(matchCode, argCode);

    case RECORD_SELECTOR:
      final Ast.RecordSelector recordSelector = (Ast.RecordSelector) expression;
      return Codes.nth(recordSelector.slot).asCode();

    case APPLY:
      final Ast.Apply apply = (Ast.Apply) expression;
      assignSelector(apply);
      argCode = compile(env, apply.arg);
      final Type argType = typeMap.getType(apply.arg);
      final Applicable fnValue = compileApplicable(env, apply.fn, argType);
      if (fnValue != null) {
        return Codes.apply(fnValue, argCode);
      }
      final Code fnCode = compile(env, apply.fn);
      return Codes.apply(fnCode, argCode);

    case LIST:
      final Ast.List list = (Ast.List) expression;
      codes = new ArrayList<>();
      for (Ast.Exp arg : list.args) {
        codes.add(compile(env, arg));
      }
      return Codes.list(codes);

    case FROM:
      final Ast.From from = (Ast.From) expression;
      final Map<Ast.Pat, Code> sourceCodes = new LinkedHashMap<>();
      final List<Binding> bindings = new ArrayList<>();
      for (Map.Entry<Ast.Pat, Ast.Exp> patExp : from.sources.entrySet()) {
        final Code expCode = compile(env.bindAll(bindings), patExp.getValue());
        final Ast.Pat pat = patExp.getKey();
        sourceCodes.put(pat, expCode);
        pat.visit(p -> {
          if (p instanceof Ast.IdPat) {
            final Ast.IdPat idPat = (Ast.IdPat) p;
            bindings.add(Binding.of(idPat.name, typeMap.getType(pat)));
          }
        });
      }
      Supplier<Codes.RowSink> rowSinkFactory =
          createRowSinkFactory(env, ImmutableList.copyOf(bindings), from.steps,
              from.yieldExpOrDefault);
      return Codes.from(sourceCodes, rowSinkFactory);

    case ID:
      final Ast.Id id = (Ast.Id) expression;
      final Binding binding = env.getOpt(id.name);
      if (binding != null && binding.value instanceof Code) {
        return (Code) binding.value;
      }
      return Codes.get(id.name);

    case TUPLE:
      final Ast.Tuple tuple = (Ast.Tuple) expression;
      codes = new ArrayList<>();
      for (Ast.Exp arg : tuple.args) {
        codes.add(compile(env, arg));
      }
      return Codes.tuple(codes);

    case RECORD:
      final Ast.Record record = (Ast.Record) expression;
      return compile(env, ast.tuple(record.pos, record.args.values()));

    case ANDALSO:
    case ORELSE:
    case CONS:
      return compileInfix(env, (Ast.InfixCall) expression,
          typeMap.getType(expression));

    default:
      throw new AssertionError("op not handled: " + expression.op);
    }
  }

  private Supplier<Codes.RowSink> createRowSinkFactory(Environment env0,
      ImmutableList<Binding> bindings, List<Ast.FromStep> steps,
      Ast.Exp yieldExp) {
    final Environment env = env0.bindAll(bindings);
    if (steps.isEmpty()) {
      final Code yieldCode = compile(env, yieldExp);
      return () -> Codes.yieldRowSink(yieldCode);
    }
    final Ast.FromStep firstStep = steps.get(0);
    final ImmutableList.Builder<Binding> outBindingBuilder =
        ImmutableList.builder();
    firstStep.deriveOutBindings(bindings,
        (name, ast) -> Binding.of(name, typeMap.getType(ast)),
        outBindingBuilder::add);
    final ImmutableList<Binding> outBindings = outBindingBuilder.build();
    final Supplier<Codes.RowSink> nextFactory =
        createRowSinkFactory(env, outBindings,
            steps.subList(1, steps.size()), yieldExp);
    switch (firstStep.op) {
    case WHERE:
      final Ast.Where where = (Ast.Where) firstStep;
      final Code filterCode = compile(env, where.exp);
      return () -> Codes.whereRowSink(filterCode, nextFactory.get());

    case ORDER:
      final Ast.Order order = (Ast.Order) firstStep;
      final ImmutableList<Pair<Code, Boolean>> codes =
          order.orderItems.stream()
              .map(i -> Pair.of(compile(env, i.exp), i.direction == DESC))
              .collect(toImmutableList());
      return () -> Codes.orderRowSink(codes, bindings, nextFactory.get());

    case GROUP:
      final Ast.Group group = (Ast.Group) firstStep;
      final ImmutableList.Builder<Code> groupCodesB = ImmutableList.builder();
      for (Pair<Ast.Id, Ast.Exp> pair : group.groupExps) {
        groupCodesB.add(compile(env, pair.right));
      }
      final ImmutableList<String> names = bindingNames(bindings);
      final ImmutableList.Builder<Applicable> aggregateCodesB =
          ImmutableList.builder();
      for (Ast.Aggregate aggregate : group.aggregates) {
        final Code argumentCode;
        final Type argumentType;
        if (aggregate.argument == null) {
          final SortedMap<String, Type> argNameTypes =
              new TreeMap<>(RecordType.ORDERING);
          bindings.forEach(b -> argNameTypes.put(b.name, b.type));
          argumentType = typeMap.typeSystem.recordOrScalarType(argNameTypes);
          argumentCode = null;
        } else {
          argumentType = typeMap.getType(aggregate.argument);
          argumentCode = compile(env, aggregate.argument);
        }
        final Applicable aggregateApplicable =
            compileApplicable(env, aggregate.aggregate,
                typeMap.typeSystem.listType(argumentType));
        final Code aggregateCode;
        if (aggregateApplicable == null) {
          aggregateCode = compile(env, aggregate.aggregate);
        } else {
          aggregateCode = aggregateApplicable.asCode();
        }
        aggregateCodesB.add(
            Codes.aggregate(env, aggregateCode, names, argumentCode));
      }
      final ImmutableList<Code> groupCodes = groupCodesB.build();
      final Code keyCode = Codes.tuple(groupCodes);
      final ImmutableList<Applicable> aggregateCodes = aggregateCodesB.build();
      final ImmutableList<String> outNames = bindingNames(outBindings);
      return () -> Codes.groupRowSink(keyCode, aggregateCodes, names,
          outNames, nextFactory.get());

    default:
      throw new AssertionError("unknown step type " + firstStep.op);
    }
  }

  private ImmutableList<String> bindingNames(List<Binding> bindings) {
    //noinspection UnstableApiUsage
    return bindings.stream().map(b -> b.name)
        .collect(ImmutableList.toImmutableList());
  }

  private void assignSelector(Ast.Apply apply) {
    if (apply.fn instanceof Ast.RecordSelector) {
      final Ast.RecordSelector selector = (Ast.RecordSelector) apply.fn;
      if (selector.slot < 0) {
        final Type argType = typeMap.getType(apply.arg);
        if (argType instanceof RecordType) {
          RecordType recordType = (RecordType) argType;
          selector.slot =
              Iterables.indexOf(recordType.argNameTypes.keySet(), n ->
                  Objects.equals(selector.name, n));
        }
      }
    }
  }

  /** Compiles a function value to an {@link Applicable}, if possible, or
   * returns null. */
  private Applicable compileApplicable(Environment env, Ast.Exp fn,
      Type argType) {
    if (fn instanceof Ast.Id) {
      final Binding binding = env.getOpt(((Ast.Id) fn).name);
      if (binding != null
          && binding.value instanceof Macro) {
        final Ast.Exp e = ((Macro) binding.value).expand(env, argType);
        switch (e.op) {
        case WRAPPED_APPLICABLE:
          return ((Ast.ApplicableExp) e).applicable;
        }
        final Code code = compile(env, e);
        return (evalEnv, argValue) -> code.eval(evalEnv);
      }
      if (binding != null
          && binding.value instanceof Applicable) {
        return (Applicable) binding.value;
      }
    }
    final Code fnCode = compile(env, fn);
    if (fnCode.isConstant()) {
      return (Applicable) fnCode.eval(EMPTY_ENV);
    } else {
      return null;
    }
  }

  private Code compileAggregate(Environment env, Ast.Aggregate aggregate) {
    throw new UnsupportedOperationException(); // TODO
  }

  private Code compileLet(Environment env, List<Ast.Decl> decls, Ast.Exp e) {
    final Ast.LetExp letExp = flattenLet(decls, e);
    return compileLet(env, Iterables.getOnlyElement(letExp.decls), letExp.e);
  }

  private Code compileLet(Environment env, Ast.Decl decl, Ast.Exp e) {
    final List<Code> varCodes = new ArrayList<>();
    final List<Binding> bindings = new ArrayList<>();
    compileDecl(env, decl, varCodes, bindings, null);
    Environment env2 = env.bindAll(bindings);
    final Code resultCode = compile(env2, e);
    return Codes.let(varCodes, resultCode);
  }

  private Ast.LetExp flattenLet(List<Ast.Decl> decls, Ast.Exp e) {
    if (decls.size() == 1) {
      return ast.let(e.pos, decls, e);
    } else {
      return ast.let(e.pos, decls.subList(0, 1),
          flattenLet(decls.subList(1, decls.size()), e));
    }
  }

  private void compileDecl(Environment env, Ast.Decl decl, List<Code> varCodes,
      List<Binding> bindings, List<Action> actions) {
    switch (decl.op) {
    case VAL_DECL:
      compileValDecl(env, (Ast.ValDecl) decl, varCodes, bindings, actions);
      break;
    case DATATYPE_DECL:
      final Ast.DatatypeDecl datatypeDecl = (Ast.DatatypeDecl) decl;
      compileDatatypeDecl(env, datatypeDecl, bindings, actions);
      break;
    case FUN_DECL:
      throw new AssertionError("unknown " + decl.op + " [" + decl
          + "] (did you remember to call TypeResolver.toValDecl?)");
    default:
      throw new AssertionError("unknown " + decl.op + " [" + decl + "]");
    }
  }

  private void compileValDecl(Environment env, Ast.ValDecl valDecl,
      List<Code> varCodes, List<Binding> bindings, List<Action> actions) {
    if (valDecl.valBinds.size() > 1) {
      // Transform "let val v1 = e1 and v2 = e2 in e"
      // to "let val (v1, v2) = (e1, e2) in e"
      final Map<Ast.Pat, Ast.Exp> matches = new LinkedHashMap<>();
      boolean rec = false;
      for (Ast.ValBind valBind : valDecl.valBinds) {
        flatten(matches, valBind.pat, valBind.e);
        rec |= valBind.rec;
      }
      final Pos pos = valDecl.pos;
      final Ast.Pat pat = ast.tuplePat(pos, matches.keySet());
      final Ast.Exp e2 = ast.tuple(pos, matches.values());
      valDecl = ast.valDecl(pos, ast.valBind(pos, rec, pat, e2));
      matches.forEach((key, value) ->
          bindings.add(
              Binding.of(((Ast.IdPat) key).name, typeMap.getType(value),
                  Unit.INSTANCE)));
    } else {
      valDecl.valBinds.forEach(valBind ->
          valBind.pat.visit(pat -> {
            if (pat instanceof Ast.IdPat) {
              final Type paramType = typeMap.getType(pat);
              bindings.add(Binding.of(((Ast.IdPat) pat).name, paramType));
            }
          }));
    }
    for (Ast.ValBind valBind : valDecl.valBinds) {
      compileValBind(env, valBind, varCodes, bindings, actions);
    }
  }

  private void compileDatatypeDecl(Environment env,
      Ast.DatatypeDecl datatypeDecl, List<Binding> bindings,
      List<Action> actions) {
    for (Ast.DatatypeBind bind : datatypeDecl.binds) {
      final List<Binding> newBindings = new TailList<>(bindings);
      final Type dataType = typeMap.typeSystem.lookup(bind.name.name);
      for (Ast.TyCon tyCon : bind.tyCons) {
        compileTyCon(env, dataType, tyCon, bindings);
      }
      if (actions != null) {
        final List<Binding> immutableBindings =
            ImmutableList.copyOf(newBindings);
        actions.add((output, outBindings, evalEnv) -> {
          output.add("datatype " + bind);
          outBindings.addAll(immutableBindings);
        });
      }
    }
  }

  private void compileTyCon(Environment env, Type dataType,
      Ast.TyCon tyCon, List<Binding> bindings) {
    final Type type = Objects.requireNonNull(typeMap.getType(tyCon));
    final Object value;
    if (tyCon.type == null) {
      value = Codes.constant(new ComparableSingletonList<>(tyCon.id.name));
    } else {
      value = Codes.tyCon(dataType, tyCon.id.name);
    }
    bindings.add(Binding.of(tyCon.id.name, type, value));
  }

  private Code compileInfix(Environment env, Ast.InfixCall call, Type type) {
    final Code code0 = compile(env, call.a0);
    final Code code1 = compile(env, call.a1);
    switch (call.op) {
    case ANDALSO:
      return Codes.andAlso(code0, code1);
    case ORELSE:
      return Codes.orElse(code0, code1);
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
      List<Ast.Match> matchList) {
    @SuppressWarnings("UnstableApiUsage")
    final ImmutableList<Pair<Ast.Pat, Code>> patCodes =
        matchList.stream()
            .map(match -> compileMatch(env, match))
            .collect(ImmutableList.toImmutableList());
    return evalEnv -> new Closure(evalEnv, patCodes);
  }

  private Pair<Ast.Pat, Code> compileMatch(Environment env, Ast.Match match) {
    final Environment[] envHolder = {env};
    match.pat.visit(pat -> {
      if (pat instanceof Ast.IdPat) {
        final Type paramType = typeMap.getType(pat);
        envHolder[0] = envHolder[0].bind(((Ast.IdPat) pat).name,
            paramType, Unit.INSTANCE);
      }
    });
    final Code code = compile(envHolder[0], match.e);
    return Pair.of(expandRecordPattern(match.pat), code);
  }

  /** Expands a pattern if it is a record pattern that has an ellipsis
   * or if the arguments are not in the same order as the labels in the type. */
  private Ast.Pat expandRecordPattern(Ast.Pat pat) {
    switch (pat.op) {
    case ID_PAT:
      final Type type = typeMap.getType(pat);
      final Ast.IdPat idPat = (Ast.IdPat) pat;
      if (type.op() == Op.DATA_TYPE
          && ((DataType) type).typeConstructors.containsKey(idPat.name)) {
        return ast.con0Pat(idPat.pos, ast.id(idPat.pos, idPat.name));
      }
      return pat;

    case RECORD_PAT:
      final RecordType recordType =
          (RecordType) typeMap.getType(pat);
      final Ast.RecordPat recordPat = (Ast.RecordPat) pat;
      final Map<String, Ast.Pat> args = new LinkedHashMap<>();
      for (String label : recordType.argNameTypes.keySet()) {
        args.put(label,
            recordPat.args.getOrDefault(label, ast.wildcardPat(pat.pos)));
      }
      if (recordPat.ellipsis || !recordPat.args.equals(args)) {
        // Only create an expanded pattern if it is different (no ellipsis,
        // or arguments in a different order).
        return ast.recordPat(recordPat.pos, false, args);
      }
      // fall through
      return recordPat;
    default:
      return pat;
    }
  }

  private void compileValBind(Environment env, Ast.ValBind valBind,
      List<Code> varCodes, List<Binding> bindings, List<Action> actions) {
    final List<Binding> newBindings = new TailList<>(bindings);
    final Code code;
    if (valBind.rec) {
      final Map<Ast.IdPat, LinkCode> linkCodes = new IdentityHashMap<>();
      valBind.pat.visit(pat -> {
        if (pat instanceof Ast.IdPat) {
          final Ast.IdPat idPat = (Ast.IdPat) pat;
          final Type paramType = typeMap.getType(pat);
          final LinkCode linkCode = new LinkCode();
          linkCodes.put(idPat, linkCode);
          bindings.add(Binding.of(idPat.name, paramType, linkCode));
        }
      });
      code = compile(env.bindAll(bindings), valBind.e);
      link(linkCodes, valBind.pat, code);
    } else {
      code = compile(env.bindAll(bindings), valBind.e);
    }
    newBindings.clear();
    final ImmutableList<Pair<Ast.Pat, Code>> patCodes =
        ImmutableList.of(Pair.of(valBind.pat, code));
    varCodes.add(evalEnv -> new Closure(evalEnv, patCodes));

    if (actions != null) {
      final String name = ((Ast.IdPat) valBind.pat).name;
      final Type type0 = typeMap.getType(valBind.e);
      final Type type = typeMap.typeSystem.ensureClosed(type0);
      actions.add((output, outBindings, evalEnv) -> {
        final Object o = code.eval(evalEnv);
        outBindings.add(Binding.of(name, type, o));
        final StringBuilder buf = new StringBuilder();
        Pretty.pretty(buf, type, new Pretty.TypedVal(name, o, type0));
        output.add(buf.toString());
      });
    }
  }

  private void link(Map<Ast.IdPat, LinkCode> linkCodes, Ast.Pat pat,
      Code code) {
    if (pat instanceof Ast.IdPat) {
      final LinkCode linkCode = linkCodes.get(pat);
      if (linkCode != null) {
        linkCode.refCode = code; // link the reference to the definition
      }
    } else if (pat instanceof Ast.TuplePat) {
      if (code instanceof Codes.TupleCode) {
        // Recurse into the tuple, binding names to code in parallel
        final List<Code> codes = ((Codes.TupleCode) code).codes;
        final List<Ast.Pat> pats = ((Ast.TuplePat) pat).args;
        Pair.forEach(codes, pats, (code1, pat1) ->
            link(linkCodes, pat1, code1));
      }
    }
  }

  /** A piece of code that is references another piece of code.
   * It is useful when defining recursive functions.
   * The reference is mutable, and is fixed up when the
   * function has been compiled.
   */
  private static class LinkCode implements Code {
    private Code refCode;

    public Object eval(EvalEnv env) {
      assert refCode != null; // link should have completed by now
      return refCode.eval(env);
    }
  }

  /** A comparable singleton list.
   *
   * @param <E> Element type */
  private static class ComparableSingletonList<E extends Comparable<E>>
      extends AbstractList<E>
      implements Comparable<ComparableSingletonList<E>> {
    private final E element;

    ComparableSingletonList(E element) {
      this.element = Objects.requireNonNull(element);
    }

    @Override public E get(int index) {
      assert index == 0;
      return element;
    }

    @Override public int size() {
      return 1;
    }

    @Override public int compareTo(ComparableSingletonList<E> o) {
      return element.compareTo(o.element);
    }
  }

}

// End Compiler.java
