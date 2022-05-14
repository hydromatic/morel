package net.hydromatic.morel;

import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.ForeignValue;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

class ProgrammaticShellTest {

    private static Map<String, ForeignValue> foreignValueMap = Calcite
            .withDataSets(BuiltInDataSet.DICTIONARY)
            .foreignValues();

    @Test
    void run() {
        ProgrammaticShell shell = new ProgrammaticShell(foreignValueMap);

        StringWriter out = new StringWriter();
        PrintWriter writer = new PrintWriter(out);

        shell.run("scott;", writer, true);
        writer.flush();

        System.out.println(out);
    }
}
