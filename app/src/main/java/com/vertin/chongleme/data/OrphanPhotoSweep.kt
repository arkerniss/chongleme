package com.vertin.chongleme.data

/**
 * 孤儿照片清扫：删掉磁盘上没有任何数据库行引用的配图文件。
 *
 * ## 为什么需要它
 *
 * 照片的写入顺序是「先落盘、再写数据库行」。如果在两者之间进程被杀（用户在编辑页
 * 拍了照还没保存就切走、系统回收了进程），那份文件就永远不会有 [Photo] 行指向它，
 * 而且没有任何后续路径会去认领——它只会一直占着存储，用户也无从察觉。
 * 单次至多十来张，但长期使用会累积。
 *
 * ## 为什么这是安全的
 *
 * 三条保护缺一不可：
 *
 * 1. **调用方必须传一份成功读出的记录列表**。若数据库读取失败，绝不能传空列表进来——
 *    那会把全部照片当成孤儿删光。所以清扫入口只接受「已经拿到的 entries」，
 *    自己不负责读库。
 * 2. **记录列表为空时直接跳过**。空列表可能意味着「数据库读失败」或「用户清空过」，
 *    这两种情况都不该触发删除：如果是后者，磁盘上本来也不该有文件；
 *    如果是前者，这就是灾难。宁可留垃圾也不误删。
 * 3. **只删 [PhotoStore] 根目录内的文件**，不碰数据库、不碰备份 ZIP、不碰其它目录。
 *
 * 返回被删掉的文件数，供日志与测试断言。
 */
object OrphanPhotoSweep {

    /**
     * 执行一次对账。
     *
     * @param entries 已成功从数据库读出的全部记录（含配图）。
     * @param photoStore 配图文件存储。
     * @return 删除的文件数。
     */
    fun sweep(entries: List<Entry>, photoStore: PhotoStore): Int {
        // 保护 2：空列表一律不动。无法区分「真的没有记录」和「读库失败」时，
        // 唯一安全的选择是什么都不做。
        if (entries.isEmpty()) return 0

        val referenced = entries.asSequence()
            .flatMap { it.photos.asSequence() }
            .map { it.relPath }
            .toHashSet()

        var removed = 0
        photoStore.listAllRelativePaths().forEach { relPath ->
            if (relPath !in referenced && photoStore.delete(relPath)) {
                removed++
            }
        }
        return removed
    }
}
