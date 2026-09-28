import com.deepwork.rpgmvviewer.AlbumStore;

import java.io.File;
import java.nio.file.Files;

/** 画册存取测试：增删改名、进度记录、置顶排序、持久化往返（含中文与空格路径）。 */
public class AlbumStoreTest {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        File tmp = Files.createTempDirectory("albumtest").toFile();
        File db = new File(tmp, "albums.txt");

        AlbumStore s1 = new AlbumStore(db);
        check("初始为空", s1.list().isEmpty());
        check("新增画册", s1.add("/storage/emulated/0/游戏/www/img"));
        check("重复添加不新增", !s1.add("/storage/emulated/0/游戏/www/img"));
        check("默认名=文件夹名", "img".equals(s1.list().get(0).name));

        s1.add("/sdcard/Pictures/壁纸 2026");
        check("重命名", s1.rename("/sdcard/Pictures/壁纸 2026", "我的壁纸"));
        check("重命名生效", "我的壁纸".equals(s1.find("/sdcard/Pictures/壁纸 2026").name));
        check("空名拒绝", !s1.rename("/sdcard/Pictures/壁纸 2026", "  "));

        s1.touch("/storage/emulated/0/游戏/www/img", 42);
        check("进度记录", s1.find("/storage/emulated/0/游戏/www/img").lastIndex == 42);
        check("touch 后置顶", "/storage/emulated/0/游戏/www/img".equals(s1.list().get(0).path));
        s1.touch("/不存在的路径", 5);
        check("touch 不存在路径为空操作", s1.find("/不存在的路径") == null);

        // 持久化往返（中文、空格路径）
        AlbumStore s2 = new AlbumStore(db);
        check("重载后数量一致", s2.list().size() == 2);
        check("重载后名称保留", "我的壁纸".equals(s2.find("/sdcard/Pictures/壁纸 2026").name));
        check("重载后进度保留", s2.find("/storage/emulated/0/游戏/www/img").lastIndex == 42);

        check("删除", s2.remove("/sdcard/Pictures/壁纸 2026"));
        check("删除后数量", s2.list().size() == 1);
        AlbumStore s3 = new AlbumStore(db);
        check("删除已持久化", s3.list().size() == 1);

        deleteRecursively(tmp);
        System.out.println(failures == 0 ? "\n全部测试通过" : "\n失败 " + failures + " 项");
        if (failures > 0) System.exit(1);
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
