package com.deepwork.rpgmvviewer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 垂直连续画廊（条漫式）：文件夹内所有图片无缝拼接成一条长图，
 * 上下惯性滚动连续查看（rpgmvp / png_ 自动解密）。
 * 位图缓存窗口为当前页前后数页，由单线程后台解码，滚动到哪里预加载到哪里。
 */
public class GalleryActivity extends Activity implements VerticalReaderView.Host {

    static void start(Activity from, File dir, int index, int sortMode) {
        Intent it = new Intent(from, GalleryActivity.class);
        it.putExtra("dir", dir.getAbsolutePath());
        it.putExtra("index", index);
        it.putExtra("sort", sortMode);
        from.startActivity(it);
    }

    /** 位图缓存字节预算：min(96MB, 应用堆的 1/4)。按“距当前页远近”淘汰，防止 OOM 崩溃。 */
    private static final long CACHE_BUDGET_CAP = 96L * 1024 * 1024;
    private long cacheBytes = 0L;

    private final List<File> images = new ArrayList<>();
    private int index = 0;
    private int sortMode = MainActivity.SORT_NAME;
    private String keyHex;
    private String dirPath;

    private VerticalReaderView reader;
    private TextView nameView;
    private TextView indexView;
    private LinearLayout topBar, bottomBar, sliderBar;
    private android.widget.SeekBar seek;
    private TextView seekLabel;
    private boolean seekDragging = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final Map<Integer, Bitmap> cache = new HashMap<>();
    private final Set<Integer> queued = new HashSet<>();
    private final Deque<Integer> pending = new ArrayDeque<>();
    private volatile Thread worker;
    private volatile boolean destroyed = false;

    /** 每页解码后的原始宽高（回收位图后仍保留，保证滚动高度不回退、不跳动）。 */
    private final Map<Integer, int[]> wh = new HashMap<>();
    /** 本文件夹已解码图片的平均高宽比（自适应估计未解码页的高度，越滑越准）。 */
    private float avgAspect = 1.0f;
    /** 当前像素预加载带 [bandFirst, bandLast]。 */
    private int bandFirst = -1, bandLast = -1;
    private int lastPrune = -999;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dirPath = getIntent().getStringExtra("dir");
        File dir = new File(dirPath);
        index = Math.max(0, getIntent().getIntExtra("index", 0));
        sortMode = getIntent().getIntExtra("sort", MainActivity.SORT_NAME);
        if (savedInstanceState != null) {
            // Activity 被系统回收重建：恢复浏览位置，绝不跳回第一张
            index = Math.max(0, savedInstanceState.getInt("scrollIndex", index));
            sortMode = savedInstanceState.getInt("sort", sortMode);
        }
        collectImages(dir);
        if (images.isEmpty()) {
            Toast.makeText(this, "文件夹里没有图片", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (index >= images.size()) index = images.size() - 1;
        buildUi();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // System.json 很小，同步读取密钥即可（读不到则走无密钥 PNG 反推）
        keyHex = RpgmvCrypto.findKeyHex(images.get(0));

        for (int k = index - 1; k <= index + 4; k++) {
            requestLoad(k);
        }
        updateBars();
    }

    private void collectImages(File dir) {
        File[] all = dir.listFiles();
        if (all == null) return;
        for (File f : all) {
            if (f.isFile() && MainActivity.isImageExt(MainActivity.extOf(f))) images.add(f);
        }
        MainActivity.sortByMode(images, sortMode);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0C10);

        reader = new VerticalReaderView(this, this, index);
        root.addView(reader, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setBackgroundColor(0xCC10151C);
        topBar.setPadding(dp(16), dp(12), dp(16), dp(12));
        nameView = new TextView(this);
        nameView.setTextColor(0xFFFFFFFF);
        nameView.setTextSize(15);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        indexView = new TextView(this);
        indexView.setTextColor(0xFF9FB0C3);
        indexView.setTextSize(12);
        indexView.setPadding(0, dp(3), 0, 0);
        topBar.addView(nameView);
        topBar.addView(indexView);
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setBackgroundColor(0xCC10151C);
        bottomBar.setPadding(dp(16), dp(10), dp(16), dp(4));
        Button export = new Button(this);
        export.setText("导出当前图为 PNG");
        export.setAllCaps(false);
        export.setOnClickListener(v -> exportCurrent());
        TextView hint = new TextView(this);
        hint.setText("上下滑动连续看图 · 双指缩放 · 双击放大 · 单击隐藏工具栏");
        hint.setTextColor(0xFF7E8894);
        hint.setTextSize(11);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, dp(2));
        bottomBar.addView(export);
        bottomBar.addView(hint);

        // 常驻底部滑动条：几百张图快速定位（拖动实时跳转）
        sliderBar = new LinearLayout(this);
        sliderBar.setOrientation(LinearLayout.HORIZONTAL);
        sliderBar.setGravity(Gravity.CENTER_VERTICAL);
        sliderBar.setBackgroundColor(0xCC10151C);
        sliderBar.setPadding(dp(16), dp(2), dp(16), dp(12));
        seek = new android.widget.SeekBar(this);
        seek.setMax(10000);
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
                if (fromUser && reader != null) {
                    reader.scrollToFraction(p / 10000f);
                    seekLabel.setText((index + 1) + "/" + images.size());
                }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {
                seekDragging = true;
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {
                seekDragging = false;
            }
        });
        seekLabel = new TextView(this);
        seekLabel.setTextColor(0xFF9FB0C3);
        seekLabel.setTextSize(12);
        seekLabel.setPadding(dp(10), 0, 0, 0);
        sliderBar.addView(seek, new LinearLayout.LayoutParams(0, -2, 1f));
        sliderBar.addView(seekLabel);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.addView(bottomBar);
        bottom.addView(sliderBar);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
    }

    // ---------- VerticalReaderView.Host ----------

    @Override
    public int pageCount() {
        return images.size();
    }

    @Override
    public Bitmap pageBitmap(int i) {
        if (i < 0 || i >= images.size()) return null;
        Bitmap b;
        synchronized (lock) {
            b = cache.get(i);
        }
        if (b == null) requestLoad(i);
        return b;
    }

    @Override
    public int pageHeightHint(int i) {
        int vw = viewW();
        int[] d = wh.get(i);
        if (d != null && d[0] > 0) {
            return (int) Math.min(Integer.MAX_VALUE, (long) d[1] * vw / d[0]);
        }
        // 未解码：用已解码图片的平均高宽比估计（自适应，越滑越准）
        return Math.max(1, Math.round(vw * avgAspect));
    }

    @Override
    public void onVisiblePageChanged(int i) {
        index = i;
        // 缓存整理节流：翻过 2 页或首次才整理
        if (Math.abs(i - lastPrune) >= 2 || lastPrune == -999) {
            lastPrune = i;
            trimCache();
        }
        updateBars();
    }

    @Override
    public void preloadBand(int first, int last) {
        first = Math.max(0, first - 1);
        last = Math.min(images.size() - 1, last + 1);
        bandFirst = first;
        bandLast = last;
        synchronized (lock) {
            // 丢掉带外的排队请求，让视野优先
            Iterator<Integer> it = pending.iterator();
            while (it.hasNext()) {
                int k = it.next();
                if (k < first || k > last) {
                    it.remove();
                    queued.remove(k);
                }
            }
        }
        // 视口优先：先排当前位置 → 前方，再排后方
        for (int k = Math.max(first, index); k <= last; k++) {
            requestLoad(k);
        }
        for (int k = Math.min(last, index - 1); k >= first; k--) {
            requestLoad(k);
        }
    }

    @Override
    public void onSingleTap() {
        boolean show = topBar.getVisibility() != View.VISIBLE;
        topBar.setVisibility(show ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ---------- 解码 ----------

    private void requestLoad(final int i) {
        if (destroyed || i < 0 || i >= images.size()) return;
        synchronized (lock) {
            if (cache.containsKey(i) || queued.contains(i)) return;
            queued.add(i);
            pending.addLast(i);
        }
        kickWorker();
    }

    private void kickWorker() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(() -> {
            while (!destroyed) {
                Integer i;
                synchronized (lock) {
                    i = pending.pollFirst();
                }
                if (i == null) break;
                Bitmap bmp = decode(images.get(i));
                final int idx = i;
                final Bitmap res = bmp;
                main.post(() -> onDecoded(idx, res));
            }
        }, "rpgmv-decoder");
        worker.setPriority(Thread.NORM_PRIORITY - 1); // 让 UI 线程优先，滚动更顺
        worker.start();
    }

    private void onDecoded(int i, Bitmap bmp) {
        if (destroyed) return; // 直接丢弃引用，由 GC 回收（避免 recycle 与渲染线程竞态崩溃）
        if (bmp != null) {
            // 记录真实宽高并平滑更新平均高宽比（供未解码页高度估计）
            wh.put(i, new int[]{bmp.getWidth(), bmp.getHeight()});
            float asp = bmp.getHeight() / (float) Math.max(1, bmp.getWidth());
            avgAspect = avgAspect <= 0f ? asp : (avgAspect * 4f + asp) / 5f;
        }
        boolean near = (i >= bandFirst && i <= bandLast) || Math.abs(i - index) <= 3;
        boolean cached;
        synchronized (lock) {
            queued.remove(i);
            cached = cache.containsKey(i);
            if (near && !cached && bmp != null) {
                cache.put(i, bmp);
                cacheBytes += bmp.getByteCount();
                cached = true;
            }
        }
        if (!cached) return; // 未入缓存：直接丢引用
        trimCache();
        reader.onPageReady(i);
    }

    private Bitmap decode(File f) {
        try {
            byte[] plain = RpgmvCrypto.decryptSmart(RpgmvCrypto.readAll(f), keyHex);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(plain, 0, plain.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int capW = viewW() * 2;   // 宽度上限 2 倍屏宽（兼顾缩放清晰度）
            // 像素上限自适应内存预算（低端机自动降低分辨率，防 OOM）
            long budget = Math.min(CACHE_BUDGET_CAP, Runtime.getRuntime().maxMemory() / 4);
            long capPx = Math.min(6_000_000L, Math.max(1_500_000L, budget / 6));
            int sample = 1;
            while (o.outWidth / (sample * 2) >= capW
                    || (long) (o.outWidth / sample) * (o.outHeight / sample) > capPx) {
                sample *= 2;
            }
            for (int attempt = 0; attempt < 4; attempt++) {
                try {
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    return BitmapFactory.decodeByteArray(plain, 0, plain.length, o2);
                } catch (OutOfMemoryError e) {
                    sample *= 2;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按字节预算整理缓存：超出预算时按“距当前页由远到近”淘汰（永不淘汰当前页）。
     * 淘汰只丢引用不 recycle，由 GC 回收——彻底消灭 recycle 与硬件加速渲染线程的竞态崩溃。
     */
    private void trimCache() {
        long budget = Math.min(CACHE_BUDGET_CAP, Runtime.getRuntime().maxMemory() / 4);
        synchronized (lock) {
            if (cacheBytes <= budget) return;
            Integer[] keys = cache.keySet().toArray(new Integer[0]);
            java.util.Arrays.sort(keys, (a, b) -> Integer.compare(Math.abs(b - index), Math.abs(a - index)));
            for (int k : keys) {
                if (cacheBytes <= budget * 7 / 10) break;
                if (k == index) continue;
                Bitmap b = cache.remove(k);
                if (b != null) cacheBytes -= b.getByteCount();
            }
        }
    }

    private int viewW() {
        int w = reader != null ? reader.getWidth() : 0;
        if (w <= 0) w = getResources().getDisplayMetrics().widthPixels;
        return Math.max(w, 320);
    }

    // ---------- UI 状态 ----------

    private void updateBars() {
        nameView.setText(images.get(index).getName());
        indexView.setText(String.format(Locale.US, "%d / %d", index + 1, images.size()));
        updateSlider();
    }

    private void updateSlider() {
        if (seekDragging || reader == null || seek == null) return;
        seek.setProgress(Math.round(reader.scrollFraction() * 10000f));
        seekLabel.setText((index + 1) + "/" + images.size());
    }

    // ---------- 导出 ----------

    private void exportCurrent() {
        final File f = images.get(index);
        toast("正在导出 " + f.getName() + " …");
        new Thread(() -> {
            try {
                byte[] plain = RpgmvCrypto.decryptSmart(RpgmvCrypto.readAll(f), keyHex);
                String base = f.getName().replaceAll("\\.[^.]*$", "");
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "RPGMV导出");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建导出目录");
                File out = new File(dir, base + ".png");
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    fo.write(plain);
                }
                toast("已导出到 " + out.getAbsolutePath());
            } catch (Exception e) {
                toast("导出失败：" + e.getMessage());
            }
        }).start();
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    // ---------- 沉浸式 & 按键 ----------

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressLint("InlinedApi")
    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            reader.scrollPageDown();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            reader.scrollPageUp();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // 实时保存浏览位置，Activity 被系统回收后原位恢复
        outState.putInt("scrollIndex", reader != null && reader.getVisiblePage() >= 0
                ? reader.getVisiblePage() : index);
        outState.putInt("sort", sortMode);
    }

    @Override
    protected void onDestroy() {
        // 记录浏览进度：下次从画册进入时从上次位置继续（画册不存在时为空操作）
        if (dirPath != null && reader != null) {
            int last = reader.getVisiblePage() >= 0 ? reader.getVisiblePage() : index;
            try {
                new AlbumStore(new File(getFilesDir(), "albums.txt")).touch(dirPath, last);
            } catch (Exception ignored) {
            }
        }
        destroyed = true;
        synchronized (lock) {
            cache.clear(); // 只丢引用，GC 回收，避免回收竞态
            cacheBytes = 0L;
            pending.clear();
            queued.clear();
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
