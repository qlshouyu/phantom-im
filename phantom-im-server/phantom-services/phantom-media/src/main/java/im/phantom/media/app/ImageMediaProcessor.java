package im.phantom.media.app;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

/** 图片尺寸探测和缩略图生成，隔离 AWT 与媒体用例。 */
@Component
public class ImageMediaProcessor {

    public Optional<int[]> dimensions(byte[] content) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            return image == null ? Optional.empty()
                    : Optional.of(new int[]{image.getWidth(), image.getHeight()});
        } catch (Exception unavailable) {
            return Optional.empty();
        }
    }

    public Optional<byte[]> thumbnail(byte[] content, int maxSize) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(content));
            if (source == null) return Optional.empty();
            int width = source.getWidth();
            int height = source.getHeight();
            double scale = Math.min(1.0, Math.min((double) maxSize / width, (double) maxSize / height));
            int thumbWidth = Math.max(1, (int) Math.round(width * scale));
            int thumbHeight = Math.max(1, (int) Math.round(height * scale));
            BufferedImage thumb = new BufferedImage(thumbWidth, thumbHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = thumb.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, thumbWidth, thumbHeight, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            return ImageIO.write(thumb, "jpg", out) ? Optional.of(out.toByteArray()) : Optional.empty();
        } catch (Exception unavailable) {
            return Optional.empty();
        }
    }
}
