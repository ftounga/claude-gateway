package fr.claudegateway.jarvis.companion;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

import fr.claudegateway.jarvis.companion.core.JarvisLink;

/**
 * Le pont entre le mot d'éveil et l'écran Jarvis (D1). Il s'affiche par-dessus l'écran verrouillé,
 * allume l'écran, demande le déverrouillage (empreinte, visage ou code — Android ne permet pas
 * d'ouvrir le navigateur par-dessus le verrou), puis ouvre {@code <gateway>/jarvis?source=compagnon}.
 * La PWA installée capte le lien ; sinon Chrome l'ouvre. Aucun écran propre : il se ferme aussitôt.
 */
public final class WakeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard != null && keyguard.isKeyguardLocked()) {
            keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    openJarvis();
                }

                @Override
                public void onDismissCancelled() {
                    finish();
                }

                @Override
                public void onDismissError() {
                    finish();
                }
            });
        } else {
            openJarvis();
        }
    }

    private void openJarvis() {
        String url = JarvisLink.jarvisUrl(new Prefs(this).gateway());
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Aucun navigateur pour ouvrir Jarvis", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
