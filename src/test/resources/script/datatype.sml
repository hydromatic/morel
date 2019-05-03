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

// Recursive
datatype 'a tree = Empty | Node of 'a tree * 'a * 'a tree;
fun max (x, y) = if x < y then y else x end;
fun height Empty = 0
  | height (Node (lft, _, rht)) = 1 + max (height lft, height rht);
Empty;
height it;
Node(Empty, 1, Empty);
height it;
Node(Empty, 2, Node(Node(Empty, 3, Empty), Empty));
height it;

// Mutually recursive
datatype 'a tree = Empty | Node of 'a * 'a forest
and      'a forest = Nil | Cons of 'a tree * 'a forest;
Empty;
Nil;
Node (1, Nil);
Node (1, Cons (Empty, Nil));

// Parentheses are required for 2 or more type parameters,
// optional for 1 type parameter,
// not allowed for 0 type parameters.
datatype ('a, 'b) pair = Pair of 'a * 'b;
datatype 'a, 'b pair = Pair of 'a * 'b; // not valid
datatype 'a single = Single of 'a;
datatype ('a) single = Single of 'a;
datatype () void = Void of unit; // not valid
datatype () void = Void; // not valid
datatype void = Void;
datatype unitVoid = Void of unit;
// End datatype.sml
