package com.local.projremote;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Android lets this app see which app is playing media (MediaSessionManager asks for an enabled
 * notification listener). It does not read, keep or send notifications.
 */
public class MediaListener extends NotificationListenerService {
}
