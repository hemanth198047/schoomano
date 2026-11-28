package com.schoomano.platform;

import java.util.List;

/**
 * Builds CSV text that is safe to open in a spreadsheet. A cell that starts with =, +, -, @, a tab or a carriage return
 * would be run as a formula by Excel or Sheets, so it is prefixed with an apostrophe; quotes, commas and line breaks are
 * escaped as usual. Lines end with CRLF.
 */
public final class CsvCells {

    private CsvCells() { }

    public static String cell(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            v = '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    public static String line(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cell(cells.get(i)));
        }
        return sb.append("\r\n").toString();
    }
}
