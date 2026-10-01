package io.github.munkchunk.passportreader.sample.ui.theme

import androidx.compose.ui.graphics.Color

// Brand. Deliberately deep and quiet: the app wears this everywhere, so it must
// not compete with the green that means "this check passed". See docs/sample-app.md.
val BrandGreen = Color(0xFF1B4332)
val BrandGreenDark = Color(0xFF2D6A4F)
val OnBrand = Color(0xFFF4F7F4)
val OnBrandDark = Color(0xFFEAF3EE)

// Brand as text: text buttons, focused fields, the active reading stage. In
// light mode the brand itself; in dark mode the brand green is only right as a
// fill (2.3:1 as text on a panel), so a lighter, greyer tone of it. Kept
// greyer than PassDark so words in it are not mistaken for a passed check.
val BrandTextLight = BrandGreen
val BrandTextDark = Color(0xFF8DBFA5)

// Grounds and surfaces. Neutrals are biased slightly green rather than pure
// grey, so they sit with the accent instead of beside it. Panels are lighter than
// the ground in both themes. MutedLight on GroundLight is 4.51:1, just over AA:
// darkening the ground any further breaks it.
//
// Line is for decoration (dividers, image frames); Border is for a boundary
// someone has to see, such as a text field's, and is at least 3:1.
val GroundLight = Color(0xFFE6ECE8)
val SurfaceLight = Color(0xFFF3F6F4)
val PanelLight = Color(0xFFFFFFFF)
val InkLight = Color(0xFF16201A)
val MutedLight = Color(0xFF5E6E64)
val LineLight = Color(0xFFC9D4CD)
val BorderLight = Color(0xFF758479)

val GroundDark = Color(0xFF101512)
val SurfaceDark = Color(0xFF182019)
val PanelDark = Color(0xFF1F2A23)
val InkDark = Color(0xFFE6EDE8)
val MutedDark = Color(0xFF9BAAA1)
val LineDark = Color(0xFF2C3830)
val BorderDark = Color(0xFF6B7C71)

// Verdicts. Reserved: these appear on verification outcomes and nowhere else.
val PassLight = Color(0xFF13773A)
val PassContainerLight = Color(0xFFE4F3E9)
val FailLight = Color(0xFFB3261E)
val FailContainerLight = Color(0xFFFBE9E7)
val UnknownLight = Color(0xFF8A5A00)
val UnknownContainerLight = Color(0xFFFAF0DC)
val UnknownEdgeLight = Color(0xFFD9A93A)
val AbsentLight = Color(0xFF5A695F)
val AbsentContainerLight = Color(0xFFE8EDE9)

val PassDark = Color(0xFF4ADE80)
val PassContainerDark = Color(0xFF17301F)
val FailDark = Color(0xFFFF8A80)
val FailContainerDark = Color(0xFF351B19)
val UnknownDark = Color(0xFFE3B341)
val UnknownContainerDark = Color(0xFF332A14)
val UnknownEdgeDark = Color(0xFF8A6A1E)
val AbsentDark = Color(0xFF8C9A91)
val AbsentContainerDark = Color(0xFF222C26)

// Illustration only - the passport drawn on the reading screen. Kept apart from
// the palette above: this is a picture of a document, not part of the app's
// identity, and it must not shift if the theme does. Navy and gold because that
// is what a passport looks like to most people, and because a navy document on
// a green app reads as a separate object rather than more chrome.
val PassportCover = Color(0xFF22304F)
val PassportCoverDark = Color(0xFF1A2540)
val PassportGold = Color(0xFFD9C38A)
