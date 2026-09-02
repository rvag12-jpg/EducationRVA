package es.iesvirgendelacaridad.corrector;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cliente directo de OpenAI Responses API para la versión Android autónoma. */
public final class ApiClient {
    private static final String RESPONSES_URL = "https://api.openai.com/v1/responses";
    private static final String MODELS_URL = "https://api.openai.com/v1/models/";
    private static Context appContext;

    private ApiClient() {}

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public static JSONObject getHealth(String endpoint, String apiKey) throws IOException, JSONException {
        requireContext();
        String model = modelFromEndpoint(endpoint);
        HttpURLConnection c = (HttpURLConnection) new URL(MODELS_URL + model).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("Authorization", "Bearer " + requireKey(apiKey));
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        String raw = readAll(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code < 200 || code >= 300) throw new IOException(apiError(code, raw));
        JSONObject modelInfo = new JSONObject(raw);
        return new JSONObject().put("ok", true).put("app", "OpenAI directo · " + modelInfo.optString("id", model));
    }

    public static JSONObject post(String endpoint, String apiKey, JSONObject payload) throws Exception {
        requireContext();
        String model = modelFromEndpoint(endpoint);
        if (endpoint.contains("/api/analyze-exam")) return analyzeExam(model, requireKey(apiKey), payload);
        if (endpoint.contains("/api/grade")) return gradeStudent(model, requireKey(apiKey), payload);
        throw new IOException("Operación directa desconocida.");
    }

    private static JSONObject analyzeExam(String model, String apiKey, JSONObject payload) throws Exception {
        JSONObject config = payload.optJSONObject("config");
        if (config == null) config = new JSONObject();
        JSONObject master = payload.optJSONObject("master_file");
        if (master == null) throw new IOException("Falta el archivo maestro de la prueba.");
        String hash = payload.optString("master_sha256", "");
        double total = config.optDouble("total_points", 10.0);
        boolean auto = config.optBoolean("auto_allocate", false);

        String prompt = "CONSTRUYE LA FICHA MAESTRA DE CORRECCIÓN (FMC) A PARTIR DEL ARCHIVO MAESTRO ADJUNTO.\n\n" +
                "CONFIGURACIÓN CONFIRMADA\n" +
                "- Materia: Química.\n- Curso: 2.º de Bachillerato.\n" +
                "- Título: " + config.optString("assessment_title", "—") + ".\n" +
                "- Unidad/bloque: " + config.optString("unit", "—") + ".\n" +
                "- Tipo: " + config.optString("type", "Mixta") + ".\n" +
                "- Duración: " + config.optInt("duration", 60) + " min.\n" +
                "- Puntuación total: " + total + " puntos.\n" +
                "- Nivel de exigencia: " + config.optString("level", "Alto") + ".\n" +
                "- Presentación: " + config.optString("presentation", "No evaluar") + ".\n" +
                "- SHA-256: " + hash + ".\n" +
                "- Si faltan ponderaciones: " + (auto ? "AUTORIZADO proponer reparto automático razonado." : "PROHIBIDO inventarlas; marca bloqueo.") + "\n\n" +
                "TAREAS OBLIGATORIAS\n" +
                "1. Lee íntegramente el archivo y extrae todas las cuestiones/subapartados en orden.\n" +
                "2. Resuelve independientemente cada cuestión y audita datos, ecuaciones, ajustes, signos, unidades, constantes, aproximaciones, nomenclatura y resultados.\n" +
                "3. No des por correcta ninguna solución impresa sin auditarla.\n" +
                "4. Para cada cuestión crea una matriz de evidencias de valor FIJO cuya suma sea exactamente max_points. No uses descuentos subjetivos.\n" +
                "5. partial_allowed solo será true si partial_points está fijado antes de corregir.\n" +
                "6. Si una evidencia esencial fija techo al faltar, define cap_if_missing.\n" +
                "7. Define errores previsibles, evidencias invalidadas/conservadas y regla de error arrastrado.\n" +
                "8. Fija reglas numéricas de unidades, redondeo, cifras significativas, signos y aproximaciones.\n" +
                "9. Asigna de 0 a 2 criterios reales y pertinentes por cuestión; no inventes códigos.\n" +
                "10. Establece anclajes alto/intermedio/insuficiente.\n" +
                "11. Si faltan ponderaciones y no están autorizadas: max_points=null, evidencia=0 y bloqueo; no simules nota.\n" +
                "12. Si están autorizadas, distribuye exactamente " + total + " puntos y marca score_source=proposed_automatic.\n" +
                "13. Usa fmc_version=\"1\" y copia master_sha256 exactamente.";

        JSONArray user = new JSONArray();
        user.put(textPart(prompt));
        user.put(textPart("ARCHIVO MAESTRO: " + master.optString("name", "prueba")));
        user.put(filePart(master));
        JSONObject schema = new JSONObject(readAsset("fmc_schema.json"));
        JSONObject result = callOpenAI(model, apiKey, user, "ficha_maestra_correccion", schema, 30000);
        result.put("master_sha256", hash);
        result.put("configured_total_points", total);
        validateFmc(result, total, auto);
        return result;
    }

    private static JSONObject gradeStudent(String model, String apiKey, JSONObject payload) throws Exception {
        if (!payload.optBoolean("fmc_confirmed", false)) throw new IOException("La FMC no está confirmada y bloqueada.");
        JSONObject fmc = payload.optJSONObject("fmc");
        if (fmc == null) throw new IOException("Falta la FMC.");
        JSONArray fmcBlocks = fmc.optJSONArray("blocking_issues");
        if (fmcBlocks != null && fmcBlocks.length() > 0) throw new IOException("La FMC contiene incidencias bloqueantes.");
        JSONArray files = payload.optJSONArray("student_files");
        if (files == null || files.length() == 0) throw new IOException("Faltan las respuestas del alumno.");
        String code = payload.optString("student_code", "").trim();
        if (code.isEmpty()) throw new IOException("Falta el código/pseudónimo del alumno.");
        JSONObject config = payload.optJSONObject("config");
        if (config == null) config = new JSONObject();
        String presentation = config.optString("presentation", "No evaluar");

        String prompt = "CORRIGE LAS RESPUESTAS DEL ALUMNO CON CÓDIGO " + code + " USANDO EXCLUSIVAMENTE LA FMC BLOQUEADA SIGUIENTE:\n\n" +
                fmc.toString() + "\n\nREGLAS DE EJECUCIÓN\n" +
                "1. No reconstruyas ni modifiques solución, ponderaciones, criterios, matrices, topes, reglas ni nivel de exigencia de la FMC.\n" +
                "2. Corrige cada cuestión independientemente; aplica anti-efecto halo.\n" +
                "3. Para CADA evidencia devuelve exactamente una entrada evidence_vector con el mismo code.\n" +
                "4. Estados válidos: ACREDITADA; PARCIALMENTE_ACREDITADA solo si partial_allowed=true; NO_ACREDITADA; NO_EVALUABLE_ILEGIBILIDAD.\n" +
                "5. La aplicación recalculará awarded_points determinísticamente desde la FMC.\n" +
                "6. No añadas ni quites puntos por impresión global ni concedas puntos a contenido no observable.\n" +
                "7. [ILEGIBLE] no acredita esa evidencia pero no invalida las demás. [AMBIGUO] se valora por lo que expresa.\n" +
                "8. [DUDOSO] con lecturas que puedan cambiar más de 0,5 puntos: material_doubt=true.\n" +
                "9. Trata error arrastrado causal sin doble penalización y conserva destrezas posteriores realmente demostradas.\n" +
                "10. criteria debe copiar exactamente los criterios fijados en la FMC.\n" +
                "11. evidence_location debe indicar dónde se observa la evidencia sin inventar texto ilegible.\n" +
                "12. confidence entre 0 y 1 refleja confianza en lectura/evaluación, no rendimiento.\n" +
                "13. Presentación configurada: " + presentation + ".\n" +
                "14. Produce 3–5 mejoras pedagógicas concretas.\n" +
                "15. status solo será definitive si no existen dudas materiales o problemas que impidan aplicar la FMC.";

        JSONArray user = new JSONArray();
        user.put(textPart(prompt));
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            user.put(textPart("RESPUESTA DEL ALUMNO · archivo " + (i + 1) + ": " + f.optString("name", "archivo")));
            user.put(filePart(f));
        }
        JSONObject schema = new JSONObject(readAsset("grade_schema.json"));
        JSONObject result = callOpenAI(model, apiKey, user, "correccion_criterial", schema, 32000);
        result.put("student_code", code);
        result.put("fmc_version", fmc.optString("fmc_version", "1"));
        deterministicScore(result, fmc, presentation);
        return result;
    }

    private static JSONObject callOpenAI(String model, String apiKey, JSONArray userContent, String schemaName, JSONObject schema, int maxTokens) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("store", false);
        body.put("reasoning", new JSONObject().put("effort", "high"));
        JSONArray input = new JSONArray();
        input.put(new JSONObject().put("role", "system").put("content", new JSONArray().put(textPart(baseInstructions()))));
        input.put(new JSONObject().put("role", "user").put("content", userContent));
        body.put("input", input);
        body.put("max_output_tokens", maxTokens);
        body.put("text", new JSONObject().put("format", new JSONObject().put("type", "json_schema").put("name", schemaName).put("strict", true).put("schema", schema)));

        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(RESPONSES_URL).openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(30_000); c.setReadTimeout(600_000); c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + apiKey);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream out = c.getOutputStream()) { out.write(data); }
        int code = c.getResponseCode();
        String raw = readAll(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code < 200 || code >= 300) throw new IOException(apiError(code, raw));
        JSONObject response = new JSONObject(raw);
        String output = extractOutputText(response);
        if (output.isEmpty()) throw new IOException("La API no devolvió JSON estructurado utilizable. Estado: " + response.optString("status", "desconocido"));
        return new JSONObject(output);
    }

    private static JSONObject textPart(String text) throws JSONException {
        return new JSONObject().put("type", "input_text").put("text", text);
    }

    private static JSONObject filePart(JSONObject file) throws JSONException {
        String name = file.optString("name", "archivo");
        String mime = file.optString("mime", "application/octet-stream");
        String data = file.optString("data", "");
        if (data.isEmpty()) throw new JSONException("El archivo " + name + " no contiene datos.");
        String url = "data:" + mime + ";base64," + data;
        if (mime.startsWith("image/")) return new JSONObject().put("type", "input_image").put("image_url", url).put("detail", "high");
        return new JSONObject().put("type", "input_file").put("filename", name).put("file_data", url);
    }

    private static String baseInstructions() throws IOException {
        return "Actúas como motor de corrección automática para Química de 2.º de Bachillerato. La aplicación usa una Ficha Maestra de Corrección (FMC) que se bloquea antes de corregir alumnado.\n\n" +
                "PROTOCOLO OBLIGATORIO\n---\n" + readAsset("prompt_corrector.txt") + "\n---\n" +
                "CONTEXTO CIENTÍFICO-DIDÁCTICO\n---\n" + readAsset("quimica_2bach_context.txt") + "\n---\n" +
                "CRITERIOS Y SABERES PRIORITARIOS\n---\n" + readAsset("criterios_quimica_2bach.txt") + "\n---\n" +
                "TABLA AUXILIAR SABERES↔CRITERIOS — SOLO APOYO\n---\n" + readAsset("saberes_criterios_aux.txt") + "\n---\n" +
                "REGLAS AUTOMÁTICAS: archivo maestro=fuentede verdad; resolver/auditar antes de corregir; FMC fija solución/evidencias/puntos/reglas; nota por suma de evidencias; mismo vector=mismos puntos; anti-halo; duda >0,5 bloquea; error arrastrado sin doble penalización; máximo dos criterios; trabajar solo con código/pseudónimo; no inventar identificadores DOCIO.";
    }

    private static String readAsset(String name) throws IOException {
        requireContext();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(appContext.getAssets().open(name), StandardCharsets.UTF_8))) {
            char[] b = new char[8192]; int n; while ((n = r.read(b)) >= 0) sb.append(b, 0, n);
        }
        return sb.toString();
    }

    private static String extractOutputText(JSONObject response) {
        if (response.has("output_text") && !response.isNull("output_text")) return response.optString("output_text", "");
        StringBuilder sb = new StringBuilder();
        JSONArray out = response.optJSONArray("output");
        if (out == null) return "";
        for (int i = 0; i < out.length(); i++) {
            JSONObject item = out.optJSONObject(i); if (item == null || !"message".equals(item.optString("type"))) continue;
            JSONArray content = item.optJSONArray("content"); if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject p = content.optJSONObject(j); if (p == null) continue;
                if ("output_text".equals(p.optString("type"))) sb.append(p.optString("text", ""));
                if ("refusal".equals(p.optString("type"))) throw new IllegalStateException("El modelo rechazó la solicitud: " + p.optString("refusal", ""));
            }
        }
        return sb.toString();
    }

    private static String modelFromEndpoint(String endpoint) {
        String s = endpoint == null ? "" : endpoint.trim();
        int api = s.indexOf("/api/"); if (api >= 0) s = s.substring(0, api);
        int health = s.indexOf("/health"); if (health >= 0) s = s.substring(0, health);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.startsWith("http://") || s.startsWith("https://") || s.isEmpty()) return "gpt-5.6-sol";
        return s;
    }

    private static String requireKey(String key) throws IOException {
        String x = key == null ? "" : key.trim();
        if (x.isEmpty()) throw new IOException("No hay una clave OpenAI API guardada.");
        return x;
    }

    private static void requireContext() {
        if (appContext == null) throw new IllegalStateException("ApiClient no inicializado.");
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] b = new char[8192]; int n; while ((n = r.read(b)) >= 0) sb.append(b, 0, n);
        }
        return sb.toString();
    }

    private static String apiError(int code, String raw) {
        try { JSONObject x = new JSONObject(raw); JSONObject e = x.optJSONObject("error"); if (e != null) return "OpenAI API HTTP " + code + ": " + e.optString("message", raw); }
        catch (Exception ignored) {}
        if (raw == null) raw = ""; return "OpenAI API HTTP " + code + ": " + raw.substring(0, Math.min(raw.length(), 1500));
    }

    private static void validateFmc(JSONObject fmc, double configuredTotal, boolean auto) throws JSONException {
        ArrayList<String> blocks = strings(fmc.optJSONArray("blocking_issues"));
        JSONArray qs = fmc.optJSONArray("questions");
        if (qs == null || qs.length() == 0) blocks.add("No se detectaron cuestiones en el archivo maestro.");
        Set<String> ids = new HashSet<>(); double knownTotal = 0; boolean allKnown = true;
        if (qs != null) for (int i = 0; i < qs.length(); i++) {
            JSONObject q = qs.getJSONObject(i); String qid = q.optString("id", "");
            if (qid.isEmpty() || !ids.add(qid)) blocks.add("Existen identificadores de cuestión vacíos o duplicados.");
            JSONArray criteria = q.optJSONArray("criteria"); if (criteria != null && criteria.length() > 2) blocks.add(qid + ": se asignaron más de dos criterios principales.");
            boolean hasMax = !q.isNull("max_points"); double max = hasMax ? q.optDouble("max_points") : 0;
            if (!hasMax) allKnown = false; else knownTotal += max;
            JSONArray matrix = q.optJSONArray("evidence_matrix"); Set<String> codes = new HashSet<>(); double sum = 0;
            if (matrix != null) for (int j = 0; j < matrix.length(); j++) {
                JSONObject ev = matrix.getJSONObject(j); String code = ev.optString("code", "");
                if (code.isEmpty() || !codes.add(code)) blocks.add(qid + ": códigos de evidencia vacíos o duplicados.");
                double pts = ev.optDouble("points", 0), partial = ev.optDouble("partial_points", 0); sum += pts;
                if (pts < 0 || partial < 0 || partial > pts) blocks.add(qid + "/" + code + ": puntuación de evidencia no válida.");
                if (!ev.optBoolean("partial_allowed", false) && Math.abs(partial) > 1e-9) ev.put("partial_points", 0.0);
                if (!ev.isNull("cap_if_missing") && hasMax) { double cap = ev.optDouble("cap_if_missing"); if (cap < 0 || cap > max) blocks.add(qid + "/" + code + ": cap_if_missing fuera de rango."); }
            }
            if (hasMax && Math.abs(sum - max) > 0.011) blocks.add(qid + ": la matriz suma " + sum + " y la cuestión vale " + max + ".");
        }
        if (allKnown && Math.abs(knownTotal - configuredTotal) > 0.011) blocks.add("La suma de cuestiones (" + knownTotal + ") no coincide con la puntuación total (" + configuredTotal + ").");
        if (!allKnown && auto) blocks.add("Se autorizó reparto automático, pero quedaron cuestiones sin puntuación definida.");
        fmc.put("blocking_issues", uniqueArray(blocks));
        fmc.put("scientific_consistency_ok", fmc.optBoolean("scientific_consistency_ok", false) && blocks.isEmpty());
    }

    private static void deterministicScore(JSONObject result, JSONObject fmc, String presentationMode) throws JSONException {
        ArrayList<String> blocks = strings(result.optJSONArray("blocking_issues"));
        Map<String, JSONObject> fqs = new LinkedHashMap<>(); JSONArray fqa = fmc.optJSONArray("questions");
        if (fqa != null) for (int i = 0; i < fqa.length(); i++) { JSONObject q = fqa.getJSONObject(i); fqs.put(q.optString("id"), q); }
        Set<String> seen = new HashSet<>(); double totalMax = 0, totalAwarded = 0; boolean allKnown = true, material = false;
        JSONArray results = result.optJSONArray("question_results"); if (results == null) results = new JSONArray();
        for (int i = 0; i < results.length(); i++) {
            JSONObject qr = results.getJSONObject(i); String qid = qr.optString("id", ""); JSONObject fq = fqs.get(qid);
            if (fq == null) { blocks.add("Cuestión no definida en FMC: " + qid); qr.put("max_points", JSONObject.NULL); qr.put("awarded_points", JSONObject.NULL); allKnown = false; continue; }
            seen.add(qid); qr.put("question", fq.optString("question", qid)); qr.put("item_id", fq.isNull("item_id") ? JSONObject.NULL : fq.opt("item_id")); qr.put("criteria", fq.optJSONArray("criteria") == null ? new JSONArray() : fq.optJSONArray("criteria"));
            if (fq.isNull("max_points")) { qr.put("max_points", JSONObject.NULL); qr.put("awarded_points", JSONObject.NULL); allKnown = false; continue; }
            double max = fq.optDouble("max_points"); qr.put("max_points", max);
            Map<String, JSONObject> matrix = new LinkedHashMap<>(); JSONArray ma = fq.optJSONArray("evidence_matrix");
            if (ma != null) for (int j = 0; j < ma.length(); j++) { JSONObject ev = ma.getJSONObject(j); matrix.put(ev.optString("code"), ev); }
            Map<String, JSONObject> provided = new HashMap<>(); JSONArray vec = qr.optJSONArray("evidence_vector");
            if (vec != null) for (int j = 0; j < vec.length(); j++) { JSONObject ev = vec.getJSONObject(j); provided.put(ev.optString("code"), ev); }
            double score = 0; ArrayList<Double> caps = new ArrayList<>(); JSONArray normalized = new JSONArray();
            for (Map.Entry<String, JSONObject> entry : matrix.entrySet()) {
                String code = entry.getKey(); JSONObject def = entry.getValue(); JSONObject ev = provided.get(code);
                if (ev == null) { ev = new JSONObject().put("code", code).put("description", def.optString("description")).put("state", "NO_ACREDITADA").put("awarded_points", 0.0).put("evidence_location", "No identificada; requiere revisión"); blocks.add(qid + ": falta evidencia " + code + " en vector."); }
                String state = ev.optString("state", "NO_ACREDITADA"); double pts = def.optDouble("points", 0), awarded = 0;
                if ("ACREDITADA".equals(state)) awarded = pts;
                else if ("PARCIALMENTE_ACREDITADA".equals(state)) { if (def.optBoolean("partial_allowed", false)) awarded = def.optDouble("partial_points", 0); else blocks.add(qid + "/" + code + ": parcial no definido en FMC."); }
                awarded = Math.max(0, Math.min(pts, awarded)); ev.put("description", def.optString("description", ev.optString("description"))); ev.put("awarded_points", awarded); normalized.put(ev); score += awarded;
                if (def.optBoolean("essential", false) && !("ACREDITADA".equals(state) || "PARCIALMENTE_ACREDITADA".equals(state)) && !def.isNull("cap_if_missing")) caps.add(def.optDouble("cap_if_missing"));
            }
            for (String extra : provided.keySet()) if (!matrix.containsKey(extra)) blocks.add(qid + ": evidencia ajena a FMC: " + extra);
            for (double cap : caps) score = Math.min(score, cap); score = Math.max(0, Math.min(max, score));
            qr.put("evidence_vector", normalized); qr.put("awarded_points", score); totalMax += max; totalAwarded += score; if (qr.optBoolean("material_doubt", false)) material = true;
        }
        for (String qid : fqs.keySet()) if (!seen.contains(qid)) { blocks.add("Falta la cuestión " + qid + " de la FMC en la corrección."); allKnown = false; }
        double penalty = result.optDouble("presentation_penalty", 0);
        if ("No evaluar".equals(presentationMode)) { penalty = 0; result.put("presentation_penalty_reason", "No evaluada por configuración."); }
        else if ("Hasta 0,5 puntos".equals(presentationMode)) penalty = Math.max(0, Math.min(.5, penalty));
        else if ("Hasta 1,0 punto".equals(presentationMode)) penalty = Math.max(0, Math.min(1, penalty));
        else penalty = Math.max(0, penalty);
        result.put("presentation_penalty", penalty);
        JSONObject totals = new JSONObject().put("max_points", JSONObject.NULL).put("awarded_points", JSONObject.NULL).put("raw_grade_0_10", JSONObject.NULL).put("presentation_penalty", penalty).put("final_grade_0_10", JSONObject.NULL);
        if (allKnown && totalMax > 0) { double raw = totalAwarded / totalMax * 10, fin = Math.max(0, raw - penalty); totals.put("max_points", totalMax).put("awarded_points", totalAwarded).put("raw_grade_0_10", raw).put("final_grade_0_10", fin); }
        else blocks.add("No puede calcularse nota definitiva porque no todas las cuestiones tienen puntuación determinable.");
        if (material) blocks.add("Existe al menos una lectura [DUDOSO] con posible impacto superior a 0,5 puntos.");
        result.put("blocking_issues", uniqueArray(blocks)); result.put("totals", totals);
        result.put("status", blocks.isEmpty() && !totals.isNull("final_grade_0_10") ? "definitive" : "blocked");
    }

    private static ArrayList<String> strings(JSONArray a) { ArrayList<String> x = new ArrayList<>(); if (a != null) for (int i = 0; i < a.length(); i++) { String s = a.optString(i, "").trim(); if (!s.isEmpty()) x.add(s); } return x; }
    private static JSONArray uniqueArray(List<String> in) { JSONArray a = new JSONArray(); Set<String> seen = new HashSet<>(); for (String x : in) { String s = x == null ? "" : x.trim(); if (!s.isEmpty() && seen.add(s)) a.put(s); } return a; }
}
