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
package net.hydromatic.sml;

import java.io.InputStream;
import java.io.PrintStream;

/** Standard ML REPL. */
public class Main {
  /** Command-line entry point.
   *
   * @param args Command-line arguments */
  public static void main(String[] args) {
    final Main main = new Main(args, System.in, System.out);
    try {
      main.run();
    } catch (Throwable e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  /** Creates a Main. */
  Main(String[] args, InputStream in, PrintStream out) {
  }

  void run() {
  }
}

// End Main.java
