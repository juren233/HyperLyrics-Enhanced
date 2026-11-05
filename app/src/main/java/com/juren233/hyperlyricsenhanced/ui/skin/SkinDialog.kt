/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.ui.skin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.component.AppWindowDialog
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioAlertFieldGroup
import io.github.neoruaa.glasmio.ui.GlasmioAlertTextField
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField

/**
 * 带确认/取消按钮的对话框。Miuix = 窗口对话框，按钮在内容下方并排；
 * Glasmio = [GlasmioAlertDialog]，按钮走其 `buttons` 规格（取消为 Cancel 角色，确认为默认角色）。
 * [onConfirm] 由调用方负责收起对话框。
 */
@Composable
fun SkinDialog(
    show: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    confirmText: String = stringResource(R.string.confirm),
    cancelText: String = stringResource(R.string.cancel),
    content: @Composable ColumnScope.() -> Unit = {},
) {
    if (LocalUiStyle.current.isGlasmio) {
        GlasmioAlertDialog(
            onDismissRequest = onDismissRequest,
            title = title,
            message = summary,
            modifier = modifier,
            visible = show,
            buttons = listOf(
                GlasmioAlertButtonSpec(cancelText, onDismissRequest, GlasmioAlertButtonRole.Cancel),
                GlasmioAlertButtonSpec(confirmText, onConfirm),
            ),
            content = content,
        )
        return
    }
    AppWindowDialog(title = title, summary = summary, show = show, onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(text = cancelText, onClick = onDismissRequest, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(text = confirmText, onClick = onConfirm, modifier = Modifier.weight(1f), primary = true)
            }
        }
    }
}

/**
 * 对话框内的文本输入框。Miuix = Miuix TextField；Glasmio = 警告框的输入分组（[GlasmioAlertTextField]），
 * 以 [label] 作占位文字。[onValueChange] 拒绝某次输入时，显示值会回到 [value]。
 */
@Composable
fun SkinTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    maxLines: Int = 1,
    useLabelAsPlaceholder: Boolean = false,
) {
    if (LocalUiStyle.current.isGlasmio) {
        var field by remember { mutableStateOf(TextFieldValue(value)) }
        val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))
        GlasmioAlertFieldGroup(modifier) {
            GlasmioAlertTextField(
                value = shown,
                onValueChange = { field = it; onValueChange(it.text) },
                placeholder = label,
                singleLine = maxLines == 1,
                maxLines = maxLines,
                keyboardOptions = keyboardOptions,
            )
        }
    } else {
        MiuixTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            useLabelAsPlaceholder = useLabelAsPlaceholder,
            keyboardOptions = keyboardOptions,
            modifier = modifier,
            maxLines = maxLines,
        )
    }
}
