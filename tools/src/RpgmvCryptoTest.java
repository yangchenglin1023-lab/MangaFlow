import com.deepwork.rpgmvviewer.RpgmvCrypto;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;

/** 端到端验证：模拟 RPG Maker 加密 → 解密还原。 */
public class RpgmvCryptoTest {

    static final byte[] PNG_MAGIC = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        SecureRandom rnd = new SecureRandom();

        // ---- 1. 正确密钥解密 ----
        byte[] key = new byte[16];
        rnd.nextBytes(key);
        String keyHex = RpgmvCryptoTestHex(key);
        byte[] png = makeFakePng(rnd, 5000);
        byte[] enc = encrypt(png, key);
        check("isEncrypted", RpgmvCrypto.isEncrypted(enc));
        byte[] dec = RpgmvCrypto.decrypt(enc, keyHex);
        check("正确密钥还原 PNG", Arrays.equals(png, dec));

        // ---- 2. 无密钥（PNG 反推） ----
        byte[] dec2 = RpgmvCrypto.decryptSmart(enc, null);
        check("无密钥反推还原 PNG", Arrays.equals(png, dec2));

        // ---- 3. 错误密钥 → smart 兜底仍还原 ----
        byte[] dec3 = RpgmvCrypto.decryptSmart(enc, "ffffffffffffffffffffffffffffffff");
        check("错误密钥兜底还原 PNG", Arrays.equals(png, dec3));

        // ---- 4. 默认密钥的文件 ----
        byte[] defKey = RpgmvCrypto.hexToBytes(RpgmvCrypto.DEFAULT_KEY_HEX);
        byte[] enc4 = encrypt(png, defKey);
        byte[] dec4 = RpgmvCrypto.decryptSmart(enc4, null);
        check("默认密钥还原 PNG", Arrays.equals(png, dec4));

        // ---- 5. OGG 音频（需要正确密钥） ----
        byte[] ogg = makeFakeOgg(rnd, 3000);
        byte[] enc5 = encrypt(ogg, key);
        byte[] dec5 = RpgmvCrypto.decryptSmart(enc5, keyHex);
        check("OGG 密钥解密", Arrays.equals(ogg, dec5));

        // ---- 6. MZ 头（RPGMZ） ----
        byte[] enc6 = encrypt("RPGMZ", png, key);
        byte[] dec6 = RpgmvCrypto.decryptSmart(enc6, null);
        check("MZ 头无密钥还原 PNG", Arrays.equals(png, dec6));

        // ---- 7. 未加密 PNG 直接透传 ----
        check("未加密透传", Arrays.equals(png, RpgmvCrypto.decryptSmart(png, null)));

        // ---- 8. findKeyHex 目录探测 ----
        File tmp = Files.createTempDirectory("rpgmvtest").toFile();
        File dataDir = new File(tmp, "data");
        dataDir.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(dataDir, "System.json"))) {
            fo.write(("{\"encryptionKey\":\"" + keyHex + "\",\"gameTitle\":\"T\"}")
                    .getBytes(StandardCharsets.UTF_8));
        }
        File imgDir = new File(tmp, "www/img/pictures");
        imgDir.mkdirs();
        File gameFile = new File(imgDir, "x.rpgmvp");
        try (FileOutputStream fo = new FileOutputStream(gameFile)) {
            fo.write(enc);
        }
        String found = RpgmvCrypto.findKeyHex(gameFile);
        check("findKeyHex 找到密钥", keyHex.equalsIgnoreCase(found));
        deleteRecursively(tmp);

        // ---- 9. 空密钥 → 默认 ----
        check("空密钥用默认", RpgmvCrypto.decrypt(enc4, "").length == png.length);

        System.out.println(failures == 0 ? "\n全部测试通过 ✔" : "\n失败 " + failures + " 项 ✘");
        if (failures > 0) System.exit(1);
    }

    static byte[] makeFakePng(SecureRandom rnd, int len) {
        byte[] b = new byte[len];
        rnd.nextBytes(b);
        System.arraycopy(PNG_MAGIC, 0, b, 0, 16);
        return b;
    }

    static byte[] makeFakeOgg(SecureRandom rnd, int len) {
        byte[] b = new byte[len];
        rnd.nextBytes(b);
        b[0] = 'O';
        b[1] = 'g';
        b[2] = 'g';
        b[3] = 'S';
        return b;
    }

    /** RPGMV 标准 16 字节头 + 前 16 字节数据与 key 异或。 */
    static byte[] encrypt(byte[] plain, byte[] key) {
        return encrypt("RPGMV", plain, key);
    }

    static byte[] encrypt(String magic, byte[] plain, byte[] key) {
        byte[] out = new byte[16 + plain.length];
        byte[] head = new byte[16];
        System.arraycopy(magic.getBytes(StandardCharsets.US_ASCII), 0, head, 0, magic.length());
        System.arraycopy(head, 0, out, 0, 16);
        for (int i = 0; i < plain.length; i++) {
            out[16 + i] = (i < 16) ? (byte) (plain[i] ^ key[i]) : plain[i];
        }
        return out;
    }

    static String RpgmvCryptoTestHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "[通过] " : "[失败] ") + name);
        if (!ok) failures++;
    }

    static void deleteRecursively(File f) {
        File[] c = f.listFiles();
        if (c != null) for (File x : c) deleteRecursively(x);
        f.delete();
    }
}
