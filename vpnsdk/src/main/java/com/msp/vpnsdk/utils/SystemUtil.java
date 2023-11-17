package com.msp.vpnsdk.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * 系统工具类
 * Created by zhuwentao on 2016-07-18.
 */
public class SystemUtil {

    /**
     * 获取进程名字
     * 通过读取/proc/pid/cmdline信息获取进程名字
     * @return 进程名字
     */
    public static String getProcessName() {
        try {
            File file = new File("/proc/" + android.os.Process.myPid() + "/" + "cmdline");
            BufferedReader mBufferedReader = new BufferedReader(new FileReader(file));
            String processName = mBufferedReader.readLine().trim();
            mBufferedReader.close();
            return processName;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
