package com.vertin.chongleme

import android.app.Application

/**
 * 只做一件事：持有 [AppContainer]。
 *
 * 不用静态单例（`object`）是为了让生命周期跟着系统走——进程被杀时容器一起消失，
 * 不会留下一个指向已失效 Context 的全局引用。
 */
class ChonglemeApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
