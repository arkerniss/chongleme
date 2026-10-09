package com.vertin.chongleme.ui.gallery

import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.Photo
import com.vertin.chongleme.util.DayKey

/**
 * 相册里的一格：一张配图，加上它属于哪条记录。
 *
 * [index] 是它在**全量列表**里的序号，由 [galleryItems] 铺平的时候顺手记下来。
 * 存在的理由：分块渲染之后，格子自己不知道全局位置，而全屏查看器的 pager
 * 需要的是全局下标。让模型带着这个数字，比在点击时用 `indexOf` 反查更稳——
 * 反查依赖相等性，同一条记录里若有两条完全一样的 Photo，就会跳错页。
 */
data class GalleryItem(
    val index: Int,
    val entry: Entry,
    val photo: Photo,
)

/** 相册的一个月份分块。块内顺序就是 [galleryItems] 的顺序（时间倒序）。 */
data class GallerySection(
    val year: Int,
    val month: Int,
    val items: List<GalleryItem>,
)

/**
 * 把全量记录铺平成配图列表，按**记录发生时间**倒序。
 *
 * 用 [Entry.occurredAt] 而不是 [Photo.createdAt]：补记一条上个月的事情时，
 * 图是今天落盘的，但它讲的是上个月那一天的事。相册按「事情发生在什么时候」排，
 * 才和日历、时间轴是同一个时间轴（三处口径一致，用户才不会觉得数据错乱）。
 *
 * 同一条记录内按 [Photo.sortOrder]，并列时按 id —— 排序必须全序，
 * 否则同一次写入的两张图在两次组合里可能换位置，网格会莫名其妙地跳。
 */
fun galleryItems(entries: List<Entry>): List<GalleryItem> {
    val out = ArrayList<GalleryItem>(entries.sumOf { it.photos.size })
    entries.asSequence()
        .sortedByDescending { it.occurredAt }
        .forEach { entry ->
            entry.photos
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .forEach { photo -> out.add(GalleryItem(index = out.size, entry = entry, photo = photo)) }
        }
    return out
}

/**
 * 按记录发生的年月分块，块之间也按时间倒序。
 *
 * 分组键用 `year * 100 + month` 这个整数：它天然可排序，又不需要 `YearMonth`
 * （多一个 java.time 类型并不会让这里更清楚），并且不依赖 Map 的迭代顺序。
 *
 * `groupBy` 在同一组内保持原顺序，所以块内的倒序是 [galleryItems] 保证的。
 */
fun gallerySections(items: List<GalleryItem>): List<GallerySection> =
    items.groupBy { item ->
        val day = DayKey.of(item.entry.occurredAt)
        day.year * 100 + day.monthValue
    }.entries
        .sortedByDescending { it.key }
        .map { (key, group) ->
            GallerySection(year = key / 100, month = key % 100, items = group)
        }
