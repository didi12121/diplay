package com.baidu.carlife.sdk;

import com.baidu.carlife.sdk.util.annotations.DoNotStrip;

@DoNotStrip
public interface ModuleStateChangeListener {
    void onModuleStateChanged(CarLifeModule module);
}
