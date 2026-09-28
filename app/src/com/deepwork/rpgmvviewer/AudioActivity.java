package com.deepwork.rpgmvviewer;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/** rpgmvo / rpgmvm / ogg_ / m4a_ 音频解密播放（OGG Vorbis / AAC）。 */
public class AudioActivity extends Activity {

    static void start(Activity from, File file) {
        Intent it = new Intent(from, AudioActivity.class);
        it.putExtra("file", file.getAbsolutePath());
        from.startActivity(it);
    }

    private MediaPlayer player;
    private File tmpFile;
    private Button playBtn;
    private SeekBar seek;
    private TextView timeView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean prepared = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (prepared && player != null && player.isPlaying()) {
                seek.setProgress(player.getCurrentPosition());
                timeView.setText(fmt(player.getCurrentPosition()) + " / " + fmt(player.getDuration()));
            }
            main.postDelayed(tick, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final File src = new File(getIntent().getStringExtra("file"));
        buildUi(src.getName());
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        new Thread(() -> {
            try {
                byte[] plain = RpgmvCrypto.decryptSmart(RpgmvCrypto.readAll(src),
                        RpgmvCrypto.findKeyHex(src));
                String ext = MainActivity.extOf(src);
                boolean m4a = ext.equals("m4a_") || ext.equals("rpgmvm") || ext.equals("m4a");
                tmpFile = new File(getCacheDir(), "play." + (m4a ? "m4a" : "ogg"));
                try (FileOutputStream fo = new FileOutputStream(tmpFile)) {
                    fo.write(plain);
                }
                main.post(this::preparePlayer);
            } catch (final Exception e) {
                main.post(() -> {
                    Toast.makeText(AudioActivity.this, "读取失败：" + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }).start();
    }

    private void preparePlayer() {
        if (isFinishing() || tmpFile == null) return;
        try {
            player = new MediaPlayer();
            player.setDataSource(tmpFile.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                prepared = true;
                playBtn.setEnabled(true);
                timeView.setText("0:00 / " + fmt(mp.getDuration()));
                seek.setMax(mp.getDuration());
                mp.start();
                playBtn.setText("暂停");
            });
            player.setOnCompletionListener(mp -> playBtn.setText("播放"));
            player.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, "此设备无法解码该音频", Toast.LENGTH_LONG).show();
                timeView.setText("无法解码");
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "初始化播放器失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void buildUi(String name) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(40));
        root.setBackgroundColor(0xFF0E1116);

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(0xFFE6E9EE);
        title.setTextSize(16);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(8));

        timeView = new TextView(this);
        timeView.setText("解密中…");
        timeView.setTextColor(0xFF8A93A0);
        timeView.setGravity(Gravity.CENTER);
        timeView.setPadding(0, 0, 0, dp(16));

        seek = new SeekBar(this);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                if (prepared && player != null) player.seekTo(s.getProgress());
            }
        });

        playBtn = new Button(this);
        playBtn.setText("播放");
        playBtn.setAllCaps(false);
        playBtn.setEnabled(false);
        playBtn.setOnClickListener(v -> {
            if (player == null || !prepared) return;
            if (player.isPlaying()) {
                player.pause();
                playBtn.setText("继续");
            } else {
                player.start();
                playBtn.setText("暂停");
            }
        });

        Button stopBtn = new Button(this);
        stopBtn.setText("停止");
        stopBtn.setAllCaps(false);
        stopBtn.setOnClickListener(v -> {
            if (player == null || !prepared) return;
            player.seekTo(0);
            player.pause();
            playBtn.setText("播放");
            seek.setProgress(0);
        });

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(playBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        btns.addView(stopBtn, new LinearLayout.LayoutParams(0, -2, 1f));

        root.addView(title);
        root.addView(timeView);
        root.addView(seek);
        root.addView(btns);
        setContentView(root);
        main.post(tick);
    }

    private static String fmt(int ms) {
        int s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(tick);
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        if (tmpFile != null) {
            try {
                tmpFile.delete();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }
}
