package com.huoyi.photovault.ui.main.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.huoyi.photovault.R

/**
 * Bottom sheet for editing the LAN address from the settings tab (design §5.6).
 *
 * Contains:
 * - Address input (hint: 建议填写 IP，如 192.168.1.10:8000; replaced by the error when save fails)
 * - 保存: calls [onSave]; null result → dismiss, otherwise stay open and show the error
 * - 清除 (only when [current] is non-empty): calls `onSave("")`
 *
 * @param onSave validates and persists the input; returns null on success or the error text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanAddressSheet(
    current: String,
    onSave: (String) -> String?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var input by rememberSaveable { mutableStateOf(current) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val save = { value: String ->
        val result = onSave(value)
        if (result == null) onDismiss() else error = result
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                text = "局域网地址",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    error = null
                },
                placeholder = { Text("192.168.1.10:8000") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = error != null,
                supportingText = {
                    Text(error ?: stringResource(R.string.login_lan_address_supporting))
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { save(input) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存")
            }

            if (current.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { save("") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("清除")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
