package cadencefinder.corpus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HarmonyTsv {

    private HarmonyTsv() {
    }

    public static List<Harmony> parse(String content, String source) {
        String[] lines = content.split("\r?\n");
        Map<String, Integer> columns = new HashMap<>();
        String[] header = lines[0].split("\t", -1);
        for (int i = 0; i < header.length; i++) {
            columns.put(header[i], i);
        }
        List<Harmony> harmonies = new ArrayList<>();
        for (int n = 1; n < lines.length; n++) {
            if (lines[n].isBlank()) {
                continue;
            }
            Row row = new Row(lines[n].split("\t", -1), columns, source, n + 1);
            harmonies.add(new Harmony(
                    row.integer("mc"),
                    row.quarters("mc_onset"),
                    row.integer("mn"),
                    row.quarters("mn_onset"),
                    row.text("duration_qb").isEmpty() ? 0 : Double.parseDouble(row.text("duration_qb")),
                    row.barQuarters("timesig"),
                    row.text("numeral"),
                    row.text("form"),
                    row.text("figbass"),
                    row.text("relativeroot"),
                    row.text("chord"),
                    row.text("chord_type"),
                    row.text("cadence"),
                    row.text("globalkey")));
        }
        harmonies.sort(Comparator.comparingInt(Harmony::mc).thenComparingDouble(Harmony::mcOnsetQuarters));
        return harmonies;
    }

    private record Row(String[] cells, Map<String, Integer> columns, String source, int line) {

        String text(String column) {
            Integer index = columns.get(column);
            if (index == null) {
                throw new IllegalArgumentException(source + ": missing column " + column);
            }
            return index < cells.length ? cells[index] : "";
        }

        int integer(String column) {
            try {
                return Integer.parseInt(text(column));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(source + ":" + line + ": bad " + column + " '" + text(column) + "'", e);
            }
        }

        double quarters(String column) {
            return 4 * fraction(text(column));
        }

        double barQuarters(String column) {
            String[] parts = text(column).split("/");
            return 4.0 * Integer.parseInt(parts[0]) / Integer.parseInt(parts[1]);
        }

        private double fraction(String value) {
            int slash = value.indexOf('/');
            if (slash < 0) {
                return Double.parseDouble(value);
            }
            return Double.parseDouble(value.substring(0, slash)) / Double.parseDouble(value.substring(slash + 1));
        }
    }
}
