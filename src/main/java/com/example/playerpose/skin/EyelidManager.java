package com.example.playerpose.skin;

import com.example.playerpose.pose.PoseLog;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;

/**
 * Editor-only eyelid preview.
 *
 * <p>Two skin conventions are supported and selected by the user:
 * SkinSpice (a closed-eye 8x8 face frame at [24,0] and [56,0], marker at [0,0])
 * and expression packs (Just Expressions / Fresh Moves), which store a small stack of
 * animated eye frames in a spare head corner such as [24,0]. The manager builds a copy of
 * the current preview texture with the selected eyelids closed and feeds it to the
 * editor's render state only.</p>
 */
public final class EyelidManager {

    public enum Format { SKINSPICE, JUST_EXPRESSIONS }

    private static final int FACE_X = 8;
    private static final int FACE_Y = 8;
    private static final int FRAME_X = 24;
    private static final int FRAME_Y = 0;
    private static final int HAT_FACE_X = 40;
    private static final int HAT_FACE_Y = 8;
    private static final int HAT_FRAME_X = 56;
    private static final int HAT_FRAME_Y = 0;
    /** Spare 8x8 corner of the head where expression packs park their eye frames. */
    private static final int STRIP_X = 24;
    private static final int STRIP_Y = 0;
    private static final int HAT_STRIP_X = 56;
    private static final int HAT_STRIP_Y = 0;

    private static volatile boolean active;
    private static Format format = Format.JUST_EXPRESSIONS;
    private static float leftOpen = 1.0F;
    private static float rightOpen = 1.0F;

    private static NativeImage base;
    private static NativeImage composed;
    private static ResourceLocation textureId;
    private static DynamicTexture texture;
    private static PlayerSkin skin;
    private static PlayerSkinTypeSnapshot built;
    private static boolean dirty;
    private static boolean needsRefresh;
    private static boolean needsNewTexture;
    private static int counter;

    private static Box leftBox;
    private static Box rightBox;
    private static Box leftHatBox;
    private static Box rightHatBox;
    private static boolean leftSupported;
    private static boolean rightSupported;
    private static int lidColor;

    private EyelidManager() {}

    private record Box(int x0, int y0, int x1, int y1) {
        int width() { return x1 - x0; }
        int height() { return y1 - y0; }
    }

    public record EyeBox(int x0, int y0, int x1, int y1) {
        public int width() { return x1 - x0; }
        public int height() { return y1 - y0; }
    }

    private record PlayerSkinTypeSnapshot(PlayerSkin.Model model) {}

    public static void setActive(boolean value) {
        active = value;
        if (!value) clear();
    }

    public static Format format() { return format; }

    public static void setFormat(Format value) {
        if (value == null || value == format) return;
        format = value;
        detect();
        dirty = true;
    }

    public static float leftOpen() { return leftOpen; }
    public static float rightOpen() { return rightOpen; }

    public static void setOpenness(boolean left, float value) {
        float clamped = Math.max(0.0F, Math.min(1.0F, value));
        if (left) leftOpen = clamped; else rightOpen = clamped;
        dirty = true;
    }

    public static boolean leftSupported() {
        ensureFresh();
        return active && base != null && leftSupported;
    }

    public static boolean rightSupported() {
        ensureFresh();
        return active && base != null && rightSupported;
    }

    public static boolean hasBase() {
        ensureFresh();
        return base != null;
    }

    /** Pick up a skin that was swapped in since the last read. */
    private static void ensureFresh() {
        if (needsRefresh && active) refresh();
    }

    public static EyeBox leftEyeBox() {
        return leftBox == null ? null
            : new EyeBox(leftBox.x0, leftBox.y0, leftBox.x1, leftBox.y1);
    }

    public static EyeBox rightEyeBox() {
        return rightBox == null ? null
            : new EyeBox(rightBox.x0, rightBox.y0, rightBox.x1, rightBox.y1);
    }

    /** The composed preview skin, or null when both eyelids are open. */
    public static PlayerSkin skin() {
        if (!active) return null;
        if (needsRefresh) refresh();
        if (base == null) return null;
        boolean leftClosed = leftSupported && leftOpen < 0.999F;
        boolean rightClosed = rightSupported && rightOpen < 0.999F;
        if (!leftClosed && !rightClosed) return null;
        if (dirty) rebuild();
        return skin;
    }

    public static void onBaseChanged() {
        needsRefresh = true;
        needsNewTexture = true;
        dirty = true;
    }

    /** Re-read the active preview pixels and re-detect support. */
    public static void refresh() {
        needsRefresh = false;
        NativeImage next = PreviewSkinManager.copyPixels();
        if (base != null && base != next) base.close();
        base = next;
        detect();
        dirty = true;
    }

    public static void clear() {
        needsRefresh = false;
        needsNewTexture = false;
        dirty = false;
        leftOpen = 1.0F;
        rightOpen = 1.0F;
        if (base != null) {
            base.close();
            base = null;
        }
        composed = null;
        releaseTexture();
        leftBox = null;
        rightBox = null;
        leftHatBox = null;
        rightHatBox = null;
        leftSupported = false;
        rightSupported = false;
    }

    private static void detect() {
        leftSupported = false;
        rightSupported = false;
        leftBox = null;
        rightBox = null;
        leftHatBox = null;
        rightHatBox = null;
        if (base == null) return;
        if (base.getWidth() != 64 || base.getHeight() != 64) return;
        if (format == Format.SKINSPICE) {
            if (!isSkinSpice(base)) return;
            leftBox = diffBox(base, FRAME_X, FRAME_Y, FACE_X, FACE_Y, 4, 7);
            rightBox = diffBox(base, FRAME_X, FRAME_Y, FACE_X, FACE_Y, 0, 3);
            leftHatBox = diffBox(base, HAT_FRAME_X, HAT_FRAME_Y,
                HAT_FACE_X, HAT_FACE_Y, 4, 7);
            rightHatBox = diffBox(base, HAT_FRAME_X, HAT_FRAME_Y,
                HAT_FACE_X, HAT_FACE_Y, 0, 3);
            leftSupported = leftBox != null || leftHatBox != null;
            rightSupported = rightBox != null || rightHatBox != null;
        } else {
            detectExpressionStrip();
        }
    }

    /**
     * Expression packs (Just Expressions / Fresh Moves style) do not use a palette: they
     * park a small stack of eye frames in the spare bottom-left corner of the head texture,
     * one row per blink phase with the two eyes side by side and mirrored. The frames are
     * usually a compressed copy of the eye band on the face, so the face is where the real
     * eye boxes come from; the strip only says "this skin blinks" and supplies the colours.
     */
    private static void detectExpressionStrip() {
        Box strip = findStrip(STRIP_X, STRIP_Y, !isSkinSpice(base));
        if (strip == null) strip = findStrip(HAT_STRIP_X, HAT_STRIP_Y, true);
        if (strip == null) return;

        int[] palette = stripPalette(strip);
        if (palette.length == 0) return;
        lidColor = stripLidColor(strip);

        rightBox = findEyeBox(0, 3, palette);
        leftBox = findEyeBox(4, 7, palette);
        boolean supported = rightBox != null || leftBox != null;
        if (rightBox == null && supported) rightBox = new Box(1, 4, 3, 5);
        if (leftBox == null && supported) leftBox = new Box(5, 4, 7, 5);
        rightSupported = supported;
        leftSupported = supported;
        if (!supported) return;
        if (rightBox != null && hasOpaqueBox(base, HAT_FACE_X, HAT_FACE_Y, rightBox)) {
            rightHatBox = rightBox;
        }
        if (leftBox != null && hasOpaqueBox(base, HAT_FACE_X, HAT_FACE_Y, leftBox)) {
            leftHatBox = leftBox;
        }
    }

    /** Opaque bounding box inside one spare 8x8 corner, or null when it holds no frames. */
    private static Box findStrip(int originX, int originY, boolean allowed) {
        if (!allowed) return null;
        int minX = 8, minY = 8, maxX = -1, maxY = -1;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if ((base.getPixel(originX + x, originY + y) >>> 24) == 0) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        if (maxX < 0) return null;
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        if (width < 2 || height < 1 || width > 6 || height > 4) return null;
        return new Box(originX + minX, originY + minY,
            originX + maxX + 1, originY + maxY + 1);
    }

    /**
     * Colours of the animated rows of the strip, skipping the top (closed) row: that one is
     * usually a flat dark lash and would match the whole face.
     */
    private static int[] stripPalette(Box strip) {
        int[] found = new int[8];
        int count = 0;
        for (int y = strip.y0() + 1; y < strip.y1(); y++) {
            for (int x = strip.x0(); x < strip.x1(); x++) {
                int color = base.getPixel(x, y);
                if ((color >>> 24) == 0) continue;
                int luminance = (((color >> 16) & 0xFF) + ((color >> 8) & 0xFF) + (color & 0xFF)) / 3;
                if (luminance < 26) continue;
                boolean known = false;
                for (int i = 0; i < count; i++) {
                    if (near(found[i], color)) { known = true; break; }
                }
                if (!known && count < found.length) found[count++] = color;
            }
        }
        int[] out = new int[count];
        System.arraycopy(found, 0, out, 0, count);
        return out;
    }

    /** Lid colour: the closed frame is drawn in the top row of the strip. */
    private static int stripLidColor(Box strip) {
        int best = base.getPixel(strip.x0(), strip.y0());
        int bestCount = -1;
        for (int x = strip.x0(); x < strip.x1(); x++) {
            int color = base.getPixel(x, strip.y0());
            if ((color >>> 24) == 0) continue;
            int count = 0;
            for (int other = strip.x0(); other < strip.x1(); other++) {
                if (base.getPixel(other, strip.y0()) == color) count++;
            }
            if (count > bestCount) { bestCount = count; best = color; }
        }
        return best;
    }

    private static boolean isSkinSpice(NativeImage image) {
        int marker = image.getPixel(0, 0);
        return (marker >>> 24) > 0 && (marker & 0xFFFFFF) == 0x00C0FF;
    }

    private static boolean opaque(NativeImage image, int x, int y) {
        return (image.getPixel(x, y) >>> 24) > 0;
    }

    /** Bounding box of pixels that differ between the closed frame and the face. */
    private static Box diffBox(NativeImage image, int frameX, int frameY,
                               int faceX, int faceY, int col0, int col1) {
        int minX = 8, minY = 8, maxX = -1, maxY = -1;
        for (int y = 0; y < 8; y++) {
            for (int x = col0; x <= col1; x++) {
                int frame = image.getPixel(frameX + x, frameY + y);
                int face = image.getPixel(faceX + x, faceY + y);
                if (frame != face && (frame >>> 24) > 0) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? null : new Box(minX, minY, maxX + 1, maxY + 1);
    }

    private static boolean hasOpaqueBox(NativeImage image, int faceX, int faceY, Box box) {
        for (int y = box.y0; y < box.y1; y++) {
            for (int x = box.x0; x < box.x1; x++) {
                if ((image.getPixel(faceX + x, faceY + y) >>> 24) > 0) return true;
            }
        }
        return false;
    }

    /** Find the eye pixels that match any colour of the expression strip. */
    private static Box findEyeBox(int col0, int col1, int[] palette) {
        // Stay one pixel inside the face border: skins with a white outline (like
        // the preview skin) would otherwise match the border as "eye white".
        int x0 = Math.max(col0, 1);
        int x1 = Math.min(col1, 6);
        int minX = 8, minY = 8, maxX = -1, maxY = -1;
        for (int y = 1; y <= 6; y++) {
            for (int x = x0; x <= x1; x++) {
                int color = base.getPixel(FACE_X + x, FACE_Y + y);
                if ((color >>> 24) == 0) continue;
                boolean match = false;
                for (int candidate : palette) {
                    if (near(color, candidate)) { match = true; break; }
                }
                if (!match) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        if (maxX < 0) return null;
        // A stray match on skin or hair would swallow the whole face; the eyes are small.
        if (maxX - minX > 4 || maxY - minY > 5) return null;
        return new Box(minX, minY, maxX + 1, maxY + 1);
    }

    private static boolean near(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return dr * dr + dg * dg + db * db <= 12 * 12;
    }

    private static void rebuild() {
        if (base == null) return;
        NativeImage next = compose();
        if (next == null) return;
        composed = next;
        Minecraft mc = Minecraft.getInstance();
        var model = PreviewSkinManager.model();
        // A new base skin means a new texture object: reusing the old GPU texture after
        // the preview changed was what made the model render with another skin's pixels.
        if (texture == null || textureId == null || needsNewTexture) {
            releaseTexture();
            textureId = ResourceLocation.fromNamespaceAndPath("player-pose",
                "preview/eyelids_" + (counter++));
            texture = new DynamicTexture(next);
            mc.getTextureManager().register(textureId, texture);
            needsNewTexture = false;
            PoseLog.info("eyelids: texture %s (%dx%d)", textureId,
                next.getWidth(), next.getHeight());
        } else {
            texture.setPixels(next);
            texture.upload();
        }
        if (built == null || built.model() != model) {
            PlayerSkin original = mc.player != null ? mc.player.getSkin() : null;
            // Keep missing cape/elytra missing instead of reusing the body texture, which
            // rendered the player's own skin on their back. A demo cape still wins.
            ResourceLocation cape = PreviewSkinManager.effectiveCape();
            ResourceLocation elytra = original != null ? original.elytraTexture() : null;
            skin = new PlayerSkin(textureId, null, cape, elytra, model, false);
            built = new PlayerSkinTypeSnapshot(model);
        }
        dirty = false;
    }

    private static void releaseTexture() {
        texture = null;
        skin = null;
        built = null;
        if (textureId != null) {
            try {
                Minecraft.getInstance().getTextureManager().release(textureId);
            } catch (Throwable ignored) {
                // texture may already be gone after a resource reload
            }
            textureId = null;
        }
    }

    private static NativeImage compose() {
        int width = base.getWidth();
        int height = base.getHeight();
        if (width != 64 || height != 64) return null;
        NativeImage out = new NativeImage(NativeImage.Format.RGBA, width, height, true);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setPixel(x, y, base.getPixel(x, y));
            }
        }
        if (format == Format.SKINSPICE) {
            applyFrame(out, rightBox, rightOpen, FACE_X, FACE_Y, FRAME_X, FRAME_Y);
            applyFrame(out, leftBox, leftOpen, FACE_X, FACE_Y, FRAME_X, FRAME_Y);
            applyFrame(out, rightHatBox, rightOpen, HAT_FACE_X, HAT_FACE_Y,
                HAT_FRAME_X, HAT_FRAME_Y);
            applyFrame(out, leftHatBox, leftOpen, HAT_FACE_X, HAT_FACE_Y,
                HAT_FRAME_X, HAT_FRAME_Y);
        } else {
            applyLid(out, rightBox, rightOpen, FACE_X, FACE_Y);
            applyLid(out, leftBox, leftOpen, FACE_X, FACE_Y);
            applyLid(out, rightHatBox, rightOpen, HAT_FACE_X, HAT_FACE_Y);
            applyLid(out, leftHatBox, leftOpen, HAT_FACE_X, HAT_FACE_Y);
        }
        return out;
    }

    private static void applyFrame(NativeImage out, Box box, float open,
                                   int faceX, int faceY, int frameX, int frameY) {
        if (box == null || open >= 0.999F) return;
        int cover = Math.min(box.height(), Math.max(0,
            Math.round((1.0F - open) * box.height())));
        for (int row = 0; row < cover; row++) {
            for (int x = box.x0; x < box.x1; x++) {
                int color = out.getPixel(frameX + x, frameY + box.y0 + row);
                out.setPixel(faceX + x, faceY + box.y0 + row, color);
            }
        }
    }

    private static void applyLid(NativeImage out, Box box, float open,
                                 int faceX, int faceY) {
        if (box == null || open >= 0.999F) return;
        int cover = Math.min(box.height(), Math.max(0,
            Math.round((1.0F - open) * box.height())));
        for (int row = 0; row < cover; row++) {
            for (int x = box.x0; x < box.x1; x++) {
                out.setPixel(faceX + x, faceY + box.y0 + row, lidColor);
            }
        }
    }

    /** Linear blend of two ARGB colors; keeps the result fully opaque. */
}
