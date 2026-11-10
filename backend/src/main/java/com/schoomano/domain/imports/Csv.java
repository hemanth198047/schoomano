package com.schoomano.domain.imports;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 CSV as exported by spreadsheet tools and legacy school systems: optional UTF-8 BOM, quoted fields
 * with doubled quotes, commas and newlines inside quotes, CRLF or LF line ends, blank lines ignored.
 */
public final class Csv {

    private Csv() {
    }

    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;
        int line = 1;
        int start = !text.isEmpty() && text.charAt(0) == '﻿' ? 1 : 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
            } else if (c == '"' && !fieldStarted) {
                quoted = true;
                fieldStarted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
                if (!(row.size() == 1 && row.get(0).isEmpty())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
                line++;
            } else {
                field.append(c);
                fieldStarted = true;
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("A quoted value is not closed (from line " + line + ").");
        }
        if (fieldStarted || !row.isEmpty()) {
            row.add(field.toString());
            if (!(row.size() == 1 && row.get(0).isEmpty())) {
                rows.add(row);
            }
        }
        return rows;
    }
}
