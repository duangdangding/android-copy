// IClipboardBridge.aidl
package com.clipditto.app;

import android.content.ClipData;

/** App 进程与 Shizuku（shell 身份）进程之间的桥接：读取系统剪贴板 */
interface IClipboardBridge {
    ClipData readClipboard();
}
