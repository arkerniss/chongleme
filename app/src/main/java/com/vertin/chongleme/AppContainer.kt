package com.vertin.chongleme

import android.app.Application
import com.vertin.chongleme.data.EntryRepository
import com.vertin.chongleme.data.PhotoStore

/**
 * 进程级依赖容器。
 *
 * 这里刻意不用 Hilt/Koin：本应用只有一份数据库、一个仓库，注入框架带来的
 * 注解处理与构建复杂度（尤其 KSP 与 Kotlin 2.4.10 的版本冲突风险）远超收益。
 *
 * 生命周期：容器挂在 [ChonglemeApp] 上，全进程复用同一份依赖，因此
 * `entries` 这个 StateFlow 也只有一份，界面之间不会各自持有一份快照。
 */
class AppContainer(application: Application) {
    val repository: EntryRepository = EntryRepository.open(application)

    /**
     * 照片文件的读写入口。
     *
     * 放在容器里而不是各页自己 `PhotoStore(context)`：虽然该类是无状态的、
     * 多建几个也不会出错，但「同一个职责只有一个实例」能让后来者一眼看清
     * 数据只从哪儿进出。
     */
    val photoStore: PhotoStore = PhotoStore(application)
}
