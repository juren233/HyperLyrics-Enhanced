package com.lidesheng.hyperlyric.ui.page.lyricnotification

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lidesheng.hyperlyric.ui.utils.pageScrollModifiers
import top.yukonga.miuix.kmp.basic.ScrollBehavior

@Composable
fun LyricNotificationWhitelistTab(
    lazyListState: LazyListState,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues,
    whitelist: List<String>,
    onAddClick: () -> Unit,
    showAddDialog: Boolean,
    onDeleteClick: (String) -> Unit,
    showDeleteDialog: Boolean,
    packageToDelete: String
) {
    LazyColumn(
        state = lazyListState,
        modifier = Modifier.pageScrollModifiers(
            enableScrollEndHaptic = true,
            showTopAppBar = true,
            topAppBarScrollBehavior = scrollBehavior
        ),
        contentPadding = contentPadding
    ) {
        whitelistSections(
            whitelist = whitelist,
            onAddClick = onAddClick,
            showAddDialog = showAddDialog,
            onDeleteClick = onDeleteClick,
            showDeleteDialog = showDeleteDialog,
            packageToDelete = packageToDelete
        )
    }
}
