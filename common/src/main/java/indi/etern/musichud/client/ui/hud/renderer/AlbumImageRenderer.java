package indi.etern.musichud.client.ui.hud.renderer;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import icyllis.modernui.mc.GradientRectangleRenderState;
import icyllis.modernui.mc.MuiModApi;
import indi.etern.musichud.client.ui.hud.metadata.BackgroundImage;
import indi.etern.musichud.client.ui.hud.metadata.HudRenderData;
import indi.etern.musichud.client.ui.hud.metadata.HudUniformWriter;
import indi.etern.musichud.client.ui.hud.metadata.Layout;
import indi.etern.musichud.client.ui.hud.piplines.HudRenderPipelines;
import indi.etern.musichud.client.ui.utils.image.ImageTextureData;
import indi.etern.musichud.client.ui.utils.image.ImageUtils;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3x2f;

public class AlbumImageRenderer {
    private static volatile AlbumImageRenderer instance;
    private final String DEFAULT_BASE64 = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAB0ElEQVQ4jX2T0YrTQBSGv8mkmaltliJFKCjdyAp6KV4v7IvoUyz4DAu+xr7Hwt4K7gOsBq+KWLGl6SaTZGa8aDPbWPFAYHI455///88c8eb1qQcYqxEAhdly+H8YhdkyVqNQAxADvB0D2pFNJ8AJ33+tSVWM9Z7WOVrrefH0BDghX64gHUJV8qXYA6CHZNMJs1QDMEs1n27z3u2X82cA3H7eYIH5acq4iYh3VB2zVLPYVFzfLQAQQqCUCgAd4EvvMVEU8o8n4PpugZQyNFtrqeuauq7RWqO15ttgwPuLs71ciA4NAXDOoZTCe4+UkiRJSJKEuq6PTO0xuLq574EIIRgZwxNjAAKI1n1/orEaBTqHAMOHB16VJVlVBZDDyJcroJvCX9E0DVYISiFohcDu81LKo9oegLWWON6l1kCeJHigUQrnHFJKmqY59iBfrvh4cQaA955oP6Y1UA4GOOeIoghjDNZaLs8zsumEwmyPJVhrkVIGEGst1u5EtG17JKH3Dj68ex6a2rbFex++rrmrgd2+BAaLTcUs1UHK1c19uBkI+a52N4WIuDBbGEC+BJjQeXI+HwOQTScIAT8Kw9efvx/pViWFAdGtc0fpf/Gvdf4DDx7ZzHsT7GgAAAAASUVORK5CYII=";
    private ResourceLocation defaultImageLocation;
    private GpuBufferSlice gpuBufferSlice;
    private final HudUniformWriter uniformWriter = new HudUniformWriter();
    private HudRenderData currentData;

    public static AlbumImageRenderer getInstance() {
        if (instance == null) {
            synchronized (AlbumImageRenderer.class) {
                if (instance == null)
                    instance = new AlbumImageRenderer();
            }
        }
        return instance;
    }

    public void configure(HudRenderData data) {
        this.currentData = data;
    }

    public void render(GuiGraphics gr, DeltaTracker deltaTracker) {
        if (currentData == null) {
            return;
        }

        gpuBufferSlice = uniformWriter.write(currentData, gr);

        Layout layout = currentData.getLayout();
        BackgroundImage bgImage = currentData.getBackgroundImage();

        var transitionStatus = HudRenderData.getTransitionStatus();
        var nextData = transitionStatus.getNextData();
        ResourceLocation nextUnblurredLocation = nextData == null ? null : nextData.nextUnblurred();

        GpuTextureView currentTextureView = getTextureView(bgImage.currentUnblurredLocation);
        GpuTextureView nextTextureView = getTextureView(nextUnblurredLocation);
        GpuTextureView nextView = transitionStatus.isTransitioning() ?
                nextTextureView : currentTextureView;

        TextureSetup textureSetup;
        if (currentTextureView != null) {
            textureSetup = nextView != null ?
                    TextureSetup.doubleTexture(currentTextureView, nextView) :
                    TextureSetup.singleTexture(currentTextureView);
        } else {
            textureSetup = TextureSetup.noTexture();
        }

        float halfWidth = layout.width / 2f;
        float halfHeight = layout.height / 2f;

        ScreenRectangle scissor = MuiModApi.get().peekScissorStack(gr);
        MuiModApi.get().submitGuiElementRenderState(gr,
                new GradientRectangleRenderState(
                        HudRenderPipelines.ROUNDED_ALBUM,
                        textureSetup,
                        new Matrix3x2f(gr.pose()),
                        -halfWidth, -halfHeight, halfWidth, halfHeight,
                        0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                        scissor
                ));
    }

    private GpuTextureView getTextureView(ResourceLocation imageLocation) {
        if (imageLocation == null) {
            if (defaultImageLocation == null) {
                String greyImageBase64 = DEFAULT_BASE64;
                ImageTextureData imageTextureData = ImageUtils.loadBase64(greyImageBase64);
                imageTextureData.register().join();
                defaultImageLocation = imageTextureData.getLocation();
            }
            return getTextureView(defaultImageLocation);
        }

        AbstractTexture texture = Minecraft.getInstance()
                .getTextureManager()
                .getTexture(imageLocation);
        if (texture instanceof DynamicTexture dynamicTexture) {
            return dynamicTexture.getTextureView();
        }
        return null;
    }

    public void updateRenderPass(RenderPass renderPass) {
        if (gpuBufferSlice != null) {
            renderPass.setUniform("HudAlbumParams", gpuBufferSlice);
        }
    }
}
