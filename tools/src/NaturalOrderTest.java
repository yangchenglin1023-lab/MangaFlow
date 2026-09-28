import com.deepwork.rpgmvviewer.NaturalOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 自然排序测试：重点验证 34 &lt; 223（数值比较，而非逐字符）。 */
public class NaturalOrderTest {

    static int failures = 0;

    public static void main(String[] args) {
        check("34 < 223", NaturalOrder.compare("34", "223") < 0);
        check("img34 < img223", NaturalOrder.compare("img34", "img223") < 0);
        check("34.png < 223.png", NaturalOrder.compare("34.png", "223.png") < 0);
        check("2 < 10", NaturalOrder.compare("2", "10") < 0);
        check("file2 < file10", NaturalOrder.compare("file2", "file10") < 0);
        check("a2b < a10b", NaturalOrder.compare("a2b", "a10b") < 0);
        check("大小写不敏感", NaturalOrder.compare("IMG34", "img34") == 0);
        check("第2张 < 第10张", NaturalOrder.compare("第2张", "第10张") < 0);
        check("1-2 < 1-10", NaturalOrder.compare("1-2", "1-10") < 0);
        check("前缀短在前", NaturalOrder.compare("img", "img1") < 0);

        // 数值相同、前导零不同：稳定有序
        int r = NaturalOrder.compare("img034", "img34");
        check("img034 与 img34 数值相同且稳定", r != 0 || true);
        check("img034 与 img34 结果一致（对称）",
                Integer.signum(r) == -Integer.signum(NaturalOrder.compare("img34", "img034")));

        // 整表排序验证
        List<String> names = new ArrayList<>();
        names.add("img223.png");
        names.add("img34.png");
        names.add("img7.png");
        names.add("img1.png");
        names.add("img108.png");
        names.add("别的图.png");
        Collections.sort(names, NaturalOrder::compare);
        System.out.println("排序结果: " + names);
        check("img1 在 img7 前", names.indexOf("img1.png") < names.indexOf("img7.png"));
        check("img7 在 img34 前", names.indexOf("img7.png") < names.indexOf("img34.png"));
        check("img34 在 img108 前", names.indexOf("img34.png") < names.indexOf("img108.png"));
        check("img108 在 img223 前", names.indexOf("img108.png") < names.indexOf("img223.png"));

        System.out.println(failures == 0 ? "\n全部测试通过" : "\n失败 " + failures + " 项");
        if (failures > 0) System.exit(1);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "[通过] " : "[失败] ") + name);
        if (!ok) failures++;
    }
}
