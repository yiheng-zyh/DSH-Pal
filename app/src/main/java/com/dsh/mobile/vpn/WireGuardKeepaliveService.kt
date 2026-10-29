package com.dsh.mobile.vpn

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.Build
import com.dsh.mobile.MainActivity
import com.dsh.mobile.R

/** A user-enabled VPN has a separate mandatory foreground notification. */
class WireGuardKeepaliveService: Service() {
    private val instanceId=java.util.UUID.randomUUID().toString()
    override fun onCreate() {
        super.onCreate()
        VpnController.onWireGuardKeepaliveStarted(instanceId)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("dsh_wireguard","WireGuard 通道",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,4202,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n=Notification.Builder(this,"dsh_wireguard").setSmallIcon(R.drawable.ic_notification).setContentTitle("DSH · WireGuard 通道已启用")
            .setContentText("仅本 App 使用；服务器可达性请查看连接状态").setContentIntent(open).setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=34) startForeground(4202,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(4202,n)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(!VpnController.isSessionActive())stopSelf()
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent:Intent?) {
        VpnController.onTaskRemoved()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy() { VpnController.onWireGuardKeepaliveStopped(instanceId);super.onDestroy() }
}
