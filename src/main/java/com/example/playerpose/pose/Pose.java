package com.example.playerpose.pose;

import java.util.List;

/**
 * A saved pose: name, optional skin filter, list of bone poses, plus the pivot
 * ("cursor") that rotations happen around.
 *
 * The cursor is an offset in model units from the joint of whichever bone is
 * being edited, so a cursor of (0,0,0) means "rotate around the joint itself".
 */
public class Pose {

    private String name;
    private String skin;
    private List<PoseBone> bones;
    private double cursorX, cursorY, cursorZ;

    public Pose() {}

    public Pose(String name, String skin, List<PoseBone> bones) {
        this.name = name;
        this.skin = skin;
        this.bones = bones;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSkin() { return skin; }
    public void setSkin(String skin) { this.skin = skin; }

    public List<PoseBone> getBones() { return bones; }
    public void setBones(List<PoseBone> bones) { this.bones = bones; }

    public double getCursorX() { return cursorX; }
    public double getCursorY() { return cursorY; }
    public double getCursorZ() { return cursorZ; }

    public void setCursor(double x, double y, double z) {
        this.cursorX = x;
        this.cursorY = y;
        this.cursorZ = z;
    }

    public boolean hasCursor() {
        return cursorX != 0.0 || cursorY != 0.0 || cursorZ != 0.0;
    }
}
