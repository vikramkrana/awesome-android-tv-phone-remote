package com.local.projremote;

import android.graphics.Bitmap;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.net.Inet4Address;
import java.util.Collections;

/** QR codes for the remote's address. */
final class Qr {
    private Qr() { }

    /**
     * The page address, by IP (a phone camera opens it on any network). "#pair" only tells the page it was opened
     * from the QR, so it asks the projector for a PIN straight away; it carries no secret.
     */
    static String url(android.content.Context context, int port) {
        Inet4Address ip = NetUtil.ipv4(context);
        if (ip == null || port <= 0) return null;
        return "http://" + ip.getHostAddress() + ":" + port + "/#pair";
    }

    /** White modules on transparent by default, for a dark card; or black on white. */
    static Bitmap bitmap(String value, int size, boolean light) {
        try {
            BitMatrix m = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size,
                    Collections.singletonMap(EncodeHintType.MARGIN, 1));
            int[] px = new int[size * size];
            int on = light ? 0xFF000000 : 0xFFFFFFFF;
            int off = light ? 0xFFFFFFFF : 0x00000000;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? on : off;
            }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        } catch (Exception failure) {
            return null;
        }
    }
}
