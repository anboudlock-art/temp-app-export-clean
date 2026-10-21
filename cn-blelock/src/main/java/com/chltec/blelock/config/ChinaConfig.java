package com.chltec.blelock.config;
/**
 * _________________________________________________________________________________________________________________________________________________________________________________
 **/


import android.content.Context;
import android.content.Intent;

import com.baidu.mapapi.SDKInitializer;
import com.chltec.base_blelock.config.BuildConfig;
import com.chltec.base_blelock.db.dao.BleLockLog;
import com.chltec.blelock.R;
import com.chltec.blelock.activity.BaiduMapActivity;
import com.chltec.blelock.vendor.baidu.LocationManager;
import com.chltec.powerlib.utils.BaseUtil;

/**
 * Created by Administrator on 2016/12/28.
 */

public class ChinaConfig implements BuildConfig {

    private int[] guideImgs = {R.mipmap.step_1, R.mipmap.step_2, R.mipmap.step_3, R.mipmap.step_4, R.mipmap.step_5};

    @Override
    public void showMaps(Context context) {
//        Intent i = new Intent(context, MapActivity.class);
    }

    @Override
    public void showMaps(Context context, BleLockLog lockLog) {
        Intent i = new Intent(context, BaiduMapActivity.class);
        i.putExtra("BleLockLog", lockLog);
        context.startActivity(i);
    }

//    public void showMaps(Context context, BleLockLog lockLog) {
//        Intent i = new Intent(context, BaiduMapActivity.class);
//        i.putExtra("BleLockLog", lockLog);
//        context.startActivity(i);
//    }

    @Override
    public void lockInit(Context context) {
        SDKInitializer.initialize(context);
        LocationManager.getInstance().startLocationClient();
    }

    @Override
    public String getTimes(long time) {
        return null;
    }

    @Override
    public boolean checkEmailOrPhonen(String phone) {
        return BaseUtil.isPhoneNumOrEmail(phone);
    }

    @Override
    public int[] getGuideImg() {
        return guideImgs;
    }

    @Override
    public int getHelpAnimImg() {
        return R.mipmap.help;
    }

    @Override
    public int getLoginLogo() {
        return R.mipmap.logo;
    }

    @Override
    public int getSplashImg() {
        return R.mipmap.splash_page;
    }

    @Override
    public boolean isThePublicVersion() {
        return false;
    }
}
