package net.hydromatic.morel;

import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.ForeignValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

class ProgrammaticShellTest {

    private final Map<String, ForeignValue> foreignValueMap =
            Calcite.withDataSets(BuiltInDataSet.DICTIONARY).foreignValues();

    @Test
    void run() {
        ProgrammaticShell shell = new ProgrammaticShell(foreignValueMap);

        StringWriter out = new StringWriter();
        PrintWriter writer = new PrintWriter(out);

        String expected = "val it = {bonus=<relation>,dept=<relation>,emp=<relation>,salgrade=<relation>}\n" +
                          "  : {bonus:{comm:real, ename:string, job:string, sal:real} list, dept:{deptno:int, dname:string, loc:string} list, emp:{comm:real, deptno:int, empno:int, ename:string, hiredate:string, job:string, mgr:int, sal:real} list, salgrade:{grade:int, hisal:real, losal:real} list}\n";

        shell.run("scott;", writer, false);
        writer.flush();

        System.out.println("Out: " + out);

        // Handle CRLF vs LF differences so that test passes on Windows.
        String expectedNormalized = expected.replaceAll("\\s+", " ").trim();
        String outNormalized = out.toString().replaceAll("\\s+", " ").trim();
        Assertions.assertEquals(expectedNormalized, outNormalized);
    }
}
