package com.schoomano.imports;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Minimal reader for the first worksheet of an .xlsx file (Office Open XML), enough for exports from school
 * systems and spreadsheets: shared strings, inline strings, numbers and booleans. No formulas are evaluated
 * (the cached value is used). Hardened for untrusted uploads: no DTDs or external entities, and limits on
 * the number of parts and on decompressed size (zip-bomb guard).
 */
public final class Xlsx {

    private static final long MAX_PART_BYTES = 50L * 1024 * 1024;
    private static final int MAX_ENTRIES = 500;

    private Xlsx() {
    }

    public static boolean looksLikeXlsx(byte[] bytes) {
        return bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
    }

    public static List<List<String>> firstSheet(byte[] bytes) {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            int entries = 0;
            while ((e = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IllegalArgumentException("The spreadsheet has too many parts.");
                }
                String name = e.getName();
                if (name.equals("xl/sharedStrings.xml") || name.equals("xl/workbook.xml")
                    || name.equals("xl/_rels/workbook.xml.rels") || name.startsWith("xl/worksheets/sheet")) {
                    parts.put(name, readLimited(zip));
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("The file is not a readable .xlsx spreadsheet.");
        }
        try {
            List<String> shared = parts.containsKey("xl/sharedStrings.xml")
                ? sharedStrings(parts.get("xl/sharedStrings.xml")) : List.of();
            byte[] sheet = parts.get(firstSheetPath(parts));
            if (sheet == null) {
                throw new IllegalArgumentException("The spreadsheet has no worksheet.");
            }
            return rows(sheet, shared);
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("The spreadsheet could not be read.");
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_PART_BYTES) {
                throw new IllegalArgumentException("The spreadsheet is too large once unpacked.");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f.createXMLStreamReader(new ByteArrayInputStream(xml));
    }

    /** The first sheet listed in the workbook, resolved through its relationship; falls back to sheet1.xml. */
    private static String firstSheetPath(Map<String, byte[]> parts) throws XMLStreamException {
        String fallback = "xl/worksheets/sheet1.xml";
        byte[] wb = parts.get("xl/workbook.xml");
        byte[] rels = parts.get("xl/_rels/workbook.xml.rels");
        if (wb == null || rels == null) {
            return fallback;
        }
        String relId = null;
        XMLStreamReader r = reader(wb);
        while (r.hasNext() && relId == null) {
            if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("sheet")) {
                for (int i = 0; i < r.getAttributeCount(); i++) {
                    if (r.getAttributeLocalName(i).equals("id")) {
                        relId = r.getAttributeValue(i);
                    }
                }
            }
        }
        if (relId == null) {
            return fallback;
        }
        XMLStreamReader rr = reader(rels);
        while (rr.hasNext()) {
            if (rr.next() == XMLStreamConstants.START_ELEMENT && rr.getLocalName().equals("Relationship")
                && relId.equals(rr.getAttributeValue(null, "Id"))) {
                String target = rr.getAttributeValue(null, "Target");
                String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                return parts.containsKey(path) ? path : fallback;
            }
        }
        return fallback;
    }

    private static List<String> sharedStrings(byte[] xml) throws XMLStreamException {
        List<String> out = new ArrayList<>();
        XMLStreamReader r = reader(xml);
        StringBuilder current = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("si")) {
                current = new StringBuilder();
            } else if (ev == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("t") && current != null) {
                current.append(r.getElementText());
            } else if (ev == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("si") && current != null) {
                out.add(current.toString());
                current = null;
            }
        }
        return out;
    }

    private static List<List<String>> rows(byte[] xml, List<String> shared) throws XMLStreamException {
        List<List<String>> rows = new ArrayList<>();
        XMLStreamReader r = reader(xml);
        List<String> row = null;
        String type = null;
        int col = 0;
        StringBuilder value = new StringBuilder();
        boolean inCell = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> row = new ArrayList<>();
                    case "c" -> {
                        type = r.getAttributeValue(null, "t");
                        String ref = r.getAttributeValue(null, "r");
                        col = ref == null ? (row == null ? 0 : row.size()) : columnIndex(ref);
                        value.setLength(0);
                        inCell = true;
                    }
                    case "v", "t" -> {
                        if (inCell) {
                            value.append(r.getElementText());
                        }
                    }
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (r.getLocalName().equals("c") && row != null) {
                    String v = value.toString();
                    if ("s".equals(type) && !v.isEmpty()) {
                        int idx = Integer.parseInt(v.trim());
                        v = idx < shared.size() ? shared.get(idx) : "";
                    } else if ("b".equals(type)) {
                        v = "1".equals(v) ? "TRUE" : "FALSE";
                    } else if (type == null || "n".equals(type)) {
                        v = v.endsWith(".0") ? v.substring(0, v.length() - 2) : v;
                    }
                    while (row.size() < col) {
                        row.add("");
                    }
                    if (row.size() == col) {
                        row.add(v);
                    } else {
                        row.set(col, v);
                    }
                    inCell = false;
                } else if (r.getLocalName().equals("row") && row != null) {
                    if (row.stream().anyMatch(s -> !s.isBlank())) {
                        rows.add(row);
                    }
                    row = null;
                }
            }
        }
        return rows;
    }

    static int columnIndex(String cellRef) {
        int n = 0;
        for (char c : cellRef.toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                n = n * 26 + (c - 'A' + 1);
            } else {
                break;
            }
        }
        return n - 1;
    }
}
