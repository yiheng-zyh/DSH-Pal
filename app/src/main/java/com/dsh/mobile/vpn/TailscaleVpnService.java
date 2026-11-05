// Adapted from DSHgo (MIT), Copyright (c) 2026 Fa2517.
// See third_party/DSHgo-MIT-LICENSE.
package com.dsh.mobile.vpn;
import com.dsh.mobile.MainActivity;
import com.dsh.mobile.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;

import java.util.UUID;

/**
 * The in-app VPN service. This is the deep-integration piece: the Tailscale
 * engine runs in our process and establishes the tunnel through this service,
 * so there is never a second VPN app or a foreign UI.
 */
public class TailscaleVpnService extends VpnService implements libtailscale.IPNService {

    public static final String ACTION_START = "com.dsh.mobile.vpn.START_TUNNEL";
    public static final String ACTION_STOP = "com.dsh.mobile.vpn.STOP_TUNNEL";
    public static final String ACTION_START_FOREGROUND_ONLY = "com.dsh.mobile.vpn.START_FOREGROUND_ONLY";
    private static final String CHANNEL = "dsh_tunnel";

    private final String randomID = UUID.randomUUID().toString();
    private static final java.util.concurrent.ExecutorService commands =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean stopped;

    private android.net.ConnectivityManager.NetworkCallback underlyingCb;
    private final java.util.LinkedHashSet<android.net.Network> physicalNetworks =
        new java.util.LinkedHashSet<>();

    private void updateUnderlyingNetworks() {
        // null delegates selection to Android when no physical network is known.
        // An empty array explicitly tells Android the VPN has NO connectivity.
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        android.net.Network preferred = TunnelStore.physicalNetwork(cm);
        setUnderlyingNetworks(preferred == null ? null : new android.net.Network[]{preferred});
        android.net.LinkProperties lp = preferred == null ? null : cm.getLinkProperties(preferred);
        libtailscale.Libtailscale.onDNSConfigChanged(lp == null ? "" : lp.getInterfaceName());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        VpnController.INSTANCE.onTailscaleServiceStarted(randomID);
        try { TailscaleEngine.get(this).ensureEngine(); watchUnderlyingNetwork(); }
        catch (Throwable error) { com.dsh.mobile.core.RunLog.INSTANCE.error("VPN", "Tailscale engine startup failed " + error.getClass().getSimpleName()); stopSelf(); }
    }

    /** Keep the tunnel bound to the active network (WiFi <-> cellular handover). */
    private void watchUnderlyingNetwork() {
        try {
            android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            underlyingCb = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network n) {
                    physicalNetworks.add(n);
                    updateUnderlyingNetworks();
                }

                @Override public void onLost(android.net.Network n) {
                    physicalNetworks.remove(n);
                    updateUnderlyingNetworks();
                }

                @Override public void onCapabilitiesChanged(android.net.Network n, android.net.NetworkCapabilities c) {
                    updateUnderlyingNetworks();
                }

                @Override public void onLinkPropertiesChanged(android.net.Network n, android.net.LinkProperties p) {
                    updateUnderlyingNetworks();
                }
            };
            cm.registerNetworkCallback(
                new android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), underlyingCb);
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (!VpnController.INSTANCE.isSessionActive() || action == null) {
            stopped = true;
            close();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            VpnController.INSTANCE.onVpnRevoked();
            stopped = true;
            commands.execute(() -> TailscaleEngine.get(this).setWantRunning(false));
            close();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START_FOREGROUND_ONLY.equals(action)) {
            // Keep the engine alive during interactive login without building a
            // tunnel: the control channel stays paused unless a service runs.
            TailscaleEngine.get(this).ensureEngine();
            showNotification();
            return START_NOT_STICKY;
        }
        showNotification();
        stopped = false;
        commands.execute(() -> {
            if (stopped || !VpnController.INSTANCE.isSessionActive()) return;
            TailscaleEngine.get(this).setWantRunning(true);
            if (!stopped && VpnController.INSTANCE.isSessionActive()) libtailscale.Libtailscale.requestVPN(this);
        });
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        stopped = true;
        VpnController.INSTANCE.onTaskRemoved();
        commands.execute(() -> {
            if (!VpnController.INSTANCE.isSessionActive()) TailscaleEngine.get(this).setWantRunning(false);
        });
        close();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    // -------------------------------------------- libtailscale.IPNService

    @Override
    public String id() {
        return randomID;
    }

    @Override
    public boolean protect(int fd) {
        return super.protect(fd);
    }

    @Override
    public libtailscale.VPNServiceBuilder newBuilder() {
        com.dsh.mobile.core.RunLog.INSTANCE.info("VPN", "Tailscale interface requested");
        Builder builder = new Builder()
            .setConfigureIntent(PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
            .addDnsServer("100.100.100.100")
            .addRoute("100.100.100.100", 32);
        try {
            // Fail closed if the package cannot be selected: never establish
            // an unrestricted VPN that changes other applications' routing.
            builder.addAllowedApplication(getPackageName());
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("DSH Mobile VPN application not found", e);
        }
        return new TsBuilder(builder);
    }

    @Override
    public void close() {
        try {
            libtailscale.Libtailscale.serviceDisconnect(this);
        } catch (Exception ignored) {}
    }

    @Override
    public void disconnectVPN() {
        stopSelf();
    }

    @Override
    public void updateVpnStatus(boolean up) {
        if (!stopped) VpnController.INSTANCE.onTailscaleVpnStatus(up);
    }

    // ------------------------------------------------ VpnService events

    @Override
    public void onDestroy() {
        stopped = true;
        if (underlyingCb != null) {
            try {
                ((android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE))
                    .unregisterNetworkCallback(underlyingCb);
            } catch (Exception ignored) {}
            underlyingCb = null;
        }
        close();
        VpnController.INSTANCE.onTailscaleServiceStopped(randomID);
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        VpnController.INSTANCE.onVpnRevoked();
        stopped = true;
        commands.execute(() -> TailscaleEngine.get(this).setWantRunning(false));
        close();
        stopSelf();
        super.onRevoke();
    }

    // -------------------------------------------------------- plumbing

    private void showNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "DSH 隧道",
                NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("DSH Mobile 专用隧道运行中")
            .setContentText("仅 DSH Mobile 使用隧道，连接状态请查看主界面")
            .setContentIntent(pi)
            .setOngoing(true);
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(4201, b.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(4201, b.build());
        } catch (Exception ignored) {}
    }

    /** Wraps android's VpnService.Builder for the Go engine. */
    final class TsBuilder implements libtailscale.VPNServiceBuilder {
        private final VpnService.Builder b;

        TsBuilder(VpnService.Builder b) {
            this.b = b;
        }

        @Override public void setMTU(int mtu) { b.setMtu(mtu); }
        @Override public void addDNSServer(String s) { b.addDnsServer(s); }
        @Override public void addSearchDomain(String s) { b.addSearchDomain(s); }
        @Override public void addRoute(String addr, int p) { b.addRoute(addr, p); }
        @Override public void excludeRoute(String addr, int p) {
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    java.net.InetAddress in = java.net.InetAddress.getByName(addr);
                    b.excludeRoute(new android.net.IpPrefix(in, p));
                } catch (Exception ignored) {}
            }
        }
        @Override public void addAddress(String addr, int p) { b.addAddress(addr, p); }
        @Override public libtailscale.ParcelFileDescriptor establish() throws Exception {
            if (stopped || !VpnController.INSTANCE.isSessionActive()) return null;
            final ParcelFileDescriptor fd = b.establish();
            if (fd == null) return null;
            return new libtailscale.ParcelFileDescriptor() {
                @Override public int detach() throws Exception {
                    return fd.detachFd();
                }
            };
        }
    }
}
