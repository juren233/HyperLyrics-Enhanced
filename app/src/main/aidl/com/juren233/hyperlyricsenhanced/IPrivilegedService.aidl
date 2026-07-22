package com.juren233.hyperlyricsenhanced;

import com.juren233.hyperlyricsenhanced.IPrivilegedLogCallback;

interface IPrivilegedService {
    void setLogCallback(IPrivilegedLogCallback callback);
    boolean setPackageNetworkingEnabled(int uid, boolean enabled);
}
