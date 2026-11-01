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
package net.hydromatic.morel.eval.code;

import com.google.common.collect.ImmutableList;
import java.util.function.BiConsumer;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Describer;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.eval.Stack;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.foreign.PreparedPlan;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.foreign.SparkConnections;

/**
 * Implementations of built-in functions and values in the {@code Spark}
 * structure.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class SparkCodes {
  private SparkCodes() {}

  /** Registers the implementations in this class. */
  public static void register(BiConsumer<BuiltIn, Object> c) {
    // lint: sort until '#}' where '##c\.accept\(BuiltIn' erase 'c\.'
    c.accept(BuiltIn.SPARK_CATALOG, SPARK_CATALOG);
    c.accept(BuiltIn.SPARK_CLOSE, SPARK_CLOSE);
    c.accept(BuiltIn.SPARK_CONNECT, SPARK_CONNECT);
    c.accept(BuiltIn.SPARK_CONNECT_DEFAULT, SPARK_CONNECT_DEFAULT);
    c.accept(BuiltIn.SPARK_EXECUTE, SPARK_EXECUTE);
    c.accept(BuiltIn.SPARK_PREPARE, SPARK_PREPARE);
    c.accept(BuiltIn.SPARK_REMOTE, SPARK_REMOTE);
    c.accept(BuiltIn.SPARK_TO_STRING, SPARK_TO_STRING);
    c.accept(BuiltIn.SPARK_USING, SPARK_USING);
  }

  /** @see BuiltIn#SPARK_CATALOG */
  private static final Applicable1 SPARK_CATALOG =
      new BaseApplicable1<Object, SparkBackend.Connection>(
          BuiltIn.SPARK_CATALOG) {
        @Override
        public Object apply(SparkBackend.Connection connection) {
          return SparkConnections.catalog(connection);
        }
      };

  /** @see BuiltIn#SPARK_CLOSE */
  private static final Applicable1 SPARK_CLOSE =
      new BaseApplicable1<Unit, SparkBackend.Connection>(BuiltIn.SPARK_CLOSE) {
        @Override
        public Unit apply(SparkBackend.Connection connection) {
          SparkConnections.close(connection);
          return Unit.INSTANCE;
        }
      };

  /**
   * The resolver replaces {@code prepare (c, f)} with the plan of {@code f}, so
   * this runs only where {@code prepare} is used as a value rather than applied
   * to a function expression -- passed to {@code map}, say -- which it cannot
   * serve, because by then the function is a closure and its parse tree is
   * gone.
   *
   * @see BuiltIn#SPARK_PREPARE
   */
  private static final Applicable SPARK_PREPARE =
      new ApplicableImpl(BuiltIn.SPARK_PREPARE) {
        @Override
        public Object apply(Stack stack, Object arg) {
          throw new SparkBackend.SparkException(
              "UNSUPPORTED",
              "Spark.prepare must be applied to a function expression");
        }
      };

  /** @see BuiltIn#SPARK_TO_STRING */
  private static final Applicable1 SPARK_TO_STRING =
      new BaseApplicable1<String, PreparedPlan>(BuiltIn.SPARK_TO_STRING) {
        @Override
        public String apply(PreparedPlan plan) {
          return plan.text();
        }
      };

  /** @see BuiltIn#SPARK_EXECUTE */
  private static final Applicable SPARK_EXECUTE = new SparkExecute(Pos.ZERO);

  /** Implements {@link #SPARK_EXECUTE}. */
  private static class SparkExecute
      extends BasePositionedApplicable2<Object, PreparedPlan, Object> {
    SparkExecute(Pos pos) {
      super(BuiltIn.SPARK_EXECUTE, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new SparkExecute(pos);
    }

    @Override
    public Object apply(PreparedPlan plan, Object arg) {
      try {
        return plan.execute(arg);
      } catch (SparkBackend.SparkException e) {
        throw Codes.sparkException(e, pos);
      }
    }
  }

  /**
   * Returns a function that executes a plan, which is the value of {@code
   * remote (c, f)}: a function of the same type as f, whose work is done on the
   * connection.
   *
   * <p>It is identified as {@code execute} rather than as {@code remote},
   * because executing is what it does; the resolver has already done the
   * preparing.
   */
  public static Applicable1 remote(PreparedPlan plan, Pos pos) {
    return new BaseApplicable1<Object, Object>(BuiltIn.SPARK_EXECUTE) {
      @Override
      public Describer describe(Describer describer) {
        // The plan a query was pushed down to is what a reader of a plan
        // description wants to see, as the plan a query was pushed down to
        // Calcite is; so this says "spark(plan ...)" where that says
        // "calcite(plan ...)", rather than naming the function that runs it.
        return describer.start("spark", plan::describe);
      }

      @Override
      public Object apply(Object arg) {
        try {
          return plan.execute(arg);
        } catch (SparkBackend.SparkException e) {
          throw Codes.sparkException(e, pos);
        }
      }
    };
  }

  /**
   * The resolver replaces {@code remote (c, f)} with the plan of {@code f} and
   * a call that executes it, so this runs only where {@code remote} is used as
   * a value rather than applied to a function expression.
   *
   * @see BuiltIn#SPARK_REMOTE
   */
  private static final Applicable SPARK_REMOTE =
      new ApplicableImpl(BuiltIn.SPARK_REMOTE) {
        @Override
        public Object apply(Stack stack, Object arg) {
          throw new SparkBackend.SparkException(
              "UNSUPPORTED",
              "Spark.remote must be applied to a function expression");
        }
      };

  /** @see BuiltIn#SPARK_CONNECT */
  private static final Applicable SPARK_CONNECT = new SparkConnect(Pos.ZERO);

  /** Implements {@link #SPARK_CONNECT}. */
  private static class SparkConnect extends BasePositionedApplicable {
    SparkConnect(Pos pos) {
      super(BuiltIn.SPARK_CONNECT, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new SparkConnect(pos);
    }

    @Override
    public Object apply(Stack stack, Object arg) {
      try {
        return SparkConnections.open(stack.session, (String) arg);
      } catch (SparkBackend.SparkException e) {
        throw Codes.sparkException(e, pos);
      }
    }
  }

  /** @see BuiltIn#SPARK_CONNECT_DEFAULT */
  private static final Applicable SPARK_CONNECT_DEFAULT =
      new SparkConnectDefault(Pos.ZERO);

  /** Implements {@link #SPARK_CONNECT_DEFAULT}. */
  private static class SparkConnectDefault extends BasePositionedApplicable {
    SparkConnectDefault(Pos pos) {
      super(BuiltIn.SPARK_CONNECT_DEFAULT, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new SparkConnectDefault(pos);
    }

    @Override
    public Object apply(Stack stack, Object arg) {
      try {
        return SparkConnections.openDefault(stack.session);
      } catch (SparkBackend.SparkException e) {
        throw Codes.sparkException(e, pos);
      }
    }
  }

  /** @see BuiltIn#SPARK_USING */
  private static final Applicable SPARK_USING = new SparkUsing(Pos.ZERO);

  /**
   * Implements {@link #SPARK_USING}. Applied to a function, returns a function
   * that opens the default connection for each call.
   */
  private static class SparkUsing extends BasePositionedApplicable {
    SparkUsing(Pos pos) {
      super(BuiltIn.SPARK_USING, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new SparkUsing(pos);
    }

    @Override
    public Object apply(Stack stack, Object arg) {
      final Session session = stack.session;
      @SuppressWarnings("unchecked")
      final Applicable1<Object, Object> f = (Applicable1<Object, Object>) arg;
      return new BaseApplicable1<Object, Object>(BuiltIn.SPARK_USING) {
        @Override
        public Object apply(Object x) {
          final SparkConnections.ConnectionRecord record;
          try {
            record = SparkConnections.openDefault(session);
          } catch (SparkBackend.SparkException e) {
            throw Codes.sparkException(e, pos);
          }
          try {
            return f.apply(ImmutableList.of(record.connection, x));
          } finally {
            SparkConnections.close(record.connection);
          }
        }
      };
    }
  }
}

// End SparkCodes.java
