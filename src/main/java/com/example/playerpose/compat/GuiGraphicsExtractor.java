package com.example.playerpose.compat;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.List;

/**
 * Small adapter that gives 1.21.11's {@link GuiGraphics} the 26.3-era drawing names
 * the editor code was written against (text / centeredText / entity), so the port
 * only has to change imports instead of every draw call.
 *
 * <p>The 26.3 text helpers default to a text shadow, which is exactly what
 * {@link GuiGraphics#drawString(Font, Component, int, int, int)} does. Entity previews
 * use the same deferred GUI render state as the inventory player preview.</p>
 */
public final class GuiGraphicsExtractor {

    private final GuiGraphics delegate;

    public GuiGraphicsExtractor(GuiGraphics delegate) {
        this.delegate = delegate;
    }

    public GuiGraphics delegate() {
        return delegate;
    }

    // ---- rectangles ------------------------------------------------------

    public void fill(int x0, int y0, int x1, int y1, int color) {
        delegate.fill(x0, y0, x1, y1, color);
    }

    public void fillGradient(int x0, int y0, int x1, int y1, int colorFrom, int colorTo) {
        delegate.fillGradient(x0, y0, x1, y1, colorFrom, colorTo);
    }

    public void hLine(int x0, int x1, int y, int color) {
        delegate.hLine(x0, x1, y, color);
    }

    public void vLine(int x, int y0, int y1, int color) {
        delegate.vLine(x, y0, y1, color);
    }

    public void renderOutline(int x, int y, int width, int height, int color) {
        delegate.renderOutline(x, y, width, height, color);
    }

    // ---- scissor ---------------------------------------------------------

    public void enableScissor(int x0, int y0, int x1, int y1) {
        delegate.enableScissor(x0, y0, x1, y1);
    }

    public void disableScissor() {
        delegate.disableScissor();
    }

    // ---- text ------------------------------------------------------------

    public void text(Font font, String text, int x, int y, int color) {
        delegate.drawString(font, text, x, y, color);
    }

    public void text(Font font, String text, int x, int y, int color, boolean shadow) {
        delegate.drawString(font, text, x, y, color, shadow);
    }

    public void text(Font font, Component text, int x, int y, int color) {
        delegate.drawString(font, text, x, y, color);
    }

    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        delegate.drawString(font, text, x, y, color, shadow);
    }

    public void text(Font font, FormattedCharSequence text, int x, int y, int color) {
        delegate.drawString(font, text, x, y, color);
    }

    public void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        delegate.drawString(font, text, x, y, color, shadow);
    }

    public void centeredText(Font font, String text, int x, int y, int color) {
        delegate.drawCenteredString(font, text, x, y, color);
    }

    public void centeredText(Font font, Component text, int x, int y, int color) {
        delegate.drawCenteredString(font, text, x, y, color);
    }

    public void centeredText(Font font, FormattedCharSequence text, int x, int y, int color) {
        delegate.drawCenteredString(font, text, x, y, color);
    }

    // ---- entity preview --------------------------------------------------

    /**
     * Submits an entity render state into the GUI's deferred render state. This is the
     * 1.21.11 equivalent of 26.3's {@code GuiGraphicsExtractor#entity}; it powers the
     * inventory player preview and works inside a screen with an active scissor.
     */
    public void entity(EntityRenderState state, float scale, Vector3fc offset,
                       Quaternionfc rotation, Quaternionfc pitchRotation,
                       int x0, int y0, int x1, int y1) {
        PoseStack pose = delegate.pose();
        pose.pushPose();
        pose.translate((x0 + x1) / 2.0F, (y0 + y1) / 2.0F, 50.0D);
        pose.scale(scale, scale, -scale);
        pose.translate(offset.x(), offset.y(), offset.z());
        pose.mulPose(rotation);
        delegate.flush();
        Lighting.setupForEntityInInventory();

        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        if (pitchRotation != null) {
            dispatcher.overrideCameraOrientation(
                new Quaternionf(pitchRotation).conjugate().rotateY((float) Math.PI));
        }
        dispatcher.setRenderShadow(false);
        delegate.drawSpecial(bufferSource ->
            dispatcher.render(state, 0.0D, 0.0D, 0.0D, delegate.pose(), bufferSource, 15728880));
        delegate.flush();
        dispatcher.setRenderShadow(true);
        pose.popPose();
        Lighting.setupFor3DItems();
    }

    // ---- tooltip ---------------------------------------------------------

    public void setComponentTooltipForNextFrame(Font font, List<Component> lines, int mouseX, int mouseY) {
        delegate.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    public void setTooltipForNextFrame(Font font, Component text, int mouseX, int mouseY) {
        delegate.renderTooltip(font, text, mouseX, mouseY);
    }
}
