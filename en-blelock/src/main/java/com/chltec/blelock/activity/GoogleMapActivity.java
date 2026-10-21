package com.chltec.blelock.activity;


import android.location.LocationManager;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.util.Log;

import com.chltec.base_blelock.db.dao.BleLockLog;
import com.chltec.blelock.R;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;


public class GoogleMapActivity extends FragmentActivity implements OnMapReadyCallback {
    GoogleMap mMap;
    BleLockLog mlog;
    /* GPS Constant Permission */
    private static final int MY_PERMISSION_ACCESS_COARSE_LOCATION = 11;
    private static final int MY_PERMISSION_ACCESS_FINE_LOCATION = 12;
    /* Position */
    private static final long MINIMUM_TIME = 10000; // 10s
    private static final float MINIMUM_DISTANCE = 50; // 50m
    // /* GPS */
    private String mProviderName;
    private LocationManager mLocationManager;
    private LocationListener mLocationListener;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_google_map);

        MapFragment mapFragment = (MapFragment) getFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
        mlog = getIntent().getParcelableExtra("BleLockLog");


    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
        float lat = mlog.getLatitude();
        float lng = mlog.getLongitude();
        Log.w("AAA", "lat: " + lat);
        Log.w("AAA", "lng: " + lng);

//        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
//                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
//            // TODO: Consider calling
//            //    ActivityCompat#requestPermissions
//            // here to request the missing permissions, and then overriding
//            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
//            //                                          int[] grantResults)
//            // to handle the case where the user grants the permission. See the documentation
//            // for ActivityCompat#requestPermissions for more details.
//            if (mProviderName == null || mProviderName.equals(" ")){
//                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
//            }
//            // At least one provider activated. Get the coordinates
//            switch (mProviderName) {
//                case "passive":
//                    mLocationManager.requestLocationUpdates(mProviderName, MINIMUM_TIME, MINIMUM_DISTANCE, mLocationListener);
//                    Location mLocation = mLocationManager.getLastKnownLocation(mProviderName);
//                    break;
//                case "network":
//                    break;
//                case "gps":
//                    break;
//            } // One or both permissions are denied.
//        } else {
//            // The ACCESS_COARSE_LOCATION is denied, then I request it and manage the result in
//            // onRequestPermissionsResult() using the constant MY_PERMISSION_ACCESS_FINE_LOCATION
//            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ) {
//                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
//                        MY_PERMISSION_ACCESS_COARSE_LOCATION);
//            }
//            // The ACCESS_FINE_LOCATION is denied, then I request it and manage the result in
//            // onRequestPermissionsResult() using the constant MY_PERMISSION_ACCESS_FINE_LOCATION
//            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ) {
//                ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.ACCESS_FINE_LOCATION },
//                        MY_PERMISSION_ACCESS_FINE_LOCATION);
//            }
//        }
        /**
         * setMyLocationEnabled()
         */
//        mMap.setMyLocationEnabled(true);
        LatLng latLng = new LatLng(lat, lng);
        //加入手势拖拽
        mMap.addMarker(new MarkerOptions().position(latLng).draggable(true));
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 12));
    }
//    @Override
//    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
//        switch (requestCode) {
//            case MY_PERMISSION_ACCESS_COARSE_LOCATION: {
//                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//                    // permission was granted
//                } else {
//                    // permission denied
//                }
//                break;
//            }
//            case MY_PERMISSION_ACCESS_FINE_LOCATION: {
//                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//                    // permission was granted
//                } else {
//                    // permission denied
//                }
//                break;
//                }
//            }
//        }
}
