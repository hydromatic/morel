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
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

/**
 * A function that is 'called' at compile time, and generates an expanded parse
 * tree.
 *
 * <p>Currently, Macros are internal. Also, the macro is validated as if it were
 * a function. Its type is derived before expansion. Expansion must preserve the
 * type.
 */
public interface Macro {
  Core.Exp expand(TypeSystem typeSystem, Environment env, Type argType);
}

// End Macro.java
