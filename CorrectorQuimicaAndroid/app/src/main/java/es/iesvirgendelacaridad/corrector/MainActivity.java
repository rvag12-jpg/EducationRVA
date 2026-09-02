package es.iesvirgendelacaridad.corrector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_MASTER = 1001;
    private static final int REQ_STUDENT = 1002;
    private static final int REQ_SAVE_PDF = 1003;
    private static final int REQ_SAVE_DOCIO = 1004;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    private EditText backendUrl, backendToken;
    private EditText assessmentTitle, assessmentId, importId, unit, duration, totalPoints;
    private Spinner testType, level, presentation, autoAllocate;
    private Button chooseMaster, generateFmc, confirmFmc, resetFmc;
    private TextView masterLabel, fmcStatus, fmcView;

    private EditText studentCode, studentId, studentName;
    private Button chooseStudent, gradeButton, nextStudent;
    private TextView studentFilesLabel, gradeStatus, resultView;

    private Button savePdf, addDocio, saveDocio, testBackend;
    private ProgressBar progress;
    private TextView progressText;

    private Uri masterUri;
    private final ArrayList<Uri> studentUris = new ArrayList<>();
    private JSONObject fmc;
    private boolean fmcConfirmed = false;
    private JSONObject correction;
    private JSONObject docioBatch;
    private String pendingPdfText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("corrector_quimica_v1", MODE_PRIVATE);
        buildUi();
        restoreState();
        updateFmcLockUi();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Corrector Criterial Automático", 24, true);
        title.setTextColor(Color.rgb(23, 54, 93));
        root.addView(title);
        TextView subtitle = text("Química · 2.º Bachillerato · FMC bloqueada · corrección por evidencias · exportación DOCIO", 13, false);
        subtitle.setTextColor(Color.DKGRAY);
        root.addView(subtitle);

        root.addView(section("1 · Servidor corrector seguro"));
        root.addView(note("La clave de OpenAI NO se introduce en Android. El teléfono se conecta a un servidor corrector que guarda la clave fuera del APK. Para uso en la misma red Wi‑Fi, introduce la URL LAN del ordenador, por ejemplo http://192.168.1.25:8765."));
        backendUrl = edit("URL del servidor", "http://192.168.1.25:8765"); root.addView(backendUrl);
        backendToken = edit("Token de acceso del servidor", ""); backendToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); root.addView(backendToken);
        testBackend = button("Probar conexión", this::onTestBackend); root.addView(testBackend);

        root.addView(section("2 · Marco de corrección y archivo maestro"));
        assessmentTitle = edit("Título exacto de la prueba", ""); root.addView(assessmentTitle);
        assessmentId = edit("assessment_id DOCIO (opcional, no inventar)", ""); root.addView(assessmentId);
        importId = edit("import_id DOCIO (se genera si está vacío)", ""); root.addView(importId);
        unit = edit("Unidad / bloque", ""); root.addView(unit);
        root.addView(label("Tipo de prueba"));
        testType = spinner(new String[]{"Mixta", "Teoría", "Problemas", "Laboratorio"}); root.addView(testType);
        duration = numberEdit("Duración (min)", "60"); root.addView(duration);
        totalPoints = decimalEdit("Puntuación total", "10"); root.addView(totalPoints);
        root.addView(label("Nivel de exigencia"));
        level = spinner(new String[]{"Alto", "Estándar", "Muy alto"}); root.addView(level);
        root.addView(label("Presentación / expresión / ortografía"));
        presentation = spinner(new String[]{"No evaluar", "Hasta 0,5 puntos", "Hasta 1,0 punto"}); root.addView(presentation);
        root.addView(label("Si el archivo de la prueba no contiene ponderaciones"));
        autoAllocate = spinner(new String[]{"No inventar: bloquear nota definitiva", "Autorizar reparto automático razonado"}); root.addView(autoAllocate);

        chooseMaster = button("Seleccionar archivo maestro de la prueba", this::onChooseMaster); root.addView(chooseMaster);
        masterLabel = text("Ningún archivo maestro seleccionado.", 12, false); root.addView(masterLabel);
        generateFmc = button("GENERAR Y AUDITAR FICHA MAESTRA (FMC)", this::onGenerateFmc); root.addView(generateFmc);
        fmcStatus = text("FMC pendiente.", 13, true); root.addView(fmcStatus);
        fmcView = text("", 12, false); fmcView.setTextIsSelectable(true); root.addView(fmcView);
        confirmFmc = button("CONFIRMAR Y BLOQUEAR FMC", this::onConfirmFmc); root.addView(confirmFmc);
        resetFmc = button("Crear nueva versión de FMC / desbloquear", this::onResetFmc); root.addView(resetFmc);

        root.addView(section("3 · Alumno y respuestas"));
        root.addView(note("Usa un código o pseudónimo para la corrección por IA. El nombre completo, si se introduce para DOCIO, permanece local en el dispositivo y NO se envía al motor corrector."));
        studentCode = edit("Código / iniciales para corrección", ""); root.addView(studentCode);
        studentId = edit("student_id DOCIO (opcional, exacto)", ""); root.addView(studentId);
        studentName = edit("Nombre exacto DOCIO (opcional, solo local)", ""); root.addView(studentName);
        chooseStudent = button("Seleccionar PDF / imágenes de respuestas", this::onChooseStudent); root.addView(chooseStudent);
        studentFilesLabel = text("Sin respuestas seleccionadas.", 12, false); root.addView(studentFilesLabel);
        gradeButton = button("CORREGIR AUTOMÁTICAMENTE", this::onGrade); root.addView(gradeButton);
        gradeStatus = text("", 13, true); root.addView(gradeStatus);

        progress = new ProgressBar(this); progress.setIndeterminate(true); progress.setVisibility(View.GONE); root.addView(progress);
        progressText = text("", 12, false); root.addView(progressText);

        root.addView(section("4 · Resultado"));
        resultView = text("", 12, false); resultView.setTextIsSelectable(true); root.addView(resultView);
        savePdf = button("Guardar informe en PDF", this::onSavePdf); root.addView(savePdf);
        addDocio = button("Añadir / actualizar alumno en lote DOCIO", this::onAddDocio); root.addView(addDocio);
        saveDocio = button("Exportar DOCIO_CORRECTION_V1.json", this::onSaveDocio); root.addView(saveDocio);
        nextStudent = button("Siguiente alumno · misma FMC", this::onNextStudent); root.addView(nextStudent);

        setContentView(scroll);
    }

    private void restoreState() {
        backendUrl.setText(prefs.getString("backend_url", backendUrl.getText().toString()));
        backendToken.setText(prefs.getString("backend_token", ""));
        assessmentTitle.setText(prefs.getString("assessment_title", ""));
        assessmentId.setText(prefs.getString("assessment_id", ""));
        importId.setText(prefs.getString("import_id", ""));
        unit.setText(prefs.getString("unit", ""));
        duration.setText(prefs.getString("duration", "60"));
        totalPoints.setText(prefs.getString("total_points", "10"));
        setSpinner(testType, prefs.getString("test_type", "Mixta"));
        setSpinner(level, prefs.getString("level", "Alto"));
        setSpinner(presentation, prefs.getString("presentation", "No evaluar"));
        autoAllocate.setSelection(prefs.getBoolean("auto_allocate", false) ? 1 : 0);

        String uri = prefs.getString("master_uri", "");
        if (!uri.isEmpty()) {
            masterUri = Uri.parse(uri);
            masterLabel.setText("Archivo maestro: " + FileUtil.displayName(this, masterUri));
        }
        String fmcJson = prefs.getString("fmc", "");
        if (!fmcJson.isEmpty()) {
            try { fmc = new JSONObject(fmcJson); } catch (JSONException ignored) {}
        }
        fmcConfirmed = prefs.getBoolean("fmc_confirmed", false) && fmc != null;
        if (fmc != null) fmcView.setText(renderFmc(fmc));
        if (fmcConfirmed) fmcStatus.setText("FMC CONFIRMADA Y BLOQUEADA. Versión: " + fmc.optString("fmc_version", "1"));

        String batch = prefs.getString("docio_batch", "");
        if (!batch.isEmpty()) {
            try { docioBatch = new JSONObject(batch); } catch (JSONException ignored) {}
        }
    }

    private void persistCommon() {
        prefs.edit()
                .putString("backend_url", backendUrl.getText().toString().trim())
                .putString("backend_token", backendToken.getText().toString().trim())
                .putString("assessment_title", assessmentTitle.getText().toString().trim())
                .putString("assessment_id", assessmentId.getText().toString().trim())
                .putString("import_id", importId.getText().toString().trim())
                .putString("unit", unit.getText().toString().trim())
                .putString("duration", duration.getText().toString().trim())
                .putString("total_points", totalPoints.getText().toString().trim())
                .putString("test_type", String.valueOf(testType.getSelectedItem()))
                .putString("level", String.valueOf(level.getSelectedItem()))
                .putString("presentation", String.valueOf(presentation.getSelectedItem()))
                .putBoolean("auto_allocate", autoAllocate.getSelectedItemPosition() == 1)
                .apply();
    }

    private void onTestBackend(View v) {
        persistCommon();
        String base = normalizedBaseUrl();
        if (base.isEmpty()) { toast("Indica la URL del servidor."); return; }
        setBusy(true, "Comprobando conexión…");
        executor.execute(() -> {
            try {
                JSONObject health = ApiClient.getHealth(base + "/health", backendToken.getText().toString());
                runOnUiThread(() -> {
                    setBusy(false, "");
                    toast("Servidor disponible: " + health.optString("app", "corrector"));
                });
            } catch (Exception e) { fail(e); }
        });
    }

    private void onChooseMaster(View v) {
        if (fmcConfirmed) { toast("La FMC está bloqueada. Crea una nueva versión antes de cambiar la prueba."); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "image/*", "text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"});
        startActivityForResult(i, REQ_MASTER);
    }

    private void onChooseStudent(View v) {
        if (!fmcConfirmed) { toast("Confirma y bloquea primero la FMC."); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "image/*", "text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"});
        startActivityForResult(i, REQ_STUDENT);
    }

    private void onGenerateFmc(View v) {
        if (masterUri == null) { toast("Selecciona el archivo maestro de la prueba."); return; }
        if (assessmentTitle.getText().toString().trim().isEmpty()) { toast("Indica el título exacto de la prueba."); return; }
        double total = parsePositive(totalPoints.getText().toString(), -1);
        if (total <= 0) { toast("La puntuación total debe ser positiva."); return; }
        persistCommon();
        setBusy(true, "Leyendo, resolviendo y auditando la prueba. Se construye la FMC antes de corregir alumnado…");
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("config", configJson());
                payload.put("master_file", FileUtil.toJsonFile(this, masterUri));
                payload.put("master_sha256", FileUtil.sha256(this, masterUri));
                JSONObject result = ApiClient.post(normalizedBaseUrl() + "/api/analyze-exam", backendToken.getText().toString(), payload);
                fmc = result;
                fmcConfirmed = false;
                prefs.edit().putString("fmc", fmc.toString()).putBoolean("fmc_confirmed", false).apply();
                runOnUiThread(() -> {
                    setBusy(false, "");
                    fmcView.setText(renderFmc(fmc));
                    List<String> blocks = jsonStrings(fmc.optJSONArray("blocking_issues"));
                    fmcStatus.setText(blocks.isEmpty() ? "FMC generada y auditada. Revísala antes de bloquearla." : "FMC generada con incidencias bloqueantes: " + String.join(" · ", blocks));
                    updateFmcLockUi();
                });
            } catch (Exception e) { fail(e); }
        });
    }

    private void onConfirmFmc(View v) {
        if (fmc == null) { toast("Genera primero la FMC."); return; }
        JSONArray blocks = fmc.optJSONArray("blocking_issues");
        if (blocks != null && blocks.length() > 0) {
            toast("La FMC contiene incidencias bloqueantes. Corrige el archivo/configuración o autoriza la ponderación automática antes de bloquearla.");
            return;
        }
        String message = "¿Confirmas esta Ficha Maestra de Corrección como marco definitivo y bloqueado para todas las correcciones de esta prueba?\n\n" +
                "Una vez bloqueada, solución, matriz de evidencias, criterios, ponderaciones y reglas no podrán variar entre alumnos.";
        new AlertDialog.Builder(this)
                .setTitle("Confirmación de FMC")
                .setMessage(message)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("CONFIRMAR Y BLOQUEAR", (d, which) -> {
                    fmcConfirmed = true;
                    try { fmc.put("confirmed_by_teacher", true); } catch (JSONException ignored) {}
                    prefs.edit().putString("fmc", fmc.toString()).putBoolean("fmc_confirmed", true).apply();
                    fmcStatus.setText("FMC CONFIRMADA Y BLOQUEADA. Misma prueba + misma FMC + mismas evidencias = misma calificación.");
                    updateFmcLockUi();
                }).show();
    }

    private void onResetFmc(View v) {
        new AlertDialog.Builder(this)
                .setTitle("Crear nueva versión")
                .setMessage("La FMC actual dejará de utilizarse para nuevas correcciones. Las correcciones anteriores conservan su referencia de versión. ¿Continuar?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Nueva versión", (d, w) -> {
                    fmc = null;
                    fmcConfirmed = false;
                    correction = null;
                    prefs.edit().remove("fmc").putBoolean("fmc_confirmed", false).apply();
                    fmcView.setText(""); fmcStatus.setText("FMC pendiente."); resultView.setText(""); gradeStatus.setText("");
                    updateFmcLockUi();
                }).show();
    }

    private void onGrade(View v) {
        if (!fmcConfirmed || fmc == null) { toast("La corrección exige una FMC confirmada y bloqueada."); return; }
        String code = studentCode.getText().toString().trim();
        if (code.isEmpty()) { toast("Introduce un código o pseudónimo del alumno."); return; }
        if (studentUris.isEmpty()) { toast("Selecciona las respuestas del alumno."); return; }
        persistCommon();
        setBusy(true, "Corrigiendo por vector de evidencias. No se usa valoración global y no se penaliza dos veces un mismo error…");
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("config", configJson());
                payload.put("fmc", fmc);
                payload.put("fmc_confirmed", true);
                payload.put("student_code", code);
                JSONArray files = new JSONArray();
                for (Uri uri : studentUris) files.put(FileUtil.toJsonFile(this, uri));
                payload.put("student_files", files);
                JSONObject result = ApiClient.post(normalizedBaseUrl() + "/api/grade", backendToken.getText().toString(), payload);
                correction = result;
                runOnUiThread(() -> {
                    setBusy(false, "");
                    resultView.setText(renderCorrection(correction));
                    String status = correction.optString("status", "provisional");
                    JSONObject totals = correction.optJSONObject("totals");
                    String grade = totals == null || totals.isNull("final_grade_0_10") ? "—" : fmt(totals.optDouble("final_grade_0_10"));
                    gradeStatus.setText("Estado: " + status.toUpperCase(Locale.ROOT) + " · Nota: " + grade + " / 10");
                    updateFmcLockUi();
                });
            } catch (Exception e) { fail(e); }
        });
    }

    private void onSavePdf(View v) {
        if (correction == null) { toast("No hay corrección que exportar."); return; }
        pendingPdfText = buildReportText();
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_TITLE, safeFileName("Correccion_" + studentCode.getText().toString().trim()) + ".pdf");
        startActivityForResult(i, REQ_SAVE_PDF);
    }

    private void onAddDocio(View v) {
        if (correction == null || fmc == null) { toast("Primero debe existir una corrección."); return; }
        if (!"definitive".equals(correction.optString("status"))) {
            toast("La corrección no es definitiva; no se añade a DOCIO hasta resolver las incidencias.");
            return;
        }
        try {
            String title = assessmentTitle.getText().toString().trim();
            String imp = importId.getText().toString().trim();
            if (imp.isEmpty()) {
                String seed = assessmentId.getText().toString().trim().isEmpty() ? title : assessmentId.getText().toString().trim();
                imp = DocioBuilder.generateImportId(seed);
                importId.setText(imp);
            }
            if (docioBatch == null || !imp.equals(docioBatch.optString("import_id"))) {
                docioBatch = DocioBuilder.newBatch(imp, title, assessmentId.getText().toString().trim(), DocioBuilder.todayIso());
            }
            JSONObject student = DocioBuilder.buildStudent(studentId.getText().toString().trim(), studentName.getText().toString().trim(), correction, fmc);
            if (student.optJSONArray("items") == null || student.optJSONArray("items").length() == 0) {
                toast("No hay cuestiones evaluables con puntuación para exportar."); return;
            }
            DocioBuilder.addOrReplaceStudent(docioBatch, student);
            prefs.edit().putString("docio_batch", docioBatch.toString()).putString("import_id", imp).apply();
            persistCommon();
            toast("Alumno añadido/actualizado en el lote DOCIO. Total: " + docioBatch.optJSONArray("students").length());
        } catch (Exception e) { toast("No se pudo construir DOCIO: " + e.getMessage()); }
    }

    private void onSaveDocio(View v) {
        if (docioBatch == null) { toast("El lote DOCIO está vacío. Añade al menos un alumno."); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "DOCIO_CORRECTION_V1_" + safeFileName(assessmentTitle.getText().toString()) + ".json");
        startActivityForResult(i, REQ_SAVE_DOCIO);
    }

    private void onNextStudent(View v) {
        studentUris.clear();
        studentFilesLabel.setText("Sin respuestas seleccionadas.");
        studentCode.setText(""); studentId.setText(""); studentName.setText("");
        correction = null; resultView.setText(""); gradeStatus.setText("");
        updateFmcLockUi();
        toast("FMC conservada y bloqueada para el siguiente alumno.");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_MASTER) {
            Uri uri = data.getData();
            if (uri == null) return;
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            masterUri = uri;
            prefs.edit().putString("master_uri", uri.toString()).apply();
            masterLabel.setText("Archivo maestro: " + FileUtil.displayName(this, uri));
            fmc = null; fmcConfirmed = false; prefs.edit().remove("fmc").putBoolean("fmc_confirmed", false).apply();
            fmcView.setText(""); fmcStatus.setText("Archivo cambiado: genera una nueva FMC."); updateFmcLockUi();
        } else if (requestCode == REQ_STUDENT) {
            studentUris.clear();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) studentUris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) studentUris.add(data.getData());
            ArrayList<String> names = new ArrayList<>();
            for (Uri uri : studentUris) names.add(FileUtil.displayName(this, uri));
            studentFilesLabel.setText(studentUris.size() + " archivo(s): " + String.join(", ", names));
        } else if (requestCode == REQ_SAVE_PDF) {
            Uri uri = data.getData();
            if (uri == null || pendingPdfText == null) return;
            try {
                PdfReport.write(this, uri, "Corrección criterial · " + studentCode.getText().toString().trim(), pendingPdfText);
                toast("PDF guardado.");
            } catch (Exception e) { toast("Error al guardar PDF: " + e.getMessage()); }
        } else if (requestCode == REQ_SAVE_DOCIO) {
            Uri uri = data.getData();
            if (uri == null || docioBatch == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("No se puede crear el archivo.");
                out.write(docioBatch.toString(2).getBytes(StandardCharsets.UTF_8));
                toast("JSON DOCIO guardado.");
            } catch (Exception e) { toast("Error al guardar DOCIO: " + e.getMessage()); }
        }
    }

    private JSONObject configJson() throws JSONException {
        JSONObject c = new JSONObject();
        c.put("assessment_title", assessmentTitle.getText().toString().trim());
        c.put("assessment_id", assessmentId.getText().toString().trim());
        c.put("unit", unit.getText().toString().trim());
        c.put("type", String.valueOf(testType.getSelectedItem()));
        c.put("duration", (int) parsePositive(duration.getText().toString(), 60));
        c.put("total_points", parsePositive(totalPoints.getText().toString(), 10));
        c.put("level", String.valueOf(level.getSelectedItem()));
        c.put("presentation", String.valueOf(presentation.getSelectedItem()));
        c.put("auto_allocate", autoAllocate.getSelectedItemPosition() == 1);
        return c;
    }

    private String renderFmc(JSONObject x) {
        StringBuilder s = new StringBuilder();
        s.append("FICHA MAESTRA DE CORRECCIÓN\n");
        s.append("Prueba: ").append(x.optString("exam_title", assessmentTitle.getText().toString())).append("\n");
        s.append("Versión: ").append(x.optString("fmc_version", "1")).append("\n");
        s.append("Estado de ponderación: ").append(x.optString("scoring_status", "—")).append("\n");
        s.append("Auditoría: ").append(x.optString("scientific_audit_summary", "—")).append("\n\n");
        appendStrings(s, "Incidencias de auditoría", x.optJSONArray("audit_issues"));
        appendStrings(s, "Incidencias bloqueantes", x.optJSONArray("blocking_issues"));
        JSONArray qs = x.optJSONArray("questions");
        if (qs != null) for (int i = 0; i < qs.length(); i++) {
            JSONObject q = qs.optJSONObject(i); if (q == null) continue;
            s.append("\n## ").append(q.optString("id", "Q" + (i + 1))).append(" · ").append(q.optString("question", q.optString("enunciado", ""))).append("\n");
            s.append("Máximo: ").append(q.isNull("max_points") ? "—" : fmt(q.optDouble("max_points"))).append(" pt\n");
            s.append("Criterios: ").append(joinJson(q.optJSONArray("criteria"))).append("\n");
            s.append("Saberes: ").append(joinJson(q.optJSONArray("saberes"))).append("\n");
            s.append("Solución canónica: ").append(q.optString("canonical_solution", q.optString("expected_answer", "—"))).append("\n");
            JSONArray ev = q.optJSONArray("evidence_matrix");
            if (ev != null) {
                s.append("Matriz de evidencias:\n");
                for (int e = 0; e < ev.length(); e++) {
                    JSONObject ee = ev.optJSONObject(e); if (ee == null) continue;
                    s.append("  ").append(ee.optString("code", "E" + (e + 1))).append(" · ")
                            .append(ee.optString("description", "")).append(" · ")
                            .append(fmt(ee.optDouble("points", 0))).append(" pt");
                    if (ee.optBoolean("partial_allowed", false)) s.append(" · parcial permitido: ").append(fmt(ee.optDouble("partial_points", 0)));
                    s.append("\n");
                }
            }
            appendStrings(s, "Requisitos esenciales", q.optJSONArray("essential_requirements"));
            appendStrings(s, "Errores previstos/reglas", q.optJSONArray("error_rules"));
            s.append("Error arrastrado: ").append(q.optString("carried_error_rule", "Regla general de la FMC")).append("\n");
            appendStrings(s, "Reglas numéricas", q.optJSONArray("numeric_rules"));
            JSONObject anchors = q.optJSONObject("anchors");
            if (anchors != null) {
                s.append("Anclaje alto: ").append(anchors.optString("high", "—")).append("\n");
                s.append("Anclaje intermedio: ").append(anchors.optString("medium", "—")).append("\n");
                s.append("Anclaje insuficiente: ").append(anchors.optString("insufficient", "—")).append("\n");
            }
        }
        return s.toString();
    }

    private String renderCorrection(JSONObject x) {
        StringBuilder s = new StringBuilder();
        JSONObject totals = x.optJSONObject("totals");
        s.append("CORRECCIÓN · ").append(x.optString("student_code", studentCode.getText().toString())).append("\n");
        s.append("Estado: ").append(x.optString("status", "provisional")).append("\n");
        s.append("FMC: ").append(x.optString("fmc_version", fmc == null ? "—" : fmc.optString("fmc_version", "1"))).append("\n");
        if (totals != null) {
            s.append("Puntos: ").append(numOrDash(totals, "awarded_points")).append(" / ").append(numOrDash(totals, "max_points")).append("\n");
            s.append("Nota bruta: ").append(numOrDash(totals, "raw_grade_0_10")).append(" / 10\n");
            s.append("Penalización presentación: ").append(numOrDash(totals, "presentation_penalty")).append("\n");
            s.append("NOTA FINAL: ").append(numOrDash(totals, "final_grade_0_10")).append(" / 10\n");
        }
        appendStrings(s, "Incidencias bloqueantes", x.optJSONArray("blocking_issues"));
        JSONArray qs = x.optJSONArray("question_results");
        if (qs != null) for (int i = 0; i < qs.length(); i++) {
            JSONObject q = qs.optJSONObject(i); if (q == null) continue;
            s.append("\n## ").append(q.optString("id", "Q" + (i + 1))).append(" · ").append(q.optString("question", "")).append("\n");
            s.append("Puntuación: ").append(numOrDash(q, "awarded_points")).append(" / ").append(numOrDash(q, "max_points")).append("\n");
            s.append("Legibilidad: ").append(q.optString("legibility", "clear")).append("\n");
            JSONArray ev = q.optJSONArray("evidence_vector");
            if (ev != null) {
                s.append("Vector de evidencias:\n");
                for (int e = 0; e < ev.length(); e++) {
                    JSONObject ee = ev.optJSONObject(e); if (ee == null) continue;
                    s.append("  ").append(ee.optString("code", "E" + (e + 1))).append(" · ")
                            .append(ee.optString("state", "")).append(" · ")
                            .append(numOrDash(ee, "awarded_points")).append(" pt · ")
                            .append(ee.optString("description", "")).append("\n");
                }
            }
            JSONArray errors = q.optJSONArray("errors");
            if (errors != null && errors.length() > 0) {
                s.append("Errores:\n");
                for (int e = 0; e < errors.length(); e++) {
                    JSONObject er = errors.optJSONObject(e); if (er == null) continue;
                    s.append("  - ").append(er.optString("type", "error")).append(": ").append(er.optString("description", ""));
                    if (er.optBoolean("is_carried_error", false)) s.append(" [ERROR ARRASTRADO]");
                    if (!er.optBoolean("penalized_here", true)) s.append(" [sin nueva penalización]");
                    s.append("\n");
                }
            }
            s.append("Justificación: ").append(q.optString("justification", "—")).append("\n");
        }
        s.append("\n## Informe técnico\n").append(x.optString("technical_report", "—")).append("\n");
        appendStrings(s, "Feedback pedagógico", x.optJSONArray("feedback"));
        return s.toString();
    }

    private String buildReportText() {
        StringBuilder s = new StringBuilder();
        s.append("Prueba: ").append(assessmentTitle.getText()).append("\n");
        s.append("Alumno/código: ").append(studentCode.getText()).append("\n");
        s.append("Marco: Química 2.º Bachillerato · LOMLOE Andalucía\n");
        s.append("FMC bloqueada: ").append(fmcConfirmed ? "sí" : "no").append("\n\n");
        s.append(renderCorrection(correction));
        return s.toString();
    }

    private void updateFmcLockUi() {
        boolean locked = fmcConfirmed;
        assessmentTitle.setEnabled(!locked); assessmentId.setEnabled(!locked); unit.setEnabled(!locked);
        duration.setEnabled(!locked); totalPoints.setEnabled(!locked); testType.setEnabled(!locked);
        level.setEnabled(!locked); presentation.setEnabled(!locked); autoAllocate.setEnabled(!locked);
        chooseMaster.setEnabled(!locked); generateFmc.setEnabled(!locked);
        confirmFmc.setEnabled(fmc != null && !locked);
        chooseStudent.setEnabled(locked); gradeButton.setEnabled(locked);
        savePdf.setEnabled(correction != null); addDocio.setEnabled(correction != null); nextStudent.setEnabled(locked);
    }

    private void setBusy(boolean busy, String message) {
        runOnUiThread(() -> {
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
            progressText.setText(message == null ? "" : message);
            generateFmc.setEnabled(!busy && !fmcConfirmed);
            gradeButton.setEnabled(!busy && fmcConfirmed);
            testBackend.setEnabled(!busy);
        });
    }

    private void fail(Exception e) {
        runOnUiThread(() -> { setBusy(false, ""); toast("Error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); });
    }

    private String normalizedBaseUrl() {
        String s = backendUrl.getText().toString().trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void appendStrings(StringBuilder s, String heading, JSONArray arr) {
        if (arr == null || arr.length() == 0) return;
        s.append(heading).append(":\n");
        for (int i = 0; i < arr.length(); i++) {
            Object value = arr.opt(i);
            if (value instanceof JSONObject) s.append("  - ").append(((JSONObject) value).optString("description", value.toString())).append("\n");
            else s.append("  - ").append(String.valueOf(value)).append("\n");
        }
    }

    private List<String> jsonStrings(JSONArray arr) {
        ArrayList<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
        return out;
    }

    private String joinJson(JSONArray arr) {
        if (arr == null || arr.length() == 0) return "—";
        ArrayList<String> xs = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) xs.add(arr.optString(i));
        return String.join(", ", xs);
    }

    private String numOrDash(JSONObject o, String key) {
        return o == null || o.isNull(key) ? "—" : fmt(o.optDouble(key));
    }

    private String fmt(double x) {
        if (!Double.isFinite(x)) return "—";
        return String.format(Locale.getDefault(), "%.2f", x);
    }

    private double parsePositive(String text, double fallback) {
        try { return Double.parseDouble(text.trim().replace(',', '.')); } catch (Exception e) { return fallback; }
    }

    private void setSpinner(Spinner s, String value) {
        for (int i = 0; i < s.getCount(); i++) if (String.valueOf(s.getItemAtPosition(i)).equals(value)) { s.setSelection(i); return; }
    }

    private String safeFileName(String s) {
        String x = s == null ? "informe" : s.trim().replaceAll("[^\\p{L}\\p{N}._-]+", "_");
        return x.isEmpty() ? "informe" : x;
    }

    private TextView section(String s) {
        TextView t = text(s, 18, true); t.setTextColor(Color.rgb(23, 54, 93));
        t.setPadding(0, dp(22), 0, dp(8)); return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 12, false); t.setBackgroundColor(Color.rgb(238, 244, 250)); t.setPadding(dp(12), dp(10), dp(12), dp(10)); return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 12, true); t.setPadding(0, dp(10), 0, dp(4)); return t;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.rgb(24, 34, 48));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setLineSpacing(0, 1.08f); return t;
    }

    private EditText edit(String hint, String value) {
        EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setTextSize(14); e.setSingleLine(true);
        e.setPadding(dp(10), dp(9), dp(10), dp(9)); return e;
    }

    private EditText numberEdit(String hint, String value) {
        EditText e = edit(hint, value); e.setInputType(InputType.TYPE_CLASS_NUMBER); return e;
    }

    private EditText decimalEdit(String hint, String value) {
        EditText e = edit(hint, value); e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); return e;
    }

    private Spinner spinner(String[] values) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(a); return s;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this); b.setText(text); b.setOnClickListener(l); b.setAllCaps(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(7), 0, 0); b.setLayoutParams(p); return b;
    }

    private int dp(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
