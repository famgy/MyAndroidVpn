package com.msp.vpnsdk.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.msp.vpnsdk.IMyAidl;
import com.msp.vpnsdk.bean.MyVpn;
import com.msp.vpnsdk.vpn.vservice.VhostsService;

public class MyAidlService extends Service {

    private final String TAG = this.getClass().getSimpleName();
    private static final String CHANNEL_ID = "MyChannel";

    public static ParcelFileDescriptor vpnInterface = null;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    private IBinder mIBinder = new IMyAidl.Stub() {
        @Override
        public MyVpn getMyVpn() throws RemoteException {
            MyVpn myvpn = new MyVpn("myvpn_bean", VhostsService.vpnInterface);
            return myvpn;
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mIBinder;
    }
}