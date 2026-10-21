package com.chltec.blelock;

import com.chltec.base_blelock.BaseBleLockApplication;
import com.chltec.base_blelock.activity.MainActivity;
import com.chltec.blelock.config.ChinaConfig;
import com.chltec.powerlib.utils.AppUtil;
import com.tencent.bugly.Bugly;
import com.tencent.bugly.beta.Beta;
import com.tencent.bugly.crashreport.CrashReport;

public class BleLockApplication extends BaseBleLockApplication {

    @Override
    protected void loadConfig() {
        buildConfig = new ChinaConfig();
        buildConfig.lockInit(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        //腾讯Bugly
        CrashReport.UserStrategy userStrategy = new CrashReport.UserStrategy(this);
        userStrategy.setAppVersion(AppUtil.getPackageInfo(this).versionName);   //设置版本号
        userStrategy.setAppChannel("应用宝");  //设置渠道
        Beta.canShowUpgradeActs.add(MainActivity.class);
        Bugly.init(this, "97b2ff0cac", false, userStrategy);
    }
}
