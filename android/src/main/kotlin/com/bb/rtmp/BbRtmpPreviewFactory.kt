package com.bb.rtmp

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import android.view.Surface

/**
 * PlatformView Factory：创建供 Flutter 嵌入的预览 View。
 * 预览使用 SurfaceView，通过回调把 Surface 以及尺寸/销毁事件传给 BbRtmpPlugin，用于 GL 渲染输出。
 */
class BbRtmpPreviewFactory(
    private val onSurfaceCreated: (Surface) -> Unit,
    private val onSurfaceChanged: (Int, Int) -> Unit,
    private val onSurfaceDestroyed: () -> Unit
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        return BbRtmpPreviewView(context, onSurfaceCreated, onSurfaceChanged, onSurfaceDestroyed)
    }
}

/**
 * 预览用 PlatformView：内嵌 SurfaceView，通过 SurfaceHolder.Callback 将
 * Surface 创建/尺寸变化/销毁通知给 BbRtmpPlugin。
 */
class BbRtmpPreviewView(
    context: Context,
    private val onSurfaceCreated: (Surface) -> Unit,
    private val onSurfaceChanged: (Int, Int) -> Unit,
    private val onSurfaceDestroyed: () -> Unit
) : PlatformView {

    private val surfaceView = SurfaceView(context)

    init {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                holder.surface?.let { onSurfaceCreated(it) }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (width > 0 && height > 0) {
                    onSurfaceChanged(width, height)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurfaceDestroyed()
            }
        })
    }

    override fun getView(): View = surfaceView

    override fun dispose() {
        // View 被 Flutter 回收时也通知宿主，便于停止渲染、清理引用
        onSurfaceDestroyed()
    }
}
