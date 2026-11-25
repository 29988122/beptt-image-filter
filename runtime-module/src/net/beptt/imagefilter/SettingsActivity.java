package net.beptt.imagefilter;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class SettingsActivity extends Activity {
    private static final int IMPORT = 1, EXPORT = 2, MAX_BYTES = 65536;
    private SharedPreferences prefs;
    private TextView status;
    private EditText urls;
    private CheckBox enabled;
    private Button save;
    private boolean loaded;
    private boolean dirty;
    private boolean changing;
    private static final java.util.Set<SettingsActivity> screens = new java.util.HashSet<>();
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static XposedService service;
    private static boolean registered;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        scroll.addView(layout);
        setContentView(scroll);
        status = new TextView(this);
        layout.addView(status);
        enabled = new CheckBox(this);
        enabled.setText("隱藏黑名單中的圖片與連結預覽");
        layout.addView(enabled);
        TextView hint = new TextView(this);
        hint.setText("每行一個完整圖片網址。可直接編輯，或從手機、Dropbox 選取 UTF-8 文字檔。儲存後請重新開啟文章。");
        layout.addView(hint);
        urls = new EditText(this);
        urls.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        urls.setMinLines(6);
        urls.setGravity(android.view.Gravity.TOP);
        layout.addView(urls);
        addButton(layout, "匯入文字檔", () -> choose(Intent.ACTION_OPEN_DOCUMENT, IMPORT));
        addButton(layout, "匯出文字檔", () -> choose(Intent.ACTION_CREATE_DOCUMENT, EXPORT));
        addButton(layout, "填入預設名單", () -> urls.setText(Config.DEFAULT_URLS));
        save = addButton(layout, "儲存設定", this::save);
        if (state == null) {
            enabled.setChecked(true);
            urls.setText(Config.DEFAULT_URLS);
        } else {
            enabled.setChecked(state.getBoolean("enabled"));
            urls.setText(state.getString("urls", Config.DEFAULT_URLS));
            loaded = state.getBoolean("loaded");
            dirty = state.getBoolean("dirty");
        }
        enabled.setOnCheckedChangeListener((ignored, checked) -> { if (!changing) dirty = true; });
        urls.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { if (!changing) dirty = true; }
            public void afterTextChanged(android.text.Editable editable) {}
        });
        screens.add(this);
        connect(service);
        registerServiceListener();
    }

    private static void registerServiceListener() {
        if (registered) return;
        registered = true;
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            public void onServiceBind(XposedService bound) {
                MAIN.post(() -> publish(bound));
            }

            public void onServiceDied(XposedService dead) {
                MAIN.post(() -> {
                    if (service == dead) publish(null);
                });
            }
        });
    }

    private static void publish(XposedService next) {
        service = next;
        for (SettingsActivity screen : screens) screen.connect(next);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("urls", urls.getText().toString());
        state.putBoolean("enabled", enabled.isChecked());
        state.putBoolean("loaded", loaded);
        state.putBoolean("dirty", dirty);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        screens.remove(this);
        super.onDestroy();
    }

    private Button addButton(LinearLayout layout, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        layout.addView(button);
        return button;
    }

    private void connect(XposedService service) {
        prefs = null;
        save.setEnabled(false);
        if (service == null) {
            status.setText("等待 Xposed 框架。請在 Vector／LSPosed 啟用模組，作用範圍只勾 BePTT。");
            return;
        }
        try {
            prefs = service.getRemotePreferences(Config.GROUP);
            if (!loaded && !dirty) {
                changing = true;
                try {
                    enabled.setChecked(prefs.getBoolean("enabled", true));
                    urls.setText(prefs.getString("urls", Config.DEFAULT_URLS));
                } finally {
                    changing = false;
                }
            }
            loaded = true;
            save.setEnabled(true);
            boolean scoped = service.getScope().contains("y_studio.ka5983.ptt2");
            status.setText(scoped ? "BePTT 已在模組作用範圍內" : "請在框架管理器的作用範圍勾選 BePTT");
        } catch (RuntimeException failure) {
            prefs = null;
            save.setEnabled(false);
            status.setText("無法連線至模組設定服務，請確認框架版本支援 libxposed。");
        }
    }

    private void save() {
        if (prefs == null) return;
        try {
            String entered = urls.getText().toString();
            if (entered.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                throw new IllegalArgumentException("名單超過 64 KiB");
            }
            BepttUrlRules rules = new BepttUrlRules(entered);
            if (!prefs.edit().putString("urls", rules.text()).putBoolean("enabled", enabled.isChecked()).commit()) {
                message("設定尚未儲存，請再試一次");
                return;
            }
            urls.setText(rules.text());
            dirty = false;
            message("已儲存 " + rules.urls.size() + " 個網址。請重新開啟文章");
        } catch (RuntimeException failure) {
            message("儲存失敗：" + failure.getMessage());
        }
    }

    private void choose(String action, int request) {
        Intent intent = new Intent(action).addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(request == IMPORT ? "*/*" : "text/plain");
        if (request == EXPORT) intent.putExtra(Intent.EXTRA_TITLE, "beptt-image-blocklist.txt");
        try {
            startActivityForResult(intent, request);
        } catch (android.content.ActivityNotFoundException failure) {
            message("這台裝置找不到檔案選擇器");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (request == IMPORT) {
                String imported = readText(uri);
                if (imported.startsWith("\uFEFF")) imported = imported.substring(1);
                BepttUrlRules rules = new BepttUrlRules(imported);
                urls.setText(rules.text());
                message("已讀取 " + rules.urls.size() + " 個網址，請按儲存設定");
            } else if (request == EXPORT) {
                BepttUrlRules rules = new BepttUrlRules(urls.getText().toString());
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException("無法開啟檔案");
                    output.write(rules.text().getBytes(StandardCharsets.UTF_8));
                }
                message("已匯出名單");
            }
        } catch (IOException | RuntimeException failure) {
            message("無法處理檔案：" + failure.getMessage());
        }
    }

    private String readText(Uri uri) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("無法開啟檔案");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (bytes.size() + count > MAX_BYTES) throw new IOException("名單超過 64 KiB");
                bytes.write(buffer, 0, count);
            }
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
        }
    }

    private void message(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
