package com.chltec.blelock.vendor.baidu;

import android.location.Location;

import com.baidu.location.BDLocation;
import com.baidu.location.BDLocationListener;
import com.baidu.location.LocationClient;
import com.baidu.location.LocationClientOption;
import com.baidu.location.Poi;
import com.chltec.base_blelock.utils.BleLockSharedPreferences;
import com.chltec.blelock.BleLockApplication;
import com.socks.library.KLog;

public class LocationManager{
    protected boolean alreadyUpdatedLocation = false;
    private static LocationManager instance;
    private LocationClient mLocationClient;
    private BDLocationListener bdLocationListener;
    private static final double BALocationDefaultLongitudeOrLatitude = 4.9E-324;

    public static LocationManager getInstance() {
        if (instance == null) {
            instance = new LocationManager();
        }
        return instance;
    }

    public void startLocationClient() {
        alreadyUpdatedLocation = false;
        setUpBaiduLocationClientIfNeeded();
        if (mLocationClient != null && !mLocationClient.isStarted()) {
            mLocationClient.start();
        }
    }

    public void stopLocationClient() {
        if (mLocationClient != null && mLocationClient.isStarted()) {
            mLocationClient.stop();
        }
        resetUpdateLocationTag();
    }

    private void setUpBaiduLocationClientIfNeeded() {
        if (mLocationClient == null) {
            mLocationClient = new LocationClient(BleLockApplication.getInstance());

            LocationClientOption option = new LocationClientOption();
            option.setLocationMode(LocationClientOption.LocationMode.Battery_Saving);//可选，默认高精度，设置定位模式，高精度，低功耗，仅设备
            option.setCoorType("bd09ll");//可选，默认gcj02，设置返回的定位结果坐标系，如果配合百度地图使用，建议设置为bd09ll;
            option.setScanSpan(300 *1000);//可选，默认0，即仅定位一次，设置发起定位请求的间隔需要大于等于1000ms才是有效的
            option.setIsNeedAddress(true);//可选，设置是否需要地址信息，默认不需要
            option.setIsNeedLocationDescribe(false);//可选，设置是否需要地址描述
            option.setNeedDeviceDirect(false);//可选，设置是否需要设备方向结果
            option.setLocationNotify(false);//可选，默认false，设置是否当gps有效时按照1S1次频率输出GPS结果
            option.setIgnoreKillProcess(false);//可选，默认true，定位SDK内部是一个SERVICE，并放到了独立进程，设置是否在stop的时候杀死这个进程，默认不杀死
            option.setIsNeedLocationDescribe(false);//可选，默认false，设置是否需要位置语义化结果，可以在BDLocation.getLocationDescribe里得到，结果类似于“在北京天安门附近”
            option.setIsNeedLocationPoiList(false);//可选，默认false，设置是否需要POI结果，可以在BDLocation.getPoiList里得到
            option.SetIgnoreCacheException(false);//可选，默认false，设置是否收集CRASH信息，默认收集
            mLocationClient.setLocOption(option);

            initBDLocatonListener();
            mLocationClient.registerLocationListener(bdLocationListener);
        }
    }

    private void initBDLocatonListener() {
        if (bdLocationListener == null) {
            /*****
             * @see copy funtion to you project
             * 定位结果回调，重写onReceiveLocation方法，可以直接拷贝如下代码到自己工程中修改
             *
             */
             bdLocationListener = new BDLocationListener() {

                @Override
                public void onReceiveLocation(BDLocation location) {
                    if (null != location && location.getLocType() != BDLocation.TypeServerError) {
                        StringBuffer sb = new StringBuffer(256);
                        sb.append("time : ");
                        /**
                         * 时间也可以使用systemClock.elapsedRealtime()方法 获取的是自从开机以来，每次回调的时间；
                         * location.getTime() 是指服务端出本次结果的时间，如果位置不发生变化，则时间不变
                         */
                        sb.append(location.getTime());
                        sb.append("\nerror code : ");
                        sb.append(location.getLocType());
                        sb.append("\nlatitude : ");
                        sb.append(location.getLatitude());
                        sb.append("\nlontitude : ");
                        sb.append(location.getLongitude());
                        sb.append("\nradius : ");
                        sb.append(location.getRadius());
                        sb.append("\nCountryCode : ");
                        sb.append(location.getCountryCode());
                        sb.append("\nCountry : ");
                        sb.append(location.getCountry());
                        sb.append("\ncitycode : ");
                        sb.append(location.getCityCode());
                        sb.append("\ncity : ");
                        sb.append(location.getCity());
                        sb.append("\nDistrict : ");
                        sb.append(location.getDistrict());
                        sb.append("\nStreet : ");
                        sb.append(location.getStreet());
                        sb.append("\naddr : ");
                        sb.append(location.getAddrStr());
                        sb.append("\nDescribe: ");
                        sb.append(location.getLocationDescribe());
                        sb.append("\nDirection(not all devices have value): ");
                        sb.append(location.getDirection());
                        sb.append("\nPoi: ");
                        if (location.getPoiList() != null && !location.getPoiList().isEmpty()) {
                            for (int i = 0; i < location.getPoiList().size(); i++) {
                                Poi poi = (Poi) location.getPoiList().get(i);
                                sb.append(poi.getName() + ";");
                            }
                        }
                        if (location.getLocType() == BDLocation.TypeGpsLocation) {// GPS定位结果
                            sb.append("\nspeed : ");
                            sb.append(location.getSpeed());// 单位：km/h
                            sb.append("\nsatellite : ");
                            sb.append(location.getSatelliteNumber());
                            sb.append("\nheight : ");
                            sb.append(location.getAltitude());// 单位：米
                            sb.append("\ndescribe : ");
                            sb.append("gps定位成功");
                        } else if (location.getLocType() == BDLocation.TypeNetWorkLocation) {// 网络定位结果
                            // 运营商信息
                            sb.append("\noperationers : ");
                            sb.append(location.getOperators());
                            sb.append("\ndescribe : ");
                            sb.append("网络定位成功");
                        } else if (location.getLocType() == BDLocation.TypeOffLineLocation) {// 离线定位结果
                            sb.append("\ndescribe : ");
                            sb.append("离线定位成功，离线定位结果也是有效的");
                        } else if (location.getLocType() == BDLocation.TypeServerError) {
                            sb.append("\ndescribe : ");
                            sb.append("服务端网络定位失败，可以反馈IMEI号和大体定位时间到loc-bugs@baidu.com，会有人追查原因");
                        } else if (location.getLocType() == BDLocation.TypeNetWorkException) {
                            sb.append("\ndescribe : ");
                            sb.append("网络不同导致定位失败，请检查网络是否通畅");
                        } else if (location.getLocType() == BDLocation.TypeCriteriaException) {
                            sb.append("\ndescribe : ");
                            sb.append("无法获取有效定位依据导致定位失败，一般是由于手机的原因，处于飞行模式下一般会造成这种结果，可以试着重启手机");
                        }
                        KLog.i(sb.toString());

//                        SobrrMessageBanner.showErrorBanner("city:" + location.getCity());
                        BleLockSharedPreferences.setCity(location.getCity());
                        updateLocation(location);
                    }
                }

            };
        }
    }

    public void updateLocation(BDLocation location) {
        if (location.getLatitude() != BALocationDefaultLongitudeOrLatitude && location.getLongitude() != BALocationDefaultLongitudeOrLatitude) {
            BleLockSharedPreferences.setLocationLatitude((float) location.getLatitude());
            BleLockSharedPreferences.setLocationLongitude((float) location.getLongitude());
//            if (!isAlreadyUpdatedLocationToBackend()) {
//                SobrrMessageBanner.showErrorBanner("latitude:" + location.getLatitude() + ",longitude:" + location.getLongitude());
//            }
        }
//        updateCurrentLocation(location);
    }

    private void updateCurrentLocation(BDLocation location) {
        Location newLocation = new Location("");
        newLocation.setLatitude(location.getLatitude());
        newLocation.setLongitude(location.getLongitude());
        mLocationClient.updateLocation(newLocation);
    }

    protected void resetUpdateLocationTag() {
        alreadyUpdatedLocation = false;
    }

    protected boolean isAlreadyUpdatedLocationToBackend() {
        return alreadyUpdatedLocation;
    }
}
