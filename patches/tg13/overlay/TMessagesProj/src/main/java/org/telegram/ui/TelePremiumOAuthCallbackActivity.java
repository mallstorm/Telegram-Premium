package org.telegram.ui;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.telepremium.ai.TPAiClient;
import org.telegram.messenger.telepremium.ai.TPOmni;

/**
 * Receives browser callbacks of account services that hand the key back through a custom
 * scheme (as in OmniChat): telepremium://requesty/oauth?code=… and
 * telepremium://zoo/auth-callback?token=zoo_ext_…
 */
public class TelePremiumOAuthCallbackActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri uri = getIntent() == null ? null : getIntent().getData();
        String providerType = null, secret = null;
        if (uri != null && "requesty".equals(uri.getHost())) {
            providerType = "requesty";
            secret = uri.getQueryParameter("code");
        } else if (uri != null && "zoo".equals(uri.getHost())) {
            providerType = "zoo-gateway";
            secret = uri.getQueryParameter("token");
            if (secret != null && !secret.startsWith("zoo_ext_")) secret = null;
        }
        if (providerType != null && !TextUtils.isEmpty(secret)) {
            final String type = providerType, key = secret;
            Toast.makeText(this, "TelePremium: подключаю аккаунт…", Toast.LENGTH_SHORT).show();
            TPAiClient.async(() -> TPOmni.connectAccount(type, key, ""), (result, error) ->
                    Toast.makeText(ApplicationLoader.applicationContext, error != null ? error : result, Toast.LENGTH_LONG).show());
        }
        finish();
    }
}
