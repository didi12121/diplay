package com.baidu.carlife.sdk.receiver;

public interface SurfaceRequestCallback {
    String KEY_SURFACE_WIDTH = "KEY_SURFACE_WIDTH";
    String KEY_SURFACE_HEIGHT = "KEY_SURFACE_HEIGHT";

    void requestSurface(int width, int height);
}
