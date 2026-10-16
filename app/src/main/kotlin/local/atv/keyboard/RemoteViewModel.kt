package local.atv.keyboard

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.atv.protocol.*
import java.net.SocketTimeoutException

data class ScreenState(
    val found: List<Endpoint> = emptyList(),val saved: List<SavedDevice> = emptyList(),
    val device: String="尚未连接",val connected: Boolean=false,val focused: Boolean=false,
    val busy: Boolean=false,val pairing: Boolean=false,val text: String="",
    val append: Boolean=false,val needsReview: Boolean=false,
    val message: String="手机与 Apple TV 请接入同一局域网。",val scanning: Boolean=false,
    val touching: Boolean=false,val live: Boolean=false,val livePending:Boolean=false,
    val volumeAvailable:Boolean=false,val muteAvailable:Boolean=false,val muted:Boolean=false,
)

class RemoteViewModel(application: Application): AndroidViewModel(application) {
    private val store=DeviceStore(application)
    private val mutable=MutableStateFlow(ScreenState(saved=store.devices()))
    val state=mutable.asStateFlow()
    private val lock=Mutex()
    @Volatile private var connection: Companion?=null
    @Volatile private var pairing: Pairing?=null
    private var pairingTarget: Endpoint?=null
    private var selected: SavedDevice?=store.last()
    @Volatile private var foreground=false
    private var reconnect=true
    private var poll: Job?=null
    private var expiry: Job?=null
    private var retryAt=0L
    private var failures=0
    private val livePolicy=LiveTextPolicy()
    private var liveJob: Job?=null
    private var liveWorkerGeneration=-1L
    @Volatile private var liveGeneration=0L
    @Volatile private var editRevision=0L
    @Volatile private var lastLiveRevision=-1L
    @Volatile private var composing=false
    private val networkMute=NetworkMute()
    @Volatile private var keyboardVisible=false
    private var bindingRequest=0L
    private var resetDraftOnBind=false
    private var padHold:RemoteHold?=null
    private var keyboardNavigation:KeyboardNavigation?=null
    private var padNavigation:KeyboardNavigation?=null
    private data class Control(val client:Companion,val action:(Companion)->Unit)
    private val inputs=RemoteInputBuffer<Control>()
    private val inputWake=kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var nextGesture=0L
    private var activeGesture:Long?=null
    private val discovery=Discovery(application,{endpoint -> viewModelScope.launch {
        mutable.update {s->s.copy(found=(s.found.filterNot{it.name==endpoint.name}+endpoint).sortedBy{it.name})}
        selected?.takeIf{it.endpoint.name==endpoint.name}?.let {old ->
            if(old.endpoint!=endpoint){store.updateEndpoint(old.id,endpoint);selected=old.copy(endpoint=endpoint);mutable.update{it.copy(saved=store.devices())}}
        }
    }},{error ->mutable.update{it.copy(message=error)}})

    init {
        viewModelScope.launch {
            var lastMovement=0L
            for(signal in inputWake){
                while(true){
                    val next=inputs.poll(SystemClock.elapsedRealtime()) ?: break
                    if(next.gesture!=null)delay((70-(SystemClock.elapsedRealtime()-lastMovement)).coerceAtLeast(0))
                    operation(quiet=true,waitForLock=true){
                        if(foreground && connection===next.value.client && inputs.valid(next,SystemClock.elapsedRealtime())){
                            next.value.action(next.value.client)
                            if(next.gesture!=null)lastMovement=SystemClock.elapsedRealtime()
                        }
                    }
                }
            }
        }
    }

    fun start(){
        foreground=true;discovery.start();mutable.update{it.copy(scanning=true)}
        if(keyboardVisible)enableLive(resetDraft=true)
        if(poll?.isActive==true)return
        poll=viewModelScope.launch {
            while(isActive && foreground){
                if(!mutable.value.busy && !mutable.value.pairing){
                    if(connection!=null)operation(quiet=true){
                        val client=connection!!;val token=bindingRequest;val current=client.refreshText()
                        mutable.update{it.copy(focused=current.focused)}
                        refreshAudio(client)
                        completeLiveBinding(client,current,token)
                        if(mutable.value.live)livePolicy.check(current.sessionId)
                    }
                    else if(reconnect && selected!=null && System.currentTimeMillis()>=retryAt)connect(selected!!)
                };delay(4000)
            }
        }
    }
    fun stop(){
        cancelKeyboardNavigation()
        disableLive();padHold?.release();padHold=null;inputs.clear();activeGesture=null;mutable.update{it.copy(touching=false)}
        foreground=false;poll?.cancel();poll=null;expiry?.cancel();discovery.stop()
        connection?.close();connection=null;pairing?.close();pairing=null
        networkMute.reset()
        mutable.update{it.copy(connected=false,focused=false,pairing=false,scanning=false,volumeAvailable=false,muteAvailable=false,muted=false,
            message="应用已离开前台，连接已断开；返回后自动重连。")}
    }
    fun edit(text: String,isComposing: Boolean=false){
        val changed=text!=mutable.value.text || composing!=isComposing
        composing=isComposing;mutable.update{it.copy(text=text)}
        if(changed){editRevision++;if(mutable.value.live && !composing)startLiveWorker()}
    }
    fun append(value: Boolean){mutable.update{it.copy(append=value)}}
    fun clearLocal(){cancelKeyboardNavigation();disableLive();composing=false;mutable.update{it.copy(text="")}}
    fun enterKeyboard(){keyboardVisible=true;enableLive(resetDraft=true)}
    fun leaveKeyboard(){keyboardVisible=false;cancelKeyboardNavigation();disableLive()}
    fun enableLive(resetDraft:Boolean=false){
        if(!keyboardVisible)return
        cancelKeyboardNavigation()
        liveGeneration++;bindingRequest=livePolicy.requestBinding();resetDraftOnBind=resetDraft
        val token=bindingRequest
        mutable.update{it.copy(live=false,livePending=true,append=false,message="实时同步默认开启，正在等待电视输入框。进入页面不会自动发送文字。")}
        val client=connection ?: return
        viewModelScope.launch {operation(quiet=true,waitForLock=true){
            if(foreground && connection===client){completeLiveBinding(client,client.refreshText(),token)}
        }}
    }
    private suspend fun completeLiveBinding(client:Companion,current:TextState,token:Long){withContext(Dispatchers.Main.immediate){
        if(!foreground || !keyboardVisible || connection!==client || token!=bindingRequest || !mutable.value.livePending)return@withContext
        mutable.update{it.copy(focused=current.focused)}
        if(!livePolicy.bind(token,current.sessionId))return@withContext
        val draft=if(resetDraftOnBind)LiveTextPolicy.draftForEntry(mutable.value.text,current.context) else mutable.value.text
        composing=false;editRevision++;lastLiveRevision=editRevision
        mutable.update{it.copy(live=true,livePending=false,text=draft,append=false,needsReview=false,message="实时同步已开启；从下一次编辑开始替换电视内容，中文选字确认后同步。")}
    }}
    fun disableLive(){liveGeneration++;livePolicy.stop();mutable.update{it.copy(live=false,livePending=false)}}
    private fun startLiveWorker(){
        if(liveJob?.isActive==true && liveWorkerGeneration==liveGeneration)return
        val generation=liveGeneration
        liveWorkerGeneration=generation
        liveJob=viewModelScope.launch {
            delay(180)
            while(isActive && foreground && mutable.value.live && generation==liveGeneration){
                var sentRevision=editRevision
                operation {
                    if(!foreground || !mutable.value.live || generation!=liveGeneration || composing)return@operation
                    sentRevision=editRevision;val snapshot=mutable.value.text
                    val result=(connection ?: throw ProtocolFailure("CONNECTION_CLOSED")).sendText(snapshot,false,true,livePolicy)
                    if(generation!=liveGeneration || !keyboardVisible)return@operation
                    if(result.contextMatches==true){lastLiveRevision=sentRevision;mutable.update{it.copy(message="实时同步 · 协议回读相符（${result.characters} 字符）")}}
                    else {disableLive();mutable.update{it.copy(needsReview=true,message="实时同步已暂停：无法核对回读或内容不同。请查看电视，确认后可整段发送。")}}
                }
                if(composing || editRevision==sentRevision)break
                delay(120)
            }
        }
    }
    private fun cancelKeyboardNavigation(){keyboardNavigation?.cancel();keyboardNavigation=null}
    private fun prepareKeyboardNavigation():KeyboardNavigation? {
        if(composing){mutable.update{it.copy(message="请先确认手机输入法的选字，再操作电视方向或确定。")};return null}
        if(mutable.value.needsReview){mutable.update{it.copy(message="请先查看电视输入结果，再重新开启同步或返回键盘页。")};return null}
        keyboardNavigation?.let{return it}
        val target=if(mutable.value.live)livePolicy.snapshot() else null
        val pending=mutable.value.text.takeIf{target!=null && editRevision>lastLiveRevision}
        return KeyboardNavigation(pending,target).also{
            keyboardNavigation=it;disableLive()
            mutable.update{state->state.copy(message="正在操作电视；文字同步已暂停，可开启开关继续输入。")}
        }
    }
    private fun keyboardControl(client:Companion,navigation:KeyboardNavigation,action:()->Unit){
        navigation.run(current={foreground && keyboardVisible && connection===client},
            send={text,policy->client.sendText(text,false,true,policy)},action=action)
    }
    fun keyboardRemote(command:Int,hold:Boolean=false){
        val s=mutable.value
        if(!s.connected || s.touching || s.pairing)return
        val navigation=prepareKeyboardNavigation() ?: return
        control(allowBusy=true){client->keyboardControl(client,navigation){client.press(command,hold)}}
    }
    fun beginPadGesture(fromKeyboard:Boolean=false):Long? {
        val s=mutable.value
        if(!s.connected || (!fromKeyboard && s.busy) || s.touching || s.pairing)return null
        padNavigation=if(fromKeyboard)prepareKeyboardNavigation() ?: return null else null
        if(!fromKeyboard)disableLive()
        val id=++nextGesture;activeGesture=id;inputs.beginGesture(id)
        mutable.update{it.copy(touching=true)};return id
    }
    fun movePadGesture(id:Long,direction:Int):Boolean {
        if(activeGesture!=id || direction !in 1..4)return false
        val client=connection ?: return false
        val accepted=inputs.offerMovement(id,Control(client){it.press(direction)},SystemClock.elapsedRealtime())
        if(accepted)inputWake.trySend(Unit)
        return accepted
    }
    fun startPadHold(id:Long):Boolean {
        if(activeGesture!=id || padHold!=null)return false
        val client=connection ?: return false
        val held=RemoteHold();padHold=held
        val navigation=padNavigation
        val accepted=inputs.offerMovement(id,Control(client){if(navigation!=null)keyboardControl(it,navigation){it.holdSelect(held)} else it.holdSelect(held)},SystemClock.elapsedRealtime(),maxAgeMillis=700)
        if(accepted)inputWake.trySend(Unit) else {held.release();padHold=null}
        return accepted
    }
    fun cancelPadHold(id:Long){if(activeGesture==id)padHold?.release()}
    fun endPadGesture(id:Long,tapCommand:Int?=null):Boolean {
        if(activeGesture!=id)return false
        val navigation=padNavigation;padNavigation=null
        padHold?.release();padHold=null
        inputs.endGesture(id);activeGesture=null;mutable.update{it.copy(touching=false)}
        return if(tapCommand!=null)control(allowBusy=navigation!=null){if(navigation!=null)keyboardControl(it,navigation){it.press(tapCommand)} else it.press(tapCommand)} else true
    }
    fun acknowledge(){cancelKeyboardNavigation();mutable.update{it.copy(needsReview=false,message="已确认查看电视结果。请核对目标输入框后再发送。")}}
    fun scan(){discovery.stop();mutable.update{it.copy(found=emptyList(),message="正在搜索 Apple TV；若无结果可手动连接。")};discovery.start()}
    fun manual(host: String,port: String){
        val number=port.toIntOrNull()
        if(number==null || number !in 1..65535){mutable.update{it.copy(message="请输入 1–65535 范围的 Companion 端口。")};return}
        beginPair(Endpoint("手动 Apple TV",host.trim(),number))
    }
    fun beginPair(endpoint: Endpoint){
        cancelKeyboardNavigation()
        disableLive()
        if(mutable.value.busy)return
        viewModelScope.launch {operation {
            reconnect=false;connection?.close();connection=null;pairing?.close()
            mutable.update{it.copy(connected=false,focused=false,device=endpoint.name)}
            val candidate=Pairing(endpoint);pairing=candidate;pairingTarget=endpoint
            try{candidate.begin()}catch(e:Exception){candidate.close();pairing=null;throw e}
            mutable.update{it.copy(pairing=true,message="请输入 Apple TV 屏幕显示的四位验证码。")}
            expiry?.cancel();expiry=viewModelScope.launch {delay(120000);cancelPair("配对已超时，请重新选择设备并输入新验证码。")}
        }}
    }
    fun cancelPair(message: String="配对已取消。"){
        pairing?.close();pairing=null;pairingTarget=null;expiry?.cancel()
        mutable.update{it.copy(pairing=false,message=message)}
    }
    fun finishPair(pin: String){
        if(mutable.value.busy)return
        viewModelScope.launch {operation {
            val p=pairing ?: throw ProtocolFailure("PAIRING_NOT_STARTED")
            val endpoint=pairingTarget ?: throw ProtocolFailure("PAIRING_NOT_STARTED")
            try {
                val credentials=p.finish(pin)
                val saved=store.save(endpoint,credentials);selected=saved;reconnect=true
                mutable.update{it.copy(saved=store.devices(),pairing=false,message="配对已完成，正在验证加密连接。")}
            }finally{p.close();pairing=null;expiry?.cancel();mutable.update{it.copy(pairing=false)}}
        };if(selected!=null && reconnect)connect(selected!!)}
    }
    fun select(device: SavedDevice){if(mutable.value.busy)return;selected=device;store.select(device.id);reconnect=true;viewModelScope.launch{connect(device)}}
    fun reconnect(){reconnect=true;retryAt=0;selected?.let{viewModelScope.launch{connect(it)}} ?: scan()}
    fun disconnect(){cancelKeyboardNavigation();disableLive();padHold?.release();padHold=null;reconnect=false;connection?.close();connection=null;mutable.update{it.copy(connected=false,focused=false,message="已手动断开。")}}
    fun forget(device: SavedDevice){
        if(selected?.id==device.id){disconnect();selected=null};store.forget(device.id)
        mutable.update{it.copy(saved=store.devices(),message="已删除本机保存的配对；电视端仍可在遥控器设置中移除该记录。")}
    }
    private suspend fun connect(device: SavedDevice){operation {
        cancelKeyboardNavigation()
        padHold?.release();padHold=null;inputs.clear();activeGesture=null;mutable.update{it.copy(touching=false)}
        connection?.close();connection=null
        networkMute.reset()
        mutable.update{it.copy(connected=false,focused=false,volumeAvailable=false,muteAvailable=false,muted=false,device=device.endpoint.name,message="正在认证并建立加密连接…")}
        val candidate=Companion(device.endpoint,store.credentials(device.id));connection=candidate
        try{candidate.connect();failures=0;retryAt=0
            mutable.update{it.copy(connected=true,focused=candidate.focused,volumeAvailable=candidate.supportsVolumeSteps,message="已认证连接。请先在电视选中输入框。")}
            refreshAudio(candidate)
            if(mutable.value.livePending)completeLiveBinding(candidate,candidate.refreshText(),bindingRequest)
        }catch(e:Exception){candidate.close();connection=null;throw e}
    }}
    fun send(confirmReplace: Boolean=false){
        val snapshot=mutable.value
        if(snapshot.busy || snapshot.needsReview || snapshot.touching)return
        cancelKeyboardNavigation()
        disableLive()
        if(!snapshot.connected){mutable.update{it.copy(message="尚未连接 Apple TV，请先连接或配对。")};return}
        viewModelScope.launch {operation {
            val result=(connection ?: throw ProtocolFailure("CONNECTION_CLOSED")).sendText(snapshot.text,snapshot.append,confirmReplace)
            val text=when(result.contextMatches){true->"协议回读相符（${result.characters} 字符）。请在电视核对实际内容。";false->"已发出文字，但协议回读不同。目标应用可能整理了内容，请查看电视；不要直接重复追加。";null->"文字事件已发出，未能确认回读。请检查电视实际内容，避免重复发送。"}
            mutable.update{it.copy(message=text,needsReview=result.contextMatches!=true)}
        }}
    }
    private fun control(allowBusy:Boolean=false,action:(Companion)->Unit):Boolean {
        val s=mutable.value
        if((s.busy && !allowBusy) || s.touching || !s.connected)return false
        val client=connection ?: return false
        disableLive()
        val accepted=inputs.offerClick(Control(client,action),SystemClock.elapsedRealtime())
        if(accepted)inputWake.trySend(Unit)
        return accepted
    }
    fun remote(command:Int,hold:Boolean=false){control{it.press(command,hold)}}
    fun power(wake:Boolean){control{it.power(wake)}}
    private fun refreshAudio(client:Companion){
        val level=if(client.supportsVolume)try{client.volume()}catch(e:ProtocolFailure){if(e.code!="AUDIO_UNSUPPORTED")throw e;null} else null
        networkMute.observe(level)
        mutable.update{it.copy(volumeAvailable=client.supportsVolumeSteps,muteAvailable=networkMute.available(level),muted=networkMute.muted)}
    }
    fun volume(up:Boolean){control{client->
        client.press(if(up)8 else 9);networkMute.reset();refreshAudio(client)
    }}
    fun mute(){control{client->
        val muted=networkMute.toggle(client::volume,client::setVolumeVerified)
        mutable.update{it.copy(muted=muted,muteAvailable=true)}
    }}
    private suspend fun operation(quiet: Boolean=false,waitForLock:Boolean=false,block: suspend ()->Unit){
        if(quiet && !waitForLock){if(!lock.tryLock())return}else lock.lock()
        if(!quiet)mutable.update{it.copy(busy=true)}
        try{withContext(Dispatchers.IO){block()}}
        catch(e:CancellationException){throw e}
        catch(e:Exception){
            disableLive()
            val code=(e as? ProtocolFailure)?.code
            val connectedError=code !in setOf("NO_FOCUSED_TEXT_FIELD","LIVE_TARGET_CHANGED","REPLACE_CONFIRMATION_REQUIRED","LOCAL_TEXT_LIMIT_64K","INVALID_UNICODE","AUDIO_UNSUPPORTED","MUTE_UNAVAILABLE","VOLUME_KEYS_UNAVAILABLE","KEYBOARD_SYNC_REVIEW_REQUIRED")
            if(connectedError){padHold?.release();padHold=null;inputs.clear();connection?.close();connection=null;failures++;retryAt=System.currentTimeMillis()+minOf(30000L,1000L shl minOf(failures,5))}
            mutable.update{it.copy(connected=if(connectedError)false else it.connected,focused=if(connectedError)false else it.focused,
                volumeAvailable=if(connectedError || code=="VOLUME_KEYS_UNAVAILABLE")false else it.volumeAvailable,
                muteAvailable=if(connectedError || code in setOf("AUDIO_UNSUPPORTED","MUTE_UNAVAILABLE"))false else it.muteAvailable,
                muted=if(connectedError || code in setOf("AUDIO_UNSUPPORTED","MUTE_UNAVAILABLE"))false else it.muted,
                message=errorText(e),needsReview=it.needsReview || code in setOf("WRITE_OUTCOME_UNKNOWN","KEYBOARD_SYNC_REVIEW_REQUIRED"))}
            if(code in setOf("PIN_OR_CREDENTIALS_REJECTED","DEVICE_IDENTITY_CHANGED","DEVICE_SIGNATURE_INVALID","AUTHENTICATION_TAG_INVALID"))reconnect=false
        }finally{if(!quiet)mutable.update{it.copy(busy=false)};lock.unlock()}
    }
    override fun onCleared(){stop();super.onCleared()}
    private fun errorText(e:Exception): String = when((e as? ProtocolFailure)?.code){
        "KEYBOARD_SYNC_REVIEW_REQUIRED"->"文字回读未能确认，已停止电视操作且不会重发。请查看电视，再重新开启同步或返回键盘页。"
        "LIVE_TARGET_CHANGED"->"实时同步已停止：电视输入框已经改变。请确认新输入框后重新开启。"
        "NO_FOCUSED_TEXT_FIELD"->"已连接，但当前没有可输入的文本框。请在电视选中 Emby 地址或登录输入框；某些应用不支持远程输入。"
        "PIN_OR_CREDENTIALS_REJECTED"->"验证码或配对凭据被电视拒绝。请重新配对并输入屏幕上的新验证码。"
        "PAIRING_BACKOFF","PAIRING_MAX_TRIES"->"Apple TV 限制了配对尝试，请等待一段时间后手动重试。"
        "PAIRING_MAX_PEERS"->"Apple TV 的配对记录已满。请在电视的遥控器设置中移除不用的设备。"
        "PAIRING_BUSY"->"Apple TV 正在处理其他配对，请关闭其他遥控器的配对页面后再试。"
        "DEVICE_SIGNATURE_INVALID","DEVICE_IDENTITY_CHANGED","SRP_SERVER_PROOF_INVALID","AUTHENTICATION_TAG_INVALID"->"设备认证校验未通过，已停止连接。请确认目标 Apple TV 后重新配对。"
        "WRITE_OUTCOME_UNKNOWN"->"发送时连接中断，电视可能已收到部分内容。未自动重发，请先查看电视。"
        "FOUR_DIGIT_PIN_REQUIRED"->"请输入电视屏幕上的四位数字验证码。"
        "PIN_EXPIRED"->"验证码已超时，请重新发起配对。"
        "IP_ADDRESS_REQUIRED","LOCAL_NETWORK_ONLY"->"请输入局域网内 Apple TV 的 IP 地址，不支持云端地址。"
        "LOCAL_TEXT_LIMIT_64K"->"文字超过本版本 64 KiB 的本地保护限制；没有截断或发送。"
        "INVALID_UNICODE"->"文字含不完整 Unicode 字符，没有替换或发送。"
        "COMMAND_REJECTED"->"Apple TV 拒绝了这条指令。请确认设备已唤醒、当前应用支持该操作。"
        "AUDIO_UNSUPPORTED","MUTE_UNAVAILABLE"->"当前输出未提供可用的网络静音切换；音量加减是独立功能。请使用电视或音响遥控器静音。"
        "VOLUME_KEYS_UNAVAILABLE"->"当前输出不支持网络音量键，请使用电视或音响遥控器。"
        else->if(e is SocketTimeoutException)"Apple TV 响应超时。请检查 Wi-Fi、电视是否唤醒，以及 IP 和端口是否变化。" else "连接未完成或已断开。请检查同一局域网、设备隔离和 Companion 端口；自动重连不会重发文字。"
    }
}
