package com.example.snapstoneprinter.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

public final class PrintReceiverActivity extends Activity {
    private final String activityInstance = UUID.randomUUID().toString();
    private int readCount;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(24, 24, 24, 24);
        TextView receiptView = new TextView(this);
        receiptView.setText(readReceipt(getIntent()).toString());
        receiptView.setContentDescription("print-receiver-receipt");
        receiptView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(receiptView);
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        Button reread = new Button(this);
        reread.setText("Read URI again");
        reread.setContentDescription("print-receiver-reread");
        reread.setOnClickListener(view -> receiptView.setText(readReceipt(getIntent()).toString()));
        column.addView(reread, buttonLayout());
        addReturnButton(column, "Return OK", "print-receiver-return-ok", RESULT_OK);
        addReturnButton(column, "Return Cancel", "print-receiver-return-cancel", RESULT_CANCELED);
        setContentView(column);
    }

    private LinearLayout.LayoutParams buttonLayout() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addReturnButton(LinearLayout column, String label, String accessibilityId, int result) {
        Button button = new Button(this);
        button.setText(label);
        button.setContentDescription(accessibilityId);
        button.setOnClickListener(view -> {
            setResult(result);
            finish();
        });
        column.addView(button, buttonLayout());
    }

    private JSONObject readReceipt(Intent incoming) {
        JSONObject receipt = new JSONObject();
        try {
            receipt.put("activityInstance", activityInstance)
                .put("readCount", ++readCount)
                .put("action", nullable(incoming.getAction()))
                .put("mime", nullable(incoming.getType()))
                .put("uid", Process.myUid())
                .put("readGrant", (incoming.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
                .put("writeGrant", (incoming.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
                .put("error", JSONObject.NULL);
            Uri stream = incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            ClipData clip = incoming.getClipData();
            int clipCount = clip == null ? 0 : clip.getItemCount();
            Uri clipUri = clipCount == 0 ? null : clip.getItemAt(0).getUri();
            Integer index = null;
            if (stream != null && stream.getLastPathSegment() != null) {
                Matcher match = Pattern.compile("slip_([0-9]+)\\.png").matcher(stream.getLastPathSegment());
                if (match.matches()) index = Integer.valueOf(match.group(1));
            }
            receipt.put("streamUri", stream == null ? JSONObject.NULL : stream.toString())
                .put("clipUri", clipUri == null ? JSONObject.NULL : clipUri.toString())
                .put("clipCount", clipCount)
                .put("urisMatch", stream != null && stream.equals(clipUri) && clipCount == 1)
                .put("slipIndex", nullable(index));
            if (!Intent.ACTION_SEND.equals(incoming.getAction()) || !"image/png".equals(incoming.getType())) {
                throw new IllegalArgumentException("Expected ACTION_SEND image/png.");
            }
            if (stream == null || !"content".equals(stream.getScheme())) {
                throw new IllegalArgumentException("Expected a content URI stream.");
            }
            Bitmap bitmap;
            try (InputStream input = getContentResolver().openInputStream(stream)) {
                bitmap = BitmapFactory.decodeStream(input);
                if (bitmap == null) throw new IOException("The shared stream did not decode as an image.");
            }
            try {
                receipt.put("width", bitmap.getWidth())
                    .put("height", bitmap.getHeight())
                    .put("pixelSha256", pixelHash(bitmap));
            } finally {
                bitmap.recycle();
            }
        } catch (Exception failure) {
            try {
                receipt.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return receipt;
    }

    private Object nullable(Object value) {
        return value == null ? JSONObject.NULL : value;
    }

    private String pixelHash(Bitmap bitmap) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int[] row = new int[bitmap.getWidth()];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            bitmap.getPixels(row, 0, bitmap.getWidth(), 0, y, bitmap.getWidth(), 1);
            for (int pixel : row) {
                digest.update((byte) (pixel >>> 24));
                digest.update((byte) (pixel >>> 16));
                digest.update((byte) (pixel >>> 8));
                digest.update((byte) pixel);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }
}
