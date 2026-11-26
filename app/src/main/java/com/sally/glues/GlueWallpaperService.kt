package com.sally.glues

import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

class GlueWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = GlueEngine()

    inner class GlueEngine : Engine() {
        private var renderer: GlueRenderer? = null

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            val frame = holder.surfaceFrame
            val cfg = GlueStore.load(this@GlueWallpaperService)
            renderer = GlueRenderer(
                context = applicationContext,
                outputSurface = holder.surface,
                width = frame.width(),
                height = frame.height(),
                config = cfg
            ).also { it.start() }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer?.resize(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            renderer?.release()
            renderer = null
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            renderer?.setVisible(visible)
            if (visible) renderer?.applyConfig(GlueStore.load(this@GlueWallpaperService))
        }
    }
}
