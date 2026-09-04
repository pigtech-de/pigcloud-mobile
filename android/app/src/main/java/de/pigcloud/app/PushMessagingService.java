package de.pigcloud.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

public class PushMessagingService extends FirebaseMessagingService {

    static final String CHANNEL_ID = "pigcloud_events";

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        if (MainActivity.isInForeground()) {
            return;
        }
        Map<String, String> data = message.getData();
        String body = data.get("body");
        if (body == null || body.isEmpty()) {
            return;
        }
        String title = data.get("title");
        String route = data.get("route");

        ensureChannel();
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (route != null && !route.isEmpty()) {
            open.putExtra(PushPlugin.EXTRA_ROUTE, route);
        }
        int requestCode = (int) (System.currentTimeMillis() & 0x7fffffff);
        PendingIntent pending = PendingIntent.getActivity(
            this,
            requestCode,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(title == null || title.isEmpty() ? getString(R.string.app_name) : title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManagerCompat manager = NotificationManagerCompat.from(this);
        if (!manager.areNotificationsEnabled()) {
            return;
        }
        try {
            manager.notify(requestCode, builder.build());
        } catch (SecurityException ignored) {}
    }

    @Override
    public void onNewToken(@NonNull String token) {
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            getString(R.string.push_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        );
        manager.createNotificationChannel(channel);
    }
}
