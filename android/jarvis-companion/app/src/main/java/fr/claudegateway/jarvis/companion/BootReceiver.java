package fr.claudegateway.jarvis.companion;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Démarrage avec le téléphone (D1c). Android 14+ interdit de démarrer un service <b>micro</b> depuis
 * {@code BOOT_COMPLETED} : plutôt que de le tenter dans le dos du système, on publie une notification
 * « Toucher pour réactiver Jarvis » — un seul geste, qui ouvre l'application et relance l'écoute.
 * Rien n'est publié si le PO avait arrêté l'écoute.
 *
 * <p>SF-232-09 : une mise à jour de l'APK ({@code MY_PACKAGE_REPLACED}) arrête aussi le service ; même geste.</p>
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final int ID_BOOT = 3;

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction());
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction());
        if (!boot && !updated) {
            return;
        }
        if (!new Prefs(context).listening()) {
            return;
        }
        CompanionService.createChannels(context);
        Intent open = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_RESUME, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, 30, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(context, CompanionService.CHANNEL_LISTEN)
                .setSmallIcon(R.drawable.ic_jarvis)
                .setContentTitle("Réactiver Jarvis")
                .setContentText(boot
                        ? "Le téléphone a redémarré : toucher pour que Jarvis écoute à nouveau."
                        : "Jarvis a été mis à jour : toucher pour qu'il écoute à nouveau.")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        context.getSystemService(NotificationManager.class).notify(ID_BOOT, n);
    }
}
