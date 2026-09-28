package com.deepwork.rpgmvviewer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
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
import android.widget.EditText;
import android.widget.FrameLayout;
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
 * 主页：我的画册。收藏的图片文件夹列表（最近打开在前），
 * 点开从上次看到的位置继续；长按可重命名 / 删除 / 定位。
 */
public class MainActivity extends Activity {

    /** 排序方式：0=文件名自然排序 1=修改时间新→旧 2=修改时间旧→新。 */
    static final int SORT_NAME = 0, SORT_TIME_DESC = 1, SORT_TIME_ASC = 2;

    static final String[] IMAGE_EXTS = {
            "rpgmvp", "png_", "png", "jpg", "jpeg", "jfif", "jpe",
            "webp", "webp_", "gif", "gif_", "bmp", "bmp_",
            "jpg_", "jpeg_", "heic", "heif"};
    static final String[] AUDIO_EXTS = {
            "rpgmvo", "rpgmvm", "ogg_", "m4a_", "ogg", "m4a", "mp3", "wav", "wav_"};

    static boolean isImageExt(String e) {
        for (String x : IMAGE_EXTS) if (x.equals(e)) return true;
        return false;
    }

    static boolean isAudioExt(String e) {
        for (String x : AUDIO_EXTS) if (x.equals(e)) return true;
        return false;
    }

    static String extOf(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.US);
    }

    /** 按当前模式排序（文件浏览器与画廊共用，保证顺序一致）。 */
    static void sortByMode(List<File> list, int mode) {
        if (mode == SORT_TIME_DESC) {
            Collections.sort(list, (a, b) -> {
                int c = Long.compare(b.lastModified(), a.lastModified());
                return c != 0 ? c : NaturalOrder.compare(a.getName(), b.getName());
            });
        } else if (mode == SORT_TIME_ASC) {
            Collections.sort(list, (a, b) -> {
                int c = Long.compare(a.lastModified(), b.lastModified());
                return c != 0 ? c : NaturalOrder.compare(a.getName(), b.getName());
            });
        } else {
            Collections.sort(list, (a, b) -> NaturalOrder.compare(a.getName(), b.getName()));
        }
    }

    static int loadSortMode(Activity a) {
        return a.getSharedPreferences("prefs", MODE_PRIVATE).getInt("sort", SORT_NAME);
    }

    private AlbumStore store;
    private ListView listView;
    private RowAdapter adapter;
    private final List<AlbumStore.Album> albums = new ArrayList<>();
    private boolean askedOnce = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new AlbumStore(new File(getFilesDir(), "albums.txt"));
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    // ---------- UI ----------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(0xFF161B22);
        top.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView title = new TextView(this);
        title.setText("漫流 · 我的画册");
        title.setTextColor(0xFFE6E9EE);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView sub = new TextView(this);
        sub.setText("漫画 / 图包 / 游戏资源 · 点开从上次位置继续看");
        sub.setTextColor(0xFF8A93A0);
        sub.setTextSize(12);
        sub.setPadding(0, dp(3), 0, 0);
        top.addView(title);
        top.addView(sub);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout wrap = new FrameLayout(this);
        listView = new ListView(this);
        listView.setDivider(null);
        adapter = new RowAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> openAlbum(albums.get(pos)));
        listView.setOnItemLongClickListener((p, v, pos, id) -> {
            albumMenu(albums.get(pos));
            return true;
        });
        wrap.addView(listView, new FrameLayout.LayoutParams(-1, -1));

        TextView empty = new TextView(this);
        empty.setText("还没有画册\n\n点下方「添加画册」浏览手机存储，\n漫画、图包或游戏图片文件夹都可以加入，\n打开文件夹里的图片会自动收藏");
        empty.setTextColor(0xFF8A93A0);
        empty.setGravity(Gravity.CENTER);
        wrap.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        listView.setEmptyView(empty);
        root.addView(wrap, new LinearLayout.LayoutParams(-1, -1, 1f));

        Button add = new Button(this);
        add.setText("＋ 添加画册（浏览文件夹）");
        add.setAllCaps(false);
        add.setOnClickListener(v -> startActivity(new Intent(this, FolderPickerActivity.class)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(dp(16), dp(8), dp(16), dp(12));
        root.addView(add, bp);

        setContentView(root);
    }

    private void reload() {
        albums.clear();
        albums.addAll(store.list());
        FolderInfo.clearCache();
        adapter.notifyDataSetChanged();
    }

    // ---------- 画册操作 ----------

    private void openAlbum(AlbumStore.Album a) {
        File dir = new File(a.path);
        if (!dir.isDirectory()) {
            Toast.makeText(this, "文件夹不存在或已移动（长按画册可删除）", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasStorageAccess()) {
            requestStorage();
            return;
        }
        int imgs = FolderInfo.countImages(dir);
        if (imgs <= 0) {
            Toast.makeText(this, "此文件夹里没有可显示的图片", Toast.LENGTH_SHORT).show();
            return;
        }
        store.touch(a.path, Math.min(a.lastIndex, imgs - 1));
        GalleryActivity.start(this, dir, Math.min(a.lastIndex, imgs - 1), loadSortMode(this));
    }

    private void albumMenu(final AlbumStore.Album a) {
        final File dir = new File(a.path);
        new AlertDialog.Builder(this)
                .setTitle(a.name)
                .setItems(new String[]{"重命名", "删除画册", "在文件浏览器中定位"}, (d, w) -> {
                    if (w == 0) renameDialog(a);
                    else if (w == 1) confirmDelete(a);
                    else {
                        if (hasStorageAccess()) {
                            Intent it = new Intent(this, FolderPickerActivity.class);
                            it.putExtra("start", a.path);
                            startActivity(it);
                        } else {
                            requestStorage();
                        }
                    }
                })
                .show();
    }

    private void renameDialog(final AlbumStore.Album a) {
        final EditText input = new EditText(this);
        input.setText(a.name);
        input.setSelection(a.name.length());
        new AlertDialog.Builder(this)
                .setTitle("重命名画册")
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (n.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                    } else {
                        store.rename(a.path, n);
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final AlbumStore.Album a) {
        new AlertDialog.Builder(this)
                .setTitle("删除画册")
                .setMessage("从画册列表移除「" + a.name + "」？\n（不会删除手机里的文件夹和图片）")
                .setPositiveButton("删除", (d, w) -> {
                    store.remove(a.path);
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
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
                    .setMessage("用于读取手机存储里的图片文件夹。\n\n请在接下来打开的系统页面中，允许本应用「管理所有文件」，然后返回。")
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

    // ---------- 列表 ----------

    private class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return albums.size();
        }

        @Override
        public Object getItem(int position) {
            return albums.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AlbumStore.Album a = albums.get(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));

            TextView name = new TextView(MainActivity.this);
            name.setText(a.name);
            name.setTextColor(0xFFE6E9EE);
            name.setTextSize(16);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);

            TextView path = new TextView(MainActivity.this);
            path.setText(a.path);
            path.setTextColor(0xFF8A93A0);
            path.setTextSize(11);
            path.setTypeface(Typeface.MONOSPACE);
            path.setSingleLine(true);
            path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            path.setPadding(0, dp(2), 0, 0);

            TextView info = new TextView(MainActivity.this);
            File dir = new File(a.path);
            if (!dir.isDirectory()) {
                info.setText("路径不存在");
                info.setTextColor(0xFFE8A33D);
            } else {
                String s = FolderInfo.describe(dir);
                if (a.lastIndex > 0) s += " · 上次看到第 " + (a.lastIndex + 1) + " 张";
                info.setText(s);
                info.setTextColor(0xFF3FBFB2);
            }
            info.setTextSize(12);
            info.setPadding(0, dp(3), 0, 0);

            row.addView(name);
            row.addView(path);
            row.addView(info);
            return row;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
