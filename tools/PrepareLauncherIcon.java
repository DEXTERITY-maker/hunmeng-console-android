import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

class PrepareLauncherIcon {
    static BufferedImage scaled(BufferedImage source, int size, int padding) {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, padding, padding, size - padding, size - padding,
            0, 0, source.getWidth(), source.getHeight(), null);
        g.dispose();
        return image;
    }

    static void save(BufferedImage image, Path path) throws Exception {
        Files.createDirectories(path.getParent());
        if (!ImageIO.write(image, "png", path.toFile())) throw new IllegalStateException("PNG writer unavailable");
    }

    public static void main(String[] args) throws Exception {
        var source = ImageIO.read(Path.of(args[0]).toFile());
        if (source == null || source.getWidth() != source.getHeight()) throw new IllegalArgumentException("Expected a square icon");
        Path res = Path.of(args[1]);
        String[] densities = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        int[] launcherSizes = {48, 72, 96, 144, 192};
        int[] layerSizes = {108, 162, 216, 324, 432};
        int[] padding = {12, 18, 24, 36, 48};
        for (int i = 0; i < densities.length; i++) {
            save(scaled(source, launcherSizes[i], 0), res.resolve("mipmap-" + densities[i] + "/ic_launcher.png"));
            save(scaled(source, layerSizes[i], padding[i]), res.resolve("drawable-" + densities[i] + "/ic_launcher_artwork.png"));
        }
        double radius = 0;
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int rgb = source.getRGB(x, y);
            if (((rgb >>> 16) & 255) > 230 && ((rgb >>> 8) & 255) > 230 && (rgb & 255) > 230)
                radius = Math.max(radius, Math.hypot(x - source.getWidth() / 2.0, y - source.getHeight() / 2.0));
        }
        System.out.printf("Source: %dx%d; launcher sizes: 48,72,96,144,192; adaptive layers: 108,162,216,324,432%n", source.getWidth(), source.getHeight());
        System.out.printf("White artwork radius in 108dp layer: %.2fdp (safe zone radius: 33dp)%n", radius * 84 / source.getWidth());
        var preview = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = preview.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setClip(new Ellipse2D.Double(0, 0, 256, 256));
        double scale = 256.0 / 72;
        g.drawImage(scaled(source, 432, 48), (int) Math.round(-18 * scale), (int) Math.round(-18 * scale),
            (int) Math.round(108 * scale), (int) Math.round(108 * scale), null);
        g.dispose();
        save(preview, Path.of(".cache/user-icon-circle-preview.png"));
    }
}
