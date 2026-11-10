package com.adong.adchat.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Shapes taken from the native visual sample; the composer and island keep their own geometry. */
object AsterShape {
    val icon = RoundedCornerShape(10.dp)
    val control = RoundedCornerShape(14.dp)
    val content = RoundedCornerShape(16.dp)
    val card = RoundedCornerShape(20.dp)
    val editor = RoundedCornerShape(24.dp)
    val sheet = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val pill = CircleShape
}
