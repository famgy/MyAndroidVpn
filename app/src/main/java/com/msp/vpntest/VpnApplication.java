package com.msp.vpntest;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.msp.vpnsdk.utils.SystemUtil;

public class VpnApplication extends Application {

    private final String TAG = "===VpnApplication===";

    @Override
    public void attachBaseContext(Context base)
    {
        super.attachBaseContext(base);

        String processName = SystemUtil.getProcessName();
        if(processName.endsWith(":myvpn"))
        {
            Log.i(TAG, "start :myvpn");
            return;
        }

        Log.i(TAG, "start vpntest");
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }
}