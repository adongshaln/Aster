package com.adong.adchat.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** Keeps StoryScreen's horizontal + bottom error-banner padding explicit and compilable. */
internal fun Modifier.padding(horizontal: Dp, bottom: Dp): Modifier =
    this.padding(start = horizontal, end = horizontal, bottom = bottom)
