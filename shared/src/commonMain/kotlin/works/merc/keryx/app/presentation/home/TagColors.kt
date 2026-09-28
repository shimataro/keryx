package works.merc.keryx.app.presentation.home

/**
 * Selectable tag colors, shared by every UI's tag color picker (Compose's `TagColorPicker.kt`, the
 * Apple app's `NamePromptSheet.swift`) so a color picked on one platform shows as selected in the
 * other's picker too, and so a newly created tag defaults to no color on both. Chosen to stay clear
 * of the app's Teal-based theme palette (`Teal`/`TealLight` in `ui/theme/KeryxTheme.kt`) so a tag
 * dot never reads as "the same color as a selected row".
 */
val TAG_COLOR_PALETTE: List<String> = listOf(
    "#E53935", // red
    "#FB8C00", // orange
    "#FDD835", // amber
    "#43A047", // green
    "#1E88E5", // blue
    "#5E35B1", // indigo
    "#8E24AA", // purple
    "#D81B60", // pink
)

/** The swatch color shown for a tag with no color assigned (`tags.color == null`). */
const val TAG_COLOR_NONE_HEX = "#9E9E9E"
