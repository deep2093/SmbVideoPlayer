package com.deepak.videolibrary.ui

import android.app.PictureInPictureParams
import android.content.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.deepak.videolibrary.MainActivity
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.player.SmbProxyFile
import com.deepak.videolibrary.settings.SmbSettings
import com.deepak.videolibrary.smb.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** WMV/ASF compatibility path; ordinary Media3-only queues keep the existing 4K player. */
@Composable
internal fun VlcPlayerScreen(video:VideoEntity,settings:SmbSettings,vm:LibraryViewModel,playlist:List<VideoEntity>,onClose:()->Unit) {
    val context=LocalContext.current
    val activity=context.findActivity()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val queue=remember(playlist,video.relativePath){playlist.ifEmpty {listOf(video)}.distinctBy {it.relativePath}}
    var index by remember {mutableIntStateOf(queue.indexOfFirst {it.relativePath==video.relativePath}.coerceAtLeast(0))}
    val current=queue[index]
    val native=remember {LibVLC(context,arrayListOf("--no-video-title-show","--no-stats","--file-caching=1500"))}
    val player=remember {MediaPlayer(native)}
    val source=remember {arrayOfNulls<SmbProxyFile>(1)}
    val gate=remember {Mutex()}
    var ready by remember {mutableStateOf(false)}
    var playing by remember {mutableStateOf(false)}
    var wantsPlay by remember {mutableStateOf(true)}
    var position by remember {mutableLongStateOf(0)}
    var duration by remember {mutableLongStateOf(0)}
    var error by remember {mutableStateOf<String?>(null)}
    var controls by remember {mutableStateOf(true)}
    var zoom by remember {mutableFloatStateOf(1f)}
    var tracks by remember {mutableStateOf(false)}
    var options by remember {mutableStateOf(false)}
    var attempt by remember {mutableIntStateOf(0)}
    var videoOutput by remember {mutableStateOf(false)}
    val prefs=remember {context.getSharedPreferences("player_prefs",Context.MODE_PRIVATE)}
    var pip by remember {mutableStateOf(prefs.getBoolean("pip_enabled",false))}
    val audio=remember {context.getSystemService(AudioManager::class.java)}
    var resumeFocus by remember {mutableStateOf(false)}
    fun debugStatus(message:String){if((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)android.util.Log.i("SmbWmv",message)}
    fun save(){if(ready)vm.saveProgress(current.relativePath,player.time.coerceAtLeast(0),player.length.coerceAtLeast(0))}
    val latestSave by rememberUpdatedState(::save)
    val latestIndex by rememberUpdatedState(index)
    val focus=remember {AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener {change->
            if(change==AudioManager.AUDIOFOCUS_GAIN){if(resumeFocus){resumeFocus=false;wantsPlay=true;player.play()}}
            else {resumeFocus=change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && player.isPlaying;player.pause();wantsPlay=false;latestSave()}
        }.build()}
    fun play(){resumeFocus=false;if(audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){wantsPlay=true;player.play()}else error="Another app currently has audio focus. Tap Play to try again."}
    fun pause(){resumeFocus=false;wantsPlay=false;player.pause();save()}
    LaunchedEffect(index,attempt) {
        ready=false;error=null;zoom=1f;position=0;duration=0;videoOutput=false
        gate.withLock {
            withContext(Dispatchers.IO){player.stop();source[0]?.close();source[0]=null}
            var opened:SmbProxyFile?=null
            try {
                val resume=vm.resumeFor(current.relativePath)
                withContext(Dispatchers.IO){opened=SmbProxyFile.open(context,SmbProfile(settings.host,settings.share,settings.rootPath,settings.username,settings.password,settings.domain,settings.requireSigning,settings.requireEncryption),current.relativePath,current.size)}
                currentCoroutineContext().ensureActive()
                source[0]=opened
                Media(native,opened!!.descriptor.fileDescriptor).apply {
                    setHWDecoderEnabled(true,false)
                    if(resume!=null && (resume.durationMs<=0 || resume.positionMs<resume.durationMs-10000))addOption(":start-time=${resume.positionMs/1000.0}")
                    player.media=this;release()
                }
                vm.hideContinue(current.relativePath,false)
                ready=true
                if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))play()
            }catch(e:CancellationException){opened?.close();throw e}
            catch(e:Exception){opened?.close();error=SmbErrors.describe(e);AppDiagnostics.record(context,"wmv","open_failed_${e.javaClass.simpleName}")}
        }
    }
    LaunchedEffect(player) {var tick=0;var last=-1L;while(true){if(ready){position=player.time.coerceAtLeast(0);duration=player.length.coerceAtLeast(0);playing=player.isPlaying;if(++tick>=5){tick=0;debugStatus("position_ms=$position playing=$playing video_output=$videoOutput");if(position!=last){latestSave();last=position}}};delay(1000)}}
    LaunchedEffect(playing,videoOutput,position>1000) {
        if(playing && videoOutput && position>1000) {
            AppDiagnostics.record(context,"wmv","video_output_active")
            debugStatus("video_output_active")
        }
    }
    LaunchedEffect(playing,controls,tracks,options){if(playing && controls && !tracks && !options){delay(3000);controls=false}}
    DisposableEffect(player) {
        player.setEventListener {event->when(event.type){
            MediaPlayer.Event.Playing->{playing=true;debugStatus("playing");AppDiagnostics.record(context,"wmv","playing")}
            MediaPlayer.Event.Paused->{playing=false;latestSave()}
            MediaPlayer.Event.Vout->{videoOutput=event.voutCount>0;debugStatus("vout_count=${event.voutCount}")}
            MediaPlayer.Event.EndReached->{latestSave();if(latestIndex<queue.lastIndex)index=latestIndex+1 else {playing=false;wantsPlay=false;controls=true}}
            MediaPlayer.Event.EncounteredError->{debugStatus("decoder_error");error=source[0]?.failure ?: "VLC could not decode this file. The file may be damaged or protected.";playing=false;wantsPlay=false;controls=true}
        }}
        onDispose {
            latestSave();player.setEventListener(null);player.detachViews();audio.abandonAudioFocusRequest(focus)
            // Native stop can wait for an outstanding SMB read; never block the UI thread.
            CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {gate.withLock {player.stop();player.release();source[0]?.close();source[0]=null;native.release()}}
        }
    }
    DisposableEffect(activity,lifecycle,player,pip) {
        val main=activity as? MainActivity
        val oldLeave=main?.onLeavePlayback
        if(main!=null)main.onLeavePlayback={if(pip && player.isPlaying)runCatching {main.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16,9)).build())}}
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP && activity?.isInPictureInPictureMode!=true){wantsPlay=false;resumeFocus=false;player.pause();latestSave()}}
        lifecycle.addObserver(observer)
        val noisy=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){if(i?.action==AudioManager.ACTION_AUDIO_BECOMING_NOISY){wantsPlay=false;resumeFocus=false;player.pause();latestSave()}}}
        ContextCompat.registerReceiver(context,noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {lifecycle.removeObserver(observer);context.unregisterReceiver(noisy);main?.onLeavePlayback=oldLeave}
    }
    DisposableEffect(activity) {
        val window=activity?.window;val before=window?.attributes?.screenBrightness
        val controller=window?.let {WindowCompat.getInsetsController(it,it.decorView)}
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);controller?.show(WindowInsetsCompat.Type.systemBars());if(window!=null && before!=null){val a=window.attributes;a.screenBrightness=before;window.attributes=a}}
    }
    LaunchedEffect(wantsPlay,error){if(wantsPlay && error==null)activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    BackHandler {if(tracks)tracks=false else if(options)options=false else onClose()}
    Box(Modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
        AndroidView(factory={VLCVideoLayout(it).also {layout->player.attachViews(layout,null,true,true);layout.importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES}},update={layout->layout.contentDescription=if(playing && position>1000 && videoOutput)"WMV playback active" else "WMV loading"},modifier=Modifier.fillMaxSize().semantics {contentDescription=if(playing && position>1000 && videoOutput)"WMV playback active" else "WMV loading"}.graphicsLayer {scaleX=zoom;scaleY=zoom})
        Box(Modifier.fillMaxSize().pointerInput(player){detectTransformGestures {_,_,factor,_->zoom=(zoom*factor).coerceIn(1f,4f)}}.pointerInput(player){detectTapGestures(onTap={controls=!controls},onDoubleTap={where->player.setTime((player.time+if(where.x<size.width/2)-10000 else 10000).coerceAtLeast(0));controls=true})})
        if(controls || !playing)Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha=0.88f)).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically){Text(current.name,Modifier.weight(1f),maxLines=2,color=Color.White);TextButton(onClick=onClose){Text("Close")}}
            Text("WMV compatibility playback · VLC",style=MaterialTheme.typography.labelSmall,color=Color.White)
            if(duration>0)Slider(value=(position.toFloat()/duration).coerceIn(0f,1f),onValueChange={position=(it*duration).toLong()},onValueChangeFinished={player.setTime(position);save()})
            Text("${android.text.format.DateUtils.formatElapsedTime(position/1000)} / ${android.text.format.DateUtils.formatElapsedTime(duration/1000)}",color=Color.White)
            Row(modifier=Modifier.horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                TextButton(enabled=index>0,onClick={save();index--}){Text("Previous")}
                TextButton(onClick={player.setTime((player.time-10000).coerceAtLeast(0));save()}){Text("−10")}
                Button(enabled=ready,onClick={if(player.isPlaying)pause()else play()}){Text(if(playing)"Pause"else"Play")}
                TextButton(onClick={player.setTime(player.time+10000);save()}){Text("+10")}
                TextButton(enabled=index<queue.lastIndex,onClick={save();index++}){Text("Next")}
                TextButton(onClick={tracks=true}){Text("Tracks")}
                TextButton(onClick={options=true}){Text("Options")}
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error);TextButton(onClick={attempt++}){Text("Retry")}}
        }
    }
    if(tracks)AlertDialog(onDismissRequest={tracks=false},title={Text("Audio and subtitles")},text={Column {
        player.audioTracks?.forEach {track->TextButton(onClick={player.setAudioTrack(track.id);tracks=false}){Text("Audio: ${track.name}")}}
        player.spuTracks?.forEach {track->TextButton(onClick={player.setSpuTrack(track.id);tracks=false}){Text("Subtitle: ${track.name}")}}
    }},confirmButton={TextButton(onClick={tracks=false}){Text("Close")}})
    if(options)AlertDialog(onDismissRequest={options=false},title={Text("Playback options")},text={Column {
        TextButton(onClick={zoom=1f}){Text("Reset zoom")}
        Row {listOf(0.75f,1f,1.5f,2f).forEach {speed->TextButton(onClick={player.rate=speed}){Text("${speed}×")}}}
        Row(verticalAlignment=Alignment.CenterVertically){Text("Picture in picture");Switch(pip,{pip=it;prefs.edit().putBoolean("pip_enabled",it).apply()})}
        Text("Volume");var volume by remember {mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat())};val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat();Slider(volume,{volume=it;audio.setStreamVolume(AudioManager.STREAM_MUSIC,it.toInt(),0)},valueRange=0f..max)
        Text("Brightness");var light by remember {mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf {it>=0} ?: 0.5f)};Slider(light,{light=it;activity?.window?.let {w->val a=w.attributes;a.screenBrightness=it;w.attributes=a}},valueRange=0.02f..1f)
    }},confirmButton={TextButton(onClick={options=false}){Text("Close")}})
}
