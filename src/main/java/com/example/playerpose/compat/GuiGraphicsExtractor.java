package com.example.playerpose.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.List;

/**
 * Small adapter that gives {@link GuiGraphics} the 26.3-era drawing names
 * the editor code was written against (text / centeredText / entity), so the port
 * only has to change imports instead of every draw call.
 *
 * <p>The text helpers default to a text shadow, which is exactly what
 * {@link GuiGraphics#drawString(Font, Component, int, int, int)} does. Entity previews
 * use the same rendering path as the inventory player preview.</p>
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
     * Renders a live entity inside the GUI. 1.21.1 has no render-state pipeline, so
     * this mirrors {@code InventoryScreen#renderEntityInInventory}, which renders the
     * entity immediately through the vanilla preview path.
     */
    public void entity(LivingEntity entity, float scale,
                       Vector3fc offset, Quaternionfc rotation, Quaternionfc pitchRotation,
                       int x0, int y0, int x1, int y1) {
        Quaternionf camera = pitchRotation == null ? null
            : new Quaternionf(pitchRotation).conjugate().rotateY((float) Math.PI);
        InventoryScreen.renderEntityInInventory(delegate,
            (x0 + x1) / 2.0F, (y0 + y1) / 2.0F, scale,
            new Vector3f(offset), new Quaternionf(rotation), camera, entity);
    }

    // ---- tooltip ---------------------------------------------------------

    public void setComponentTooltipForNextFrame(Font font, List<Component> lines, int mouseX, int mouseY) {
        delegate.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    public void setTooltipForNextFrame(Font font, Component text, int mouseX, int mouseY) {
        delegate.renderTooltip(font, text, mouseX, mouseY);
    }
}
