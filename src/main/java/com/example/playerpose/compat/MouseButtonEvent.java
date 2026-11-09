package com.example.playerpose.compat;

/**
 * 1.21.8 compatibility shape for the 26.x mouse-button event record.
 * The screen still reads the same {@code x()}, {@code y()} and {@code button()}
 * accessors as the newer API.
 */
public record MouseButtonEvent(double x, double y, int button) {
}
