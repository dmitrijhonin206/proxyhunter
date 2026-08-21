package com.proxyhunter.telegram.ui.screens.proxylist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.proxyhunter.telegram.domain.model.ProxyFilter
import com.proxyhunter.telegram.domain.model.ProxyProtocol
import com.proxyhunter.telegram.domain.model.SortOption

// Панель фильтров под топ-баром: поиск по IP/порту, протокол, статус, избранное,
// сортировка. Каждое изменение сразу пробрасывается в ViewModel через onFilterChange —
// сам фильтр применяется на уровне SQL-запроса в ProxyDao.observeProxies.
@Composable
fun FilterBar(
    filter: ProxyFilter,
    onFilterChange: (ProxyFilter) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = { onFilterChange(filter.copy(query = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Поиск по IP или порту") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )

        Spacer(Modifier.height(8.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = filter.onlyWorking,
                    onClick = { onFilterChange(filter.copy(onlyWorking = !filter.onlyWorking)) },
                    label = { Text("Только рабочие") },
                )
            }
            item {
                FilterChip(
                    selected = filter.onlyFavorites,
                    onClick = { onFilterChange(filter.copy(onlyFavorites = !filter.onlyFavorites)) },
                    label = { Text("Избранное") },
                )
            }
            items(ProxyProtocol.entries.toList()) { protocol ->
                FilterChip(
                    selected = filter.protocol == protocol,
                    onClick = {
                        val newProtocol = if (filter.protocol == protocol) null else protocol
                        onFilterChange(filter.copy(protocol = newProtocol))
                    },
                    label = { Text(protocol.name) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SortOption.entries.toList()) { option ->
                FilterChip(
                    selected = filter.sortBy == option,
                    onClick = { onFilterChange(filter.copy(sortBy = option)) },
                    label = { Text(sortLabel(option)) },
                )
            }
        }
    }
}

private fun sortLabel(option: SortOption) = when (option) {
    SortOption.LATENCY -> "По скорости"
    SortOption.ADDED_DATE -> "По дате"
    SortOption.COUNTRY -> "По стране"
}
