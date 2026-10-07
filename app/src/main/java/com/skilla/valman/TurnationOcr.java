package com.skilla.valman;

import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OCR helper for the photographed weekly maintenance rota.
 *
 * v0.3.0:
 * - employee-name matching works with both "Nome Cognome" and "Cognome Nome";
 * - the row is reconstructed geometrically, cell by cell;
 * - each day chooses the nearest valid shift cluster to the employee row instead
 *   of concatenating a broad horizontal band (which previously mixed adjacent rows);
 * - compound codes such as G REP can be rebuilt even when ML Kit splits them;
 * - the original image is never auto-published: output is always a review draft.
 */
public class TurnationOcr {
    public static class Row {
        public String userId;
        public String name;
        public String raw = "";
        public String[] codes = new String[]{"", "", "", "", "", "", ""};
        public int recognized = 0;
        public boolean nameMatched = false;
    }

    public interface Callback {
        void onSuccess(List<Row> rows, String rawText, boolean headerFound, String detectedWeekStart);
        void onError(String message);
    }

    private static class El {
        String text;
        Rect box;
        int cx;
        int cy;
        El(String text, Rect box) {
            this.text = text == null ? "" : text;
            this.box = box;
            this.cx = box == null ? 0 : box.centerX();
            this.cy = box == null ? 0 : box.centerY();
        }
    }

    private static class Ln {
        String text;
        Rect box;
        int cy;
        Ln(String text, Rect box) {
            this.text = text == null ? "" : text;
            this.box = box;
            this.cy = box == null ? 0 : box.centerY();
        }
    }

    private static class CellHit {
        String code = "";
        int y = 0;
        int distance = Integer.MAX_VALUE;
    }

    private static final Pattern SHIFT_PATTERN = Pattern.compile(
            "(?<![A-Z0-9])(?:G\\s*REP|PAR|R\\s*E\\s*P|R\\s*P|N\\s*C|F|G|1|2|3)(?![A-Z0-9])",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d{1,2})\\s*[/.-]\\s*(\\d{1,2})\\s*[/.-]\\s*(20\\d{2})(?!\\d)");

    public static void analyze(Context context, Uri uri, JSONArray users, final Callback callback) {
        try {
            InputImage image = InputImage.fromFilePath(context, uri);
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            recognizer.process(image)
                    .addOnSuccessListener(result -> {
                        try {
                            List<El> elements = flatten(result);
                            List<Ln> lines = flattenLines(result);
                            List<Integer> dayCenters = findDayCenters(elements);
                            List<Row> rows = parseUsers(elements, lines, dayCenters, users);
                            String detected = detectWeekStart(result.getText());
                            boolean tableReadable = dayCenters.size() >= 7 || hasUsefulRow(rows);
                            callback.onSuccess(rows, result.getText(), tableReadable, detected);
                        } catch (Exception e) {
                            callback.onError("Testo letto, ma non riesco a interpretare la tabella: " + e.getMessage());
                        } finally {
                            recognizer.close();
                        }
                    })
                    .addOnFailureListener(e -> {
                        recognizer.close();
                        callback.onError("OCR non riuscito: " + (e.getMessage() == null ? "errore sconosciuto" : e.getMessage()));
                    });
        } catch (Exception e) {
            callback.onError("Immagine non leggibile: " + e.getMessage());
        }
    }

    private static boolean hasUsefulRow(List<Row> rows) {
        for (Row r : rows) if (r.recognized >= 4) return true;
        return false;
    }

    private static List<El> flatten(Text text) {
        List<El> out = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    Rect r = element.getBoundingBox();
                    if (r != null) out.add(new El(element.getText(), r));
                }
            }
        }
        return out;
    }

    private static List<Ln> flattenLines(Text text) {
        List<Ln> out = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect r = line.getBoundingBox();
                if (r != null) out.add(new Ln(line.getText(), r));
            }
        }
        return out;
    }

    private static List<Integer> findDayCenters(List<El> elements) {
        Set<String> days = new HashSet<>();
        Collections.addAll(days, "LUN", "MAR", "MER", "GIO", "VEN", "SAB", "DOM");
        List<El> candidates = new ArrayList<>();
        for (El e : elements) {
            String n = norm(e.text).replace(".", "").replace(":", "");
            if (days.contains(n)) candidates.add(e);
        }
        if (candidates.size() < 7) return new ArrayList<>();

        // Choose the densest horizontal header band. A photographed sheet can be
        // slightly slanted, so the tolerance is intentionally wider than one glyph.
        List<El> bestBand = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            El anchor = candidates.get(i);
            List<El> band = new ArrayList<>();
            int tol = Math.max(34, anchor.box.height() * 3);
            for (El e : candidates) if (Math.abs(e.cy - anchor.cy) <= tol) band.add(e);
            if (band.size() > bestBand.size()) bestBand = band;
        }
        if (bestBand.size() < 7) bestBand = candidates;
        Collections.sort(bestBand, Comparator.comparingInt(a -> a.cx));

        List<Integer> centers = new ArrayList<>();
        for (El e : bestBand) {
            boolean duplicate = false;
            for (Integer x : centers) {
                if (Math.abs(x - e.cx) < Math.max(8, e.box.width() / 2)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) centers.add(e.cx);
            // The form can show an eighth column for the following Monday.
            // The service week is always the first seven columns.
            if (centers.size() == 7) break;
        }
        return centers;
    }

    private static List<Row> parseUsers(List<El> elements, List<Ln> lines, List<Integer> dayCenters, JSONArray users) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.optJSONObject(i);
            if (u == null || !u.optBoolean("enabled", true)) continue;

            Row row = new Row();
            row.userId = u.optString("id");
            row.name = u.optString("name");
            List<String> nameKeys = nameCandidates(row.name, row.userId);

            // Whole-line path: when ML Kit gives us the complete printed row,
            // this is still the most reliable source.
            Ln line = findNameLine(lines, nameKeys);
            if (line != null) {
                row.nameMatched = true;
                row.raw = line.text;
                List<String> fromLine = extractShiftCodes(line.text);
                if (fromLine.size() >= 5) {
                    for (int d = 0; d < 7 && d < fromLine.size(); d++) row.codes[d] = fromLine.get(d);
                }
            }

            // Geometric reconstruction. This is the important v0.3 change:
            // use the target employee Y as an anchor and solve each day cell on its
            // own. We no longer build a broad row band that can include the rows
            // immediately above and below.
            El nameEl = findNameElement(elements, nameKeys);
            if (nameEl != null) {
                row.nameMatched = true;
                if (row.raw.isEmpty()) row.raw = nameEl.text;

                Row geo = new Row();
                if (dayCenters.size() >= 7) {
                    fillByNearestCellClusters(geo, elements, nameEl, dayCenters);
                } else {
                    fillSequentialTight(geo, elements, nameEl);
                }

                for (int d = 0; d < 7; d++) {
                    if (row.codes[d].isEmpty() && !geo.codes[d].isEmpty()) row.codes[d] = geo.codes[d];
                }
            }

            for (String c : row.codes) if (!c.isEmpty()) row.recognized++;
            rows.add(row);
        }
        return rows;
    }

    /**
     * Reconstruct one employee row by selecting a valid OCR cluster inside each
     * day column. The selected cluster must be the one closest to the employee's
     * vertical position. This avoids mixing adjacent rows.
     */
    private static void fillByNearestCellClusters(Row row, List<El> all, El nameEl, List<Integer> centers) {
        int expectedY = nameEl.cy;
        boolean anchoredByCell = false;

        for (int d = 0; d < 7; d++) {
            int left = d == 0
                    ? centers.get(0) - (centers.get(1) - centers.get(0)) / 2
                    : (centers.get(d - 1) + centers.get(d)) / 2;
            int right = d == 6
                    ? centers.get(6) + (centers.get(6) - centers.get(5)) / 2
                    : (centers.get(d) + centers.get(d + 1)) / 2;

            // Before the first reliable shift cell, allow a wider search because
            // the name box can sit a few pixels higher/lower than the tiny shift text.
            int maxY = anchoredByCell ? Math.max(13, nameEl.box.height() + 5)
                                      : Math.max(27, nameEl.box.height() * 2);
            CellHit hit = bestCellHit(all, left, right, expectedY, maxY);
            if (hit != null && !hit.code.isEmpty()) {
                row.codes[d] = hit.code;
                if (!anchoredByCell) {
                    expectedY = hit.y;
                    anchoredByCell = true;
                } else {
                    // Smoothly follow the slight perspective/curvature across the page.
                    expectedY = (expectedY * 3 + hit.y) / 4;
                }
            }
        }
    }

    private static CellHit bestCellHit(List<El> all, int left, int right, int expectedY, int maxY) {
        List<El> inCell = new ArrayList<>();
        for (El e : all) {
            if (e.cx >= left && e.cx < right && Math.abs(e.cy - expectedY) <= maxY + 12) inCell.add(e);
        }
        if (inCell.isEmpty()) return null;

        Collections.sort(inCell, Comparator.comparingInt(a -> a.cy));
        CellHit best = null;

        // Build a small same-line cluster around every OCR element. This handles
        // cases like "G" + "REP" or "N" + "C" being returned separately.
        for (El seed : inCell) {
            int sameLineTol = Math.max(5, seed.box.height() / 2 + 2);
            List<El> cluster = new ArrayList<>();
            for (El e : inCell) {
                if (Math.abs(e.cy - seed.cy) <= sameLineTol) cluster.add(e);
            }
            Collections.sort(cluster, Comparator.comparingInt(a -> a.cx));
            StringBuilder phrase = new StringBuilder();
            int ySum = 0;
            for (El e : cluster) {
                if (phrase.length() > 0) phrase.append(' ');
                phrase.append(e.text);
                ySum += e.cy;
            }
            String code = normalizeShiftCode(phrase.toString());
            if (code.isEmpty()) continue;
            int clusterY = ySum / Math.max(1, cluster.size());
            int dist = Math.abs(clusterY - expectedY);
            if (dist > maxY) continue;

            if (best == null || dist < best.distance || (dist == best.distance && code.length() > best.code.length())) {
                best = new CellHit();
                best.code = code;
                best.y = clusterY;
                best.distance = dist;
            }
        }

        // Fallback to a single element if clustering did not produce a code.
        if (best == null) {
            for (El e : inCell) {
                String code = normalizeShiftCode(e.text);
                if (code.isEmpty()) continue;
                int dist = Math.abs(e.cy - expectedY);
                if (dist <= maxY && (best == null || dist < best.distance)) {
                    best = new CellHit();
                    best.code = code;
                    best.y = e.cy;
                    best.distance = dist;
                }
            }
        }
        return best;
    }

    private static void fillSequentialTight(Row row, List<El> elements, El nameEl) {
        List<El> sameRow = new ArrayList<>();
        int tol = Math.max(15, nameEl.box.height() + 4);
        for (El e : elements) {
            if (e.cx > nameEl.cx && Math.abs(e.cy - nameEl.cy) <= tol) sameRow.add(e);
        }
        Collections.sort(sameRow, Comparator.comparingInt(a -> a.cx));

        List<String> codes = new ArrayList<>();
        for (El e : sameRow) {
            String c = normalizeShiftCode(e.text);
            if (!c.isEmpty()) codes.add(c);
        }
        for (int i = 0; i < 7 && i < codes.size(); i++) row.codes[i] = codes.get(i);
    }

    private static List<String> extractShiftCodes(String raw) {
        List<String> out = new ArrayList<>();
        String n = norm(raw).replace('°', ' ');
        Matcher m = SHIFT_PATTERN.matcher(n);
        while (m.find()) {
            String code = normalizeShiftCode(m.group());
            if (!code.isEmpty()) out.add(code);
        }
        return out;
    }

    private static Ln findNameLine(List<Ln> lines, List<String> keys) {
        if (keys == null || keys.isEmpty()) return null;
        Ln best = null;
        double bestScore = 0.0;

        for (Ln line : lines) {
            String lc = compact(line.text);
            String[] tokens = norm(line.text).split("[^A-Z0-9]+");
            for (String rawKey : keys) {
                String key = compact(rawKey);
                if (key.length() < 3) continue;
                double weight = 1.0 + Math.min(0.30, key.length() * 0.02);

                if (lc.contains(key)) {
                    double score = weight;
                    if (score > bestScore) { bestScore = score; best = line; }
                }
                for (String t : tokens) {
                    String tc = compact(t);
                    if (tc.length() < 3) continue;
                    double s = similarity(key, tc) * weight;
                    if (s > bestScore) { bestScore = s; best = line; }
                }
            }
        }
        return bestScore >= 0.82 ? best : null;
    }

    private static El findNameElement(List<El> elements, List<String> keys) {
        if (keys == null || keys.isEmpty()) return null;
        El best = null;
        double bestScore = 0.0;
        for (El e : elements) {
            String v = compact(e.text);
            if (v.isEmpty()) continue;
            for (String rawKey : keys) {
                String key = compact(rawKey);
                if (key.length() < 3) continue;
                double weight = 1.0 + Math.min(0.30, key.length() * 0.02);

                if (v.equals(key) || v.contains(key) || (key.contains(v) && v.length() >= 4)) {
                    double score = weight;
                    if (score > bestScore) { bestScore = score; best = e; }
                } else if (v.length() >= 3) {
                    double s = similarity(key, v) * weight;
                    if (s > bestScore) { bestScore = s; best = e; }
                }
            }
        }
        return bestScore >= 0.82 ? best : null;
    }

    private static List<String> nameCandidates(String name, String userId) {
        List<String> out = new ArrayList<>();
        String[] parts = norm(name == null ? "" : name).split("[^A-Z0-9]+");
        for (String part : parts) {
            String c = compact(part);
            if (c.length() >= 3 && !out.contains(c)) out.add(c);
        }

        String id = compact(userId == null ? "" : userId);
        Collections.sort(out, (a, b) -> {
            boolean ai = id.contains(a), bi = id.contains(b);
            if (ai != bi) return ai ? -1 : 1;
            return Integer.compare(b.length(), a.length());
        });
        return out;
    }

    private static String normalizeShiftCode(String raw) {
        String n = norm(raw).replaceAll("[^A-Z0-9]+", " ").trim();
        if (n.isEmpty()) return "";
        String c = n.replace(" ", "");

        if (c.contains("PAR")) return "PAR";
        if (c.contains("GREP") || (c.contains("REP") && c.startsWith("G"))) return "G REP";
        if (c.equals("REP") || c.endsWith("REP")) return "REP";
        if (c.equals("NC") || c.equals("N0") || c.equals("NO")) return "NC";
        if (c.equals("RP") || c.equals("R") || c.equals("AP")) return "RP";
        if (c.equals("F")) return "F";
        if (c.equals("G") || c.equals("6")) return "G";
        if (c.equals("1") || c.equals("I") || c.equals("L")) return "1";
        if (c.equals("2")) return "2";
        if (c.equals("3")) return "3";

        if (c.matches(".*1.*") && c.length() <= 3) return "1";
        if (c.matches(".*2.*") && c.length() <= 3) return "2";
        if (c.matches(".*3.*") && c.length() <= 3) return "3";
        return "";
    }

    private static String detectWeekStart(String rawText) {
        List<LocalDate> dates = new ArrayList<>();
        Matcher m = DATE_PATTERN.matcher(rawText == null ? "" : rawText);
        while (m.find()) {
            try {
                LocalDate d = LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
                if (!dates.contains(d)) dates.add(d);
            } catch (Exception ignored) {}
        }
        for (LocalDate a : dates) {
            for (LocalDate b : dates) {
                if (b.equals(a.plusDays(6))) return a.toString();
            }
        }
        for (LocalDate d : dates) if (d.getDayOfWeek() == DayOfWeek.MONDAY) return d.toString();
        return "";
    }

    private static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int dist = levenshtein(a, b);
        return 1.0 - ((double) dist / (double) Math.max(a.length(), b.length()));
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    private static String compact(String s) { return norm(s).replaceAll("[^A-Z0-9]", ""); }

    private static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toUpperCase(Locale.ITALY).trim();
    }
}
