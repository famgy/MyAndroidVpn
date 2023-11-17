package com.msp.vpnsdk.bean;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

public class MyVpn implements Parcelable {

    private final String TAG = "===MyVpn===";
    public String mName;
    public ParcelFileDescriptor mVpnInterface = null;

    public MyVpn(String name, ParcelFileDescriptor vpnInterface) {
        mName = name;
        mVpnInterface = vpnInterface;
    }

    protected MyVpn(Parcel in) {
        mName = in.readString();
        mVpnInterface = in.readFileDescriptor();
    }

    public String getVpnStr() {
        return mName;
    }

    public ParcelFileDescriptor getVpnInterface() {
        return mVpnInterface;
    }

    public static final Creator<MyVpn> CREATOR = new Creator<MyVpn>() {
        @Override
        public MyVpn createFromParcel(Parcel in) {
            return new MyVpn(in);
        }

        @Override
        public MyVpn[] newArray(int size) {
            return new MyVpn[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        try {
            dest.writeString(mName);
            dest.writeFileDescriptor(mVpnInterface != null ? mVpnInterface.getFileDescriptor() : null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
