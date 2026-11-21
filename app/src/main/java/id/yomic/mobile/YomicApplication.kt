package id.yomic.mobile

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import id.yomic.mobile.di.AppContainer

class YomicApplication : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        container.imagePipeline.imageLoader
}
