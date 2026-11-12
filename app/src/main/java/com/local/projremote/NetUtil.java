package com.local.projremote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** This device's own IPv4 address on the local network: Wi-Fi or Ethernet, whatever it is using now. */
final class NetUtil {
    private NetUtil() { }

    static Inet4Address ipv4(Context context) {
        // The network Android is using right now.
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = cm == null ? null : cm.getActiveNetwork();
            LinkProperties link = active == null ? null : cm.getLinkProperties(active);
            if (link != null) {
                for (LinkAddress la : link.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (a instanceof Inet4Address && usable(a)) return (Inet4Address) a;
                }
            }
        } catch (RuntimeException ignored) { }
        // No active network reported (some boxes on Ethernet, or as a hotspot): any plain local interface.
        try {
            Inet4Address fallback = null;
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                String n = nif.getName();
                // Wi-Fi Direct (casting), mobile data, VPN and test interfaces are not where the phone is.
                if (n.startsWith("p2p") || n.startsWith("rmnet") || n.startsWith("tun") || n.startsWith("dummy")) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !usable(a)) continue;
                    if (n.startsWith("wlan") || n.startsWith("eth")) return (Inet4Address) a;
                    if (fallback == null) fallback = (Inet4Address) a;
                }
            }
            return fallback;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** The interface that address is on, for joining the multicast group there. */
    static NetworkInterface interfaceOf(Inet4Address address) {
        try {
            return address == null ? null : NetworkInterface.getByInetAddress(address);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean usable(InetAddress a) {
        return !a.isLoopbackAddress() && !a.isLinkLocalAddress() && !a.isAnyLocalAddress();
    }
}
