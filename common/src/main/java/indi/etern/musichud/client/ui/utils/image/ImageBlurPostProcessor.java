package indi.etern.musichud.client.ui.utils.image;

import com.mojang.blaze3d.platform.NativeImage;
import icyllis.modernui.graphics.Bitmap;
import indi.etern.musichud.MusicHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.atomic.AtomicReference;

import static indi.etern.musichud.client.ui.utils.image.ImageUtils.convertBitmapToNativeImage;

public class ImageBlurPostProcessor {

    public static ImageTextureData blur(ImageTextureData originalImageData, int radius) {
        synchronized (originalImageData.getSource()) {
            String cacheKey = originalImageData.getSource() + "_blurred_" + radius;
            ImageTextureData cachedData = ImageUtils.getCachedTexturesData().getIfPresent(cacheKey);
            if (cachedData != null) {
                return cachedData;
            }

            Bitmap bitmap = originalImageData.convertToBitmap();
            var result = applyGaussianBlurOptimized(bitmap, radius);

            NativeImage nativeImage = convertBitmapToNativeImage(result);
            assert nativeImage != null;
            ResourceLocation imageBlurredLocation = ResourceLocation.fromNamespaceAndPath(MusicHud.MOD_ID,
                    "image_blurred_" + radius + "_" + bitmap.hashCode());
            AtomicReference<DynamicTexture> texture = new AtomicReference<>();
            Minecraft.getInstance().submit(() -> {
                texture.set(new DynamicTexture(() -> "downloaded_blurred_" + originalImageData.getSource().hashCode(), nativeImage));
            }).join();
            ImageTextureData imageTextureData = new ImageTextureData(
                    originalImageData.getSource(),
                    imageBlurredLocation,
                    texture.get(),
                    false
            );

            ImageUtils.getCachedTexturesData().put(cacheKey, imageTextureData);
            return imageTextureData;
        }
    }

    private static Bitmap applyGaussianBlurOptimized(Bitmap source, int radius) {
        if (radius <= 0) {
            return source;
        }
        return applyCorrectedGaussianBlur(source, radius);
    }

    private static Bitmap applyCorrectedGaussianBlur(Bitmap source, int radius) {
        int width = source.getWidth();
        int height = source.getHeight();

        Bitmap result = Bitmap.createBitmap(width, height, source.getFormat());

        int[] sourcePixels = new int[width * height];
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height);

        int[] resultPixels = new int[width * height];

        // 高斯核参数
        float sigma = Math.max(radius / 3.0f, 0.5f);

        // 分步处理，先水平后垂直
        int[] tempPixels = new int[width * height];

        // 第一步：水平模糊（使用正确的边界处理）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                tempPixels[x + y * width] = gaussianBlurPixelHorizontal(sourcePixels, width, x, y, radius, sigma);
            }
        }

        // 第二步：垂直模糊（使用正确的边界处理）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                resultPixels[x + y * width] = gaussianBlurPixelVertical(tempPixels, width, height, x, y, radius, sigma);
            }
        }

        result.setPixels(resultPixels, 0, width, 0, 0, width, height);

        // 临时测试：如果需要验证偏移问题，可以在这里添加偏移补偿
        // return applyCompensatedShift(result, radius);

        return result;
    }

    private static int gaussianBlurPixelHorizontal(int[] sourcePixels, int width, int x, int y, int radius, float sigma) {
        float sumA = 0, sumR = 0, sumG = 0, sumB = 0;
        float weightSum = 0;

        // 根据当前位置决定实际使用的卷积核
        int actualRadius = Math.min(radius, Math.min(x, width - 1 - x));

        for (int dx = -actualRadius; dx <= actualRadius; dx++) {
            float weight = gaussian(dx, sigma);
            int nx = x + dx;

            // 确保索引有效
            if (nx >= 0 && nx < width) {
                int pixel = sourcePixels[nx + y * width];
                sumA += ((pixel >> 24) & 0xFF) * weight;
                sumR += ((pixel >> 16) & 0xFF) * weight;
                sumG += ((pixel >> 8) & 0xFF) * weight;
                sumB += (pixel & 0xFF) * weight;
                weightSum += weight;
            }
        }

        // 使用有效权重重新归一化
        if (weightSum > 0) {
            sumA /= weightSum;
            sumR /= weightSum;
            sumG /= weightSum;
            sumB /= weightSum;
        }

        return ((int)sumA << 24) | ((int)sumR << 16) | ((int)sumG << 8) | (int)sumB;
    }

    private static int gaussianBlurPixelVertical(int[] sourcePixels, int width, int height, int x, int y, int radius, float sigma) {
        float sumA = 0, sumR = 0, sumG = 0, sumB = 0;
        float weightSum = 0;

        // 根据当前位置决定实际使用的卷积核
        int actualRadius = Math.min(radius, Math.min(y, height - 1 - y));

        for (int dy = -actualRadius; dy <= actualRadius; dy++) {
            float weight = gaussian(dy, sigma);
            int ny = y + dy;

            // 确保索引有效
            if (ny >= 0 && ny < height) {
                int pixel = sourcePixels[x + ny * width];
                sumA += ((pixel >> 24) & 0xFF) * weight;
                sumR += ((pixel >> 16) & 0xFF) * weight;
                sumG += ((pixel >> 8) & 0xFF) * weight;
                sumB += (pixel & 0xFF) * weight;
                weightSum += weight;
            }
        }

        // 使用有效权重重新归一化
        if (weightSum > 0) {
            sumA /= weightSum;
            sumR /= weightSum;
            sumG /= weightSum;
            sumB /= weightSum;
        }

        return ((int)sumA << 24) | ((int)sumR << 16) | ((int)sumG << 8) | (int)sumB;
    }

    private static float gaussian(int x, float sigma) {
        return (float) Math.exp(-(x * x) / (2 * sigma * sigma));
    }

}