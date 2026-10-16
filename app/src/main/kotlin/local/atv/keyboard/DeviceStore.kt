package local.atv.keyboard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import local.atv.protocol.Credentials
import local.atv.protocol.Endpoint
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedDevice(val id: String, val endpoint: Endpoint)

/** Only credentials are persisted; text, URLs and clipboard contents never are. */
class DeviceStore(context: Context) {
    private val prefs=context.getSharedPreferences("paired_devices",Context.MODE_PRIVATE)
    private val alias="atv_pairing_aes_v1"
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    private fun records()=JSONArray(prefs.getString("records","[]"))
    fun devices(): List<SavedDevice> = records().let{list -> (0 until list.length()).map {i -> list.getJSONObject(i).let {
        SavedDevice(it.getString("id"),Endpoint(it.getString("name"),it.getString("host"),it.getInt("port"))) }}}
    fun save(endpoint: Endpoint,credentials: Credentials): SavedDevice {
        val id=UUID.randomUUID().toString()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(id.toByteArray())
        val plain=credentials.encode();val encrypted=try{cipher.doFinal(plain)}finally{plain.fill(0)}
        val record=JSONObject().put("id",id).put("name",endpoint.name).put("host",endpoint.host).put("port",endpoint.port)
            .put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).put("credentials",Base64.encodeToString(encrypted,Base64.NO_WRAP))
        val all=records();all.put(record)
        check(prefs.edit().putString("records",all.toString()).putString("last",id).commit())
        return SavedDevice(id,endpoint)
    }
    fun credentials(id: String): Credentials {
        val all=records();val record=(0 until all.length()).map {all.getJSONObject(it)}.first{it.getString("id")==id}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(record.getString("iv"),Base64.NO_WRAP)))
        cipher.updateAAD(id.toByteArray())
        val plain=cipher.doFinal(Base64.decode(record.getString("credentials"),Base64.NO_WRAP))
        return try{Credentials.decode(plain)}finally{plain.fill(0)}
    }
    fun last() = devices().firstOrNull{it.id==prefs.getString("last",null)}
    fun select(id: String){prefs.edit().putString("last",id).apply()}
    fun forget(id: String){val all=records();val filtered=JSONArray();repeat(all.length()){val item=all.getJSONObject(it);if(item.getString("id")!=id)filtered.put(item)}
        prefs.edit().putString("records",filtered.toString()).apply()}
    fun updateEndpoint(id: String,endpoint: Endpoint){val all=records();repeat(all.length()){val item=all.getJSONObject(it);if(item.getString("id")==id)item.put("host",endpoint.host).put("port",endpoint.port)}
        prefs.edit().putString("records",all.toString()).apply()}
}
