package com.example.wifip2p_mdns_test;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.Collection;

// Based on https://developer.android.com/guide/topics/connectivity/wifip2p#java
public class WifiP2PActivity extends AppCompatActivity implements WifiP2pManager.PeerListListener, View.OnClickListener {
    static final String TAG = "WifiP2P-MDNS-Test";
    static final String COMPUTER_PRIMARY_DEVICE_PREFIX = "1-0050F204-";

    WifiP2pManager manager;
    WifiP2pManager.Channel channel;
    BroadcastReceiver receiver;

    TextView logOutput;

    IntentFilter intentFilter;

    WifiManager.MulticastLock lock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);
        receiver = new WifiP2PBroadcastReceiver(manager, channel, this);

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            lock = wifi.createMulticastLock("Log_Tag");
            lock.acquire();
        }

        final Button button = (Button) findViewById(R.id.StartButton);
        button.setOnClickListener(this);
        logOutput = (TextView) findViewById(R.id.LogOutput);

        log("Wired up");
    }

    void log(String text) {
        Log.i(TAG, text);
        logOutput.append(text + "\n");
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(receiver, intentFilter);
        if (lock != null) {
            lock.acquire();
        }
    }

    /* unregister the broadcast receiver and release multicast lock */
    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
        if (lock != null) {
            lock.release();
        }
    }

    public void startConnecting() {
        log("Discovering peers");
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                log("Peer discovery success");
            }

            @Override
            public void onFailure(int reasonCode) {
                log("Peer discovery failed. Reason code: " + reasonCode);
            }
        });
    }

    @Override
    public void onPeersAvailable(WifiP2pDeviceList wifiP2pDeviceList) {
        Collection<WifiP2pDevice> devices = wifiP2pDeviceList.getDeviceList();

        if (devices.size() == 0) {
            // No devices found!
            log("No peers found after scan");
            return;
        }

        for (WifiP2pDevice device : devices) {
            if (device.status != WifiP2pDevice.AVAILABLE) {
                // Device isn't available, so whatever
                continue;
            }
            String primaryDeviceType = device.primaryDeviceType;
            String deviceId = device.deviceName + " ( " + device.deviceAddress + " )";

            if(!primaryDeviceType.startsWith(COMPUTER_PRIMARY_DEVICE_PREFIX)) {
                log("Found device with invalid type "+ primaryDeviceType + ": " + deviceId);
                // Not a computer, don't care!
                continue;
            }
            log("Connecting to available device: " + deviceId);
            // Primary device type can be found here:
            // https://github.com/nfcpy/ndeflib/blob/master/src/ndef/wifi.py#L319
            log("Primary Device Type: " + device.primaryDeviceType);

            WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = device.deviceAddress;
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    log("Connected to " + deviceId);
                    //success logic
                }

                @Override
                public void onFailure(int reason) {
                    log("Failed to connect to " + deviceId + ". Reason: " + reason);
                    //failure logic
                }
            });
        }
    }

    @Override
    public void onClick(View view) {
        startConnecting();
    }
}