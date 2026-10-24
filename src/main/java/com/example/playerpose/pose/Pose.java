package com.example.playerpose.pose;

import java.util.List;

/**
 * A saved pose: name, optional skin filter, list of bone poses.
 *
 * Rotations always happen about the joint of the bone being edited - there is no
 * separate pivot to move; picking a part in the editor picks its joint.
 */
public class Pose {

    private String name;
    private String skin;
    private List<PoseBone> bones;

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
}
