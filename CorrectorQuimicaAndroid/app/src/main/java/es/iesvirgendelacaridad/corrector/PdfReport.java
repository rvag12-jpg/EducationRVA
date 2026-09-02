package es.iesvirgendelacaridad.corrector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public final class PdfReport {
    private static final int PAGE_W = 595;
    private static final int PAGE_H = 842;
    private static final int MARGIN = 42;
    private static final float BODY_SIZE = 9.8f;
    private static final float LINE_H = 13.2f;

    private PdfReport() {}

    public static void write(Context context, Uri outputUri, String title, String body) throws IOException {
        PdfDocument document = new PdfDocument();
        Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setTextSize(BODY_SIZE);
        bodyPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));

        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setTextSize(15f);
        titlePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));

        Paint headingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headingPaint.setTextSize(11f);
        headingPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));

        int pageNo = 0;
        PdfDocument.Page page = null;
        Canvas canvas = null;
        float y = MARGIN;

        try {
            page = startPage(document, ++pageNo);
            canvas = page.getCanvas();
            canvas.drawText(title == null ? "Informe de corrección" : title, MARGIN, y, titlePaint);
            y += 25f;

            String[] paragraphs = (body == null ? "" : body).replace("\r", "").split("\n", -1);
            for (String paragraph : paragraphs) {
                boolean heading = paragraph.startsWith("## ") || paragraph.startsWith("### ");
                String text = heading ? paragraph.replaceFirst("^#{2,3}\\s*", "") : paragraph;
                Paint paint = heading ? headingPaint : bodyPaint;

                if (text.trim().isEmpty()) {
                    y += LINE_H * 0.65f;
                    continue;
                }

                List<String> lines = wrap(text, paint, PAGE_W - 2f * MARGIN);
                for (String line : lines) {
                    if (y > PAGE_H - MARGIN) {
                        document.finishPage(page);
                        page = startPage(document, ++pageNo);
                        canvas = page.getCanvas();
                        y = MARGIN;
                    }
                    canvas.drawText(line, MARGIN, y, paint);
                    y += heading ? 16f : LINE_H;
                }
                if (heading) y += 2f;
            }

            if (page != null) document.finishPage(page);
            try (OutputStream out = context.getContentResolver().openOutputStream(outputUri, "w")) {
                if (out == null) throw new IOException("No se puede crear el PDF de salida.");
                document.writeTo(out);
            }
        } finally {
            document.close();
        }
    }

    private static PdfDocument.Page startPage(PdfDocument document, int number) {
        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create();
        return document.startPage(info);
    }

    private static List<String> wrap(String text, Paint paint, float maxWidth) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            result.add("");
            return result;
        }
        String prefix = "";
        String work = text;
        if (text.startsWith("- ")) {
            prefix = "- ";
            work = text.substring(2);
        } else if (text.matches("^\\d+\\. .*")) {
            int p = text.indexOf(' ');
            prefix = text.substring(0, p + 1);
            work = text.substring(p + 1);
        }

        String[] words = work.split("\\s+");
        StringBuilder line = new StringBuilder(prefix);
        String continuationPrefix = prefix.isEmpty() ? "" : "  ";
        for (String word : words) {
            if (word.isEmpty()) continue;
            String candidate = line.length() == 0 ? word : line + (line.toString().trim().isEmpty() ? "" : " ") + word;
            if (paint.measureText(candidate) <= maxWidth || line.length() == 0) {
                if (line.length() > 0 && !line.toString().endsWith(" ")) line.append(' ');
                line.append(word);
            } else {
                result.add(line.toString());
                line = new StringBuilder(continuationPrefix).append(word);
            }
        }
        if (line.length() > 0) result.add(line.toString());
        return result;
    }
}
