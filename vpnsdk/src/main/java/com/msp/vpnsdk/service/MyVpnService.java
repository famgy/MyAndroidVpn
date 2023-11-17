package com.msp.vpnsdk.service;


import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Debug;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.msp.vpnsdk.IMyAidl;
import com.msp.vpnsdk.bean.MyVpn;
import com.msp.vpnsdk.bean.Packet;
import com.msp.vpnsdk.utils.ByteBufferPool;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Random;

public class MyVpnService extends Service {

    private static final String TAG = "===MyVpnService===";
    private IMyAidl mAidl = null;

    String vpnName = "unknown";
    public static ParcelFileDescriptor mVpnInterface = null;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate() is called");

        new Thread(new Runnable() {
            @Override
            public void run() {
//                Debug.waitForDebugger();
                Intent intent = new Intent();
                ComponentName componentName = new ComponentName("com.msp.vpntest" ,"com.msp.vpnsdk.service.MyAidlService");
                intent.setComponent(componentName);
                ServiceConnection mConnection = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {

                        //连接后拿到 Binder，转换成 AIDL，在不同进程会返回个代理
                        mAidl = IMyAidl.Stub.asInterface(service);

                        try {
                            MyVpn myVpn = mAidl.getMyVpn();
                            vpnName = myVpn.getVpnStr();
                            mVpnInterface = myVpn.getVpnInterface();
                            Log.i(TAG, "vpnName : " + vpnName);
                            Log.i(TAG, "vpnInterface : " + mVpnInterface.getFileDescriptor().toString());

                            // read
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    readCache();
                                }
                            }).start();

//                            // write
//                            new Thread(new Runnable() {
//                                @Override
//                                public void run() {
//                                    writeCache();
//                                }
//                            }).start();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        mAidl = null;
                    }
                };
                boolean result = bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
                Log.i(TAG, "bindService result : " + result);
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Service onDestroy() is called");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void readCache() {
        FileChannel vpnInput = new FileInputStream(mVpnInterface.getFileDescriptor()).getChannel();

        ByteBuffer bufferToNetwork = null;
        while (true) {
            bufferToNetwork = ByteBufferPool.acquire();
            int readBytes = 0;
            try {
                readBytes = vpnInput.read(bufferToNetwork);

                if (readBytes > 0) {
                    bufferToNetwork.flip();
                    Packet packetTmp = new Packet(bufferToNetwork);
                    InetAddress destinationAddress = packetTmp.ipHeader.destinationAddress;
                    String dstStr = destinationAddress.getHostAddress();
                    Log.i(TAG, "readCache : " + dstStr);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    public static void writeCache() {
        try {
            FileChannel vpnOutput = new FileOutputStream(mVpnInterface.getFileDescriptor()).getChannel();

            // 构造的报文
            ByteBuffer myBufferCache = ByteBufferPool.acquire();
            buildIPv4Packet(myBufferCache);
            buildTCPHandshake(myBufferCache);

            myBufferCache.flip();
            Packet packetTmp = new Packet(myBufferCache);
            InetAddress destinationAddress = packetTmp.ipHeader.destinationAddress;
            String dstStr = destinationAddress.getHostAddress();

            myBufferCache.flip();
            vpnOutput.write(myBufferCache);
            Log.i(TAG, "writeCache : " + dstStr);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void buildIPv4Packet(ByteBuffer buffer) {
        try {
            // IPv4 头部长度（4 个 32 位字，即 20 字节）
            byte headerLength = 5;  // 5 即表示 5 * 4 = 20 字节
            byte versionAndIHL = (byte) ((4 << 4) | headerLength);
            buffer.put(versionAndIHL);

            // 服务类型（Type of Service）
            byte serviceType = 0;
            buffer.put(serviceType);

            // 总长度（Total Length）（IP 头部 + 数据部分）
            short totalLength = 40;  // 20 字节 IP 头部 + 20 字节数据部分
            buffer.putShort(totalLength);

            // 标识（Identification）
            short identification = 12345;
            buffer.putShort(identification);

            // 标志（Flags）和片偏移（Fragment Offset）
            short flagsAndFragmentOffset = 0;
            buffer.putShort(flagsAndFragmentOffset);

            // 生存时间（Time to Live）
            byte timeToLive = 64;  // 通常设置为 64
            buffer.put(timeToLive);

            // 协议（Protocol）（例如，TCP 是 6，UDP 是 17）
            byte protocol = 6;  // TCP
            buffer.put(protocol);

            // 头部校验和（Header Checksum）（初始时可以填 0，后面会计算）
            short headerChecksum = 0;
            buffer.putShort(headerChecksum);

            // 源 IP 地址
//            InetAddress sourceAddress = InetAddress.getByName("10.0.0.2");
            InetAddress sourceAddress = InetAddress.getByName("192.168.0.100");
            buffer.put(sourceAddress.getAddress());

            // 目标 IP 地址
            String ipDstStr = "220.181.38.150";
            InetAddress destinationAddress = InetAddress.getByName(ipDstStr);
            buffer.put(destinationAddress.getAddress());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static int port = 6400;
    private static void buildTCPHandshake(ByteBuffer buffer) {
        // 源端口号
        short sourcePort = (short)port++;
        buffer.putShort(sourcePort);

        // 目标端口号
        short destinationPort = 80;  // 假设是 HTTP 服务
        buffer.putShort(destinationPort);

        // 序列号，随机选择
        int sequenceNumber = new Random().nextInt();
        buffer.putInt(sequenceNumber);

        // 确认号，初始时为 0
        buffer.putInt(0);

        // 数据偏移和保留字段
        byte dataOffsetAndReserved = (byte) 0x50;  // 数据偏移 6，保留字段 0
        buffer.put(dataOffsetAndReserved);

        // 标志位，设置 SYN 标志
        byte flags = (byte) 0x02;  // 0000 0010
        buffer.put(flags);

        // 窗口大小，这里设置为 65535
        short windowSize = (short) 65535;
        buffer.putShort(windowSize);

        // 校验和，暂时填充为 0，后面会计算
        buffer.putShort((short) 0);

        // 紧急指针，暂时填充为 0
        buffer.putShort((short) 0);
    }
}