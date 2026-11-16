package com.local.projremote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Other TVs and projectors on the same network that run this remote, so a phone can switch between them. Each
 * device announces itself as a DNS-SD service ("_phoneremote._tcp") and browses for the others. Only names and
 * addresses are exchanged; each device still pairs every phone itself.
 */
final class Peers {
    private static final String TAG = "ProjRemote";
    static final String TYPE = "_phoneremote._tcp.";

    private final NsdManager nsd;
    private final Map<String, String> found = new ConcurrentHashMap<>();   // service name -> http://ip:port/
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;
    private volatile String ownName = "";
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;

    Peers(Context context) {
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    void start(String name, String id, int port) {
        stop();
        if (nsd == null) return;
        try {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(name);
            info.setServiceType(TYPE);
            info.setPort(port);
            info.setAttribute("id", id);
            ownName = name;
            registration = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo s) { ownName = s.getServiceName(); }
                @Override public void onRegistrationFailed(NsdServiceInfo s, int error) { Log.w(TAG, "announce failed " + error); }
                @Override public void onServiceUnregistered(NsdServiceInfo s) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo s, int error) { }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
            discovery = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String type) { }
                @Override public void onDiscoveryStopped(String type) { }
                @Override public void onStartDiscoveryFailed(String type, int error) { Log.w(TAG, "browse failed " + error); }
                @Override public void onStopDiscoveryFailed(String type, int error) { }
                @Override public void onServiceFound(NsdServiceInfo s) {
                    if (s.getServiceName().equals(ownName)) return;
                    synchronized (toResolve) {
                        toResolve.add(s);
                    }
                    resolveNext();
                }
                @Override public void onServiceLost(NsdServiceInfo s) { found.remove(s.getServiceName()); }
            };
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException failure) {
            Log.w(TAG, "peers unavailable: " + failure);
        }
    }

    /** Older Android resolves one service at a time. */
    private void resolveNext() {
        NsdServiceInfo next;
        synchronized (toResolve) {
            if (resolving || toResolve.isEmpty()) return;
            next = toResolve.poll();
            resolving = true;
        }
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo s, int error) { done(); }
                @Override public void onServiceResolved(NsdServiceInfo s) {
                    InetAddress host = s.getHost();
                    if (host != null && host.getAddress().length == 4 && !s.getServiceName().equals(ownName)) {
                        found.put(s.getServiceName(), "http://" + host.getHostAddress() + ":" + s.getPort() + "/");
                    }
                    done();
                }
            });
        } catch (RuntimeException failure) {
            done();
        }
    }

    private void done() {
        synchronized (toResolve) {
            resolving = false;
        }
        resolveNext();
    }

    /** [{"name","url"}] of the other devices, without this one. */
    JSONArray json(String ownAddress) {
        JSONArray list = new JSONArray();
        for (Map.Entry<String, String> e : found.entrySet()) {
            if (ownAddress != null && e.getValue().contains("//" + ownAddress + ":")) continue;
            try {
                list.put(new JSONObject().put("name", e.getKey()).put("url", e.getValue()));
            } catch (Exception ignored) { }
        }
        return list;
    }

    void stop() {
        try {
            if (registration != null) nsd.unregisterService(registration);
        } catch (RuntimeException ignored) { }
        try {
            if (discovery != null) nsd.stopServiceDiscovery(discovery);
        } catch (RuntimeException ignored) { }
        registration = null;
        discovery = null;
        found.clear();
    }
}
