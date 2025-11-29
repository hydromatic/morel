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
package net.hydromatic.morel;

import static net.hydromatic.morel.Ml.ml;
import static net.hydromatic.morel.parse.Parsers.stringToString;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import java.util.List;
import net.hydromatic.morel.datalog.DatalogAnalyzer;
import net.hydromatic.morel.datalog.DatalogAst;
import net.hydromatic.morel.datalog.DatalogAst.Atom;
import net.hydromatic.morel.datalog.DatalogAst.BodyAtom;
import net.hydromatic.morel.datalog.DatalogAst.Constant;
import net.hydromatic.morel.datalog.DatalogAst.Declaration;
import net.hydromatic.morel.datalog.DatalogAst.Fact;
import net.hydromatic.morel.datalog.DatalogAst.Output;
import net.hydromatic.morel.datalog.DatalogAst.Param;
import net.hydromatic.morel.datalog.DatalogAst.Program;
import net.hydromatic.morel.datalog.DatalogAst.Rule;
import net.hydromatic.morel.datalog.DatalogAst.Variable;
import net.hydromatic.morel.datalog.DatalogException;
import net.hydromatic.morel.datalog.DatalogParserImpl;
import net.hydromatic.morel.datalog.ParseException;
import org.junit.jupiter.api.Test;

/** Unit tests for Datalog components. */
public class DatalogTest {

  @Test
  void testParseDeclaration() throws ParseException {
    String input = ".decl edge(x:int, y:int)";
    Program program = DatalogParserImpl.parse(input);

    assertThat(program.statements.size(), is(1));
    assertThat(program.statements.get(0) instanceof Declaration, is(true));

    Declaration decl = (Declaration) program.statements.get(0);
    assertThat(decl.name, is("edge"));
    assertThat(decl.arity(), is(2));
    assertThat(decl.params.get(0).name, is("x"));
    assertThat(decl.params.get(0).type, is("int"));
    assertThat(decl.params.get(1).name, is("y"));
    assertThat(decl.params.get(1).type, is("int"));
  }

  @Test
  void testParseFact() throws ParseException {
    String input =
        ".decl edge(x:int, y:int)\n" //
            + "edge(1,2).";
    Program program = DatalogParserImpl.parse(input);

    assertThat(program.statements.size(), is(2));
    assertThat(program.statements.get(1) instanceof Fact, is(true));

    Fact fact = (Fact) program.statements.get(1);
    assertThat(fact.atom.name, is("edge"));
    assertThat(fact.atom.arity(), is(2));
    assertThat(fact.atom.terms.get(0) instanceof Constant, is(true));
    Constant c0 = (Constant) fact.atom.terms.get(0);
    assertThat(c0.value, is(1)); // Parser returns Integer, not Long
    assertThat(c0.type, is("int"));
  }

  @Test
  void testParseRule() throws ParseException {
    String input =
        ".decl edge(x:int, y:int)\n"
            + ".decl path(x:int, y:int)\n"
            + "path(X,Y) :- edge(X,Y).";
    Program program = DatalogParserImpl.parse(input);

    assertThat(program.statements.size(), is(3));
    assertThat(program.statements.get(2) instanceof Rule, is(true));

    Rule rule = (Rule) program.statements.get(2);
    assertThat(rule.head.name, is("path"));
    assertThat(rule.head.arity(), is(2));
    assertThat(rule.body.size(), is(1));

    BodyAtom bodyAtom = rule.body.get(0);
    assertThat(bodyAtom.negated, is(false));
    assertThat(bodyAtom.atom.name, is("edge"));
  }

  @Test
  void testParseNegation() throws ParseException {
    String input =
        ".decl p(x:int)\n" //
            + ".decl q(x:int)\n"
            + "p(X) :- !q(X).";
    Program program = DatalogParserImpl.parse(input);

    Rule rule = (Rule) program.statements.get(2);
    BodyAtom bodyAtom = rule.body.get(0);
    assertThat(bodyAtom.negated, is(true));
    assertThat(bodyAtom.atom.name, is("q"));
  }

  @Test
  void testParseMultipleBodyAtoms() throws ParseException {
    String input =
        ".decl edge(x:int, y:int)\n"
            + ".decl path(x:int, y:int)\n"
            + "path(X,Z) :- edge(X,Y), edge(Y,Z).";
    Program program = DatalogParserImpl.parse(input);

    Rule rule = (Rule) program.statements.get(2);
    assertThat(rule.body.size(), is(2));
    assertThat(rule.body.get(0).atom.name, is("edge"));
    assertThat(rule.body.get(1).atom.name, is("edge"));
  }

  @Test
  void testParseOutput() throws ParseException {
    String input =
        ".decl edge(x:int, y:int)\n" //
            + ".output edge";
    Program program = DatalogParserImpl.parse(input);

    assertThat(program.statements.size(), is(2));
    assertThat(program.statements.get(1) instanceof Output, is(true));

    Output output = (Output) program.statements.get(1);
    assertThat(output.relationName, is("edge"));

    // Test getOutputs() helper
    assertThat(program.getOutputs().size(), is(1));
    assertThat(program.getOutputs().get(0).relationName, is("edge"));
  }

  @Test
  void testParseMultipleTypes() throws ParseException {
    String input = ".decl person(name:string, age:int)";
    Program program = DatalogParserImpl.parse(input);

    Declaration decl = (Declaration) program.statements.get(0);
    assertThat(decl.params.get(0).type, is("string"));
    assertThat(decl.params.get(1).type, is("int"));
  }

  @Test
  void testParseStringConstant() throws ParseException {
    String input =
        ".decl person(name:string)\n" //
            + "person(\"Alice\").";
    Program program = DatalogParserImpl.parse(input);

    Fact fact = (Fact) program.statements.get(1);
    Constant c = (Constant) fact.atom.terms.get(0);
    assertThat(c.value, is("Alice"));
    assertThat(c.type, is("string"));
  }

  @Test
  void testParseLowercaseAsVariable() throws ParseException {
    String input =
        ".decl color(c:string)\n" //
            + "color(red).";
    Program program = DatalogParserImpl.parse(input);

    Fact fact = (Fact) program.statements.get(1);
    // Parser treats lowercase identifiers as variables
    assertThat(fact.atom.terms.get(0) instanceof Variable, is(true));
    Variable v = (Variable) fact.atom.terms.get(0);
    assertThat(v.name, is("red"));
  }

  @Test
  void testParseComments() throws ParseException {
    String input =
        "// Line comment\n"
            + ".decl edge(x:int, y:int)\n"
            + "/* Block comment */\n"
            + "edge(1,2)."; // Comments are handled by lexer
    Program program = DatalogParserImpl.parse(input);

    assertThat(program.statements.size(), is(2));
  }

  @Test
  void testAnalyzeValidProgram() throws ParseException {
    String input =
        ".decl edge(x:int, y:int)\n"
            + ".decl path(x:int, y:int)\n"
            + "edge(1,2).\n"
            + "path(X,Y) :- edge(X,Y).\n"
            + ".output path";
    Program program = DatalogParserImpl.parse(input);

    // Should not throw
    DatalogAnalyzer.analyze(program);
  }

  @Test
  void testAnalyzeArityMismatchFact() throws ParseException {
    String input =
        ".decl edge(x:int, y:int)\n" //
            + "edge(1,2,3).";
    Program program = DatalogParserImpl.parse(input);

    DatalogException e =
        assertThrows(
            DatalogException.class, () -> DatalogAnalyzer.analyze(program));
    assertThat(e.getMessage(), containsString("edge/3"));
    assertThat(e.getMessage(), containsString("edge/2"));
  }

  @Test
  void testAnalyzeArityMismatchRuleHead() throws ParseException {
    String input =
        ".decl edge(x:int, y:int)\n"
            + ".decl path(x:int, y:int)\n"
            + "path(X,Y,Z) :- edge(X,Y).";
    Program program = DatalogParserImpl.parse(input);

    DatalogException e =
        assertThrows(
            DatalogException.class, () -> DatalogAnalyzer.analyze(program));
    assertThat(e.getMessage(), containsString("path/3"));
    assertThat(e.getMessage(), containsString("path/2"));
  }

  @Test
  void testAnalyzeArityMismatchRuleBody() throws ParseException {
    String input =
        ".decl edge(x:int, y:int)\n"
            + ".decl path(x:int, y:int)\n"
            + "path(X,Y) :- edge(X).";
    Program program = DatalogParserImpl.parse(input);

    DatalogException e =
        assertThrows(
            DatalogException.class, () -> DatalogAnalyzer.analyze(program));
    assertThat(e.getMessage(), containsString("edge/1"));
    assertThat(e.getMessage(), containsString("edge/2"));
  }

  @Test
  void testAnalyzeUndeclaredRelation() throws ParseException {
    String input =
        ".decl edge(x:int, y:int)\n" //
            + "path(1,2).";
    Program program = DatalogParserImpl.parse(input);

    DatalogException e =
        assertThrows(
            DatalogException.class, () -> DatalogAnalyzer.analyze(program));
    assertThat(e.getMessage(), containsString("path"));
    assertThat(e.getMessage(), containsString("not declared"));
  }

  @Test
  void testAnalyzeTypeMismatchNumber() throws ParseException {
    String input =
        ".decl person(name:string)\n" //
            + "person(42).";
    Program program = DatalogParserImpl.parse(input);

    DatalogException e =
        assertThrows(
            DatalogException.class, () -> DatalogAnalyzer.analyze(program));
    assertThat(e.getMessage(), containsString("Type mismatch"));
    assertThat(e.getMessage(), containsString("string"));
    assertThat(e.getMessage(), containsString("int"));
  }

  @Test
  void testAnalyzeTypeMismatchString() throws ParseException {
    String input =
        ".decl num(n:int)\n" //
            + "num(\"hello\").";
    Program program = DatalogParserImpl.parse(input);

    DatalogException e =
        assertThrows(
            DatalogException.class, () -> DatalogAnalyzer.analyze(program));
    String expected =
        "Type mismatch in fact num(...): "
            + "expected int, got string for parameter n";
    assertThat(e.getMessage(), hasToString(expected));
  }

  @Test
  void testAnalyzeUnsafeRuleHeadVariable() throws ParseException {
    String input =
        ".decl edge(x:int, y:int)\n"
            + ".decl path(x:int, y:int)\n"
            + "path(X,Y) :- edge(X,Z).";
    Program program = DatalogParserImpl.parse(input);

    DatalogException e =
        assertThrows(
            DatalogException.class, () -> DatalogAnalyzer.analyze(program));
    assertThat(e.getMessage(), containsString("unsafe"));
    assertThat(e.getMessage(), containsString("Variable 'Y'"));
  }

  @Test
  void testAnalyzeUnsafeNegation() throws ParseException {
    String input =
        ".decl edge(x:int, y:int)\n"
            + ".decl path(x:int, y:int)\n"
            + "path(X,Y) :- edge(X,Z), !edge(Y,W).";
    Program program = DatalogParserImpl.parse(input);

    DatalogException e =
        assertThrows(
            DatalogException.class, () -> DatalogAnalyzer.analyze(program));
    assertThat(e.getMessage(), containsString("unsafe"));
    assertThat(e.getMessage(), containsString("Variable 'Y'"));
  }

  @Test
  void testAnalyzeStratificationValid() throws ParseException {
    String input =
        ".decl edge(X:int, Y:int)\n"
            + ".decl path(X:int, Y:int)\n"
            + "edge(1,2).\n"
            + "path(X,Y) :- edge(X,Y).\n"
            + "path(X,Z) :- path(X,Y), edge(Y,Z).";
    Program program = DatalogParserImpl.parse(input);

    // Should not throw - program is safe and stratified
    DatalogAnalyzer.analyze(program);
  }

  @Test
  void testAnalyzeStratificationNegationCycle() throws ParseException {
    String input =
        ".decl edge(X:int, Y:int)\n"
            + ".decl p(X:int)\n"
            + ".decl q(X:int)\n"
            + "edge(1,2).\n"
            + "p(X) :- edge(X,Y), !q(X).\n"
            + "q(X) :- edge(X,Y), !p(X).";
    Program program = DatalogParserImpl.parse(input);

    DatalogException e =
        assertThrows(
            DatalogException.class, () -> DatalogAnalyzer.analyze(program));
    assertThat(e.getMessage(), containsString("not stratified"));
    assertThat(
        e.getMessage(), containsString("Negation cycle")); // Case-sensitive
  }

  @Test
  void testAstProgramHelpers() throws ParseException {
    String input =
        ".decl edge(x:int, y:int)\n"
            + ".decl path(x:int, y:int)\n"
            + ".output edge\n"
            + ".output path";
    Program program = DatalogParserImpl.parse(input);

    // Test getDeclaration
    assertThat(program.hasDeclaration("edge"), is(true));
    assertThat(program.hasDeclaration("path"), is(true));
    assertThat(program.hasDeclaration("missing"), is(false));

    Declaration edgeDecl = program.getDeclaration("edge");
    assertThat(edgeDecl.name, is("edge"));
    assertThat(edgeDecl.arity(), is(2));

    // Test getOutputs
    assertThat(program.getOutputs().size(), is(2));
    assertThat(program.getOutputs().get(0).relationName, is("edge"));
    assertThat(program.getOutputs().get(1).relationName, is("path"));
  }

  @Test
  void testAstVariableEquality() {
    Variable v1 = new Variable("X");
    Variable v2 = new Variable("X");
    Variable v3 = new Variable("Y");

    assertThat(v1.equals(v2), is(true));
    assertThat(v1.equals(v3), is(false));
    assertThat(v1.hashCode(), is(v2.hashCode()));
  }

  @Test
  void testAstConstantEquality() {
    Constant c1 = new Constant(42L, "int");
    Constant c2 = new Constant(42L, "int");
    Constant c3 = new Constant(43L, "int");
    Constant c4 = new Constant(42L, "string");

    assertThat(c1.equals(c2), is(true));
    assertThat(c1.equals(c3), is(false));
    assertThat(c1.equals(c4), is(false));
    assertThat(c1.hashCode(), is(c2.hashCode()));
  }

  @Test
  void testAstToString() {
    // Test Param toString
    Param param = new Param("x", "int");
    assertThat(param, hasToString("x:int"));

    // Test Declaration toString
    List<Param> params =
        ImmutableList.of(new Param("x", "int"), new Param("y", "int"));
    Declaration decl = new Declaration("edge", params);
    assertThat(decl.toString(), containsString(".decl edge"));

    // Test Variable toString
    Variable var = new Variable("X");
    assertThat(var, hasToString("X"));

    // Test Constant toString - int
    Constant numConst = new Constant(42L, "int");
    assertThat(numConst, hasToString("42"));

    // Test Constant toString - string
    Constant strConst = new Constant("hello", "string");
    assertThat(strConst, hasToString("\"hello\""));

    // Test Atom toString
    List<DatalogAst.Term> terms = ImmutableList.of(var, numConst);
    Atom atom = new Atom("test", terms);
    assertThat(atom.toString(), containsString("test"));

    // Test BodyAtom toString - positive
    BodyAtom posBodyAtom = new BodyAtom(atom, false);
    assertThat(posBodyAtom.toString(), containsString("test"));

    // Test BodyAtom toString - negated
    BodyAtom negBodyAtom = new BodyAtom(atom, true);
    assertThat(negBodyAtom.toString(), containsString("!"));
  }

  @Test
  void testParseError() {
    String input = ".decl edge(x:int"; // Missing closing paren

    assertThrows(ParseException.class, () -> DatalogParserImpl.parse(input));
  }

  @Test
  void testEmptyProgram() throws ParseException {
    String input = "";
    Program program = DatalogParserImpl.parse(input);

    assertThat(program.statements.size(), is(0));
  }

  /** Tests calling {@code Datalog.validate}. */
  @Test
  void testValidateThroughMorel() {
    // Test Datalog.validate integration with Morel - verify it compiles and has
    // correct type
    String program =
        ".decl edge(x:int, y:int)\n" //
            + "edge(1,2).\n"
            + ".output edge\n";
    String ml = "Datalog.validate \"" + stringToString(program) + "\"";
    ml(ml).assertType("string");
  }

  /** Tests calling {@code Datalog.execute}. */
  @Test
  void testExecuteThroughMorel() {
    String program =
        ".decl edge(x:int, y:int)\n" //
            + "edge(1,2).\n"
            + ".output edge\n";
    String ml = "Datalog.execute \"" + stringToString(program) + "\"";
    String expected = "Variant({edge:{x:int, y:int} list}, [[[1, 2]]])";
    ml(ml).assertType("variant").assertEval(hasToString(expected));
  }
}

// End DatalogTest.java
