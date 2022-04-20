package com.example.wifip2p_mdns_test;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;

// Based on https://developer.android.com/guide/topics/connectivity/wifip2p#java
public class WifiP2PActivity extends AppCompatActivity implements WifiP2pManager.PeerListListener {
    static final String TAG = "WifiP2P-MDNS-Test";
    static final String COMPUTER_PRIMARY_DEVICE_PREFIX = "10-0050F204-";
    static final String HOST_MAC_ADDRESS = "00:06:06:06:04:20";
    static final String HOST_NETWORK_NAME = "DIRECT-AG-Example";
    static final String HOST_PASSWORD = "password";
    WifiP2pConfig DEFAULT_HOST_CONFIG = new WifiP2pConfig
            .Builder()
            .setNetworkName(HOST_NETWORK_NAME)
            .setPassphrase(HOST_PASSWORD)
            .setDeviceAddress(MacAddress.fromString(HOST_MAC_ADDRESS))
            .build();

    String BROADCAST_IP = "224.1.1.1";
    int BROADCAST_PORT = 6669;
    int REPEAT_INTERVAL = 10 * 1000;
    int SEARCH_TIMEOUT = 10 * 1000;

    WifiP2pManager p2pManager;
    WifiManager wifiManager;
    WifiP2pManager.Channel channel;
    BroadcastReceiver receiver;
    IntentFilter intentFilter;

    TextView logOutput;

    WifiManager.MulticastLock lock;

    boolean autoConnecting = false;
    MulticastSocket socket;

    Handler mainHandler;
    Thread socketConsumerThread = new Thread() {
        @Override
        public void run() {
            while(!socket.isClosed()) {
                int length = 1024;
                byte[] buffer = new byte[length];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    String string = new String(packet.getData(), StandardCharsets.UTF_8).trim();
                    String from = packet.getAddress().toString();
                    logOnMain("<< ( " + from + " ) " + string);
                    Thread.sleep(100);
                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    break;
                }
            }
        }
    };
    Thread socketProducerThread = new Thread() {
        @Override
        public void run() {
            while(!socket.isClosed()) {
                try {
                    sendMessage("Hello World!");
                    Thread.sleep(REPEAT_INTERVAL);
                } catch (InterruptedException | IOException e) {
                    e.printStackTrace();
                    break;
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainHandler = new Handler(getMainLooper());

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        p2pManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = p2pManager.initialize(this, getMainLooper(), null);
        receiver = new WifiP2PBroadcastReceiver(p2pManager, channel, this);

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        logOutput = (TextView) findViewById(R.id.LogOutput);

        final Button startButton = (Button) findViewById(R.id.StartButton);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startScanning();
            }
        });

        final Button createButton = (Button) findViewById(R.id.CreateButton);
        createButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                createGroup();
            }
        });

        final Button connectButton = (Button) findViewById(R.id.ConnectButton);
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                connectGroup();
            }
        });

        final Button autoConnectButton = (Button) findViewById(R.id.AutoConnectButton);
        autoConnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                autoConnect();
            }
        });

        log("Wired up");

        if(!wifiManager.isWifiEnabled()) {
            log("Error: Wifi is not enabled");
        }
    }

    void log(String text) {
        Log.i(TAG, text);
        logOutput.append(text + "\n");
    }

    void logOnMain(String text) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                log(text);
            }
        });
    }

    @Override
    public void onDestroy() {
        if (p2pManager != null) {
            p2pManager.removeGroup(channel, null);
        }
        if(socket != null && !socket.isClosed()) {
            socket.close();
        }
        super.onDestroy();
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

    public void sendMessage(String message) throws IOException {
        InetAddress groupAddress = InetAddress.getByName(BROADCAST_IP);
        SocketAddress group = new InetSocketAddress(groupAddress, BROADCAST_PORT);

        byte[] buffer = message.getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group);
        socket.send(packet);
        logOnMain(">> " + message);
    }

    public void setupSocket() throws IOException {
        if(socket != null && !socket.isClosed()) return;

        NetworkInterface groupInterface = null;
        for(NetworkInterface i : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            String name = i.getName();
            boolean isPotential = name.contains("wlan0") || name.contains("p2p");
            if(isPotential && i.isUp()) {
                groupInterface = i;
            }
            log(i.getName() + ": Multicast:" + i.supportsMulticast() + " Up:" + i.isUp());
        }

        if(lock == null) {
            lock = wifiManager.createMulticastLock("Log_Tag");
            lock.acquire();
        }

        InetAddress groupAddress = InetAddress.getByName(BROADCAST_IP);
        SocketAddress group = new InetSocketAddress(groupAddress, BROADCAST_PORT);
        socket = new MulticastSocket(BROADCAST_PORT);

        socket.setNetworkInterface(groupInterface);

        log("Joining group " + groupInterface.getDisplayName());
        for(InterfaceAddress address : groupInterface.getInterfaceAddresses()) {
            log(address.toString());
        }

        socket.joinGroup(group, groupInterface);
        boolean loopBackDisabled = true;
        socket.setLoopbackMode(loopBackDisabled);
        socketConsumerThread.start();
        socketProducerThread.start();
        log("Set up multicast socket");
    }

    public void startScanning() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            log("Error: Can not discovery peers, no permission");
            return;
        }

        log("Discovering peers");
        p2pManager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
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

    public void autoConnect() {
        autoConnecting = true;
        connectGroup();
    }

    public void connectGroup() {
        /*log("Failed to connect to default host: " + getActionFailureReason(i));
                if(autoConnecting && i != WifiP2pManager.BUSY) {
                    log("No existing group found, creating one");
                    createGroup();
                }*/
        final NetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid((HOST_NETWORK_NAME))
                .setWpa2Passphrase(HOST_PASSWORD)
                .build();
        final NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();
        final ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        connectivityManager.requestNetwork(request, new ConnectivityManager.NetworkCallback() {
            @Override
            public void onUnavailable() {
                logOnMain("Unable to find existing group");
                // Create group if autocreating and one doesn't exist
                if(autoConnecting) {
                    createGroup();
                }
            }

            @Override
            public void onAvailable(Network network) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        log("Connected to existing group");
                        try {
                            setupSocket();
                        } catch (IOException e) {
                            e.printStackTrace();
                            log("Unable to set up Multicast Socket");
                        }
                    }
                });
            }

            @Override
            public void onLost(Network network) {
                logOnMain("Disconnected from group");
            }
        }, SEARCH_TIMEOUT);


    }

    public void createGroup() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            log("Error: Could not create group, no permission");
            return;
        }
        p2pManager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
            @SuppressLint("MissingPermission")
            @Override
            public void onGroupInfoAvailable(WifiP2pGroup wifiP2pGroup) {
                if(wifiP2pGroup != null) {
                    log("Group already active");
                    return;
                }

                p2pManager.createGroup(channel, DEFAULT_HOST_CONFIG, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        log("Created group");
                    }

                    @Override
                    public void onFailure(int i) {
                        String reason = getActionFailureReason(i);
                        log("Failed to create group: " + reason);
                    }
                });

            }
        });
    }

    private String getActionFailureReason(int i) {
        String reason = "Unknown";
        if(i == WifiP2pManager.P2P_UNSUPPORTED) reason = "P2P unsupported";
        if(i == WifiP2pManager.ERROR) reason = "Error";
        if(i == WifiP2pManager.BUSY) reason = "Busy";
        return reason;
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
            // log(device.toString());
            if (device.status != WifiP2pDevice.AVAILABLE) {
                // Device isn't available, so whatever
                log("Found unavailable device: " + device.deviceAddress + " " + device.status);
                continue;
            }
            String primaryDeviceType = device.primaryDeviceType;
            String deviceId = device.deviceName + " ( " + device.deviceAddress + " )";

            if (!primaryDeviceType.startsWith(COMPUTER_PRIMARY_DEVICE_PREFIX)) {
                log("Found device with invalid type " + primaryDeviceType + ": " + deviceId);
                // Not a computer, don't care!
                continue;
            }
            // TODO: Find network name and auto-create group
            log("Connecting to available device: " + deviceId);
            if (true) continue;
            // Primary device type can be found here:
            // https://github.com/nfcpy/ndeflib/blob/master/src/ndef/wifi.py#L319
            log("Primary Device Type: " + device.primaryDeviceType);

            WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = device.deviceAddress;
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                log("Error: Could not connect to device, no permission");
                return;
            }
            p2pManager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    log("Connected to " + deviceId);
                    //success logic
                }

                @Override
                public void onFailure(int reason) {
                    log("Failed to connect to " + deviceId + ". Reason: " + getActionFailureReason(reason));
                    //failure logic
                }
            });
        }
    }
}
