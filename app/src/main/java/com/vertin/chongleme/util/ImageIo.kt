package com.vertin.chongleme.util

/**
 * 把「原图最长边」压到 `maxEdge` 以内所需的降采样因子，向上取到 2 的幂。
 *
 * 为什么必须是 2 的幂：`BitmapFactory.Options.inSampleSize` 只在 2 的幂上保证
 * 「按比例整除」的解码路径，非 2 的幂在小内存设备上会被驱动悄悄退化成最近邻甚至整图解码。
 *
 * 语义（本项目的唯一定义，测试按此断言）：
 * - 因子 = ⌈最长边 / maxEdge⌉，再向上取到 2 的幂；即保证 `最长边 / 因子 ≤ maxEdge`。
 * - 已经 ≤ maxEdge（含正好等于）→ 1，即不降采样。
 * - 防御式输入：`maxEdge <= 0` 或**任一维非正**，一律返回 1。
 *   取 1 而不是猜一个档位，理由有二：这两个维度来自相册/相机回传的元数据，
 *   坏值意味着「我们其实不知道图有多大」；而在一张尺寸未知的图上做降采样，
 *   可能把用户刚拍的照片缩成 1 像素。返回 1 是一个明确的信号：
 *   调用方应当先用 `inJustDecodeBounds` 量一次真实尺寸，再回来算档位。
 *   同样地，这里不抛异常——坏元数据不该让整条记录流程崩掉。
 *
 * 纯函数：不碰 `Bitmap`、不读文件、不看 `Resources`——那些留在 UI 层，
 * 这里只留下可以在 JVM 上直接断言的那部分算术。
 */
fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return 1

    val longest = maxOf(width, height)
    if (longest <= maxEdge) return 1

    // 用 Long 做上取整，避免 longest + maxEdge 在 Int.MAX_VALUE 附近溢出。
    val needed = (longest.toLong() + maxEdge - 1L) / maxEdge.toLong()

    var sample = 1L
    while (sample < needed) sample = sample shl 1

    return if (sample > Int.MAX_VALUE) Int.MAX_VALUE else sample.toInt()
}
