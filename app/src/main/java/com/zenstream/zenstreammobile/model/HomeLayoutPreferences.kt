package com.zenstream.zenstreammobile.model

import java.util.Locale

data class HomeLayoutPreferences(
    val order: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
)

fun homeSectionId(row: MediaRow): String =
    when (row.title) {
        RowTitle.ContinueWatching -> "home:continueWatching"
        RowTitle.NextUp -> "home:nextUp"
        RowTitle.Recommendations -> "home:recommendations"
        RowTitle.MyList -> "home:favorites"
        RowTitle.FavoriteMusic -> "home:favoriteMusic"
        RowTitle.Genre -> "home:genre:${row.label.orEmpty().trim().lowercase(Locale.ROOT)}"
        RowTitle.NewlyAdded,
        RowTitle.TopRated -> {
            val library = row.libraryId ?: "name:${row.libraryName.orEmpty()}"
            val titleKey = if (row.title == RowTitle.NewlyAdded) "newlyAddedOn" else "topRated"
            "home:library:$library:$titleKey"
        }
    }

fun orderHomeRows(
    rows: List<MediaRow>,
    preferences: HomeLayoutPreferences,
    includeHidden: Boolean = false,
): List<MediaRow> {
    val defaultRows = orderedHomeRows(rows).distinctBy(::homeSectionId)
    val byId = defaultRows.associateBy(::homeSectionId)
    val savedOrder = preferences.order.distinct()
    val savedOrderSet = savedOrder.toSet()
    val ordered =
        savedOrder.mapNotNull(byId::get) +
            defaultRows.filter { homeSectionId(it) !in savedOrderSet }
    if (includeHidden) return ordered
    val hidden = preferences.hidden.toSet()
    return ordered.filter { homeSectionId(it) !in hidden }
}

fun reorderHomeLayoutPreferences(
    preferences: HomeLayoutPreferences,
    availableIds: List<String>,
    reorderedIds: List<String>,
): HomeLayoutPreferences {
    val available = availableIds.toSet()
    val reordered = reorderedIds.distinct().filter(available::contains)
    val order = preferences.order.distinct().toMutableList()
    availableIds.forEach { if (it !in order) order += it }
    var nextIndex = 0
    val merged = order.map { id ->
        if (id in available) reordered.getOrNull(nextIndex++) ?: id else id
    }
    return preferences.copy(order = merged.distinct())
}

fun setHomeSectionHidden(
    preferences: HomeLayoutPreferences,
    id: String,
    hidden: Boolean,
): HomeLayoutPreferences {
    val next = preferences.hidden.toMutableSet()
    if (hidden) next += id else next -= id
    return preferences.copy(hidden = next.toList())
}
