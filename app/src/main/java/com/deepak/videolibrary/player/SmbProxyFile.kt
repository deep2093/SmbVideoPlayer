package com.deepak.videolibrary.player

import android.content.Context
import android.os.*
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbErrors
import com.deepak.videolibrary.smb.SmbProfile
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Seekable, read-only Android descriptor backed by SMB. No local download or credential URL. */
class SmbProxyFile private constructor(private val connection:SmbConnection,
    private val file:com.hierynomus.smbj.share.File,private val size:Long):Closeable {
    private val thread=HandlerThread("wmv-smb-reader").apply {start()}
    private val handler=Handler(thread.looper)
    private val closing=AtomicBoolean(false)
    private val released=AtomicBoolean(false)
    private val cache=ReadAheadBuffer()
    private var nextOffset=-1L
    @Volatile var failure:String?=null
        private set
    lateinit var descriptor:ParcelFileDescriptor
        private set
    private fun releaseData(){if(released.compareAndSet(false,true)){runCatching {file.close()};connection.close();thread.quitSafely()}}
    private fun mount(context:Context) {
        descriptor=context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
            object:ProxyFileDescriptorCallback(){
                override fun onGetSize()=size
                override fun onRead(offset:Long,requested:Int,data:ByteArray):Int {
                    if(closing.get())throw ErrnoException("read",OsConstants.EBADF)
                    if(offset>=size)return 0
                    return try {
                        if(offset!=nextOffset)cache.clear()
                        val n=cache.read(data,0,requested.coerceAtMost(data.size),size-offset){block,count->file.read(block,offset,0,count)}
                        nextOffset=offset+n;n
                    }catch(e:Exception){failure=SmbErrors.describe(e);throw ErrnoException("SMB read",OsConstants.EIO,e)}
                }
                override fun onRelease(){releaseData()}
            },handler)
    }
    override fun close(){if(closing.compareAndSet(false,true)){if(::descriptor.isInitialized)runCatching {descriptor.close()};handler.post {releaseData()}}}
    companion object {
        /** Blocking: call on Dispatchers.IO. */
        fun open(context:Context,profile:SmbProfile,path:String,size:Long):SmbProxyFile {
            require(size>0){"Empty video file"}
            val conn=SmbConnection(profile)
            try {
                conn.open()
                val source=SmbProxyFile(conn,conn.openFile(path),size)
                try {source.mount(context);return source}catch(e:Exception){source.close();throw e}
            }catch(e:Exception){conn.close();throw e}
        }
    }
}
