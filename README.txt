一. 安全键盘SDK集成
 
1. 将safekeyboard.aar直接复制到libs目录下
2. 在build.gradle中添加aar的引用配置

    dependencies {
        implementation fileTree(include: ['*.jar'], dir: 'libs')
        implementation files('libs/safekeyboard.aar')
    }

二. 功能集成（activity方式集成）

在需要使用安全键盘的activity-layout中添加安全编辑组件(com.keyboard.safekeyboard.SafeKeyboardEditText)

如：

<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <com.keyboard.safekeyboard.SafeKeyboardEditText
        android:layout_width="match_parent"
        android:layout_height="50sp"
        android:layout_marginEnd="10sp"
        android:layout_marginStart="10sp"
        android:hint="安全键盘"
        android:inputType="textVisiblePassword" />
</LinearLayout>


三. 功能集成（web方式集成）
    1. 为js注册java接口
        WebView mWebView = findViewById(R.id.web_view);
        WebSettings webSettings = mWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);

        //context : must be this, never use getApplicationContext() 这里需要注意 this
        mWebView.addJavascriptInterface(new NativeInterface(this), "AndroidNative");
        mWebView.loadUrl("file:///android_asset/safeweb.html");

    2. 实现js调用的接口
    public class NativeInterface {

        private Context mContext;

        public NativeInterface(Context context) {
            this.mContext = context;
        }

        //显示安全键盘
        @JavascriptInterface
        public void showSafeKeyboard(final int posHeight) {
            runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    // TODO Auto-generated method stub
                    SafeKeyboardManager safeKeyboardManager = SafeKeyboardManager.getInstance(mContext, mWebView);
                    safeKeyboardManager.showKeyboard(posHeight);
                }
            });
        }

        //隐藏安全键盘
        @JavascriptInterface
        public void closeSafeKeybord() {
            runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    // TODO Auto-generated method stub
                    SafeKeyboardManager safeKeyboardManager = SafeKeyboardManager.getInstance(mContext, mWebView);
                    safeKeyboardManager.hideKeyboard();
                }
            });
        }
    }

    3. 应用调用实现
        <script type="text/javascript">
        var idVar;
        function showSafeKeyboard(idTmp) {
            idVar = idTmp;

            setLoseFocus();

            var et = document.getElementById(idVar);
            var height = getPos(et);

            AndroidNative.showSafeKeyboard(height);
        }

        function closeSafeKeyboard() {
            AndroidNative.closeSafeKeybord();
        }

        function setFocus() {//设置焦点
          var et = document.getElementById(idVar);
          et.focus();
        }

        function setLoseFocus() {//取消焦点
          var et = document.getElementById(idVar);
          et.blur();
        }

        function getPos(o) //取元素坐标
        {
            var y = o.offsetTop;
            y += o.offsetHeight;
            return y;
        }

        function del() {//删除
            var str = document.getElementById(idVar).value;
            var strNew = str.substring(0,str.length-1);
            document.getElementById(idVar).value = strNew;
        }

        function insert(str) {//插入
            <!--AndroidNative.logInfo("hahahahahhaa")-->
            var s = document.getElementById(idVar).value;
            var strNew = s + str;
            document.getElementById(idVar).value = strNew;
        }
    </script>


四. 功能接口说明

//获取安全键盘管理器对象
WebView mWebView = findViewById(R.id.web_view);
SafeKeyboardManager safeKeyboardManager = SafeKeyboardManager.getInstance(this, mWebView);

//提供安全键盘字母和数字随机的开关，默认关闭
safeKeyboardManager.setSafeKeyboardRandom(true);

//提供安全键盘按下效果显示的开关，默认关闭
safeKeyboardManager.setSafeKeyboardKeyBg(true);

//提供安全键盘按键预览效果的开关，默认关闭
safeKeyboardManager.setSafeKeyboardPreview(true);

//提供安全键盘主动隐藏的接口
safeKeyboardManager.hideKeyboard();

//判断安全键盘是否弹起状态
safeKeyboardManager.isSafeKeyboardShown();

//安全键盘隐藏回调
safeKeyboardManager.setHideKeyboardEvent(new OnHideKeyboardListener() {
    @Override
    public void notifyEvent() {
        Log.e("===test===", "hideKeyboard finish");
    }
});


五. 补充说明

    // 当点击返回键时, 如果软键盘正在显示, 则隐藏软键盘并是此次返回无效
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            if (safeKeyboardManager.isSafeKeyboardShown()) {
                safeKeyboardManager.hideKeyboard();
                return false;
            }
            return super.onKeyDown(keyCode, event);
        }
        return super.onKeyDown(keyCode, event);
    }

    // 处理activity切换保证安全键盘成功隐藏
    @Override
    protected void onPause() {
        if (safeKeyboardManager.isSafeKeyboardShown()) {
            safeKeyboardManager.hideKeyboard();
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (safeKeyboardManager.isSafeKeyboardShown()) {
            safeKeyboardManager.hideKeyboard();
        }

        super.onDestroy();
    }


