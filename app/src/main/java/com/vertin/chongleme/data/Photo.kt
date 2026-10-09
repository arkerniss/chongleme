package com.vertin.chongleme.data

/**
 * 一条记录的配图。
 *
 * [relPath] 是**相对于应用私有图片根目录**的路径，不是绝对路径：
 * 绝对路径里带着 user id / 挂载点，一旦设备迁移或应用被移动到别的沙箱就全失效，
 * 而且它会把用户目录结构写进数据库。真正的绝对路径在 UI 侧用图片根目录拼出来。
 *
 * [width] / [height] 存原始像素尺寸，不是为了展示，而是为了让列表页在解码前
 * 就能算出采样率（见 [com.vertin.chongleme.util.sampleSizeFor]），避免把整张原图读进内存。
 *
 * 契约冻结：字段与顺序不得改。
 */
data class Photo(
    val id: Long,
    val entryId: Long,
    val relPath: String,
    val width: Int,
    val height: Int,
    val sortOrder: Int,
    val createdAt: Long,
)

/**
 * 尚未落库的配图（写入口的入参形态）。
 *
 * 存在的理由：照片是先落盘、再写数据库行的，落盘那一刻还没有 photo id 与 entryId，
 * 所以写入口不能要求调用方先造一个 [Photo]。
 */
data class PhotoDraft(
    val relPath: String,
    val width: Int,
    val height: Int,
)
