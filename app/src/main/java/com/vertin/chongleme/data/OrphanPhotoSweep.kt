package com.vertin.chongleme.data

/**
 * 孤儿照片清扫：删掉磁盘上没有任何数据库行引用的配图文件。
 *
 * ## 为什么需要它
 *
 * 照片的写入顺序是「先落盘、再写数据库行」。如果在两者之间进程被杀（用户在编辑页
 * 拍了照还没保存就切走、系统回收了进程），那份文件就永远不会有 [Photo] 行指向它，
 * 而且没有任何后续路径会去认领——它只会一直占着存储，用户也无从察觉。
 *
 * ## ⚠️ 它曾经是个 bug 制造机
 *
 * 最初版本只按「数据库有没有引用」判断，结果把**编辑页里已经落盘、但还没保存**的
 * 照片也删了：草稿是通过 `rememberSaveable` 跨进程死亡恢复的，恢复后那批文件确实
 * 没有数据库行引用，于是被判为孤儿。用户回到编辑页点保存，数据库里就出现指向
 * 不存在文件的 photo 行——静默丢用户刚拍的照片。
 *
 * 现在有**四道**保护，缺一不可：
 *
 * 1. **调用方必须传一份成功读出的记录列表**。读库失败时绝不能传空列表进来。
 * 2. **记录列表为空时直接跳过**（空列表可能意味着「读库失败」，不能当成「没有照片」）。
 * 3. **宽限期**：文件修改时间在 [graceMillis] 之内的，一律不动。
 *    这是对「正在进行的编辑会话」唯一可靠的保护——无论怎么加参数，
 *    清扫都不可能知道另一个组件此刻正打算引用哪个文件，但「刚写进来的文件先别动」
 *    这个规则永远成立。
 * 4. **[protectedPaths]**：调用方显式声明不能删的路径（当前编辑会话的待保存配图）。
 *    前三道是兜底，这一道是正解——两者都要有。
 *
 * 另外只删除 [PhotoStore] 根目录内的文件，不碰数据库、不碰备份 ZIP。
 */
object OrphanPhotoSweep {

    /**
     * 宽限期默认值：15 分钟。
     *
     * 取值理由：用户从拍照到点保存，几乎不会超过 15 分钟；而真正的孤儿文件
     * 在磁盘上多留 15 分钟毫无代价（下次启动就会被清）。宁可晚清，不可早删。
     */
    const val DEFAULT_GRACE_MILLIS: Long = 15L * 60L * 1000L

    /**
     * 执行一次对账。
     *
     * @param entries 已成功从数据库读出的全部记录（含配图）。
     * @param photoStore 配图文件存储。
     * @param protectedPaths 当前不能删的相对路径（例如编辑页正在编辑、尚未保存的配图）。
     * @param graceMillis 修改时间距今小于该值的文件一律跳过。
     * @param now 当前时间，可注入以便测试。
     * @return 删除的文件数。
     */
    fun sweep(
        entries: List<Entry>,
        photoStore: PhotoStore,
        protectedPaths: Set<String> = emptySet(),
        graceMillis: Long = DEFAULT_GRACE_MILLIS,
        now: Long = System.currentTimeMillis(),
    ): Int {
        // 保护 2：空列表一律不动。无法区分「真的没有记录」和「读库失败」时，
        // 唯一安全的选择是什么都不做。
        if (entries.isEmpty()) return 0

        val referenced = entries.asSequence()
            .flatMap { it.photos.asSequence() }
            .map { it.relPath }
            .toHashSet()

        var removed = 0
        photoStore.listAllRelativePaths().forEach { relPath ->
            if (relPath in referenced) return@forEach
            // 保护 4：调用方点名的路径，无条件保留（正解）
            if (relPath in protectedPaths) return@forEach

            val file = photoStore.resolve(relPath)
            // 保护 3：刚写进来的文件先别动（兜底）。
            // 取不到修改时间时保守处理——当作「刚写的」，不删。
            val modified = file.lastModified()
            if (modified <= 0L || now - modified < graceMillis) return@forEach

            if (photoStore.delete(relPath)) removed++
        }
        return removed
    }
}
