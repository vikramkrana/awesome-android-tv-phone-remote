package com.local.projremote;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Locale;

/**
 * Answers Bonjour (mDNS) lookups for one name, e.g. "living-room-3f2a.local", with this device's current IPv4 address,
 * so the phone's bookmark keeps working when the network or the address changes. Only A-record questions for
 * exactly that name are answered; everything else is ignored.
 */
final class MdnsResponder {
    private static final String TAG = "ProjRemote";
    private static final String GROUP = "224.0.0.251";
    private static final int PORT = 5353;

    private final Context context;
    private volatile String name;
    private volatile boolean running;
    private volatile MulticastSocket socket;
    private WifiManager.MulticastLock lock;

    MdnsResponder(Context context, String name) {
        this.context = context.getApplicationContext();
        this.name = name.toLowerCase(Locale.ROOT);
    }

    /** Answers to another name from now on (the device was renamed). */
    void setName(String name) {
        this.name = name.toLowerCase(Locale.ROOT);
    }

    void start() {
        running = true;
        Thread t = new Thread(this::loop, "remote-mdns");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        if (socket != null) socket.close();
        if (lock != null && lock.isHeld()) lock.release();
    }

    private void loop() {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            lock = wifi.createMulticastLock("projremote-mdns");
            lock.setReferenceCounted(false);
            lock.acquire();
        } catch (RuntimeException noWifi) {
            // an Ethernet-only box: multicast needs no lock there
        }
        while (running) {
            try (MulticastSocket s = new MulticastSocket(null)) {
                socket = s;
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT));
                InetAddress group = InetAddress.getByName(GROUP);
                NetworkInterface nif = NetUtil.interfaceOf(NetUtil.ipv4(context));
                if (nif != null) {
                    s.joinGroup(new InetSocketAddress(group, PORT), nif);
                    s.setNetworkInterface(nif);
                } else {
                    s.joinGroup(group);
                }
                s.setTimeToLive(255);
                byte[] buf = new byte[1500];
                while (running) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    s.receive(packet);
                    answer(s, packet, group);
                }
            } catch (Exception failure) {
                if (!running) return;
                Log.w(TAG, "mdns: " + failure + ", retrying");
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
            }
        }
    }

    private void answer(MulticastSocket s, DatagramPacket packet, InetAddress group) throws Exception {
        byte[] d = packet.getData();
        int len = packet.getLength();
        if (len < 12) return;
        int flags = ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        if ((flags & 0x8000) != 0) return;                     // a response, not a question
        int questions = ((d[4] & 0xFF) << 8) | (d[5] & 0xFF);
        int pos = 12;
        boolean asked = false;
        boolean unicast = false;
        for (int i = 0; i < questions && pos < len; i++) {
            StringBuilder qname = new StringBuilder();
            pos = readName(d, len, pos, qname, 0);
            if (pos < 0 || pos + 4 > len) return;
            int type = ((d[pos] & 0xFF) << 8) | (d[pos + 1] & 0xFF);
            int qclass = ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
            pos += 4;
            if (qname.toString().equalsIgnoreCase(name) && (type == 1 || type == 255)) {
                asked = true;
                if ((qclass & 0x8000) != 0) unicast = true;    // "QU": the asker wants a direct reply
            }
        }
        if (!asked) return;
        Inet4Address ip = NetUtil.ipv4(context);
        if (ip == null) return;
        boolean legacy = packet.getPort() != PORT;            // a plain DNS client: reply to it directly with its id
        byte[] reply = build(legacy ? (((d[0] & 0xFF) << 8) | (d[1] & 0xFF)) : 0, ip, legacy);
        if (legacy || unicast) {
            s.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
        }
        if (!legacy) s.send(new DatagramPacket(reply, reply.length, group, PORT));
    }

    private byte[] build(int id, Inet4Address ip, boolean legacy) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] header = {id >> 8, id & 0xFF, 0x84, 0x00, 0, 0, 0, 1, 0, 0, 0, 0};
        for (int b : header) out.write(b);
        for (String label : name.split("\\.")) {
            byte[] l = label.getBytes();
            out.write(l.length);
            out.write(l, 0, l.length);
        }
        out.write(0);
        out.write(0); out.write(1);                                    // type A
        out.write(legacy ? 0x00 : 0x80); out.write(1);                 // class IN, cache-flush for mDNS
        out.write(0); out.write(0); out.write(0); out.write(120);       // TTL 120 s
        out.write(0); out.write(4);
        byte[] a = ip.getAddress();
        out.write(a, 0, 4);
        return out.toByteArray();
    }

    /** Reads a DNS name (with compression pointers) into out; returns the position after it, or -1. */
    private static int readName(byte[] d, int len, int pos, StringBuilder out, int depth) {
        if (depth > 8) return -1;
        while (pos < len) {
            int l = d[pos] & 0xFF;
            if (l == 0) return pos + 1;
            if ((l & 0xC0) == 0xC0) {
                if (pos + 1 >= len) return -1;
                int ptr = ((l & 0x3F) << 8) | (d[pos + 1] & 0xFF);
                if (readName(d, len, ptr, out, depth + 1) < 0) return -1;
                return pos + 2;
            }
            if (pos + 1 + l > len) return -1;
            if (out.length() > 0) out.append('.');
            out.append(new String(d, pos + 1, l));
            pos += 1 + l;
        }
        return -1;
    }
}
