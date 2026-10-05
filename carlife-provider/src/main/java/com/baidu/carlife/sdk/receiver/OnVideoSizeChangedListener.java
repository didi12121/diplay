package com.baidu.carlife.sdk.receiver;

import com.baidu.carlife.sdk.util.annotations.DoNotStrip;

@DoNotStrip
public interface OnVideoSizeChangedListener {
    void onVideoSizeChanged(int width, int height);
}
