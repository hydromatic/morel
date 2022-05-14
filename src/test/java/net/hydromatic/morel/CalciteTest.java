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

import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.CalciteForeignValue;
import net.hydromatic.morel.foreign.ForeignValue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.calcite.adapter.java.ReflectiveSchema;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Map;

import static org.apache.calcite.util.Util.toLinux;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/** Tests Morel's integration with Calcite.
 *
 * <p>Algebra is covered in {@link AlgebraTest} but this test covers
 * what's left, such as {@link CalciteForeignValue} and schemas.
 */
class CalciteTest {
  final boolean debug = hashCode() < hashCode(); // always false

  /** Tests that you if you create a
   * {@link net.hydromatic.morel.foreign.CalciteForeignValue}
   * whose schema has nested schemas, those schemas appear as fields. */
  @Test void testNestedSchema() {
    final Schema userSchema = new ReflectiveSchema(new UserSchema());
    final Schema taskSchema = new ReflectiveSchema(new TaskSchema());

    final Map<String, ForeignValue> foreignValueMap = Calcite.withDataSets(
        ImmutableMap.of("user", (Calcite calcite) -> {
          SchemaPlus newSchema =
              calcite.rootSchema.add("users", userSchema);
          newSchema.add("task", taskSchema);
          newSchema.add("task2", taskSchema);
          return new CalciteForeignValue(calcite, newSchema, true);
        })).foreignValues();

    final String sql = "user;\n"
        + "user.task;\n"
        + "user.task2;\n"
        + "from t in user.users yield t;\n"
        + "from t in user.task2.tasks yield t;\n";
    final InputStream in = new ByteArrayInputStream(sql.getBytes());
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final Main main =
        new Main(ImmutableList.of(), in, new PrintStream(out), foreignValueMap,
            new File(""));

    main.run();
    if (debug) {
      System.out.println(out);
    }

    String expected = "val it = {task={tasks=<relation>},"
        + "task2={tasks=<relation>},"
        + "users=<relation>}\n"
        + "  : {task:{tasks:{completed:bool, name:string} list}, "
        + "task2:{tasks:{completed:bool, name:string} list}, "
        + "users:{age:int, name:string} list}\n"
        + "val it = {tasks=<relation>} : {tasks:{completed:bool, name:string} list}\n"
        + "val it = {tasks=<relation>} : {tasks:{completed:bool, name:string} list}\n"
        + "val it = [{age=20,name=\"John\"},{age=21,name=\"Jane\"},{age=22,name=\"Jack\"}]\n"
        + "  : {age:int, name:string} list\n"
        + "val it =\n"
        + "  [{completed=false,name=\"Buy milk\"},{completed=false,name=\"Buy eggs\"},\n"
        + "   {completed=false,name=\"Buy bread\"}] : {completed:bool, name:string} list\n";
    assertThat(toLinux(out.toString()), is(expected));
  }

  /** Java object that will, via reflection, become create the "user" schema. */
  public static class UserSchema {
    @Override public String toString() {
      return "UserSchema";
    }

    /** Array that will, via reflection, become the "users" table in the
     * "user" schema. */
    @SuppressWarnings("unused") // used via reflection
    public final User[] users = {
        new User("John", 20),
        new User("Jane", 21),
        new User("Jack", 22)
    };
  }

  /** Row in the "users" table. */
  public static class User {
    public final String name;
    public final int age;

    User(String name, int age) {
      this.name = name;
      this.age = age;
    }
  }

  /** Java object that will, via reflection, become create the "task" schema. */
  public static class TaskSchema {
    @Override public String toString() {
      return "TaskSchema";
    }

    /** Array that will, via reflection, become the "tasks" table in the
     * "task" schema. */
    @SuppressWarnings("unused") // used via reflection
    public final Task[] tasks = {
        new Task("Buy milk", false),
        new Task("Buy eggs", false),
        new Task("Buy bread", false)
    };
  }


  /** Row in the "tasks" table. */
  public static class Task {
    public final String name;
    public final boolean completed;

    Task(String name, boolean completed) {
      this.name = name;
      this.completed = completed;
    }
  }
}

// End CalciteTest.java
