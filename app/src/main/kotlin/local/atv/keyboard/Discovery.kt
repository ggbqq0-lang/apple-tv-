package local.atv.keyboard

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import local.atv.protocol.Endpoint
import java.util.ArrayDeque

/** Android NSD manages mDNS sockets; no location/Bluetooth permission needed at target 36. */
@Suppress("DEPRECATION")
class Discovery(context: Context,private val onFound: (Endpoint)->Unit,private val onError: (String)->Unit) {
    private val nsd=context.getSystemService(NsdManager::class.java)
    private val queue=ArrayDeque<NsdServiceInfo>()
    private var resolving=false
    private var listener: NsdManager.DiscoveryListener?=null
    @Synchronized fun start(){
        if(listener!=null)return
        val l=object:NsdManager.DiscoveryListener{
            override fun onDiscoveryStarted(type: String){}
            override fun onDiscoveryStopped(type: String){}
            override fun onStartDiscoveryFailed(type: String,code: Int){stop();onError("局域网搜索未启动（$code）。可手动填写 IP 和 Companion 端口。")}
            override fun onStopDiscoveryFailed(type: String,code: Int){}
            override fun onServiceLost(service: NsdServiceInfo){}
            override fun onServiceFound(service: NsdServiceInfo){enqueue(service)}
        };listener=l
        try{nsd.discoverServices("_companion-link._tcp.",NsdManager.PROTOCOL_DNS_SD,l)}
        catch(_:Exception){listener=null;onError("无法搜索局域网，请检查网络权限和 Wi-Fi。")}
    }
    @Synchronized private fun enqueue(info: NsdServiceInfo){if(listener==null)return;queue.add(info);next()}
    @Synchronized private fun next(){
        if(resolving || queue.isEmpty() || listener==null)return
        resolving=true;val info=queue.removeFirst()
        nsd.resolveService(info,object:NsdManager.ResolveListener{
            override fun onResolveFailed(service: NsdServiceInfo,code: Int){synchronized(this@Discovery){resolving=false;next()}}
            override fun onServiceResolved(service: NsdServiceInfo){
                val model=service.attributes.entries.firstOrNull{it.key.equals("rpmd",true)}?.value?.toString(Charsets.UTF_8)
                val host=service.host?.hostAddress
                synchronized(this@Discovery){
                    if(listener!=null && host!=null && model?.startsWith("AppleTV")==true)onFound(Endpoint(service.serviceName,host,service.port))
                    resolving=false;next()
                }
            }
        })
    }
    @Synchronized fun stop(){val l=listener;listener=null;queue.clear();if(l!=null)try{nsd.stopServiceDiscovery(l)}catch(_:Exception){}}
}
