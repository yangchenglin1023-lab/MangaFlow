package com.deepwork.rpgmvviewer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 文件夹浏览器（添加画册用）：找到目标文件夹后，
 * 打开其中的图片会自动加入画册；也可手动收藏当前文件夹。
 */
public class FolderPickerActivity extends Activity {

    private File currentDir;
    private TextView pathView;
    private Button galleryBtn, favBtn, sortBtn;
    private ListView listView;
    private RowAdapter adapter;
    private final List<File> rows = new ArrayList<>();
    private final List<File> imageFiles = new ArrayList<>();
    private AlbumStore store;
    private int sortMode = MainActivity.SORT_NAME;
    private boolean askedOnce = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new AlbumStore(new File(getFilesDir(), "albums.txt"));
        sortMode = MainActivity.loadSortMode(this);
        String start = getIntent().getStringExtra("start");
        currentDir = new File(Environment.getExternalStorageDirectory(), "");
        if (start != null) {
            File f = new File(start);
            while (f != null && !f.isDirectory()) f = f.getParentFile();
            if (f != null) currentDir = f;
        }
        buildUi();
        refresh();
        if (!hasStorageAccess()) requestStorage();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---------- 权限 ----------

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    @SuppressLint("InlinedApi")
    private void requestStorage() {
        if (askedOnce) return;
        askedOnce = true;
        if (Build.VERSION.SDK_INT >= 30) {
            new AlertDialog.Builder(this)
                    .setTitle("需要「所有文件访问」权限")
                    .setMessage("用于浏览手机存储中的图片文件夹。\n\n请在接下来打开的系统页面中，允许本应用「管理所有文件」，然后返回。")
                    .setPositiveButton("去授权", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    })
                    .setNegativeButton("暂不", null)
                    .show();
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    // ---------- UI ----------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(0xFF161B22);
        top.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView title = new TextView(this);
        title.setText("漫流 · 添加画册");
        title.setTextColor(0xFFE6E9EE);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        pathView = new TextView(this);
        pathView.setTypeface(Typeface.MONOSPACE);
        pathView.setTextSize(11);
        pathView.setTextColor(0xFF8A93A0);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pathView.setPadding(0, dp(3), 0, 0);

        galleryBtn = new Button(this);
        galleryBtn.setText("查看本文件夹全部图片");
        galleryBtn.setAllCaps(false);
        galleryBtn.setOnClickListener(v -> openGallery(0));

        favBtn = new Button(this);
        favBtn.setText("收藏此文件夹");
        favBtn.setAllCaps(false);
        favBtn.setOnClickListener(v -> {
            boolean added = store.add(currentDir.getAbsolutePath());
            Toast.makeText(this, added ? "已加入画册" : "画册里已有此文件夹", Toast.LENGTH_SHORT).show();
        });

        sortBtn = new Button(this);
        sortBtn.setAllCaps(false);
        sortBtn.setOnClickListener(v -> showSortDialog());

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.setPadding(0, dp(6), 0, 0);
        r1.addView(galleryBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, -2, 1f);
        fp.leftMargin = dp(8);
        r1.addView(favBtn, fp);

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.setPadding(0, dp(6), 0, 0);
        r2.addView(sortBtn, new LinearLayout.LayoutParams(-1, -2));

        top.addView(title);
        top.addView(pathView);
        top.addView(r1);
        top.addView(r2);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        listView = new ListView(this);
        listView.setDivider(null);
        adapter = new RowAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> onTap(rows.get(pos)));
        root.addView(listView, new LinearLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    private void showSortDialog() {
        final String[] items = {"文件名（自然排序，34 在 223 前）", "修改时间（新 → 旧）", "修改时间（旧 → 新）"};
        new AlertDialog.Builder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(items, sortMode, (d, w) -> {
                    sortMode = w;
                    getSharedPreferences("prefs", MODE_PRIVATE).edit().putInt("sort", w).apply();
                    d.dismiss();
                    refresh();
                })
                .show();
    }

    private void onTap(File f) {
        if (f.isDirectory()) {
            currentDir = f;
            refresh();
            return;
        }
        String ext = MainActivity.extOf(f);
        if (MainActivity.isImageExt(ext)) {
            int idx = imageFiles.indexOf(f);
            if (idx < 0) idx = 0;
            store.add(currentDir.getAbsolutePath());
            GalleryActivity.start(this, currentDir, idx, sortMode);
        } else if (MainActivity.isAudioExt(ext)) {
            AudioActivity.start(this, f);
        }
    }

    private void openGallery(int idx) {
        if (imageFiles.isEmpty()) {
            Toast.makeText(this, "此文件夹里没有图片（rpgmvp / png_ / png …）", Toast.LENGTH_SHORT).show();
            return;
        }
        store.add(currentDir.getAbsolutePath());
        GalleryActivity.start(this, currentDir,
                Math.max(0, Math.min(idx, imageFiles.size() - 1)), sortMode);
    }

    // ---------- 数据 ----------

    void refresh() {
        rows.clear();
        imageFiles.clear();
        FolderInfo.clearCache();
        if (!hasStorageAccess()) {
            galleryBtn.setEnabled(false);
            favBtn.setEnabled(false);
            pathView.setText(currentDir.getAbsolutePath() + "（未授权）");
            rows.add(null);
            adapter.notifyDataSetChanged();
            return;
        }
        galleryBtn.setEnabled(true);
        favBtn.setEnabled(true);

        List<File> dirs = new ArrayList<>();
        List<File> files = new ArrayList<>();
        File[] all = currentDir.listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.isDirectory()) {
                    if (!f.getName().startsWith(".")) dirs.add(f);
                } else {
                    String e = MainActivity.extOf(f);
                    if (MainActivity.isImageExt(e)) files.add(f);
                    else if (MainActivity.isAudioExt(e)) files.add(f);
                }
            }
        }
        Collections.sort(dirs, (a, b) -> NaturalOrder.compare(a.getName(), b.getName()));
        MainActivity.sortByMode(files, sortMode);
        rows.addAll(dirs);
        rows.addAll(files);
        for (File f : files) {
            if (MainActivity.isImageExt(MainActivity.extOf(f))) imageFiles.add(f);
        }
        if (rows.isEmpty()) rows.add(null);

        String[] labels = {"排序：名称", "排序：时间新→旧", "排序：时间旧→新"};
        sortBtn.setText(labels[sortMode]);
        pathView.setText(currentDir.getAbsolutePath());
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onBackPressed() {
        File parent = currentDir.getParentFile();
        if (parent != null && !currentDir.equals(Environment.getExternalStorageDirectory())) {
            currentDir = parent;
            refresh();
        } else {
            super.onBackPressed();
        }
    }

    // ---------- 列表 ----------

    private class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            File f = rows.get(position);
            if (f == null) {
                TextView t = new TextView(FolderPickerActivity.this);
                t.setText(hasStorageAccess() ? "此文件夹里没有可显示的文件" : "未获得存储权限");
                t.setPadding(dp(20), dp(30), dp(20), dp(30));
                t.setTextColor(0xFF8A93A0);
                t.setGravity(Gravity.CENTER);
                return t;
            }

            LinearLayout row = new LinearLayout(FolderPickerActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));

            TextView badge = new TextView(FolderPickerActivity.this);
            badge.setGravity(Gravity.CENTER);
            badge.setTextSize(12);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(6));
            String kind;
            if (f.isDirectory()) {
                kind = "目录";
                bg.setColor(0xFF2A313C);
                badge.setTextColor(0xFF9FB0C3);
            } else if (MainActivity.isImageExt(MainActivity.extOf(f))) {
                kind = "图";
                bg.setColor(0x333FBFB2);
                badge.setTextColor(0xFF3FBFB2);
            } else {
                kind = "音";
                bg.setColor(0x33E8A33D);
                badge.setTextColor(0xFFE8A33D);
            }
            badge.setText(kind);
            badge.setBackground(bg);
            row.addView(badge, new LinearLayout.LayoutParams(dp(44), dp(28)));

            LinearLayout stack = new LinearLayout(FolderPickerActivity.this);
            stack.setOrientation(LinearLayout.VERTICAL);
            stack.setPadding(dp(12), 0, 0, 0);

            TextView name = new TextView(FolderPickerActivity.this);
            name.setText(f.getName());
            name.setTextColor(0xFFE6E9EE);
            name.setTextSize(15);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);

            TextView info = new TextView(FolderPickerActivity.this);
            info.setTextSize(11);
            info.setTextColor(0xFF8A93A0);
            if (f.isDirectory()) {
                info.setText(FolderInfo.describe(f));
            } else {
                String enc = MainActivity.extOf(f).startsWith("rpgmv")
                        || MainActivity.extOf(f).endsWith("_") ? " · 已加密" : "";
                info.setText(fmtSize(f.length()) + enc);
            }

            stack.addView(name);
            stack.addView(info);
            row.addView(stack, new LinearLayout.LayoutParams(0, -2, 1f));
            return row;
        }
    }

    private static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f KB", b / 1024.0);
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
