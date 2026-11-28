package com.schoomano.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** B43: cells are escaped, and anything a spreadsheet would run as a formula is defused. */
class CsvCellsTest {

    @Test
    void escapesAndDefusesFormulas() {
        assertEquals("plain", CsvCells.cell("plain"));
        assertEquals("", CsvCells.cell(null));
        assertEquals("'=1+1", CsvCells.cell("=1+1"));
        assertEquals("'@SUM(A1)", CsvCells.cell("@SUM(A1)"));
        assertEquals("'-5", CsvCells.cell("-5"));
        assertEquals("'+91", CsvCells.cell("+91"));
        assertEquals("\"a,b\"", CsvCells.cell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvCells.cell("say \"hi\""));
        assertEquals("\"'=a,b\"", CsvCells.cell("=a,b"));
        assertEquals("\"line1\nline2\"", CsvCells.cell("line1\nline2"));
        assertEquals("a,,c\r\n", CsvCells.line(List.of("a", "", "c")));
    }
}
