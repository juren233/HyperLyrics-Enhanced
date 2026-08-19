package com.juren233.hyperlyricsenhanced.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun NumberInputDialog(
    show: Boolean,
    title: String,
    label: String,
    useLabelAsPlaceholder: Boolean = false,
    initialValue: Int,
    min: Int,
    max: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var inputValue by remember { mutableStateOf(initialValue.toString()) }
    LaunchedEffect(show, initialValue) {
        if (show) inputValue = initialValue.toString()
    }

    WindowDialog(title = title, show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = inputValue,
                onValueChange = { newValue -> if (newValue.all { it.isDigit() }) inputValue = newValue },
                label = label,
                useLabelAsPlaceholder = useLabelAsPlaceholder,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                maxLines = 1
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(text = stringResource(id = R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(id = R.string.confirm),
                    onClick = {
                        inputValue.toIntOrNull()?.let {
                            onConfirm(it.coerceIn(min, max))
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
fun NumberRangeInputDialog(
    show: Boolean,
    title: String,
    minLabel: String,
    maxLabel: String,
    initialMinValue: Int,
    initialMaxValue: Int,
    allowedMin: Int,
    allowedMax: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    var minInputValue by remember { mutableStateOf(initialMinValue.toString()) }
    var maxInputValue by remember { mutableStateOf(initialMaxValue.toString()) }
    LaunchedEffect(show, initialMinValue, initialMaxValue) {
        if (show) {
            minInputValue = initialMinValue.toString()
            maxInputValue = initialMaxValue.toString()
        }
    }

    WindowDialog(title = title, show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = minInputValue,
                onValueChange = { newValue ->
                    if (newValue.all(Char::isDigit)) minInputValue = newValue
                },
                label = minLabel,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = maxInputValue,
                onValueChange = { newValue ->
                    if (newValue.all(Char::isDigit)) maxInputValue = newValue
                },
                label = maxLabel,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    text = stringResource(id = R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(id = R.string.confirm),
                    onClick = {
                        val enteredMin = minInputValue.toIntOrNull() ?: return@TextButton
                        val enteredMax = maxInputValue.toIntOrNull() ?: return@TextButton
                        val clampedMin = enteredMin.coerceIn(allowedMin, allowedMax)
                        val clampedMax = enteredMax.coerceIn(allowedMin, allowedMax)
                        onConfirm(
                            minOf(clampedMin, clampedMax),
                            maxOf(clampedMin, clampedMax),
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
fun FloatRangeInputDialog(
    show: Boolean,
    title: String,
    minLabel: String,
    maxLabel: String,
    initialMinValue: Float,
    initialMaxValue: Float,
    allowedMin: Float,
    allowedMax: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float, Float) -> Unit,
) {
    var minInputValue by remember { mutableStateOf(initialMinValue.toString()) }
    var maxInputValue by remember { mutableStateOf(initialMaxValue.toString()) }
    LaunchedEffect(show, initialMinValue, initialMaxValue) {
        if (show) {
            minInputValue = initialMinValue.toString()
            maxInputValue = initialMaxValue.toString()
        }
    }
    val acceptsDecimal = { value: String ->
        value.count { it == '.' } <= 1 && value.all { it.isDigit() || it == '.' }
    }

    WindowDialog(title = title, show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = minInputValue,
                onValueChange = { if (acceptsDecimal(it)) minInputValue = it },
                label = minLabel,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = maxInputValue,
                onValueChange = { if (acceptsDecimal(it)) maxInputValue = it },
                label = maxLabel,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    text = stringResource(id = R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(id = R.string.confirm),
                    onClick = {
                        val enteredMin = minInputValue.toFloatOrNull() ?: return@TextButton
                        val enteredMax = maxInputValue.toFloatOrNull() ?: return@TextButton
                        val clampedMin = enteredMin.coerceIn(allowedMin, allowedMax)
                        val clampedMax = enteredMax.coerceIn(allowedMin, allowedMax)
                        onConfirm(
                            minOf(clampedMin, clampedMax),
                            maxOf(clampedMin, clampedMax),
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
fun TextInputDialog(
    show: Boolean,
    title: String,
    initialValue: String,
    label: String = title,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    confirmText: String = stringResource(id = R.string.confirm),
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var inputValue by remember { mutableStateOf(initialValue) }
    LaunchedEffect(show, initialValue) {
        if (show) inputValue = initialValue
    }

    WindowDialog(title = title, show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = inputValue,
                onValueChange = { inputValue = it },
                label = label,
                keyboardOptions = keyboardOptions,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                maxLines = 15
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(text = stringResource(id = R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = confirmText,
                    onClick = { onConfirm(inputValue); onDismiss() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
fun SimpleDialog(
    show: Boolean,
    title: String,
    summary: String? = null,
    confirmText: String = stringResource(id = R.string.confirm),
    cancelText: String = stringResource(id = R.string.cancel),
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    WindowDialog(
        title = title,
        summary = summary,
        show = show,
        onDismissRequest = onDismiss
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(text = cancelText, onClick = onDismiss, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(20.dp))
            TextButton(
                text = confirmText,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f),
                onClick = {
                    onConfirm()
                    onDismiss()
                }
            )
        }
    }
}

@Composable
fun FloatInputDialog(
    show: Boolean,
    title: String,
    label: String,
    initialValue: Float,
    min: Float,
    max: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    var inputValue by remember { mutableStateOf(initialValue.toString()) }
    LaunchedEffect(show, initialValue) {
        if (show) inputValue = initialValue.toString()
    }

    WindowDialog(title = title, show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = inputValue,
                onValueChange = { newValue -> if (newValue.all { it.isDigit() || it == '.' }) inputValue = newValue },
                label = label,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                maxLines = 1
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(text = stringResource(id = R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(id = R.string.confirm),
                    onClick = {
                        inputValue.toFloatOrNull()?.let {
                            onConfirm(it.coerceIn(min, max))
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
fun PaddingInputDialog(
    show: Boolean,
    title: String,
    initialLeft: Int,
    initialRight: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var leftValue by remember { mutableStateOf(initialLeft.toString()) }
    var rightValue by remember { mutableStateOf(initialRight.toString()) }
    LaunchedEffect(show, initialLeft, initialRight) {
        if (show) {
            leftValue = initialLeft.toString()
            rightValue = initialRight.toString()
        }
    }

    WindowDialog(title = title, show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val filter = { text: String ->
                if (text == "-" || text.isEmpty()) true
                else text.toIntOrNull() != null
            }
            TextField(
                value = leftValue,
                onValueChange = { if (filter(it)) leftValue = it },
                label = stringResource(id = R.string.label_left_padding_range),
                useLabelAsPlaceholder = false,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = rightValue,
                onValueChange = { if (filter(it)) rightValue = it },
                label = stringResource(id = R.string.label_right_padding_range),
                useLabelAsPlaceholder = false,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(text = stringResource(id = R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(id = R.string.confirm),
                    onClick = {
                        val l = leftValue.toIntOrNull() ?: 0
                        val r = rightValue.toIntOrNull() ?: 0
                        onConfirm(l.coerceIn(-50, 100), r.coerceIn(-50, 100))
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}
