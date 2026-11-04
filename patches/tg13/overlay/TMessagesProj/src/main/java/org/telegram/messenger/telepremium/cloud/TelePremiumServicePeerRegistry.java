package org.telegram.messenger.telepremium.cloud;

import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

public final class TelePremiumServicePeerRegistry {
    public static final int SERVICE_TYPE_CLOUD = 1;

    private TelePremiumServicePeerRegistry() {}

    public static boolean isServicePeer(int account, long dialogId) {
        long service = TelePremiumCloudPeerManager.getInstance(account).getDialogId();
        return service != 0 && service == dialogId;
    }

    public static boolean isServicePeer(long dialogId) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (isServicePeer(a, dialogId)) return true;
        }
        return false;
    }

    public static long getCloudDialogId(int account) {
        return TelePremiumCloudPeerManager.getInstance(account).getDialogId();
    }

    public static TLRPC.InputPeer getCloudInputPeer(int account) {
        return TelePremiumCloudPeerManager.getInstance(account).getInputPeer();
    }

    public static ArrayList<TLRPC.Dialog> filterDialogs(int account, List<TLRPC.Dialog> source) {
        ArrayList<TLRPC.Dialog> out = new ArrayList<>();
        if (source == null) return out;
        long service = getCloudDialogId(account);
        if (service == 0) {
            out.addAll(source);
            return out;
        }
        for (TLRPC.Dialog dialog : source) {
            if (dialog == null || dialog.id != service) out.add(dialog);
        }
        return out;
    }
}
