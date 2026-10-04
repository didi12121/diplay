package com.baidu.carlife.sdk.receiver;

import com.baidu.carlife.sdk.util.annotations.DoNotStrip;

import java.io.File;

@DoNotStrip
public interface FileTransferListener {
    void onReceiveFile(File file);
}

