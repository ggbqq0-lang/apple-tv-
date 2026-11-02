package local.atv.keyboard

import android.content.Context

enum class ControlTarget { APPLE_TV, XIAOMI_TV }

/** Local connection preferences only; never stores input or pairing keys. */
class RemoteSettings(context:Context) {
    private val prefs=context.getSharedPreferences("remote_settings",Context.MODE_PRIVATE)
    fun target()=if(prefs.getString("target",null)=="xiaomi")ControlTarget.XIAOMI_TV else ControlTarget.APPLE_TV
    fun host()=prefs.getString("xiaomi_host","").orEmpty()
    fun target(value:ControlTarget){prefs.edit().putString("target",if(value==ControlTarget.XIAOMI_TV)"xiaomi" else "apple").apply()}
    fun host(value:String){
        val edit=prefs.edit().putString("xiaomi_host",value)
        if(value!=host())edit.remove("apple_mute_ids")
        edit.apply()
    }
    fun muteLinked(appleId:String)=appleId in prefs.getStringSet("apple_mute_ids",emptySet()).orEmpty()
    fun linkMute(appleId:String,enabled:Boolean){
        val ids=prefs.getStringSet("apple_mute_ids",emptySet()).orEmpty().toMutableSet()
        if(enabled)ids.add(appleId) else ids.remove(appleId)
        prefs.edit().putStringSet("apple_mute_ids",ids).apply()
    }
    fun forgetXiaomi(){prefs.edit().remove("xiaomi_host").remove("apple_mute_ids").apply()}
}
