package com.example.wifip2p_mdns_test;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
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
import java.util.Collections;

import moe.mauve.agregore.wifiautoconnect.WifiAutoConnect;

// Based on https://developer.android.com/guide/topics/connectivity/wifip2p#java
public class WifiP2PActivity extends AppCompatActivity {
    static final String TAG = "WifiP2P-MDNS-Test";

    String BROADCAST_IP = "224.1.1.1";
    int BROADCAST_PORT = 6669;
    int REPEAT_INTERVAL = 10 * 1000;

    TextView logOutput;
    Button autoConnectButton;

    MulticastSocket socket;
    WifiManager.MulticastLock lock;

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

    WifiAutoConnect wifiAutoConnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainHandler = new Handler(getMainLooper());
        logOutput = (TextView) findViewById(R.id.LogOutput);

        autoConnectButton = (Button) findViewById(R.id.AutoConnectButton);
        autoConnectButton.setOnClickListener(view -> autoConnect());

        wifiAutoConnect = new WifiAutoConnect(this);
        wifiAutoConnect.setSearchTimeout(5 * 1000);
        wifiAutoConnect.setStateChangeListener(new WifiAutoConnect.WifiAutoConnectStateChangeListener(){
            @Override
            public void onStateChange(int state) {
                switch (state) {
                    case WifiAutoConnect.STATE_CONNECTING:
                        logOnMain("Wifi AutoConnect Connecting");
                        break;
                    case WifiAutoConnect.STATE_CONNECTED:
                        String isHost = wifiAutoConnect.isHost() ? "As Host" : "As Client";
                        logOnMain("Wifi AutoConnect Connected " + isHost);
                        autoConnectButton.setVisibility(View.GONE);
                        try {
                            setupSocket();
                        } catch (IOException e) {
                            e.printStackTrace();
                            log("Unable to set up socket");
                        }
                        break;
                    case WifiAutoConnect.STATE_DISCONNECTED:
                        logOnMain("Wifi AutoConnect Disconnected");
                        break;
                    case WifiAutoConnect.STATE_UNINITIALIZED:
                        logOnMain("Wifi AutoConnect Uninitialized");
                        break;
                    case WifiAutoConnect.STATE_ERROR:
                        logOnMain("Wifi AutoConnect Entered Error State");
                        break;
                    case WifiAutoConnect.STATE_INITIALIZED:
                        logOnMain("Wifi AutoConnect Initialized");
                        break;
                    default:
                        logOnMain("Wifi AutoConnect entered invalid state: " + state);
                }
            }
        });

        log("Wired up");
    }

    void log(String text) {
        Log.i(TAG, text);
        logOutput.append(text + "\n");
    }

    void logOnMain(String text) {
        mainHandler.post(() -> log(text));
    }

    @Override
    public void onDestroy() {
        if(lock !=null && lock.isHeld()) {
            lock.release();
        }
        if(wifiAutoConnect != null && wifiAutoConnect.getState() != WifiAutoConnect.STATE_UNINITIALIZED) {
            wifiAutoConnect.disconnect();
        }
        if(socket != null && !socket.isClosed()) {
            socket.close();
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        wifiAutoConnect.onResume();
    }

    /* unregister the broadcast receiver and release multicast lock */
    @Override
    protected void onPause() {
        super.onPause();
        wifiAutoConnect.onPause();
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
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        lock = wifiManager.createMulticastLock(TAG);
        lock.acquire();
        NetworkInterface groupInterface = null;
        for(NetworkInterface i : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            String name = i.getName();
            boolean isPotential = name.contains("wlan0") || name.contains("p2p");
            if(isPotential && i.isUp()) {
                groupInterface = i;
            }
            log(i.getName() + ": Multicast:" + i.supportsMulticast() + " Up:" + i.isUp());
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
    public void autoConnect() {
        wifiAutoConnect.autoConnect();
    }
}
