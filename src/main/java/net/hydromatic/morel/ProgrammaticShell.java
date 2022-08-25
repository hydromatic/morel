package net.hydromatic.morel;

import com.google.common.collect.ImmutableMap;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.CompiledStatement;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.parse.ParseException;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.MorelException;

import java.io.PrintWriter;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;


/**
 * Programmatic, non-interactive shell for Morel.
 * <p>
 * Meant to be used for programmatic evaluation of expressions.
 * Useful for embedding Morel in other programs, and for testing.
 * <p>
 * The shell contains a persistent environment/foreign value map,
 * along with a cache of compiled statements and their results,
 * which reduces the cost of repeated invocations.
 */
public class ProgrammaticShell implements Session.Shell {
    private Environment env0;
    private Map<String, ForeignValue> foreignValueMap;

    private final Session session = new Session();
    private TypeSystem typeSystem = new TypeSystem();

    ProgrammaticShell(Map<String, ForeignValue> foreignValueMap) {
        this.foreignValueMap = ImmutableMap.copyOf(foreignValueMap);
        this.env0 = makeEnv(typeSystem, foreignValueMap);
    }

    public Map<String, ForeignValue> getForeignValueMap() {
        return foreignValueMap;
    }

    public void setForeignValueMap(Map<String, ForeignValue> foreignValueMap) {
        this.foreignValueMap = ImmutableMap.copyOf(foreignValueMap);
        this.env0 = makeEnv(typeSystem, foreignValueMap);
    }

    public void setTypeSystem(TypeSystem typeSystem) {
        this.typeSystem = typeSystem;
        this.env0 = makeEnv(typeSystem, foreignValueMap);
    }

    private static Environment makeEnv(TypeSystem typeSystem, Map<String, ForeignValue> foreignValueMap) {
        return Environments.env(typeSystem, foreignValueMap);
    }

    /**************************************************************/

    public void run(String code, PrintWriter out, boolean echo) {
        final MorelParserImpl parser = new MorelParserImpl(new StringReader(code));
        final Consumer<String> outLines = out::println;

        while (true) {
            try {
                parser.zero("stdIn");
                final AstNode statement = parser.statementSemicolonOrEof();

                if (statement == null && code.endsWith("\n")) {
                    code = code.substring(0, code.length() - 1);
                }

                if (echo) {
                    outLines.accept(code);
                }

                if (statement == null) {
                    break;
                }

                session.withShell(this, outLines, session1 ->
                        command(statement, outLines));
            } catch (ParseException e) {
                final String message = e.getMessage();

                if (message.startsWith("Encountered \"<EOF>\" ")) {
                    break;
                }

                if (echo) {
                    outLines.accept(code);
                }

                outLines.accept(message);
                if (code.length() == 0) {
                    // If we consumed no input, we're not making progress, so we'll
                    // never finish. Abort.
                    break;
                }
            }
        }
    }

    private void command(AstNode statement, Consumer<String> outLines) {
        try {
            final Map<String, Binding> outBindings = new LinkedHashMap<>();
            final Environment env = env0.bindAll(outBindings.values());

            final CompiledStatement compiled =
                    Compiles.prepareStatement(typeSystem, session, env,
                            statement, null, e -> appendToOutput(e, outLines));

            final List<Binding> bindings = new ArrayList<>();
            compiled.eval(session, env, outLines, bindings::add);
            bindings.forEach(b -> outBindings.put(b.id.name, b));
        } catch (Codes.MorelRuntimeException e) {
            appendToOutput(e, outLines);
        }
    }

    private void appendToOutput(MorelException e, Consumer<String> outLines) {
        final StringBuilder buf = new StringBuilder();
        session.handle(e, buf);
        outLines.accept(buf.toString());
    }

    /**************************************************************/

    @Override
    public void use(String fileName, Pos pos) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void handle(RuntimeException e, StringBuilder buf) {
        if (e instanceof MorelException) {
            final MorelException me = (MorelException) e;
            me.describeTo(buf)
                    .append("\n")
                    .append("  raised at: ");
            me.pos().describeTo(buf);
        } else {
            buf.append(e);
        }
    }
}
