package com.edde746.tortuga.presentation

import com.edde746.tortuga.presentation.library.LibraryBody
import com.edde746.tortuga.presentation.library.LibraryRow
import com.edde746.tortuga.presentation.library.LibraryUiState
import com.edde746.tortuga.presentation.profiles.ProfilePickerBody
import com.edde746.tortuga.presentation.profiles.ProfilePickerUiState
import com.edde746.tortuga.presentation.profiles.ProfileTile
import com.edde746.tortuga.presentation.settings.HomeRowSetting
import com.edde746.tortuga.presentation.settings.HomeSettingsBody
import com.edde746.tortuga.presentation.settings.HomeSettingsUiState

/** The items the screen lists: its [LibraryBody.Grid]'s, else none. */
internal val LibraryUiState.items: List<LibraryRow> get() = (body as? LibraryBody.Grid)?.items.orEmpty()

/** More items match than [items]. */
internal val LibraryUiState.hasMore: Boolean get() = (body as? LibraryBody.Grid)?.hasMore == true

/** The tiles the picker shows: its [ProfilePickerBody.Tiles]', else none. */
internal val ProfilePickerUiState.profiles: List<ProfileTile> get() = (body as? ProfilePickerBody.Tiles)?.profiles.orEmpty()

/** The rows the page arranges: its [HomeSettingsBody.Rows]', else none. */
internal val HomeSettingsUiState.rows: List<HomeRowSetting> get() = (body as? HomeSettingsBody.Rows)?.rows.orEmpty()
