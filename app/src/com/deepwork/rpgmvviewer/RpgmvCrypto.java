package com.deepwork.rpgmvviewer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解密 RPG Maker MV（.rpgmvp / .rpgmvo / .rpgmvm）与 MZ（.png_ / .ogg_ / .m4a_）加密资源。
 *
 * 文件格式：16 字节头（"RPGMV" 或 "RPGMZ" 开头）+ 原始数据，
 * 其中紧跟头部的 16 字节与 System.json 里的 encryptionKey（16 字节）异或。
 */
public final class RpgmvCrypto {

    /** RPG Maker 默认加密密钥（空字符串的 MD5）。 */
    public static final String DEFAULT_KEY_HEX = "d41d8cd98f00b204e9800998ecf8427e";

    /** PNG 文件前 16 字节是固定的，可用于无 System.json 时反推密钥。 */
    private static final byte[] PNG_MAGIC16 = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};

    private static final Pattern KEY_PATTERN =
            Pattern.compile("\"encryptionKey\"\\s*:\\s*\"([0-9a-fA-F]*)\"");

    private RpgmvCrypto() {}

    /** 判断数据是否为 RPGMV/MZ 加密格式。 */
    public static boolean isEncrypted(byte[] data) {
        if (data == null || data.length < 32) return false;
        return data[0] == 'R' && data[1] == 'P' && data[2] == 'G' && data[3] == 'M'
                && (data[4] == 'V' || data[4] == 'Z');
    }

    /** 用给定密钥解密；未加密文件原样返回。 */
    public static byte[] decrypt(byte[] data, String keyHex) {
        if (!isEncrypted(data)) return data;
        byte[] key = hexToBytes(keyHex);
        if (key.length == 0) key = hexToBytes(DEFAULT_KEY_HEX);
        byte[] out = new byte[data.length - 16];
        System.arraycopy(data, 16, out, 0, out.length);
        for (int i = 0; i < 16 && i < out.length; i++) {
            out[i] = (byte) (out[i] ^ key[i % key.length]);
        }
        return out;
    }

    /**
     * 无密钥模式：利用 PNG 固定文件头反推密钥（仅对图片有效，且总是正确）。
     * key[i] = data[16+i] ^ PNG_MAGIC16[i]，明文头 16 字节即 PNG_MAGIC16，其余原样。
     */
    public static byte[] decryptPngKeyless(byte[] data) {
        if (!isEncrypted(data)) return data;
        byte[] plain = new byte[data.length - 16];
        System.arraycopy(data, 16, plain, 0, plain.length);
        for (int i = 0; i < 16 && i < plain.length; i++) {
            plain[i] = PNG_MAGIC16[i];
        }
        return plain;
    }

    /**
     * 智能解密：依次尝试「找到的密钥 → 默认密钥 → PNG 无密钥反推」，
     * 返回第一个能识别出已知文件头的明文；音频无密钥时用默认密钥兜底。
     */
    public static byte[] decryptSmart(byte[] data, String keyHex) {
        if (!isEncrypted(data)) return data;

        byte[] a = decrypt(data, keyHex);
        if (hasKnownMagic(a)) return a;

        if (keyHex != null && !keyHex.equalsIgnoreCase(DEFAULT_KEY_HEX)) {
            byte[] b = decrypt(data, DEFAULT_KEY_HEX);
            if (hasKnownMagic(b)) return b;
        }

        byte[] c = decryptPngKeyless(data);
        if (hasKnownMagic(c)) return c;

        return a; // 尽力而为
    }

    /** 明文是否以已知媒体文件头开始（PNG / OGG / M4A / JPEG / WEBP）。 */
    static boolean hasKnownMagic(byte[] d) {
        if (d == null || d.length < 12) return false;
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return true;
        if (d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S') return true;
        if (d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') return true;
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) return true; // JPEG
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return true;
        return false;
    }

    /**
     * 从游戏目录里自动找加密密钥：在文件所在目录及其上级（最多 5 层）
     * 依次查找 data/System.json、www/data/System.json、System.json。
     *
     * @return 32 位十六进制密钥；找不到返回 null
     */
    public static String findKeyHex(File fileInGame) {
        try {
            File dir = fileInGame.getAbsoluteFile().getParentFile();
            for (int level = 0; level < 6 && dir != null; level++, dir = dir.getParentFile()) {
                String[] candidates = {"data/System.json", "www/data/System.json", "System.json"};
                for (String c : candidates) {
                    File f = new File(dir, c);
                    if (f.isFile() && f.length() < 1024 * 1024) {
                        String key = readKeyFromSystemJson(readAll(f));
                        if (key != null) return key;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String readKeyFromSystemJson(byte[] json) {
        try {
            Matcher m = KEY_PATTERN.matcher(new String(json, StandardCharsets.UTF_8));
            if (m.find()) {
                String k = m.group(1).trim();
                if (k.isEmpty()) return DEFAULT_KEY_HEX;
                if (k.length() > 32) k = k.substring(0, 32);
                return k.toLowerCase();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static byte[] readAll(File f) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null) return new byte[0];
        String h = hex.trim();
        if (h.length() % 2 != 0) h = "0" + h;
        int len = h.length() / 2;
        byte[] out = new byte[len];
        try {
            for (int i = 0; i < len; i++) {
                out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            return new byte[0];
        }
        return out;
    }
}
