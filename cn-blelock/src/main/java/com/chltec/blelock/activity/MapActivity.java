package com.chltec.blelock.activity;

import android.app.Activity;
import android.os.Bundle;

import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.BitmapDescriptor;
import com.baidu.mapapi.map.BitmapDescriptorFactory;
import com.baidu.mapapi.map.MapStatus;
import com.baidu.mapapi.map.MapStatusUpdate;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.Marker;
import com.baidu.mapapi.map.MarkerOptions;
import com.baidu.mapapi.map.OverlayOptions;
import com.baidu.mapapi.model.LatLng;
import com.chltec.base_blelock.R;
import com.chltec.base_blelock.db.dao.BleLockLog;

import butterknife.BindView;
import butterknife.ButterKnife;

/**
 * Created by Administrator on 2016/12/12.
 */

public class MapActivity extends Activity {

    @BindView(R.id.map_baidu)
    MapView mapView;
    private BaiduMap baiduMap;
    Marker maker;
    BleLockLog mlog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //在使用sdk各组件之前初始化context信息，传入ApplicationContext
        //注意该方法要在setContentView方法之前实现
//        SDKInitializer.initialize(getApplicationContext());
        setContentView(R.layout.layout_map_fragment);
        ButterKnife.bind(this);

        mlog = getIntent().getParcelableExtra("BleLockLog");
        baiduMap = mapView.getMap();
        baiduMap.setMapType(BaiduMap.MAP_TYPE_NORMAL);

        float lat = mlog.getLatitude();
        float lng = mlog.getLongitude();
        //定义Maker坐标点
        LatLng point = new LatLng(lat, lng);
        //构建Marker图标
        BitmapDescriptor bitmap = BitmapDescriptorFactory.fromResource(R.mipmap.icon_marker);
        //构建MarkerOption，用于在地图上添加Marker
        OverlayOptions option = new MarkerOptions()
                .position(point)   //设置位置
                .icon(bitmap)      //设置marker图标
                .draggable(true);  //设置手势拖拽;
        //在地图上添加Marker，并显示
        maker = (Marker) baiduMap.addOverlay(option);
        //定义Maker坐标点
        MapStatus mMapStatus = new MapStatus.Builder().target(new LatLng(lat, lng)).build();
        MapStatusUpdate mMapStatusUpdate = MapStatusUpdateFactory.newMapStatus(mMapStatus);
        //改变地图状态
        baiduMap.setMapStatus(mMapStatusUpdate);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //在activity执行onDestroy时执行mMapView.onDestroy()，实现地图生命周期管理
        mapView.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        //在activity执行onResume时执行mMapView. onResume ()，实现地图生命周期管理
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        //在activity执行onPause时执行mMapView. onPause ()，实现地图生命周期管理
        mapView.onPause();
    }

}
