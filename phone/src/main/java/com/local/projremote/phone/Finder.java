package com.local.projremote.phone;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;

/**
 * Finds the projectors and TVs running the remote on this Wi-Fi: each announces itself as a DNS-SD service
 * ("_phoneremote._tcp", see Peers.java in the TV app) with its name, port and a fixed id.
 */
final class Finder {
    private static final String TAG = "PhoneRemote";
    private static final String TYPE = "_phoneremote._tcp.";

    /** One device found: its name, its page, and the id that stays the same when its address changes. */
    static final class Device {
        final String name, url, id;

        Device(String name, String url, String id) {
            this.name = name;
            this.url = url;
            this.id = id;
        }
    }

    interface Listener {
        void onFound(Device device);
    }

    private final NsdManager nsd;
    private final WifiManager.MulticastLock lock;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private boolean resolving;
    private NsdManager.DiscoveryListener discovery;

    Finder(Context context, Listener listener) {
        this.listener = listener;
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        lock = wifi == null ? null : wifi.createMulticastLock("phoneremote");
        if (lock != null) lock.setReferenceCounted(false);
    }

    void start() {
        stop();
        if (nsd == null) return;
        if (lock != null) lock.acquire();
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onStartDiscoveryFailed(String type, int error) { Log.w(TAG, "browse failed " + error); }
            @Override public void onStopDiscoveryFailed(String type, int error) { }
            @Override public void onServiceLost(NsdServiceInfo s) { }
            @Override public void onServiceFound(NsdServiceInfo s) {
                synchronized (queue) {
                    queue.add(s);
                }
                resolveNext();
            }
        };
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException failed) {
            Log.w(TAG, "browse: " + failed);
            discovery = null;
        }
    }

    /** Android resolves one service at a time. */
    private void resolveNext() {
        NsdServiceInfo next;
        synchronized (queue) {
            if (resolving || queue.isEmpty()) return;
            resolving = true;
            next = queue.poll();
        }
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo s, int error) { done(); }
                @Override public void onServiceResolved(NsdServiceInfo s) {
                    if (s.getHost() instanceof Inet4Address) {
                        String url = "http://" + s.getHost().getHostAddress() + ":" + s.getPort() + "/";
                        Device d = new Device(s.getServiceName(), url, attribute(s, "id"));
                        main.post(() -> listener.onFound(d));
                    }
                    done();
                }
            });
        } catch (RuntimeException failed) {
            done();
        }
    }

    private void done() {
        synchronized (queue) {
            resolving = false;
        }
        resolveNext();
    }

    private static String attribute(NsdServiceInfo s, String key) {
        Map<String, byte[]> all = s.getAttributes();
        byte[] v = all == null ? null : all.get(key);
        return v == null || v.length == 0 ? null : new String(v, StandardCharsets.UTF_8);
    }

    void stop() {
        if (discovery != null) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (RuntimeException ignored) { }
            discovery = null;
        }
        synchronized (queue) {
            queue.clear();
            resolving = false;
        }
        if (lock != null && lock.isHeld()) lock.release();
    }
}
