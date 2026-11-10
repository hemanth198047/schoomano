package com.schoomano.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class XlsxTest {

    static byte[] zip(Map<String, String> parts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (var e : parts.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    static final String SHARED = """
        <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <si><t>Admission No</t></si><si><t>First Name</t></si><si><r><t>Ka</t></r><r><t>vya</t></r></si>
        </sst>""";

    @Test
    void readsSharedRichAndInlineStringsNumbersAndGaps() throws Exception {
        String sheet = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
              <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="D1" t="inlineStr"><is><t>DOB</t></is></c></row>
              <row r="2"><c r="A2"><v>1042</v></c><c r="B2" t="s"><v>2</v></c><c r="D2"><v>42436</v></c></row>
              <row r="3"><c r="A3" t="s"></c></row>
            </sheetData></worksheet>""";
        byte[] file = zip(Map.of("xl/sharedStrings.xml", SHARED, "xl/worksheets/sheet1.xml", sheet));
        assertTrue(Xlsx.looksLikeXlsx(file));
        assertEquals(List.of(List.of("Admission No", "First Name", "", "DOB"), List.of("1042", "Kavya", "", "42436")),
            Xlsx.firstSheet(file));
    }

    @Test
    void externalEntitiesAreNotResolved() throws Exception {
        String sheet = """
            <?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>
            <worksheet><sheetData><row><c t="inlineStr"><is><t>&e;</t></is></c></row></sheetData></worksheet>""";
        byte[] file = zip(Map.of("xl/worksheets/sheet1.xml", sheet));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Xlsx.firstSheet(file));
        assertFalse(e.getMessage().contains("root:"));
    }

    @Test
    void columnLettersMapToIndexes() {
        assertEquals(0, Xlsx.columnIndex("A1"));
        assertEquals(25, Xlsx.columnIndex("Z9"));
        assertEquals(27, Xlsx.columnIndex("AB12"));
    }

    @Test
    void csvIsNotMistakenForXlsx() {
        assertFalse(Xlsx.looksLikeXlsx("a,b\n".getBytes(StandardCharsets.UTF_8)));
    }
}
