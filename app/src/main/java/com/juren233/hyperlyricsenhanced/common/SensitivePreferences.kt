/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

/**
 * 模块偏好里需要保护的键。备份恢复和日志导出都引用这一份，新增敏感项只在这里登记。
 */
internal object SensitivePreferences {
    /** 机密值：不写入备份、不从备份恢复；日志导出只标明是否已填写。 */
    val SECRET_KEYS: Set<String> = setOf(
        RootConstants.KEY_HOOK_AI_TRANS_API_KEY,
    )

    /** 地址类值：日志导出只保留主机名，不带路径、端口和查询参数。 */
    val HOST_ONLY_KEYS: Set<String> = setOf(
        RootConstants.KEY_HOOK_AI_TRANS_BASE_URL,
    )

    /** 用户自写的长文本：日志导出只标明是否为默认值及长度。 */
    val TEXT_LENGTH_ONLY_KEYS: Map<String, String> = mapOf(
        RootConstants.KEY_HOOK_AI_TRANS_PROMPT to RootConstants.DEFAULT_HOOK_AI_TRANS_PROMPT,
    )
}
