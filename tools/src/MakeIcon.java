import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/** 生成各密度的启动图标（青→蓝渐变圆角方块 + "MF"）。 */
public class MakeIcon {
    public static void main(String[] args) throws Exception {
        File resDir = new File(args[0]);
        int[] sizes = {48, 72, 96, 144, 192};
        String[] dirs = {"mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"};
        for (int i = 0; i < sizes.length; i++) {
            BufferedImage img = render(sizes[i]);
            File d = new File(resDir, dirs[i]);
            d.mkdirs();
            ImageIO.write(img, "png", new File(d, "ic_launcher.png"));
            System.out.println("icon: " + dirs[i] + "/" + sizes[i] + "px");
        }
        System.out.println("icons ok -> " + resDir.getAbsolutePath());
    }

    static final String LABEL = "MF";

    static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        float pad = size * 0.03f;
        float r = size * 0.24f;
        GradientPaint gp = new GradientPaint(pad, pad, new Color(0x2FB8AB),
                size - pad, size - pad, new Color(0x4C6FE7));
        g.setPaint(gp);
        g.fill(new RoundRectangle2D.Float(pad, pad, size - 2 * pad, size - 2 * pad, r, r));

        g.setColor(Color.WHITE);
        Font f = new Font(Font.SANS_SERIF, Font.BOLD, (int) (size * 0.40f));
        while (true) {
            FontMetrics fm = g.getFontMetrics(f);
            if (fm.stringWidth(LABEL) <= size * 0.70f || f.getSize2D() < 8f) break;
            f = f.deriveFont(f.getSize2D() - 1f);
        }
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int tx = (size - fm.stringWidth(LABEL)) / 2;
        int ty = (int) ((size - fm.getHeight()) / 2f + fm.getAscent() - size * 0.02f);
        g.drawString(LABEL, tx, ty);

        g.dispose();
        return img;
    }
}
