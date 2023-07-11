package io.github.lgatodu47.screenshot_viewer.screens;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FastColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

import static io.github.lgatodu47.screenshot_viewer.screens.ManageScreenshotsScreen.CONFIG;
import static io.github.lgatodu47.screenshot_viewer.screens.ManageScreenshotsScreen.LOGGER;

final class ScreenshotWidget extends AbstractWidget implements AutoCloseable, ScreenshotImageHolder {
    private final ManageScreenshotsScreen mainScreen;
    private final Minecraft client;
    private final Context ctx;

    private File screenshotFile;
    private CompletableFuture<NativeImage> image;
    @Nullable
    private DynamicTexture texture;
    private float bgOpacity = 0;
    private int baseY;

    public ScreenshotWidget(ManageScreenshotsScreen mainScreen, int x, int y, int width, int height, Context ctx, File screenshotFile) {
        super(x, y, width, height, Component.literal(screenshotFile.getName()));
        this.mainScreen = mainScreen;
        this.client = mainScreen.client();
        this.baseY = y;
        this.ctx = ctx;
        this.screenshotFile = screenshotFile;
        this.image = getImage(screenshotFile);
    }

    void updateBaseY(int baseY) {
        setY(this.baseY = baseY);
    }

    void updateY(int scrollY) {
        setY(baseY - scrollY);
    }

    void updateScreenshotFile(File screenshotFile) {
        this.screenshotFile = screenshotFile;
        if (texture != null) {
            texture.close();
        } else if (image != null) {
            image.thenAcceptAsync(image -> {
                if (image != null) {
                    image.close();
                }
            }, this.client);
        }
        texture = null;
        image = getImage(screenshotFile);
    }

    File getScreenshotFile() {
        return screenshotFile;
    }

    void updateHoverState(int mouseX, int mouseY, int viewportY, int viewportBottom, boolean updateHoverState) {
        this.isHovered = updateHoverState && (mouseX >= this.getX() && mouseY >= Math.max(this.getY(), viewportY) && mouseX < this.getX() + this.width && mouseY < Math.min(this.getY() + this.height, viewportBottom));
        int maxOpacity = CONFIG.screenshotElementBackgroundOpacity.get();
        if (maxOpacity > 0 && isHovered) {
            if (bgOpacity < maxOpacity / 100f) {
                bgOpacity = Math.min(maxOpacity / 100f, bgOpacity + 0.05F);
            }
        } else {
            if (bgOpacity > 0) {
                bgOpacity = Math.max(0, bgOpacity - 0.05F);
            }
        }
    }

    /// Rendering Methods ///

    void render(GuiGraphics graphics, int mouseX, int mouseY, float delta, int viewportY, int viewportBottom) {
        renderBackground(graphics, mouseX, mouseY, viewportY, viewportBottom);
        final int spacing = 2;

        DynamicTexture image = texture();
        if (image != null && image.getPixels() != null) {
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.setShaderTexture(0, image.getId());
            RenderSystem.enableBlend();
            int renderY = Math.max(getY() + spacing, viewportY);
            int imgHeight = (int) (height / 1.08 - spacing * 3);
            int topOffset = Math.max(0, viewportY - getY() - spacing);
            int bottomOffset = Math.max(0, getY() + spacing + imgHeight - viewportBottom);
            int topV = topOffset * image.getPixels().getHeight() / imgHeight;
            int bottomV = bottomOffset * image.getPixels().getHeight() / imgHeight;
            drawTexture(graphics,
                    getX() + spacing,
                    renderY,
                    width - spacing * 2,
                    imgHeight - topOffset - bottomOffset,
                    0,
                    topV,
                    image.getPixels().getWidth(),
                    image.getPixels().getHeight() - topV - bottomV,
                    image.getPixels().getWidth(),
                    image.getPixels().getHeight()
            );
            RenderSystem.disableBlend();
        }
        float scaleFactor = (float) (client.getWindow().getGuiScaledHeight() / 96) / ctx.screenshotsPerRow();
        int textY = getY() + (int) (height / 1.08) - spacing;
        if (textY > viewportY && (float) textY + scaleFactor * (client.font.lineHeight) < viewportBottom) {
            PoseStack matrices = graphics.pose();
            matrices.pushPose();
            matrices.translate(getX() + width / 2f, textY, 0);
            matrices.scale(scaleFactor, scaleFactor, scaleFactor);
            Component message = getMessage();
            float centerX = (float) (-client.font.width(getMessage()) / 2);
            int textColor = Optional.ofNullable(TextColor.parseColor(CONFIG.screenshotElementTextColor.get())).map(TextColor::getValue).orElse(0xFFFFFF);
            graphics.drawString(client.font, message, (int) centerX, 0, textColor, CONFIG.renderScreenshotElementFontShadow.get());
            matrices.popPose();
        }
    }

    public static void drawTexture(GuiGraphics graphics, int x, int y, int width, int height, int u, int v, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        int x2 = x + width;
        int y2 = y + height;
        float u1 = u / (float) textureWidth;
        float u2 = (u + (float) regionWidth) / (float) textureWidth;
        float v1 = v / (float) textureHeight;
        float v2 = (v + (float) regionHeight) / (float) textureHeight;

        Matrix4f matrix4f = graphics.pose().last().pose();
        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.vertex(matrix4f, x, y, 0).uv(u1, v1).endVertex();
        bufferBuilder.vertex(matrix4f, x, y2, 0).uv(u1, v2).endVertex();
        bufferBuilder.vertex(matrix4f, x2, y2, 0).uv(u2, v2).endVertex();
        bufferBuilder.vertex(matrix4f, x2, y, 0).uv(u2, v1).endVertex();
        BufferUploader.drawWithShader(bufferBuilder.end());
    }

    @Override
    public void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    }

    private void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, int viewportY, int viewportBottom) {
        int renderY = Math.max(getY(), viewportY);
        int renderHeight = Math.min(getY() + height, viewportBottom);
        graphics.fill(getX(), renderY, getX() + width, renderHeight, FastColor.ARGB32.color((int) (bgOpacity * 255), 255, 255, 255));
    }

    /// Utility methods ///

    private void onClick() {
        this.mainScreen.enlargeScreenshot(this);
    }

    private void onRightClick(double mouseX, double mouseY) {
        this.mainScreen.showScreenshotProperties(mouseX, mouseY, this);
    }

    private CompletableFuture<NativeImage> getImage(File file) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream inputStream = new FileInputStream(file)) {
                return NativeImage.read(inputStream);
            } catch (Exception e) {
                LOGGER.error("Failed to load screenshot: {}", file.getName(), e);
            }
            return null;
        }, Util.backgroundExecutor());
    }

    @Nullable
    public DynamicTexture texture() {
        if (texture != null) {
            return texture;
        }
        if (image == null) {
            image = getImage(screenshotFile);
        }
        if (image.isDone()) {
            return texture = new DynamicTexture(image.join());
        }
        return null;
    }

    /// ScreenshotImageHolder implementations ///

    @Override
    public int indexInList() {
        return ctx.currentIndex(this);
    }

    @Override
    public int imageId() {
        DynamicTexture texture = texture();
        return texture != null ? texture.getId() : 0;
    }

    @Nullable
    @Override
    public NativeImage image() {
        if (image == null) {
            image = getImage(screenshotFile);
        }
        return image.getNow(null);
    }

    /// Common Widget implementations ///

    @Override
    public @NotNull Component getMessage() {
        return this.screenshotFile == null ? super.getMessage() : Component.literal(this.screenshotFile.getName());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isHoveredOrFocused()) {
            super.playDownSound(this.client.getSoundManager());
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                onClick();
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                onRightClick(mouseX, mouseY);
            }
            return true;
        }
        return false;
    }

    @Override
    protected boolean clicked(double mouseX, double mouseY) {
        return false;
    }

    @Override
    public boolean isHoveredOrFocused() {
        return isHovered;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return isHoveredOrFocused();
    }

    @Override
    protected void updateWidgetNarration(@NotNull NarrationElementOutput p_259858_) {
    }

    @Override
    public void close() {
        if (texture != null) {
            texture.close(); // Also closes the image
        } else if(image != null) {
            image.thenAcceptAsync(image -> {
                if (image != null) {
                    image.close();
                }
            }, this.client);
        }
        image = null;
        texture = null;
    }

    interface Context {
        int screenshotsPerRow();

        int currentIndex(ScreenshotWidget widget);

        static Context create(IntSupplier screenshotsPerRow, ToIntFunction<ScreenshotWidget> currentIndex) {
            return new Context() {
                @Override
                public int screenshotsPerRow() {
                    return screenshotsPerRow.getAsInt();
                }

                @Override
                public int currentIndex(ScreenshotWidget widget) {
                    return currentIndex.applyAsInt(widget);
                }
            };
        }
    }
}