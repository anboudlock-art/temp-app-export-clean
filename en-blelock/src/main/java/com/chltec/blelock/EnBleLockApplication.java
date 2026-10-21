package com.chltec.blelock;

import android.content.Context;
import android.support.multidex.MultiDex;

import com.chltec.base_blelock.BaseBleLockApplication;
import com.chltec.base_blelock.activity.MainActivity;
import com.chltec.blelock.config.ForeignConfig;
import com.chltec.powerlib.utils.AppUtil;
import com.tencent.bugly.beta.Beta;
import com.tencent.bugly.crashreport.CrashReport;

public class EnBleLockApplication extends BaseBleLockApplication {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    protected void loadConfig() {
        buildConfig = new ForeignConfig();
        buildConfig.lockInit(this);

        //腾讯Bugly
        CrashReport.UserStrategy userStrategy = new CrashReport.UserStrategy(this);
        userStrategy.setAppVersion(AppUtil.getPackageInfo(this).versionName);   //设置版本号
        userStrategy.setAppChannel("谷歌商店"); //设置渠道
        Beta.canShowUpgradeActs.add(MainActivity.class);
        CrashReport.initCrashReport(this, "97b2ff0cac", false, userStrategy);

    }

}
