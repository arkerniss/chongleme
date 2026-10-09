package com.vertin.chongleme.support

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.time.ZoneId
import java.util.TimeZone

/**
 * 在测试期间把 JVM 默认时区固定成 [zoneId]，结束后还原。
 *
 * 为什么需要它：`Stats` 的签名被冻结成只吃 `LocalDate`，「时间戳属于哪一天」由
 * `ZoneId.systemDefault()` 决定。测试要断言「同一个时间戳在上海是 3 月 1 日、
 * 在 UTC 是 2 月 28 日」，就必须能把默认时区拨来拨去。
 *
 * 用 `TestRule` 而不是在每个测试里手写 try/finally：还原写在一条公共路径上，
 * 漏还原的测试会让后面的测试随机变红（Gradle 默认单 fork 顺序跑，脏状态会传染）。
 */
class FixedTimeZoneRule(private val zoneId: String) : TestRule {

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val original = TimeZone.getDefault()
            try {
                // 只改 TimeZone 的默认值就够：ZoneId.systemDefault() 就是它转出来的。
                // 刻意不去动 user.timezone 系统属性——JDK 只在初始化时读它一次，
                // 之后再写是无效动作，反而会给人「改了属性就生效」的错觉。
                TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zoneId)))
                base.evaluate()
            } finally {
                TimeZone.setDefault(original)
            }
        }
    }
}
