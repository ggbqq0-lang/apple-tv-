package local.atv.keyboard

import android.content.ClipboardManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.atv.protocol.TouchGestureClassifier
import local.atv.protocol.SwipeDirectionTracker
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity: ComponentActivity() {
    private lateinit var model: RemoteViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        model=ViewModelProvider(this)[RemoteViewModel::class.java]
        setContent {MaterialTheme(colorScheme=if(isSystemInDarkTheme())darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()){RemoteApp(model)}
        }}
    }
    override fun onStart(){super.onStart();model.start()}
    override fun onStop(){model.stop();super.onStop()}
}

@Composable
private fun RemoteApp(model:RemoteViewModel){
    val state by model.state.collectAsStateWithLifecycle()
    var tab by remember{mutableIntStateOf(0)}
    var pin by remember(state.pairing){mutableStateOf("")}
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    fun switchTo(value:Int){if(tab==1 && value!=1){model.disableLive();focus.clearFocus();keyboard?.hide()};tab=value}
    BackHandler(enabled=tab!=0){switchTo(0)}
    Scaffold(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),bottomBar={
        NavigationBar(windowInsets=WindowInsets(0)){
            listOf("遥控","键盘","设备").forEachIndexed{i,label->
                NavigationBarItem(selected=tab==i,onClick={switchTo(i)},icon={Text(listOf("◉","⌨","⚙")[i],style=MaterialTheme.typography.titleLarge)},label={Text(label)})
            }
        }
    }){padding->Column(Modifier.fillMaxSize().padding(padding)){
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Text("●",color=if(state.connected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Column(Modifier.weight(1f)){
                Text(if(state.connected)state.device else if(state.busy)"正在连接…" else "Apple TV",style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(if(state.connected)"已连接" else "${state.message}",style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(!state.connected)TextButton(onClick={switchTo(2)}){Text("设备")}
        }
        when(tab){0->RemotePage(state,model){switchTo(1)};1->KeyboardPage(state,model);2->DevicePage(state,model)}
    }}
    if(state.pairing)AlertDialog(onDismissRequest={if(!state.busy)model.cancelPair()},title={Text("与 Apple TV 配对")},
        text={Column{Text("输入电视屏幕当前显示的四位验证码。");OutlinedTextField(value=pin,onValueChange={pin=it.filter{c->c in '0'..'9'}.take(4)},label={Text("四位验证码")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))}},
        confirmButton={TextButton(onClick={val value=pin;pin="";model.finishPair(value)},enabled=pin.length==4 && !state.busy){Text("完成配对")}},
        dismissButton={TextButton(onClick={pin="";model.cancelPair()},enabled=!state.busy){Text("取消")}})
}

@Composable
private fun RemotePage(state:ScreenState,model:RemoteViewModel,onKeyboard:()->Unit){
    val available=state.connected && !state.busy && !state.pairing
    val keys=available && !state.touching
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal=24.dp)){
        val keySize=if(maxHeight<560.dp)56.dp else 64.dp
        Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(Modifier.fillMaxWidth().height(40.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                Text("轻按唤醒 · 长按睡眠",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                RemoteKey(RemoteGlyph.Power,"电源",keys,Modifier.size(40.dp),showLabel=false,
                    longLabel="使 Apple TV 睡眠",onLongClick={model.power(false)}){model.power(true)}
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){
                val diameter=minOf(maxWidth,maxHeight,300.dp)
                TouchPad(available,model,Modifier.size(diameter))
            }
            Text("中央轻点/长按确定 · 外圈方向 · 滑动移动",style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
                Row(horizontalArrangement=Arrangement.spacedBy(44.dp)){
                    RemoteKey(RemoteGlyph.Back,"返回",keys,Modifier.size(keySize),longLabel="长按返回主屏幕",onLongClick={model.remote(5,true)}){model.remote(5)}
                    RemoteKey(RemoteGlyph.TV,"TV",keys,Modifier.size(keySize),longLabel="打开控制中心",onLongClick={model.remote(7,true)}){model.remote(7)}
                }
                Row(horizontalArrangement=Arrangement.spacedBy(44.dp)){
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                        RemoteKey(RemoteGlyph.PlayPause,"播放/暂停",keys,Modifier.size(keySize)){model.remote(14)}
                        RemoteKey(RemoteGlyph.Mute,if(state.muted)"取消静音" else "静音",keys && state.muteAvailable,Modifier.size(keySize)){model.mute()}
                    }
                    VolumeRocker(keys && state.volumeAvailable,Modifier.width(keySize).height(keySize*2+8.dp),model)
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                ConfirmButton(available,model,Modifier.weight(1f).height(44.dp))
                FilledTonalButton(onClick=onKeyboard,modifier=Modifier.weight(1f).height(44.dp)){Text("文字键盘")}
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun TouchPad(available:Boolean,model:RemoteViewModel,modifier:Modifier){
    var finger by remember{mutableStateOf<Offset?>(null)}
    var holding by remember{mutableStateOf(false)}
    val view=LocalView.current
    val ink=Color(0xFFE4E6EB)
    Surface(modifier,shape=CircleShape,color=Color(0xFF303238)){
        Box(Modifier.fillMaxSize().clip(CircleShape).semantics{
            contentDescription="圆形点按板，中央确定，外圈上下左右，滑动移动"
            onClick("确定"){if(available)model.remote(6);available}
            onLongClick("长按确定一秒"){if(available)model.remote(6,true);available}
            customActions=listOf("上" to 1,"下" to 2,"左" to 3,"右" to 4).map{(name,command)->
                CustomAccessibilityAction(name){if(available)model.remote(command);available}
            }
        }.pointerInput(available){
            if(available)detectRemotePad(model,true,onFinger={finger=it},onHolding={holding=it},moveFeedback={view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)},
                feedback={long->view.performHapticFeedback(if(long)HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY)})
        },contentAlignment=Alignment.Center){
            Canvas(Modifier.fillMaxSize()){
                val radius=size.minDimension/2
                val center=Offset(size.width/2,size.height/2)
                drawCircle(Color(0xFF222429),radius*.67f,center)
                drawCircle(ink.copy(alpha=.13f),radius*.67f,center,style=Stroke(1.dp.toPx()))
                val mark=ink.copy(alpha=if(available).7f else .25f)
                val tip=radius*.84f;val arm=radius*.045f;val stroke=2.dp.toPx()
                fun line(a:Offset,b:Offset){drawLine(mark,a,b,stroke,StrokeCap.Round)}
                line(center+Offset(-arm,-tip+arm),center+Offset(0f,-tip));line(center+Offset(0f,-tip),center+Offset(arm,-tip+arm))
                line(center+Offset(-arm,tip-arm),center+Offset(0f,tip));line(center+Offset(0f,tip),center+Offset(arm,tip-arm))
                line(center+Offset(-tip+arm,-arm),center+Offset(-tip,0f));line(center+Offset(-tip,0f),center+Offset(-tip+arm,arm))
                line(center+Offset(tip-arm,-arm),center+Offset(tip,0f));line(center+Offset(tip,0f),center+Offset(tip-arm,arm))
                finger?.let{drawCircle(ink.copy(alpha=.17f),radius=24.dp.toPx(),center=it)}
            }
            Text(if(holding)"松手结束" else if(available)"确定" else "未连接",color=ink.copy(alpha=if(available).8f else .4f),style=MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Shared pointer lifecycle for both Select surfaces; no delayed click after a hold. */
private suspend fun PointerInputScope.detectRemotePad(model:RemoteViewModel,circle:Boolean,fromKeyboard:Boolean=false,
    onFinger:(Offset?)->Unit={},onHolding:(Boolean)->Unit={},moveFeedback:()->Unit={},feedback:(Boolean)->Unit){
    awaitEachGesture {
        val down=awaitFirstDown(requireUnconsumed=false)
        val center=Offset(size.width/2f,size.height/2f)
        if(circle && (down.position-center).getDistance()>size.width/2f)return@awaitEachGesture
        fun x(p:Offset)=(p.x/size.width*1000).toInt().coerceIn(0,1000)
        fun y(p:Offset)=(p.y/size.height*1000).toInt().coerceIn(0,1000)
        val command=if(circle)TouchGestureClassifier.tapCommand(x(down.position),y(down.position)) else 6
        val central=command==6
        val slop=maxOf(viewConfiguration.touchSlop,(if(central)18.dp else 26.dp).toPx())
        val classifier=TouchGestureClassifier(down.position.x,down.position.y,slop)
        val swipe=SwipeDirectionTracker(down.position.x,down.position.y,slop*.75f)
        val gesture=model.beginPadGesture(fromKeyboard) ?: return@awaitEachGesture
        var released=false;var dragged=false;var holdAttempted=false;var lastMoveFeedback=0L
        onFinger(down.position);down.consume()
        try{
            while(true){
                val event=if(central && !dragged && !holdAttempted){
                    val remaining=(down.uptimeMillis+viewConfiguration.longPressTimeoutMillis-SystemClock.uptimeMillis()).coerceAtLeast(1)
                    withTimeoutOrNull(remaining){awaitPointerEvent()}
                }else awaitPointerEvent()
                if(event==null){
                    holdAttempted=true
                    if(model.startPadHold(gesture)){onHolding(true);feedback(true)}
                    continue
                }
                if(event.changes.any{it.id!=down.id && it.pressed})break
                val change=event.changes.firstOrNull{it.id==down.id} ?: break
                val point=change.position;onFinger(point);change.consume()
                if(!change.pressed){
                    val tap=!holdAttempted && classifier.finishIsTap(point.x,point.y)
                    val accepted=model.endPadGesture(gesture,command.takeIf{tap})
                    if(tap && accepted)feedback(false)
                    released=true;break
                }
                if(classifier.move(point.x,point.y)){
                    dragged=true
                    if(holdAttempted){model.cancelPadHold(gesture);onHolding(false)}
                    else if(circle)swipe.move(point.x,point.y)?.let{direction->
                        if(model.movePadGesture(gesture,direction)){
                            val now=SystemClock.uptimeMillis()
                            if(now-lastMoveFeedback>=70){moveFeedback();lastMoveFeedback=now}
                        }
                    }
                }
            }
        }finally{if(!released)model.endPadGesture(gesture);onFinger(null);onHolding(false)}
    }
}

@Composable
private fun ConfirmButton(available:Boolean,model:RemoteViewModel,modifier:Modifier,fromKeyboard:Boolean=false){
    val view=LocalView.current
    var holding by remember{mutableStateOf(false)}
    Surface(modifier.semantics{
        contentDescription="确定，支持按住连续长按"
        onClick("确定"){if(available){if(fromKeyboard)model.keyboardRemote(6) else model.remote(6)};available}
        onLongClick("长按确定一秒"){if(available){if(fromKeyboard)model.keyboardRemote(6,true) else model.remote(6,true)};available}
    }.pointerInput(available){
        if(available)detectRemotePad(model,false,fromKeyboard,onHolding={holding=it},feedback={long->
            view.performHapticFeedback(if(long)HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY)
        })
    },shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline),color=MaterialTheme.colorScheme.surface){
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(if(holding)"松手结束" else if(fromKeyboard)"确定\n可长按" else "确定 / 长按",textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(available)1f else .3f))}
    }
}

private enum class RemoteGlyph { Power, Back, TV, PlayPause, Mute, Plus, Minus }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteKey(glyph:RemoteGlyph,label:String,enabled:Boolean,modifier:Modifier=Modifier,showLabel:Boolean=true,
    longLabel:String?=null,onLongClick:(()->Unit)?=null,onClick:()->Unit){
    val view=LocalView.current
    val ink=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled)1f else .3f)
    Surface(modifier.clip(CircleShape).combinedClickable(enabled=enabled,role=Role.Button,onLongClickLabel=longLabel,
        onLongClick=onLongClick?.let{action->{view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);action()}},
        onClick={view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);onClick()})
        .semantics{contentDescription=label},shape=CircleShape,color=MaterialTheme.colorScheme.surfaceContainerHigh){
        Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
            RemoteIcon(glyph,ink,Modifier.size(24.dp))
            if(showLabel)Text(label,color=ink,style=MaterialTheme.typography.labelSmall,maxLines=1)
        }
    }
}

@Composable
private fun VolumeRocker(enabled:Boolean,modifier:Modifier,model:RemoteViewModel){
    val view=LocalView.current
    val ink=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled)1f else .3f)
    Surface(modifier,shape=RoundedCornerShape(36.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh){
        Column(horizontalAlignment=Alignment.CenterHorizontally){
            Box(Modifier.weight(1f).fillMaxWidth().clickable(enabled=enabled,role=Role.Button){
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);model.volume(true)
            }.semantics{contentDescription="增大音量"},contentAlignment=Alignment.Center){RemoteIcon(RemoteGlyph.Plus,ink,Modifier.size(24.dp))}
            Text("音量",color=ink,style=MaterialTheme.typography.labelSmall)
            Box(Modifier.weight(1f).fillMaxWidth().clickable(enabled=enabled,role=Role.Button){
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);model.volume(false)
            }.semantics{contentDescription="减小音量"},contentAlignment=Alignment.Center){RemoteIcon(RemoteGlyph.Minus,ink,Modifier.size(24.dp))}
        }
    }
}

@Composable
private fun RemoteIcon(glyph:RemoteGlyph,ink:Color,modifier:Modifier){
    Canvas(modifier){
        val unit=size.minDimension/24f
        fun point(x:Float,y:Float)=Offset(x*unit,y*unit)
        fun line(x1:Float,y1:Float,x2:Float,y2:Float){drawLine(ink,point(x1,y1),point(x2,y2),2*unit,StrokeCap.Round)}
        when(glyph){
            RemoteGlyph.Power->{drawArc(ink,-50f,280f,false,point(4f,4f),Size(16*unit,16*unit),style=Stroke(2*unit,cap=StrokeCap.Round));line(12f,2f,12f,11f)}
            RemoteGlyph.Back->{line(15f,4f,7f,12f);line(7f,12f,15f,20f)}
            RemoteGlyph.TV->{drawRoundRect(ink,point(2f,4f),Size(20*unit,14*unit),CornerRadius(2*unit),style=Stroke(2*unit));line(8f,22f,16f,22f);line(12f,18f,12f,22f)}
            RemoteGlyph.PlayPause->{val path=Path().apply{moveTo(3*unit,4*unit);lineTo(12*unit,12*unit);lineTo(3*unit,20*unit);close()};drawPath(path,ink);line(16f,5f,16f,19f);line(21f,5f,21f,19f)}
            RemoteGlyph.Mute->{val path=Path().apply{moveTo(3*unit,9*unit);lineTo(7*unit,9*unit);lineTo(12*unit,5*unit);lineTo(12*unit,19*unit);lineTo(7*unit,15*unit);lineTo(3*unit,15*unit);close()};drawPath(path,ink,style=Stroke(1.8f*unit));line(17f,9f,23f,15f);line(23f,9f,17f,15f)}
            RemoteGlyph.Plus->{line(4f,12f,20f,12f);line(12f,4f,12f,20f)}
            RemoteGlyph.Minus->line(4f,12f,20f,12f)
        }
    }
}

@Composable
private fun KeyboardPage(state:ScreenState,model:RemoteViewModel){
    val context=LocalContext.current
    var editor by remember{mutableStateOf(TextFieldValue(state.text,TextRange(state.text.length)))}
    LaunchedEffect(state.text){if(editor.text!=state.text)editor=TextFieldValue(state.text,TextRange(state.text.length))}
    var confirmReplace by remember{mutableStateOf(false)}
    DisposableEffect(model){model.enterKeyboard();onDispose{model.leaveKeyboard()}}
    var note by remember{mutableStateOf("")}
    Column(Modifier.fillMaxSize().padding(horizontal=18.dp)){
      Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
            Column(Modifier.weight(1f)){
                Text("实时同步",style=MaterialTheme.typography.titleMedium)
                Text(if(state.livePending)"等待连接和电视输入框，准备后自动开启" else if(state.live)"编辑即替换 · 中文确认选字后同步" else "已暂停，可切回实时同步",style=MaterialTheme.typography.bodySmall)
            }
            Switch(checked=state.live || state.livePending,onCheckedChange={if(it)model.enableLive() else model.disableLive()},enabled=!state.busy || state.live || state.livePending)
        }
        OutlinedTextField(value=editor,onValueChange={editor=it;model.edit(it.text,it.composition!=null)},modifier=Modifier.fillMaxWidth().heightIn(min=120.dp,max=210.dp),
            label={Text("输入或粘贴文字")},enabled=!state.livePending,
            keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,keyboardType=KeyboardType.Text),
            supportingText={Text("${state.text.codePointCount(0,state.text.length)} 字符 · ${if(state.focused)"电视有输入焦点" else "电视无输入焦点"}")})
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
            OutlinedButton(onClick={
                val manager=context.getSystemService(ClipboardManager::class.java);val clip=manager.primaryClip
                val value=if(clip!=null && clip.itemCount>0)clip.getItemAt(0).text else null
                if(value!=null){model.edit(value.toString());note=if(state.live)"已读取，正在实时同步。" else "已读取；点击发送即可。"}else note="剪贴板没有文字。"
            },enabled=!state.livePending,modifier=Modifier.weight(1f)){Text("读取剪贴板")}
            OutlinedButton(onClick={model.clearLocal();note="手机内容已清除，实时同步已关闭。"},modifier=Modifier.weight(1f)){Text("清除手机内容")}
        }
        if(note.isNotEmpty())Text(note,style=MaterialTheme.typography.bodySmall)
        if(!state.live && !state.livePending){
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                FilterChip(selected=!state.append,onClick={model.append(false)},label={Text("替换输入")})
                FilterChip(selected=state.append,onClick={model.append(true)},label={Text("追加输入")})
            }
            Button(onClick={if(state.append)model.send() else confirmReplace=true},enabled=state.connected && !state.busy && !state.needsReview,
                modifier=Modifier.fillMaxWidth().height(52.dp)){Text(if(state.append)"追加到 Apple TV" else if(state.text.isEmpty())"清空电视输入框" else "发送到 Apple TV")}
        }
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(state.needsReview)Button(onClick=model::acknowledge,enabled=!state.busy){Text("我已查看电视结果")}
        Text("默认实时同步，中文确认选字后发送。操作下方遥控键会先同步已确认的文字，再暂停同步；开启开关可继续输入。",style=MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
      }
      HorizontalDivider()
      KeyboardRemote(state,model)
    }
    if(confirmReplace)AlertDialog(onDismissRequest={confirmReplace=false},title={Text("替换电视当前内容？")},text={Text("这会覆盖 Apple TV 当前获得焦点的输入框。空文字会清空该输入框。")},confirmButton={TextButton(onClick={confirmReplace=false;model.send(true)}){Text("确认替换")}},dismissButton={TextButton(onClick={confirmReplace=false}){Text("取消")}})

}

@Composable
private fun KeyboardRemote(state:ScreenState,model:RemoteViewModel){
    // Text verification may still be running; queue navigation behind that work
    // without cancelling an in-progress press when busy changes.
    val available=state.connected && !state.pairing
    val keys=available && !state.touching
    val view=LocalView.current
    @Composable fun direction(label:String,symbol:String,command:Int){
        FilledTonalButton(onClick={view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);model.keyboardRemote(command)},
            enabled=keys,modifier=Modifier.size(48.dp).semantics{contentDescription=label},
            shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(0.dp)){Text(symbol,style=MaterialTheme.typography.titleLarge)}
    }
    Row(Modifier.fillMaxWidth().padding(vertical=8.dp).semantics{contentDescription="键盘页遥控"},
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
        RemoteKey(RemoteGlyph.Back,"返回",keys,Modifier.size(56.dp)){model.keyboardRemote(5)}
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)){
            direction("上","↑",1)
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                direction("左","←",3);direction("下","↓",2);direction("右","→",4)
            }
        }
        ConfirmButton(available,model,Modifier.width(76.dp).height(100.dp),fromKeyboard=true)
    }
}

@Composable
private fun DevicePage(state:ScreenState,model:RemoteViewModel){
    var manual by remember{mutableStateOf(false)}
    var info by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("设备管理",style=MaterialTheme.typography.titleLarge)
        Text(state.message,style=MaterialTheme.typography.bodyMedium)
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(onClick=model::scan,enabled=!state.busy && !state.pairing){Text("搜索设备")}
            OutlinedButton(onClick=model::reconnect,enabled=!state.busy && !state.pairing){Text("重新连接")}
            if(state.connected)TextButton(onClick=model::disconnect){Text("断开")}
        }
        state.saved.forEach{device->OutlinedCard(Modifier.fillMaxWidth()){
            Column(Modifier.padding(14.dp)){
                Text(device.endpoint.name,fontWeight=FontWeight.SemiBold)
                Text("${device.endpoint.host}:${device.endpoint.port}",style=MaterialTheme.typography.bodySmall)
                Row{TextButton(onClick={model.select(device)},enabled=!state.busy && !state.pairing){Text("连接")};TextButton(onClick={model.forget(device)},enabled=!state.busy && !state.pairing){Text("删除本机配对")}}
            }
        }}
        Text("发现的 Apple TV",style=MaterialTheme.typography.titleMedium)
        if(state.found.isEmpty())Text("未发现电视时，请唤醒设备并检查局域网；也可手动填写 IP 和 Companion 端口。",style=MaterialTheme.typography.bodySmall)
        state.found.forEach{device->OutlinedButton(onClick={model.beginPair(device)},enabled=!state.busy && !state.pairing,modifier=Modifier.fillMaxWidth()){
            Column{Text("配对：${device.name}");Text("${device.host}:${device.port}",style=MaterialTheme.typography.bodySmall)}
        }}
        OutlinedButton(onClick={manual=true},enabled=!state.busy && !state.pairing,modifier=Modifier.fillMaxWidth()){Text("手动填写 IP 和端口")}
        TextButton(onClick={info=true}){Text("遥控说明与隐私")}
        Text(if(state.connected && state.muteAvailable)"当前输出可用网络静音切换。" else "音量加减与静音分别判断。未确认支持静音时按钮变灰。",style=MaterialTheme.typography.bodySmall)
        Text("0.5.0 · 手机直接连接 Apple TV",style=MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(12.dp))
    }
    if(manual){var host by remember{mutableStateOf("")};var port by remember{mutableStateOf("49153")}
        AlertDialog(onDismissRequest={manual=false},title={Text("手动连接")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("Companion 端口会变化。49153 只是常见值，以实际发现结果为准。")
            OutlinedTextField(host,{host=it},label={Text("局域网 IP")},singleLine=true)
            OutlinedTextField(port,{port=it},label={Text("Companion 端口")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        }},confirmButton={TextButton(onClick={manual=false;model.manual(host,port)}){Text("发起配对")}},dismissButton={TextButton(onClick={manual=false}){Text("取消")}})
    }
    if(info)AlertDialog(onDismissRequest={info=false},title={Text("遥控说明与隐私")},text={Column(Modifier.verticalScroll(rememberScrollState())){Text("圆环点按方向，中央轻点确定，按住中央或独立确定可持续长按；松手、移出或切后台释放，单次最多 15 秒。先选中电视键盘退格可连续删除（视目标应用支持）。整个圆盘都可滑动，滑动转换为有间隔的方向短按，松手停止，不带惯性。返回键长按回主屏幕；TV 键长按开控制中心，轻按行为取决于电视设置。电源轻按唤醒 Apple TV，长按睡眠，是否联动电视取决于 HDMI-CEC。\n\n音量加减与静音按各自支持情况启用。仅能读取有效音量并确认设定结果时，静音通过音量归零和恢复实现。读数为零不代表电视已静音；无法确认支持时灰显，绝不自动发出音量加。未实现 Siri 语音、红外和绕圈时间线。\n\n手机直接连接 Apple TV，不需要 Mac 中转。无广告、统计或云服务。剪贴板仅主动点击读取，文字不持久化，凭据由 Android Keystore 加密。\n\n每次进入键盘默认实时同步，准备好当前电视输入框后，从下一次编辑开始替换。可关闭开关改为手动发送；输入框变化或连接异常时暂停。\n\n协议参考 pyatv 0.18.0（MIT，Pierre Ståhl）；加密使用 Bouncy Castle（MIT）。某些第三方应用可能不支持远程文字或触摸输入。")}},confirmButton={TextButton(onClick={info=false}){Text("知道了")}})
}
