package com.example.playerpose.compat;

/**
 * Local stand-ins for the 1.21.11 input event records.
 *
 * <p>1.21.5's {@code Screen} still uses primitive input signatures
 * ({@code mouseClicked(double,double,int)}, {@code keyPressed(int,int,int)}, ...).
 * The editor code is written against the newer record-shaped events, so the
 * primitive overrides build one of these and delegate to the existing handlers.</p>
 */
public final class PoseInputEvent {

    private PoseInputEvent() {}

    public record MouseButtonEvent(double x, double y, int button) {}

    public record KeyEvent(int key, int scancode, int modifiers) {}

    public record CharacterEvent(char codepoint) {}
}
