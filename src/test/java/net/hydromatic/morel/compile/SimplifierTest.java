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

import static net.hydromatic.morel.ast.AstBuilder.ast;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;

import com.google.common.collect.ImmutableList;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Ast.Decl;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/** Tests for {@link Simplifier}. */
public class SimplifierTest {
  private static void checkSimplify(
      String message, String s, String expectedToString) {
    final Fixture f = new Fixture();
    final Core.Exp e = f.parseExp(s);
    Core.Exp e2 = Simplifier.simplify(f.typeSystem, e);
    assertThat(message, e2, hasToString(expectedToString));
  }

  /** Tests various expression simplifications. */
  @Test
  void testSimplify() {
    checkSimplify(
        "y + 10 - 1 - (y + 1) => 8",
        "fn (x: int, y: int) => y + 10 - 1 - (y + 1)",
        "fn v => case v of (x, y) => 8");
    checkSimplify(
        "(x + y) - x => y",
        "fn (x: int, y: int) => x + y - x",
        "fn v => case v of (x, y) => y");
    checkSimplify(
        "(x + y) - (x + z) => y - z",
        "fn (x: int, y: int, z: int) => (x + y) - (x + z)",
        "fn v => case v of (x, y, z) => -:int (y, z)");
    checkSimplify("4 + 1 => 5", "4 + 1", "5");
    checkSimplify("4 - 1 => 3", "4 - 1", "3");
    checkSimplify("(9 + 1) - 2 => 8", "9 + 1 - 2", "8");
    checkSimplify("(9 - 2) + 1 => 8", "9 - 2 + 1", "8");
  }

  /** Test fixture with common setup. */
  @SuppressWarnings("unused")
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();

    {
      // Register built-in types
      BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    }

    final PrimitiveType intType = PrimitiveType.INT;
    final PrimitiveType stringType = PrimitiveType.STRING;

    final Type intListType = typeSystem.listType(intType);
    final Type stringListType = typeSystem.listType(stringType);

    // Variables for testing
    final Core.IdPat xPat = core.idPat(intType, "x", 0);
    final Core.Id xId = core.id(xPat);

    final Core.IdPat yPat = core.idPat(intType, "y", 0);
    final Core.Id yId = core.id(yPat);

    final Core.IdPat pPat = core.idPat(stringType, "p", 0);
    final Core.Id pId = core.id(pPat);

    final Core.IdPat sPat = core.idPat(stringType, "s", 0);
    final Core.Id sId = core.id(sPat);

    final Core.IdPat empnoPat = core.idPat(intType, "empno", 0);
    final Core.Id empnoId = core.id(empnoPat);

    final Core.IdPat deptnoPat = core.idPat(intType, "deptno", 0);
    final Core.Id deptnoId = core.id(deptnoPat);

    final Core.IdPat dnamePat = core.idPat(stringType, "dname", 0);
    final Core.Id dnameId = core.id(dnamePat);

    // Lists for testing
    final Core.IdPat myListPat = core.idPat(intListType, "myList", 0);
    final Core.Id myListId = core.id(myListPat);

    final Type edgeRecordType =
        typeSystem.recordType(RecordType.map("x", intType, "y", intType));
    final Type edgeListType = typeSystem.listType(edgeRecordType);
    final Core.IdPat edgesPat = core.idPat(edgeListType, "edges", 0);
    final Core.Id edgesId = core.id(edgesPat);

    // Function types for testing user-defined functions
    final Type tupleTwoIntsType = typeSystem.tupleType(intType, intType);
    final Type intIntBoolFnType =
        typeSystem.fnType(tupleTwoIntsType, PrimitiveType.BOOL);
    final Type stringIntTupleBoolFnType =
        typeSystem.fnType(
            typeSystem.tupleType(intType, stringType), PrimitiveType.BOOL);

    Core.Literal intLiteral(int i) {
      return core.intLiteral(BigDecimal.valueOf(i));
    }

    Core.Literal stringLiteral(String s) {
      return core.stringLiteral(s);
    }

    /** Converts a Morel expression to a Core.Exp. */
    public Core.Exp parseExp(String s) {
      try {
        // Parse the string to AST
        final MorelParserImpl parser = new MorelParserImpl(new StringReader(s));
        parser.zero("test");
        final AstNode astNode = parser.statementEofSafe();

        // Convert AST.Exp to AST.Decl (wrap in val declaration)
        final Decl decl;
        if (astNode instanceof Ast.Exp) {
          decl =
              ast.valDecl(
                  Pos.ZERO,
                  false, // not recursive
                  false, // not inferred
                  ImmutableList.of(
                      ast.valBind(
                          Pos.ZERO,
                          ast.idPat(Pos.ZERO, "it"),
                          (Ast.Exp) astNode)));
        } else {
          decl = (Decl) astNode;
        }

        // Type-check and resolve
        final Session session = new Session(Collections.emptyMap(), typeSystem);
        final Environment env =
            Environments.env(typeSystem, session, Collections.emptyMap());
        final TypeResolver.Resolved resolved =
            TypeResolver.deduceType(env, decl, typeSystem, w -> {});

        // Convert to Core
        final Resolver resolver = Resolver.of(resolved.typeMap, env, null);
        final Core.Decl coreDecl0 = resolver.toCore(resolved.node);

        final Inliner inliner = Inliner.of(typeSystem, env, null);
        final Core.Decl coreDecl = coreDecl0.accept(inliner);

        // Extract the expression from the Core.Decl
        if (coreDecl instanceof Core.NonRecValDecl) {
          final Core.NonRecValDecl valDecl = (Core.NonRecValDecl) coreDecl;
          return valDecl.exp;
        }
        throw new RuntimeException("Expected NonRecValDecl, got " + coreDecl);
      } catch (Exception e) {
        throw new RuntimeException("Failed to parse: " + s, e);
      }
    }
  }
}

// End SimplifierTest.java
