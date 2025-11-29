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

/**
 * Datalog implementation for Morel.
 *
 * <p>This package provides a Datalog interface that parses Datalog programs,
 * validates them, and translates them to Morel expressions for execution.
 *
 * <h2>Key Classes</h2>
 *
 * <ul>
 *   <li>{@link net.hydromatic.morel.datalog.DatalogEvaluator} - Main entry
 *       point. Orchestrates the pipeline: parse &rarr; analyze &rarr; translate
 *       &rarr; compile &rarr; execute.
 *   <li>{@link net.hydromatic.morel.datalog.DatalogParserImpl} - JavaCC-based
 *       parser for Datalog syntax.
 *   <li>{@link net.hydromatic.morel.datalog.DatalogAst} - Abstract syntax tree
 *       nodes for Datalog programs (declarations, facts, rules, etc.).
 *   <li>{@link net.hydromatic.morel.datalog.DatalogAnalyzer} - Safety and
 *       stratification checker. Ensures all rules are safe (variables in head
 *       appear in positive body atoms) and programs are stratified (no negation
 *       cycles).
 *   <li>{@link net.hydromatic.morel.datalog.DatalogTranslator} - Translates
 *       Datalog programs to Morel source code using {@code Relational.iterate}
 *       for semi-naive fixpoint evaluation.
 *   <li>{@link net.hydromatic.morel.datalog.DatalogException} - Checked
 *       exception for Datalog compilation and execution errors.
 * </ul>
 *
 * <h2>Morel API</h2>
 *
 * <p>The Datalog interface is exposed to Morel programs through the {@code
 * Datalog} structure:
 *
 * <ul>
 *   <li>{@code Datalog.execute : string -> 'a variant} - Executes a Datalog
 *       program and returns results for output relations.
 *   <li>{@code Datalog.validate : string -> string} - Validates a Datalog
 *       program and returns its type or an error message.
 *   <li>{@code Datalog.translate : string -> string option} - Translates a
 *       Datalog program to Morel source code.
 * </ul>
 *
 * <h2>Supported Syntax</h2>
 *
 * <p>The syntax is based on <a
 * href="https://souffle-lang.github.io/">Souffle</a> with the following
 * differences:
 *
 * <ul>
 *   <li>Souffle's {@code number} type is {@code int}
 *   <li>Souffle's {@code symbol} type is {@code string}
 *   <li>Souffle's {@code .input} directive has one argument; Morel's has an
 *       optional second argument for the file name
 * </ul>
 *
 * <p>Supported constructs:
 *
 * <ul>
 *   <li>Declarations: {@code .decl relation(param:type, ...)}
 *   <li>Facts: {@code relation(const, ...).}
 *   <li>Rules: {@code head(X, Y) :- body1(X, Z), body2(Z, Y).}
 *   <li>Negation: {@code result(X) :- p(X), !q(X).}
 *   <li>Comparisons: {@code =}, {@code !=}, {@code <}, {@code <=}, {@code >},
 *       {@code >=}
 *   <li>Arithmetic in heads: {@code fact(N + 1, V * (N + 1)) :- fact(N, V).}
 *   <li>Input directive: {@code .input relation "file.csv"}
 *   <li>Output directive: {@code .output relation}
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * Datalog.execute ".decl edge(x:int, y:int)
 * .decl path(x:int, y:int)
 * edge(1, 2). edge(2, 3).
 * path(X, Y) :- edge(X, Y).
 * path(X, Z) :- path(X, Y), edge(Y, Z).
 * .output path";
 * (* Returns: {path=[{x=1,y=2},{x=2,y=3},{x=1,y=3}]}
 *      : {path:{x:int, y:int} list} variant *)
 * }</pre>
 *
 * @see <a href="https://en.wikipedia.org/wiki/Datalog">Datalog (Wikipedia)</a>
 */
package net.hydromatic.morel.datalog;

// End package-info.java
