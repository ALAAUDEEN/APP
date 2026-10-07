package in.co.deen.fam;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class NotifyService extends Service {
    // Must match $pushTopic in index.php
    static final String TOPIC = "bank_statement_alert_4092";
    static final String CH_ALERT = "fam_alerts";
    static final String CH_SVC = "fam_service";

    private volatile boolean running = false;
    private Thread worker;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannels();
        Notification n = new Notification.Builder(this, CH_SVC)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("FAM running")
                .setContentText("Listening for statement alerts")
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, n);
        }
        if (!running) {
            running = true;
            worker = new Thread(this::listen, "ntfy-listener");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Statement alerts", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_SVC, "Background service", NotificationManager.IMPORTANCE_MIN));
    }

    private void listen() {
        SharedPreferences sp = getSharedPreferences("fam", Context.MODE_PRIVATE);
        while (running) {
            HttpURLConnection c = null;
            try {
                long since = sp.getLong("since", System.currentTimeMillis() / 1000);
                c = (HttpURLConnection) new URL("https://ntfy.sh/" + TOPIC + "/json?since=" + since).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(0); // keep stream open
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    JSONObject o = new JSONObject(line);
                    if ("message".equals(o.optString("event"))) {
                        long t = o.optLong("time", System.currentTimeMillis() / 1000);
                        sp.edit().putLong("since", t + 1).apply();
                        show(o.optString("title", "Statement Update"), o.optString("message", ""));
                    }
                }
            } catch (Exception e) {
                // fall through and reconnect
            } finally {
                if (c != null) c.disconnect();
            }
            try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
        }
    }

    private void show(String title, String msg) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(msg)
                .setStyle(new Notification.BigTextStyle().bigText(msg))
                .setContentIntent(pi)
                .setAutoCancel(true).build();
        getSystemService(NotificationManager.class).notify((int) System.currentTimeMillis(), n);
    }
}
