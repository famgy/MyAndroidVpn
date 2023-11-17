package com.msp.vpntest.activity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.msp.vpnsdk.service.MyAidlService;
import com.msp.vpnsdk.service.MyVpnService;
import com.msp.vpnsdk.utils.HttpRequest;
import com.msp.vpnsdk.vpn.vservice.VhostsService;
import com.msp.vpntest.R;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private final String TAG = "===MainActivity===";
    private static final int VPN_REQUEST_CODE = 0x0F;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getPermissions();
        intView();
    }

    private void intView() {

        findViewById(R.id.bt_start_vpn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent vpnIntent = VhostsService.prepare(MainActivity.this);
                if (vpnIntent != null){
                    Log.i(TAG, "需要请求设备vpn权限");
                    startActivityForResult(vpnIntent, VPN_REQUEST_CODE);

                    return;
                }

                //启动服务器
                Log.i(TAG, "启动设备vpn服务");
                startService(new Intent(MainActivity.this, VhostsService.class).setAction(VhostsService.ACTION_CONNECT));

                // start aidl server
                final Intent myAidlServerIntent = new Intent(MainActivity.this, MyAidlService.class);
                startService(myAidlServerIntent);
            }
        });

        findViewById(R.id.bt_stop_vpn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "关闭设备vpn服务");
                startService(new Intent(MainActivity.this, VhostsService.class).setAction(VhostsService.ACTION_DISCONNECT));
            }
        });

        findViewById(R.id.bt_my_vpn_service).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent myVpnService = new Intent(MainActivity.this, MyVpnService.class);
                startService(myVpnService);
            }
        });

        findViewById(R.id.bt_web_request).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        EditText et = findViewById(R.id.et_web_request);
                        String msg = "";
                        boolean bOk = false;
                        try {
                            HttpRequest req=new HttpRequest(et.getText().toString(), true);
                            msg = req.prepare(HttpRequest.Method.GET).setUserAgent("vpnsdk").sendAndReadString();
                            bOk = true;
                        } catch (Exception e) {
                            msg = e.getMessage();
                        }

                        final String fmsg = msg;
                        Handler handler = new Handler(Looper.getMainLooper());
                        boolean finalBOk = bOk;
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (finalBOk) {
                                    Log.i(TAG, "资源获取 --- 成功");
                                } else {
                                    Log.i(TAG, "资源获取 --- 失败");
                                }

                                Toast.makeText(MainActivity.this, fmsg, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }).start();
            }
        });
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        //Toast.makeText(MainActivity.this, "onRestart", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "onRestart");
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "onStart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE) {
            // 处理VPN请求的结果
            if (resultCode == RESULT_OK) {
                //启动服务器
                Log.i(TAG, "启动设备vpn服务");
                startService(new Intent(MainActivity.this, VhostsService.class).setAction(VhostsService.ACTION_CONNECT));
            } else {
                Log.i(TAG, "未授权vpn权限");
            }
        }
    }

    private void getPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ArrayList<String> permissions = new ArrayList<String>();

            if(checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED){
                permissions.add(Manifest.permission.FOREGROUND_SERVICE);
            }

            if (permissions.size() > 0) {
                requestPermissions(permissions.toArray(new String[permissions.size()]), 1);
            }
        }
    }
}
