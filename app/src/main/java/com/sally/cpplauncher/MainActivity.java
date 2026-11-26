package com.sally.cpplauncher;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private EditText etJvm, etGameDir, etClasspath, etMain, etJvmArgs, etUser, etVersion;
    private TextView tvLog;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        File ext = getExternalFilesDir(null);
        String base = ext != null ? ext.getAbsolutePath() : getFilesDir().getAbsolutePath();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        etJvm = field(root, "Path libjvm.so (JRE Android)", getFilesDir() + "/jre/lib/libjvm.so");
        etGameDir = field(root, "Folder game", base + "/.minecraft");
        etClasspath = field(root, "Classpath (dipisah ':')", base + "/.minecraft/client.jar");
        etMain = field(root, "Main class", "net.minecraft.client.main.Main");
        etJvmArgs = field(root, "Argumen JVM", "-Xmx1G -Xss1M");
        etUser = field(root, "Username", "Player");
        etVersion = field(root, "Versi", "1.21.11");

        Button btn = new Button(this);
        btn.setText("Launch");
        btn.setOnClickListener(v -> launch());
        root.addView(btn);

        tvLog = new TextView(this);
        tvLog.setTextSize(12);
        tvLog.setTypeface(Typeface.MONOSPACE);
        root.addView(tvLog);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        log("Siap. Pastikan JRE Android dan client.jar sudah ada.");
        log("Log game ada di logcat, tag MCGame dan CppLauncher.");
    }

    private void launch() {
        final String jvm = etJvm.getText().toString().trim();
        final String gameDir = etGameDir.getText().toString().trim();
        final String cp = etClasspath.getText().toString().trim();
        final String main = etMain.getText().toString().trim();
        final String user = etUser.getText().toString().trim();
        final String version = etVersion.getText().toString().trim();

        if (!new File(jvm).isFile()) {
            log("libjvm.so tidak ditemukan: " + jvm);
            return;
        }
        new File(gameDir).mkdirs();

        final String[] jvmArgs = splitArgs(etJvmArgs.getText().toString());
        final String[] gameArgs = {
                "--username", user,
                "--version", version,
                "--gameDir", gameDir,
                "--accessToken", "0",
                "--userType", "legacy"
        };

        log("Menjalankan " + main + "...");
        new Thread(() -> {
            int rc = LauncherNative.launch(jvm, gameDir, main, cp, jvmArgs, gameArgs);
            String note = rc == 0 ? " (JVM berjalan di background)" : "";
            log("native launch -> " + rc + note);
        }).start();
    }

    private static String[] splitArgs(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.trim().split("\\s+")) {
            if (!part.isEmpty()) out.add(part);
        }
        return out.toArray(new String[0]);
    }

    private void log(String msg) {
        ui.post(() -> tvLog.append(msg + "\n"));
    }

    private EditText field(LinearLayout root, String label, String value) {
        TextView t = new TextView(this);
        t.setText(label);
        root.addView(t);

        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        root.addView(e);
        return e;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
