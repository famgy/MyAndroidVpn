/*
 ** Copyright 2015, Mohamed Naufal
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.msp.vpnsdk.vpn.vservice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.msp.vpnsdk.bean.Packet;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Selector;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;


public class VhostsService extends VpnService {
    private static final String TAG = VhostsService.class.getSimpleName();
    private static final String VPN_ADDRESS = "10.0.0.2";
    private static final String VPN_ADDRESS6 = "fe80:49b1:7e4f:def2:e91f:95bf:fbb6:1111";
    private static final String VPN_ROUTE = "0.0.0.0"; // Intercept everything
    private static final String VPN_ROUTE6 = "::"; // Intercept everything
    private static String VPN_DNS4 = "114.114.114.114";
    private static String VPN_DNS6 = "2001:4860:4860::8888";

    public static final String BROADCAST_VPN_STATE = VhostsService.class.getName() + ".VPN_STATE";
    public static final String ACTION_CONNECT = VhostsService.class.getName() + ".START";
    public static final String ACTION_DISCONNECT = VhostsService.class.getName() + ".STOP";

    private static boolean isRunning = false;
    private static boolean isStopping = false;
    private static Thread threadHandleHosts = null;
    public static ParcelFileDescriptor vpnInterface = null;

    private PendingIntent pendingIntent;

    private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
    private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;
    private ExecutorService executorService;

    private Selector udpSelector;
    private Selector tcpSelector;
    private ReentrantLock udpSelectorLock;
    private ReentrantLock tcpSelectorLock;
    //private NetworkReceiver netStateReceiver;
    private static boolean isOAndBoot = false;


    @Override
    public void onCreate() {
//        registerNetReceiver();
        super.onCreate();
        if (isOAndBoot) {
            //android 8.0 boot
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel("vhosts_channel_id", "System", NotificationManager.IMPORTANCE_NONE);
                NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                manager.createNotificationChannel(channel);
                Notification notification = new Notification.Builder(this, "vhosts_channel_id")
                        //.setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("Virtual Hosts Running")
                        .build();
                startForeground(1, notification);
            }
            isOAndBoot=false;
        }
        //setupHostFile();
        setupVPN();
        if (vpnInterface == null) {
            Log.i(TAG,"vpnInterface == null");
            stopVService();
            return;
        }
        isRunning = true;

        try {
            udpSelector = Selector.open();
            tcpSelector = Selector.open();
            deviceToNetworkUDPQueue = new ConcurrentLinkedQueue<>();
            deviceToNetworkTCPQueue = new ConcurrentLinkedQueue<>();
            networkToDeviceQueue = new ConcurrentLinkedQueue<>();
            udpSelectorLock = new ReentrantLock();
            tcpSelectorLock = new ReentrantLock();
            executorService = Executors.newFixedThreadPool(5);
            /*
            UDPInput, UDPOutput, TCPInput, TCPOutput四个线程处理对应类型的包, 这些包存放在队列中, 它们和服务器打交道
            VPNRunnable处理上面四个线程产生的存储在队列中的包, 进行读写接口的操作, 它与接口打交道
             */
            executorService.submit(new UDPInput(networkToDeviceQueue, udpSelector, udpSelectorLock));
            executorService.submit(new UDPOutput(deviceToNetworkUDPQueue, networkToDeviceQueue, udpSelector, udpSelectorLock, this));
            executorService.submit(new TCPInput(networkToDeviceQueue, tcpSelector, tcpSelectorLock));
            executorService.submit(new TCPOutput(deviceToNetworkTCPQueue, networkToDeviceQueue, tcpSelector, tcpSelectorLock, this));
            //尝试同步异步混合, 非隧道走异步, 隧道走同步
            //executorService.submit(new TCPOutputSyn(deviceToNetworkTCPQueue, networkToDeviceQueue, this));

            executorService.submit(new VPNRunnable(vpnInterface.getFileDescriptor(),
                    deviceToNetworkUDPQueue, deviceToNetworkTCPQueue, networkToDeviceQueue));

            //LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(BROADCAST_VPN_STATE).putExtra("running", true));
            sendBroadcast(new Intent(BROADCAST_VPN_STATE).putExtra("running", true));
            Log.i(TAG,"设备vpn服务启动成功");
        } catch (Exception e) {
            // TODO: Here and elsewhere, we should explicitly notify the user of any errors
            // and suggest that they stop the service, since we can't do it ourselves
            Log.i(TAG,"Error starting service", e);
            stopVService();
        }
    }

    private void setupVPN() {
        try {
            if (vpnInterface == null) {
                Builder builder = new Builder();
                builder.addAddress(VPN_ADDRESS, 24);
                builder.addAddress(VPN_ADDRESS6, 128);
                //VPN_DNS4 = getString(R.string.dns_server);
                Log.i(TAG,"use dns:" + VPN_DNS4);
                //tcp不走vpn
                builder.addRoute(VPN_DNS4, 32);
//            builder.addRoute(VPN_DNS6, 128);

              builder.addRoute("220.181.38.150",32);
//            builder.addRoute(VPN_ROUTE,0);
                builder.addRoute(VPN_ROUTE6,0);

                // 指定域名服务器
                builder.addDnsServer(VPN_DNS4);
                //builder.addDnsServer(VPN_DNS6);

                //sivpn一般是应用的名字, 但这里是sdk
                vpnInterface = builder.setSession("sivpn").setConfigureIntent(pendingIntent).establish();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_DISCONNECT.equals(intent.getAction())) {
                stopVService();
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    public static boolean isRunning() {

        return isRunning;
    }

    public static void startVService(Context context, int method) {
        Intent intent = VhostsService.prepare(context);
        if (intent != null) {
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.i(TAG,"Run Fail On Boot");
        }
        try {
            if (method == 2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                isOAndBoot = true;
                context.startForegroundService(new Intent(context, VhostsService.class).setAction(ACTION_CONNECT));
            } else {
                isOAndBoot = false;
                context.startService(new Intent(context, VhostsService.class).setAction(ACTION_CONNECT));
            }
        } catch (RuntimeException e) {
            Log.i(TAG,"Not allowed to start service Intent", e);
        }
    }

    public static void stopVService(Context context) {
        context.startService(new Intent(context, VhostsService.class).setAction(VhostsService.ACTION_DISCONNECT));
    }

    private void stopVService() {
        if (false == isRunning || isStopping) {
            return;
        }

        isStopping = true;

        if (threadHandleHosts != null) threadHandleHosts.interrupt();
//        unregisterNetReceiver();
        if (executorService != null) executorService.shutdownNow();

        cleanup();
        stopSelf();

        Log.i(TAG,"设备vpn服务关闭完成");

        isRunning = false;
        isStopping = false;
    }

    @Override
    public void onRevoke() {
        stopVService();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopVService();
        super.onDestroy();
    }

    private void cleanup() {
        udpSelectorLock = null;
        tcpSelectorLock = null;
        deviceToNetworkTCPQueue = null;
        deviceToNetworkUDPQueue = null;
        networkToDeviceQueue = null;
        ByteBufferPool.clear();
        closeResources(udpSelector, tcpSelector, vpnInterface);
        vpnInterface = null;
    }

    // TODO: Move this to a "utils" class for reuse
    private static void closeResources(Closeable... resources) {
        for (Closeable resource : resources) {
            try {
                resource.close();
            } catch (Exception e) {
                Log.i(TAG,e.toString(), e);
            }
        }
    }

    int count = 0;
    private static class VPNRunnable implements Runnable {
        private static final String TAG = VPNRunnable.class.getSimpleName();

        private FileDescriptor vpnFileDescriptor;

        private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
        private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
        private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;

        public VPNRunnable(FileDescriptor vpnFileDescriptor,
                           ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue,
                           ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue,
                           ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue) {
            this.vpnFileDescriptor = vpnFileDescriptor;
            this.deviceToNetworkUDPQueue = deviceToNetworkUDPQueue;
            this.deviceToNetworkTCPQueue = deviceToNetworkTCPQueue;
            this.networkToDeviceQueue = networkToDeviceQueue;
        }

        @Override
        public void run() {
            Log.i(TAG,"Started");

            FileChannel vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();
            FileChannel vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();
            try {
                ByteBuffer bufferToNetwork = null;
                boolean dataSent = true;
                boolean dataReceived;
                while (!Thread.interrupted()) {
                    try {
                        if (dataSent)
                            bufferToNetwork = ByteBufferPool.acquire();
                        else
                            bufferToNetwork.clear();

                        // TODO: Block when not connected
                        int readBytes = vpnInput.read(bufferToNetwork);
                        if (readBytes > 0) {
                            dataSent = true;
                            bufferToNetwork.flip();
                            Packet packet = new Packet(bufferToNetwork);
                            if (packet.isUDP()) {
                                deviceToNetworkUDPQueue.offer(packet);
                            } else if (packet.isTCP()) {
                                deviceToNetworkTCPQueue.offer(packet);
                                String dstStr = packet.ipHeader.destinationAddress.getHostAddress();
                                Log.i(TAG,"mVpnInput read dstStr : " + dstStr);
                            } else {
                                Log.i(TAG,"Unknown packet type");
                                dataSent = false;
                            }
                        } else {
                            dataSent = false;
                        }
                        ByteBuffer bufferFromNetwork = networkToDeviceQueue.poll();
                        if (bufferFromNetwork != null) {
                            bufferFromNetwork.flip();
                            while (bufferFromNetwork.hasRemaining())
                                try {
                                    vpnOutput.write(bufferFromNetwork);
                                } catch (Exception e) {
                                    Log.i(TAG,e.toString(), e);
                                    break;
                                }
                            dataReceived = true;
                            ByteBufferPool.release(bufferFromNetwork);
                        } else {
                            dataReceived = false;
                        }

                        // TODO: Sleep-looping is not very battery-friendly, consider blocking instead
                        // Confirm if throughput with ConcurrentQueue is really higher compared to BlockingQueue
                        if (!dataSent && !dataReceived)
                            Thread.sleep(11);
                    } catch (InterruptedException ie) {
                        // Handle InterruptedException separately
                        Log.i(TAG,"VPNRunnable 退出");
                        break;  // Exit the while loop
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } finally {
                closeResources(vpnInput, vpnOutput);
            }
        }
    }

}
