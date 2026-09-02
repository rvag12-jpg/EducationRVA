package es.iesvirgendelacaridad.corrector;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class DocioBuilder {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RNG = new SecureRandom();

    private DocioBuilder() {}

    public static String generateImportId(String assessmentIdentifier) {
        String base = slug(assessmentIdentifier == null ? "PRUEBA" : assessmentIdentifier);
        if (base.length() > 22) base = base.substring(0, 22);
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        StringBuilder random = new StringBuilder(6);
        for (int i = 0; i < 6; i++) random.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        return "DOCIO-" + base + "-" + date + "-" + random;
    }

    public static JSONObject newBatch(String importId, String assessmentTitle, String assessmentId, String dateIso) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", "DOCIO_CORRECTION_V1");
        root.put("import_id", importId);
        JSONObject assessment = new JSONObject();
        putExactOptional(assessment, "assessment_id", assessmentId);
        assessment.put("title", assessmentTitle == null ? "" : assessmentTitle);
        if (dateIso != null && !dateIso.trim().isEmpty()) assessment.put("date", dateIso.trim());
        assessment.put("type", "WRITTEN_TEST");
        root.put("assessment", assessment);
        root.put("students", new JSONArray());
        return root;
    }

    public static JSONObject buildStudent(String studentId, String exactName, JSONObject correction, JSONObject fmc) throws JSONException {
        JSONObject student = new JSONObject();
        putExactOptional(student, "student_id", studentId);
        if (exactName != null && !exactName.trim().isEmpty()) student.put("name", exactName.trim());
        JSONArray items = new JSONArray();

        JSONArray results = correction.optJSONArray("question_results");
        if (results == null) results = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject q = results.optJSONObject(i);
            if (q == null || q.isNull("awarded_points") || q.isNull("max_points")) continue;
            double max = q.optDouble("max_points", Double.NaN);
            double raw = q.optDouble("awarded_points", Double.NaN);
            if (!Double.isFinite(max) || !Double.isFinite(raw) || max < 0) continue;
            raw = Math.max(0d, Math.min(max, raw));
            String question = q.optString("question", q.optString("id", "Cuestión " + (i + 1)));
            if (question.trim().isEmpty()) question = "Cuestión " + (i + 1);
            if (!seen.add(question)) continue;

            JSONObject item = new JSONObject();
            Object itemId = q.opt("item_id");
            if (itemId != null && itemId != JSONObject.NULL && !String.valueOf(itemId).trim().isEmpty()) {
                item.put("item_id", itemId);
            }
            item.put("question", question);
            item.put("raw_score", raw);
            item.put("max_score", max);

            JSONArray criteria = q.optJSONArray("criteria");
            if (criteria == null) criteria = new JSONArray();
            item.put("criteria", criteria);

            JSONArray evidenceTexts = new JSONArray();
            JSONArray vector = q.optJSONArray("evidence_vector");
            if (vector != null) {
                for (int e = 0; e < vector.length(); e++) {
                    JSONObject ev = vector.optJSONObject(e);
                    if (ev == null) continue;
                    String description = ev.optString("description", ev.optString("code", "E" + (e + 1)));
                    String state = ev.optString("state", "");
                    evidenceTexts.put(description + " — " + state);
                }
            }
            JSONArray extraEvidence = q.optJSONArray("evidence");
            if (evidenceTexts.length() == 0 && extraEvidence != null) {
                for (int e = 0; e < extraEvidence.length(); e++) evidenceTexts.put(extraEvidence.optString(e));
            }
            item.put("evidence", evidenceTexts);
            item.put("comment", q.optString("comment", q.optString("justification", "")));
            double confidence = q.optDouble("confidence", 0.85d);
            if (!Double.isFinite(confidence)) confidence = 0.85d;
            item.put("confidence", Math.max(0d, Math.min(1d, confidence)));
            items.put(item);
        }
        student.put("items", items);
        return student;
    }

    public static void addOrReplaceStudent(JSONObject batch, JSONObject student) throws JSONException {
        JSONArray students = batch.optJSONArray("students");
        if (students == null) {
            students = new JSONArray();
            batch.put("students", students);
        }
        String sid = stringValue(student.opt("student_id"));
        String name = student.optString("name", "");
        int replace = -1;
        for (int i = 0; i < students.length(); i++) {
            JSONObject current = students.optJSONObject(i);
            if (current == null) continue;
            String csid = stringValue(current.opt("student_id"));
            String cname = current.optString("name", "");
            if (!sid.isEmpty() && sid.equals(csid)) { replace = i; break; }
            if (sid.isEmpty() && !name.isEmpty() && name.equals(cname)) { replace = i; break; }
        }
        if (replace >= 0) students.put(replace, student); else students.put(student);
    }

    public static String todayIso() {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static String slug(String s) {
        String normalized = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        normalized = normalized.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-");
        normalized = normalized.replaceAll("^-+|-+$", "");
        return normalized.isEmpty() ? "PRUEBA" : normalized;
    }

    private static void putExactOptional(JSONObject target, String key, String value) throws JSONException {
        if (value == null || value.trim().isEmpty()) return;
        String v = value.trim();
        if (v.matches("-?\\d+")) {
            try { target.put(key, Long.parseLong(v)); return; } catch (NumberFormatException ignored) {}
        }
        target.put(key, v);
    }

    private static String stringValue(Object o) {
        if (o == null || o == JSONObject.NULL) return "";
        return String.valueOf(o);
    }
}
